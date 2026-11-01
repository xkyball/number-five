#!/usr/bin/env python3
"""Deterministic local wheel: read preserved host once, add small host extensions."""
from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
IOT = REPO.parent
SCOPE = Path("/Users/chris/projects/max/openecho-hardware-20261009")
NAME = "no5_sonard_openecho"
VERSION = "0.4.31"
WHEEL_NAME = f"{NAME}-{VERSION}-py3-none-any.whl"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def compose():
    intake = REPO / "projects/no5-sonard"
    inventory = json.loads((intake / "source-inventory.json").read_text())
    original = {}
    for archive in inventory["archives"]:
        for entry in archive["files"]:
            if entry["disposition"] != "retained":
                continue
            relative = entry["retained_path"]
            data = (intake / relative).read_bytes()
            if sha(data) != entry["sha256"]:
                raise ValueError(f"Original intake changed: {relative}")
            if relative.startswith("m1/src/"):
                original[relative.removeprefix("m1/src/")] = data
    handover = json.loads((IOT / "SONARD_OSZI_HANDOVER.json").read_text())
    changed = {row["original_git_path"].split("/m1/src/")[1]: row
               for row in handover["six_modified_files"]}
    if len(changed) != 6 or len(original) != 20:
        raise ValueError("Unexpected original/modified source inventory")
    files, provenance = {}, []
    for relative, base in original.items():
        source = SCOPE / "sonard-link/host" / relative
        data = source.read_bytes()
        expected = changed[relative]["current_sha256"] if relative in changed else sha(base)
        if sha(data) != expected:
            raise ValueError(f"Preserved OpenEcho host changed: {relative}")
        files[relative] = data
        provenance.append({"member": relative, "input": str(source),
                           "input_sha256": sha(data), "original_sha256": sha(base),
                           "preserved_openecho_delta": relative in changed})
    # Derive only dependency factories; original six changes remain in the input.
    # No copied source tree, source overwrite, firmware or shared-venv mutation.
    replacements = [
        ("from .pico.client import PicoClient",
         "from no5_sonard_openecho.wire import VerifiedClient as PicoClient"),
        ("from .transport.serial_posix import PosixSerialTransport, SerialDisconnected",
         "from no5_sonard_openecho.wire import ObservedTransport as PosixSerialTransport, SerialDisconnected"),
        ("transport = PosixSerialTransport(self.config.device)",
         'transport = PosixSerialTransport(self.config.device, audit_root=self._session_path / "wire" / str(uuid.uuid4()), expected_identity=getattr(self, "expected_identity", None))'),
    ]
    source = files["no5_sonard/service.py"].decode()
    resampler_import = 'from .processing_m1 import detect_bottom_m1, max_pool_resample\n'
    if source.count(resampler_import) != 1:
        raise ValueError('Preserved resampler import contract changed')
    source = source.replace(resampler_import,
        'from .processing_m1 import detect_bottom_m1\n'
        'from no5_sonard_openecho.resample import max_pool_resample\n')
    for old, new in replacements:
        if source.count(old) != 1:
            raise ValueError(f"Service factory contract changed: {old}")
        source = source.replace(old, new)
    tx_gate = """        valid_tx = (self.config.tx_reference_verified and bool(ping.ping_flags & 1)
                    and not (ping.ping_flags & invalid_flags)
                    and 0 <= ping.tx_zero_sample_index < ping.samples.size)
"""
    tx_gate_replacement = """        empirical_trigger = (getattr(self.config, \"empirical_trigger_fallback\", False)
                             and self.config.tx_reference_verified
                             and not bool(ping.ping_flags & 1)
                             and bool(ping.ping_flags & (1 << 3))
                             and 0 <= ping.tx_zero_sample_index < ping.samples.size)
        invalid_for_origin = (invalid_flags & ~(1 << 5)) if empirical_trigger else invalid_flags
        valid_tx = (self.config.tx_reference_verified
                    and (bool(ping.ping_flags & 1) or empirical_trigger)
                    and not (ping.ping_flags & invalid_for_origin)
                    and 0 <= ping.tx_zero_sample_index < ping.samples.size)
"""
    if source.count(tx_gate) != 1:
        raise ValueError("Service TX gate contract changed")
    source = source.replace(tx_gate, tx_gate_replacement)
    display_flags = "            flags=ping.ping_flags if valid_tx else (ping.ping_flags & ~1) | (1 << 5),\n"
    display_flags_replacement = "            flags=(((ping.ping_flags | 1 | (1 << 7)) & ~(1 << 5)) if empirical_trigger else ping.ping_flags) if valid_tx else (ping.ping_flags & ~1) | (1 << 5),\n"
    if source.count(display_flags) != 1:
        raise ValueError("Service echogram TX flag contract changed")
    source = source.replace(display_flags, display_flags_replacement)
    # Archive and legacy processing still run for every raw PING. The derived
    # host publishes only after same-PING analysis and source selection exist.
    publish = "        await self.hub.publish(frame)\n"
    if source.count(publish) != 1:
        raise ValueError("Service echogram publication contract changed")
    source = source.replace(publish,
        "        if hasattr(self, '_stage_echogram'):\n"
        "            await self._stage_echogram(ping, frame)\n"
        "        else:\n"
        "            await self.hub.publish(frame)\n")
    anchor = "        writer.append(record)\n"
    if source.count(anchor) != 1:
        raise ValueError("Durable raw append contract changed")
    source = source.replace(anchor,
        "        from no5_sonard_openecho.archive_io import append_raw\n"
        "        await append_raw(writer, record)\n")
    start = source.index("        writer = self._writer\n", source.index("    async def _handle_ping"))
    end = source.index("        # Archive every valid packet.", start)
    archive_block = source[start:end]
    source = (source[:start] + "        if getattr(self, 'archive_enabled', True):\n"
              + ''.join('    '+line if line.strip() else line for line in archive_block.splitlines(True))
              + source[end:])
    files["no5_sonard/service.py"] = source.encode()
    # The Pico firmware already implements CONFIG_SET.reserved bit 0 as an
    # opt-in TX-marker request.  Derive that small protocol extension only in
    # this wheel so the six accepted Sonard source deltas remain untouched.
    config = files["no5_sonard/config.py"].decode()
    config_anchor = "    tx_reference_verified: bool = False\n"
    if config.count(config_anchor) != 1:
        raise ValueError("ServiceConfig TX-reference contract changed")
    config = config.replace(config_anchor, config_anchor +
                            "    tx_marker_requested: bool = False\n"
                            "    empirical_trigger_fallback: bool = False\n"
                            "    tx_reference_basis: str = \"unverified\"\n")
    files["no5_sonard/config.py"] = config.encode()
    protocol = files["no5_sonard/pico_protocol.py"].decode()
    protocol_replacements = [
        ("    capture_timeout_us: int\n\n\n@dataclass(slots=True, frozen=True)\nclass ConfigAck:",
         "    capture_timeout_us: int\n    reserved: int = 0\n\n\n@dataclass(slots=True, frozen=True)\nclass ConfigAck:"),
        ("                                  value.capture_timeout_us, 0)\n",
         "                                  value.capture_timeout_us, value.reserved)\n"),
        ("return ConfigSet(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])\n",
         "return ConfigSet(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])\n"),
    ]
    for old, new in protocol_replacements:
        if protocol.count(old) != 1:
            raise ValueError(f"Pico protocol marker contract changed: {old!r}")
        protocol = protocol.replace(old, new)
    anchor = "    ERROR = 0x7F\n"
    extension = "    STREAM_FRAME = 0x22\n    TX_CONFIG = 0x40\n    TX_ARM = 0x41\n    TX_FIRE = 0x42\n    TX_ABORT = 0x43\n    TX_ACK = 0x44\n    TX_EVENT = 0x45\n    TX_CLIP_CONFIG = 0x46\n"
    if protocol.count(anchor) != 1:
        raise ValueError("TX message namespace contract changed")
    extension += "    TX_DEPTH_CONFIG = 0x47\n"
    protocol = protocol.replace(anchor, extension + anchor)
    decoder_boundary = '\n\ndef encode_packet(packet: Packet) -> bytes:\n'
    if protocol.count(decoder_boundary) != 1:
        raise ValueError('Preserved COBS decoder boundary changed')
    protocol = protocol.replace(decoder_boundary,
        '\n\n_cobs_decode_reference = cobs_decode\n'
        'from no5_sonard_openecho.cobs_codec import decode as _fast_cobs_decode\n'
        '\ndef cobs_decode(data: bytes) -> bytes:\n'
        '    return _fast_cobs_decode(data, _cobs_decode_reference)\n'
        + decoder_boundary)
    files["no5_sonard/pico_protocol.py"] = protocol.encode()
    for path in sorted((ROOT / "src").rglob("*.py")):
        files[path.relative_to(ROOT / "src").as_posix()] = path.read_bytes()
    dist = f"{NAME}-{VERSION}.dist-info"
    files[f"{dist}/METADATA"] = (
        f"Metadata-Version: 2.1\nName: no5-sonard-openecho\nVersion: {VERSION}\n"
        "Summary: OpenEcho PING host with preserved M1 processing and archive\n"
        "Requires-Python: >=3.11\nRequires-Dist: numpy>=2,<3\n"
        "Requires-Dist: scipy>=1.14,<2\n"
        "Requires-Dist: pandas>=2.2,<3\n"
        "Requires-Dist: filterpy>=1.4.5,<2\n"
        "Requires-Dist: trackpy>=0.6,<1\n"
        "Requires-Dist: fastapi>=0.115,<1\nRequires-Dist: uvicorn>=0.30,<1\nRequires-Dist: websockets>=12,<16\n"
        "Provides-Extra: fast-cobs\nRequires-Dist: cobs==1.2.2; extra == 'fast-cobs'\n\n"
    ).encode()
    files[f"{dist}/WHEEL"] = b"Wheel-Version: 1.0\nGenerator: no5-local-wheel/1\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
    files[f"{dist}/entry_points.txt"] = b"[console_scripts]\nno5-sonard-openecho = no5_sonard_openecho.__main__:main\nno5-sonard-dataset = no5_sonard_openecho.dataset:main\n"
    manifest = {"schema": "no5.sonard-host-source/1", "version": VERSION,
                "inputs": provenance, "wheel_only_service_derivation": replacements,
                "wheel_only_marker_derivation": ["ServiceConfig marker basis", "CONFIG_SET reserved bit 0", "empirical trigger fallback"],
                "wheel_only_detection_derivation": ["SciPy robust CFAR/peak geometry", "post-TX direct-envelope gate for broad returns", "trackpy association", "FilterPy state tracks", "rules_v1 classes", "derived JSONL/labels/dataset APIs"],
                "wheel_only_display_derivation": ["stage frame until same-PING analysis", "all/own/native presentation selection; archive all raw PINGs", "NumPy/SciPy local_adc_background_v3 + repeated_rc_impulse_v1; optional physical near-field exclusion before derived detection/filtering; full raw capture and TX parameters retained"],
                "wheel_only_context_derivation": ["additive read-only /ws/v1/echogram-context", "N5EC v1: metadata<=4096, original M1<=2088, envelope<=6200 bytes", "separate q4 hub; same-PING frozen Published evidence; legacy/HTTP/raw/ADC/DSP/TX unchanged"],
                "preserved_modified_input_files": 6,
                "unmodified_original_intake": True,
                "wheel_only_cpu_derivation": ["both service imports use byte-equivalent NumPy reduceat resampler", "COBS decoder delegates through compatibility adapter; original encoder, decoder reference, packet/CRC checks retained", "optional reviewed cobs 1.2.2 native extension", "trackpy0.7 rolling-window linkage through equivalent NumPy input/anchor management; same Linker, memory and bounds"],
                "wheel_only_transmit_derivation": ["additive types 0x40..0x45", "finite PIO TX API and physical marker/PING association"],
                "firmware_included": False, "second_source_checkout": False}
    files[f"{dist}/source-provenance.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    record_name = f"{dist}/RECORD"
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    for path, data in sorted(files.items()):
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        writer.writerow([path, "sha256=" + digest, len(data)])
    writer.writerow([record_name, "", ""])
    files[record_name] = output.getvalue().encode()
    return files, manifest


def build(output):
    files, manifest = compose()
    output.mkdir(parents=True, exist_ok=True)
    path = output / WHEEL_NAME
    blob = io.BytesIO()
    with zipfile.ZipFile(blob, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 12, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    data = blob.getvalue()
    if path.exists() and path.read_bytes() != data:
        raise FileExistsError(f"Different package already at {path}; use a new output directory")
    if not path.exists():
        with path.open("xb") as output_file:
            output_file.write(data)
    return {"wheel": str(path.resolve()), "sha256": sha(data), "bytes": len(data),
            "members": len(files), "source": manifest}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/final")
    print(json.dumps(build(parser.parse_args().output), indent=2))

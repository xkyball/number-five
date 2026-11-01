from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import struct
import tempfile
import zlib

import numpy as np


_PENDING_PREFIX = struct.Struct("<QqQIiIHHI")
_PENDING_LEN = struct.Struct("<I")
_PENDING_CRC = struct.Struct("<I")


@dataclass(slots=True, frozen=True)
class RawPingRecord:
    ping_id: int
    time_utc_ns: int
    trigger_device_time_us: int
    sample_period_ns: int
    tx_zero_sample_index: int
    ping_flags: int
    adc_min: int
    adc_max: int
    samples: np.ndarray


class RawZarrV2Writer:
    """Append-only Zarr v2 directory writer for fixed-geometry ping segments.

    It writes standard Zarr v2 metadata/chunks directly and keeps a small
    fsync'd pending WAL for the currently uncommitted chunk.
    """

    ARRAY_SPECS = {
        "samples": ("<u2", 0),
        "ping_id": ("<u8", 0),
        "trigger_device_time_us": ("<u8", 0),
        "time_utc_ns": ("<i8", 0),
        "ping_flags": ("<u4", 0),
        "tx_zero_sample_index": ("<i4", -1),
        "adc_min": ("<u2", 0),
        "adc_max": ("<u2", 0),
    }

    def __init__(self, path: Path, *, sample_count: int, sample_period_ns: int,
                 chunk_pings: int = 16, attrs: dict | None = None, zlib_level: int = 1) -> None:
        self.path = Path(path)
        self.sample_count = int(sample_count)
        self.sample_period_ns = int(sample_period_ns)
        self.chunk_pings = int(chunk_pings)
        self.zlib_level = int(zlib_level)
        if self.sample_count <= 0 or self.chunk_pings <= 0:
            raise ValueError("invalid Zarr geometry")
        self._buffer: list[RawPingRecord] = []
        self._count = 0
        self._pending_start_row = 0
        self._closed = False
        self.path.mkdir(parents=True, exist_ok=True)
        self._initialize(attrs or {})
        self._recover_pending()

    @property
    def count(self) -> int:
        return self._count

    @property
    def pending_count(self) -> int:
        return len(self._buffer)

    def _atomic_write(self, path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)

    def _write_json(self, path: Path, value: dict) -> None:
        self._atomic_write(path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode())

    def _initialize(self, attrs: dict) -> None:
        group_file = self.path / ".zgroup"
        manifest_file = self.path / "manifest.json"
        if not group_file.exists():
            self._write_json(group_file, {"zarr_format": 2})
            self._write_json(self.path / ".zattrs", attrs)
            self._write_json(manifest_file, {"committed_rows": 0, "sample_count": self.sample_count,
                                             "sample_period_ns": self.sample_period_ns,
                                             "chunk_pings": self.chunk_pings})
            for name, (dtype, fill) in self.ARRAY_SPECS.items():
                self._create_array(name, dtype, fill)
        else:
            manifest = json.loads(manifest_file.read_text())
            if manifest["sample_count"] != self.sample_count or manifest["sample_period_ns"] != self.sample_period_ns:
                raise ValueError("existing Zarr segment geometry does not match")
            if manifest["chunk_pings"] != self.chunk_pings:
                raise ValueError("existing Zarr chunk geometry does not match")
            self._count = int(manifest["committed_rows"])
            self._repair_shapes(self._count)
        self._pending_start_row = self._count

    def _create_array(self, name: str, dtype: str, fill_value: int) -> None:
        p = self.path / name
        p.mkdir(parents=True, exist_ok=True)
        shape = [0, self.sample_count] if name == "samples" else [0]
        chunks = [self.chunk_pings, self.sample_count] if name == "samples" else [self.chunk_pings]
        self._write_json(p / ".zarray", {
            "chunks": chunks,
            "compressor": {"id": "zlib", "level": self.zlib_level},
            "dtype": dtype,
            "fill_value": fill_value,
            "filters": None,
            "order": "C",
            "shape": shape,
            "zarr_format": 2,
        })
        self._write_json(p / ".zattrs", {})

    def _repair_shapes(self, rows: int) -> None:
        for name in self.ARRAY_SPECS:
            p = self.path / name / ".zarray"
            meta = json.loads(p.read_text())
            desired = [rows, self.sample_count] if name == "samples" else [rows]
            if meta.get("shape") != desired:
                meta["shape"] = desired
                self._write_json(p, meta)

    @property
    def _pending_path(self) -> Path:
        return self.path / "_pending.bin"

    @property
    def _pending_meta_path(self) -> Path:
        return self.path / "_pending.json"

    def _append_pending(self, r: RawPingRecord) -> None:
        samples = np.asarray(r.samples, dtype="<u2")
        if samples.size != self.sample_count:
            raise ValueError("sample count mismatch")
        body = _PENDING_PREFIX.pack(r.ping_id, r.time_utc_ns, r.trigger_device_time_us,
                                    r.sample_period_ns, r.tx_zero_sample_index, r.ping_flags,
                                    r.adc_min, r.adc_max, samples.size) + samples.tobytes(order="C")
        record = body + _PENDING_CRC.pack(zlib.crc32(body) & 0xFFFFFFFF)
        if not self._pending_meta_path.exists():
            self._write_json(self._pending_meta_path, {"start_row": self._pending_start_row})
        with self._pending_path.open("ab", buffering=0) as fh:
            fh.write(_PENDING_LEN.pack(len(record)))
            fh.write(record)
            os.fsync(fh.fileno())

    def _read_pending(self) -> list[RawPingRecord]:
        if not self._pending_path.exists():
            return []
        data = self._pending_path.read_bytes()
        out: list[RawPingRecord] = []
        off = 0
        while off + _PENDING_LEN.size <= len(data):
            length = _PENDING_LEN.unpack_from(data, off)[0]
            off += _PENDING_LEN.size
            if off + length > len(data):
                break  # torn final record: ignore it
            record = data[off:off + length]
            off += length
            if len(record) < _PENDING_PREFIX.size + _PENDING_CRC.size:
                break
            body, crc_bytes = record[:-4], record[-4:]
            if (zlib.crc32(body) & 0xFFFFFFFF) != _PENDING_CRC.unpack(crc_bytes)[0]:
                break
            v = _PENDING_PREFIX.unpack_from(body)
            sample_count = v[8]
            expected = _PENDING_PREFIX.size + sample_count * 2
            if len(body) != expected or sample_count != self.sample_count:
                break
            samples = np.frombuffer(body, dtype="<u2", count=sample_count,
                                    offset=_PENDING_PREFIX.size).copy()
            out.append(RawPingRecord(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], samples))
        return out

    def _recover_pending(self) -> None:
        if not self._pending_meta_path.exists():
            return
        try:
            start_row = int(json.loads(self._pending_meta_path.read_text())["start_row"])
        except Exception:
            return
        if self._count > start_row:
            # Manifest commit won; pending truncate was interrupted.
            self._clear_pending()
            self._pending_start_row = self._count
            return
        if self._count != start_row:
            raise RuntimeError("pending WAL start row is inconsistent with manifest")
        recovered = self._read_pending()
        self._buffer.extend(recovered)
        if len(self._buffer) >= self.chunk_pings:
            self.flush()

    def append(self, record: RawPingRecord) -> None:
        if self._closed:
            raise RuntimeError("writer is closed")
        if record.sample_period_ns != self.sample_period_ns:
            raise ValueError("sample period mismatch")
        self._append_pending(record)
        self._buffer.append(record)
        if len(self._buffer) >= self.chunk_pings:
            self.flush()

    def _chunk_bytes(self, name: str, rows: list[RawPingRecord]) -> bytes:
        dtype = np.dtype(self.ARRAY_SPECS[name][0])
        fill = self.ARRAY_SPECS[name][1]
        if name == "samples":
            arr = np.full((self.chunk_pings, self.sample_count), fill, dtype=dtype)
            for i, r in enumerate(rows):
                arr[i, :] = np.asarray(r.samples, dtype=dtype)
        else:
            arr = np.full((self.chunk_pings,), fill, dtype=dtype)
            for i, r in enumerate(rows):
                value = {
                    "ping_id": r.ping_id,
                    "trigger_device_time_us": r.trigger_device_time_us,
                    "time_utc_ns": r.time_utc_ns,
                    "ping_flags": r.ping_flags,
                    "tx_zero_sample_index": r.tx_zero_sample_index,
                    "adc_min": r.adc_min,
                    "adc_max": r.adc_max,
                }[name]
                arr[i] = value
        return zlib.compress(arr.tobytes(order="C"), self.zlib_level)

    def flush(self) -> None:
        if not self._buffer:
            return
        # A pending chunk cannot exceed chunk_pings because append flushes at the boundary.
        rows = self._buffer[:self.chunk_pings]
        chunk_index = self._count // self.chunk_pings
        if self._count % self.chunk_pings != 0:
            raise RuntimeError("manifest row count is not on a chunk boundary")
        for name in self.ARRAY_SPECS:
            key = f"{chunk_index}.0" if name == "samples" else str(chunk_index)
            self._atomic_write(self.path / name / key, self._chunk_bytes(name, rows))
        new_count = self._count + len(rows)
        self._repair_shapes(new_count)
        manifest = json.loads((self.path / "manifest.json").read_text())
        manifest["committed_rows"] = new_count
        self._write_json(self.path / "manifest.json", manifest)
        self._count = new_count
        del self._buffer[:len(rows)]
        self._clear_pending()
        self._pending_start_row = self._count
        # If >chunk_pings records were restored from a WAL, rewrite remainder to a new WAL.
        if self._buffer:
            remaining = list(self._buffer)
            self._buffer.clear()
            for r in remaining:
                self._append_pending(r)
                self._buffer.append(r)

    def _clear_pending(self) -> None:
        for p in (self._pending_path, self._pending_meta_path):
            try:
                p.unlink()
            except FileNotFoundError:
                pass

    def close(self) -> None:
        if self._closed:
            return
        self.flush()
        self._closed = True

    def __enter__(self) -> "RawZarrV2Writer":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

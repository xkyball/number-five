from __future__ import annotations

import json
import asyncio
import logging
import os
from pathlib import Path
import tempfile
import time
import uuid
import weakref
from collections import deque
from dataclasses import asdict
from .detection import DetectionPipeline, DetectionPolicy, DetectionResult, RULE_CLASSES

from no5_sonard.service import SonarService
from no5_sonard.pico_protocol import ConfigSet
from no5_sonard.transport.serial_posix import SerialDisconnected
from . import __version__
from .wire import ObservedTransport, VerifiedClient
from .transmit import TransmitController
from .noise_reduction import NoiseReduction
from .resample import max_pool_resample
from no5_sonard.protocol import ECHOGRAM_HEADER_LEN, unpack_echogram_frame, pack_echogram_frame
from .display_window import display_window
from .echogram_context import pack_context
from .archive_fault import ArchiveFault
from no5_sonard.hub import LatestFrameHub

COUNTERS = ("packet_seq_gaps", "ping_id_gaps", "queue_drops", "frame_errors",
            "crc_errors", "bytes_rx", "bytes_tx")


def client_counters(client):
    return {"packet_seq_gaps": client.packet_seq_gaps,
            "ping_id_gaps": client.ping_id_gaps,
            "queue_drops": client.missed_ping_queue,
            **{key: getattr(client.transport, key) for key in
               ("frame_errors", "crc_errors", "bytes_rx", "bytes_tx")}}


class HostService(SonarService):
    """Existing processing/archive/UI with durable counters and shutdown receipt."""

    def _ensure_session(self):
        if self.archive_enabled:
            return super()._ensure_session()
        if self.status.session_id is None:
            self.status.session_id = str(uuid.uuid4())

    def _open_segment(self, device_serial, sample_count, sample_period_ns):
        if not self.archive_enabled:
            self.status.segment_id = str(uuid.uuid4())
            self._published_pings.clear()
            self._publication_sequence = 0
            return
        super()._open_segment(device_serial, sample_count, sample_period_ns)
        # Cursor and header describe this USB segment only. A fresh segment
        # may reuse PING IDs; never return older rows under the new identity.
        self._published_pings.clear()
        self._publication_sequence = 0
        from .depth_rate import DepthRateController
        if isinstance(self.transmit, DepthRateController):
            from .geometry_archive import GeometryArchive
            if self.config.zarr_chunk_pings>=256:
                from .rotating_archive import RotatingGeometryArchive
                self._writer=RotatingGeometryArchive(self._writer,
                    journal_root=self._capture_journal_root,volume_guard=self._data_volume_guard)
            else:
                self._writer=GeometryArchive(self._writer)

    def __init__(self, config, *, expected_identity=None, detection_policy=None, transmit_profile=None,
                 display_ping_source="all", noise_reduction_mode=None,
                 capture_journal_root=None, data_volume_guard=None, control_root=None, archive_enabled=False, test_archive_root=None, operational_receipt_path=None, cobs_decoder='auto'):
        from .cobs_codec import configure
        configure(cobs_decoder)
        if display_ping_source not in ("all", "own", "native"):
            raise ValueError("display ping source must be all, own or native")
        if getattr(config, "tx_reference_verified", False):
            basis = getattr(config, "tx_reference_basis", "")
            accepted = basis == "empirical_marker_aba_20261012" or (
                basis == "owned_marker_aba_v1" and transmit_profile is not None
                and transmit_profile.depth_reference_verified) or (
                basis == "hardware_gp16_transducer" and not getattr(config,"empirical_trigger_fallback",False))
            if not getattr(config, "tx_marker_requested", False) or not accepted:
                raise ValueError("TX reference requires a measured marker A-B-A basis")
        if type(archive_enabled) is not bool:
            raise ValueError('archive_enabled must be boolean')
        if not archive_enabled and capture_journal_root is not None:
            raise ValueError('capture journal requires explicit test archive mode')
        self.operational_receipt_path = operational_receipt_path
        self.test_recorder = None
        self._test_archive_root = test_archive_root
        self.archive_enabled = archive_enabled
        super().__init__(config)
        self._capture_journal_root=capture_journal_root
        self._data_volume_guard=data_volume_guard
        self.control_root = Path(control_root or config.storage_root)
        self._hold_path = self.control_root / 'archive-hold.json'
        self._hold_task = None
        self.expected_identity = expected_identity
        self._completed_clients = weakref.WeakSet()
        self._totals = dict.fromkeys(COUNTERS, 0)
        self._connections = []
        self._last_config = None
        self._last_device = None
        self._last_clock = None
        self._last_startup_sync = None
        self._archived_total = 0
        self._last_raw_host_at = None
        self._clipped_total = 0
        self.final_status_path = None
        # Set only after STOP has completed (or timed out).  The acquisition
        # task then drains packets already accepted by the RX task before its
        # supervisor is allowed to close the writer or serial endpoint.
        self._shutdown_requested = asyncio.Event()
        self._storage_fault = (json.loads(self._hold_path.read_text()).get('reason')
                               if self._hold_path.exists() else None)
        self._cleanup_errors = []
        self._derived_journal = None
        self._analysis_lock = asyncio.Lock()
        self._capture_activity = None
        self.stop_confirmation_timeout_s = 3.0
        self.shutdown_settle_timeout_s = 3.0
        self._shutdown_outcome = None
        self.detection = DetectionPipeline(
            detection_policy or DetectionPolicy(), sound_speed_mps=config.sound_speed_mps)
        self._last_detection: DetectionResult | None = None
        # This is the sole operator-facing interpretation.  It is derived after
        # the raw archive is committed and never feeds back into it.
        self._last_presentation: dict | None = None
        self._presentation_was_locked = False
        self._display_ping_source = display_ping_source
        self._display_generation = 1
        self._selection_generation = 1
        self._display_detection = None
        self._display_presentation = None
        self._staged_frame = None
        self._display_counts = {"received": 0, "published": 0, "hidden": 0,
                                "own": 0, "native": 0}
        self._display_times = deque(maxlen=128)
        self._published_pings = deque(maxlen=512)
        self.context_hub = LatestFrameHub(queue_size=4)
        self._publication_sequence = 0
        self._context_encode_errors = 0
        self._last_context_error = None
        self._noise_policy_path = self.control_root / "noise-reduction.json"
        saved = (json.loads(self._noise_policy_path.read_text())
                 if noise_reduction_mode is None and self._noise_policy_path.exists() else {})
        self.noise_reduction = NoiseReduction(noise_reduction_mode or saved.get("mode", "auto"),
                                              revision=saved.get("revision", 1),
                                              stripe_mode=saved.get("stripe_mode", "auto"),
                                              impulse_mode=saved.get("impulse_mode", "auto"),
                                              near_field_m=saved.get("near_field_m", 0.))
        self._last_noise = self._display_noise = None
        self._last_display_window = self._display_window = None
        self._acquisition_context = {
            "mount": None, "gps": None, "course_deg": None,
            "speed_mps": None, "water": None,
        }
        from .clip_endurance import ClipProfile, ClipEnduranceController
        from .depth_rate import DepthRateProfile, DepthRateController
        self.transmit = (DepthRateController(self, transmit_profile) if isinstance(transmit_profile, DepthRateProfile) else ClipEnduranceController(self, transmit_profile)
                         if isinstance(transmit_profile, ClipProfile)
                         else TransmitController(self, transmit_profile))
        from .recovery import RecoveryController
        self.recovery = RecoveryController(self)

    @staticmethod
    def _valid_tx_reference(config, ping) -> bool:
        """Mirror the preserved host gate before deriving a physical range."""
        invalid_flags = (1 << 2) | (1 << 4) | (1 << 5) | (1 << 6)
        empirical_trigger = (getattr(config, "empirical_trigger_fallback", False)
                             and getattr(config, "tx_reference_verified", False)
                             and not bool(ping.ping_flags & 1)
                             and bool(ping.ping_flags & (1 << 3))
                             and 0 <= ping.tx_zero_sample_index < ping.samples.size)
        invalid_for_origin = (invalid_flags & ~(1 << 5)) if empirical_trigger else invalid_flags
        return bool(getattr(config, "tx_reference_verified", False)
                    and (bool(ping.ping_flags & 1) or empirical_trigger)
                    and not (ping.ping_flags & invalid_for_origin)
                    and 0 <= ping.tx_zero_sample_index < ping.samples.size)

    def set_detection_mode(self, mode: str) -> dict:
        policy = self.detection.set_mode(mode).public()
        self._refresh_presentation()
        return policy

    def update_acquisition_context(self, values: dict) -> dict:
        allowed = set(self._acquisition_context)
        unexpected = set(values) - allowed
        if unexpected:
            raise ValueError("unsupported acquisition context: " + ", ".join(sorted(unexpected)))
        for key, value in values.items():
            if key in {"course_deg", "speed_mps"} and value is not None and not isinstance(value, (int, float)):
                raise ValueError(f"{key} must be numeric or null")
            if key in {"mount", "gps", "water"} and value is not None and not isinstance(value, dict):
                raise ValueError(f"{key} must be an object or null")
        self._acquisition_context.update(values)
        self._persist_acquisition_context()
        return dict(self._acquisition_context)

    def noise_reduction_status(self) -> dict:
        latest = self._last_noise if self._display_ping_source == "all" else self._display_noise
        return {"schema": "no5.sonard-noise-reduction-status/1",
                "policy": self.noise_reduction.policy(), "latest": latest,
                "display_generation": self._display_generation}

    def set_noise_reduction_mode(self, mode, *, stripe_mode=None, impulse_mode=None, near_field_m=None) -> dict:
        stripe_mode = self.noise_reduction.stripe_mode if stripe_mode is None else stripe_mode
        impulse_mode = self.noise_reduction.impulse_mode if impulse_mode is None else impulse_mode
        near_field_m = self.noise_reduction.near_field_m if near_field_m is None else near_field_m
        candidate = NoiseReduction(mode, revision=self.noise_reduction.revision+1,
                                   stripe_mode=stripe_mode, impulse_mode=impulse_mode, near_field_m=near_field_m)
        if (mode == self.noise_reduction.mode and stripe_mode == self.noise_reduction.stripe_mode
                and impulse_mode == self.noise_reduction.impulse_mode
                and near_field_m == self.noise_reduction.near_field_m):
            return self.noise_reduction_status()
        path = self._noise_policy_path
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".noise-")
        try:
            with os.fdopen(fd, "w") as output:
                json.dump(candidate.policy(), output, sort_keys=True)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        if near_field_m != self.noise_reduction.near_field_m:
            self.detection = DetectionPipeline(self.detection.policy, sound_speed_mps=self.config.sound_speed_mps)
            self._last_detection = self._display_detection = None
            self._last_presentation = self._display_presentation = None
            self._presentation_was_locked = False
        self.noise_reduction = candidate
        self._last_noise = self._display_noise = None
        self._last_display_window = self._display_window = None
        self._display_generation += 1
        return self.noise_reduction_status()

    def latest_detection(self) -> dict | None:
        result = self._last_detection if self._display_ping_source == "all" else self._display_detection
        return None if result is None else result.public()

    def display_selection(self) -> dict:
        times = self._display_times
        hz = ((len(times)-1)/(times[-1]-times[0])
              if len(times)>1 and times[-1]>times[0] and time.monotonic()-times[-1]<2 else 0.0)
        return {"schema": "no5.sonard-display-selection/1",
                "source": self._display_ping_source, "generation": self._display_generation,
                "selection_generation": self._selection_generation,
                "counts": dict(self._display_counts), "published_hz": round(hz, 3),
                "last_published_ping_id": (None if self._display_detection is None
                                            else self._display_detection.ping_id),
                # This established field declares the unfiltered recording
                # scope, not whether a recorder is enabled or any row exists.
                "raw_archive_source": "all", "raw_archive_enabled": self.archive_enabled,
                "test_archive_enabled": self.test_recorder is not None,
                "raw_samples_modified": False,
                "own_evidence": "physical TX ledger marker matched to raw PING within 3 us",
                "automatic_native_fallback": False}

    def published_pings(self, after_ping_id=0, limit=64) -> dict:
        if type(after_ping_id) is not int or after_ping_id < 0:
            raise ValueError("after_ping_id must be a nonnegative integer")
        if type(limit) is not int or not 1 <= limit <= 64:
            raise ValueError("limit must be an integer in 1..64")
        rows = list(self._published_pings)
        available = [dict(row) for row in rows if row["ping_id"] > after_ping_id]
        selected = available[:limit]
        oldest = rows[0]["ping_id"] if rows else None
        return {"schema": "no5.sonard-published-pings/1", "capacity": 512,
                "session_id": self.status.session_id, "segment_id": self.status.segment_id,
                "oldest_ping_id": oldest, "latest_ping_id": rows[-1]["ping_id"] if rows else None,
                "truncated": bool(after_ping_id and len(rows) == 512 and after_ping_id < oldest),
                "next_after_ping_id": selected[-1]["ping_id"] if selected else after_ping_id,
                "has_more": len(available) > limit, "pings": selected}

    def set_display_source(self, source: str) -> dict:
        if not isinstance(source, str) or source not in ("all", "own", "native"):
            raise ValueError("source must be all, own or native")
        if source != self._display_ping_source:
            self._display_ping_source = source
            self._selection_generation += 1
            self._display_generation += 1
            self._display_detection = self._display_presentation = self._display_noise = None
            self._display_window = None
            self._display_times.clear()
            # Existing consumers must also clear their accumulated history.
            # Future frames never silently fall back to a different source.
            if self._session_path is not None:
                path = self._session_path / "derived" / "display-selection.jsonl"
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open("a", encoding="utf-8") as output:
                    output.write(json.dumps({**self.display_selection(), "time_utc_ns": time.time_ns()})+"\n")
        return self.display_selection()

    async def _stage_echogram(self, ping, frame):
        """Called by the wheel-only hook after the unmodified raw archive."""
        self._staged_frame = (ping.ping_id, frame)

    def presentation(self) -> dict:
        """Return the canonical, display-safe result for the latest PING.

        Raw samples and the legacy M1 depth stay independently archived.  The
        UI consumes this object only for interpretation: it may render raw
        amplitude freely, but must never recreate a second bottom detector.
        """
        selected = (self._last_presentation if self._display_ping_source == "all"
                    else self._display_presentation)
        if selected is not None:
            return {**selected, "display_selection": self.display_selection(),
                    "noise_reduction": self.noise_reduction_status()}
        return {
            "schema": "no5.sonard-presentation/1",
            "ping_id": None,
            "quality": "WAITING",
            "noise_reduction": self.noise_reduction_status(),
            "reason": f"waiting for {self._display_ping_source} time-referenced PINGs",
            "bottom": None,
            "tracks": [],
            "filter": self._detection_decision(),
            "recommendations": {"range": "HOLD", "freeze": False},
            "display_selection": self.display_selection(),
        }

    def _refresh_presentation(self) -> None:
        """Publish a small, versioned decision without changing raw evidence."""
        result = self._last_detection
        decision = self._detection_decision()
        selected = None if result is None else result.selected
        raw_usable = (self.status.last_bottom_depth_m is not None
                      and self.status.last_bottom_confidence >= 0.75
                      and self.status.last_tx_reference_valid)
        bottom = None
        if (decision["effective_mode"] == "auto_raw" and raw_usable
                and decision.get("raw_support_track_id") is not None):
            source = "m1_raw"
            depth_m = float(self.status.last_bottom_depth_m)
            confidence = float(self.status.last_bottom_confidence)
            track_id = None
            classification = "unknown"
            rule_score = None
        elif decision["active"] and selected is not None:
            source = f"{selected.detector}_track_rules_v1"
            depth_m = float(selected.depth_m)
            confidence = float(selected.confidence)
            track_id = selected.track_id
            classification = selected.classification
            rule_score = float(selected.rule_score)
        else:
            source = None
            depth_m = confidence = track_id = classification = rule_score = None

        if source is not None:
            sample_count = self.status.last_sample_count
            period_ns = self.status.last_sample_period_ns
            max_depth_m = (sample_count * period_ns * 1e-9
                           * self.config.sound_speed_mps / 2.0)
            window = self._last_display_window
            if (window is not None and result is not None and window["ping_id"] == result.ping_id
                    and window["display_range_m"] is not None):
                max_depth_m = window["display_range_m"]
            display_bin = None if max_depth_m <= 0 else max(0.0, min(
                1023.0, depth_m / max_depth_m * 1023.0))
            bottom = {
                "source": source, "display_bin": display_bin,
                "depth_m": depth_m, "confidence": confidence,
                "strength": (confidence if rule_score is None else rule_score),
                "track_id": track_id, "classification": classification,
                "classification_source": ("rules_v1" if source != "m1_raw" else None),
            }

        tracks = [] if result is None else [track.public() for track in result.tracks
                                             if track.state == "confirmed"]
        if result is None:
            quality, reason = "WAITING", "waiting for a time-referenced PING"
        elif not result.valid_tx:
            quality, reason = "UNVERIFIED_TX", result.reason
        elif bottom is not None:
            quality, reason = "LOCKED", decision["reason"]
        elif self._presentation_was_locked:
            quality, reason = "UNSTABLE", decision["reason"]
        else:
            quality, reason = "SEARCHING", decision["reason"]
        freeze = quality == "UNSTABLE"
        self._presentation_was_locked = bottom is not None
        self._last_presentation = {
            "schema": "no5.sonard-presentation/1",
            "ping_id": None if result is None else result.ping_id,
            "acquisition_source": None if result is None else result.acquisition_source,
            "quality": quality, "reason": reason, "bottom": bottom,
            "tracks": tracks, "filter": decision,
            "recommendations": {
                "range": "FOLLOW_BOTTOM" if bottom is not None else "HOLD",
                "range_end_bin": None if bottom is None else max(32.0, min(
                    1023.0, bottom["display_bin"] * 1.3)),
                "freeze": freeze,
            },
        }

    def _labels_path(self) -> Path:
        if not self.archive_enabled:
            raise ValueError('dataset labels require explicit test archive mode')
        if self._session_path is None:
            self._ensure_session()
        assert self._session_path is not None
        return self._session_path / "derived" / "labels.jsonl"

    def labels(self) -> list[dict]:
        if not self.archive_enabled: return []
        path = self._labels_path()
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]

    def append_label(self, label: dict) -> dict:
        """Append immutable human feedback for later dataset construction."""
        if not isinstance(label, dict) or set(label) - {"track_id", "ping_id", "label", "note", "reviewer"}:
            raise ValueError("label may contain only track_id, ping_id, label, note, reviewer")
        if not isinstance(label.get("track_id"), int) or label["track_id"] < 1:
            raise ValueError("track_id must be a positive integer")
        if not isinstance(label.get("ping_id"), int) or label["ping_id"] < 0:
            raise ValueError("ping_id must be a non-negative integer")
        if label.get("label") not in RULE_CLASSES:
            raise ValueError("label must be one of: " + ", ".join(sorted(RULE_CLASSES)))
        for key in ("note", "reviewer"):
            if key in label and label[key] is not None and not isinstance(label[key], str):
                raise ValueError(f"{key} must be text or null")
        row = {"schema": "no5.sonard-label/1", "created_utc_ns": time.time_ns(), **label}
        path = self._labels_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as output:
            output.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
            output.flush()
            os.fsync(output.fileno())
        return row

    def _persist_acquisition_context(self) -> None:
        if self._session_path is None:
            return
        path = self._session_path / "acquisition-context.json"
        fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
        try:
            with os.fdopen(fd, "w") as output:
                json.dump(self._acquisition_context, output, indent=2, sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    async def _archive_detection(self, result: DetectionResult, *, legacy_depth_m, legacy_confidence) -> None:
        """Append derived data without changing the immutable raw-PING archive."""
        if self.test_recorder is not None:
            self.test_recorder.evidence("detections", result.public())
        if self._session_path is None or self.status.segment_id is None:
            return
        path = self._session_path / "derived" / self.status.segment_id / "detections.jsonl"
        row = {
            **result.public(),
            "policy": self.detection.policy.public(),
            "legacy_bottom": {"depth_m": legacy_depth_m, "confidence": legacy_confidence},
            "selection": self._detection_decision().copy(),
            "presentation": self._last_presentation,
            "display_selection": self.display_selection(),
            "acquisition_context": self._acquisition_context,
            "derived_schema": "no5.sonard-derived/3",
            "noise_reduction": self._last_noise,
            "display_window": self._last_display_window,
        }
        data = (json.dumps(row, sort_keys=True, separators=(",", ":"))+"\n").encode()
        if getattr(self,'_async_evidence',False):
            from .evidence_journal import EvidenceJournal
            if self._derived_journal is None:self._derived_journal=EvidenceJournal(
                root=self._capture_journal_root,volume_guard=self._data_volume_guard)
            try:self._derived_journal.append(path,data)
            except OSError as exc:self._record_storage_fault(exc);raise
            return
        def append():
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("ab") as output:
                output.write(data)
                output.flush()
        from .archive_io import complete_in_worker
        await complete_in_worker(append)

    def _detection_decision(self) -> dict:
        """Make automatic fallback explicit instead of hiding it in confidence."""
        result = self._last_detection
        candidate = None if result is None else result.selected
        legacy_available = (self.noise_reduction.near_field_m == 0
                            and result is not None and result.valid_tx and candidate is not None
                            and self.status.last_bottom_depth_m is not None
                            and self.status.last_bottom_confidence >= 0.75)
        # A loud legacy peak alone is not a bottom. Require current geometry
        # AND temporal evidence at that depth, from this acquisition source.
        # A deeper supported bottom still wins over an unrelated shallow fish.
        support = next((track for track in (() if result is None else result.tracks)
                        if legacy_available and track.state == "confirmed"
                        and track.classification != "multipath_like"
                        and track.hits >= min(self.detection.policy.track_window_pings,
                            candidate.hits if candidate is not None else self.detection.policy.min_track_hits)
                        and abs(track.depth_m-self.status.last_bottom_depth_m)
                            <= min(.03, self.detection.policy.association_gate_m)), None)
        legacy_usable = legacy_available and support is not None
        requested = self.detection.policy.mode
        if requested == "off":
            return {"requested_mode": requested, "effective_mode": "off", "active": False,
                    "reason": "disabled by operator", "candidate": None}
        if requested == "on":
            return {"requested_mode": requested, "effective_mode": "on", "active": candidate is not None,
                    "reason": None if candidate else "waiting for a persistent echo track",
                    "candidate": None if candidate is None else candidate.public()}
        # Preserve a corroborated raw bottom. Otherwise prefer a measured,
        # confirmed return; missing current returns remain unknown, not filled.
        active = not legacy_usable and candidate is not None
        return {"requested_mode": requested,
                "selection_version": "confirmed_support_continuity_v1",
                "effective_mode": "auto_filter" if active else "auto_raw",
                "active": active,
                "reason": ("raw bottom corroborated by a current confirmed track" if legacy_usable else
                           ("waiting for a persistent echo track" if candidate is None else
                            "raw bottom lacks current track support; tracked return selected")),
                "raw_support_track_id": None if support is None else support.track_id,
                "candidate": None if not active else candidate.public()}

    def _record_storage_fault(self, error):
        if self._storage_fault:return
        self._storage_fault = f'{type(error).__name__}: {error}'
        self.status.state = 'HOLD'
        self.status.last_error = self._storage_fault
        def persist():
            self.control_root.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(dir=self.control_root, prefix='.archive-hold-')
            try:
                with os.fdopen(fd, 'w') as out:
                    json.dump({'schema':'no5.archive-hold/1','reason':self._storage_fault,
                               'created_utc_ns':time.time_ns()},out)
                    out.flush();os.fsync(out.fileno())
                os.replace(name,self._hold_path)
                directory=os.open(self.control_root,os.O_RDONLY)
                try:os.fsync(directory)
                finally:os.close(directory)
            finally:Path(name).unlink(missing_ok=True)
        try:loop=asyncio.get_running_loop()
        except RuntimeError:persist()
        else:self._hold_task=loop.create_task(asyncio.to_thread(persist))

    def check_archive(self):
        if self._storage_fault:
            raise ArchiveFault(self._storage_fault)
        if self._derived_journal is not None:self._derived_journal.check()
        writer_check=getattr(self._writer,'check_archive',None)
        if writer_check:writer_check()
        client = self._client
        if client is not None:
            check = getattr(client.transport, "check_archive", None)
            if check: check()
        if self.transmit._archive_fault:
            raise OSError(self.transmit._archive_fault)
        if self.transmit._evidence_journal is not None:
            journal = self.transmit._evidence_journal
            journal.check()

    async def start(self):
        if self._test_archive_root is not None and self.test_recorder is None:
            from .test_recorder import TestRecorder
            self.test_recorder = TestRecorder(self._test_archive_root)
        self._async_evidence = True
        self._shutdown_requested.clear()
        self._shutdown_outcome = None
        if self._storage_fault:
            self.status.state='HOLD';self.status.last_error=self._storage_fault
            return
        from .capture_activity import CaptureActivity
        self._capture_activity = CaptureActivity()
        self._capture_activity.start()
        await super().start()
        self._persist_acquisition_context()
        self.recovery.start()

    async def _supervisor(self):
        # Missing hardware remains WAIT_PICO. A protocol/identity/device fault
        # aborts this acquisition attempt, rather than repeatedly issuing START.
        while self._running:
            if self._storage_fault:
                self.status.state='HOLD';break
            missing = False
            try:
                await self._run_pico_connection()
            except asyncio.CancelledError:
                raise
            except FileNotFoundError as exc:
                missing = True
                self.status.last_error = f"{type(exc).__name__}: {exc}"
                self.status.state = "WAIT_PICO"
            except (SerialDisconnected, ConnectionError, TimeoutError) as exc:
                # Reconnect only for an explicitly retained continuous intent.
                # The new connection starts stopped and must pass identity,
                # clock, raw-acquisition and finite physical-pilot checks.
                missing = self.recovery.reconnect_enabled and not self._storage_fault
                self.status.last_error = f"{type(exc).__name__}: {exc}"
                self.status.state = 'HOLD' if self._storage_fault else "WAIT_PICO" if missing else "ERROR"
                if not missing: self._running = False
            except Exception as exc:
                if isinstance(exc,OSError):self._record_storage_fault(exc)
                self.status.last_error = self._storage_fault or f"{type(exc).__name__}: {exc}"
                self.status.state = 'HOLD' if self._storage_fault else "ERROR"
                self._running = False
            finally:
                if self._writer:
                    try:await asyncio.to_thread(self._writer.close)
                    except Exception as exc:
                        self._record_storage_fault(exc)
                        self._cleanup_errors.append(f'RAW close: {type(exc).__name__}: {exc}')
                    raw_snapshot=getattr(self._writer,'snapshot',None)
                    if raw_snapshot is not None:self._last_raw_archive=raw_snapshot()
                    self._writer = None
                if self._client:
                    try:await self._client.close()
                    except Exception as exc:
                        self._record_storage_fault(exc)
                        self._cleanup_errors.append(f'USB/wire close: {type(exc).__name__}: {exc}')
                    self._client = None
            if self._storage_fault:
                self.status.state='HOLD';self.status.last_error=self._storage_fault
                self._running=False
            if missing and self._running:
                self.status.reconnects += 1
                await asyncio.sleep(1)

    async def _drain_received_pings(self, client):
        """Archive all PINGs the RX task accepted before the STOP boundary.

        STOP confirmation is observed by the same ordered RX task that puts
        PING_FRAMEs in ``client.pings``.  Once its stopped STATUS has arrived,
        no later queued PING can precede that boundary.  Draining this queue
        before returning to the supervisor makes the WAL/archive close after,
        rather than before, those PINGs are persisted.
        """
        drained = 0
        while True:
            try:
                ping = client.pings.get_nowait()
            except asyncio.QueueEmpty:
                break
            await self._handle_ping(ping, client)
            drained += 1
        return drained

    async def _settle_archive_fault(self, client, error, pending_ping=None):
        """Stop USB/TX before any potentially blocked WD close operation.

        Analysis evidence may be unavailable, so the final accepted PINGs use
        the original RAW writer only. Their samples and timestamps are kept;
        they are never published or fed back into a transmit decision.
        """
        self._record_storage_fault(error)
        self._fault_wire_journal = getattr(client.transport, '_wire_journal', None)
        begin_settle = getattr(client, 'begin_archive_fault_settle', None)
        if begin_settle is not None:
            try:begin_settle()
            except Exception as exc:self._cleanup_errors.append(f'archive wire settlement: {type(exc).__name__}: {exc}')
        try:await self.transmit._cancel_renewal()
        except Exception as exc:self._cleanup_errors.append(f'archive renewal cancel: {type(exc).__name__}: {exc}')
        try:await self.transmit._command(0x43)
        except Exception as exc:self._cleanup_errors.append(f'archive TX stop: {type(exc).__name__}: {exc}')
        try:await client.stop_acquisition()
        except Exception as exc:self._cleanup_errors.append(f'archive acquisition stop: {type(exc).__name__}: {exc}')
        try:await client.transport.freeze_receive()
        except Exception as exc:self._cleanup_errors.append(f'archive receive boundary: {type(exc).__name__}: {exc}')
        rescued=None
        begin_drain=getattr(self._writer,'begin_fault_drain',None)
        if begin_drain is not None:
            from .archive_io import complete_in_worker
            try:rescued=await complete_in_worker(begin_drain)
            except Exception as exc:self._cleanup_errors.append(f'archive failed original drain: {type(exc).__name__}: {exc}')
        if rescued and getattr(getattr(client,'clock',None),'ready',False):
            self._archived_total+=rescued
        async def original_only(ping):
            await super(HostService,self)._handle_ping(ping,client)
            self._staged_frame=None
            if getattr(getattr(client,'clock',None),'ready',False):self._archived_total+=1
            if hasattr(self._writer,'archived_count'):
                self.status.archived_pings=self._writer.archived_count
        drained=0
        if pending_ping is not None:
            if pending_ping.done() and not pending_ping.cancelled():
                # The waiting get can claim a PING while STOP/freeze awaits.
                # It belongs ahead of the remaining queue, never outside it.
                await original_only(pending_ping.result())
                drained+=1
            else:
                pending_ping.cancel()
                try:await pending_ping
                except asyncio.CancelledError:pass
        while not client.pings.empty():
            ping=client.pings.get_nowait()
            try:
                await original_only(ping)
                drained+=1
            except Exception as exc:
                self._cleanup_errors.append(f'archive RAW drain: {type(exc).__name__}: {exc}')
                break
        self._archive_fault_settle={'stopped_status_confirmed':client.last_stop_confirmed,
            'raw_drained_pings':drained,'remaining_received_pings':client.pings.qsize()}
        audit_errors=getattr(client.transport,'_settlement_audit_errors',None)
        if audit_errors:self._archive_fault_settle['wire_settlement_audit_errors']=list(audit_errors)
        if rescued is not None:self._archive_fault_settle['failed_original_raw_drained']=rescued

    async def _run_pico_connection(self):
        """PING acquisition with a cooperative, bounded shutdown drain.

        This is the original M1 connection sequence using only the candidate's
        audited transport/client factories.  Its stop branch deliberately
        differs: it waits for the request set by :meth:`stop`, persists the
        PING that may have become ready in the same event-loop turn, drains
        all remaining queued PINGs, and only then returns to the supervisor's
        writer/WAL/port cleanup.
        """
        client = None
        ping_task = None
        try:
            self.status.state = "WAIT_PICO"
            transport = ObservedTransport(
                self.config.device,
                audit_root=(self._session_path / "wire" / str(uuid.uuid4()) if self.archive_enabled else None),
                expected_identity=self.expected_identity,
                journal_root=self._capture_journal_root,volume_guard=self._data_volume_guard,
                recorder=self.test_recorder,
            )
            transport.archive_fault_handler = self._record_storage_fault
            client = VerifiedClient(transport,
                                    stop_confirmation_timeout_s=self.stop_confirmation_timeout_s)
            self._client = client
            await client.start()
            # Verify the exact peer before a recovering connection may STOP,
            # CONFIG or START it. HELLO_ACK only prompts the COBS boundary.
            await client.acknowledge_hello()
            self.status.state = "HANDSHAKE"
            hello = await client.wait_hello(timeout=5.0)
            await client.stop_acquisition()
            await client.acknowledge_hello()
            await client.synchronize(self.config.clock_initial_exchanges)
            caps = hello.capabilities
            if self.config.trigger_mode == 1 and not (caps & (1 << 0)):
                raise RuntimeError("Pico does not support external GPIO trigger")
            if self.config.trigger_mode == 2 and not (caps & (1 << 1)):
                raise RuntimeError("Pico does not support analog-threshold trigger")
            if self.config.signal_mode == 1 and not (caps & (1 << 3)):
                raise RuntimeError("Pico does not support envelope signal mode")
            if self.config.signal_mode == 2 and not (caps & (1 << 4)):
                raise RuntimeError("Pico does not support carrier signal mode")
            pretrigger = self.config.pretrigger_samples if (caps & (1 << 2)) else 0
            cfg = ConfigSet(
                revision=int(time.time()) & 0xFFFFFFFF,
                sample_period_ns=self.config.sample_period_ns,
                sample_count=min(self.config.sample_count, hello.max_samples),
                pretrigger_samples=pretrigger,
                trigger_mode=self.config.trigger_mode,
                signal_mode=self.config.signal_mode,
                trigger_threshold=self.config.trigger_threshold,
                trigger_hysteresis=self.config.trigger_hysteresis,
                capture_timeout_us=self.config.capture_timeout_us,
                reserved=1 if getattr(self.config, "tx_marker_requested", False) else 0,
            )
            ack = await client.configure(cfg)
            self._open_segment(hello.device_serial, ack.actual_sample_count,
                               ack.actual_sample_period_ns)
            await client.start_acquisition()
            self.status.state = "RUNNING"
            self.status.last_error = None
            self.transmit.bind(client)
            sync_task = asyncio.create_task(self._periodic_sync(client), name="pico-clock-sync")
            disconnect_task = asyncio.create_task(client.wait_disconnected(), name="pico-disconnect")
            shutdown_task = asyncio.create_task(self._shutdown_requested.wait(),
                                                name="pico-shutdown-drain")
            try:
                while True:
                    ping_task = asyncio.create_task(client.pings.get(), name="pico-ping")
                    done, _ = await asyncio.wait(
                        {ping_task, disconnect_task, shutdown_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if shutdown_task in done:
                        await client.transport.freeze_receive()
                        # Freeze awaits RX completion. A waiting get can claim
                        # a PING during that await, after asyncio.wait took its
                        # earlier done snapshot. Inspect the task now, or that
                        # original is neither in the queue nor in the drain.
                        drained = 0
                        if ping_task.done() and not ping_task.cancelled():
                            ping=ping_task.result();ping_task=None
                            await self._handle_ping(ping, client)
                            drained += 1
                        else:
                            ping_task.cancel()
                            try:
                                await ping_task
                            except asyncio.CancelledError:
                                pass
                            ping_task=None
                        queued = client.pings.qsize()
                        client.transport.trace("shutdown_drain_begin", queued_pings=queued,
                                               ready_ping=drained)
                        drained += await self._drain_received_pings(client)
                        client.transport.trace("shutdown_drain_complete", drained_pings=drained,
                                               remaining_pings=client.pings.qsize())
                        if self._shutdown_outcome is not None:
                            self._shutdown_outcome["drained_received_pings"] = drained
                            self._shutdown_outcome["remaining_received_pings"] = client.pings.qsize()
                        return
                    if disconnect_task in done:
                        self.check_archive()
                        if ping_task in done:
                            ping=ping_task.result();ping_task=None
                            await self._handle_ping(ping, client)
                        else:
                            ping_task.cancel()
                            try: await ping_task
                            except asyncio.CancelledError: pass
                            ping_task=None
                        await self._drain_received_pings(client)
                        raise SerialDisconnected("Pico disconnected")
                    ping=ping_task.result();ping_task=None
                    await self._handle_ping(ping, client)
            finally:
                for task in (sync_task, disconnect_task, shutdown_task):
                    task.cancel()
                for task in (sync_task, disconnect_task, shutdown_task):
                    try:
                        await task
                    except (asyncio.CancelledError, Exception):
                        pass
        except OSError as exc:
            if (client is not None and self.status.state in {'RUNNING','HOLD'} and
                    not isinstance(exc,(SerialDisconnected,ConnectionError))):
                await self._settle_archive_fault(client,exc,pending_ping=ping_task)
            raise
        finally:
            try:await self.transmit.unbind()
            finally:
                self._capture_client(client)
                journal,self._derived_journal=self._derived_journal,None
                if journal is not None:
                    self._last_derived_archive=journal.snapshot()
                    try:await asyncio.to_thread(journal.close)
                    except OSError as exc:
                        self._record_storage_fault(exc)
                        self._cleanup_errors.append(f'derived close: {type(exc).__name__}: {exc}')
                    self._last_derived_archive=journal.snapshot()

    def _capture_client(self, client):
        if client is None or client in self._completed_clients:
            return
        self._completed_clients.add(client)
        counters = client_counters(client)
        for key, value in counters.items():
            self._totals[key] += value
        self._last_config = client.accepted_config or self._last_config
        self._last_clock = {**client.clock.snapshot(), "last_observed": True}
        self._last_startup_sync = client.transport.startup_sync_snapshot()
        observed = client.transport.last_device_status
        if observed is not None:
            self._last_device = {**observed, "running": client.transport.status_running}
        self._connections.append({"counters": counters,
            "hello": asdict(client.hello) if client.hello else None,
            "accepted_config": client.accepted_config,
            "startup_sync": client.transport.startup_sync_snapshot(),
            "device_status": observed,
            "stopped_status_confirmed": client.last_stop_confirmed,
            "raw_rx": (str(client.transport.audit_root / "rx.cobs") if client.transport.audit_root else None),
            "raw_tx": (str(client.transport.audit_root / "tx.cobs") if client.transport.audit_root else None),
            "control_trace": (str(client.transport.audit_root / "control.jsonl") if client.transport.audit_root else None)})

    async def _handle_ping(self, ping, client):
        async with self._analysis_lock:
            return await self._process_ping(ping, client)

    async def _process_ping(self, ping, client):
        ready = client.clock.ready
        self._staged_frame = None
        await super()._handle_ping(ping, client)
        if hasattr(self._writer,'archived_count'):
            self.status.archived_pings=self._writer.archived_count
        if ready:
            if self.test_recorder is not None:
                from no5_sonard.storage.raw_zarr import RawPingRecord
                self.test_recorder.raw(RawPingRecord(ping_id=ping.ping_id,
                    time_utc_ns=self.status.last_ping_utc_ns, trigger_device_time_us=ping.trigger_device_time_us,
                    sample_period_ns=ping.sample_period_ns, tx_zero_sample_index=ping.tx_zero_sample_index,
                    ping_flags=ping.ping_flags, adc_min=ping.adc_min, adc_max=ping.adc_max, samples=ping.samples))
            self._last_raw_host_at = time.monotonic()
            self._archived_total += int(self.archive_enabled)
            self.transmit.on_ping(ping)
            self._clipped_total += int(bool(ping.ping_flags & 2))
            valid_tx = self._valid_tx_reference(self.config, ping)
            source = ('own' if getattr(self.transmit, 'is_own_ping', lambda p: False)(ping) else 'native')
            from .archive_io import complete_in_worker
            result = await complete_in_worker(self.detection.process,
                ping_id=ping.ping_id, samples=ping.samples,
                tx_zero_sample_index=ping.tx_zero_sample_index,
                sample_period_ns=ping.sample_period_ns,
                time_utc_ns=self.status.last_ping_utc_ns,
                valid_tx=valid_tx,
                acquisition_source=source, near_field_m=self.noise_reduction.near_field_m)
            self._last_detection = result
            self._refresh_presentation()
            observer = getattr(self.transmit, 'on_detection', None)
            if observer is not None:
                observer(result, self._last_presentation)
            cleaned, self._last_noise = await complete_in_worker(self.noise_reduction.process,
                ping.samples, ping_id=ping.ping_id,
                sample_period_ns=ping.sample_period_ns,
                tx_zero_sample_index=ping.tx_zero_sample_index,
                sound_speed_mps=self.config.sound_speed_mps,
                blanking_m=self.detection.policy.blanking_m, blocks=result.blocks,
                valid_tx=valid_tx, acquisition_source=result.acquisition_source)
            depth_policy = getattr(self.transmit, "depth_policy", None)
            control = (depth_policy.snapshot() if depth_policy is not None
                       and self.transmit.policy.get("rate_policy") == "depth_auto" else None)
            window = display_window(sample_count=len(ping.samples), sample_period_ns=ping.sample_period_ns,
                tx_zero_sample_index=ping.tx_zero_sample_index, sound_speed_mps=self.config.sound_speed_mps,
                ping_id=ping.ping_id, stripe_mode=self.noise_reduction.stripe_mode,
                depth_control=control, valid_tx=valid_tx, acquisition_source=result.acquisition_source)
            self._last_display_window = window
            # Only the presentation bin changes. Metric depth, confidence,
            # tracks, capture geometry and the depth controller are untouched.
            if self._last_presentation.get("bottom") is not None and window["display_range_m"] is not None:
                bottom = dict(self._last_presentation["bottom"])
                bottom["display_bin"] = min(1023., max(0., bottom["depth_m"]/window["display_range_m"]*1023.))
                self._last_presentation = {**self._last_presentation, "bottom": bottom,
                    "recommendations": {**self._last_presentation["recommendations"],
                                        "range_end_bin": min(1023., max(32., bottom["display_bin"]*1.3))}}
            self._last_presentation = {**self._last_presentation, "display_window": window}
            await self._archive_detection(result,
                                    legacy_depth_m=self.status.last_bottom_depth_m,
                                    legacy_confidence=self.status.last_bottom_confidence)
            source = result.acquisition_source
            self._display_counts["received"] += 1
            self._display_counts[source] += 1
            if self._display_ping_source in ("all", source):
                self._display_detection = result
                self._display_presentation = dict(self._last_presentation)
                self._display_noise = dict(self._last_noise)
                self._display_window = dict(window)
                self._display_counts["published"] += 1
                self._display_times.append(time.monotonic())
                if self._staged_frame is None or self._staged_frame[0] != ping.ping_id:
                    raise RuntimeError("same-PING echogram stage missing")
                frame = self._staged_frame[1]
                near_active = self._last_noise.get("near_field", {}).get("active", False)
                if self._last_noise["active"] or near_active or (valid_tx and window["display_end_sample_exclusive"] != len(ping.samples)):
                    display = max_pool_resample(cleaned[:window["display_end_sample_exclusive"]], start_index=window["display_start_sample"],
                                                bins=self.config.display_bins)
                    header, _ = unpack_echogram_frame(frame)
                    bottom = self._last_presentation.get("bottom") if near_active else None
                    if near_active:
                        import math
                        masked_bins = (len(display) if self.noise_reduction.near_field_m >= window["display_range_m"]
                            else math.ceil(self.noise_reduction.near_field_m / window["display_range_m"] * (len(display)-1)))
                        display[:masked_bins] = 0
                        self._last_noise["near_field"]["display_excluded_bins"] = masked_bins
                    frame = pack_echogram_frame(ping_id=header.ping_id, time_utc_ns=header.time_utc_ns,
                        max_depth_m=window["display_range_m"],
                        bottom_depth_m=(header.bottom_depth_m if not near_active else
                                        float('nan') if bottom is None else bottom['depth_m']),
                        bottom_confidence=(header.bottom_confidence if not near_active else
                                           0. if bottom is None else bottom['confidence']),
                        flags=header.flags, amplitudes=display)
                reference = getattr(self.transmit, "reference_observation", None) or {}
                native_confirmed = (valid_tx and reference.get("observation_reason") == "qualified_native_marker"
                    and abs(reference.get("observed_device_us", -1000000)-ping.trigger_device_time_us) <= 3)
                actual_source = "own" if source == "own" else "native" if native_confirmed else None
                published = {
                    "ping_id": ping.ping_id, "time_utc_ns": self.status.last_ping_utc_ns,
                    "session_id": self.status.session_id, "segment_id": self.status.segment_id,
                    "actual_source": actual_source,
                    "source_evidence": ("physical_own_marker_raw_match" if actual_source == "own" else
                        "qualified_native_marker_raw_match" if actual_source == "native" else "unconfirmed"),
                    "valid_tx_reference": valid_tx,
                    "display_generation": self._display_generation,
                    "selection_generation": self._selection_generation,
                    "sample_count": len(ping.samples), "sample_period_ns": ping.sample_period_ns,
                    "noise_revision": self.noise_reduction.revision,
                    "noise_active": self._last_noise["active"],
                    "display_window": window,
                }
                self._published_pings.append(published)
                self._publication_sequence += 1
                context = None
                try:
                    context = pack_context(frame, published,
                        selected_source=self._display_ping_source,
                        publication_sequence=self._publication_sequence)
                except (ValueError, TypeError, OverflowError) as exc:
                    # An optional read envelope must never break legacy output,
                    # acquisition, raw archiving or the transmitter's lease.
                    self._context_encode_errors += 1
                    error = f"{type(exc).__name__}: {exc}"[:160]
                    if error != self._last_context_error:
                        logging.getLogger(__name__).warning("Echogram context skipped: %s", error)
                    self._last_context_error = error
                await self.hub.publish(frame)
                if context is not None:
                    await self.context_hub.publish(context)
            else:
                self._display_counts["hidden"] += 1
        self._staged_frame = None

    def snapshot(self):
        from .archive_io import processing_timings
        result = super().snapshot()
        result['processing_timings'] = processing_timings()
        result['pending_ping_queue'] = self._client.pings.qsize() if self._client else 0
        result["transmit"] = self.transmit.snapshot()
        result["display_selection"] = self.display_selection()
        result["noise_reduction"] = self.noise_reduction_status()
        result["display_window"] = self._display_window
        # Source-only tests use the preserved M1 config directly; its compact
        # idle snapshot omits acquisition until a device has started.
        result.setdefault("acquisition", {})
        hello = self._client.hello if self._client else None
        result["acquisition"]["amplitude"] = {
            "schema": "no5.display-amplitude/1", "encoding": "uint16_le",
            "unit": "adc_code", "significant_bits": 12, "fullscale_code": 4095,
            "adc_fullscale_mv": None if hello is None else hello.adc_fullscale_mv,
            "display_transform": "background_reduction_then_max_pool_or_interpolate"
                if (self.noise_reduction_status()["latest"] or {}).get("active") else
                "max_pool_or_interpolate_without_amplitude_scaling",
            "noise_reduction": self.noise_reduction_status(),
            "normalization_source": "HELLO_12bit_checked_host_contract",
            "raw_samples_modified": False,
        }
        result["acquisition"]["time_reference"] = {
            "kind":getattr(self.config,"tx_reference_basis","unverified"),
            "marker_gpio":16 if getattr(self.config,"tx_marker_requested",False) else None,
            "last_ping_verified":self.status.last_tx_reference_valid,
            "depth_calibrated":False,"depth_conversion":"sound_speed_time_of_flight_estimate",
        }
        result.setdefault("session", {})
        result.setdefault("pico", {})
        current = self._client
        counters = dict(self._totals)
        if current is not None and current not in self._completed_clients:
            for key, value in client_counters(current).items():
                counters[key] += value
        result["pico"].update(counters)
        if current is not None and hasattr(current.transport, "liveness_snapshot"):
            result["pico"]["transport_liveness"] = current.transport.liveness_snapshot()
        raw_age = None if self._last_raw_host_at is None else max(0., time.monotonic()-self._last_raw_host_at)
        result["rates"]["last_raw_arrival_age_s"] = raw_age
        wire_journal=getattr(getattr(current,'transport',None),'_wire_journal',None)
        if wire_journal is None:wire_journal=getattr(self,'_fault_wire_journal',None)
        result['archive_health']={'state':'HOLD' if self._storage_fault else ('OK' if self.archive_enabled else 'DISABLED'),
            'enabled':self.archive_enabled,'mode':'test' if self.archive_enabled else 'off',
            'reason':self._storage_fault,'cleanup_errors':list(self._cleanup_errors),
            'wire':wire_journal.snapshot() if hasattr(wire_journal,'snapshot') else None,
            'derived':self._derived_journal.snapshot() if self._derived_journal is not None else getattr(self,'_last_derived_archive',None),
            'transmit':self.transmit._evidence_journal.snapshot() if self.transmit._evidence_journal is not None else None,
            'last_raw_arrival_age_s':raw_age}
        result['test_recorder'] = self.test_recorder.snapshot() if self.test_recorder else {
            'enabled':False,'state':'DISABLED','affects_live_health':False}
        result['archive_health']['fault_settle']=getattr(self,'_archive_fault_settle',None)
        raw_snapshot=getattr(self._writer,'snapshot',None)
        result['archive_health']['raw']=raw_snapshot() if raw_snapshot is not None else getattr(self,'_last_raw_archive',None)
        if raw_age is not None and raw_age > 3:
            result["rates"]["last_observed_ping_hz"] = result["rates"].get("ping_hz")
            result["rates"]["ping_hz"] = 0.
        result["pico"]["startup_sync"] = (
            current.transport.startup_sync_snapshot() if current is not None
            else self._last_startup_sync)
        result["host_package"] = {"name": "no5-sonard-openecho", "version": __version__,
                                  "mode": "ping", "ping_frame_type": 0x20,
                                  "stream_adapter_implemented": False}
        from .cobs_codec import snapshot as cobs_backend
        result['acceleration'] = {'resampler': 'numpy_reduceat_v1', 'cobs_decoder': cobs_backend()}
        result["accepted_config"] = (
            current.accepted_config if current and current.accepted_config
            else self._last_config)
        device = current.transport.last_device_status if current else None
        result["device_status"] = ({**device, "running": current.transport.status_running}
                                   if device is not None else self._last_device)
        result["session"]["archived_pings_total"] = self._archived_total
        result["acquisition"]["clipped_pings_total"] = self._clipped_total
        result["acquisition"]["tx_marker_requested"] = getattr(
            self.config, "tx_marker_requested", False)
        result["acquisition"]["tx_reference_basis"] = getattr(
            self.config, "tx_reference_basis", "unverified")
        result["acquisition"]["empirical_trigger_fallback"] = getattr(
            self.config, "empirical_trigger_fallback", False)
        selected = None if self._last_detection is None else self._last_detection.selected
        decision = self._detection_decision()
        result["detection"] = {
            "policy": self.detection.policy.public(),
            "state": "waiting" if self._last_detection is None else self._last_detection.state,
            "reason": None if self._last_detection is None else self._last_detection.reason,
            "selection": decision,
            "candidate": None if selected is None else selected.public(),
            "presentation": self.presentation(),
            "confirmed_tracks": 0 if self._last_detection is None else sum(
                item.state == "confirmed" for item in self._last_detection.tracks),
            "archive": (None if self.status.segment_id is None or self._session_path is None else
                        str(self._session_path / "derived" / self.status.segment_id / "detections.jsonl")),
        }
        result["acquisition"]["context"] = dict(self._acquisition_context)
        if current is None and self._last_clock is not None:
            result["clock"] = self._last_clock
        result["connection_history"] = list(self._connections)
        result["shutdown"] = self._shutdown_outcome
        result["final_status_path"] = (str(self.final_status_path)
                                       if self.final_status_path else None)
        return result

    async def stop(self):
        try:
            await self._stop_capture()
        finally:
            if self.test_recorder is not None:
                await asyncio.to_thread(self.test_recorder.close)
            if self._capture_activity is not None:
                self._capture_activity.close()
                self._capture_activity = None

    async def _stop_capture(self):
        if not self._running and self._task is None and self.final_status_path:
            return
        prior_state = self.status.state
        try:
            await self.recovery.close(operator_stop=self.control_root == Path(self.config.storage_root))
        except Exception as exc:
            if self._client:
                try:self._client.transport.trace("transmit_abort_failed", error=str(exc))
                except OSError:pass
        self._running = False
        client = self._client
        self._shutdown_outcome = {
            "stop_status_confirmed": False,
            "reason": "stop requested",
            "drained_received_pings": 0,
            "remaining_received_pings": None,
            "supervisor_settled": False,
        }
        if client is not None:
            try:
                await client.stop_acquisition()
            except Exception as exc:
                self._shutdown_outcome.update({
                    "reason": "stop confirmation failed",
                    "error": f"{type(exc).__name__}: {exc}",
                })
                try:client.transport.trace("stop_confirmation_failed",error=self._shutdown_outcome["error"])
                except OSError:pass
            else:
                self._shutdown_outcome["stop_status_confirmed"] = True
                self._shutdown_outcome["reason"] = "stopped status confirmed"
        else:
            self._shutdown_outcome["reason"] = "no active client"
        # Release the acquisition task only after the bounded confirmation wait.
        # Its shutdown branch drains all already-received PINGs before its
        # supervisor performs the archive/WAL and serial close.
        self._shutdown_requested.set()
        task = self._task
        if task is not None:
            try:
                await asyncio.wait_for(asyncio.shield(task), self.shutdown_settle_timeout_s)
                self._shutdown_outcome["supervisor_settled"] = True
            except asyncio.TimeoutError:
                self._shutdown_outcome.update({
                    "reason": "shutdown drain settle timeout",
                    "error": f"TimeoutError: supervisor did not settle within {self.shutdown_settle_timeout_s}s",
                })
                if client is not None:
                    try:client.transport.trace("shutdown_drain_timeout",timeout_s=self.shutdown_settle_timeout_s)
                    except OSError:pass
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            except Exception as exc:
                self._cleanup_errors.append(f'supervisor: {type(exc).__name__}: {exc}')
            self._task = None
        # Normal connection teardown is owned by _supervisor.  This fallback
        # covers a stop while setup is incomplete or a cancelled supervisor.
        if self._client is not None:
            self._capture_client(self._client)
            try:await self._client.close()
            except Exception as exc:self._cleanup_errors.append(f'client: {type(exc).__name__}: {exc}')
            self._client = None
        if self._writer:
            try:await asyncio.to_thread(self._writer.close)
            except Exception as exc:self._cleanup_errors.append(f'writer: {type(exc).__name__}: {exc}')
            self._writer = None
        journal,self._derived_journal=self._derived_journal,None
        if journal is not None:
            try:await asyncio.to_thread(journal.close)
            except Exception as exc:self._cleanup_errors.append(f'derived: {type(exc).__name__}: {exc}')
        try:await self.transmit.close_archive()
        except OSError as exc:
            self._record_storage_fault(exc)
            self._cleanup_errors.append(f'transmit evidence close: {type(exc).__name__}: {exc}')
        if self._hold_task is not None:await self._hold_task
        self.status.state = 'HOLD' if self._storage_fault else "IDLE"
        if self._session_path is None and self.operational_receipt_path is None:
            return
        self.final_status_path = (self._session_path / "host-final-status.json" if self._session_path else
                                  Path(self.operational_receipt_path))
        self.final_status_path.parent.mkdir(parents=True, exist_ok=True)
        receipt = self.snapshot()
        receipt["schema"] = "no5.sonard-host-final/1"
        receipt["state_before_shutdown"] = prior_state
        receipt["port_closed"] = self._client is None
        receipt["archive_closed"] = self._writer is None
        receipt["all_connections_stop_confirmed"] = (
            all(row["stopped_status_confirmed"] for row in self._connections)
            if self._connections else None)
        fd, name = tempfile.mkstemp(prefix="host-final-", dir=self.final_status_path.parent)
        try:
            with os.fdopen(fd, "w") as output:
                json.dump(receipt, output, indent=2, allow_nan=False)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(name, self.final_status_path)
        finally:
            Path(name).unlink(missing_ok=True)

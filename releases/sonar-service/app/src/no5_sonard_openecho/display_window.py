"""Declared display geometry; original acquisition and detection remain complete."""
from __future__ import annotations
import struct


def display_window(*, sample_count, sample_period_ns, tx_zero_sample_index,
                   sound_speed_mps, ping_id, stripe_mode, depth_control=None,
                   valid_tx=False, acquisition_source="native"):
    verified = bool(valid_tx and sample_period_ns > 0 and 0 <= tx_zero_sample_index < sample_count)
    # M1's unverified fallback publishes all original samples from index zero
    # and a NaN physical range. JSON represents that unknown range as null.
    start = tx_zero_sample_index if verified else 0
    available = sample_count-start
    count, reason = available, "full_capture" if verified else "unverified_time_reference"
    d = depth_control or {}
    if (stripe_mode != "off" and verified and acquisition_source == "own"
            and d.get("reason") in {"depth_locked", "depth_held_fresh"}
            and d.get("depth_age_s") is not None and 0 <= d["depth_age_s"] < .9
            and type(d.get("sample_count")) is int and 2048 <= d["sample_count"] <= 16384):
        count = min(available, d["sample_count"])
        reason = "qualified_depth_window" if count < available else "capture_matches_depth_window"
    scale = sample_period_ns*1e-9*sound_speed_mps/2
    # Same binary32 range as the M1 header, without a JSON/f64 disagreement.
    display_range = struct.unpack('<f', struct.pack('<f', count*scale))[0] if verified else None
    return {"schema": "no5.display-window/1", "ping_id": int(ping_id),
            "capture_sample_count": int(sample_count), "display_sample_count": int(count),
            "display_start_sample": int(start), "display_end_sample_exclusive": int(start+count),
            "capture_range_m": available*scale if verified else None, "display_range_m": display_range,
            "tx_reference_verified": verified,
            "reason": reason, "raw_samples_modified": False,
            "detector_uses_full_capture": True}

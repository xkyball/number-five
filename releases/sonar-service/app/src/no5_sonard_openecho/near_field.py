"""One physical exclusion boundary; acquisition indices and raw ADC stay intact."""
from __future__ import annotations

import math


def validate_distance(value):
    try:
        valid = type(value) in (int, float) and math.isfinite(value) and value >= 0
    except OverflowError:
        valid = False
    if not valid:
        raise ValueError("near_field_m must be a finite nonnegative number")
    return float(value)


def boundary(*, sample_count, sample_period_ns, tx_zero_sample_index,
             sound_speed_mps, near_field_m, valid_tx=True):
    distance = validate_distance(near_field_m)
    verified = bool(valid_tx and sample_period_ns > 0 and sound_speed_mps > 0
                    and 0 <= tx_zero_sample_index < sample_count)
    start = 0
    reason = "disabled" if distance == 0 else "unverified_time_reference"
    if distance > 0 and verified:
        step = sample_period_ns * 1e-9 * sound_speed_mps / 2
        available = sample_count - tx_zero_sample_index
        count = available if distance >= available * step else math.ceil(distance / step)
        start = int(tx_zero_sample_index + count)
        reason = "no_usable_samples" if start == sample_count else "excluded_before_processing"
    return {"near_field_m": distance, "active": bool(distance and verified),
            "analysis_start_sample": start,
            "excluded_samples": max(0, start-tx_zero_sample_index) if start else 0,
            "reason": reason, "raw_samples_modified": False}


def mask_display(values, *, boundary, tx_zero_sample_index):
    """Zero only a new derived view, preserving time zero and physical range."""
    result = values.copy()
    if boundary["active"]:
        result[tx_zero_sample_index:boundary["analysis_start_sample"]] = 0
    return result

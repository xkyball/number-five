"""Separate a measured, repeated fast-attack RC signature in display ADC only.

This is an additive component estimate, never temporal column replacement.
The 2-us/0.78 decay model is empirical for this acquisition, not acoustic Sv.
Ambiguous, isolated, clipped and detected echo samples remain untouched.
"""
from functools import lru_cache

import numpy as np
from scipy.signal import find_peaks

ALGORITHM = "repeated_rc_impulse_v1"


@lru_cache(maxsize=1)
def _model():
    k = np.arange(-4, 12)
    basis = np.column_stack((np.ones(k.size), k,
        np.where(k >= 0, .78 ** np.maximum(k, 0), 0), k == 0, k == 1))
    return k, basis, np.linalg.pinv(basis)


def separate_impulses(raw, cleaned, protected, *, sample_period_ns, source):
    """Return a new display array and a reproducible same-PING decision."""
    status = {"algorithm": ALGORITHM, "active": False, "reason": "no_repeated_signature",
              "events": 0, "modified_samples": 0, "maximum_subtraction_adc": 0,
              "detected_echo_samples_unchanged": True, "temporal_replacement": False}
    out = cleaned.copy()
    if sample_period_ns != 2000 or source != "own":
        status["reason"] = "unqualified_acquisition"
        return out, status
    if np.any(raw >= 4095):
        status["reason"] = "clipped_profile"
        return out, status
    values = raw.astype(np.float64)
    peaks, _ = find_peaks(np.diff(values, prepend=values[0]), height=30, distance=6)
    peaks = peaks[(peaks >= 32) & (peaks < len(values)-20)]
    peaks = peaks[~protected[peaks]]
    if len(peaks) < 6:
        return out, status
    k, basis, inverse = _model()
    observations = values[peaks[:, None]+k]
    coefficients = observations @ inverse.T
    fits = coefficients @ basis.T
    error = np.sqrt(np.mean((observations-fits)**2, axis=1))
    amplitude = coefficients[:, 2]
    qualified = ((amplitude >= 15) & (error <= np.maximum(6, amplitude*.10))
        & (np.abs(coefficients[:, 1]) <= amplitude*.025)
        & (coefficients[:, 3] >= -np.maximum(10, amplitude*.2))
        & (coefficients[:, 4] >= -np.maximum(10, amplitude*.2)))
    peaks, coefficients = peaks[qualified], coefficients[qualified]
    if len(peaks) < 6 or peaks[-1]-peaks[0] < 300:
        return out, status
    distances = np.diff(peaks)
    short = distances[distances < 80]
    spacing = float(np.median(short)) if short.size else 0.
    if not 8 <= spacing <= 40:
        return out, status
    # Both neighbours are checked without N-by-N work. Missing pulses can be
    # integral multiples of the inferred train period. A solitary target is
    # insufficient evidence, even if it happens to have an exponential tail.
    multiple = distances/spacing
    neighbour = ((distances < 80) & (np.abs(multiple-np.rint(multiple)) < .2))
    member = np.r_[False, neighbour] | np.r_[neighbour, False]
    peaks, coefficients = peaks[member], coefficients[member]
    if len(peaks) < 6 or peaks[-1]-peaks[0] < 300:
        return out, status
    correction = np.zeros(len(values))
    decay = .78**np.arange(20)
    for peak, coeff in zip(peaks, coefficients):
        component = coeff[2]*decay.copy()
        component[:2] += np.maximum(coeff[3:5], 0)
        # Overlapping fits cannot subtract the same component twice.
        correction[peak:peak+20] = np.maximum(correction[peak:peak+20], component)
    correction[protected] = 0
    out = np.rint(np.maximum(cleaned.astype(np.float64)-correction, 0)).astype(np.uint16)
    delta = cleaned.astype(np.int32)-out
    assert np.array_equal(out[protected], cleaned[protected])
    status.update(active=bool(np.any(delta)), reason="repeated_fast_attack_rc_signature",
                  events=int(len(peaks)), modified_samples=int(np.count_nonzero(delta)),
                  maximum_subtraction_adc=int(delta.max()))
    return out, status

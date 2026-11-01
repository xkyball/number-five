"""Causal ADC-envelope background reduction, exclusively for display derivatives.

No calibration to acoustic power is implied. No temporal average is subtracted:
stationary targets must survive. Geometry and echo detection use original ADC.
"""
from __future__ import annotations

import numpy as np
from scipy.ndimage import median_filter, uniform_filter1d
from .impulse_reduction import separate_impulses
from .near_field import boundary, mask_display, validate_distance

ALGORITHM = "local_adc_background_v3"
PREVIOUS_ALGORITHM = "local_adc_background_v2"
LEGACY_ALGORITHM = "robust_adc_background_v1"
MODES = ("off", "auto", "on")


class NoiseReduction:
    def __init__(self, mode="auto", *, revision=1, stripe_mode="auto", impulse_mode="auto",
                 legacy_metadata=False, previous_metadata=False, near_field_m=0., near_field_metadata=True):
        if not isinstance(mode, str) or mode not in MODES:
            raise ValueError("mode must be off, auto or on")
        self.mode = mode
        self.revision = revision
        if not isinstance(stripe_mode, str) or stripe_mode not in MODES:
            raise ValueError("stripe_mode must be off, auto or on")
        self.stripe_mode = stripe_mode
        self.legacy_metadata = legacy_metadata
        if not isinstance(impulse_mode, str) or impulse_mode not in MODES:
            raise ValueError("impulse_mode must be off, auto or on")
        self.impulse_mode = impulse_mode
        self.previous_metadata = previous_metadata
        self.near_field_m = validate_distance(near_field_m)
        self.near_field_metadata = near_field_metadata

    @classmethod
    def from_recorded(cls, recorded):
        """Reproduce older derived decisions without silently changing their DSP."""
        return cls(recorded["mode"], revision=recorded["revision"],
                   stripe_mode=recorded.get("stripe_mode", "off"),
                   impulse_mode=recorded.get("impulse_mode", "off"),
                   legacy_metadata="stripe_mode" not in recorded,
                   previous_metadata="impulse_mode" not in recorded,
                   near_field_m=recorded.get("near_field_m", 0.),
                   near_field_metadata="near_field_m" in recorded)

    def policy(self):
        policy = {"mode": self.mode, "revision": self.revision,
                "algorithm": (LEGACY_ALGORITHM if self.stripe_mode == "off" else
                              PREVIOUS_ALGORITHM if self.impulse_mode == "off" else ALGORITHM),
                "scope": "echogram_only",
                "reference_cell_us": 256, "threshold_sigma": 2.0,
                "raw_samples_modified": False,
                "detection_input": "original_adc", "transmit_input": "original_detection"}
        if not self.legacy_metadata:
            policy.update(stripe_mode=self.stripe_mode,
                          display_window="capture" if self.stripe_mode == "off" else "qualified_depth_window",
                          temporal_target_subtraction=False)
        if not self.previous_metadata and not self.legacy_metadata:
            policy["impulse_mode"] = self.impulse_mode
        if self.near_field_metadata:
            policy["near_field_m"] = self.near_field_m
        if self.near_field_m:
            policy["algorithm"] = "near_field_excluded_adc_v1"
        return policy

    def process(self, samples, *, ping_id, sample_period_ns, tx_zero_sample_index,
                sound_speed_mps=1500., blanking_m=.5, blocks=(), valid_tx=True,
                acquisition_source="native"):
        raw = np.asarray(samples)
        if raw.ndim != 1 or raw.dtype.kind not in "ui" or raw.size == 0:
            raise ValueError("samples must be a nonempty integer ADC profile")
        # Always own the output, even when disabled. Never alter a view of PING.
        output = raw.astype(np.uint16, copy=True)
        status = {"schema": "no5.sonard-noise-reduction/1", **self.policy(),
                  "ping_id": int(ping_id), "acquisition_source": acquisition_source,
                  "sample_count": int(raw.size), "sample_period_ns": int(sample_period_ns),
                  "active": False, "reason": "disabled", "baseline_adc": None,
                  "noise_sigma_adc": None, "reference_samples": 0,
                  "suppressed_fraction": 0., "protected_fraction": 0.,
                  "output": "original_adc", "calibrated_acoustic_power": False}
        if not self.previous_metadata and not self.legacy_metadata:
            status["impulse_reduction"] = {"active": False, "reason": "disabled"}
        area = boundary(sample_count=raw.size, sample_period_ns=sample_period_ns,
            tx_zero_sample_index=tx_zero_sample_index, sound_speed_mps=sound_speed_mps,
            near_field_m=self.near_field_m, valid_tx=valid_tx)
        if self.near_field_metadata:
            status["near_field"] = area
        output = mask_display(output, boundary=area, tx_zero_sample_index=tx_zero_sample_index)
        if area["reason"] == "no_usable_samples":
            status.update(reason="no_usable_samples", output="near_field_masked_adc_codes")
            return output, status
        if area["active"]:
            status["output"] = "near_field_masked_adc_codes"
        if self.mode == "off":
            return output, status
        if not valid_tx or sample_period_ns <= 0 or not 0 <= tx_zero_sample_index < raw.size:
            status["reason"] = "unverified_time_reference"
            return output, status
        start = area["analysis_start_sample"] if area["active"] else int(tx_zero_sample_index)
        values = raw[start:].astype(np.float64)
        n = len(values)
        protected = np.zeros(n, dtype=bool)
        guard = max(1, int(np.ceil(50000 / sample_period_ns)))
        for block in blocks:
            a = max(0, int(block.start_sample)-start-guard)
            b = min(n, int(block.end_sample)-start+guard+1)
            if b > a:
                protected[a:b] = True
        reference = ~protected
        blank = int(np.ceil(blanking_m*2/sound_speed_mps*1e9/sample_period_ns))
        reference[:max(0, int(tx_zero_sample_index)+blank-start)] = False
        status["reference_samples"] = int(reference.sum())
        if reference.sum() < 64:
            status["reason"] = "insufficient_noise_reference"
            return output, status
        # Robust coarse estimates, then interpolate in physical time. Unlike a
        # rolling raw median this never smooths or shifts an individual echo.
        cell = max(16, int(np.ceil(256000 / sample_period_ns)))
        centers, floors, sigmas = [], [], []
        # Keep cell locations tied to TX, not to the exclusion boundary. A
        # crop must not move every later noise reference to a different phase.
        first_cell = -((start-int(tx_zero_sample_index)) % cell)
        for a in range(first_cell, n, cell):
            b = min(n, a+cell)
            lo = max(0, a)
            refs = values[lo:b][reference[lo:b]]
            if len(refs) < max(16, (b-a)//3):
                continue
            median = float(np.median(refs))
            centers.append((a+b-1)/2)
            floors.append(median)
            sigmas.append(max(1., 1.4826*float(np.median(np.abs(refs-median)))))
        if not centers:
            status["reason"] = "insufficient_noise_reference"
            return output, status
        floor = np.asarray(floors)
        sigma = np.asarray(sigmas)
        # Use the same physical neighbourhood for short and full-search PINGs.
        # A distant low floor must not cap the near-water estimate and create
        # a bright column solely because the capture contains more far samples.
        if self.stripe_mode == "off":
            cap = np.median(floor)+3*np.median(sigma)
        else:
            cap = median_filter(floor, size=3, mode="nearest") + 3*median_filter(sigma, size=3, mode="nearest")
        floor = np.minimum(floor, cap)
        floor = median_filter(floor, size=3, mode="nearest")
        sigma = median_filter(sigma, size=3, mode="nearest")
        baseline = np.interp(np.arange(n), centers, floor)
        noise = np.interp(np.arange(n), centers, sigma)
        status.update(baseline_adc=round(float(np.median(baseline)), 4),
                      noise_sigma_adc=round(float(np.median(noise)), 4))
        if self.mode == "auto" and np.max(baseline) < 1 and np.max(noise) <= 1:
            status["reason"] = "no_measurable_background"
            return output, status
        residual = np.maximum(values-baseline, 0.)
        echo_protected = protected.copy()
        # Protect short coherent weak structures as well as detector blocks.
        # This gate only relaxes shrinkage, never creates an echo or changes it
        # into a claimed fish. Single-sample peaks are not deleted categorically.
        coherent = uniform_filter1d(values-baseline, size=3, mode="nearest") >= 2*noise
        protected |= coherent
        penalty = np.where(protected, 0., 2*noise)
        cleaned = np.rint(np.clip(residual-penalty, 0, 4095)).astype(np.uint16)
        if self.stripe_mode != "off" and self.impulse_mode != "off":
            separated, impulse = separate_impulses(
                raw[start:], raw[start:].astype(np.uint16), echo_protected,
                sample_period_ns=sample_period_ns, source=acquisition_source)
            status["impulse_reduction"] = impulse
            if impulse["active"]:
                # Re-estimate background after separation. Otherwise a train's
                # inflated MAD would still suppress a weak echo underneath it.
                # Detected blocks retain the exact previous display values.
                corrected = output.copy()
                corrected[start:] = separated
                revised, revised_status = NoiseReduction(self.mode,
                    stripe_mode=self.stripe_mode, impulse_mode="off",
                    near_field_m=self.near_field_m, near_field_metadata=self.near_field_metadata).process(
                    corrected, ping_id=ping_id, sample_period_ns=sample_period_ns,
                    tx_zero_sample_index=tx_zero_sample_index, sound_speed_mps=sound_speed_mps,
                    blanking_m=blanking_m, blocks=blocks, valid_tx=valid_tx,
                    acquisition_source=acquisition_source)
                cleaned = np.where(echo_protected, cleaned, revised[start:])
                impulse.update(background_reestimated=True,
                    corrected_baseline_adc=revised_status["baseline_adc"],
                    corrected_noise_sigma_adc=revised_status["noise_sigma_adc"])
        output[start:] = cleaned
        status.update(active=True, reason="automatic_background_estimate" if self.mode == "auto" else "enabled",
                      suppressed_fraction=round(float(np.mean(cleaned == 0)), 6),
                      protected_fraction=round(float(np.mean(protected)), 6),
                      output="background_reduced_adc_codes")
        return output, status

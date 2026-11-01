"""Inspectable 1-D sonar detection, tracking and provisional classification.

Raw PING samples remain authoritative.  This module only produces a versioned
derived view: robust envelope features -> CA-CFAR blocks -> trackpy association
-> FilterPy state smoothing -> deliberately provisional rule classes.
"""
from __future__ import annotations

from collections import deque
from dataclasses import asdict, dataclass, field, replace
import math
from typing import Iterable

import numpy as np
import pandas as pd
import trackpy as tp
from filterpy.kalman import KalmanFilter
from scipy.ndimage import gaussian_filter1d, median_filter
from scipy.signal import find_peaks, peak_widths
from scipy.stats import median_abs_deviation
from .near_field import boundary


RULE_CLASSES = frozenset({
    "bottom_like", "fish_like", "weed_like", "bubble_like", "multipath_like", "unknown",
})


@dataclass(frozen=True, slots=True)
class DetectionPolicy:
    """Versioned parameters stored alongside every derived PING."""

    mode: str = "auto"  # off | auto | on
    blanking_m: float = 0.50
    training_cells: int = 32
    guard_cells: int = 8
    cfar_scale: float = 4.5
    min_block_cells: int = 4
    max_block_cells: int = 640
    association_gate_m: float = 0.18
    association_geometry: bool = True
    max_track_misses: int = 3
    min_track_hits: int = 3
    track_window_pings: int = 48
    compact_envelope: bool = True
    compact_min_width_us: float = 20.0
    compact_max_width_us: float = 300.0
    compact_reference_windows: int = 4
    track_local_template: bool = True
    template_core_window_us: float | None = 200.0
    template_alignment_us: float = 10.0
    rule_version: str = "rules_v1"

    def __post_init__(self) -> None:
        if self.mode not in {"off", "auto", "on"}:
            raise ValueError("detection mode must be off, auto, or on")
        if self.training_cells < 4 or self.guard_cells < 0:
            raise ValueError("invalid CFAR window")
        if self.cfar_scale <= 1 or self.min_block_cells < 1:
            raise ValueError("invalid detector threshold or block width")
        if self.association_gate_m <= 0 or self.track_window_pings < 3:
            raise ValueError("invalid association policy")
        if type(self.association_geometry) is not bool:
            raise ValueError("invalid association geometry policy")
        if type(self.compact_envelope) is not bool or not 0 < self.compact_min_width_us < self.compact_max_width_us:
            raise ValueError("invalid compact envelope policy")
        if type(self.compact_reference_windows) is not int or self.compact_reference_windows not in {1,4}:
            raise ValueError("invalid compact noise reference windows")
        if type(self.track_local_template) is not bool:
            raise ValueError("invalid local template policy")
        if self.template_core_window_us is not None and (
                type(self.template_core_window_us) not in (int, float)
                or not math.isfinite(self.template_core_window_us)
                or not 100 <= self.template_core_window_us <= 400):
            raise ValueError("invalid template core window")
        if (type(self.template_alignment_us) not in (int, float)
                or not math.isfinite(self.template_alignment_us)
                or not 0 <= self.template_alignment_us <= 20):
            raise ValueError("invalid template alignment window")

    def public(self) -> dict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class EchoBlock:
    start_sample: int
    end_sample: int
    peak_sample: int
    depth_m: float
    width_m: float
    energy: float
    peak_amplitude: float
    local_noise: float
    snr_db: float
    prominence: float
    detector: str = "cfar"
    qualification_method: str = "ca_cfar_v1"
    template_ping_id: int | None = None
    template_correlation: float | None = None
    template_score: float | None = None
    template_fit_window: str | None = None
    template_fit_window_samples: int | None = None
    template_fit_offset_samples: int | None = None
    template_observed_peak_sample: int | None = None
    template_fit_preprocessing: str | None = None

    def public(self) -> dict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class Track:
    track_id: int
    depth_m: float
    velocity_mps: float
    hits: int
    age_pings: int
    misses: int
    confidence: float
    state: str
    kind: str = "echo_structure"
    classification: str = "unknown"
    classification_source: str = "rules_v1"
    rule_score: float = 0.0
    evidence: tuple[str, ...] = ()
    detector: str = "cfar"

    def public(self) -> dict:
        result = asdict(self)
        result["evidence"] = list(self.evidence)
        return result


@dataclass(frozen=True, slots=True)
class DetectionResult:
    valid_tx: bool
    ping_id: int
    time_utc_ns: int | None
    sample_period_ns: int
    tx_zero_sample_index: int
    blocks: tuple[EchoBlock, ...]
    tracks: tuple[Track, ...]
    selected: Track | None
    state: str
    reason: str | None
    acquisition_source: str = "native"

    def public(self) -> dict:
        return {
            "schema": "no5.sonard-detection/2",
            "valid_tx": self.valid_tx,
            "ping_id": self.ping_id,
            "time_utc_ns": self.time_utc_ns,
            "sample_period_ns": self.sample_period_ns,
            "tx_zero_sample_index": self.tx_zero_sample_index,
            "blocks": [block.public() for block in self.blocks],
            "tracks": [track.public() for track in self.tracks],
            "selected": None if self.selected is None else self.selected.public(),
            "state": self.state,
            "reason": self.reason,
            "acquisition_source": self.acquisition_source,
        }


@dataclass(slots=True)
class _TrackState:
    filter: KalmanFilter
    hits: int = 0
    age_pings: int = 0
    misses: int = 0
    latest_snr_db: float = 0.0
    latest_width_m: float = 0.0
    latest_energy: float = 0.0
    latest_detector: str = "cfar"
    latest_block: EchoBlock | None = None
    last_time_utc_ns: int | None = None
    evidence: deque[str] = field(default_factory=lambda: deque(maxlen=8))


def meters_per_sample(sample_period_ns: int, sound_speed_mps: float) -> float:
    value = sample_period_ns * 1e-9 * sound_speed_mps / 2.0
    if value <= 0:
        raise ValueError("sample period and sound speed must be positive")
    return value


def _cfar_threshold(signal: np.ndarray, *, training: int, guard: int,
                    scale: float) -> tuple[np.ndarray, np.ndarray]:
    """Cell-averaging CFAR threshold with explicit unsupported edge cells."""
    n = signal.size
    threshold = np.full(n, np.inf, dtype=np.float64)
    noise = np.full(n, np.nan, dtype=np.float64)
    first, last = training + guard, n - training - guard
    if last <= first:
        return threshold, noise
    cumulative = np.concatenate(([0.0], np.cumsum(signal, dtype=np.float64)))
    indices = np.arange(first, last)
    left = cumulative[indices - guard] - cumulative[indices - guard - training]
    right = cumulative[indices + guard + training + 1] - cumulative[indices + guard + 1]
    local = (left + right) / float(2 * training)
    noise[indices] = local
    threshold[indices] = np.maximum(local * scale, 1.0)
    return threshold, noise


def _contiguous(mask: np.ndarray) -> Iterable[tuple[int, int]]:
    edges = np.diff(np.r_[False, mask, False].astype(np.int8))
    return zip(np.flatnonzero(edges == 1).tolist(), np.flatnonzero(edges == -1).tolist())


def _coarse_max_pool(values: np.ndarray, *, bins: int) -> np.ndarray:
    """Keep a short direct return intact while reducing a long ADC trace."""
    edges = np.linspace(0, values.size, bins + 1, dtype=np.int64)
    return np.asarray([values[start:max(start + 1, end)].max()
                       for start, end in zip(edges[:-1], edges[1:])], dtype=np.float64)


def _quiet_reference(values: np.ndarray, windows: int) -> np.ndarray:
    """Use a contiguous quiet part, rather than count a nearby echo as noise.

    Smallest-of four reference windows retains real adjacent noise samples;
    it does not sort individual amplitudes or subtract a predicted echo.
    Peak prominence/physical width and track confirmation remain independent.
    """
    if len(values)<16*windows:
        return values
    return min(np.array_split(values,windows),key=lambda part:float(np.median(part)))


def _direct_envelope_block(raw: np.ndarray, *, tx_zero_sample_index: int,
                           sample_period_ns: int, sound_speed_mps: float,
                           policy: DetectionPolicy) -> EchoBlock | None:
    """Find the first broad direct return after transmitter ring-down.

    CA-CFAR correctly adapts to local noise, but a wide return can fill both
    of its training windows.  It then has no contrast left to detect.  This
    complementary coarse gate takes its reference only from the first useful
    post-TX interval, requires a sustained onset, and is still confirmed by
    the normal tracker before it can become a range decision.
    """
    mps = meters_per_sample(sample_period_ns, sound_speed_mps)
    first = max(0, tx_zero_sample_index + math.ceil(policy.blanking_m / mps))
    if first >= raw.size - 32:
        return None
    bins = min(1024, raw.size)
    # The broad-return gate evaluates an envelope at its coarse resolution.
    # Smooth one coarse bin before estimating both signal and noise; otherwise
    # max-pool phase ripple can break a sustained return into isolated cells.
    original_pooled = _coarse_max_pool(raw, bins=bins)
    pooled = gaussian_filter1d(original_pooled, sigma=1.0)
    samples_per_bin = raw.size / float(bins)
    first_bin = min(bins - 1, int(math.ceil(first / samples_per_bin)))
    # 45 cm of quiet water after blanking is enough to establish a robust
    # baseline, yet remains ahead of the expected short-range wall/ground
    # return in the bathtub and in normal shallow-water operation.
    noise_bins = max(16, int(math.ceil(0.45 / (mps * samples_per_bin))))
    noise_end = min(bins, first_bin + noise_bins)
    noise_region = pooled[first_bin:noise_end]
    if noise_region.size < 8:
        return None
    baseline = float(np.median(noise_region))
    mad = float(median_abs_deviation(noise_region, scale="normal"))
    gate = baseline + max(10.0, 5.0 * max(1.0, mad))
    visible_end = min(bins, int(math.ceil(10.0 / (mps * samples_per_bin))))
    sustained = 5
    onset = next((index for index in range(first_bin, max(first_bin, visible_end - sustained))
                  if np.all(pooled[index:index + sustained] > gate)), None)
    if onset is None:
        return None
    # Limit the peak search to the direct envelope; later bathtub reverberation
    # and bottom multiples cannot replace the initial return.
    end_bin = min(visible_end, onset + max(sustained, int(math.ceil(0.75 / (mps * samples_per_bin)))))
    # Preserve the original peak/plateau geometry; smoothing qualifies the
    # envelope but must not move a flat return to the far end of its plateau.
    peak_bin = onset + int(np.argmax(original_pooled[onset:end_bin]))
    peak_value = float(original_pooled[peak_bin])
    amplitude = max(0.0, peak_value - baseline)
    snr_db = 20.0 * math.log10(max(amplitude, 1.0) / max(1.0, mad))
    start_sample = int(round(onset * samples_per_bin))
    end_sample = int(round(end_bin * samples_per_bin))
    peak_sample = int(round(peak_bin * samples_per_bin))
    return EchoBlock(
        start_sample=start_sample, end_sample=end_sample, peak_sample=peak_sample,
        depth_m=float((peak_sample - tx_zero_sample_index) * mps),
        width_m=float((end_sample - start_sample) * mps),
        energy=float(np.sum(np.maximum(pooled[onset:end_bin] - baseline, 0.0))),
        peak_amplitude=amplitude, local_noise=mad, snr_db=snr_db,
        prominence=amplitude, detector="direct_envelope",
        qualification_method="coarse_sustained_v1",
    )


def _compact_envelope_blocks(raw: np.ndarray, *, tx_zero_sample_index: int,
                            sample_period_ns: int, sound_speed_mps: float,
                            policy: DetectionPolicy) -> tuple[EchoBlock, ...]:
    """A resolved pulse needs measured height, prominence and physical width.

    The coarse sustained gate requires five display cells; at 2us/8192 this
    loses legitimate pulses shorter than 80us and max-pool ADC outliers can
    move its chosen peak. Median rejection and a short Gaussian operate at
    ADC resolution. This gate never fills a missed ping from a track prior.
    """
    if not policy.compact_envelope:
        return ()
    period_us = sample_period_ns / 1000.0
    mps = meters_per_sample(sample_period_ns, sound_speed_mps)
    first = max(0, tx_zero_sample_index + math.ceil(policy.blanking_m / mps))
    end = min(raw.size, tx_zero_sample_index + math.ceil(10.0 / mps))
    noise_end = min(end, first + math.ceil(0.45 / mps))
    if noise_end-first < 16:
        return ()
    median_cells = max(1, int(round(10.0 / period_us)) | 1)
    smooth = gaussian_filter1d(median_filter(raw, size=median_cells), sigma=max(.5, 8.0/period_us))
    reference = _quiet_reference(smooth[first:noise_end],policy.compact_reference_windows)
    baseline = float(np.median(reference))
    noise = max(1.0, float(median_abs_deviation(reference, scale="normal")))
    margin = max(10.0, 6.0*noise)
    peaks, properties = find_peaks(smooth[first:end], height=baseline+margin,
        prominence=margin, width=(max(1.0, policy.compact_min_width_us/period_us),
                                  policy.compact_max_width_us/period_us))
    if not peaks.size:
        return ()
    # The first acceptable peak can be a small leading ripple. Use the most
    # prominent resolved pulse; CFAR preserves other independently found blocks.
    indices = [int(np.argmax(properties['prominences']))]
    blocks = []
    for index in indices:
        peak = first + int(peaks[index])
        start = first + float(properties['left_ips'][index])
        stop = first + float(properties['right_ips'][index])
        amplitude = float(smooth[peak]-baseline)
        blocks.append(EchoBlock(int(math.floor(start)), int(math.ceil(stop)), peak,
            (peak-tx_zero_sample_index)*mps, (stop-start)*mps,
            float(np.sum(np.maximum(smooth[int(start):int(math.ceil(stop))]-baseline, 0))),
            amplitude, noise, 20.0*math.log10(amplitude/noise),
            float(properties['prominences'][index]), detector="direct_envelope",
            qualification_method="median_gaussian_prominence_v1"))
    return tuple(blocks)


def detect_echo_blocks(samples: np.ndarray, *, tx_zero_sample_index: int,
                       sample_period_ns: int, sound_speed_mps: float,
                       policy: DetectionPolicy, post_blanking=False) -> tuple[EchoBlock, ...]:
    """Return CA-CFAR-qualified echo blocks with SciPy peak geometry."""
    raw = np.asarray(samples, dtype=np.float64)
    minimum = 2 * (policy.training_cells + policy.guard_cells) + 8
    if raw.ndim != 1 or raw.size < minimum:
        raise ValueError("sample vector is too short for the configured CFAR window")
    mps = meters_per_sample(sample_period_ns, sound_speed_mps)
    first = max(0, tx_zero_sample_index + math.ceil(policy.blanking_m / mps))
    reference_end = min(raw.size, max(first, 32))
    reference = ( _quiet_reference(raw[:max(32, math.ceil(.45/mps))], policy.compact_reference_windows)
                  if post_blanking else raw[:reference_end])
    baseline = float(np.median(reference))
    mad = float(median_abs_deviation(reference, scale="normal"))
    envelope = np.abs(raw - baseline)
    threshold, noise = _cfar_threshold(envelope, training=policy.training_cells,
                                        guard=policy.guard_cells, scale=policy.cfar_scale)
    # find_peaks/peak_widths make the returned geometry stable and inspectable;
    # CFAR still makes the local acceptance decision.
    peaks, peak_properties = find_peaks(envelope, height=np.nan_to_num(threshold, nan=np.inf),
                                        prominence=max(1.0, 3.0 * mad))
    widths = {}
    if peaks.size:
        values = peak_widths(envelope, peaks, rel_height=0.5)[0]
        widths = {int(peak): float(width) for peak, width in zip(peaks, values)}
    hits = envelope > threshold
    hits[:first] = False
    blocks: list[EchoBlock] = []
    for start, end in _contiguous(hits):
        cells = end - start
        if cells < policy.min_block_cells or cells > policy.max_block_cells:
            continue
        local = envelope[start:end]
        peak = start + int(np.argmax(local))
        local_noise = float(np.nanmedian(noise[start:end]))
        amplitude = float(envelope[peak])
        snr_db = 20.0 * math.log10(max(amplitude, 1.0) / max(local_noise, 1.0))
        prominence = float(peak_properties["prominences"][np.where(peaks == peak)[0][0]]
                           if peak in widths else amplitude)
        width_cells = max(float(cells), widths.get(peak, float(cells)))
        blocks.append(EchoBlock(
            start_sample=start, end_sample=end, peak_sample=peak,
            depth_m=float((peak - tx_zero_sample_index) * mps),
            width_m=float(width_cells * mps), energy=float(np.sum(local)),
            peak_amplitude=amplitude, local_noise=local_noise, snr_db=snr_db,
            prominence=prominence,
        ))
    # Unrelated narrow CFAR hits must not hide a broad direct return whose
    # training cells contain the return itself. Avoid duplicating overlapping
    # observations; both gates still require temporal confirmation.
    compact = _compact_envelope_blocks(raw, tx_zero_sample_index=tx_zero_sample_index,
                                    sample_period_ns=sample_period_ns,
                                    sound_speed_mps=sound_speed_mps, policy=policy)
    coarse = _direct_envelope_block(raw, tx_zero_sample_index=tx_zero_sample_index,
                                    sample_period_ns=sample_period_ns,
                                    sound_speed_mps=sound_speed_mps, policy=policy)
    for direct in (*compact, coarse):
        if direct is not None and not any(
                abs(block.peak_sample - direct.peak_sample) * mps <= 0.03
                for block in blocks):
            blocks.append(direct)
    return tuple(blocks)


class EchoTracker:
    """trackpy associates 1-D blocks; FilterPy keeps a depth/velocity state."""

    def __init__(self, policy: DetectionPolicy) -> None:
        self.policy = policy
        self._frame = 0
        self._observations: deque[dict] = deque(maxlen=4096)
        self._next_track_id = 1
        self._states: dict[int, _TrackState] = {}

    @staticmethod
    def _new_filter(depth_m: float) -> KalmanFilter:
        filt = KalmanFilter(dim_x=2, dim_z=1)
        filt.x = np.array([[depth_m], [0.0]])
        filt.F = np.array([[1.0, 1.0], [0.0, 1.0]])
        filt.H = np.array([[1.0, 0.0]])
        filt.P *= 0.05
        filt.R *= 0.01
        filt.Q = np.array([[0.003, 0.0], [0.0, 0.03]])
        return filt

    def _linked_latest(self, blocks: tuple[EchoBlock, ...]) -> dict[int, int]:
        start = max(0, self._frame - self.policy.track_window_pings + 1)
        rows = [row for row in self._observations if row['frame'] >= start]
        rows.extend({'frame': self._frame, 'depth_m': block.depth_m,
                     'geometry': self._geometry(block),
                     'block_index': index, 'track_id': None}
                    for index, block in enumerate(blocks))
        if not rows:
            return {}
        positions = ['depth_m', 'geometry'] if self.policy.association_geometry else ['depth_m']
        # Keep link_df's exact rolling window, frame order and Linker settings.
        # Its DataFrame copy, grouping and repeated anchor scans are avoidable.
        # Huge external IDs retain pandas' original dtype/conversion behaviour.
        if getattr(tp, '__version__', None) != '0.7' or any(not -(1 << 63) <= row['frame'] < (1 << 63) or
               (row['track_id'] is not None and abs(row['track_id']) > (1 << 53))
               for row in rows):
            return self._link_rows_dataframe(rows, positions)
        frames = np.asarray([row['frame'] for row in rows], dtype=np.int64)
        order = np.argsort(frames, kind='quicksort')  # pandas_sort's default
        frames = frames[order]
        coordinates = np.asarray([[row[key] for key in positions] for row in rows],
                                 dtype=np.float64)[order]
        unique, counts = np.unique(frames, return_counts=True)
        grouped = dict(zip(unique, np.split(coordinates, np.cumsum(counts)[:-1])))
        empty = np.empty((0, len(positions)))

        def levels():
            for frame in range(int(unique[0]), int(unique[-1]) + 1):
                # coords_from_df includes empty frames: memory ages identically.
                yield frame, grouped.get(frame, empty)

        particles = []
        for _, ids in tp.link_iter(levels(), search_range=self.policy.association_gate_m,
                                   memory=self.policy.max_track_misses, adaptive_stop=0.01,
                                   neighbor_strategy='KDTree', link_strategy='auto'):
            particles.extend(ids)
        # Each particle has at most one observation per frame. Reverse the
        # sorted history once, preserving the latest-first anchor precedence.
        anchors = {}
        for index in range(len(order) - 1, -1, -1):
            row = rows[order[index]]
            if row['frame'] < self._frame and row['track_id'] is not None:
                anchors.setdefault(particles[index], []).append(int(row['track_id']))
        links, used = {}, set()
        for index in np.flatnonzero(frames == self._frame):
            track_id = next((value for value in anchors.get(particles[index], ())
                             if value in self._states and value not in used), None)
            if track_id is None:
                track_id = self._next_track_id
                self._next_track_id += 1
            links[int(rows[order[index]]['block_index'])] = track_id
            used.add(track_id)
        return links

    def _link_rows_dataframe(self, rows, positions):
        """Original path for values outside the exact NumPy frame/ID domain."""
        linked = tp.link_df(pd.DataFrame(rows), search_range=self.policy.association_gate_m,
                            pos_columns=positions, t_column='frame',
                            memory=self.policy.max_track_misses, adaptive_stop=0.01,
                            neighbor_strategy='KDTree', link_strategy='auto')
        links, used = {}, set()
        for row in linked[linked['frame'] == self._frame].itertuples(index=False):
            # Particle numbers are local to this rolling link_df call. Bridge
            # through actual shared observations, never reuse those numbers as
            # persistent Kalman/confidence identities.
            anchors = linked[(linked['particle'] == row.particle) &
                             (linked['frame'] < self._frame)].sort_values('frame', ascending=False)
            track_id = next((int(value) for value in anchors['track_id'].dropna()
                             if int(value) in self._states and int(value) not in used), None)
            if track_id is None:
                track_id = self._next_track_id; self._next_track_id += 1
            links[int(row.block_index)] = track_id; used.add(track_id)
        return links

    def _geometry(self, block: EchoBlock) -> float:
        # The max-pooled sustained gate locates a broad search envelope, not
        # the same resolved peak as ADC-CFAR/median/template geometry. Treating
        # both as interchangeable points let a noisy coarse peak steal an
        # established compact track. A separate trackpy coordinate preserves
        # BOTH observations without blending their different peak references.
        # Separation exceeds the association radius; depth limits stay equal.
        return (2.0*self.policy.association_gate_m
                if block.qualification_method == 'coarse_sustained_v1' else 0.0)

    def _classify(self, track_id: int, state: _TrackState, *,
                  current_blocks: tuple[EchoBlock, ...], current_track_depths: dict[int, float]) -> tuple[str, float, tuple[str, ...]]:
        depth = float(state.filter.x[0, 0])
        width, snr, velocity = state.latest_width_m, state.latest_snr_db, abs(float(state.filter.x[1, 0]))
        evidence = list(state.evidence)
        prior = [item for key, item in self._states.items()
                 if key != track_id and item.hits >= self.policy.min_track_hits
                 and item.misses == 0 and float(item.filter.x[0, 0]) < depth-0.15
                 and abs(float(item.filter.x[1, 0])) < .12
                 and item.latest_energy > state.latest_energy*.6]
        if (state.hits >= self.policy.min_track_hits and state.misses == 0 and velocity < .12
                and any(abs(depth/float(item.filter.x[0, 0])-2.0) <= .22 for item in prior)):
            evidence.append("stable later depth is near 2x a stronger confirmed earlier track")
            return "multipath_like", 0.82, tuple(evidence[-4:])
        deepest = max((current_track_depths[key] for key, item in self._states.items()
                       if item.hits >= self.policy.min_track_hits and item.misses == 0
                       and abs(float(item.filter.x[1, 0])) < .12), default=depth)
        if depth >= deepest - 0.08 and state.hits >= self.policy.min_track_hits and velocity < 0.12:
            evidence.append("deepest persistent track is stable")
            return "bottom_like", 0.68 if width >= 0.02 else 0.56, tuple(evidence[-4:])
        if velocity > 0.35 and state.hits <= 5:
            evidence.append("short, high-velocity track")
            return "bubble_like", 0.57, tuple(evidence[-4:])
        if width >= 0.07 and state.hits >= self.policy.min_track_hits and depth >= deepest - 0.5:
            evidence.append("wide persistent return near bottom")
            return "weed_like", 0.55, tuple(evidence[-4:])
        if width <= 0.05 and snr >= 9.0 and state.hits >= self.policy.min_track_hits:
            evidence.append("compact persistent return separated from bottom")
            return "fish_like", 0.52, tuple(evidence[-4:])
        evidence.append("no rule reaches provisional threshold")
        return "unknown", 0.0, tuple(evidence[-4:])

    def update(self, blocks: Iterable[EchoBlock], *, time_utc_ns: int | None) -> tuple[Track, ...]:
        blocks = tuple(blocks)
        self._frame += 1
        links = self._linked_latest(blocks)
        seen = set(links.values())
        for block_index, track_id in links.items():
            block = blocks[block_index]
            state = self._states.get(track_id)
            if state is None:
                state = _TrackState(self._new_filter(block.depth_m))
                self._states[track_id] = state
            dt = 1.0
            if time_utc_ns is not None and state.last_time_utc_ns is not None:
                dt = max(0.001, (time_utc_ns - state.last_time_utc_ns) * 1e-9)
            state.filter.F[0, 1] = dt
            state.filter.predict()
            state.filter.update(np.array([[block.depth_m]]))
            state.hits += 1
            state.age_pings += 1
            state.misses = 0
            state.latest_snr_db, state.latest_width_m = block.snr_db, block.width_m
            state.latest_energy, state.last_time_utc_ns = block.energy, time_utc_ns
            state.latest_detector = block.detector
            state.latest_block = block
            state.evidence.append(f"{block.detector} block {block.depth_m:.3f}m / {block.snr_db:.1f}dB")
            self._observations.append({'frame': self._frame, 'depth_m': block.depth_m,
                                       'geometry': self._geometry(block),
                                       'block_index': block_index, 'track_id': track_id})
        for track_id, state in list(self._states.items()):
            if track_id not in seen:
                state.misses += 1
                state.age_pings += 1
            if state.misses > self.policy.max_track_misses:
                del self._states[track_id]
        depths = {track_id: float(item.filter.x[0, 0]) for track_id, item in self._states.items()}
        tracks = []
        for track_id, state in self._states.items():
            persistence = min(1.0, state.hits / float(self.policy.min_track_hits + 2))
            snr = min(1.0, max(0.0, (state.latest_snr_db - 3.0) / 24.0))
            continuity = 1.0 - min(1.0, state.misses / float(self.policy.max_track_misses + 1))
            confidence = 0.50 * persistence + 0.30 * snr + 0.20 * continuity
            status = "confirmed" if state.hits >= self.policy.min_track_hits and state.misses == 0 else (
                "stale" if state.misses else "tentative")
            label, score, evidence = self._classify(track_id, state, current_blocks=blocks,
                                                     current_track_depths=depths)
            tracks.append(Track(track_id, round(depths[track_id], 6),
                                round(float(state.filter.x[1, 0]), 6), state.hits,
                                state.age_pings, state.misses, round(confidence, 5), status,
                                classification=label, classification_source=self.policy.rule_version,
                                rule_score=round(score, 5), evidence=evidence,
                                detector=state.latest_detector))
        return tuple(sorted(tracks, key=lambda item: item.track_id))


def _template_envelope(samples, sample_period_ns):
    period_us = sample_period_ns/1000.0
    cells = max(1, int(round(10.0/period_us)) | 1)
    values = median_filter(np.asarray(samples, dtype=float), size=cells)
    return values, gaussian_filter1d(values, sigma=max(.5, 8.0/period_us)), cells


def _local_template_block(samples, *, template: dict, expected_depth_m: float,
                          policy: DetectionPolicy, tx_zero_sample_index: int,
                          sample_period_ns: int, sound_speed_mps: float,
                          prepared=None) -> EchoBlock | None:
    """Qualify a CURRENT weak pulse against a previously measured clean pulse.

    A constant/linear background is fitted independently. Residual lag-one
    correlation inflates coefficient uncertainty; the six-standard-error and
    shape gates are not calibrated probabilities for arbitrary marine noise.
    No template value is ever emitted as a measured profile or missing depth.
    """
    mps = meters_per_sample(sample_period_ns, sound_speed_mps)
    period_us = sample_period_ns/1000.0
    values, smooth, cells = (_template_envelope(samples, sample_period_ns)
                             if prepared is None else prepared)
    centre = tx_zero_sample_index+round(expected_depth_m/mps)
    gate = math.ceil(policy.association_gate_m/mps)
    first = max(0, tx_zero_sample_index+math.ceil(policy.blanking_m/mps))
    lo, hi = max(first,centre-gate), min(len(values),centre+gate+1)
    if hi-lo < 16:
        return None
    peaks, props = find_peaks(smooth[lo:hi],prominence=10,
        width=(max(1,policy.compact_min_width_us/period_us),policy.compact_max_width_us/period_us))
    full = template['values'];full_half = len(full)//2
    windows = [('measured_full_v1', full, (0,), values, 'median_v1')]
    alignment = int(policy.template_alignment_us/period_us)
    if alignment:
        windows.append(('measured_full_v1', full,
                        tuple(k for k in range(-alignment, alignment+1) if k), values, 'median_v1'))
    if policy.template_core_window_us is not None:
        half = round(policy.template_core_window_us/(2*period_us))
        if 8 <= half < full_half:
            windows.append(('measured_core_v1', full[full_half-half:full_half+half+1], (0,), values, 'median_v1'))
    # A varying late tail must not veto a matching measured pulse core. The
    # one fixed physical core window is a fallback, not a fitted/free window.
    # Its CURRENT peak, width, amplitude, 6-sigma and .8 shape gates all remain.
    # Peak geometry already uses this 8us Gaussian. Fit the measured template
    # through the SAME filter when ADC noise masks its unsmoothed shape. Keep
    # the original fit first, count the smoothing correlation in uncertainty,
    # and still require a current peak, six-sigma amplitude and .8 similarity.
    sigma=max(.5,8.0/period_us)
    windows += [(name+'_smoothed_v1', gaussian_filter1d(shape,sigma=sigma), offsets,
                 smooth, 'median_gaussian_v1')
                for name,shape,offsets,_,_ in list(windows)]
    for window, shape, offsets, observed_values, preprocessing in windows:
        half = len(shape)//2
        axis = np.linspace(-1,1,len(shape));background = np.column_stack([np.ones(len(shape)),axis])
        design = np.column_stack([background,shape]);covariance = np.linalg.inv(design.T@design)
        orthogonal = shape-background@np.linalg.lstsq(background,shape,rcond=None)[0]
        best = None
        for index, offset, alignment_offset in (
                (index, offset, shift) for index, offset in enumerate(peaks) for shift in offsets):
            observed_peak = lo+int(offset)
            peak = observed_peak+alignment_offset
            if (preprocessing=='median_gaussian_v1'
                    and abs(peak-centre)*mps>min(.03,policy.association_gate_m)):
                # Filtering reduces shape detail. It may recover the current
                # established return, never substitute a nearby reflector.
                continue
            start, stop = lo+float(props['left_ips'][index]), lo+float(props['right_ips'][index])
            if not start <= peak <= stop:
                continue
            a, b = peak-half, peak+half+1
            if a < first or b > len(values):
                continue
            observed = observed_values[a:b]
            coefficients = np.linalg.lstsq(design,observed,rcond=None)[0]
            residual = observed-design@coefficients
            variance = float(residual@residual)/max(1,len(observed)-3)
            rho = float(residual[1:]@residual[:-1])/max(1e-9,float(residual@residual))
            rho = float(np.clip(rho,0,.99))
            smoothing_cells = math.ceil(2*math.sqrt(math.pi)*sigma) if preprocessing=='median_gaussian_v1' else 1
            inflation = max(cells,smoothing_cells,(1+rho)/(1-rho))
            uncertainty = math.sqrt(max(1.0,variance)*covariance[2,2]*inflation)
            score = float(coefficients[2])/uncertainty
            detrended = observed-background@np.linalg.lstsq(background,observed,rcond=None)[0]
            correlation = float(detrended@orthogonal)/max(1e-9,float(np.linalg.norm(detrended)*np.linalg.norm(orthogonal)))
            if score < 6 or correlation < .8 or coefficients[2] < 10:
                continue
            amplitude = max(0.0,float(observed[half]-background[half]@coefficients[:2]))
            block = EchoBlock(int(math.floor(start)),int(math.ceil(stop)),peak,
                (peak-tx_zero_sample_index)*mps,(stop-start)*mps,
                float(np.maximum(observed-background@coefficients[:2],0).sum()),
                amplitude,uncertainty,20*math.log10(max(1,amplitude)/max(1,uncertainty)),
                float(props['prominences'][index]),detector='direct_envelope',
                qualification_method='track_local_measured_template_v1',
                template_ping_id=template['ping_id'],template_correlation=round(correlation,6),
                template_score=round(score,6),template_fit_window=window,
                template_fit_window_samples=len(shape),
                template_fit_offset_samples=alignment_offset,
                template_observed_peak_sample=observed_peak,
                template_fit_preprocessing=preprocessing)
            if best is None or score > best.template_score:
                best = block
        if best is not None:
            return best
    return None


class DetectionPipeline:
    """CFAR + SciPy geometry + trackpy/FilterPy tracks + rule hypotheses."""

    def __init__(self, policy: DetectionPolicy | None = None, *, sound_speed_mps: float = 1500.0) -> None:
        self.policy = policy or DetectionPolicy()
        self.sound_speed_mps = sound_speed_mps
        self.tracker = EchoTracker(self.policy)
        self._trackers = {"native": self.tracker, "own": EchoTracker(self.policy)}
        self._selected_ids = {"native": None, "own": None}
        self._templates: dict[str, dict] = {"native": {}, "own": {}}
        self.history: deque[DetectionResult] = deque(maxlen=64)

    def set_mode(self, mode: str) -> DetectionPolicy:
        self.policy = DetectionPolicy(**{**self.policy.public(), "mode": mode})
        for tracker in self._trackers.values():
            tracker.policy = self.policy
        return self.policy

    def process(self, *, ping_id: int, samples: np.ndarray, tx_zero_sample_index: int,
                sample_period_ns: int, time_utc_ns: int | None, valid_tx: bool,
                acquisition_source: str = "native", near_field_m: float = 0.) -> DetectionResult:
        if acquisition_source not in self._trackers:
            raise ValueError("acquisition source must be own or native")
        if not valid_tx:
            result = DetectionResult(False, ping_id, time_utc_ns, sample_period_ns,
                                     tx_zero_sample_index, (), (), None, "not_applicable",
                                     "TX reference is not verified for this ping")
        elif self.policy.mode == "off":
            result = DetectionResult(True, ping_id, time_utc_ns, sample_period_ns,
                                     tx_zero_sample_index, (), (), None, "off", "disabled by operator")
        else:
            area = boundary(sample_count=len(samples), sample_period_ns=sample_period_ns,
                tx_zero_sample_index=tx_zero_sample_index, sound_speed_mps=self.sound_speed_mps,
                near_field_m=near_field_m, valid_tx=valid_tx)
            offset = area['analysis_start_sample'] if area['active'] else 0
            analysis = np.asarray(samples)[offset:]
            origin = tx_zero_sample_index-offset
            def original(block):
                return replace(block, start_sample=block.start_sample+offset,
                    end_sample=block.end_sample+offset, peak_sample=block.peak_sample+offset,
                    template_observed_peak_sample=(None if block.template_observed_peak_sample is None
                                                  else block.template_observed_peak_sample+offset)) if offset else block
            enough = len(analysis) >= 2*(self.policy.training_cells+self.policy.guard_cells)+8
            blocks = tuple(original(b) for b in detect_echo_blocks(analysis, tx_zero_sample_index=origin,
                                        sample_period_ns=sample_period_ns,
                                        sound_speed_mps=self.sound_speed_mps, policy=self.policy,
                                        post_blanking=bool(offset))) if enough else ()
            tracker = self._trackers[acquisition_source]
            previous_id = self._selected_ids[acquisition_source]
            prior = tracker._states.get(previous_id)
            templates = self._templates[acquisition_source].get(previous_id, [])
            if (enough and self.policy.track_local_template and prior is not None and templates
                    and prior.hits >= self.policy.min_track_hits+2
                    and time_utc_ns is not None and prior.last_time_utc_ns is not None
                    and 0 <= time_utc_ns-prior.last_time_utc_ns <= 500_000_000
                    and not any(abs(b.depth_m-float(prior.filter.x[0, 0])) <= .03 for b in blocks)):
                prepared = _template_envelope(analysis, sample_period_ns)
                fits = [_local_template_block(analysis, template=template,
                        expected_depth_m=float(prior.filter.x[0, 0]), policy=self.policy,
                        tx_zero_sample_index=origin, sample_period_ns=sample_period_ns,
                        sound_speed_mps=self.sound_speed_mps, prepared=prepared)
                        for template in templates if template['period_ns'] == sample_period_ns]
                measured = max((fit for fit in fits if fit is not None),
                               key=lambda fit: (fit.template_fit_window == 'measured_full_v1',
                                                fit.template_score), default=None)
                if measured is not None:
                    measured = original(measured)
                    # Replace only a duplicate observation of the SAME current
                    # peak. A nearby different reflector must not veto the fit.
                    blocks = (*(b for b in blocks if abs(b.depth_m-measured.depth_m) > .03), measured)
            tracks = self._trackers[acquisition_source].update(blocks, time_utc_ns=time_utc_ns)
            confirmed = [track for track in tracks
                         if track.state == "confirmed" and track.classification != "multipath_like"]
            # Keep a currently measured established return when a new shallow
            # reflection appears. Never publish the stale Kalman prediction.
            selected = next((track for track in confirmed
                             if track.track_id == self._selected_ids[acquisition_source]), None)
            # A brief loss of the established bottom is not evidence that a
            # different reflector became the bottom. Do not output its prior.
            if selected is None and (previous_id not in tracker._states or any(
                    t.track_id == previous_id and t.classification == 'multipath_like' for t in tracks)):
                selected = min(confirmed, key=lambda track: (
                    -min(track.hits, self.policy.track_window_pings),
                    tracker._states[track.track_id].latest_block.qualification_method
                        != 'median_gaussian_prominence_v1', track.depth_m), default=None)
            if selected is not None:
                self._selected_ids[acquisition_source] = selected.track_id
                if self.policy.track_local_template and selected.hits >= self.policy.min_track_hits+2:
                    linked_block = tracker._states[selected.track_id].latest_block
                    compact = [linked_block] if (linked_block is not None
                        and linked_block.qualification_method == 'median_gaussian_prominence_v1'
                        and linked_block.snr_db >= 24) else []
                    for block in compact:
                        half = max(16, math.ceil((block.end_sample-block.start_sample)*1.3))
                        a, b = block.peak_sample-half, block.peak_sample+half+1
                        if a < offset or b > len(samples):
                            continue
                        cells = max(1,int(round(10000/sample_period_ns)) | 1)
                        values = median_filter(analysis.astype(float),size=cells)[a-offset:b-offset].copy()
                        axis = np.linspace(-1,1,len(values)); background = np.column_stack([np.ones(len(values)),axis])
                        values -= background @ np.linalg.lstsq(background,values,rcond=None)[0]
                        span = float(np.ptp(values))
                        if span < 10:
                            continue
                        bank = self._templates[acquisition_source].setdefault(selected.track_id, [])
                        bank.append({
                            'values':values/span,'snr_db':block.snr_db,'ping_id':ping_id,
                            'period_ns':sample_period_ns})
                        # Recent measured shape variations, bounded to eight;
                        # retaining only the loudest pulse lost weaker returns
                        # when its asymmetric tail changed with the TX phase.
                        del bank[:-8]
            self._templates[acquisition_source] = {k:v for k,v in self._templates[acquisition_source].items()
                                                    if k in tracker._states}
            result = DetectionResult(True, ping_id, time_utc_ns, sample_period_ns,
                                     tx_zero_sample_index, blocks, tracks, selected,
                                     "active" if selected else "evaluating",
                                     None if selected else "waiting for a persistent non-multipath echo track")
        result = replace(result, acquisition_source=acquisition_source)
        self.history.append(result)
        return result

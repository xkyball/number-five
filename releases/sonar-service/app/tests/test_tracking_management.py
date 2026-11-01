"""Temporal and memory-bound regressions against the installed0.4.30 source."""
from dataclasses import asdict
import importlib.util
from pathlib import Path
import sys

import numpy as np
import pytest

from no5_sonard_openecho import detection


@pytest.fixture(scope='module')
def reference():
    path = Path(__file__).resolve().parents[2] / 'no5-rebuild/templates/sonar-tracking-0430-reference/detection.py'
    name = 'no5_sonard_openecho._test_tracking_reference'
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def block(module, depth):
    return module.EchoBlock(100, 106, 103, depth, .02, 100., 200., 1., 20., 30.)


def compare(reference, x, y, depths, frame):
    left = x.update([block(reference, v) for v in depths], time_utc_ns=frame * 55_000_000)
    right = y.update([block(detection, v) for v in depths], time_utc_ns=frame * 55_000_000)
    assert [asdict(v) for v in left] == [asdict(v) for v in right]
    assert list(x._observations) == list(y._observations)
    assert list(x._states) == list(y._states)
    assert x._next_track_id == y._next_track_id
    for key in x._states:
        for attr in ('x', 'P', 'x_prior', 'P_prior', 'x_post', 'P_post', 'K', 'y'):
            a, b = getattr(x._states[key].filter, attr), getattr(y._states[key].filter, attr)
            assert a.dtype == b.dtype and a.tobytes() == b.tobytes()


def test_observation_capacity_eviction_keeps_exact_tracks(reference):
    x = reference.EchoTracker(reference.DetectionPolicy(track_window_pings=3))
    y = detection.EchoTracker(detection.DetectionPolicy(track_window_pings=3))
    for frame in range(265):
        compare(reference, x, y, [1 + .4 * i for i in range(16)], frame)
        assert len(y._observations) <= 4096
    assert len(y._observations) == y._observations.maxlen == 4096
    assert set(vars(x)) == set(vars(y))  # No new persistent history/cache.


def test_large_persistent_ids_preserve_pandas_float_conversion(reference):
    x = reference.EchoTracker(reference.DetectionPolicy())
    y = detection.EchoTracker(detection.DetectionPolicy())
    x._next_track_id = y._next_track_id = 2**53 + 1
    for frame in range(8):
        compare(reference, x, y, [1., 2.], frame)


def test_unreviewed_trackpy_version_retains_original_path(reference, monkeypatch):
    monkeypatch.setattr(detection.tp, '__version__', 'unreviewed')
    x = reference.EchoTracker(reference.DetectionPolicy())
    y = detection.EchoTracker(detection.DetectionPolicy())
    for frame in range(8):
        compare(reference, x, y, [1., 1.4] if frame % 3 else [], frame)


def test_empty_frame_memory_and_reappearance_do_not_merge_tracks(reference):
    for window in (3, 48):
        x = reference.EchoTracker(reference.DetectionPolicy(track_window_pings=window))
        y = detection.EchoTracker(detection.DetectionPolicy(track_window_pings=window))
        for frame in range(100):
            depths = [] if frame % 13 in (0, 1, 2, 3, 4) else [1. + .01 * (frame % 9), 1.7]
            compare(reference, x, y, depths[::-1] if frame % 2 else depths, frame)

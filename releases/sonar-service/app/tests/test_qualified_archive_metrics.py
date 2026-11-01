"""The rate proof checks every complete device-time window, including gaps."""
import importlib.util
from pathlib import Path
import sys

import numpy as np

TOOLS = Path(__file__).resolve().parents[1]/'tools'
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('qualified_archive', TOOLS/'verify_qualified_window_archive.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_rolling_event_boundaries_equal_exhaustive_integer_windows():
    rng = np.random.default_rng(20261016)
    for _ in range(20):
        stamps = sorted(rng.integers(0, 101, size=80).tolist())
        result = module.complete_window_counts(stamps, 0, 100, width=10)
        exhaustive = [sum(a <= t < a+10 for t in stamps) for a in range(91)]
        aligned = [sum(a <= t < a+10 for t in stamps) for a in range(0, 100, 10)]
        assert result['rolling_min_max'] == [min(exhaustive), max(exhaustive)]
        assert result['aligned_min_max'] == [min(aligned), max(aligned)]


def test_empty_seconds_and_right_open_boundaries_are_not_dropped():
    result = module.complete_window_counts([0, 10, 20, 90, 100], 0, 100, width=10)
    assert result['rolling_min_max'] == [0, 1]
    assert result['aligned_min_max'] == [0, 1]
    assert result['aligned_windows'] == 10

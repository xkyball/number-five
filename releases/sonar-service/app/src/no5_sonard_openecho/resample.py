"""Byte-equivalent max-pooling without one Python reduction per output bin."""
import numpy as np
from no5_sonard.processing_m1 import max_pool_resample as reference_resample


def max_pool_resample(samples, *, start_index, bins):
    src = np.asarray(samples, dtype=np.uint16)[max(0, start_index):]
    # Preserve interpolation, empty-input and invalid-bin behavior verbatim.
    if bins <= 0 or src.size <= bins:
        return reference_resample(samples, start_index=start_index, bins=bins)
    edges = np.linspace(0, src.size, bins + 1, dtype=np.int64)
    return np.maximum.reduceat(src, edges[:-1])

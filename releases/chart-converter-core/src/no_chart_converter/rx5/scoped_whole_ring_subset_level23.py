"""Optional exact-L23 adapter; four actual canonical L24 restores are mandatory.

This emits buffers/proofs only. A receipt does not enforce a chart's scope.
"""
from .scoped_whole_ring_profile import prepare_whole_ring_profile, NAMES, OUTPUTS, ROOT

EXACT_LEVEL = 23
RESTORE_LEVEL = 24
COORDINATE_ERROR_PIXELS = 2064
HALO = 128
POLICY = 'exact-L23-root1-exterior-whole-ring-subset/1'


def prepare_scoped_whole_ring_subset_level23(features, indices, coordinates, render, additional, *,
        source_tile, owner_uid, expected_input_sha256, source_header,
        expected_header_sha256, source_file_bytes):
    return prepare_whole_ring_profile(features, indices, coordinates, render, additional,
        source_tile=source_tile, owner_uid=owner_uid,
        expected_input_sha256=expected_input_sha256, source_header=source_header,
        expected_header_sha256=expected_header_sha256, source_file_bytes=source_file_bytes,
        profile='exact-L23')

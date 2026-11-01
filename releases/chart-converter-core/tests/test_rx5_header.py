"""Synthetic wire contracts only; no original libraries or chart fixtures."""
import struct

import pytest

from no_chart_converter.rx5.header import (
    FILE_SEGMENT_SIZE, PLAIN_VECTOR_QWORDS, new_plain_vector_header,
    repair_own_plain_vector_header, validate_plain_vector_header,
)


def test_complete_unsigned_layout_and_original_ctor_read_contract():
    raw = new_plain_vector_header(bytes(range(16)), native_scamin_multiplier=987654321)
    assert len(raw) == 240 and raw[56:72] == bytes(range(16))
    assert struct.unpack_from('<I', raw, 216)[0] == 987654321
    assert {at: struct.unpack_from('<Q', raw, at)[0] for at in PLAIN_VECTOR_QWORDS} == {
        8: 240, 16: 16, 24: 32, 32: 32, 40: 1 << 30, 48: 1,
        72: 8, 80: 5, 88: 4, 104: 256,
    }
    # Retained original constructor contract: read HeaderSize bytes into a
    # 256-byte zero buffer, then copy256. This is an own model, not native proof.
    source = raw + b'OWN_FIRST_BLOCK!!'
    stored = source[:struct.unpack_from('<Q', source, 8)[0]].ljust(256, b'\0')
    assert stored[:240] == raw and stored[240:] == bytes(16)
    divisor = struct.unpack_from('<Q', stored, 40)[0]
    assert divmod(239, divisor) == (0, 239)
    assert divmod(FILE_SEGMENT_SIZE + 17, divisor) == (1, 17)
    assert struct.unpack_from('<Q', stored, 88)[0] & 4  # isVectorChart contract
    validate_plain_vector_header(raw, file_bytes=4096)


@pytest.mark.parametrize('offset', PLAIN_VECTOR_QWORDS)
def test_omitted_format_field_is_rejected(offset):
    raw = bytearray(new_plain_vector_header(bytes(16)))
    struct.pack_into('<Q', raw, offset, 0)
    with pytest.raises(ValueError, match=f'UNSIGNED64_AT_{offset}'):
        validate_plain_vector_header(bytes(raw))


def test_zero_header_size_reproduces_own_model_of_original_failure():
    old = bytearray(240)
    old[:8] = b'\x01RayRX5\0'
    stored = bytes(old)[:struct.unpack_from('<Q', old, 8)[0]].ljust(256, b'\0')
    assert stored == bytes(256)
    with pytest.raises(ZeroDivisionError):
        divmod(240, struct.unpack_from('<Q', stored, 40)[0])
    with pytest.raises(ValueError, match='UNSIGNED64_AT_8'):
        validate_plain_vector_header(bytes(old))


def test_repair_is_strictly_limited_to_explicit_words():
    old = bytearray(240)
    old[:8] = b'\x01RayRX5\0'
    old[56:72] = bytes(range(16))
    for at, value in ((136, 240), (144, 900), (160, 1600), (168, 2024),
                      (184, 3000), (200, 4000), (216, 123456)):
        struct.pack_into('<Q', old, at, value)
    old[228:240] = b'OWN RESERVED'
    fixed = repair_own_plain_vector_header(bytes(old), file_bytes=5000)
    allowed = {n for at in PLAIN_VECTOR_QWORDS for n in range(at, at + 8)}
    assert all(a == b or i in allowed for i, (a, b) in enumerate(zip(old, fixed)))
    assert fixed[56:72] == old[56:72]
    assert fixed[112:] == old[112:]
    assert repair_own_plain_vector_header(fixed, file_bytes=5000) == fixed


@pytest.mark.parametrize('offset', (96, 152, 176, 192))
def test_never_reinterprets_drm_or_vendor_header(offset):
    raw = bytearray(new_plain_vector_header(bytes(16)))
    struct.pack_into('<Q', raw, offset, 1)
    with pytest.raises(ValueError, match='AUTH_DRM_VENDOR'):
        repair_own_plain_vector_header(bytes(raw), file_bytes=4096)


@pytest.mark.parametrize('size', (0, 239, (1 << 30) + 1, True))
def test_single_file_writer_does_not_invent_segmented_output(size):
    with pytest.raises(ValueError, match='SINGLE_SEGMENT_EXTENT'):
        repair_own_plain_vector_header(new_plain_vector_header(bytes(16)), file_bytes=size)


def test_conflicting_nonzero_header_cannot_be_overwritten():
    raw = bytearray(new_plain_vector_header(bytes(16)))
    struct.pack_into('<Q', raw, 88, 8)
    with pytest.raises(ValueError, match='REPAIR_CONFLICT_AT_88'):
        repair_own_plain_vector_header(bytes(raw), file_bytes=4096)


@pytest.mark.parametrize('identity,multiplier', ((b'', 0), (bytes(15), 0),
                                               (bytes(16), -1), (bytes(16), True)))
def test_new_header_requires_exact_identity_and_multiplier(identity, multiplier):
    with pytest.raises(ValueError):
        new_plain_vector_header(identity, native_scamin_multiplier=multiplier)

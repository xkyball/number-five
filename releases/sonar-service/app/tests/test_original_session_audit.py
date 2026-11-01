from pathlib import Path
import sys

import numpy as np
from no5_sonard import pico_protocol as protocol
from no5_sonard.storage.raw_zarr import RawPingRecord, RawZarrV2Writer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from audit_original_session import audit_session


def fixture_session(base):
    raw = base / 'raw' / 'fixture.zarr'
    raw.parent.mkdir(parents=True)
    samples = np.arange(32, dtype='<u2')
    writer = RawZarrV2Writer(raw, sample_count=32, sample_period_ns=2000, chunk_pings=2)
    writer.append(RawPingRecord(1, 123456789, 100, 2000, 0, 1, 0, 31, samples))
    writer.close()
    payload = protocol.PING_PREFIX.pack(1, 100, 2000, 32, 0, 0, 31, 1) + samples.tobytes()
    wire = base / 'wire' / 'fixture' / 'rx.cobs'
    wire.parent.mkdir(parents=True)
    encoded = protocol.encode_packet(protocol.Packet(protocol.MessageType.PING_FRAME, 1, 0, payload))
    wire.write_bytes(encoded)
    return wire, payload


def test_original_payload_and_current_transmit_message_types_are_verified(tmp_path):
    wire, _ = fixture_session(tmp_path)
    with wire.open('ab') as output:
        for seq, kind in enumerate((protocol.MessageType.TX_ACK, protocol.MessageType.TX_EVENT), 2):
            output.write(protocol.encode_packet(protocol.Packet(kind, seq, 0, b'')))
    result = audit_session(tmp_path, protocol)
    assert result['passed'] and result['original_raw_rows'] == 1
    assert result['matched_original_payloads'] == 1
    assert result['decoded_frames'] == 3 and result['invalid_wire_frame_count'] == 0
    assert result['message_type_counts'][0x44] == result['message_type_counts'][0x45] == 1


def test_crc_valid_changed_ADC_is_rejected(tmp_path):
    wire, payload = fixture_session(tmp_path)
    changed = payload[:-2] + b'\x63\x00'
    wire.write_bytes(protocol.encode_packet(protocol.Packet(protocol.MessageType.PING_FRAME, 1, 0, changed)))
    result = audit_session(tmp_path, protocol)
    assert result['invalid_wire_frame_count'] == 0
    assert not result['passed'] and result['raw_payload_error_count'] == 1
    assert not result['original_sample_values_unchanged']


def test_error_examples_remain_bounded_without_losing_total_count(tmp_path):
    wire, _ = fixture_session(tmp_path)
    with wire.open('ab') as output:
        output.write(b'\x01\0' * 40)
    result = audit_session(tmp_path, protocol)
    assert not result['passed'] and result['invalid_wire_frame_count'] == 40
    assert len(result['invalid_wire_frames']) == 32


def test_partial_original_tail_is_preserved_and_fails(tmp_path):
    wire, _ = fixture_session(tmp_path)
    with wire.open('ab') as output:
        output.write(b'abc')
    before = wire.read_bytes()
    result = audit_session(tmp_path, protocol)
    assert not result['passed'] and not result['all_original_wire_packets_verified']
    assert result['wire_files'][0]['incomplete_tail_bytes'] == 3
    assert wire.read_bytes() == before


def test_unmatched_wire_ping_before_first_RAW_is_not_silently_ignored(tmp_path):
    wire, payload = fixture_session(tmp_path)
    before = protocol.PING_PREFIX.pack(0, 50, 2000, 32, 0, 0, 31, 1) + payload[protocol.PING_PREFIX.size:]
    with wire.open('ab') as output:
        output.write(protocol.encode_packet(protocol.Packet(protocol.MessageType.PING_FRAME, 0, 0, before)))
    result = audit_session(tmp_path, protocol)
    assert not result['passed'] and result['wire_pings_before_first_raw'] == 1

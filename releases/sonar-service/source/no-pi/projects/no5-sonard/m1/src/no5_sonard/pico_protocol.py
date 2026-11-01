from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
import struct
import zlib
from typing import Final

import numpy as np

MAGIC: Final[bytes] = b"N5"
PROTOCOL_VERSION: Final[int] = 1
MAX_DECODED_PACKET: Final[int] = 1_048_576
HEADER = struct.Struct("<2sBBIII")
CRC = struct.Struct("<I")


class MessageType(IntEnum):
    HELLO = 0x01
    HELLO_ACK = 0x02
    CONFIG_SET = 0x10
    CONFIG_ACK = 0x11
    START = 0x12
    STOP = 0x13
    PING_FRAME = 0x20
    STATUS = 0x21
    TIME_SYNC_REQ = 0x30
    TIME_SYNC_RESP = 0x31
    ERROR = 0x7F


HELLO_STRUCT = struct.Struct("<BBBBIIIHH16s")
CONFIG_SET_STRUCT = struct.Struct("<IIIHBBHHII")
CONFIG_ACK_STRUCT = struct.Struct("<IIII")
PING_PREFIX = struct.Struct("<QQIIiHHI")
STATUS_STRUCT = struct.Struct("<QQIIIIIi")
TIME_SYNC_REQ_STRUCT = struct.Struct("<I")
TIME_SYNC_RESP_STRUCT = struct.Struct("<IQQ")


@dataclass(slots=True, frozen=True)
class Packet:
    type: MessageType
    seq: int
    flags: int
    payload: bytes


@dataclass(slots=True, frozen=True)
class Hello:
    fw_major: int
    fw_minor: int
    fw_patch: int
    capabilities: int
    max_sample_rate_hz: int
    max_samples: int
    adc_bits: int
    adc_fullscale_mv: int
    device_serial: str


@dataclass(slots=True, frozen=True)
class ConfigSet:
    revision: int
    sample_period_ns: int
    sample_count: int
    pretrigger_samples: int
    trigger_mode: int
    signal_mode: int
    trigger_threshold: int
    trigger_hysteresis: int
    capture_timeout_us: int


@dataclass(slots=True, frozen=True)
class ConfigAck:
    revision: int
    status: int
    actual_sample_period_ns: int
    actual_sample_count: int


@dataclass(slots=True, frozen=True)
class PingFrame:
    ping_id: int
    trigger_device_time_us: int
    sample_period_ns: int
    tx_zero_sample_index: int
    adc_min: int
    adc_max: int
    ping_flags: int
    samples: np.ndarray


@dataclass(slots=True, frozen=True)
class PicoStatus:
    uptime_us: int
    pings_total: int
    dropped_frames: int
    trigger_misses: int
    adc_overruns: int
    usb_errors: int
    last_error: int
    pico_temperature_mc: int


@dataclass(slots=True, frozen=True)
class TimeSyncResponse:
    token: int
    pico_rx_us: int
    pico_tx_us: int


def cobs_encode(data: bytes) -> bytes:
    if not data:
        return b"\x01"
    out = bytearray([0])
    code_index = 0
    code = 1
    for byte in data:
        if byte == 0:
            out[code_index] = code
            code_index = len(out)
            out.append(0)
            code = 1
        else:
            out.append(byte)
            code += 1
            if code == 0xFF:
                out[code_index] = code
                code_index = len(out)
                out.append(0)
                code = 1
    out[code_index] = code
    return bytes(out)


def cobs_decode(data: bytes) -> bytes:
    if not data:
        raise ValueError("empty COBS frame")
    out = bytearray()
    i = 0
    n = len(data)
    while i < n:
        code = data[i]
        if code == 0:
            raise ValueError("zero byte inside COBS payload")
        i += 1
        end = i + code - 1
        if end > n:
            raise ValueError("COBS code overruns frame")
        out.extend(data[i:end])
        i = end
        if code != 0xFF and i < n:
            out.append(0)
    return bytes(out)


def encode_packet(packet: Packet) -> bytes:
    payload = bytes(packet.payload)
    header = HEADER.pack(MAGIC, PROTOCOL_VERSION, int(packet.type), packet.seq & 0xFFFFFFFF, len(payload), packet.flags)
    crc = zlib.crc32(header + payload) & 0xFFFFFFFF
    return cobs_encode(header + payload + CRC.pack(crc)) + b"\x00"


def decode_packet(encoded_without_delimiter: bytes) -> Packet:
    raw = cobs_decode(encoded_without_delimiter)
    if len(raw) < HEADER.size + CRC.size:
        raise ValueError("packet too short")
    if len(raw) > MAX_DECODED_PACKET:
        raise ValueError("packet exceeds safety limit")
    magic, version, msg_type, seq, payload_len, flags = HEADER.unpack_from(raw)
    if magic != MAGIC:
        raise ValueError("invalid packet magic")
    if version != PROTOCOL_VERSION:
        raise ValueError(f"unsupported protocol version {version}")
    expected = HEADER.size + payload_len + CRC.size
    if len(raw) != expected:
        raise ValueError(f"packet length mismatch: got {len(raw)}, expected {expected}")
    expected_crc = CRC.unpack_from(raw, len(raw) - CRC.size)[0]
    actual_crc = zlib.crc32(raw[:-CRC.size]) & 0xFFFFFFFF
    if actual_crc != expected_crc:
        raise ValueError("CRC32 mismatch")
    try:
        kind = MessageType(msg_type)
    except ValueError as exc:
        raise ValueError(f"unknown message type 0x{msg_type:02x}") from exc
    return Packet(kind, seq, flags, raw[HEADER.size:-CRC.size])


def pack_hello_ack() -> bytes:
    return b""


def parse_hello(payload: bytes) -> Hello:
    if len(payload) != HELLO_STRUCT.size:
        raise ValueError("invalid HELLO payload size")
    values = HELLO_STRUCT.unpack(payload)
    serial = values[9].split(b"\x00", 1)[0].decode("ascii", errors="replace")
    return Hello(values[0], values[1], values[2], values[4], values[5], values[6], values[7], values[8], serial)


def pack_hello(value: Hello) -> bytes:
    serial = value.device_serial.encode("ascii", errors="replace")[:16].ljust(16, b"\x00")
    return HELLO_STRUCT.pack(value.fw_major, value.fw_minor, value.fw_patch, 0, value.capabilities,
                             value.max_sample_rate_hz, value.max_samples, value.adc_bits,
                             value.adc_fullscale_mv, serial)


def pack_config_set(value: ConfigSet) -> bytes:
    return CONFIG_SET_STRUCT.pack(value.revision, value.sample_period_ns, value.sample_count,
                                  value.pretrigger_samples, value.trigger_mode, value.signal_mode,
                                  value.trigger_threshold, value.trigger_hysteresis,
                                  value.capture_timeout_us, 0)


def parse_config_set(payload: bytes) -> ConfigSet:
    if len(payload) != CONFIG_SET_STRUCT.size:
        raise ValueError("invalid CONFIG_SET payload size")
    v = CONFIG_SET_STRUCT.unpack(payload)
    return ConfigSet(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])


def pack_config_ack(value: ConfigAck) -> bytes:
    return CONFIG_ACK_STRUCT.pack(value.revision, value.status, value.actual_sample_period_ns, value.actual_sample_count)


def parse_config_ack(payload: bytes) -> ConfigAck:
    if len(payload) != CONFIG_ACK_STRUCT.size:
        raise ValueError("invalid CONFIG_ACK payload size")
    return ConfigAck(*CONFIG_ACK_STRUCT.unpack(payload))


def pack_ping_frame(value: PingFrame) -> bytes:
    samples = np.asarray(value.samples, dtype="<u2")
    prefix = PING_PREFIX.pack(value.ping_id, value.trigger_device_time_us, value.sample_period_ns,
                              samples.size, value.tx_zero_sample_index, value.adc_min, value.adc_max,
                              value.ping_flags)
    return prefix + samples.tobytes(order="C")


def parse_ping_frame(payload: bytes) -> PingFrame:
    if len(payload) < PING_PREFIX.size:
        raise ValueError("PING_FRAME shorter than prefix")
    v = PING_PREFIX.unpack_from(payload)
    sample_count = v[3]
    expected = PING_PREFIX.size + sample_count * 2
    if len(payload) != expected:
        raise ValueError(f"PING_FRAME length mismatch: got {len(payload)}, expected {expected}")
    samples = np.frombuffer(payload, dtype="<u2", count=sample_count, offset=PING_PREFIX.size).copy()
    return PingFrame(v[0], v[1], v[2], v[4], v[5], v[6], v[7], samples)


def pack_status(value: PicoStatus) -> bytes:
    return STATUS_STRUCT.pack(value.uptime_us, value.pings_total, value.dropped_frames,
                              value.trigger_misses, value.adc_overruns, value.usb_errors,
                              value.last_error, value.pico_temperature_mc)


def parse_status(payload: bytes) -> PicoStatus:
    if len(payload) != STATUS_STRUCT.size:
        raise ValueError("invalid STATUS payload size")
    return PicoStatus(*STATUS_STRUCT.unpack(payload))


def pack_time_sync_req(token: int) -> bytes:
    return TIME_SYNC_REQ_STRUCT.pack(token & 0xFFFFFFFF)


def parse_time_sync_req(payload: bytes) -> int:
    if len(payload) != TIME_SYNC_REQ_STRUCT.size:
        raise ValueError("invalid TIME_SYNC_REQ payload size")
    return TIME_SYNC_REQ_STRUCT.unpack(payload)[0]


def pack_time_sync_resp(value: TimeSyncResponse) -> bytes:
    return TIME_SYNC_RESP_STRUCT.pack(value.token, value.pico_rx_us, value.pico_tx_us)


def parse_time_sync_resp(payload: bytes) -> TimeSyncResponse:
    if len(payload) != TIME_SYNC_RESP_STRUCT.size:
        raise ValueError("invalid TIME_SYNC_RESP payload size")
    return TimeSyncResponse(*TIME_SYNC_RESP_STRUCT.unpack(payload))

"""Bounded publication evidence, independent of optional analysis.

The host records these facts before publishing the corresponding binary frame.
Neither its selected source nor its latest detection is an origin substitute.
"""
from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
import math
import struct

from .protocol import ProtocolError
from .sonar_contract import integer, host_session_id, DISPLAY_WINDOW_VERSIONS

CAPACITY = 512
PAGE_LIMIT = 64
MAX_BODY_BYTES = 65536
SCHEMA = 'no5.sonard-published-pings/1'
EVIDENCE = {'physical_own_marker_raw_match': 'own',
            'qualified_native_marker_raw_match': 'native', 'unconfirmed': None}


def host_context(status):
    session = status.get('session') if isinstance(status, dict) else None
    segment = session.get('segment_id') if isinstance(session, dict) else None
    identifier = host_session_id(status)
    if (identifier is None or not isinstance(segment, str) or not 1 <= len(segment) <= 128 or
            not segment.isprintable()):
        return None
    return identifier, segment


@dataclass(frozen=True, slots=True)
class PublishedDisplayWindow:
    capture_sample_count: int
    display_sample_count: int
    display_start_sample: int
    display_end_sample_exclusive: int
    capture_range_m: float | None
    display_range_m: float | None
    tx_reference_verified: bool
    reason: str


def _display_window(value, row, *, strict_reason=True):
    """0.4.14 declares the actual M1 window, never a replacement raw geometry."""
    if (not isinstance(value, dict) or value.get('schema') != 'no5.display-window/1' or
            type(value.get('ping_id')) is not int or value['ping_id'] != row['ping_id'] or
            value.get('raw_samples_modified') is not False or
            value.get('detector_uses_full_capture') is not True or
            type(value.get('tx_reference_verified')) is not bool or
            value['tx_reference_verified'] != row['valid_tx_reference']):
        raise ProtocolError('invalid published display window')
    for field in ('capture_sample_count', 'display_sample_count', 'display_start_sample',
                  'display_end_sample_exclusive'):
        if not integer(value.get(field), maximum=1_000_000):
            raise ProtocolError('invalid published display window integer')
    capture, count, start, end = (value[name] for name in ('capture_sample_count',
        'display_sample_count', 'display_start_sample', 'display_end_sample_exclusive'))
    if capture != row['sample_count'] or not 0 <= start < end <= capture or count != end - start:
        raise ProtocolError('published display window outside original capture')
    reason = value.get('reason')
    maximum, displayed = value.get('capture_range_m'), value.get('display_range_m')
    if value['tx_reference_verified']:
        if (not isinstance(reason, str) or
                (strict_reason and reason not in {'full_capture', 'qualified_depth_window', 'capture_matches_depth_window'}) or
                any(type(number) not in (int, float) or not math.isfinite(number) or
                    not 0 < number <= 1_000_000 for number in (maximum, displayed))):
            raise ProtocolError('invalid published metric display window')
        # JSON carries the exact header binary32 range. The full-capture range
        # is a binary64 calculation and may round slightly UP in binary32.
        rounded_capture = struct.unpack('<f', struct.pack('<f', maximum))[0]
        if displayed != struct.unpack('<f', struct.pack('<f', displayed))[0] or displayed > rounded_capture:
            raise ProtocolError('published display range is not the bounded header binary32 value')
        expected = struct.unpack('<f', struct.pack('<f', maximum * count / (capture - start)))[0]
        if displayed != expected:
            raise ProtocolError('published range disagrees with declared display sample window')
        if strict_reason and (reason == 'qualified_depth_window') != (end < capture):
            raise ProtocolError('published display reason disagrees with sample window')
        if end < capture and row['actual_source'] != 'own':
            raise ProtocolError('only verified own depth can narrow the display window')
    elif ((strict_reason and reason != 'unverified_time_reference') or maximum is not None or displayed is not None or
          start != 0 or count != capture or end != capture):
        raise ProtocolError('unverified display must retain full samples without meters')
    return PublishedDisplayWindow(**{field: value[field] for field in PublishedDisplayWindow.__dataclass_fields__})


@dataclass(frozen=True, slots=True)
class PublishedPing:
    ping_id: int
    time_utc_ns: int
    session_id: str
    segment_id: str
    actual_source: str | None
    source_evidence: str
    valid_tx_reference: bool
    selection_generation: int
    display_generation: int
    sample_count: int
    sample_period_ns: int
    noise_revision: int
    noise_active: bool
    display_window: PublishedDisplayWindow | None = None


@dataclass(frozen=True, slots=True)
class PublishedPage:
    context: tuple[str, str] | None
    rows: tuple[PublishedPing, ...]
    next_cursor: int
    has_more: bool
    truncated: bool



def parse_record(row, *, context, host_version=None, strict_window_reason=True) -> PublishedPing:
    """One immutable evidence interpretation shared by HTTP and atomic transport."""
    if not isinstance(row, dict) or context is None:
        raise ProtocolError('invalid publication record')
    if (row.get('session_id'), row.get('segment_id')) != context:
        raise ProtocolError('publication record context mismatch')
    for field in ('ping_id', 'time_utc_ns', 'selection_generation', 'display_generation',
                  'sample_count', 'sample_period_ns', 'noise_revision'):
        if not integer(row.get(field)):
            raise ProtocolError('invalid publication record integer')
    if (row['time_utc_ns'] == 0 or not 1 <= row['sample_count'] <= 1_000_000 or
            not 1 <= row['sample_period_ns'] <= 1_000_000 or type(row.get('valid_tx_reference')) is not bool or
            type(row.get('noise_active')) is not bool):
        raise ProtocolError('invalid publication record bounds')
    evidence = row.get('source_evidence')
    if (not isinstance(evidence, str) or evidence not in EVIDENCE or
            row.get('actual_source') != EVIDENCE[evidence] or
            (EVIDENCE[evidence] == 'native' and row['valid_tx_reference'] is not True)):
        raise ProtocolError('unconfirmed publication origin evidence')
    window = row.get('display_window')
    if window is not None or host_version in DISPLAY_WINDOW_VERSIONS:
        window = _display_window(window, row, strict_reason=strict_window_reason)
    return PublishedPing(**{field: row[field] for field in PublishedPing.__dataclass_fields__
                            if field != 'display_window'}, display_window=window)


def matches_frame(row, frame, context):
    """An exact row/header pair; source selection cannot supply missing facts."""
    if (row is None or frame.host_context_hint != context or
            row.ping_id != frame.ping_id or row.time_utc_ns != frame.time_utc_ns or
            row.valid_tx_reference != bool(frame.flags & 1)):
        return False
    window = row.display_window
    if window is not None:
        if window.tx_reference_verified:
            if (not math.isfinite(frame.displayed_max_depth_m) or
                    struct.pack('<f', window.display_range_m) !=
                    struct.pack('<f', frame.displayed_max_depth_m)):
                return False
        elif not math.isnan(frame.displayed_max_depth_m):
            return False
    return True


def parse_page(payload, *, after_ping_id: int, host_version: str | None = None) -> PublishedPage:
    if not integer(after_ping_id) or not isinstance(payload, dict) or payload.get('schema') != SCHEMA:
        raise ProtocolError('invalid publication page')
    if type(payload.get('capacity')) is not int or payload['capacity'] != CAPACITY:
        raise ProtocolError('unsupported publication ring capacity')
    rows = payload.get('pings')
    if not isinstance(rows, list) or len(rows) > PAGE_LIMIT:
        raise ProtocolError('publication page exceeds bounds')
    context = host_context({'session': {'id': payload.get('session_id'),
                                        'segment_id': payload.get('segment_id')}})
    oldest, latest = payload.get('oldest_ping_id'), payload.get('latest_ping_id')
    next_cursor = payload.get('next_after_ping_id')
    if (not integer(next_cursor) or type(payload.get('has_more')) is not bool or
            type(payload.get('truncated')) is not bool or
            (oldest is not None and not integer(oldest)) or (latest is not None and not integer(latest)) or
            (oldest is None) != (latest is None) or
            (oldest is not None and (context is None or oldest > latest))):
        raise ProtocolError('invalid publication page bounds')
    parsed = []
    previous = after_ping_id
    for row in rows:
        record = parse_record(row, context=context, host_version=host_version)
        if record.ping_id <= previous or oldest is None or not oldest <= record.ping_id <= latest:
            raise ProtocolError('invalid publication record bounds')
        parsed.append(record)
        previous = row['ping_id']
    if (next_cursor != previous or (payload['has_more'] and not parsed) or
            (oldest is None and (parsed or payload['truncated'] or payload['has_more']))):
        raise ProtocolError('invalid publication cursor')
    return PublishedPage(context, tuple(parsed), next_cursor, payload['has_more'], payload['truncated'])


class PublishedPingCache:
    """At most 512 immutable records; each fetch consumes at most one page."""
    def __init__(self):
        self.context = None
        self.cursor = 0
        self._rows = OrderedDict()
        self.truncated_pages = 0

    def set_context(self, context):
        if context != self.context:
            self.context = context
            self.cursor = 0
            self._rows.clear()

    def accept(self, page: PublishedPage):
        if page.context != self.context:
            raise ProtocolError('publication host context changed')
        # Pages are validated before mutation. Conflicting repeated records
        # cannot silently change the evidence for an already published ping.
        for row in page.rows:
            previous = self._rows.get(row.ping_id)
            if previous is not None and previous != row:
                raise ProtocolError('publication record changed')
        for row in page.rows:
            self._rows[row.ping_id] = row
            if len(self._rows) > CAPACITY:
                self._rows.popitem(last=False)
        self.cursor = page.next_cursor
        if page.truncated:
            self.truncated_pages += 1

    def match(self, frame):
        row = self._rows.get(frame.ping_id)
        return row if matches_frame(row, frame, self.context) else None

    def __len__(self):
        return len(self._rows)

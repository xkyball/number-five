"""On-demand bounded diagnostics projected from existing Sonard read APIs."""
from __future__ import annotations
import asyncio
import copy
import json
import math
import time
from .protocol import ProtocolError, strict_json_object
from .sonar_contract import (display_selection, host_session_id, package_version, SOURCE_VERSIONS,
                            DEPTH_RATE_VERSIONS, PUBLISHED_VERSIONS, DISPLAY_WINDOW_VERSIONS, IMPULSE_VERSIONS)

MAX_BODY = 32768
MAX_FIELDS = 128
MAX_CHARACTERS = 24000
STATUS_TIME_BASIS = 'GATEWAY_STATUS_REQUEST_STARTED_AT_UTC'


async def read_json(response, limit=256 * 1024):
    raw = bytearray()
    async for chunk in response.content.iter_chunked(8192):
        raw.extend(chunk)
        if len(raw) > limit: raise ProtocolError('Sonard response exceeds bounds')
    return strict_json_object(bytes(raw))


def _at(value, path):
    for key in path.split('.'):
        if not isinstance(value, dict) or key not in value: return None
        value = value[key]
    return value


def _text(value):
    if value is None: return None
    if type(value) is float and not math.isfinite(value): return None
    if isinstance(value, str):
        # Preserve IDs and numeric precision; no HTML or formatting is executed.
        text = ''.join(char if ord(char) >= 32 else ' ' for char in value)
        return text if len(text) <= 2048 else text[:2036] + ' [TRUNCATED]'
    if type(value) in (bool, int, float): return json.dumps(value, allow_nan=False)
    if isinstance(value, (list, dict)):
        # Only bounded simple details reach the UI; never serialize arbitrary
        # status subtrees, archive paths or a 600-second TX event history.
        if isinstance(value, list) and len(value) <= 16 and all(type(item) in (str, bool, int, float) for item in value):
            try:
                text = json.dumps(value, separators=(',', ':'), allow_nan=False)
                return text if len(text) <= 2048 else text[:2036] + ' [TRUNCATED]'
            except ValueError: return None
    return None


STATUS_PATHS = (
    'state', 'last_error', 'host_package.version', 'host_package.name', 'session.id', 'session.segment_id',
    'session.archived_pings_total', 'rates.ping_hz',
    'display_selection.source', 'display_selection.generation', 'display_selection.published_hz',
    'display_selection.counts.received', 'display_selection.counts.published', 'display_selection.counts.hidden',
    'display_selection.counts.own', 'display_selection.counts.native',
    'acquisition.tx_marker_requested', 'acquisition.tx_reference_basis', 'acquisition.empirical_trigger_fallback',
    'acquisition.last_tx_reference_valid', 'acquisition.clipped_pings_total',
    'acquisition.time_reference.kind', 'acquisition.time_reference.marker_gpio',
    'acquisition.time_reference.last_ping_verified', 'acquisition.time_reference.depth_calibrated',
    'acquisition.amplitude.significant_bits', 'acquisition.amplitude.fullscale_code',
    'acquisition.amplitude.normalization_source',
    'accepted_config.requested.sample_count', 'accepted_config.requested.sample_period_ns',
    'accepted_config.ack.actual_sample_count', 'accepted_config.ack.actual_sample_period_ns',
    'pico.packet_seq_gaps', 'pico.ping_id_gaps', 'pico.queue_drops', 'pico.frame_errors', 'pico.crc_errors',
    'clock.ready', 'clock.state',
    'transmit.mode', 'transmit.reason', 'transmit.output_starts', 'transmit.physical_markers', 'transmit.linked_pings',
    'transmit.marker_reference.quarantined', 'transmit.marker_reference.quarantines',
    'transmit.marker_reference.recoveries', 'transmit.marker_reference.skipped_slots',
    'transmit.marker_reference.last_observed_device_us',
    'transmit.schedule.kind', 'transmit.schedule.nominal_own_mean_hz', 'transmit.schedule.own_slots_us',
    'transmit.run.state', 'transmit.run.elapsed_s', 'transmit.run.own_starts', 'transmit.run.physical_markers',
    'transmit.run.raw_ping_links', 'transmit.run.native_pings', 'transmit.run.usable_pings',
    'transmit.run.usable_hz', 'transmit.run.own_mean_hz', 'transmit.run.all_associated',
)
# Reviewed 0.4.11/0.4.12 telemetry only. In particular planned_hz is not a measured
# rate, and the five-second own-only window is not run.usable_hz or app FPS.
DEPTH_RATE_PATHS = tuple('transmit.rate_control.' + name for name in (
    'schema', 'rate_policy', 'planned_hz', 'limiting_reason', 'reason', 'depth_m', 'depth_age_s',
    'sample_count', 'receive_range_m', 'sample_period_ns', 'minimum_spacing_us', 'qualified_min_spacing_us',
    'range_reserve_limited', 'experimental_trial', 'trial_spacing_us', 'pipeline_reserve_us',
    'periodic_full_search', 'geometry_errors', 'applied_window.revision', 'applied_window.sample_count',
    'applied_window.next_min_spacing_us', 'applied_window.device_us', 'applied_window.native_cycle',
)) + tuple('transmit.ping_rates.' + name for name in (
    'window_s', 'start_device_us', 'end_device_us', 'observed_s', 'complete', 'time_basis',
)) + tuple('transmit.ping_rates.rates.' + kind + '.' + name
          for kind in ('own', 'physical', 'raw', 'usable')
          for name in ('count', 'hz', 'rolling_1s_min', 'max_gap_s'))
REFERENCE_PATHS = tuple('transmit.original_reference.' + name for name in (
    'schema', 'arrival_age_s', 'observation_fresh', 'latched_until', 'recovery_rule', 'tx_mode',
)) + tuple('transmit.original_reference.' + kind + '.' + name
          for kind in ('latest', 'latched_fault')
          for name in ('firmware_event', 'firmware_detail', 'observation_reason', 'observed_device_us',
                       'observed_utc_ns', 'utc_basis', 'observation_kind', 'native_last_device_us',
                       'native_period_us', 'native_stable', 'slot', 'meaning', 'received_host_utc_ns'))
NOISE_PATHS = ('display_selection.selection_generation',) + tuple('noise_reduction.' + name for name in (
    'schema', 'display_generation', 'policy.mode', 'policy.revision', 'policy.algorithm', 'policy.scope',
    'policy.reference_cell_us', 'policy.threshold_sigma', 'policy.raw_samples_modified',
    'policy.detection_input', 'policy.transmit_input', 'latest.schema', 'latest.ping_id',
    'latest.acquisition_source', 'latest.sample_count', 'latest.sample_period_ns', 'latest.active',
    'latest.reason', 'latest.baseline_adc', 'latest.noise_sigma_adc', 'latest.reference_samples',
    'latest.suppressed_fraction', 'latest.protected_fraction', 'latest.output', 'latest.calibrated_acoustic_power',
))
DISPLAY_WINDOW_PATHS = tuple('display_window.' + name for name in (
    'schema', 'ping_id', 'capture_sample_count', 'display_sample_count', 'display_start_sample',
    'display_end_sample_exclusive', 'capture_range_m', 'display_range_m', 'tx_reference_verified',
    'reason', 'raw_samples_modified', 'detector_uses_full_capture',
)) + tuple('noise_reduction.' + name for name in (
    'policy.stripe_mode', 'policy.display_window', 'policy.temporal_target_subtraction',
    'latest.stripe_mode', 'latest.display_window', 'latest.temporal_target_subtraction',
))
TRANSPORT_LIVENESS_PATHS = tuple('pico.transport_liveness.' + name for name in (
    'state', 'last_byte_age_s', 'last_packet_age_s', 'read_poll_interval_s',
    'packet_silence_limit_s', 'poll_read_bytes', 'raw_samples_modified',
)) + ('rates.last_raw_arrival_age_s', 'rates.last_observed_ping_hz')
# Snapshot/wire parity is read-verified for these exact versions. Missing
# fields remain absent; source/status telemetry is not a fresh acoustic ping.
TRANSPORT_LIVENESS_VERSIONS = frozenset({'0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
IMPULSE_PATHS = tuple('noise_reduction.' + name for name in (
    'policy.impulse_mode', 'latest.impulse_mode', 'latest.impulse_reduction.active',
    'latest.impulse_reduction.reason', 'latest.impulse_reduction.algorithm',
    'latest.impulse_reduction.events', 'latest.impulse_reduction.modified_samples',
    'latest.impulse_reduction.maximum_subtraction_adc',
    'latest.impulse_reduction.detected_echo_samples_unchanged',
    'latest.impulse_reduction.temporal_replacement', 'latest.impulse_reduction.background_reestimated',
    'latest.impulse_reduction.corrected_baseline_adc', 'latest.impulse_reduction.corrected_noise_sigma_adc',
))
TRACK_FIELDS = ('track_id', 'depth_m', 'velocity_mps', 'hits', 'age_pings', 'misses', 'confidence', 'state',
                'kind', 'classification', 'classification_source', 'rule_score', 'evidence', 'detector')
BLOCK_FIELDS = ('start_sample', 'end_sample', 'peak_sample', 'depth_m', 'width_m', 'energy', 'peak_amplitude',
                'local_noise', 'snr_db', 'prominence', 'detector', 'qualification_method', 'template_ping_id',
                'template_correlation', 'template_score', 'template_fit_window', 'template_fit_window_samples',
                'template_fit_offset_samples', 'template_observed_peak_sample', 'template_fit_preprocessing')
UNITS = {'depth_m': 'm', 'width_m': 'm', 'velocity_mps': 'm/s', 'sample_period_ns': 'ns',
         'snr_db': 'dB', 'peak_amplitude': 'ADC_CODE', 'local_noise': 'ADC_CODE'}


CORE_STATUS_PATHS = (
    'state', 'host_package.version', 'host_package.name', 'session.id',
    'display_selection.source', 'display_selection.generation',
    'transmit.ping_rates.window_s', 'transmit.ping_rates.observed_s', 'transmit.ping_rates.complete',
    'transmit.ping_rates.time_basis', 'transmit.ping_rates.start_device_us', 'transmit.ping_rates.end_device_us',
    'transmit.ping_rates.rates.usable.count', 'transmit.ping_rates.rates.usable.hz',
    'transmit.rate_control.schema', 'transmit.rate_control.planned_hz', 'transmit.rate_control.limiting_reason',
    'acquisition.amplitude.significant_bits', 'acquisition.amplitude.fullscale_code',
    'acquisition.amplitude.normalization_source', 'accepted_config.requested.sample_count',
    'accepted_config.requested.sample_period_ns', 'accepted_config.ack.actual_sample_count',
    'accepted_config.ack.actual_sample_period_ns',
)


def status_context_matches(state, status):
    try: selected = display_selection(status.get('display_selection'))
    except ProtocolError: return False
    current = state.display_selection
    return (state._connected and state.config.producer == 'm1' and status.get('source') == 'pico' and
            state.upstream_session_id is not None and
            host_session_id(status) == state.upstream_session_id and
            package_version(status) == state.host_package_version and
            package_version(status) in SOURCE_VERSIONS and current is not None and
            selected['source'] == current['source'] and selected['generation'] == current['generation'])


def projection(state, status, payload, *, status_request_started_at=None, status_request_elapsed_s=None):
    if not isinstance(status, dict) or not isinstance(payload, dict): raise ProtocolError('invalid Sonard diagnostics')
    result = {'version': 1, 'kind': 'sonar-diagnostics', 'serverSessionId': state.server_session_id,
              'generation': state.generation, 'pingId': None, 'observedAtEpochMs': None, 'source': None,
              'fields': [], 'trackTotal': 0, 'trackOmitted': 0, 'blockTotal': 0, 'blockOmitted': 0,
              'available': True, 'stale': True,
              'statusObservedAtEpochMs': None, 'statusStale': True, 'statusTimeBasis': None}
    status_matches = status_context_matches(state, status)
    if status_matches and type(status_request_started_at) is int and status_request_started_at > 0:
        age = state._now() - status_request_started_at
        result.update(statusObservedAtEpochMs=status_request_started_at, statusTimeBasis=STATUS_TIME_BASIS,
                      statusStale=not (-5000 <= age <= state.config.stale_after_s * 1000 and
                          type(status_request_elapsed_s) in (int, float) and
                          math.isfinite(status_request_elapsed_s) and 0 <= status_request_elapsed_s <= state.config.stale_after_s))
    fields = result['fields']; characters = 0; seen = set()
    def add(group, name, value, unit=None):
        nonlocal characters
        text = _text(value)
        if text is None: return True
        key = (group[:80], name[:80])
        if key in seen: return True
        row = {'group': group[:80], 'name': name[:80], 'value': text}
        if unit is not None: row['unit'] = unit[:40]
        size = sum(len(item) for item in row.values())
        if len(fields) >= MAX_FIELDS or characters + size > MAX_CHARACTERS: return False
        fields.append(row)
        if len(json.dumps(result, separators=(',', ':'), ensure_ascii=False).encode()) > MAX_BODY - 256:
            fields.pop(); return False
        characters += size
        seen.add(key)
        return True
    extra = ()
    if package_version(status) in TRANSPORT_LIVENESS_VERSIONS:
        extra += TRANSPORT_LIVENESS_PATHS
    if (package_version(status) in DISPLAY_WINDOW_VERSIONS and
            _at(status, 'display_window.schema') == 'no5.display-window/1'):
        extra += DISPLAY_WINDOW_PATHS
    if (package_version(status) in PUBLISHED_VERSIONS and
            _at(status, 'noise_reduction.schema') == 'no5.sonard-noise-reduction-status/1'):
        extra += NOISE_PATHS
        if package_version(status) in IMPULSE_VERSIONS:
            extra += IMPULSE_PATHS
    if package_version(status) in DEPTH_RATE_VERSIONS:
        if _at(status, 'transmit.original_reference.schema') == 'no5.original-reference-observation/1':
            extra += REFERENCE_PATHS
        if (_at(status, 'transmit.schema') == 'no5.depth-rate-status/1' and
                _at(status, 'transmit.rate_control.schema') == 'no5.depth-rate-control/1'):
            extra += DEPTH_RATE_PATHS
    paths = extra + STATUS_PATHS
    def add_path(path):
        group, _, name = path.partition('.')
        leaf = path.rsplit('.', 1)[-1]
        unit = ('Hz' if leaf.endswith('_hz') or leaf in {'hz', 'rolling_1s_min'} else
                's' if leaf.endswith('_s') else 'µs' if leaf.endswith('_us') else
                'ns' if leaf.endswith('_ns') else 'm' if leaf.endswith('_m') else None)
        if path in IMPULSE_PATHS and leaf.endswith('_adc'):
            unit = 'ADC_CODE'
        add(group if name else 'status', name or group, _at(status, path), unit)
    # Essential source/session/window evidence comes first. Optional status
    # trees cannot starve the first complete matching track and template block.
    for path in CORE_STATUS_PATHS:
        if path in paths: add_path(path)
    def add_optional_status():
        for path in paths: add_path(path)
    add('gateway', 'unconfirmed_source_frames', state.unconfirmed_source_frames)
    add('gateway', 'old_selection_frames_dropped', state.old_selection_frames_dropped)
    detection, presentation = payload.get('detection'), payload.get('presentation')
    selected = None
    try: selected = display_selection(status.get('display_selection'))
    except ProtocolError: pass
    if selected is not None: result['source'] = selected['source']
    if not isinstance(detection, dict) or detection.get('schema') != 'no5.sonard-detection/2':
        add_optional_status(); return result
    if not isinstance(presentation, dict) or presentation.get('schema') != 'no5.sonard-presentation/1':
        add_optional_status(); return result
    ping, timestamp, origin = detection.get('ping_id'), detection.get('time_utc_ns'), detection.get('acquisition_source')
    context_matches = (status_matches and type(ping) is int and 0 <= ping < 2**63 and type(timestamp) is int and timestamp > 0 and
                       presentation.get('ping_id') == ping and isinstance(origin, str) and origin in {'own', 'native'} and selected is not None and
                       selected['source'] in {'all', origin} and selected['lastPublishedPingId'] == ping and
                       state.display_selection is not None and selected['generation'] == state.display_selection['generation'] and
                       selected['source'] == state.display_selection['source'])
    try:
        analysis_selection = display_selection(presentation.get('display_selection'))
        context_matches = context_matches and analysis_selection['generation'] == selected['generation'] and analysis_selection['source'] == selected['source']
    except ProtocolError: context_matches = False
    if not context_matches:
        add('diagnostics', 'analysis', 'SOURCE_OR_PING_NOT_MATCHED')
        add_optional_status()
        return result
    result.update(pingId=ping, observedAtEpochMs=timestamp // 1_000_000)
    age = state._now() - result['observedAtEpochMs']
    result['stale'] = (not -5000 <= age <= state.config.stale_after_s * 1000 or state.status_name() != 'LIVE')
    for name in ('valid_tx', 'time_utc_ns', 'sample_period_ns', 'tx_zero_sample_index', 'state', 'reason', 'acquisition_source'):
        add('ping', name, detection.get(name), UNITS.get(name))
    for name in ('quality', 'reason', 'acquisition_source'):
        add('presentation', name, presentation.get(name))
    # Whole-record admission: track/block omitted counts cannot imply that a
    # partly shown record was complete. The bounded remainder is explicit.
    collections = []
    for collection, keys in (('tracks', TRACK_FIELDS), ('blocks', BLOCK_FIELDS)):
        rows = detection.get(collection)
        if not isinstance(rows, list): rows = []
        total_key, omitted_key = ('trackTotal', 'trackOmitted') if collection == 'tracks' else ('blockTotal', 'blockOmitted')
        result[total_key] = len(rows)
        collections.append([collection, keys, rows, omitted_key, 0, False])
    # Alternate records so a busy track list cannot consume the entire budget
    # before even the first block's qualification/template evidence is shown.
    for index in range(4):
        if index == 1:
            add_optional_status()
            policy = payload.get('policy')
            if isinstance(policy, dict):
                for name, value in tuple(policy.items())[:24]: add('policy', name, value)
        for collection in collections:
            name, keys, rows, omitted_key, emitted, blocked = collection
            if blocked or index >= len(rows): continue
            item = rows[index]
            if not isinstance(item, dict) or not any(item.get(key) is not None for key in keys): continue
            old_size, old_chars, old_seen = len(fields), characters, set(seen)
            identifier = str(item.get('track_id', index)) if name == 'tracks' else str(index)
            group = ('track:' if name == 'tracks' else 'block:') + identifier
            if any((group[:80], key[:80]) in seen for key in keys): continue
            if all(add(group, key, item.get(key), UNITS.get(key)) for key in keys): collection[4] += 1
            else:
                del fields[old_size:]; characters = old_chars; seen = old_seen; collection[5] = True
    for _, _, rows, omitted_key, emitted, _ in collections:
        result[omitted_key] = len(rows) - emitted
    return result


class SonarDiagnostics:
    def __init__(self, state, exchange=None):
        self.state = state; self.exchange = exchange or self._exchange
        self._lock = asyncio.Lock(); self._cached = None; self._context = None; self._next = 0.; self._failure = False
    def _source_context(self):
        selection = self.state.display_selection or {}
        return (self.state.server_session_id, self.state.generation, self.state.config.upstream_base_url,
                self.state.upstream_session_id, self.state.host_package_version,
                selection.get('source'), selection.get('generation'), self.state._connected)
    def _age(self, value):
        cached = copy.deepcopy(value)
        if cached['observedAtEpochMs'] is not None:
            age = self.state._now() - cached['observedAtEpochMs']
            cached['stale'] = cached['stale'] or not -5000 <= age <= self.state.config.stale_after_s * 1000
        timestamp = cached['statusObservedAtEpochMs']
        if timestamp is not None:
            age = self.state._now() - timestamp
            cached['statusStale'] = cached['statusStale'] or not -5000 <= age <= self.state.config.stale_after_s * 1000
        return cached
    async def snapshot(self):
        if not self.state.diagnostics_supported: raise ProtocolError('Sonard diagnostics unavailable')
        async with self._lock:
            context = self._source_context()
            now = time.monotonic()
            if context == self._context and now < self._next:
                if self._failure: raise ProtocolError('Sonard diagnostics retry window')
                return self._age(self._cached)
            # No background task: only an explicit visible-UI request triggers
            # these existing read APIs. Identical consumers share one 1Hz fetch.
            self._next = now + 1.; self._context = context; self._cached = None; self._failure = True
            started_at = self.state._now()
            status, payload = await self.exchange(context[2])
            elapsed = time.monotonic() - now
            if context != self._source_context():
                raise ProtocolError('Sonard diagnostics source changed')
            if package_version(status) not in SOURCE_VERSIONS: raise ProtocolError('unreviewed Sonard diagnostics version')
            result = projection(self.state, status, payload, status_request_started_at=started_at, status_request_elapsed_s=elapsed)
            self._context = context; self._cached = result; self._failure = False
            return copy.deepcopy(result)
    async def _exchange(self, base):
        import aiohttp
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.state.config.upstream_timeout_s)) as session:
            async with session.get(base + '/api/v1/status') as response:
                if response.status != 200: raise ProtocolError('Sonard diagnostics status unavailable')
                status = await read_json(response)
            # Analysis is optional, as on the raw stream. Its failure cannot
            # erase an otherwise fresh, source-bound status/rate observation.
            try:
                async with session.get(base + '/api/v1/detection/latest') as response:
                    payload = await read_json(response) if response.status == 200 else {}
            except (asyncio.TimeoutError, OSError, aiohttp.ClientError, ProtocolError): payload = {}
            return status, payload

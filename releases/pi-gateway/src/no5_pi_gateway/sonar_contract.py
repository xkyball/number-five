"""Bounded additive Sonard evidence; no acquisition or transmitter control."""
from __future__ import annotations
import copy
import math
import uuid
from .protocol import ProtocolError

# These interfaces have been read against their package sources. A higher
# version number alone never grants a command or analysis capability.
ANALYSIS_VERSIONS = frozenset({'0.3.5', '0.3.6', '0.4.9', '0.4.10', '0.4.11', '0.4.12', '0.4.13', '0.4.14', '0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
SOURCE_VERSIONS = frozenset({'0.4.10', '0.4.11', '0.4.12', '0.4.13', '0.4.14', '0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
DEPTH_RATE_VERSIONS = frozenset({'0.4.11', '0.4.12', '0.4.13', '0.4.14', '0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
# Exact 0.4.13 wheel reviewed: dcd462f74ea7c0414545585ab2bea75c29076a238857e738a266177803932662.
# Exact 0.4.14 wheel reviewed: 77cd48bb9094eb7257e638c2d574594dbad4b5a78087cbbafc0cbad96a237179.
# Exact 0.4.15 wheel reviewed: 1f3abbe481d4803d7bdef7c57adbe3f4de220a7e09521c2a6847e0208aaf39f9.
PUBLISHED_VERSIONS = frozenset({'0.4.13', '0.4.14', '0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
DISPLAY_WINDOW_VERSIONS = frozenset({'0.4.14', '0.4.15', '0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
# Exact 0.4.16 source candidate reviewed: 590af7330c4ac5dbb9a23d91c91a47ebe1030fc45ffb96515f268bf13bf36ed3.
# Deployment approval is separate from this bounded read implementation.
# Exact0.4.17 display-only wheel reviewed: 6c7ddf80c7a894937f3109bae96c8477cade368789b79432adc8c8c40e4d311a.
# Exact0.4.18 transport/profile-only wheel reviewed: aa5a6a0c55090007a8fb1330eba74e189dbda15b0621ae2453090b7a1cf63381.
# Exact0.4.19 recovery-only wheel reviewed: e73b10e2df48f4c376fc218e354558a43c0fc2515613902dbd474ecc8bdc48fc.
ATOMIC_CONTEXT_VERSIONS = frozenset({'0.4.16', '0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
IMPULSE_VERSIONS = frozenset({'0.4.17', '0.4.18', '0.4.19', '0.4.20', '0.4.21', '0.4.22', '0.4.23', '0.4.24', '0.4.25', '0.4.26', '0.4.27', '0.4.28', '0.4.29', '0.4.30', '0.4.31'})
SOURCES = frozenset({'own', 'native', 'all'})
MAX_SIGNED = (1 << 63) - 1


def package_version(status):
    package = status.get('host_package') if isinstance(status, dict) else None
    if isinstance(package, dict) and package.get('name') == 'no5-sonard-openecho':
        value = package.get('version')
        if isinstance(value, str) and value in ANALYSIS_VERSIONS: return value
    return None


def host_session_id(status):
    """The preserved M1 host creates session.id with uuid4, not a ping ID."""
    session = status.get('session') if isinstance(status, dict) else None
    value = session.get('id') if isinstance(session, dict) else None
    if not isinstance(value, str) or len(value) != 36: return None
    try: return value if str(uuid.UUID(value)) == value else None
    except ValueError: return None


def integer(value, *, maximum=MAX_SIGNED):
    return type(value) is int and 0 <= value <= maximum


def display_selection(value, *, observed_at=None):
    if not isinstance(value, dict) or value.get('schema') != 'no5.sonard-display-selection/1':
        raise ProtocolError('unsupported Sonard display selection')
    source, generation, hz, last = (value.get(key) for key in ('source', 'generation', 'published_hz', 'last_published_ping_id'))
    if (not isinstance(source, str) or source not in SOURCES or not integer(generation) or type(hz) not in (int, float) or
            not math.isfinite(hz) or not 0 <= hz <= 10000 or (last is not None and not integer(last)) or
            value.get('automatic_native_fallback') is not False or value.get('raw_archive_source') != 'all' or
            value.get('raw_samples_modified') is not False):
        raise ProtocolError('invalid Sonard display selection')
    result = {'version': 1, 'source': source, 'generation': generation, 'publishedHz': float(hz),
            'lastPublishedPingId': last, 'observedAtEpochMs': observed_at,
            'rawArchiveSource': 'all', 'automaticNativeFallback': False}
    # In 0.4.13 generation is the display epoch (source OR noise policy).
    # The separate source-only counter must not be confused with that epoch.
    if 'selection_generation' in value:
        if not integer(value['selection_generation']):
            raise ProtocolError('invalid Sonard source selection generation')
        result['selectionGeneration'] = value['selection_generation']
    return result


def amplitude(status, *, scale_only=False):
    version = package_version(status)
    if version not in SOURCE_VERSIONS: return None
    acquisition = status.get('acquisition')
    value = acquisition.get('amplitude') if isinstance(acquisition, dict) else None
    result = {'version': 1, 'encoding': 'UINT16_LE', 'unit': 'ADC_CODE', 'significantBits': None,
              'fullScaleCode': None, 'normalizationSource': 'UNCONFIRMED', 'rawSamplesModified': None}
    transforms = {'max_pool_or_interpolate_without_amplitude_scaling'}
    if version in PUBLISHED_VERSIONS:
        transforms.add('background_reduction_then_max_pool_or_interpolate')
    if (isinstance(value, dict) and value.get('schema') == 'no5.display-amplitude/1' and
            value.get('encoding') == 'uint16_le' and value.get('unit') == 'adc_code' and
            type(value.get('significant_bits')) is int and value['significant_bits'] == 12 and
            type(value.get('fullscale_code')) is int and value['fullscale_code'] == 4095 and
            value.get('display_transform') in transforms and
            value.get('normalization_source') == 'HELLO_12bit_checked_host_contract' and
            value.get('raw_samples_modified') is False):
        result.update(significantBits=12, fullScaleCode=4095,
                      normalizationSource=value['normalization_source'], rawSamplesModified=False)
        if version in PUBLISHED_VERSIONS:
            # Status describes the latest display, not an arbitrary queued ping.
            # Per-frame active/revision are attached only from published facts.
            if scale_only:
                # Internal only: numerical scale for an eventual exact
                # publication join, never a stand-alone .13 display claim.
                return result
            noise = value.get('noise_reduction')
            policy = noise.get('policy') if isinstance(noise, dict) else None
            latest = noise.get('latest') if isinstance(noise, dict) else None
            if (isinstance(noise, dict) and noise.get('schema') == 'no5.sonard-noise-reduction-status/1' and
                    isinstance(policy, dict) and integer(policy.get('revision')) and
                    isinstance(latest, dict) and type(latest.get('active')) is bool and
                    latest['active'] == (value['display_transform'] == 'background_reduction_then_max_pool_or_interpolate')):
                result.update(noiseRevision=policy['revision'], noiseActive=latest['active'])
                result['displayTransform'] = value['display_transform']
            else:
                return publication_amplitude(None, None)
    return result


def publication_amplitude(value, publication):
    """The archive remains raw; the displayed ADC codes may be noise-reduced."""
    if publication is None:
        return {'version': 1, 'encoding': 'UINT16_LE', 'unit': 'ADC_CODE',
                'significantBits': None, 'fullScaleCode': None,
                'normalizationSource': 'UNCONFIRMED', 'rawSamplesModified': None}
    result = clone(value)
    if not isinstance(result, dict):
        result = publication_amplitude(None, None)
    result.update(displayTransform=('background_reduction_then_max_pool_or_interpolate'
                                   if publication.noise_active else
                                   'max_pool_or_interpolate_without_amplitude_scaling'),
                  noiseRevision=publication.noise_revision, noiseActive=publication.noise_active)
    return result


def observation_reason(status, *, status_received_at):
    """A reported reference fault is neither a transport failure nor a new ping.

    0.4.10 exposes only the latched TX reason. 0.4.11/0.4.12 may additionally bind
    the actual firmware fault to accepted USB time. Never use the latest
    recovery marker, TX start, status receipt or zero counters as fault time.
    """
    version = package_version(status)
    transmit = status.get('transmit') if isinstance(status, dict) else None
    if (version not in SOURCE_VERSIONS or not isinstance(transmit, dict) or
            transmit.get('reason') != 'native_reference_lost'):
        return None
    result = {'version': 1, 'code': 'ORIGINAL_REFERENCE_LOST', 'detail': 'native_reference_lost',
              'observedAtEpochMs': None, 'statusObservedAtEpochMs': status_received_at,
              'latched': True, 'statusFresh': False}
    reference = transmit.get('original_reference')
    fault = reference.get('latched_fault') if isinstance(reference, dict) else None
    reasons = {1: 'native_interval_invalid', 2: 'native_period_outside_profile',
               3: 'previous_native_cycle_unfinished', 4: 'native_reference_deadline_expired'}
    if (version not in DEPTH_RATE_VERSIONS or not isinstance(reference, dict) or
            reference.get('schema') != 'no5.original-reference-observation/1' or not isinstance(fault, dict)):
        return result
    detail = fault.get('firmware_detail')
    if (type(detail) is not int or detail not in reasons or type(fault.get('firmware_event')) is not int or
            fault['firmware_event'] != 8 or fault.get('meaning') != 'fault' or
            fault.get('observation_reason') != reasons[detail] or
            fault.get('observation_kind') != ('timeout_check' if detail == 4 else 'marker_edge') or
            not integer(fault.get('observed_device_us'))):
        return result
    result.update(detail=reasons[detail], firmwareDetail=detail,
                  observedDeviceUs=fault['observed_device_us'], observationKind=fault['observation_kind'], utcBasis=None)
    timestamp = fault.get('observed_utc_ns')
    if fault.get('utc_basis') == 'accepted_usb_time_sync' and integer(timestamp) and timestamp > 0:
        result.update(observedAtEpochMs=timestamp // 1_000_000, utcBasis='accepted_usb_time_sync')
    return result


def frame_context(frame, payload):
    """Only a matching analysis can attach an actual own/native frame source."""
    if not isinstance(payload, dict): return None, None
    detection, presentation = payload.get('detection'), payload.get('presentation')
    if (not isinstance(detection, dict) or not isinstance(presentation, dict) or
            type(detection.get('ping_id')) is not int or type(presentation.get('ping_id')) is not int or
            type(detection.get('time_utc_ns')) is not int or detection.get('ping_id') != frame.ping_id or
            presentation.get('ping_id') != frame.ping_id or detection.get('time_utc_ns') != frame.time_utc_ns): return None, None
    origin = detection.get('acquisition_source')
    if not isinstance(origin, str) or origin not in {'own', 'native'}: return None, None
    try: selection = display_selection(presentation.get('display_selection'), observed_at=frame.observed_at_epoch_ms)
    except ProtocolError: return None, origin
    if selection['lastPublishedPingId'] != frame.ping_id or selection['source'] not in {'all', origin}:
        return None, None
    return selection, origin


def clone(value):
    return copy.deepcopy(value)

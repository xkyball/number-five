"""Compare caller-pinned, stored decoder measurements without running a decoder.

Only the explicitly supplied report/compatibility files are read. Chart, library,
source and resource paths contained in a report are never opened. Successful
comparison validates the declared measurement contract, not chart-content
retention or the authenticity of the original execution.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
from typing import Any

DEFAULT_MAX_REPORT_BYTES = 8 * 1024 * 1024  # Tool read budget, not a chart/report format limit.
COMPARISON_FORMAT = 'no-chart-converter-decoder-profile-comparison/1'
COMPATIBILITY_FORMAT = 'no-chart-converter-decoder-driver-compatibility/1'
SYNTHETIC_FIXTURE_KIND = 'SYNTHETIC_TEST_ONLY'
_GEOMETRY_FEATURE = '_ZN10RX5Feature12loadGeometryERK10QByteArrayS2_RKN9RX5Common25SPATIAL_COORDINATE_FORMATE'
_DATA_FEATURE = '_ZN10RX5Feature8loadDataERK10QByteArrayS2_'
_BINDINGS = ('code', 'declaredFileChartOwnershipSkeleton', 'readParser', 'keyOwnerGetterHelper')
_COMPATIBLE = ('sameInvocationABI', 'sameInstructionAccounting', 'sameGeometryMetadataPhases',
               'sameIOBridgeSemantics', 'sameOriginalResults', 'sameFreshRequestSemantics')
_COUNT_FUNCTIONS = {
    'nonSoundingBoundingBoxes': '_ZN21RX5TilePointContainer18computeBoundingBoxEv',
    'getX': '_ZNK21RX5TilePointContainer4getXEi',
    'getY': '_ZNK21RX5TilePointContainer4getYEi',
    'soundingContainers': '_ZN29RX5TileSoundingPointContainerC1EN9RX5Common25SPATIAL_COORDINATE_FORMATERK10QByteArrayRK15RX5SpatialIndex',
    'soundingXYZPointCtorU32': '_ZN28RX5TileSoundingPointTemplateIjEC1EPKc',
    'soundingXYZPointCtorU16': '_ZN28RX5TileSoundingPointTemplateItEC1EPKc',
}
_COUNTS = ('nonSoundingBoundingBoxes', 'nonSoundingXYPointOccurrences', 'getX', 'getY',
           'soundingContainers', 'soundingXYZPointCtorU32', 'soundingXYZPointCtorU16',
           'totalXYAndXYZPointOccurrences')


class DecoderProfileError(ValueError):
    """An input binding or declared measurement condition cannot be validated."""


def _need(ok: bool, message: str) -> None:
    if not ok:
        raise DecoderProfileError(message)


def _object(value: Any, label: str) -> dict:
    _need(type(value) is dict, f'{label}: expected object')
    return value


def _count(value: Any, label: str, *, positive: bool = False) -> int:
    _need(type(value) is int and (1 if positive else 0) <= value <= (1 << 63) - 1,
          f'{label}: expected nonnegative integer' if not positive else f'{label}: expected positive integer')
    return value


def _text(value: Any, label: str) -> str:
    _need(type(value) is str and bool(value) and len(value) <= 8192, f'{label}: expected bounded nonempty text')
    return value


def _sha(value: Any, label: str) -> str:
    _need(type(value) is str and re.fullmatch(r'[0-9a-f]{64}', value) is not None,
          f'{label}: expected lowercase SHA256')
    return value


def _pin(value: Any, label: str) -> dict:
    obj = _object(value, label)
    return {'bytes': _count(obj.get('bytes'), label + '.bytes', positive=True),
            'sha256': _sha(obj.get('sha256'), label + '.sha256')}


def _budget(value: int) -> int:
    _need(type(value) is int and value > 0, 'max_report_bytes: positive tool read budget required')
    return value


def _canonical(value: Any) -> bytes:
    try:
        return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
                          allow_nan=False).encode('ascii')
    except (TypeError, ValueError, RecursionError) as exc:
        raise DecoderProfileError('Input must be finite JSON data') from exc


def _tile(value: Any, label: str) -> list[int]:
    _need(type(value) is list and len(value) == 3 and all(type(v) is int for v in value),
          f'{label}: integer [level,x,y] required')
    z, x, y = value
    _need(0 <= z <= 29 and 0 <= x < 1 << z and 0 <= y < 1 << z, f'{label}: invalid logical tile')
    return list(value)


def _flags(value: Any, expected: list[int], label: str) -> None:
    _need(type(value) is list and all(type(v) is int for v in value) and value == expected,
          f'{label}: expected fresh load transition {expected}')


def _libraries(value: Any) -> list[dict]:
    _need(type(value) is list and 1 <= len(value) <= 64, 'originalLibraries: nonempty bounded image list required')
    result = []
    for lib in value:
        obj = _object(lib, 'originalLibraries.image')
        item = _pin(obj, 'originalLibraries.image')
        item['base'] = _count(obj.get('base'), 'originalLibraries.base')
        item['mappedBytes'] = _count(obj.get('mappedBytes'), 'originalLibraries.mappedBytes', positive=True)
        # File-only ELF sections need not fit in the guest mapped extent.
        result.append(item)
    _need(len({r['base'] for r in result}) == len(result), 'originalLibraries: duplicate mapped base')
    return sorted(result, key=lambda r: r['base'])


def _phase(value: Any, name: str, *, expected_feature_calls: int, feature_symbol: str) -> dict:
    phase = _object(value, name)
    _need(phase.get('name') == name, f'{name}: different cost phase label')
    instructions = _count(phase.get('executedOriginalInstructionVisits'), name + '.instructions', positive=True)
    read_bytes = _count(phase.get('streamReadBytes'), name + '.readBytes')
    reads = _count(phase.get('streamReads'), name + '.reads')
    calls = phase.get('calls')
    _need(type(calls) is list and len(calls) <= 65536, f'{name}: bounded IO call list required')
    actual_bytes = actual_reads = 0
    previous_end = None
    for call in calls:
        obj = _object(call, name + '.ioCall')
        _text(obj.get('method'), name + '.ioMethod')
        _need(obj.get('phase') == name, f'{name}: IO belongs to different phase')
        _need(obj.get('fail') is False and obj.get('eof') is False, f'{name}: failed/partial IO state')
        before = _count(obj.get('cursorBefore'), name + '.cursorBefore')
        after = _count(obj.get('cursorAfter'), name + '.cursorAfter')
        _need(previous_end is None or before == previous_end, f'{name}: discontinuous IO trace')
        previous_end = after
        if 'transferredBytes' in obj:
            count = _count(obj.get('transferredBytes'), name + '.transferredBytes')
            _need(_count(obj.get('requestedBytes'), name + '.requestedBytes') == count,
                  f'{name}: incomplete IO read')
            _sha(obj.get('sha256'), name + '.readSHA256')
            _need(before + count == after, f'{name}: read range/count mismatch')
            actual_bytes += count
            actual_reads += 1
        else:
            _need('requestedBytes' not in obj and _count(obj.get('position'), name + '.seek') == after,
                  f'{name}: invalid seek trace')
    _need((read_bytes, reads) == (actual_bytes, actual_reads), f'{name}: FD read sum mismatch')
    entries = _object(phase.get('originalFunctionEntries'), name + '.functionEntries')
    for symbol, count in entries.items():
        _text(symbol, name + '.functionSymbol')
        _count(count, name + '.functionCount')
    _need(entries.get(feature_symbol) == expected_feature_calls, f'{name}: feature decoder count mismatch')
    seconds = phase.get('hostEmulationSeconds')
    _need(type(seconds) in (int, float) and seconds >= 0 and
          ((type(seconds) is int and seconds <= (1 << 63) - 1) or
           (type(seconds) is float and math.isfinite(seconds))),
          f'{name}: finite host instrumentation time required')
    return {'originalInstructionVisits': instructions, 'streamReadBytes': read_bytes,
            'streamReads': reads, 'hostInstrumentationSeconds': seconds}


def _report(report: dict, expected_chart_sha256: str, allow_synthetic: bool, max_bytes: int) -> dict:
    obj = _object(report, 'report')
    canonical = _canonical(obj)
    _need(len(canonical) <= max_bytes, 'report: exceeds tool JSON budget, not a format limit')
    _text(obj.get('format'), 'report.format')
    synthetic = 'fixtureKind' in obj
    if synthetic:
        _need(obj['fixtureKind'] == SYNTHETIC_FIXTURE_KIND and allow_synthetic,
              'Synthetic reports require explicit allow_synthetic=True')
    _need(obj.get('status') == 'PASS_DECLARED_BOUNDED_ORIGINAL_REQUESTS', 'report: original request not completed')
    for name in ('actualGeometryRequests', 'actualMetadataLoads', 'actualTargetRequestsThisProcess'):
        _need(_count(obj.get(name), name) == 1, f'{name}: exactly one actual target required')
    _need(obj.get('vendorResultStubs') is False, 'report: original result stubs forbidden')
    _need(_count(obj.get('sourceChartOrDeviceWrites'), 'sourceChartOrDeviceWrites') == 0,
          'report: source/device writes forbidden')
    _need(obj.get('physicalA97TimingClaim') is False, 'report: hardware timing claim incompatible')
    chart = _pin(obj.get('chart'), 'chart')
    _need(chart['sha256'] == _sha(expected_chart_sha256, 'expected chart SHA256'), 'Declared chart SHA256 mismatch')
    libraries = _libraries(obj.get('originalLibraries'))
    bindings = {key: _pin(obj.get(key), key) for key in _BINDINGS}
    counter_scope = _text(obj.get('instructionCounterScope'), 'instructionCounterScope')
    definitions = _object(obj.get('pointCountDefinitions'), 'pointCountDefinitions')
    for key in ('nonSounding', 'sounding', 'total'):
        _text(definitions.get(key), 'pointCountDefinitions.' + key)
    fresh_scope = _text(obj.get('freshRequestSemantics'), 'freshRequestSemantics')
    warm_scope = _text(obj.get('warmScope'), 'warmScope')
    bound = _object(obj.get('rootApprovedSingleCallBound'), 'cold geometry call bound')
    bound = {'function': _text(bound.get('function'), 'bound.function'),
             'phase': _text(bound.get('phase'), 'bound.phase'),
             'wallMicroseconds': _count(bound.get('wallMicroseconds'), 'bound.wallMicroseconds', positive=True),
             'instructionCap': _count(bound.get('instructionCap'), 'bound.instructionCap', positive=True)}
    cases = obj.get('cases')
    _need(type(cases) is list and len(cases) == 1, 'report: exactly one case required')
    case = _object(cases[0], 'case')
    name = _text(case.get('name'), 'case.name')
    destination = _tile(case.get('requestedTile'), 'requestedTile')
    source = _tile(case.get('actualOriginalSourceTileId'), 'actualOriginalSourceTileId')
    _need(case.get('predictedPhysicalSource') == source, 'case: predicted/native source mismatch')
    shift = destination[0] - source[0]
    _need(shift >= 0 and destination[1] >> shift == source[1] and destination[2] >> shift == source[2],
          'case: source is not the declared destination ancestor')
    _need(bound['phase'] == name + ':cold_loadGeometry', 'bound: wrong geometry phase')
    _need(bound['function'] == '_ZN13RX5VectorTile12loadGeometryEv', 'bound: wrong geometry entry point')
    _flags(case.get('freshVectorFlagsBefore'), [0, 0], 'freshVectorFlagsBefore')
    _flags(case.get('loadedVectorFlagsAfter'), [1, 0], 'loadedVectorFlagsAfter')
    _flags(case.get('flagsAfterColdData'), [1, 1], 'flagsAfterColdData')
    features = _count(case.get('featureCount'), 'featureCount')
    counts = _object(case.get('actualNativeCounts'), 'actualNativeCounts')
    counts = {key: _count(counts.get(key), key) for key in _COUNTS}
    xy, boxes = counts['nonSoundingXYPointOccurrences'], counts['nonSoundingBoundingBoxes']
    xyz = counts['soundingXYZPointCtorU32'] + counts['soundingXYZPointCtorU16']
    _need(counts['getX'] == counts['getY'] == 2 * (xy + boxes), 'Native XY/BBox axis counts inconsistent')
    _need(counts['totalXYAndXYZPointOccurrences'] == xy + xyz, 'Native total point count inconsistent')
    metrics = _object(case.get('structuralMetrics'), 'structuralMetrics')
    totals = _object(metrics.get('totals'), 'structuralMetrics.totals')
    for key, value in totals.items():
        _text(key, 'structural count key')
        _count(value, 'structural count ' + key)
    expected = {'features': features, 'nonSoundingParts': boxes, 'nonSoundingXYPointOccurrences': xy,
                'soundingParts': counts['soundingContainers'], 'soundingXYZPointOccurrences': xyz}
    _need(all(totals.get(key) == value for key, value in expected.items()), 'Structural/native typed counts disagree')
    kinds = {key: value for key, value in totals.items() if re.fullmatch(r'kind[0-9]+', key)}
    _need(bool(kinds) and sum(kinds.values()) == features, 'Structural feature-kind sum inconsistent')
    _sha(metrics.get('liveGeometryElementOrderedSHA256'), 'live geometry SHA256')
    geometry = _phase(case.get('coldGeometry'), name + ':cold_loadGeometry',
                      expected_feature_calls=features, feature_symbol=_GEOMETRY_FEATURE)
    _need(geometry['originalInstructionVisits'] <= bound['instructionCap'],
          'Geometry instruction visits exceed the declared single-call instruction cap')
    geometry_entries = case['coldGeometry']['originalFunctionEntries']
    _need(all(geometry_entries.get(symbol) == counts[key] for key, symbol in _COUNT_FUNCTIONS.items()),
          'Native summary/function-entry counts disagree')
    data = _phase(case.get('coldData'), name + ':cold_loadData',
                  expected_feature_calls=features, feature_symbol=_DATA_FEATURE)
    return {'chart': chart, 'libraries': libraries, 'bindings': bindings, 'counterScope': counter_scope,
            'pointDefinitions': definitions, 'freshScope': fresh_scope, 'warmScope': warm_scope,
            'bound': bound, 'name': name, 'destination': destination, 'source': source, 'features': features,
            'counts': counts, 'kinds': kinds, 'geometry': geometry, 'data': data, 'xyz': xyz,
            'synthetic': synthetic, 'canonicalInput': {'bytes': len(canonical), 'sha256': hashlib.sha256(canonical).hexdigest()}}


def _delta(before: int, after: int) -> dict:
    """Signed reductions permit regressions and zero baselines."""
    return {'before': before, 'after': after, 'removed': before - after,
            'reductionPercent': None if before == 0 else (before - after) * 100 / before}


def compare_decoder_profiles(baseline: dict, candidate: dict, *, expected_baseline_chart_sha256: str,
                             expected_candidate_chart_sha256: str, allow_synthetic: bool = False,
                             driver_compatibility: dict | None = None,
                             max_report_bytes: int = DEFAULT_MAX_REPORT_BYTES) -> dict:
    """Validate matched declared measurements; infer neither actual provenance nor content safety.

    Different driver/adapter/parser/helper pins require an explicit, exact-pair
    compatibility review declaration. This function does not execute that review.
    Canonical JSON hashes bind dict inputs; use the file API for raw-file SHA pins.
    """
    _need(type(allow_synthetic) is bool, 'allow_synthetic: boolean required')
    budget = _budget(max_report_bytes)
    b = _report(baseline, expected_baseline_chart_sha256, allow_synthetic, budget)
    c = _report(candidate, expected_candidate_chart_sha256, allow_synthetic, budget)
    _need(b['synthetic'] == c['synthetic'], 'Cannot mix synthetic fixtures and unmarked stored reports')
    for key in ('libraries', 'counterScope', 'pointDefinitions', 'freshScope', 'warmScope',
                'bound', 'name', 'destination', 'source'):
        _need(b[key] == c[key], 'Measurement contract mismatch: ' + key)
    compatibility = None
    if driver_compatibility is not None:
        receipt = _object(driver_compatibility, 'driver compatibility')
        raw = _canonical(receipt)
        _need(len(raw) <= budget, 'Compatibility receipt exceeds tool JSON budget')
        _need(receipt.get('format') == COMPATIBILITY_FORMAT, 'Unknown driver compatibility schema')
        baseline_bindings = _object(receipt.get('baselineBindings'), 'compatibility.baselineBindings')
        candidate_bindings = _object(receipt.get('candidateBindings'), 'compatibility.candidateBindings')
        _need(set(baseline_bindings) == set(candidate_bindings) == set(_BINDINGS), 'Incomplete compatibility binding set')
        baseline_bindings = {key: _pin(baseline_bindings[key], 'compatibility.baseline.' + key) for key in _BINDINGS}
        candidate_bindings = {key: _pin(candidate_bindings[key], 'compatibility.candidate.' + key) for key in _BINDINGS}
        _need(baseline_bindings == b['bindings'] and candidate_bindings == c['bindings'],
              'Compatibility receipt bound to different driver/ABI/helper pins')
        _need(all(receipt.get(key) is True for key in _COMPATIBLE), 'Incomplete driver compatibility review declaration')
        _sha(receipt.get('reviewEvidenceSHA256'), 'compatibility review evidence SHA256')
        _need(receipt.get('syntheticFixture') is b['synthetic'], 'Compatibility receipt origin mismatch')
        compatibility = {'canonicalSHA256': hashlib.sha256(raw).hexdigest(),
                         'reviewEvidenceSHA256': receipt['reviewEvidenceSHA256'],
                         'declarationOnlyNotReexecuted': True}
    _need(b['bindings'] == c['bindings'] or compatibility is not None,
          'Different driver/ABI/helper pins require exact compatibility review declaration')
    costs = {}
    for phase in ('geometry', 'data'):
        costs[phase] = {key: _delta(b[phase][key], c[phase][key])
                        for key in ('originalInstructionVisits', 'streamReadBytes', 'streamReads')}
    counters = {key: _delta(b['counts'][key], c['counts'][key]) for key in _COUNTS}
    return {'format': COMPARISON_FORMAT,
            'status': 'PASS_SYNTHETIC_REPORT_COMPARISON' if b['synthetic'] else 'PASS_MATCHED_STORED_DECODER_REPORTS',
            'evidenceKind': 'synthetic-test-only' if b['synthetic'] else 'caller-pinned-stored-reports',
            'inputs': {'baseline': b['canonicalInput'], 'candidate': c['canonicalInput'], 'binding': 'canonical-json'},
            'charts': {'baseline': b['chart'], 'candidate': c['chart'], 'hashesAreDeclaredNotChartReads': True},
            'target': {'name': b['name'], 'destination': b['destination'], 'source': b['source']},
            'measurementContract': {'originalLibraries': b['libraries'], 'instructionCounterScope': b['counterScope'],
                                    'pointCountDefinitions': b['pointDefinitions'], 'coldGeometryBound': b['bound'],
                                    'baselineBindings': b['bindings'], 'candidateBindings': c['bindings'],
                                    'driverCompatibility': compatibility},
            'featureOwners': _delta(b['features'], c['features']), 'nativeCounters': counters,
            'soundingXYZ': _delta(b['xyz'], c['xyz']), 'costs': costs,
            'featureKinds': {key: _delta(b['kinds'].get(key, 0), c['kinds'].get(key, 0))
                             for key in sorted(b['kinds'].keys() | c['kinds'].keys())},
            'hostInstrumentationSeconds': {phase: {'before': b[phase]['hostInstrumentationSeconds'],
                                                 'after': c[phase]['hostInstrumentationSeconds']}
                                           for phase in ('geometry', 'data')},
            'nativeCallsPerformed': 0, 'hardwareTimingClaim': False, 'semanticRetentionVerified': False,
            'limits': ['Stored report and review declarations do not cryptographically prove an original execution.',
                       'Referenced chart/library/resource paths are not opened; only declared chart hashes are matched.',
                       'Original instruction visits exclude bridged external bodies; not CPU cycles or device load times.',
                       'Changed counts do not prove retained content, geometry, query semantics or visible rendering.',
                       'Immediate same-object warm calls are not evidence of fresh pan/zoom cache performance.']}


def _read_json(path: str | Path, expected_sha256: str, budget: int) -> tuple[dict, dict]:
    expected = _sha(expected_sha256, 'expected report/receipt SHA256')
    def reject_constant(value):
        raise DecoderProfileError('Non-finite JSON constant: ' + value)
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            _need(key not in result, 'Duplicate JSON object key')
            result[key] = value
        return result
    try:
        _need(hasattr(os, 'O_NOFOLLOW'), 'Stable no-follow file reads unsupported on this platform')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, 'O_NONBLOCK', 0))
        with os.fdopen(fd, 'rb') as stream:
            before = os.fstat(stream.fileno())
            _need(stat.S_ISREG(before.st_mode), 'Explicit input must be a regular file')
            _need(before.st_size <= budget, 'Explicit input exceeds tool read budget, not a format limit')
            raw = stream.read(budget + 1)
            after = os.fstat(stream.fileno())
        fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
        end = os.stat(path, follow_symlinks=False)
        _need(tuple(getattr(before, k) for k in fields) == tuple(getattr(after, k) for k in fields)
              == tuple(getattr(end, k) for k in fields), 'Explicit input changed while reading')
        _need(len(raw) == before.st_size and len(raw) <= budget, 'Explicit input read extent/budget changed')
        _need(hashlib.sha256(raw).hexdigest() == expected, 'Explicit input SHA256 mismatch')
        value = json.loads(raw, parse_constant=reject_constant, object_pairs_hook=unique_keys)
        return _object(value, 'explicit JSON input'), {'bytes': len(raw), 'sha256': expected}
    except DecoderProfileError:
        raise
    except (OSError, ValueError, RecursionError) as exc:
        raise DecoderProfileError('Cannot read bounded, stable explicit JSON input') from exc


def compare_decoder_profile_files(baseline_path: str | Path, candidate_path: str | Path, *,
                                  expected_baseline_report_sha256: str, expected_candidate_report_sha256: str,
                                  expected_baseline_chart_sha256: str, expected_candidate_chart_sha256: str,
                                  max_report_bytes: int = DEFAULT_MAX_REPORT_BYTES, allow_synthetic: bool = False,
                                  driver_compatibility_path: str | Path | None = None,
                                  expected_driver_compatibility_sha256: str | None = None) -> dict:
    """Read only explicit regular JSON files, bounded and raw-SHA pinned; return no paths."""
    budget = _budget(max_report_bytes)
    _need((driver_compatibility_path is None) == (expected_driver_compatibility_sha256 is None),
          'Compatibility path and expected SHA256 must be supplied together')
    baseline, bp = _read_json(baseline_path, expected_baseline_report_sha256, budget)
    candidate, cp = _read_json(candidate_path, expected_candidate_report_sha256, budget)
    receipt = receipt_pin = None
    if driver_compatibility_path is not None:
        receipt, receipt_pin = _read_json(driver_compatibility_path, expected_driver_compatibility_sha256, budget)
    result = compare_decoder_profiles(baseline, candidate,
        expected_baseline_chart_sha256=expected_baseline_chart_sha256,
        expected_candidate_chart_sha256=expected_candidate_chart_sha256,
        allow_synthetic=allow_synthetic, driver_compatibility=receipt, max_report_bytes=budget)
    result['inputs'] = {'baseline': bp, 'candidate': cp, 'binding': 'explicit-raw-file-sha256'}
    result['explicitInputFilesRead'] = 2 + (receipt_pin is not None)
    if receipt_pin is not None:
        result['driverCompatibilityInput'] = receipt_pin
    return result

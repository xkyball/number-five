"""Wholly invented small reports; never charts or original-runtime fixtures.

The factory is also usable by CLI tests. Every report is explicitly marked
synthetic and requires the public allow_synthetic test mode.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from no_chart_converter import decoder_profile as profile

BASE_CHART_SHA = 'a' * 64
CANDIDATE_CHART_SHA = 'b' * 64
GEO_FEATURE = '_ZN10RX5Feature12loadGeometryERK10QByteArrayS2_RKN9RX5Common25SPATIAL_COORDINATE_FORMATE'
DATA_FEATURE = '_ZN10RX5Feature8loadDataERK10QByteArrayS2_'
BINDING_FIELDS = ('code', 'declaredFileChartOwnershipSkeleton', 'readParser', 'keyOwnerGetterHelper')


def synthetic_report(*, chart_sha=BASE_CHART_SHA, features=5, boxes=3, xy=12, xyz=4,
                     geo_instructions=1000, data_instructions=100, read_bytes=40):
    """Invented data, no native process, files or chart source involved."""
    def phase(label, symbol, instructions):
        calls = [] if not read_bytes else [dict(method='synthetic-read', phase=label,
            cursorBefore=10, cursorAfter=10+read_bytes, requestedBytes=read_bytes,
            transferredBytes=read_bytes, sha256='c'*64, fail=False, eof=False)]
        return dict(name=label, executedOriginalInstructionVisits=instructions,
                    streamReadBytes=read_bytes, streamReads=len(calls), calls=calls,
                    originalFunctionEntries={symbol: features}, hostEmulationSeconds=0.01)
    geo = phase('Synthetic region:cold_loadGeometry', GEO_FEATURE, geo_instructions)
    geo['originalFunctionEntries'].update({
        '_ZN21RX5TilePointContainer18computeBoundingBoxEv': boxes,
        '_ZNK21RX5TilePointContainer4getXEi': 2*(xy+boxes),
        '_ZNK21RX5TilePointContainer4getYEi': 2*(xy+boxes),
        '_ZN29RX5TileSoundingPointContainerC1EN9RX5Common25SPATIAL_COORDINATE_FORMATERK10QByteArrayRK15RX5SpatialIndex': 2,
        '_ZN28RX5TileSoundingPointTemplateIjEC1EPKc': xyz,
        '_ZN28RX5TileSoundingPointTemplateItEC1EPKc': 0,
    })
    result = dict(format='synthetic-decoder-fixture/1', fixtureKind=profile.SYNTHETIC_FIXTURE_KIND,
        status='PASS_DECLARED_BOUNDED_ORIGINAL_REQUESTS', actualGeometryRequests=1,
        actualMetadataLoads=1, actualTargetRequestsThisProcess=1, vendorResultStubs=False,
        sourceChartOrDeviceWrites=0, physicalA97TimingClaim=False,
        chart=dict(path='REFERENCE_DO_NOT_OPEN/chart.rx5', bytes=1234, sha256=chart_sha),
        originalLibraries=[dict(path='REFERENCE_DO_NOT_OPEN/library.so', bytes=100, mappedBytes=4096,
                                base=4096, sha256='d'*64)],
        instructionCounterScope='Synthetic counter contract, no original execution',
        pointCountDefinitions=dict(nonSounding='invented XY', sounding='invented XYZ', total='invented sum'),
        freshRequestSemantics='Synthetic fresh object declaration', warmScope='Synthetic same-object only',
        rootApprovedSingleCallBound=dict(function='_ZN13RX5VectorTile12loadGeometryEv',
            phase='Synthetic region:cold_loadGeometry', wallMicroseconds=1000000, instructionCap=100000),
        cases=[dict(name='Synthetic region', requestedTile=[2, 2, 2],
            actualOriginalSourceTileId=[1, 1, 1], predictedPhysicalSource=[1, 1, 1], featureCount=features,
            freshVectorFlagsBefore=[0, 0], loadedVectorFlagsAfter=[1, 0], flagsAfterColdData=[1, 1],
            actualNativeCounts=dict(nonSoundingBoundingBoxes=boxes, nonSoundingXYPointOccurrences=xy,
                getX=2*(xy+boxes), getY=2*(xy+boxes), soundingContainers=2,
                soundingXYZPointCtorU32=xyz, soundingXYZPointCtorU16=0, totalXYAndXYZPointOccurrences=xy+xyz),
            structuralMetrics=dict(liveGeometryElementOrderedSHA256='e'*64,
                totals=dict(features=features, kind1=1, kind2=1, kind3=features-4, kind4=1, kind9=1,
                    nonSoundingParts=boxes, nonSoundingXYPointOccurrences=xy,
                    soundingParts=2, soundingXYZPointOccurrences=xyz)),
            coldGeometry=geo, coldData=phase('Synthetic region:cold_loadData', DATA_FEATURE, data_instructions))])
    for i, field in enumerate(BINDING_FIELDS):
        result[field] = dict(path='REFERENCE_DO_NOT_OPEN/'+field, bytes=10+i, sha256=str(i+1)*64)
    return result


def compare(baseline=None, candidate=None, **kwargs):
    return profile.compare_decoder_profiles(baseline or synthetic_report(),
        candidate or synthetic_report(chart_sha=CANDIDATE_CHART_SHA),
        expected_baseline_chart_sha256=BASE_CHART_SHA,
        expected_candidate_chart_sha256=CANDIDATE_CHART_SHA, allow_synthetic=True, **kwargs)


def put_file(path, data):
    raw = json.dumps(data, sort_keys=True).encode() if isinstance(data, dict) else data
    path.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


def file_kwargs(tmp_path, baseline=None, candidate=None):
    b, c = tmp_path/'before.json', tmp_path/'after.json'
    return dict(baseline_path=b, candidate_path=c,
        expected_baseline_report_sha256=put_file(b, baseline or synthetic_report()),
        expected_candidate_report_sha256=put_file(c, candidate or synthetic_report(chart_sha=CANDIDATE_CHART_SHA)),
        expected_baseline_chart_sha256=BASE_CHART_SHA, expected_candidate_chart_sha256=CANDIDATE_CHART_SHA,
        allow_synthetic=True)


def compatibility_receipt(baseline, candidate):
    def bindings(report):
        return {key: {field: report[key][field] for field in ('bytes', 'sha256')} for key in BINDING_FIELDS}
    return dict(format=profile.COMPATIBILITY_FORMAT, baselineBindings=bindings(baseline),
        candidateBindings=bindings(candidate), syntheticFixture=True, reviewEvidenceSHA256='f'*64,
        sameInvocationABI=True, sameInstructionAccounting=True, sameGeometryMetadataPhases=True,
        sameIOBridgeSemantics=True, sameOriginalResults=True, sameFreshRequestSemantics=True)


def test_synthetic_reduction_preserves_separate_costs_and_has_no_actual_claim():
    b = synthetic_report()
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA, features=4, boxes=2, xy=6,
                         geo_instructions=500, data_instructions=90, read_bytes=20)
    snapshot = copy.deepcopy((b, c))
    result = compare(b, c)
    assert result['status'] == 'PASS_SYNTHETIC_REPORT_COMPARISON'
    assert result['costs']['geometry']['originalInstructionVisits']['reductionPercent'] == 50
    assert result['costs']['data']['originalInstructionVisits']['reductionPercent'] == 10
    assert result['nativeCounters']['nonSoundingXYPointOccurrences']['removed'] == 6
    assert result['soundingXYZ']['removed'] == 0
    assert result['nativeCallsPerformed'] == 0
    assert result['hardwareTimingClaim'] is result['semanticRetentionVerified'] is False
    assert (b, c) == snapshot
    assert 'REFERENCE_DO_NOT_OPEN' not in json.dumps(result)


def test_regressions_and_zero_denominators_are_reported_without_assuming_success():
    b = synthetic_report(xy=0, boxes=0, xyz=0)
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA, geo_instructions=2000)
    result = compare(b, c)
    assert result['costs']['geometry']['originalInstructionVisits']['removed'] == -1000
    assert result['costs']['geometry']['originalInstructionVisits']['reductionPercent'] == -100
    assert result['soundingXYZ']['reductionPercent'] is None
    assert result['soundingXYZ']['after'] == 4
    assert result['semanticRetentionVerified'] is False


def test_relocated_reference_paths_do_not_change_image_or_driver_identity():
    b, c = synthetic_report(), synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    c['originalLibraries'][0]['path'] = 'different-unopened-root/library.so'
    for key in BINDING_FIELDS:
        c[key]['path'] = 'another-unopened-root/' + key
    assert compare(b, c)['costs']['geometry']['originalInstructionVisits']['removed'] == 0


def mutate_target(d):d['cases'][0]['requestedTile'] = [2, 3, 2]
def mutate_source(d):
    d['cases'][0]['actualOriginalSourceTileId'] = [2, 2, 2]
    d['cases'][0]['predictedPhysicalSource'] = [2, 2, 2]
def mutate_library(d):d['originalLibraries'][0]['sha256'] = '9'*64
def mutate_counter(d):d['instructionCounterScope'] = 'different counters'
def mutate_definition(d):d['pointCountDefinitions']['sounding'] = 'different definition'
def mutate_driver(d):d['code']['sha256'] = '9'*64
def mutate_adapter(d):d['declaredFileChartOwnershipSkeleton']['sha256'] = '9'*64
def mutate_parser(d):d['readParser']['sha256'] = '9'*64
def mutate_helper(d):d['keyOwnerGetterHelper']['sha256'] = '9'*64
def mutate_bound(d):d['rootApprovedSingleCallBound']['instructionCap'] += 1


@pytest.mark.parametrize('mutation', [mutate_target, mutate_source, mutate_library, mutate_counter,
    mutate_definition, mutate_driver, mutate_adapter, mutate_parser, mutate_helper, mutate_bound])
def test_unmatched_measurement_conditions_are_refused(mutation):
    candidate = synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    mutation(candidate)
    with pytest.raises(profile.DecoderProfileError):compare(candidate=candidate)


@pytest.mark.parametrize('field,value', [('freshVectorFlagsBefore', [1, 0]), ('freshVectorFlagsBefore', [False, 0]),
    ('loadedVectorFlagsAfter', [1, 1]), ('flagsAfterColdData', [1, 0])])
def test_actual_fresh_and_completed_states_required(field, value):
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c['cases'][0][field] = value
    with pytest.raises(profile.DecoderProfileError, match='fresh load transition'):compare(candidate=c)


@pytest.mark.parametrize('field,value', [('actualMetadataLoads', 0), ('actualGeometryRequests', True),
    ('actualTargetRequestsThisProcess', 2), ('vendorResultStubs', True), ('sourceChartOrDeviceWrites', 1),
    ('sourceChartOrDeviceWrites', False), ('physicalA97TimingClaim', True)])
def test_incomplete_mutating_stubbed_or_ambiguous_reports_are_refused(field, value):
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c[field] = value
    with pytest.raises(profile.DecoderProfileError):compare(candidate=c)


@pytest.mark.parametrize('field,value', [('getX', 2), ('nonSoundingXYPointOccurrences', -1),
    ('soundingXYZPointCtorU32', True), ('totalXYAndXYZPointOccurrences', 1)])
def test_native_count_arithmetic_and_types_are_checked(field, value):
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c['cases'][0]['actualNativeCounts'][field] = value
    with pytest.raises(profile.DecoderProfileError):compare(candidate=c)


def test_structural_counts_and_observed_function_entries_cannot_disagree():
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    c['cases'][0]['structuralMetrics']['totals']['nonSoundingParts'] += 1
    with pytest.raises(profile.DecoderProfileError, match='typed counts'):compare(candidate=c)
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    c['cases'][0]['coldGeometry']['originalFunctionEntries'][GEO_FEATURE] -= 1
    with pytest.raises(profile.DecoderProfileError, match='feature decoder'):compare(candidate=c)
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    c['cases'][0]['coldGeometry']['originalFunctionEntries']['_ZNK21RX5TilePointContainer4getXEi'] -= 1
    with pytest.raises(profile.DecoderProfileError, match='function-entry'):compare(candidate=c)


@pytest.mark.parametrize('field,value', [('streamReadBytes', 41), ('streamReads', 2),
    ('executedOriginalInstructionVisits', False), ('executedOriginalInstructionVisits', -1),
    ('hostEmulationSeconds', float('nan')), ('hostEmulationSeconds', 10**500)])
def test_phase_measurements_are_finite_typed_and_reconcile_with_io(field, value):
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c['cases'][0]['coldData'][field] = value
    with pytest.raises(profile.DecoderProfileError):compare(candidate=c)


@pytest.mark.parametrize('field,value', [('requestedBytes', 41), ('transferredBytes', -1), ('sha256', 'not-a-hash'),
    ('cursorAfter', 51), ('fail', True), ('eof', True), ('phase', 'Synthetic region:cold_loadData')])
def test_partial_wrong_phase_or_inconsistent_read_traces_are_refused(field, value):
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c['cases'][0]['coldGeometry']['calls'][0][field] = value
    with pytest.raises(profile.DecoderProfileError):compare(candidate=c)


def test_synthetic_mode_requires_explicit_opt_in_and_cannot_mix_origins():
    b, c = synthetic_report(), synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    with pytest.raises(profile.DecoderProfileError, match='Synthetic'):
        profile.compare_decoder_profiles(b, c, expected_baseline_chart_sha256=BASE_CHART_SHA,
            expected_candidate_chart_sha256=CANDIDATE_CHART_SHA)
    del c['fixtureKind']
    with pytest.raises(profile.DecoderProfileError, match='mix synthetic'):compare(b, c)
    # This remains invented data: only model the unmarked stored-report schema.
    # Deleting a marker never supplies authenticity or actual-execution evidence.
    del b['fixtureKind']
    result = compare(b, c)
    assert result['status'] == 'PASS_MATCHED_STORED_DECODER_REPORTS'
    assert result['nativeCallsPerformed'] == 0 and result['semanticRetentionVerified'] is False
    assert any('do not cryptographically prove' in line for line in result['limits'])


def test_exact_compatibility_declaration_binds_all_driver_abi_and_helper_pins():
    b, c = synthetic_report(), synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    c['code']['sha256'] = '9'*64
    receipt = compatibility_receipt(b, c)
    result = compare(b, c, driver_compatibility=receipt)
    assert result['measurementContract']['driverCompatibility']['declarationOnlyNotReexecuted'] is True
    for mutate in ('stale', 'missing-condition', 'missing-binding', 'wrong-origin', 'bad-sha', 'bool-size'):
        bad = copy.deepcopy(receipt)
        if mutate == 'stale':bad['candidateBindings']['code']['sha256'] = '8'*64
        if mutate == 'missing-condition':del bad['sameInvocationABI']
        if mutate == 'missing-binding':del bad['baselineBindings']['readParser']
        if mutate == 'wrong-origin':bad['syntheticFixture'] = False
        if mutate == 'bad-sha':bad['reviewEvidenceSHA256'] = 'bad'
        if mutate == 'bool-size':bad['baselineBindings']['code']['bytes'] = True
        with pytest.raises(profile.DecoderProfileError):compare(b, c, driver_compatibility=bad)


def test_file_api_reads_only_explicit_paths_and_raw_hash_binds_inputs(tmp_path, monkeypatch):
    args = file_kwargs(tmp_path)
    real_open = os.open;seen = []
    def explicit_only(path, flags, *rest, **kwargs):
        assert Path(path) in (args['baseline_path'], args['candidate_path'])
        seen.append(Path(path));return real_open(path, flags, *rest, **kwargs)
    with monkeypatch.context() as patch:
        patch.setattr(profile.os, 'open', explicit_only)
        result = profile.compare_decoder_profile_files(**args)
    assert seen == [args['baseline_path'], args['candidate_path']]
    assert result['inputs']['binding'] == 'explicit-raw-file-sha256'
    assert result['inputs']['baseline']['sha256'] == args['expected_baseline_report_sha256']
    assert result['explicitInputFilesRead'] == 2 and 'REFERENCE_DO_NOT_OPEN' not in json.dumps(result)
    args['expected_candidate_report_sha256'] = '0'*64
    with pytest.raises(profile.DecoderProfileError, match='SHA256 mismatch'):
        profile.compare_decoder_profile_files(**args)


def test_compatibility_file_requires_its_own_explicit_caller_hash(tmp_path):
    b, c = synthetic_report(), synthetic_report(chart_sha=CANDIDATE_CHART_SHA);c['code']['sha256'] = '9'*64
    args = file_kwargs(tmp_path, b, c);p = tmp_path/'compat.json';digest = put_file(p, compatibility_receipt(b, c))
    with pytest.raises(profile.DecoderProfileError, match='supplied together'):
        profile.compare_decoder_profile_files(**args, driver_compatibility_path=p)
    result = profile.compare_decoder_profile_files(**args, driver_compatibility_path=p,
        expected_driver_compatibility_sha256=digest)
    assert result['explicitInputFilesRead'] == 3
    assert result['driverCompatibilityInput']['sha256'] == digest


@pytest.mark.parametrize('raw', [b'{"x":1,"x":2}', b'{"x":NaN}', b'not JSON', b'[]'])
def test_strict_json_reader_rejects_ambiguous_or_non_object_input(tmp_path, raw):
    args = file_kwargs(tmp_path);args['expected_baseline_report_sha256'] = put_file(args['baseline_path'], raw)
    with pytest.raises(profile.DecoderProfileError):profile.compare_decoder_profile_files(**args)


def test_report_budget_is_explicit_tool_budget_not_implicit_format_limit(tmp_path):
    args = file_kwargs(tmp_path)
    with pytest.raises(profile.DecoderProfileError, match='tool read budget'):
        profile.compare_decoder_profile_files(**args, max_report_bytes=20)
    with pytest.raises(profile.DecoderProfileError, match='tool JSON budget'):
        compare(max_report_bytes=20)
    with pytest.raises(profile.DecoderProfileError, match='positive tool read budget'):
        compare(max_report_bytes=True)


def test_input_symlinks_and_fifos_are_refused_without_reading_or_blocking(tmp_path):
    args = file_kwargs(tmp_path);link = tmp_path/'link.json';link.symlink_to(args['baseline_path'])
    with pytest.raises(profile.DecoderProfileError):profile.compare_decoder_profile_files(**{**args, 'baseline_path': link})
    fifo = tmp_path/'pipe';os.mkfifo(fifo)
    with pytest.raises(profile.DecoderProfileError, match='regular file'):
        profile.compare_decoder_profile_files(**{**args, 'baseline_path': fifo})


def test_file_identity_metadata_changes_during_read_are_refused(tmp_path, monkeypatch):
    args = file_kwargs(tmp_path);real_fstat = os.fstat;calls = 0
    def changed(fd):
        nonlocal calls
        calls += 1;value = real_fstat(fd)
        if calls == 2:
            values = {key: getattr(value, key) for key in ('st_mode','st_dev','st_ino','st_size','st_mtime_ns','st_ctime_ns')}
            values['st_mtime_ns'] += 1
            return SimpleNamespace(**values)
        return value
    with monkeypatch.context() as patch:
        patch.setattr(profile.os, 'fstat', changed)
        with pytest.raises(profile.DecoderProfileError, match='changed while reading'):
            profile.compare_decoder_profile_files(**args)


def test_single_call_geometry_visits_cannot_exceed_declared_instruction_cap():
    b = synthetic_report()
    c = synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    for report in (b, c):
        report['rootApprovedSingleCallBound']['instructionCap'] = 999
    with pytest.raises(profile.DecoderProfileError, match='single-call instruction cap'):
        compare(b, c)


def test_identical_image_fingerprints_allow_file_only_sections_and_mapping_at_zero():
    b, c = synthetic_report(), synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
    for report in (b, c):
        report['originalLibraries'][0].update(bytes=10000, mappedBytes=4096, base=0)
    result = compare(b, c)
    assert result['measurementContract']['originalLibraries'][0]['bytes'] == 10000
    assert result['measurementContract']['originalLibraries'][0]['mappedBytes'] == 4096

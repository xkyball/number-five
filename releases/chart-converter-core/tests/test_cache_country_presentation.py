"""Invented presentation tests; no private chart, programme or glyph payloads."""
from dataclasses import replace
import hashlib
import json

import pytest

from no_chart_converter.cache_country.presentation import (
    PresentationRefusal, compile_closed_programme, feature_digest,
    parse_common_lups, parse_profile_rules, parse_s52_xml, select_lookup,
    split_commands,
    CONTEXT_PAINT_POLICY, target_owner_paint_state,
)


def feature(name='BOYLAT', primitive=0, attributes=(), geometry='point'):
    return {'sourceId': 'INVENTED:f17', 'sourceFeatureId': 17,
            'classCode': 17, 'classAcronym': name, 'sourcePrimitive': primitive,
            'attributes': [{'code': index + 1, 'acronym': key, 'type': kind,
                            'value': value} for index, (key, kind, value) in enumerate(attributes)],
            'geometry': {'kind': geometry}}


def rule(**updates):
    row = {'id': 'invented-default', 'classAcronym': 'BOYLAT', 'sourcePrimitive': 0,
           'sourceTable': 76, 'sourceRCID': 7, 'priority': 4,
           'category': 'STANDARD', 'attributes': [], 'instruction': 'SY(FAKE0001);'}
    row.update(updates)
    return row


def test_generic_attribute_specificity_and_stable_source_sequence():
    rows = parse_profile_rules([
        rule(), rule(id='first-red', attributes=[{'acronym': 'COLOUR', 'value': '3,1'}],
                     instruction='SY(FAKE0002);'),
        rule(id='second-red', attributes=[{'acronym': 'COLOUR', 'value': '3,1'}],
             instruction='SY(FAKE0003);')])
    fresh = feature(attributes=[('COLOUR', 'utf8', '3,1')])
    assert select_lookup(rows, fresh, 196608).identifier == 'first-red'
    assert select_lookup(rows, feature(), 196609).identifier == 'invented-default'
    # A new feature, source ID and typed attributes are matched again, not
    # joined to a prior chart's owner, UID or effective style manifest.
    fresh['sourceFeatureId'] = 99
    fresh['sourceId'] = 'OTHER:f99'
    fresh['attributes'][0]['value'] = '1,3'
    assert select_lookup(rows, fresh, 196608).identifier == 'invented-default'


def test_typed_rule_does_not_alias_string_integer_or_float():
    rows = parse_profile_rules([rule(), rule(id='integer', attributes=[{'acronym': 'BOYSHP', 'value': 4}])])
    assert select_lookup(rows, feature(attributes=[('BOYSHP', 'uint32', 4)]), 196608).identifier == 'integer'
    assert select_lookup(rows, feature(attributes=[('BOYSHP', 'utf8', '4')]), 196608).identifier == 'invented-default'
    assert select_lookup(rows, feature(attributes=[('BOYSHP', 'float64', 4.0)]), 196608).identifier == 'invented-default'


def test_unknown_class_duplicate_attributes_and_owner_rule_refuse():
    with pytest.raises(PresentationRefusal, match='NO_MATCHING_GENERIC_CLASS_PRESENTATION'):
        select_lookup(parse_profile_rules([rule()]), feature(name='UNKNOWN'), 196608)
    with pytest.raises(PresentationRefusal, match='AMBIGUOUS_DUPLICATE_FEATURE_ATTRIBUTE'):
        select_lookup(parse_profile_rules([rule()]), feature(attributes=[('COLOUR', 'utf8', '1'), ('COLOUR', 'utf8', '3')]), 196608)
    with pytest.raises(PresentationRefusal, match='NOT_OWNER_PROGRAMME'):
        parse_profile_rules([rule(uid=42)])


def xml_lookup(rcid, table, attributes='', instruction='SY(FAKE0001)'):
    return f'''<lookup id="{rcid}" RCID="{rcid}" name="BOYLAT">
      <type>Point</type><disp-prio>Point Symbol</disp-prio><table-name>{table}</table-name>
      {attributes}<instruction>{instruction}</instruction><display-cat>Standard</display-cat></lookup>'''


def test_public_xml_has_real_separate_paper_and_simple_tables():
    xml = ('<chartsymbols><lookups>' + xml_lookup(1, 'Paper') +
           xml_lookup(2, 'Simplified', instruction='SY(FAKE0002)') +
           xml_lookup(3, 'Paper', '<attrib-code>BOYSHP4</attrib-code>', 'SY(FAKE0003)') +
           '</lookups></chartsymbols>').encode()
    rows = parse_s52_xml(xml)
    fresh = feature(attributes=[('BOYSHP', 'uint32', 4)])
    assert select_lookup(rows, fresh, 196608).rcid == 3
    assert select_lookup(rows, fresh, 196609).rcid == 2
    assert select_lookup(rows, feature(), 196608).rcid == 1
    assert all(row.origin == 'public_opencpn_s52_xml' for row in rows)


def test_s52_string_lists_require_exact_string_and_wildcard_requires_presence():
    xml = ('<chartsymbols><lookups>' + xml_lookup(1, 'Paper') +
           xml_lookup(2, 'Paper', '<attrib-code>COLOUR3,1</attrib-code>') +
           xml_lookup(3, 'Paper', '<attrib-code>OBJNAM </attrib-code>') +
           '</lookups></chartsymbols>').encode()
    rows = parse_s52_xml(xml)
    assert select_lookup(rows, feature(attributes=[('COLOUR', 'utf8', '3,1')]), 196608).rcid == 2
    assert select_lookup(rows, feature(attributes=[('COLOUR', 'utf8', '1,3')]), 196608).rcid == 1
    assert select_lookup(rows, feature(attributes=[('OBJNAM', 'utf8', '')]), 196608).rcid == 3


def test_common_generic_lup_and_nxstyle_refusal():
    header = 'LU00001NILBOYLATP00004LSIMPLIFIED'
    text = (f'LUPT  {len(header)}{header}\nATTC  6COLOUR3,1\nINST  12SY(FAKE0001)\nDISC  8STANDARD\n').encode()
    rows = parse_common_lups(text)
    assert len(rows) == 1 and rows[0].table == 76
    assert select_lookup(rows, feature(attributes=[('COLOUR', 'utf8', '3,1')]), 196609).rcid == 1
    with pytest.raises(PresentationRefusal, match='NXSTYL_IS_NOT_A_GENERIC'):
        parse_common_lups(text.replace(b'COLOUR3,1', b'NXSTYL100'))


@pytest.mark.parametrize('low,high,two,expected', [
    (-2, -1, False, 'DEPIT'), (0, 2, False, 'DEPVS'),
    (2, 5, False, 'DEPMS'), (5, 6, False, 'DEPMD'),
    (10, 20, False, 'DEPDW'), (2, 5, True, 'DEPVS'),
    (5, 6, True, 'DEPDW'), (5, 5, False, 'DEPMD')])
def test_closed_depth_area_preserves_exact_contour_boundary(low, high, two, expected):
    row = parse_profile_rules([rule(classAcronym='DEPARE', sourcePrimitive=2, sourceTable=78,
                                    instruction='CS(DEPARE01)')])[0]
    fresh = feature('DEPARE', 2, [('DRVAL1', 'float64', float(low)), ('DRVAL2', 'float64', float(high))], 'area')
    programme, receipts = compile_closed_programme(row, fresh, {'twoShades': two, 'shallowContour': 2., 'safetyContour': 5., 'deepContour': 10.})
    assert programme == f'AC({expected});'.encode()
    assert receipts[0]['implementation'] == 'public_opencpn_depare01_closed'


def test_closed_depth_and_other_conditionals_need_complete_dependencies():
    row = parse_profile_rules([rule(classAcronym='DEPARE', sourcePrimitive=2, sourceTable=78,
                                    instruction='CS(DEPARE02)')])[0]
    with pytest.raises(PresentationRefusal, match='MARINER_SETTINGS_REQUIRED'):
        compile_closed_programme(row, feature('DEPARE', 2, geometry='area'), {})
    with pytest.raises(PresentationRefusal, match='PUBLIC_S52_PROVIDER_REQUIRED'):
        compile_closed_programme(replace(row, instruction='CS(OBSTRN04)'), feature(), {})
    with pytest.raises(PresentationRefusal, match='PUBLIC_S52_PROVIDER_REQUIRED'):
        compile_closed_programme(replace(row, instruction='CA(CHBLK,1,LITRD,2,0,90,1,0)'), feature(), {})


def test_only_real_soundg_multipoints_use_native_per_sample_helper():
    row = parse_profile_rules([rule(classAcronym='SOUNDG', instruction='CS(SOUNDG02)')])[0]
    programme, receipts = compile_closed_programme(row, feature('SOUNDG', geometry='multipoint'), {})
    assert programme == b'CS(SOUNDG01);'
    assert receipts[0]['originalSamplesRetained']
    with pytest.raises(PresentationRefusal, match='PUBLIC_S52_PROVIDER_REQUIRED'):
        compile_closed_programme(row, feature('SOUNDG'), {})


def test_command_boundaries_and_fresh_ir_digest():
    assert split_commands("TE('A;B(%s)','OBJNAM',3,1,2,'15110',1,0,CHBLK,29);AC(LANDA);") == [
        "TE('A;B(%s)','OBJNAM',3,1,2,'15110',1,0,CHBLK,29)", 'AC(LANDA)']
    with pytest.raises(PresentationRefusal, match='UNBALANCED_PROGRAMME'):
        split_commands('SY(FAKE0001')
    fresh = feature()
    assert feature_digest(fresh) == hashlib.sha256(json.dumps(fresh, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
    prior = feature_digest(fresh)
    fresh['geometry']['position'] = [21., -17.]
    assert feature_digest(fresh) != prior


def test_public_definition_framing_keeps_zero_maximum_pattern_distance():
    from no_chart_converter.cache_country.presentation import _xml_common_definitions
    xml = b'''<chartsymbols><patterns><pattern><name>FAKEPAT1</name>
      <definition>V</definition><filltype>L</filltype><spacing>C</spacing>
      <vector width="200" height="200"><distance min="150" max="0"/>
      <pivot x="1500" y="1500"/><origin x="1500" y="1300"/></vector>
      <color-ref>DCHGRD</color-ref><HPGL>SPD;SW1;PU1500,1300;PD;</HPGL>
      </pattern></patterns></chartsymbols>'''
    raw, admitted, refused = _xml_common_definitions(xml)
    assert len(admitted) == 1 and refused == []
    assert b'FAKEPAT1VLINCON0015000000' in raw


def test_reusable_bootstrap_discards_old_owner_selected_rows(tmp_path):
    from no_chart_converter.cache_country.presentation import bootstrap_public_s52_profile
    from no_chart_converter.country_runtime.inputs import file_pin
    from no_chart_converter.country_runtime.wire import AuthoredBlock, assemble
    old_common = (b'LUPT  34LU00001NILBOYLATP00004OSIMPLIFIED\n'
                  b'ATTC  10NXSTYL777\nINST  14SY(FAKE0001)\nDISC  9STANDARD\n'
                  b'SYMB  10SY00001NIL\nSYMD  39FAKE0001V000000000000010000100000000000\n'
                  b'SCRF  6ACHBLK\nSVCT  13SPA;PU0,0;PD;\n')
    header = bytearray(240)
    header[:8] = b'\x01RayRX5\0'
    blocks = [AuthoredBlock('palette', 64, 0, b'', b'OWN PALETTE'),
              AuthoredBlock('common', 65, 0, b'', old_common),
              AuthoredBlock('png', 68, 0, b'', b'OWN PNG'),
              AuthoredBlock('csv', 69, 0, b'', b'OWN CSV')]
    parent = tmp_path / 'own-parent.rx5'
    parent.write_bytes(assemble(header, blocks, {slot: None for slot in (136, 144, 160, 168, 184, 200)})[0])
    xml = tmp_path / 'own-lookups.xml'
    xml.write_bytes(('<chartsymbols><lookups>' + xml_lookup(2, 'Paper') +
                     xml_lookup(3, 'Simplified') + '</lookups></chartsymbols>').encode())
    result = bootstrap_public_s52_profile(file_pin(parent), file_pin(xml), tmp_path / 'bootstrap',
        provider_command=['OWN_PROVIDER'], settings={})
    profile = json.loads(open(result['profile']['path']).read())
    common_pin = next(r['data'] for r in profile['resources'] if r['id'] == 65)
    raw = open(common_pin['path'], 'rb').read()
    assert b'NXSTYL' not in raw and b'FAKE0001V' in raw
    assert len(parse_common_lups(raw)) == 2
    assert profile['provenance']['ownerManifestOrRegistryReused'] is False
    assert profile['requireAtlasBindings'] is True


def test_provider_cli_json_stdout_is_preserved_in_bounded_refusal(tmp_path, monkeypatch):
    import subprocess
    from no_chart_converter.cache_country.presentation import _external_programmes
    message = b'x' * 4096 + b'{"status":"REFUSED","reason":"S52_UNSUPPORTED_CONDITIONAL:QUAPOS01;TX"}'
    monkeypatch.setattr(subprocess, 'run', lambda *args, **kwargs:
        subprocess.CompletedProcess(args[0], 2, stdout=message, stderr=b''))
    context = {'source_package': {}, 'cells': [], 'owners': [], 'by_cell': {}}
    with pytest.raises(PresentationRefusal, match='S52_UNSUPPORTED_CONDITIONAL:QUAPOS01;TX') as caught:
        _external_programmes(context, {'provider': {'command': ['EXPLICIT_PROVIDER']}}, 'a' * 64, tmp_path)
    assert len(str(caught.value)) < 2200


def test_context_paint_policy_preserves_source_selections_and_uses_target_state():
    import copy
    selections = {'196608': {'displayPriority': 4, 'category': 'STANDARD',
                              'sourceTable': 82, 'sourceRCID': 11},
                  '196609': {'displayPriority': 6, 'category': 'STANDARD',
                              'sourceTable': 76, 'sourceRCID': 22}}
    original = copy.deepcopy(selections)
    with pytest.raises(PresentationRefusal, match='EXPLICIT_CONTEXT_PAINT_POLICY_REQUIRED'):
        target_owner_paint_state(selections, {}, source_id='INVENTED:f17')
    target, receipt = target_owner_paint_state(selections, {'contextPaintPolicy': CONTEXT_PAINT_POLICY})
    assert target == {'displayPriority': 6, 'category': 'STANDARD'}
    assert selections == original and receipt['sourceContextStates']['196608']['displayPriority'] == 4
    assert receipt['contextStatesDiffer'] and not receipt['sourceContextPaintOrderParityClaim']
    assert not receipt['drawingProgrammesAltered'] and receipt['derivedOwnersAdded'] == 0


def test_context_paint_policy_category_union_is_explicit_and_validated():
    selections = {'196608': {'displayPriority': 6, 'category': 'OTHER'},
                  '196609': {'displayPriority': 4, 'category': 'DISPLAYBASE'}}
    selected, receipt = target_owner_paint_state(selections, {'contextPaintPolicy': CONTEXT_PAINT_POLICY})
    assert selected == {'displayPriority': 6, 'category': 'DISPLAYBASE'}
    assert receipt['sourceContextStates']['196608']['category'] == 'OTHER'
    with pytest.raises(PresentationRefusal, match='UNSUPPORTED_CONTEXT_PAINT_POLICY'):
        target_owner_paint_state(selections, {'contextPaintPolicy': 'PAPER_ONLY'})
    selections['196609']['displayPriority'] = True
    with pytest.raises(PresentationRefusal, match='VALID_CONTEXT_PAINT_STATES_REQUIRED'):
        target_owner_paint_state(selections, {'contextPaintPolicy': CONTEXT_PAINT_POLICY})


def test_equal_context_paint_state_remains_backward_compatible_without_policy():
    selections = {str(key): {'displayPriority': 4, 'category': 'STANDARD'} for key in (196608, 196609)}
    selected, receipt = target_owner_paint_state(selections, {})
    assert selected == selections['196608']
    assert receipt['policy'] == 'IDENTICAL_CONTEXT_PAINT_STATES/1'
    assert receipt['sourceContextPaintOrderParityClaim'] and not receipt['contextStatesDiffer']


def test_compiled_owner_keeps_exact_context_packets_and_source_state(tmp_path, monkeypatch):
    from no_chart_converter.cache_country import presentation as p
    from no_chart_converter.country_runtime.inputs import file_pin
    from no_chart_converter.country_runtime.render_activation import decode_frames
    from no_chart_converter.country_runtime.native_hooks import fresh_visibility_profiles
    common = tmp_path / 'invented-common.txt'
    common.write_bytes(b'SYMB  1SYNTHETIC-ONLY\nSYMD  1FAKE0001\n')
    fresh = feature()
    uid = (1 << 32) | fresh['sourceFeatureId']
    owner = {'cellId': 'INVENTED', 'sourceOwnerOrdinal': 0, 'uid': uid, 'feature': fresh}
    ctx = {'cells': [{'id': 'INVENTED'}], 'owners': [owner], 'by_cell': {'INVENTED': [owner]}}
    programmes = {'196608': b'SY(FAKE0001);',
                  '196609': b"SY(FAKE0001);TX(OBJNAM,3,2,2,'15110',1,-1,CHBLK,26);"}
    provided = {'programmes': {key: value.hex() for key, value in programmes.items()},
                'selections': {key: {'sourceRCID': 11 + i, 'sourceTable': 82 if i == 0 else 76,
                    'priority': 4 if i == 0 else 6, 'category': 'STANDARD',
                    'instruction': value.decode('ascii')} for i, (key, value) in enumerate(programmes.items())}}
    monkeypatch.setattr(p, '_external_programmes', lambda *args:
                        ({('INVENTED', 17): provided}, {'invented': True}))
    profile = {'format': 'no5-cache-country-presentation/1', 'nativeScaminLevelU32': 0,
               'nativeScaminMultiplierU32': 1, 'contextPaintPolicy': CONTEXT_PAINT_POLICY,
               'resources': [{'id': 65, 'data': file_pin(common)}], 'provider': {'command': ['INVENTED']}}
    result = p.prepare_presentation(ctx,
        {'cells': {'INVENTED': {'additional': bytes(14), 'additionalOffsets': {uid: 0}}}},
        tmp_path / 'compiled', {'presentationProfileDocument': profile})
    resolution = result['owners'][uid]['resolution']
    assert resolution['displayPriority'] == 6
    assert resolution['selected']['displayPriority'] == 4  # Original Paper state, not relabelled target.
    assert resolution['selected']['sourceRCID'] == 11
    assert resolution['contextSelections']['196609']['displayPriority'] == 6
    registry = json.loads(open(result['pins']['sourceRegistry']['path']).read())
    assert registry[0]['actualAfterPriority'] == 6
    assert registry[0]['targetPaintStatePolicy']['targetStateDerived']
    assert registry[0]['contexts']['196608']['displayPriority'] == 4
    assert registry[0]['actualAfterFieldsMeaning'].startswith('declared_own_target_state')
    frames = decode_frames(result['cells']['INVENTED']['render'], {0})
    assert dict(frames[0].entries) == {int(key): value for key, value in programmes.items()}
    bindings = json.loads(open(result['pins']['sourcePresentationIndex']['path']).read())['pointProgramBindings']
    assert [x['priority'] for x in fresh_visibility_profiles(bindings[0])['BOYLAT']] == [4, 6]
    assert result['report']['contextPaintStatesDifferOwners'] == 1

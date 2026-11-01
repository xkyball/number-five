"""Own bounded budget/contiguous offset controls, no native crosspage result."""
import hashlib
import io
import json
import struct

import pytest

from no_chart_converter.cli import main
from no_chart_converter.navionics.batch import (
    Area24, BatchSpec, Line32, Point40, plan_plain_batch, plan_sha256, write_plain_batch,
)
from no_chart_converter.navionics.writer import RawMapHeader


def header():
    return RawMapHeader(0x1234, (100000,100000,200000,200000), (4,4), (8,20), 0)


def point():
    return BatchSpec(header(), (Point40('compat',126,(125000,175000)),))


def crossing():
    return BatchSpec(header(), (Line32('own-boundary-line',29,
        (tuple((i % 4, (i * 3) % 4) for i in range(750)),)),))


def test_default_plan_digests_remain_byte_for_byte_021_compatible():
    mixed = BatchSpec(header(), (
        Line32('compat-line',29,(((0,0),(1,2),(3,1)),)),
        Area24('compat-area',74,(((0,0),(3,0),(1,2),(0,0)),)),
    ))
    for spec, expected in [(point(), 'b9fd0574807201920bec28b54413a66b0160724607ec56d06f2c4e634ed44044'),
                           (mixed, '9a76a1c343bf361293a3705c057ab141e73bf0b29acfb70f8a98dec233ec5ab5')]:
        default = plan_plain_batch(spec)
        assert default == plan_plain_batch(spec, max_output_bytes=4096)
        assert 'outputBudgetBytes' not in default
        assert default['physicalPacking'] == 'FIRST_LOGICAL_BLOCK_ONLY'
        assert plan_sha256(default) == expected


def test_explicit_budget_binds_plan_but_never_changes_emitted_geometry():
    hashes, outputs = [], []
    for budget in (4000,4096,7000,8192):
        plan = plan_plain_batch(point(), max_output_bytes=budget)
        if budget != 4096:
            assert plan['outputBudgetBytes'] == budget
        digest = plan_sha256(plan); hashes.append(digest)
        sink = io.BytesIO()
        report = write_plain_batch(point(),sink,expected_plan_sha256=digest,max_output_bytes=budget)
        assert report['nativeReaderExecuted'] is False
        outputs.append(sink.getvalue())
    assert len(set(hashes)) == 4 and len(set(outputs)) == 1


def test_crosspage_source_only_plan_and_no_4100_disk_stride():
    spec = crossing()
    with pytest.raises(ValueError, match='UNQUALIFIED_PHYSICAL_PACKING'):
        plan_plain_batch(spec)
    plan = plan_plain_batch(spec,max_output_bytes=8192)
    assert plan['physicalPacking'] == 'SOURCE_BOUND_CONTIGUOUS_PLAIN_GENERIC_NATIVE_UNQUALIFIED'
    assert plan['outputBudgetBytes'] == 8192
    assert (plan['totalBytes'],plan['layerOffset'],plan['firstFeatureOffset'],plan['featureEndOffset']) == (4153,4098,4127,4146)
    assert plan['features'][0]['parts'][0]['streamOffset'] == 348
    sink = io.BytesIO()
    report = write_plain_batch(spec,sink,expected_plan_sha256=plan_sha256(plan),max_output_bytes=8192)
    raw = sink.getvalue()
    assert len(raw) == 4153 and struct.unpack_from('<I',raw,11)[0] == 4153
    assert raw[4092] == 0x88
    assert struct.unpack_from('<H',raw,4093)[0] == 749 % 4
    assert struct.unpack_from('<H',raw,4095)[0] == (749 * 3) % 4
    assert raw[4097] == 0 and raw[4098:4100] == b'\x12\x80'
    assert report['emittedStreamSHA256'] == hashlib.sha256(raw).hexdigest()


def test_wrong_budget_binding_and_overbudget_refuse_before_write():
    class Sink:
        calls = 0
        def write(self, raw):
            self.calls += 1
            return len(raw)
    for spec, plan_budget, write_budget in [(point(),8192,4096), (crossing(),8192,4096),
                                            (crossing(),8192,4152)]:
        sink = Sink(); expected = plan_sha256(plan_plain_batch(spec,max_output_bytes=plan_budget))
        with pytest.raises(ValueError):
            write_plain_batch(spec,sink,expected_plan_sha256=expected,max_output_bytes=write_budget)
        assert sink.calls == 0


@pytest.mark.parametrize('budget',[None,False,True,0,-1,8193,2**32,8192.0,'8192'])
def test_invalid_own_budget_refuses(budget):
    with pytest.raises(ValueError, match='output budget'):
        plan_plain_batch(point(),max_output_bytes=budget)


def test_cli_explicit_two_step_budget_and_wrong_budget_no_output(tmp_path,capsys):
    value={'format':'no5-own-plain-feature-batch/1','header':{
        'flags':0x1234,'raw_bounds':[100000,100000,200000,200000],
        'raw_grid':[4,4],'raw_levels':[8,20],'trailing_value':0},
        'features':[{'kind':32,'source_id':'own-boundary-line','class_code':29,
                     'parts':[[[i%4,(i*3)%4] for i in range(750)]]}],
        'layer_id':60,'layer_range':[0,100000],'date_text_1':'20261017','date_text_2':'20261017'}
    path=tmp_path/'source.json';raw=json.dumps(value).encode();path.write_bytes(raw)
    args=['--spec',str(path),'--expect-spec-sha256',hashlib.sha256(raw).hexdigest()]
    assert main(['plan-navionics-batch',*args])==2;capsys.readouterr()
    assert main(['plan-navionics-batch',*args,'--max-output-bytes','8192'])==0
    plan=json.loads(capsys.readouterr().out)
    output=tmp_path/'new.bin'
    write=['write-navionics-batch',*args,'--expect-plan-sha256',plan['planSHA256'],'--output',str(output)]
    assert main(write)==2 and not output.exists();capsys.readouterr()
    assert main([*write,'--max-output-bytes','8192'])==0
    result=json.loads(capsys.readouterr().out)
    assert output.stat().st_size==4153 and result['persistedFileReadback'] is True
    assert result['nativeReaderExecuted'] is False
    assert result['plan']['outputBudgetBytes']==8192

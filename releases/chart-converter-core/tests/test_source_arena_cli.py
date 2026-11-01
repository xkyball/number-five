"""Actual CLI using the existing invented arena fixture, no private charts."""
import hashlib
import json
import subprocess
import sys

from test_source_arena import fixture, A, B
from no_chart_converter.rx5.source_arena import plan_sha256


def run(tmp_path, *args):
    return subprocess.run([sys.executable,'-m','no_chart_converter.cli',*args],
                          cwd=tmp_path,text=True,capture_output=True)


def setup(tmp_path):
    raw=fixture();source=tmp_path/'source.rx5';source.write_bytes(raw)
    return raw,['--source',str(source),'--expect-sha256',hashlib.sha256(raw).hexdigest()]


def test_real_cli_plan_and_lossless_apply_are_bound_and_persisted(tmp_path):
    raw,source=setup(tmp_path);plan_file=tmp_path/'plan.json'
    planned=run(tmp_path,'plan-source-arenas',*source,'--tile',*map(str,A),'--output',str(plan_file))
    assert planned.returncode==0,planned.stdout+planned.stderr
    receipt=json.loads(planned.stdout);plan=json.loads(plan_file.read_bytes())
    digest=plan_sha256(plan)
    assert receipt['canonicalPlanSHA256']==digest
    assert receipt['plan']['sha256']==hashlib.sha256(plan_file.read_bytes()).hexdigest()
    target=tmp_path/'new.rx5';report=tmp_path/'result.json'
    args=['compact-source-arenas',*source,'--tile',*map(str,A),
          '--expect-plan-sha256',digest,'--output',str(target),'--report',str(report)]
    applied=run(tmp_path,*args)
    assert applied.returncode==0,applied.stdout+applied.stderr
    result=json.loads(report.read_bytes());output=target.read_bytes()
    assert result['persistedSHA256Verified']and result['sourcePlanSHA256']==digest
    assert result['chart']['sha256']==hashlib.sha256(output).hexdigest()
    inverse=bytearray(output[:len(raw)])
    for edit in result['allowedOriginalPrefixEdits']:
        inverse[edit['offset']:edit['offset']+edit['bytes']]=bytes.fromhex(edit['originalHex'])
    assert inverse==raw
    before=target.read_bytes();repeated=run(tmp_path,*args)
    assert repeated.returncode!=0 and target.read_bytes()==before


def test_wrong_source_wrong_scope_and_wrong_plan_never_create_output(tmp_path):
    raw,source=setup(tmp_path)
    from no_chart_converter.rx5.source_arena import plan_source_arenas
    digest=plan_sha256(plan_source_arenas(raw,[A]))
    output=tmp_path/'wrong.rx5';report=tmp_path/'wrong.json'
    for wrong_source,tile,expected in ((True,A,digest),(False,B,digest),(False,A,'0'*64)):
        actual=list(source)
        if wrong_source:actual[-1]='0'*64
        r=run(tmp_path,'compact-source-arenas',*actual,'--tile',*map(str,tile),
              '--expect-plan-sha256',expected,'--output',str(output),'--report',str(report))
        assert r.returncode!=0 and not output.exists()and not report.exists()


def test_plan_no_clobber_and_output_report_distinct(tmp_path):
    _,source=setup(tmp_path);same=tmp_path/'same.json';same.write_bytes(b'RETAIN')
    r=run(tmp_path,'plan-source-arenas',*source,'--tile',*map(str,A),'--output',str(same))
    assert r.returncode!=0 and same.read_bytes()==b'RETAIN'
    target=tmp_path/'same-new.rx5'
    r=run(tmp_path,'compact-source-arenas',*source,'--tile',*map(str,A),
          '--expect-plan-sha256','0'*64,'--output',str(target),'--report',str(target))
    assert r.returncode!=0 and not target.exists()


def test_normalized_output_report_alias_is_refused_before_chart_write(tmp_path):
    raw,source=setup(tmp_path);sub=tmp_path/'sub';sub.mkdir()
    from no_chart_converter.rx5.source_arena import plan_source_arenas
    digest=plan_sha256(plan_source_arenas(raw,[A]))
    target=tmp_path/'new.rx5';alias=sub/'..'/'new.rx5'
    r=run(tmp_path,'compact-source-arenas',*source,'--tile',*map(str,A),
          '--expect-plan-sha256',digest,'--output',str(target),'--report',str(alias))
    assert r.returncode!=0 and 'FRESH_DISTINCT_SOURCE_ARENA'in r.stdout
    assert not target.exists()and not alias.exists()

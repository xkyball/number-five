"""Installed CLI contract on explicitly invented stored reports only."""
import json
import subprocess
import sys

from test_decoder_profile import file_kwargs, synthetic_report, CANDIDATE_CHART_SHA


def run(tmp_path,*args):
    return subprocess.run([sys.executable,'-m','no_chart_converter.cli',*args],cwd=tmp_path,
                          capture_output=True,text=True)


def arguments(data):
    return ['--baseline',str(data['baseline_path']),'--candidate',str(data['candidate_path']),
            '--expect-baseline-report-sha256',data['expected_baseline_report_sha256'],
            '--expect-candidate-report-sha256',data['expected_candidate_report_sha256'],
            '--expect-baseline-chart-sha256',data['expected_baseline_chart_sha256'],
            '--expect-candidate-chart-sha256',data['expected_candidate_chart_sha256']]


def test_actual_cli_compares_separate_costs_and_reports_regression_without_native_claim(tmp_path):
    data=file_kwargs(tmp_path,candidate=synthetic_report(chart_sha=CANDIDATE_CHART_SHA,
                     xy=6,geo_instructions=1200,data_instructions=90,read_bytes=20))
    before={p:p.read_bytes()for p in (data['baseline_path'],data['candidate_path'])}
    out=tmp_path/'comparison.json'
    result=run(tmp_path,'compare-decoder-profiles',*arguments(data),'--allow-synthetic','--output',str(out))
    assert result.returncode==0,result.stdout+result.stderr
    document=json.loads(out.read_bytes())
    assert document['status']=='PASS_SYNTHETIC_REPORT_COMPARISON'
    assert document['costs']['geometry']['originalInstructionVisits']['removed']==-200
    assert document['costs']['data']['originalInstructionVisits']['removed']==10
    assert document['nativeCounters']['nonSoundingXYPointOccurrences']['removed']==6
    assert document['soundingXYZ']['removed']==0
    assert document['nativeCallsPerformed']==0 and document['explicitInputFilesRead']==2
    assert document['hardwareTimingClaim']is document['semanticRetentionVerified']is False
    assert document['inputs']['binding']=='explicit-raw-file-sha256'
    assert {p:p.read_bytes()for p in before}==before
    assert 'REFERENCE_DO_NOT_OPEN'not in out.read_text()
    assert sorted(p.name for p in tmp_path.iterdir())==['after.json','before.json','comparison.json']


def test_cli_requires_synthetic_opt_in_and_all_four_pins(tmp_path):
    data=file_kwargs(tmp_path);out=tmp_path/'refused.json'
    result=run(tmp_path,'compare-decoder-profiles',*arguments(data),'--output',str(out))
    assert result.returncode==2 and not out.exists()
    for field in ('expected_baseline_report_sha256','expected_candidate_report_sha256',
                  'expected_baseline_chart_sha256','expected_candidate_chart_sha256'):
        changed=dict(data);changed[field]='0'*64
        result=run(tmp_path,'compare-decoder-profiles',*arguments(changed),'--allow-synthetic','--output',str(out))
        assert result.returncode==2 and json.loads(result.stdout)['status']=='REFUSED'
        assert not out.exists()


def test_cli_keeps_strict_same_driver_and_exact_target_contract(tmp_path):
    out=tmp_path/'refused.json'
    for field in ('code','target'):
        candidate=synthetic_report(chart_sha=CANDIDATE_CHART_SHA)
        if field=='code':candidate['code']['sha256']='9'*64
        else:candidate['cases'][0]['requestedTile']=[2,3,2]
        data=file_kwargs(tmp_path,candidate=candidate)
        result=run(tmp_path,'compare-decoder-profiles',*arguments(data),'--allow-synthetic','--output',str(out))
        assert result.returncode==2 and not out.exists()


def test_comparison_no_clobber_or_symlink_follow(tmp_path):
    data=file_kwargs(tmp_path);existing=tmp_path/'keep.json';existing.write_bytes(b'KEEP')
    alias=tmp_path/'alias.json';alias.symlink_to(existing)
    for out in (existing,alias):
        result=run(tmp_path,'compare-decoder-profiles',*arguments(data),'--allow-synthetic','--output',str(out))
        assert result.returncode==2 and existing.read_bytes()==b'KEEP'

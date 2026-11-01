import importlib.util
import json
from pathlib import Path

import pytest


spec=importlib.util.spec_from_file_location('interval_analysis',Path(__file__).resolve().parents[1]/'tools/analyze_interval_signature.py')
analysis=importlib.util.module_from_spec(spec);spec.loader.exec_module(analysis)


def archive(root, *, long_gap=27060, wrong_label=False):
    (root/'derived/segment').mkdir(parents=True)
    links=[];detections=[]
    for cycle,base,gap,bit in [(1,1000000,24350,0),(2,1500000,long_gap,1)]:
        for index in range(4):
            pid=len(links)+1
            links.append({'event':'verified_raw_ping_link','host_utc_ns':pid+1,'ping_id':pid,
                'marker_device_us':base+index*gap,'capture_window':{'sample_count':2048,
                    'native_cycle':cycle,'interval_signature':{'symbol':0 if wrong_label else bit,'distinguishable':True}}})
            detections.append({'ping_id':pid,'presentation':{'quality':'LOCKED','bottom':{'depth_m':1.21}}})
    (root/'derived/transmit.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in links))
    (root/'derived/segment/detections.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in detections))
    result=root/'result.json';result.write_text(json.dumps({'run':{'state':'COMPLETE','verified':True,
        'started_utc_ns':1,'finished_utc_ns':20,'raw_ping_links':8}}))
    return result


def test_physical_marker_pattern_and_echo_stability_are_separate_from_acoustic_claim(tmp_path):
    result=analysis.analyze(tmp_path,archive(tmp_path))
    assert result['timing_pattern_observed'] and result['correct_cycles']==2
    assert result['physical_gaps_us_by_symbol']['0']['median']==24350
    assert result['physical_gaps_us_by_symbol']['1']['median']==27060
    assert not result['acoustic_code_confirmed'] and not result['multipath_rejection_qualified']
    assert result['raw_integrity_requires_separate_wire_archive_audit']


@pytest.mark.parametrize('gap,wrong_label',[(26500,False),(27060,True)])
def test_ambiguous_or_mislabeled_pattern_never_becomes_a_success(tmp_path,gap,wrong_label):
    result=analysis.analyze(tmp_path,archive(tmp_path,long_gap=gap,wrong_label=wrong_label))
    assert not result['timing_pattern_observed']


def test_analyzer_rejects_a_physical_gap_below_qualified_floor(tmp_path):
    with pytest.raises(AssertionError,match='floor'):
        analysis.analyze(tmp_path,archive(tmp_path,long_gap=24000))

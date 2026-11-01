"""Actual CLI with invented chart bytes; no private chart or runtime inputs."""
import hashlib
import json
import struct
import subprocess
import sys

from test_fill_duplicates import fixture
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.wire import AuthoredBlock, HEADER_SLOTS, assemble


def chart():
    features,indices,coordinates,render,additional=fixture()
    authored=[AuthoredBlock(name,disk,0,b'',body) for name,disk,body in (
        ('i',0x1101,indices),('c',0x1102,coordinates),
        ('r',0x1103,render),('a',0x1104,additional))]
    refs=tuple((8*i,None)for i in range(4))+tuple((32+8*i,name)for i,name in enumerate(('i','c','r','a')))
    authored.append(AuthoredBlock('root',0x6000,3,bytes(64),features,references=refs))
    return assemble(new_plain_vector_header(bytes(range(16))),authored,
                    {slot:('root'if slot==160 else None)for slot in HEADER_SLOTS})[0]


def run(tmp_path,*args):
    return subprocess.run([sys.executable,'-m','no_chart_converter.cli',*args],cwd=tmp_path,
                          text=True,capture_output=True)


def setup(tmp_path):
    raw=chart();source=tmp_path/'own-source.rx5';source.write_bytes(raw)
    return raw,source,['--source',str(source),'--expect-sha256',hashlib.sha256(raw).hexdigest()]


def test_actual_analysis_cli_has_source_scope_pins_and_hard_no_apply_contract(tmp_path):
    raw,source,args=setup(tmp_path);out=tmp_path/'analysis.json'
    result=run(tmp_path,'analyze-fill-duplicates',*args,'--tile','0','0','0','--output',str(out))
    assert result.returncode==0,result.stdout+result.stderr
    document=json.loads(out.read_bytes())
    assert document['status']=='ANALYSIS_ONLY_NO_CHART_CREATED'
    assert document['source']=={'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
    assert document['operativeApplyAllowed']is document['chartMaterialized']is False
    assert document['nativeQueryDisposition']=='UNPROVEN'
    assert document['originalCentroidPreservation']=='UNPROVEN_DUPLICATE_WEIGHTED_F32_MEAN'
    assert document['physicalA97TimingMeasured']is document['nativeDecoderExecuted']is False
    row,=document['rows'];assert row['tile']==[0,0,0]
    assert set(row['sourceStreams'])=={'indices','coordinates','render','additional'}
    assert row['analysis']['totals']['adjacentPointOccurrencesRemoved']==3
    assert row['analysis']['materializationApproved']is False
    assert row['analysis']['owners'][0]['parts'][0]['keptVertexOrdinals']==[0,2,4,5,7]
    assert source.read_bytes()==raw
    assert sorted(p.name for p in tmp_path.iterdir())==['analysis.json','own-source.rx5']


def test_wrong_source_absent_duplicate_or_out_of_bounds_scope_refuse_before_output(tmp_path):
    raw,source,args=setup(tmp_path);out=tmp_path/'refused.json'
    cases=[(args[:-1]+['0'*64],['--tile','0','0','0']),
           (args,['--tile','1','0','0']),
           (args,['--tile','0','0','0','--tile','0','0','0']),
           (args,['--tile','30','0','0'])]
    for source_args,tiles in cases:
        result=run(tmp_path,'analyze-fill-duplicates',*source_args,*tiles,'--output',str(out))
        assert result.returncode==2 and json.loads(result.stdout)['status']=='REFUSED'
        assert not out.exists()and source.read_bytes()==raw


def test_analysis_no_clobber_or_symlink_follow(tmp_path):
    raw,source,args=setup(tmp_path);existing=tmp_path/'existing.json';existing.write_bytes(b'KEEP')
    alias=tmp_path/'alias.json';alias.symlink_to(existing)
    for out in (existing,alias):
        result=run(tmp_path,'analyze-fill-duplicates',*args,'--tile','0','0','0','--output',str(out))
        assert result.returncode==2 and existing.read_bytes()==b'KEEP'
    assert source.read_bytes()==raw


def test_malformed_source_stream_type_refuses_without_report(tmp_path):
    raw,source,args=setup(tmp_path);bad=bytearray(raw)
    struct.pack_into('<H',bad,240,0x9999)
    source.write_bytes(bad);args[-1]=hashlib.sha256(bad).hexdigest();out=tmp_path/'bad.json'
    result=run(tmp_path,'analyze-fill-duplicates',*args,'--tile','0','0','0','--output',str(out))
    assert result.returncode==2 and 'PLAIN_UNCHAINED_FILL_DUPLICATE_SOURCE_STREAM'in result.stdout
    assert not out.exists()and source.read_bytes()==bad

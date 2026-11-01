"""Actual optional-policy CLI, using invented closed area parts only."""
import hashlib
import json

from test_fill_duplicates import fixture, RING
from test_fill_duplicates_cli import run
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.wire import AuthoredBlock, HEADER_SLOTS, assemble


def two_part_chart():
    shifted=[(x+100,y+100)for x,y in RING]
    feature,indices,coordinates,render,additional=fixture([RING,shifted])
    authored=[AuthoredBlock(name,disk,0,b'',body)for name,disk,body in (
        ('i',0x1101,indices),('c',0x1102,coordinates),('r',0x1103,render),('a',0x1104,additional))]
    refs=tuple((8*i,None)for i in range(4))+tuple((32+8*i,name)for i,name in enumerate(('i','c','r','a')))
    authored.append(AuthoredBlock('root',0x6000,3,bytes(64),feature,references=refs))
    return assemble(new_plain_vector_header(bytes(range(16))),authored,
                    {slot:('root'if slot==160 else None)for slot in HEADER_SLOTS})[0]


def test_optional_policy_protects_decoded_polygon0_and_keeps_query_boundary(tmp_path):
    raw=two_part_chart();source=tmp_path/'own.rx5';source.write_bytes(raw)
    args=['--source',str(source),'--expect-sha256',hashlib.sha256(raw).hexdigest(),
          '--tile','0','0','0']
    baseline=tmp_path/'all.json';protected=tmp_path/'protected.json'
    first=run(tmp_path,'analyze-fill-duplicates',*args,'--output',str(baseline))
    assert first.returncode==0,first.stdout+first.stderr
    second=run(tmp_path,'analyze-fill-duplicates',*args,'--anchor-policy','preserve_native_polygon0',
               '--output',str(protected))
    assert second.returncode==0,second.stdout+second.stderr
    previous=json.loads(baseline.read_bytes());actual=json.loads(protected.read_bytes())
    assert previous['anchorPolicy']=='analyze_all'
    assert previous['rows'][0]['analysis']['totals']['adjacentPointOccurrencesRemoved']==6
    assert actual['anchorPolicy']=='preserve_native_polygon0'
    analysis=actual['rows'][0]['analysis'];owner,=analysis['owners']
    first_part,later=owner['parts']
    assert owner['polygonMappingStatus']=='RESOLVED_CLOSED_ROOT1_PARTS'
    assert owner['polygon0PartOrdinals']==[0]
    assert first_part['decodedPolygonOrdinal']==0
    assert first_part['anchorClassification']=='PRESERVED_DECODED_POLYGON0'
    assert first_part['keptVertexOrdinals']==list(range(len(RING)))
    assert first_part['proposedTypedElementSHA256']==first_part['sourceTypedElementSHA256']
    assert later['decodedPolygonOrdinal']==1 and later['removedVertexOrdinals']==[1,3,6]
    assert analysis['totals']['adjacentPointOccurrencesRemoved']==3
    assert actual['operativeApplyAllowed']is actual['chartMaterialized']is False
    assert actual['nativeQueryDisposition']=='UNPROVEN'
    assert actual['aliasReviewScope']=='EACH_SELECTED_SOURCE_PAYLOAD_INDEPENDENTLY'
    assert actual['crossSourceAliasReviewComplete']is False
    assert analysis['nativeAnchorFallbackEquivalenceApproved']is False
    assert source.read_bytes()==raw
    assert sorted(p.name for p in tmp_path.iterdir())==['all.json','own.rx5','protected.json']


def test_unknown_anchor_policy_is_refused_without_report(tmp_path):
    raw=two_part_chart();source=tmp_path/'own.rx5';source.write_bytes(raw);output=tmp_path/'none.json'
    result=run(tmp_path,'analyze-fill-duplicates','--source',str(source),
               '--expect-sha256',hashlib.sha256(raw).hexdigest(),'--tile','0','0','0',
               '--anchor-policy','guessed-first-part','--output',str(output))
    assert result.returncode==2 and not output.exists()and source.read_bytes()==raw

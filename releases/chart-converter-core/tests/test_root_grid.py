"""Invented clipping cases for the declared unit Root-grid policy."""
from types import SimpleNamespace
import struct

import pytest
from shapely.geometry import MultiPolygon, Polygon, box

from no_chart_converter.country_runtime import root_grid, vertex_projection
from no_chart_converter.rx5.wire import FEATURE


def test_whole_snap_rounding_resolves_vertex_touch_created_by_independent_rounding():
    reference=Polygon([(0.1,.1),(4.1,.1),(4.1,4.1),(2.4,4.1),(2.4,1.4),(2.2,1.4),(2.2,4.1),(.1,4.1),(.1,.1)])
    naive=Polygon([(round(x),round(y))for x,y in reference.exterior.coords])
    assert reference.is_valid and not naive.is_valid
    result,event=root_grid.quantize_fill(reference,(100,200))
    assert result.is_valid and result.area>0
    assert all(v.is_integer()for point in result.exterior.coords for v in point)
    assert event['signedAddedAreaRootUnits2']==result.difference(reference).area
    assert event['signedLostAreaRootUnits2']==reference.difference(result).area
    assert not event['authoringPixelBudgetEnforced']
    assert not event['wholePixelModelIdentityClaim']


def test_hole_touch_and_subgrid_hole_changes_are_explicit():
    reference=Polygon(box(.1,.1,10.1,10.1).exterior.coords,[box(.2,.2,1.1,1.1).exterior.coords,
                                                               box(5.1,5.1,5.3,5.3).exterior.coords])
    result,event=root_grid.quantize_fill(reference,(0,0))
    assert result.is_valid and event['holesBefore']==2 and event['holesAfter']==0
    assert event['observedTopologyCountsChanged']
    assert len(event['collapsedInputHoles'])==1
    assert event['signedLostAreaRootUnits2']>=0 and event['signedAddedAreaRootUnits2']>0


def test_subgrid_component_collapse_is_logged_without_dropping_whole_owner():
    reference=MultiPolygon([box(.1,.1,4.1,4.1),box(8.1,.1,8.4,.4)])
    result,event=root_grid.quantize_fill(reference,(0,0))
    assert result.area>0 and event['inputComponents']==2 and event['outputComponents']==1
    assert len(event['collapsedInputComponents'])==1
    assert not event['clippedDisplayRepresentationOmitted']
    assert not event['originalSourceFeatureDeleted']


def test_positive_clipped_subgrid_rest_may_quantize_empty_with_exact_loss_receipt():
    reference=box(.1,.1,.4,.4)
    output,event=root_grid.quantize_fill(reference,(0,0),owner_binding={'uid':123})
    assert output.is_empty and output.is_valid
    assert not event['inputReferenceEmpty'] and event['postGridEmpty']
    assert event['positiveFloatClipCollapsedOnGrid'] and event['sourceUID']==123
    assert event['signedLostAreaRootUnits2']==reference.area
    assert event['signedAddedAreaRootUnits2']==0 and event['outputAreaRootUnits2']==0
    assert event['disposition']=='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED'
    assert event['clippedDisplayRepresentationOmitted'] and not event['nativeFillRecordExpected']
    assert not event['originalSourceFeatureDeleted']
    assert not event['sourceFeatureFilteredBeforeQuantization']


def test_invalid_source_and_out_of_root_domain_are_not_repaired():
    with pytest.raises(ValueError,match='VALID_FLOAT_FILL'):
        root_grid.quantize_fill(Polygon([(0,0),(3,3),(0,3),(3,0),(0,0)]),(0,0))
    with pytest.raises(ValueError,match='GLOBAL_ROOT_U32'):
        root_grid.quantize_fill(box(2,2,3,3),(root_grid.Q,root_grid.Q))


def test_productive_clipper_serializes_exact_grid_inverse_and_keeps_record_complement():
    origin=(100,200);cid='INVENTED';uid=9001
    mask=Polygon(box(.1,.1,10.1,10.1).exterior.coords,[box(.2,.2,1.1,1.1).exterior.coords])
    base=SimpleNamespace(origin=origin,level=14,masks={cid:mask},
        local=lambda points:[(x-origin[0],y-origin[1])for x,y in points],
        ownerAuthenticSemantics={'own':cid,'ownCoverage':mask,'higher':Polygon()})
    owner={'cellId':cid,'uid':uid,'sourceOwnerOrdinal':3}
    clipper=vertex_projection.wrap_clipper(base,epoch_last_level=14,owner_binding=owner)
    points=[(100,200),(120,200),(120,220),(100,220),(100,200)]
    co=struct.pack('<I',0x01000005)+b''.join(struct.pack('<II',*p)for p in points)
    ix=struct.pack('<II',1,8<<24)
    record=FEATURE.pack(uid,0,701,9,17,42,3,2,0,0)
    outco,outix,outrecord,receipt=vertex_projection.clip_owner(co,ix,record,cell_id=cid,clipper=clipper)
    assert outrecord==record
    final=vertex_projection.validate_final_owner(clipper,outco,outix,outrecord,cell_id=cid)
    assert final['passed'] and final['fillQuantizationPolicy']==root_grid.POLICY
    assert final['policy']=='own-root-u32-grid1-serialized-role-roundtrip/1'
    assert 'ownAuthoringPixelBudget'not in final
    assert not final['exactZeroOutsideAuthenticDomainClaim']
    assert not final['authoringPixelBudgetEnforced']
    assert clipper.events[0]['holesBefore']==1 and clipper.events[0]['holesAfter']==0
    assert receipt['recordComplementUnchanged']


def test_productive_empty_clip_has_strict_empty_serialization_and_explicit_disposition():
    origin=(100,200);cid='INVENTED-EMPTY';uid=9002
    mask=box(.1,.1,.4,.4)
    base=SimpleNamespace(origin=origin,level=11,masks={cid:mask},
        local=lambda points:[(x-origin[0],y-origin[1])for x,y in points],
        ownerAuthenticSemantics={'own':cid,'ownCoverage':mask,'higher':Polygon()})
    owner={'cellId':cid,'uid':uid,'sourceOwnerOrdinal':4}
    clipper=vertex_projection.wrap_clipper(base,epoch_last_level=11,owner_binding=owner)
    points=[(100,200),(120,200),(120,220),(100,220),(100,200)]
    co=struct.pack('<I',0x01000005)+b''.join(struct.pack('<II',*p)for p in points)
    ix=struct.pack('<II',1,8<<24);record=FEATURE.pack(uid,0,702,9,17,42,3,2,0,0)
    outco,outix,outrecord,_=vertex_projection.clip_owner(co,ix,record,cell_id=cid,clipper=clipper)
    assert outco==b'' and outix==struct.pack('<I',0) and outrecord is None
    final=vertex_projection.validate_final_owner(clipper,outco,outix,outrecord,cell_id=cid)
    assert final['passed']
    assert final['disposition']=='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED'
    assert final['positiveFloatClipCollapsedOnGrid'] and final['postGridEmpty']
    assert final['signedLostAreaRootUnits2']==clipper.reference.area>0
    assert final['clippedDisplayRepresentationOmitted']
    assert not final['originalSourceFeatureDeleted']
    assert final['ownerBinding']==owner
    assert final['pixelModelWholeSerializedOwnerReceipt'] is None

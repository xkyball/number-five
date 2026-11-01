"""Actual detail clipping keeps quantized emptiness in the outer receipt."""
from types import SimpleNamespace
import struct

from shapely.geometry import Polygon,box

from no_chart_converter.country_runtime import native_hooks,vertex_projection,writer_extension
from no_chart_converter.rx5.wire import FEATURE


def test_positive_clip_quantized_empty_survives_outer_and_compacted_receipts():
    cid='INVENTED-EMPTY';uid=7001
    mask=box(.1,.1,.4,.4)
    base=SimpleNamespace(origin=(0,0),level=11,masks={cid:mask},
        local=lambda points:list(points),
        ownerAuthenticSemantics={'own':cid,'ownCoverage':mask,'higher':Polygon()})
    service=SimpleNamespace(for_owner=lambda *args,**kwargs:(base,{'invented':True}))
    points=[(0,0),(10,0),(10,10),(0,10),(0,0)]
    coordinates=struct.pack('<I',0x01000005)+b''.join(struct.pack('<II',*p)for p in points)
    indices=struct.pack('<II',1,8<<24)
    record=FEATURE.pack(uid,0,701,9,17,42,3,2,0,0)
    owner={'uid':uid,'cellId':cid,'sourceOwnerOrdinal':3,'bbox':(0,0,10,10),
           'record':record,'coordinates':coordinates,'indices':indices,
           'paintPriority':4,'originalSequence':0}
    original=dict(owner)
    scene,receipts=writer_extension.compile_overlay_profile([owner],requested_level=11,
        authoring_last_level=11,coverage_service=service,quantizer=vertex_projection,
        visibility=lambda *args:{'eligible':True,'policy':'INVENTED_VISIBLE'})
    assert scene==[] and owner==original
    assert len(receipts)==1 and receipts[0]['uid']==uid
    expected='EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED'
    assert receipts[0]['disposition']==expected
    final=receipts[0]['finalOwnerMaskValidation']
    assert final['disposition']==expected
    assert final['positiveFloatClipCollapsedOnGrid'] is True
    assert final['postGridEmpty'] is True
    assert final['clippedDisplayRepresentationOmitted'] is True
    assert final['originalSourceFeatureDeleted'] is False
    assert final['sourceFeatureFilteredBeforeQuantization'] is False
    assert final['signedLostAreaRootUnits2']==mask.area
    compact=native_hooks.compact_receipts(receipts)
    assert compact[0]['disposition']==expected
    assert compact[0]['finalOwnerMaskValidation']==final
    assert len(compact[0]['completeReceiptSHA256'])==64

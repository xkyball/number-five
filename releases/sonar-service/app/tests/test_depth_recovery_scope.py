import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace
import pytest
sys.path.insert(0,str(Path(__file__).parents[1]/'tools'))
from depth_recovery_scope import capture,verify_depth_events


def records():
    e=[];markers=[];identity=100
    def event(kind,reason,tid,start,marker=0):
        e.append(dict(event=kind,reason=reason,id=tid,output_start_us=start,marker_us=marker,ping_id=0))
    for native in [1000000,1386400,1772800]:
        event(8,11,native,native,386400)
        if native==1772800:break
        for slot in range(5):
            stamp=native+174000+24300*slot;identity+=1
            event(9,1,2048,stamp,24300)
            for kind in [1,2,3]:event(kind,0,identity,stamp,stamp+2 if kind>1 else 0)
            markers.append(stamp+2)
    event(7,0,identity,stamp,stamp+2)
    return e,markers


def test_adaptive_scope_requires_real_markers_geometry_and_full_native_guard():
    e,m=records();r=verify_depth_events(e,m,24300)
    assert r['own_confirmed_markers']==10 and not r['echo_qualification']
    assert r['full_native_guards_verified']
    with pytest.raises(ValueError,match='raw STREAM'):verify_depth_events(e,[],24300)
    bad=[dict(row) for row in e]
    next(x for x in bad if x['event']==9)['id']=16384
    with pytest.raises(ValueError,match='window/spacing'):verify_depth_events(bad,m,24300)
    with pytest.raises(ValueError,match='spacing outside'):verify_depth_events(e,m,27000)


def test_recovery_requires_matching_physical_audit_before_opening_usb(tmp_path):
    import json
    proof=tmp_path/'proof.json';proof.write_text(json.dumps({'passed':False}))
    with pytest.raises(ValueError,match='matching 30s'):
        asyncio.run(capture(SimpleNamespace(wiring_verified=True,prior_audit=proof,
                                          output=tmp_path/'capture',spacing_us=24300)))
    assert not (tmp_path/'capture').exists()

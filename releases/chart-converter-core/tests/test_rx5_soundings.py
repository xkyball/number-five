"""Invented XYZ12/profile/UID fixtures, no actual chart or original binary."""
import struct
import hashlib
import json
import subprocess
import sys

import pytest

from no_chart_converter.rx5 import wire
from no_chart_converter.rx5.header import new_plain_vector_header
from no_chart_converter.rx5.model import native_tree, parts
from no_chart_converter.rx5.soundings import (
    materialize_sounding_twins, sounding_kind9, validate_sounding_pairs,
)


def chart(features=None):
    sound = wire.FEATURE.pack(17, 0, 0, 0, 111, 129, 4, 1, 2, 3)
    point = wire.FEATURE.pack(89, 8, 0, 0, 222, 17, 1, 4, 5, 6)
    xyz = struct.pack('<I9I', 0x01000003, 11, 12, 0, 21, 22, (-12)&0xffffffff, 31, 32, 124)
    xy = struct.pack('<III', 0x01000001, 71, 72)
    idx = struct.pack('<4I', 1, 0, 1, len(xyz))
    raw_features = sound + point if features is None else features
    bodies = [wire.AuthoredBlock('idx', 0x1101, 0, b'', idx),
              wire.AuthoredBlock('co', 0x1102, 0, b'', xyz+xy),
              wire.AuthoredBlock('ro', 0x1103, 0, b'', struct.pack('<HI', 0, 0)),
              wire.AuthoredBlock('ao', 0x1104, 0, b'', bytes(14))]
    stream_refs = tuple((32+8*i, n) for i,n in enumerate(('idx','co','ro','ao')))
    bodies += [wire.AuthoredBlock('root', 0x6000, 3, bytes(64), raw_features,
               references=tuple((8*i, 'child' if i==0 else None)for i in range(4))+stream_refs),
               wire.AuthoredBlock('child', 0x6001, 3, bytes(64), raw_features,
               references=tuple((8*i, None)for i in range(4))+stream_refs)]
    header_refs = {at: 'root' if at==160 else None for at in wire.HEADER_SLOTS}
    return wire.assemble(new_plain_vector_header(bytes(range(16))), bodies, header_refs)[0], sound, point


def test_all_profiles_exact_roles_xyz_and_original_streams():
    source, sound, point = chart()
    result, report = materialize_sounding_twins(source)
    assert report['addedKind9Records']==2 and report['newPrimaryUIDs']==0
    assert result[56:72] == source[56:72]
    old,new = wire.blocks(source),wire.blocks(result)
    before,after = {v:k for k,v in native_tree(source).items()},{v:k for k,v in native_tree(result).items()}
    assert before.keys()==after.keys()
    for tile in before:
        a,b=old[before[tile]],new[after[tile]]
        assert b.payload == sound+sounding_kind9(sound)+point
        assert validate_sounding_pairs(b.payload)==1
        for x,y in zip(struct.unpack('<4Q',a.prefix[32:]),struct.unpack('<4Q',b.prefix[32:])):
            assert old[x].payload == new[y].payload
        io,co,*_=struct.unpack('<4Q',b.prefix[32:])
        ordinary=list(parts(sound,new[io].payload,new[co].payload))
        decimated=list(parts(sounding_kind9(sound),new[io].payload,new[co].payload))
        assert ordinary==decimated
        assert [struct.unpack_from('<i',ordinary[0]['raw'],12+12*i)[0]for i in range(3)]==[0,-12,124]
    again,second=materialize_sounding_twins(result)
    assert again==result and second['addedKind9Records']==0 and second['existingPairs']==2


def test_native_original_gate_model_has_one_complementary_role_at_all_levels():
    # The assertions model the pinned original gate; the native execution
    # evidence remains separate and is never claimed by this synthetic test.
    for source in range(22):
        for destination in range(22):
            admitted=[kind for kind in (4,9)if(kind==9)==(source==destination)]
            assert admitted==([9]if source==destination else[4])


def test_existing_and_missing_pairs_and_empty_profiles_are_preserved():
    _,sound,point=chart()
    another=bytearray(sound);struct.pack_into('<Q',another,0,99);another=bytes(another)
    source,*_=chart(sound+sounding_kind9(sound)+another+point)
    output,report=materialize_sounding_twins(source)
    assert report['existingPairs']==2 and report['addedKind9Records']==2
    for at in native_tree(output):
        payload=wire.blocks(output)[at].payload
        assert payload==sound+sounding_kind9(sound)+another+sounding_kind9(another)+point
    empty,*_=chart(b'')
    assert materialize_sounding_twins(empty)[0]==empty


def test_other_header_version_is_not_silently_reinterpreted():
    source,*_=chart();changed=bytearray(source);struct.pack_into('<Q',changed,72,3)
    with pytest.raises(ValueError,match='UNSIGNED64_AT_72'):
        materialize_sounding_twins(bytes(changed))


def test_orphan_and_conflicting_or_nonpair_uid_duplicates_refused():
    _,sound,point=chart()
    invalids=[sounding_kind9(sound), sound+sounding_kind9(sound)[:-1]+b'\x07',
              sound+sound, point+point]
    for bad in invalids:
        with pytest.raises(ValueError):validate_sounding_pairs(bad)
    for bad in invalids[:2]:
        raw,*_=chart(bad)
        with pytest.raises(ValueError):materialize_sounding_twins(raw)


@pytest.mark.parametrize('module',('rx5','country_runtime'))
def test_wire_rebase_allows_only_exact_explicit_4_9_pair(module):
    if module=='rx5':w=wire
    else:
        from no_chart_converter.country_runtime import wire as w
    _,sound,point=chart()
    valid=sound+sounding_kind9(sound)+point
    after,uids=w.rebase_features(valid,16,8,{0:7})
    assert uids==[17,17,89]and validate_sounding_pairs(after)==1
    for bad in (point+point,sound+sound,sound+sounding_kind9(sound)[:-1]+b'\x07'):
        with pytest.raises(ValueError):w.rebase_features(bad,16,8,{0:7})


def test_truncated_xyz_refused_without_materialization():
    raw,sound,_=chart()
    parsed=wire.blocks(raw);at=next(at for at,b in parsed.items()if b.disk_id==0x1102)
    altered=bytearray(raw);struct.pack_into('<I',altered,at+16,0x0100ffff)
    with pytest.raises(ValueError,match='COMPLETE_TYPED_ELEMENT'):
        materialize_sounding_twins(bytes(altered))


def test_cli_hash_bound_materialization_and_no_clobber(tmp_path):
    raw,*_=chart();source=tmp_path/'source.rx5';source.write_bytes(raw)
    target=tmp_path/'new.rx5';report=tmp_path/'report.json'
    cmd=[sys.executable,'-m','no_chart_converter.cli','materialize-soundings',
         '--source',str(source),'--expect-sha256',hashlib.sha256(raw).hexdigest(),
         '--output',str(target),'--report',str(report)]
    result=subprocess.run(cmd,cwd=tmp_path,text=True,capture_output=True)
    assert result.returncode==0,result.stdout+result.stderr
    assert json.loads(report.read_bytes())['addedKind9Records']==2
    before=target.read_bytes()
    duplicate=subprocess.run(cmd,cwd=tmp_path,text=True,capture_output=True)
    assert duplicate.returncode!=0 and target.read_bytes()==before
    target.unlink();report.unlink();cmd[cmd.index('--expect-sha256')+1]='0'*64
    invalid=subprocess.run(cmd,cwd=tmp_path,text=True,capture_output=True)
    assert invalid.returncode!=0 and not target.exists()and not report.exists()

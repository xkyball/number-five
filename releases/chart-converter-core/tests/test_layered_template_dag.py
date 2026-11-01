"""Invented wire fixtures only; no Original binary or file reads."""
from dataclasses import replace
import hashlib
import struct

import pytest

from no_chart_converter.rx5 import header, model
from no_chart_converter.rx5 import layered_template_dag as d


def sha(b):
    return hashlib.sha256(b).hexdigest()


def fixture(vector_levels=(20,)):
    # One physical node per level, with all four outgoing offsets aliased.
    node_offsets={};pos=240
    for level in range(20,30):
        node_offsets[level]=pos
        pos += 112 if level in vector_levels or level == 29 else 48
    stream_data=[('indices',b'own-I'),('coordinates',b'own-C'),
        ('render',b'own-corrected-RO'),('render',b'own-canonical-RO'),
        ('additional',b'own-AO')]
    stream_offsets=[]
    for role,payload in stream_data:
        stream_offsets.append(pos);pos+=16+len(payload)
    profile_refs=(stream_offsets[0],stream_offsets[1],stream_offsets[2],stream_offsets[4])
    restore_refs=(stream_offsets[0],stream_offsets[1],stream_offsets[3],stream_offsets[4])
    fi_full=struct.pack('<Q5I4B',123,0,0,70,0,42,3,0,0,0)
    fi_profile=struct.pack('<Q5I4B',123,0,6,70,0,42,3,0,0,0)
    h=bytearray(header.new_plain_vector_header(b'OWN-DAG-FIXTURE!'))
    struct.pack_into('<Q',h,160,node_offsets[20])
    raw=bytearray(h);nodes=[]
    for level in range(20,30):
        restore=level==29
        role='canonical-restore' if restore else ('profile-vector' if level in vector_levels else 'router')
        flags=1 if role=='router' else 3
        child=0 if restore else node_offsets[level+1]
        body=struct.pack('<4Q',child,child,child,child)
        if flags==3:
            body+=struct.pack('<4Q',*(restore_refs if restore else profile_refs))
            body+=fi_full if restore else fi_profile
        node=struct.pack('<HHIQ',0x6000+level,flags,len(body),0)+body
        assert len(raw)==node_offsets[level]
        nodes.append(d.NodeSpec(len(raw),level,role,sha(node)));raw+=node
    streams=[]
    for at,(role,payload) in zip(stream_offsets,stream_data):
        assert len(raw)==at
        raw+=struct.pack('<HHIQ',d.DISK_IDS[role],0,len(payload),0)+payload
        streams.append(d.StreamSpec(at,role,d.PayloadPin(len(payload),sha(payload))))
    args=dict(source_header=bytes(h),expected_header_sha256=sha(h),
        source_file_sha256=sha(raw),file_bytes=len(raw),source_root=(20,577952,629436),
        root_offset=node_offsets[20],nodes=nodes,streams=streams,
        profile_fi=d.PayloadPin(len(fi_profile),sha(fi_profile)),
        canonical_restore_fi=d.PayloadPin(len(fi_full),sha(fi_full)),
        profile_stream_offsets=profile_refs,canonical_restore_stream_offsets=restore_refs,
        profile_receipt_sha256=sha(b'own-receipt'),allow_ancestor=True,
        enabled=True,policy=d.POLICY)
    return bytes(raw),args,node_offsets


def run(raw,args,**changes):
    return d.validate_layered_template_dag(lambda off,n:raw[off:off+n],**(args|changes))


def modify_node(raw,args,offsets,level,change):
    out=bytearray(raw);at=offsets[level]
    change(out,at)
    size=struct.unpack_from('<I',out,at+4)[0]
    nodes=[replace(n,sha256=sha(out[at:at+16+size])) if n.level==level else n for n in args['nodes']]
    return bytes(out),args|dict(nodes=nodes)


def test_shared_siblings_pass_without_logical_expansion():
    raw,args,_=fixture();r=run(raw,args)
    assert r['uniquePhysicalNodes']==10 and r['physicalEdges']==36
    assert r['logicalBoundaryOccurrences']==262144
    assert r['logicalOccurrencesByLevel']=={z:4**(z-20)for z in range(20,30)}
    assert len(r['physicalNodes'])==10 and not r['logicalNodesEnumerated']
    assert r['readBytes']<2048 and r['allCanonicalRestorePinsChecked']
    assert r['actualNativeCallbackSourceIDsRequireSeparateAdmission']
    assert not r['nativePhysicalCompatibilityClaim']
    assert not r['defaultLegacyTreeValidatorChanged']


def test_existing_vector_sources_can_coexist_with_shared_routes():
    raw,args,_=fixture((20,21,23));r=run(raw,args)
    assert [n['level']for n in r['physicalNodes']if n['role']=='profile-vector']==[20,21,23]
    assert r['logicalBoundaryOccurrences']==262144


@pytest.mark.parametrize('changes',[dict(enabled=False),dict(policy=None),dict(policy='strictTree')])
def test_default_off_refuses_before_reader(changes):
    _,args,_=fixture();calls=[]
    with pytest.raises(ValueError,match='DEFAULT_OFF'):
        d.validate_layered_template_dag(lambda *a:calls.append(a),**(args|changes))
    assert calls==[]


def test_same_offset_different_level_refused():
    raw,args,_=fixture()
    bad=args['nodes']+[replace(args['nodes'][1],level=22)]
    with pytest.raises(ValueError,match='UNIQUE_PHYSICAL_NODE_OFFSET'):
        run(raw,args,nodes=bad)


@pytest.mark.parametrize('offset',[240.0,[240],True,239,1 << 64])
def test_root_offset_requires_in_file_integer_before_reader(offset):
    _,args,_=fixture();calls=[]
    with pytest.raises(ValueError,match='ROOT_OFFSET_U64'):
        d.validate_layered_template_dag(lambda *a:calls.append(a),**(args|dict(root_offset=offset)))
    assert calls==[]


@pytest.mark.parametrize('to_level',[20,21,28])
def test_cycles_and_backedges_refused(to_level):
    raw,args,offsets=fixture()
    raw,args=modify_node(raw,args,offsets,28,lambda b,at:struct.pack_into('<Q',b,at+16,offsets[to_level]))
    with pytest.raises(ValueError,match='CHILD_LEVEL_PLUS_ONE_NO_CYCLES'):
        run(raw,args)


def test_missing_restore_spec_refused():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='CHILD_REFERENCE_SPEC_MISSING'):
        run(raw,args,nodes=args['nodes'][:-1])


def test_every_child_slot_must_reach_boundary():
    raw,args,offsets=fixture()
    raw,args=modify_node(raw,args,offsets,24,lambda b,at:struct.pack_into('<Q',b,at+16+8,0))
    with pytest.raises(ValueError,match='FOUR_CHILDREN_TO_PHYSICAL_BOUNDARY'):
        run(raw,args)


def test_restore_is_leaf_and_cannot_leak_profile_fallback():
    raw,args,offsets=fixture()
    raw,args=modify_node(raw,args,offsets,29,lambda b,at:struct.pack_into('<Q',b,at+16,offsets[20]))
    with pytest.raises(ValueError,match='RESTORE_CHILDREN_ZERO'):
        run(raw,args)


@pytest.mark.parametrize('level,flags',[(20,1),(22,3),(29,1)])
def test_actual_parent_router_and_restore_flags_match_roles(level,flags):
    raw,args,offsets=fixture()
    raw,args=modify_node(raw,args,offsets,level,lambda b,at:struct.pack_into('<H',b,at+2,flags))
    with pytest.raises(ValueError,match='NODE_HEADER_ROLE_LEVEL_FLAGS_NEXT'):
        run(raw,args)


def test_router_requires_explicit_true_ancestor():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='ROUTER_REQUIRES_EXPLICIT_TRUE'):
        run(raw,args,allow_ancestor=False)
    with pytest.raises(ValueError,match='EXPLICIT_ALLOW_ANCESTOR_BOOL'):
        run(raw,args,allow_ancestor=1)


def test_all_vector_model_does_not_need_router_fallback():
    raw,args,_=fixture(tuple(range(20,29)))
    assert run(raw,args,allow_ancestor=False)['explicitAllowAncestor'] is False


def test_out_of_bounds_header_and_u32_huge_payload_refused():
    raw,args,_=fixture()
    bad=args['nodes'][:-1]+[replace(args['nodes'][-1],offset=len(raw)-8)]
    with pytest.raises(ValueError,match='BLOCK_EXTENT'):
        run(raw,args,nodes=bad)
    out=bytearray(raw);struct.pack_into('<I',out,args['root_offset']+4,0xffffffff)
    with pytest.raises(ValueError,match='U32_NODE_PAYLOAD_LAYOUT'):
        run(bytes(out),args)


def test_exact_node_byte_pins_required():
    raw,args,offsets=fixture();out=bytearray(raw);out[offsets[20]+100]^=1
    with pytest.raises(ValueError,match='NODE_BYTES_PIN'):
        run(bytes(out),args)


def test_restore_fi_and_four_stream_pins_required():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='CANONICAL_RESTORE_FI_PIN'):
        run(raw,args,canonical_restore_fi=d.PayloadPin(32,sha(b'wrong')))
    with pytest.raises(ValueError,match='CANONICAL_RESTORE_STREAM_POINTERS'):
        run(raw,args,canonical_restore_stream_offsets=args['profile_stream_offsets'])
    s=args['streams'][0]
    bad=[replace(s,payload=replace(s.payload,sha256=sha(b'wrong'))),*args['streams'][1:]]
    with pytest.raises(ValueError,match='STREAM_PAYLOAD_PIN'):
        run(raw,args,streams=bad)


def test_next_chains_and_stream_headers_are_not_silently_accepted():
    raw,args,_=fixture();out=bytearray(raw)
    struct.pack_into('<Q',out,args['streams'][0].offset+8,args['streams'][0].offset)
    with pytest.raises(ValueError,match='STREAM_ROLE_HEADER_U32_SIZE_NEXT'):
        run(bytes(out),args)


def test_overlaps_reserved_inventory_refused():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='OVERLAPPING_DECLARED_PHYSICAL_EXTENTS'):
        run(raw,args,reserved_ranges=[(args['root_offset'],args['root_offset']+1)])


def test_descriptor_and_read_bounds():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='PHYSICAL_NODE_COUNT'):
        run(raw,args,nodes=[args['nodes'][0]]*257)
    with pytest.raises(ValueError,match='UNIQUE_PHYSICAL_STREAM_OFFSET'):
        run(raw,args,streams=args['streams']+[args['streams'][0]])
    with pytest.raises(ValueError,match='STREAM_SPEC'):
        run(raw,args,streams=[replace(args['streams'][0],payload=d.PayloadPin(1<<32,sha(b'x'))),*args['streams'][1:]])
    with pytest.raises(ValueError,match='EXACT_IMMUTABLE_READER_RETURN'):
        d.validate_layered_template_dag(lambda off,n:raw[off:off+n-1],**args)


def test_header_and_profile_are_bound():
    raw,args,_=fixture()
    with pytest.raises(ValueError,match='HEADER_PIN'):
        run(raw,args,expected_header_sha256=sha(b'wrong'))
    h=bytearray(args['source_header']);struct.pack_into('<Q',h,104,512)
    with pytest.raises(ValueError,match='UNSIGNED64_AT_104'):
        run(raw,args,source_header=bytes(h),expected_header_sha256=sha(h))
    with pytest.raises(ValueError,match='EXACT_SOURCE20_ROOT'):
        run(raw,args,source_root=(21,1,1))


def test_child_coordinates_are_parent_derived():
    parent=(20,577952,629436)
    assert [d.derive_child_tile(parent,s)for s in range(4)]==[
        (21,1155904,1258872),(21,1155905,1258872),
        (21,1155904,1258873),(21,1155905,1258873)]
    with pytest.raises(ValueError,match='SLOT_BOUNDS'):
        d.derive_child_tile(parent,4)


def test_legacy_validator_still_refuses_aliases():
    # Legacy native_tree starts atglobal0, not atthis validator's scoped20root.
    h=bytearray(header.new_plain_vector_header(b'OWN-DAG-FIXTURE!'))
    struct.pack_into('<Q',h,160,240)
    root=struct.pack('<HHIQ4Q',0x6000,1,32,0,288,288,288,288)
    body=bytes(64)+struct.pack('<Q5I4B',123,0,0,70,0,42,3,0,0,0)
    leaf=struct.pack('<HHIQ',0x6001,3,len(body),0)+body
    raw=bytes(h)+root+leaf
    with pytest.raises(ValueError,match='ACYCLIC_COMPLETE_NATIVE_TREE'):
        model.native_tree(raw)

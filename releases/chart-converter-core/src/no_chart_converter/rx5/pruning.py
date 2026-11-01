"""Pure chart-bound visual-leaf pruning; standard library only, no file IO.

Precondition: independent certificates have already approved same-window fill,
all contributing ink/source gates, and nautical/query semantics for the complete
child destination domain. This module verifies their explicit chart binding and
the wire mutation, not their external visual mathematics. A candidate pool is
not an approval. There is no parent/child payload merge or physical block removal.
"""
from dataclasses import dataclass
import hashlib
import re
import struct
from typing import Sequence

_SHA=re.compile(r'[0-9a-f]{64}')
_HEADER_SIZE=240
_NODE_HEADER=struct.Struct('<HHIQ')


@dataclass(frozen=True)
class PruneDecision:
    source_sha256: str
    tile: tuple[int,int,int]
    child_offset: int
    parent_offset: int
    parent_slot: int
    expected_child_ref_hex: str
    fallback_offset: int
    fallback_tile: tuple[int,int,int]
    visual_certificate_sha256: str
    admission_certificate_sha256: str
    query_certificate_sha256: str
    query_disposition: str
    source_gate_disposition: str
    approved: bool


def _need(condition,reason):
    if not condition:raise ValueError(reason)


def _node(raw,at,level):
    _need(isinstance(at,int) and _HEADER_SIZE<=at<=len(raw)-16,'NODE_HEADER_IN_RANGE')
    disk_id,flags,size,_next=_NODE_HEADER.unpack_from(raw,at)
    _need(flags in(1,3) and disk_id==0x6000+level,'ORIGINAL_NATIVE_NODE_ID_AND_ROLE')
    prefix_size=32 if flags==1 else 64
    _need(size>=prefix_size and at+16+size<=len(raw),'COMPLETE_NATIVE_NODE')
    if flags==3:_need((size-prefix_size)%32==0,'COMPLETE_FEATURE32_BODY')
    return flags,struct.unpack_from('<4Q',raw,at+16)


def native_tree(raw):
    """Read only the reachable native tree; unknown resource pointers stay opaque."""
    _need(isinstance(raw,bytes) and len(raw)>=_HEADER_SIZE,'WHOLE_CHART_HEADER')
    root=struct.unpack_from('<Q',raw,160)[0];todo=[(root,(0,0,0))];found={}
    while todo:
        at,tile=todo.pop();level,x,y=tile
        _need(at not in found,'NO_NODE_ALIAS_OR_CYCLE_IN_APPROVED_TREE')
        flags,children=_node(raw,at,level);found[at]=tile
        for slot,child in enumerate(children):
            if child:todo.append((child,(level+1,2*x+(slot&1),2*y+(slot>>1))))
    return found


def select_vector(raw,tile):
    level,x,y=tile
    _need(isinstance(level,int) and level>=0 and isinstance(x,int) and isinstance(y,int)
          and 0<=x<1<<level and 0<=y<1<<level,'VALID_DESTINATION_TILE')
    at=struct.unpack_from('<Q',raw,160)[0];actual=(0,0,0);best=None
    for depth in range(level+1):
        flags,children=_node(raw,at,depth)
        if flags==3:best=(at,*actual)
        if depth==level:break
        shift=level-depth-1;slot=((x>>shift)&1)|(((y>>shift)&1)<<1)
        if not children[slot]:break
        at=children[slot];actual=(depth+1,2*actual[1]+(slot&1),2*actual[2]+(slot>>1))
    _need(best is not None,'PROVED_VECTOR_ANCESTOR_MUST_EXIST')
    return best


def apply_verified_prunes(raw:bytes,approved:Sequence[PruneDecision],*,uuid_bytes:bytes|None=None)->bytes:
    """Return the exact original image except approved leaf slots and optional UUID.

    All physical child payloads, coordinate arenas, programmes, queries and
    unknown resources remain byte-for-byte present at their original addresses.
    Logical fallback changes only for each approved child subtree (a true leaf
    has no authored descendants); its actual nearest ancestor is verified.
    """
    source_sha=hashlib.sha256(raw).hexdigest();before=native_tree(raw)
    if not approved:
        _need(uuid_bytes is None or uuid_bytes==raw[56:72],'NO_UUID_ONLY_FAKE_PRUNE')
        return raw
    output=bytearray(raw);positions={};removed=set()
    for decision in approved:
        _need(isinstance(decision,PruneDecision) and decision.approved is True,'EXPLICIT_TYPED_APPROVAL_REQUIRED')
        _need(decision.source_sha256==source_sha,'DECISION_BOUND_TO_EXACT_CHART')
        for digest in(decision.source_sha256,decision.visual_certificate_sha256,
                      decision.admission_certificate_sha256,decision.query_certificate_sha256):
            _need(isinstance(digest,str) and _SHA.fullmatch(digest) is not None,'COMPLETE_EVIDENCE_SHA256')
        _need(decision.query_disposition in('IDENTICAL_WITHIN_PROVED_CHILD_DOMAIN','DIFFERENT_EXPLICITLY_RECORDED_IN_ACCEPTED_REPORT'),
              'EXPLICIT_NAUTICAL_AND_QUERY_DISPOSITION')
        _need(decision.source_gate_disposition in('OBSERVABLY_EQUIVALENT','NO_SOURCE_DEPENDENT_INK_WITHIN_PROVED_DOMAIN'),
              'EXPLICIT_SOURCE_GATE_DISPOSITION')
        tile=tuple(decision.tile);child=decision.child_offset;parent=decision.parent_offset;slot=decision.parent_slot
        _need(before.get(child)==tile and child not in removed,'EXACT_UNIQUE_CHILD_TILE')
        flags,children=_node(raw,child,tile[0]);_need(flags==3 and children==(0,0,0,0),'ONLY_FULL_VECTOR_LEAFS_NO_DESCENDANTS')
        _need(parent in before and slot in range(4),'ORIGINAL_STRUCTURAL_PARENT_AND_SLOT')
        parent_tile=before[parent];_pf,pc=_node(raw,parent,parent_tile[0])
        _need(tile==(parent_tile[0]+1,2*parent_tile[1]+(slot&1),2*parent_tile[2]+(slot>>1)),
              'EXACT_QUADRANT_RELATIONSHIP')
        position=parent+16+8*slot;old=raw[position:position+8]
        _need(old.hex()==decision.expected_child_ref_hex and pc[slot]==child,'EXACT_APPROVED_EIGHT_BYTE_CHILD_REFERENCE')
        _need(position not in positions,'NO_OVERLAPPING_APPROVED_POINTER_EDITS')
        positions[position]=old;removed.add(child);struct.pack_into('<Q',output,position,0)
    if uuid_bytes is not None:
        _need(isinstance(uuid_bytes,bytes) and len(uuid_bytes)==16,'EXACT_UUID16_BYTES');output[56:72]=uuid_bytes
    result=bytes(output);after=native_tree(result)
    _need(set(after)==set(before)-removed and all(after[at]==before[at]for at in after),'ALL_OTHER_NATIVE_ROUTING_AND_SOURCE_IDS_EXACT')
    for decision in approved:
        actual=select_vector(result,tuple(decision.tile))
        _need(actual==(decision.fallback_offset,*tuple(decision.fallback_tile)),'ACTUAL_APPROVED_NEAREST_ANCESTOR_FALLBACK')
    inverse=bytearray(result);inverse[56:72]=raw[56:72]
    for position,old in positions.items():inverse[position:position+8]=old
    _need(bytes(inverse)==raw,'WHOLE_IMAGE_INVERSE_ONLY_UUID_AND_APPROVED_SLOTS')
    return result

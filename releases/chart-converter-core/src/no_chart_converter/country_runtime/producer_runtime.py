from .inputs import read

"""Narrow detail/LOD hooks used by the pinned existing country producer."""

from pathlib import Path

import hashlib, json, math

VISIBILITY_POLICY='own-s57-source133-nonpoint-denominator-with-genuine-final-displaybase-guard/1'

SOURCE_REGISTRY=None

SOURCE_REGISTRY_PIN=None

def configure_source_registry(pin):
    global SOURCE_REGISTRY,SOURCE_REGISTRY_PIN
    raw=read(pin)
    if len(raw)!=pin['bytes']or hashlib.sha256(raw).hexdigest()!=pin['sha256']:
        raise ValueError('ACTUAL_SOURCE_FINAL_STATE_REGISTRY_PIN')
    rows=json.loads(raw);SOURCE_REGISTRY={r['keySHA256']:r for r in rows};SOURCE_REGISTRY_PIN=pin
    if len(SOURCE_REGISTRY)!=len(rows):raise ValueError('UNIQUE_ACTUAL_SOURCE_FINAL_STATE_KEYS')

def visibility(owner,level,denominator):
    row=owner['sourceBinding'];resolution=row['resolution'];raw=row['sourceSCAMIN']
    selected=resolution['selected']
    if SOURCE_REGISTRY is None:raise ValueError('BOUND_ACTUAL_SOURCE_FINAL_STATE_REQUIRED')
    actual=SOURCE_REGISTRY[selected['effectiveSourceKeySHA256']]
    if not(actual['ownEffectiveStyleID']==selected['effectiveStyleID'] and actual['classAcronym']==row['className']
        and actual['sourceTable']==selected['sourceTable'] and actual['sourceRCID']==selected['sourceRCID']
        and actual['sourcePrimitive']==selected['sourcePrimitive'] and actual['actualAfterPriority']==resolution['displayPriority']):
        raise ValueError('SAME_SOURCE_STYLE_OWNER_FINAL_STATE_JOIN')
    final=dict(category=actual['actualAfterCategory'],priority=actual['actualAfterPriority'])
    receipt=dict(policy=VISIBILITY_POLICY,requestedLevel=level,sourceSCAMIN=raw,
        originalAdditionalFrameSHA256=row['additionalFrameSHA256'],
        sourceAttributeCode=133,nativeWord20Or216UnitClaim=False,
        actualFinalState=final,effectiveSourceKeySHA256=selected['effectiveSourceKeySHA256'],
        actualSourceFinalStateRegistry=SOURCE_REGISTRY_PIN,priorityOneIsNotDisplayBase=True,
        separatePrioGroup1ExceptionApplied=False)
    if final['category']==68:
        return dict(receipt,eligible=True,reason='GENUINE_SOURCE_ACTUAL_FINAL_DISPLAYBASE_KEEP')
    if not raw:return dict(receipt,eligible=True,reason='SOURCE_SCAMIN_MISSING_KEEP')
    if len(raw)!=1 or not isinstance(raw[0],str)or not raw[0].isascii()or not raw[0].isdigit():
        return dict(receipt,eligible=True,reason='SOURCE_SCAMIN_AMBIGUOUS_OR_INVALID_KEEP')
    value=int(raw[0])
    if value==0:return dict(receipt,eligible=True,reason='SOURCE_SCAMIN_ZERO_UNSPECIFIED_KEEP')
    if not 0<value<=0xffffffff:return dict(receipt,eligible=True,reason='SOURCE_SCAMIN_INVALID_KEEP')
    d=denominator(level)
    return dict(receipt,eligible=d<=value,reason='OWN_DISPLAY_DENOMINATOR_AT_OR_BELOW_SOURCE_SCAMIN'if d<=value else'OWN_DISPLAY_DENOMINATOR_GREATER_THAN_SOURCE_SCAMIN',
        ownDenominatorExact=[d.numerator,d.denominator])

def visibility_epochs(owners,denominator):
    last={};states={};changed={}
    for level in range(22):
        current={o['uid']:visibility(o,level,denominator)['eligible']for o in owners}
        changed[level]=[o for o in owners if current[o['uid']]!=last.get(o['uid'],False)]
        states[level]=current;last=current
    return states,changed

def add_detail_targets(target_tiles,changed,epochs,Q):
    counts={}
    for level in epochs:
        if level==0:continue
        tiles=target_tiles.setdefault(level,set());before=len(tiles);size=1<<level;pad=(Q+256*size-1)//(256*size)
        for owner in changed[level]:
            x0,y0,x1,y1=owner['bbox']
            lo_x=max(0,(x0-pad)*size//Q);hi_x=min(size-1,(x1+pad)*size//Q)
            lo_y=max(0,(y0-pad)*size//Q);hi_y=min(size-1,(y1+pad)*size//Q)
            for x in range(lo_x,hi_x+1):
                for y in range(lo_y,hi_y+1):tiles.add((level,x,y))
            if len(tiles)>500000:raise ValueError('OWN_SPARSE_DETAIL_TARGET_BUDGET_REVIEW_REQUIRED')
        counts[level]=len(tiles)-before
    return counts

def region_index(scene,Q):
    from collections import defaultdict
    result=defaultdict(list);pad=(Q+65535)//65536
    for owner in scene:
        x0,y0,x1,y1=owner['bbox']
        for x in range(max(0,(x0-pad)*256//Q),min(255,(x1+pad)*256//Q)+1):
            for y in range(max(0,(y0-pad)*256//Q),min(255,(y1+pad)*256//Q)+1):result[(x,y)].append(owner)
    return result

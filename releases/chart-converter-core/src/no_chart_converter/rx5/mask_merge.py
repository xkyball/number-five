"""Bounded exact convex MASK8 coalescing; no IO or native imports.

Every boundary vertex survives. Only opposite interior edges are cancelled.
The projected certificate covers all selectable descendant levels through 21.
"""
import re
import math
import struct
from functools import lru_cache

ROOT = 2147483649
MAX_BOUNDARY = 16
MAX_LEVEL = 21
MIN_SOURCE_LEVEL = 13
TOKEN = re.compile(rb'(?:AC\([A-Z0-9_]+\)|AP\([A-Z0-9_]+\)|LS\((?:SOLD|DASH|DOTT),[1-9][0-9]*,[A-Z0-9_]+\))')


def area(points):
    return sum(a[0]*b[1]-a[1]*b[0] for a,b in zip(points,points[1:]+points[:1]))


def convex(points):
    if len(set(points)) != len(points) or not area(points):
        return False
    sign = 1 if area(points)>0 else -1
    for a,b,c in zip(points,points[1:]+points[:1],points[2:]+points[:2]):
        if sign*((b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])) < 0:
            return False
    def orient(a,b,c):
        return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    def intersects(a,b,c,d):
        if max(a[0],b[0])<min(c[0],d[0]) or max(c[0],d[0])<min(a[0],b[0]) or max(a[1],b[1])<min(c[1],d[1]) or max(c[1],d[1])<min(a[1],b[1]):return False
        return orient(a,b,c)*orient(a,b,d)<=0 and orient(c,d,a)*orient(c,d,b)<=0
    edges=list(zip(points,points[1:]+points[:1]))
    for i,(a,b) in enumerate(edges):
        for j in range(i+1,len(edges)):
            if j==i+1 or (i==0 and j==len(edges)-1):continue
            if intersects(a,b,*edges[j]):return False
    return True


def eligible(kind, programmes):
    if kind != 3 or not programmes:
        return False
    for value in programmes.values():
        tokens=value.split(b';')
        if tokens and tokens[-1] == b'': tokens.pop()
        if not tokens or any(TOKEN.fullmatch(t) is None for t in tokens):
            return False
    return True


def projected(points, level):
    out=[]
    for point in points:
        q=[]
        for coordinate in point:
            n=coordinate*(256<<level)
            q.append((2*n+ROOT)//(2*ROOT))
        out.append(tuple(q))
    return out


def f32(value):
    return struct.unpack('<f',struct.pack('<f',value))[0]


def qt_round(value):
    """Actual original Qt4 QPolygonF.toPolygon F32 branches, not Python round."""
    if value>=0:
        return math.trunc(f32(value+0.5))
    i=math.trunc(f32(value-1.0))
    j=math.trunc(f32(f32(value-f32(i))+0.5))
    return i+j


@lru_cache(maxsize=400000)
def axis_projection_proof(coordinate,level,source_level,source_axis):
    """Exhaust every actual destination translation on this scalar axis.

    There are at most 256 choices under our explicit Source13..15 / Dest<=21
    compiler budget. Independent x/y coverage proves every 2D combination.
    """
    first=source_axis<<(level-source_level)
    end=(source_axis+1)<<(level-source_level)
    rational=(2*coordinate*(256<<level)+ROOT)//(2*ROOT)
    root_double=coordinate/ROOT
    scaled=(2.0**level)*root_double
    for dest_axis in range(first,end):
        value=f32((scaled-float(dest_axis))*256.0)
        if qt_round(value)!=rational-256*dest_axis:
            return False
    return True


def projection_certificate(boundary, triangles, source_tile):
    source_level,sx,sy=source_tile
    sign=1 if area(boundary)>0 else -1
    levels=[]
    for level in range(source_level,MAX_LEVEL+1):
        b=projected(boundary,level)
        ts=[projected(t,level) for t in triangles]
        for x,y in set(p for t in triangles for p in t):
            if not axis_projection_proof(x,level,source_level,sx) or not axis_projection_proof(y,level,source_level,sy):
                return None
        # Strict, same-orientation triangle interiors plus a convex simple
        # boundary form an embedded triangulated disk in the integer plane.
        if not convex(b) or sign*area(b)<=0 or any(sign*area(t)<=0 for t in ts):
            return None
        if sum(area(t) for t in ts) != area(b):
            return None
        levels.append(dict(level=level,signedDoubleArea=area(b),triangles=len(ts)))
    return levels


def merge_boundary(a,b):
    edges={}
    cancelled=0
    for points in (a,b):
        for edge in zip(points,points[1:]+points[:1]):
            reverse=(edge[1],edge[0])
            if reverse in edges:
                del edges[reverse];cancelled+=1
            elif edge in edges:
                return None
            else:edges[edge]=True
    if cancelled != 1 or not 3<=len(edges)<=MAX_BOUNDARY:
        return None
    forward={}
    for start,end in edges:
        if start in forward:return None
        forward[start]=end
    start=a[0] if a[0] in forward else min(forward)
    ring=[start];current=start
    for _ in range(len(edges)):
        if current not in forward:return None
        current=forward[current]
        if current==start:break
        ring.append(current)
    if current!=start or len(ring)!=len(edges) or not convex(ring):return None
    return ring


def merge_run(parts, source_tile):
    """parts=[{ordinal, points: closed original U32 triangle}], same usage8.

    Return ordered groups. A group never crosses the supplied run boundaries.
    Every successful merge has a complete integer-projection certificate.
    """
    active={i:dict(ordinals=[p['ordinal']],boundary=list(map(tuple,p['points'][:-1])),
                   triangles=[list(map(tuple,p['points'][:-1]))],levels=None)
            for i,p in enumerate(parts)}
    while True:
        edges={};candidates=[]
        for key,g in sorted(active.items()):
            b=g['boundary']
            for edge in zip(b,b[1:]+b[:1]):
                rev=(edge[1],edge[0])
                if rev in edges:candidates.append((edges[rev],key))
                edges[edge]=key
        changed=False
        for i,j in candidates:
            if i not in active or j not in active:continue
            a,b=active[i],active[j]
            if area(a['boundary'])*area(b['boundary'])<=0:continue
            ring=merge_boundary(a['boundary'],b['boundary'])
            if ring is None:continue
            triangles=a['triangles']+b['triangles']
            levels=projection_certificate(ring,triangles,source_tile)
            if levels is None:continue
            active[i]=dict(ordinals=sorted(a['ordinals']+b['ordinals']),boundary=ring,triangles=triangles,levels=levels)
            del active[j];changed=True
        if not changed:break
    return sorted(active.values(),key=lambda g:g['ordinals'][0])


def plan(kind,programmes,parts,source_tile):
    if not eligible(kind,programmes) or not MIN_SOURCE_LEVEL<=source_tile[0]<=15:return []
    runs=[];current=[];lastsign=0
    for p in parts:
        points=p['points']
        valid=(p['usage']==8 and p['rootFlag']==1 and len(points)==4 and points[0]==points[-1]
               and len(set(map(tuple,points[:-1])))==3 and area(list(map(tuple,points[:-1])))!=0)
        sign=(1 if area(list(map(tuple,points[:-1])))>0 else -1) if valid else 0
        if not valid or sign!=lastsign:
            if current:runs.append(current)
            current=[]
        if valid:current.append(dict(ordinal=p['partOrdinal'],points=points))
        lastsign=sign
    if current:runs.append(current)
    return [g for run in runs if len(run)>1 for g in merge_run(run,source_tile) if len(g['ordinals'])>1]

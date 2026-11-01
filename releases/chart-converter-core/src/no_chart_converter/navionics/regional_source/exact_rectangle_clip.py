"""Exact rational polygon intersection with an axis-aligned rectangle.

Input finite binary64 vertices are promoted exactly. Boundary construction
uses source-edge intervals and exact winding tests, without a tolerance.
The caller independently checks emitted U16 topology and geometric error.
"""
from collections import defaultdict,Counter
from fractions import Fraction as F
from functools import cmp_to_key


def need(ok,why):
    if not ok:raise ValueError(why)


def point(p):return tuple(v if type(v)is F else F.from_float(float(v))for v in p)
def cross(a,b):return a[0]*b[1]-a[1]*b[0]
def sub(a,b):return(a[0]-b[0],a[1]-b[1])
def area(ring):return sum(cross(a,b)for a,b in zip(ring,ring[1:]))/2


def segment_interval(a,b,bounds):
    a,b=point(a),point(b);lo,hi=F(0),F(1)
    for axis in range(2):
        d=b[axis]-a[axis]
        if not d:
            if not bounds[axis]<=a[axis]<=bounds[axis+2]:return None
            continue
        x=(bounds[axis]-a[axis])/d;y=(bounds[axis+2]-a[axis])/d
        lo,hi=max(lo,min(x,y)),min(hi,max(x,y))
        if lo>hi:return None
    return(lo,hi,tuple(a[i]+lo*(b[i]-a[i])for i in range(2)),
                  tuple(a[i]+hi*(b[i]-a[i])for i in range(2)))


def on_segment(p,a,b):
    return cross(sub(p,a),sub(b,a))==0 and all(min(a[i],b[i])<=p[i]<=max(a[i],b[i])for i in range(2))


def winding_classify(p,rings):
    winding=0
    for ring in rings:
        for a,b in zip(ring,ring[1:]):
            if on_segment(p,a,b):return 0
            orientation=cross(sub(b,a),sub(p,a))
            if a[1]<=p[1]<b[1] and orientation>0:winding+=1
            if b[1]<=p[1]<a[1] and orientation<0:winding-=1
    return 1 if winding else -1


def side_of(a,b,bounds):
    x0,y0,x1,y1=bounds
    if a[1]==b[1]==y0:return 0
    if a[0]==b[0]==x1:return 1
    if a[1]==b[1]==y1:return 2
    if a[0]==b[0]==x0:return 3
    return None


def side_position(p,side):return(p[0],p[1],-p[0],-p[1])[side]


def sides_at(p,bounds):
    x0,y0,x1,y1=bounds
    return[i for i,ok in enumerate((p[1]==y0,p[0]==x1,p[1]==y1,p[0]==x0))if ok]


def clockwise_compare(reference,a,b):
    va,vb=sub(a['b'],a['a']),sub(b['b'],b['a'])
    transform=lambda v:(reference[0]*v[0]+reference[1]*v[1],-cross(reference,v))
    x,y=transform(va),transform(vb)
    half=lambda p:0 if p[1]>0 or(p[1]==0 and p[0]>=0)else 1
    if half(x)!=half(y):return -1 if half(x)<half(y)else 1
    c=cross(x,y)
    need(c!=0,'AMBIGUOUS_COLLINEAR_DIRECTED_BOUNDARY_GRAPH')
    return -1 if c>0 else 1


def clip_polygon(source_rings,bounds):
    need(len(bounds)==4 and bounds[0]<bounds[2]and bounds[1]<bounds[3],'STRICT_RECTANGLE')
    rings=[]
    for i,source in enumerate(source_rings):
        ring=[point(p)for p in source]
        need(len(ring)>=4 and ring[0]==ring[-1]and area(ring)!=0,'CLOSED_NONDEGENERATE_SOURCE_RING')
        if(area(ring)>0)!=(i==0):ring=list(reversed(ring))
        rings.append(ring)
    x0,y0,x1,y1=map(F,bounds)
    corners=[(x0,y0),(x1,y0),(x1,y1),(x0,y1)]
    events=[{corners[i],corners[(i+1)%4]}for i in range(4)]
    source_edges=[]
    for ri,ring in enumerate(rings):
        for si,(p0,p1)in enumerate(zip(ring,ring[1:])):
            item=segment_interval(p0,p1,bounds)
            if item is None:continue
            lo,hi,a,b=item
            for p in(a,b):
                for side in sides_at(p,bounds):events[side].add(p)
            if a==b:continue
            side=side_of(a,b,bounds)
            if side is not None and side_position(b,side)<=side_position(a,side):
                continue # Source interior and rectangle interior are opposite.
            source_edges.append(dict(a=a,b=b,origin='source',ring=ri,segment=si,
                                     parameter=(lo,hi),side=side,p0=p0,p1=p1))
    edges=[]
    for row in source_edges:
        side=row['side']
        if side is None:edges.append(row);continue
        positions=sorted((p for p in events[side]if on_segment(p,row['a'],row['b'])),key=lambda p:side_position(p,side))
        for a,b in zip(positions,positions[1:]):
            axis=0 if row['p0'][0]!=row['p1'][0]else 1
            parameter=lambda p:(p[axis]-row['p0'][axis])/(row['p1'][axis]-row['p0'][axis])
            edges.append(dict(row,a=a,b=b,parameter=(parameter(a),parameter(b))))
    for side,points in enumerate(events):
        ordered=sorted(points,key=lambda p:side_position(p,side))
        for a,b in zip(ordered,ordered[1:]):
            midpoint=tuple((a[i]+b[i])/2 for i in range(2))
            if winding_classify(midpoint,rings)==1:
                edges.append(dict(a=a,b=b,origin='rectangle',side=side))
    need(len(edges)==len({(e['a'],e['b'])for e in edges}),'UNIQUE_EXACT_DIRECTED_BOUNDARY_EDGES')
    outgoing=defaultdict(list);degree=Counter()
    for i,e in enumerate(edges):
        outgoing[e['a']].append(i);degree[e['a']]+=1;degree[e['b']]-=1
    need(not any(degree.values()),'CLOSED_EXACT_DIRECTED_BOUNDARY_GRAPH')
    unused=set(range(len(edges)));loops=[]
    while unused:
        current=min(unused,key=lambda i:(edges[i]['a'],edges[i]['b']))
        start=edges[current]['a'];ring=[start]
        while True:
            need(current in unused,'EACH_EXACT_BOUNDARY_EDGE_USED_ONCE')
            unused.remove(current);e=edges[current];ring.append(e['b'])
            if e['b']==start:break
            candidates=[i for i in outgoing[e['b']]if i in unused]
            need(candidates,'EXACT_BOUNDARY_WALK_CLOSED')
            if len(candidates)>1:
                reference=sub(e['a'],e['b'])
                candidates=sorted(candidates,key=cmp_to_key(lambda i,j:clockwise_compare(reference,edges[i],edges[j])))
            current=candidates[0]
        need(len(ring)>=4 and area(ring)!=0,'NONZERO_EXACT_CLIPPED_RING')
        loops.append(ring)
    outers=[r for r in loops if area(r)>0];holes=[r for r in loops if area(r)<0]
    polygons=[dict(rings=[r],area=area(r))for r in outers]
    for hole in holes:
        containing=[i for i,r in enumerate(outers)if winding_classify(hole[0],[r])==1]
        need(len(containing)==1,'EXACT_CLIPPED_HOLE_HAS_ONE_EXTERIOR')
        polygons[containing[0]]['rings'].append(hole);polygons[containing[0]]['area']+=area(hole)
    need(all(p['area']>0 for p in polygons),'POSITIVE_EXACT_CLIPPED_POLYGONS')
    return dict(polygons=polygons,edges=edges,sourceRings=rings,
                area=sum((p['area']for p in polygons),F(0)),
                sourceIntervals=[(e['ring'],e['segment'],*e['parameter'])for e in edges if e['origin']=='source'],
                rectangleEdges=[(e['a'],e['b'])for e in edges if e['origin']=='rectangle'])


def rational(value):
    if type(value)is F:return[value.numerator,value.denominator]
    if type(value)is tuple:return[rational(v)for v in value]
    if type(value)is list:return[rational(v)for v in value]
    return value


def normalize_axis_edges(rows):
    """Signed interval function; opposite internal rectangle edges cancel."""
    events=defaultdict(lambda:defaultdict(int))
    for a,b in rows:
        need((a[0]==b[0])!=(a[1]==b[1]),'NONZERO_AXIS_BOUNDARY_SEGMENT')
        axis=1 if a[0]==b[0]else 0;fixed=a[1-axis]
        lo,hi=sorted((a[axis],b[axis]));sign=1 if b[axis]>a[axis]else-1
        events[(axis,fixed)][lo]+=sign;events[(axis,fixed)][hi]-=sign
    out=[]
    for key,changes in sorted(events.items()):
        count=0;previous=None
        for position,delta in sorted(changes.items()):
            if previous is not None and previous<position and count:
                if out and out[-1][:2]==key and out[-1][3]==previous and out[-1][4]==count:
                    out[-1]=(key[0],key[1],out[-1][2],position,count)
                else:out.append((key[0],key[1],previous,position,count))
            count+=delta;previous=position
        need(count==0,'CLOSED_SIGNED_AXIS_INTERVAL_FUNCTION')
    return out

"""Manufacturing adapter for between-hole cuts absent from stripboard-py's router.

Uses stripboard-py's pin/footprint placement above, then connects real copper
islands with insulated top-side wires. Graph connectivity is independently
reconstructed in verify.py; no inferred connection across component bodies.
"""
import heapq,math
from collections import defaultdict
from common import ROOT,OUT,read,save

def complete(c):
    q=c['board'];cols,rows=q['cols'],q['rows'];pins=[];rowpins=defaultdict(list)
    for p in c['parts']:
        for pin,(x,y)in p['pad_grid'].items():
            net=p['pins'][pin] if not p.get('dnp') else None
            v=dict(id=p['ref']+'.'+pin,ref=p['ref'],pin=pin,x=x,y=y,net=net or 'NC_'+p['ref']+'_'+pin)
            pins.append(v);rowpins[y].append(v)
    cuts=[];segments=[]
    for y in range(1,rows+1):
        pp=sorted(rowpins[y],key=lambda p:p['x']);last=1
        # Cuts are BETWEEN holes: do not copy the beta solver's through-hole
        # cuts, and retain physical pin IDs across every drawing.
        groups=[]
        for p in pp:
            if not groups or groups[-1][0]['net']!=p['net']:groups.append([p])
            else:groups[-1].append(p)
        for i,g in enumerate(groups):
            end=(g[-1]['x']+groups[i+1][0]['x'])//2 if i+1<len(groups)else cols
            segments.append(dict(id=f'S{y}_{i}',y=y,xa=last,xb=end,net=g[0]['net'],pins=[p['id']for p in g]))
            if i+1<len(groups):cuts.append(dict(y=y,after_col=end,kerf_mm=.6))
            last=end+1
    occupied={(p['x'],p['y'])for p in pins};bodies=set()
    for p in c['parts']:
        # The Pico underside has a verified DESIGN reserve (not real fit):
        # 8mm sockets, 1.4mm max insulated bridge OD. Wires beneath its body
        # remain accessible after unplugging Pico. Other bodies are avoided.
        if p['kind']=='pico':continue
        for x0,y0,x1,y1 in p['body_keepouts']:
            bodies|={(x,y)for x in range(x0,x1+1)for y in range(y0,y1+1)}
    used=set();paths=[];nets=defaultdict(list);layer_edges=[set(),set(),set()]
    for s in segments:
        if not s['net'].startswith('NC_'):nets[s['net']].append(s)
    def freepads(s):
        return [(x,s['y'])for x in range(s['xa'],s['xb']+1)if(x,s['y'])not in occupied|bodies|used and not mount_blocked(x,s['y'])]
    def mount_blocked(x,y):
        a=q['datum'][0]+(x-1)*2.54;b=q['datum'][1]+(y-1)*2.54
        return any(math.hypot(a-mx,b-my)<4.2 for mx,my in q.get('layout_mounts',q['mounts']))
    blocked={(x,y)for x in range(2,2*cols+1)for y in range(2,2*rows+1)if any(abs(x/2-u)<.6 and abs(y/2-v)<.6 for u,v in bodies)}
    def path(a,z,level):
        # Half-grid orthogonal routing. Different nets may CROSS in insulated
        # wires; no common copper node or solder junction is implied.
        aa=(a[0]*2,a[1]*2);zz=(z[0]*2,z[1]*2)
        queue=[(0,0,aa)];cost={aa:0};prev={};seen=set()
        while queue:
            _,g,u=heapq.heappop(queue)
            if u in seen:continue
            seen.add(u)
            if u==zz:
                pts=[u]
                while u!=aa:u=prev[u];pts.append(u)
                pts=list(reversed(pts));out=[pts[0]]
                for i in range(1,len(pts)-1):
                    if (pts[i][0]-pts[i-1][0],pts[i][1]-pts[i-1][1])!=(pts[i+1][0]-pts[i][0],pts[i+1][1]-pts[i][1]):out.append(pts[i])
                out.append(pts[-1]);return [[x/2,y/2]for x,y in out],pts
            for dx,dy in [(1,0),(-1,0),(0,1),(0,-1)]:
                v=(u[0]+dx,u[1]+dy)
                if not(2<=v[0]<=2*cols and 2<=v[1]<=2*rows):continue
                # Body rectangles include half-cell margins.
                if v not in[aa,zz]and v in blocked:continue
                if v not in[aa,zz]and v[0]%2==0 and v[1]%2==0 and(v[0]//2,v[1]//2)in occupied|used:continue
                if tuple(sorted((u,v)))in layer_edges[level]:continue
                ng=g+1
                if ng<cost.get(v,1e99):cost[v]=ng;prev[v]=u;heapq.heappush(queue,(ng+abs(v[0]-zz[0])+abs(v[1]-zz[1]),ng,v))
        return None
    for net,ss in sorted(nets.items(),key=lambda kv:len(kv[1])):
        ss.sort(key=lambda s:len(freepads(s)),reverse=True)
        # All Pico GND pins are physically common on the purchased Pico PCB.
        connected=[ss.pop(0)];remaining=ss[:]
        if net=='GND':
            for s in remaining[:]:
                if any(pin.startswith('PICO.')for pin in s['pins'])and any(any(pin.startswith('PICO.')for pin in z['pins'])for z in connected):connected.append(s);remaining.remove(s)
        while remaining:
            if net=='GND'and any(any(pin.startswith('PICO.')for pin in s['pins'])for s in connected):
                for s in remaining[:]:
                    if any(pin.startswith('PICO.')for pin in s['pins']):connected.append(s);remaining.remove(s)
            if not remaining:break
            pairs=[]
            for s in connected:
                for t in remaining:
                    for a in freepads(s):
                        for z in freepads(t):pairs.append((abs(a[0]-z[0])+abs(a[1]-z[1]),a,z,s,t))
            # Prefer branch-capable islands before consuming the only free
            # pad of a leaf (e.g. the two op-amp follower pins).
            pairs.sort(key=lambda x:(len(freepads(x[4]))<2,x[0]));found=False
            for level in range(3):
                for _,a,z,s,t in pairs[:150]:
                    routed=path(a,z,level)
                    if routed is None:continue
                    pts,walk=routed
                    layer_edges[level].update(tuple(sorted((u,v)))for u,v in zip(walk,walk[1:]))
                    used|={a,z};paths.append(dict(id=f'W{len(paths)+1:03}',net=net,start=list(a),end=list(z),from_strip=s['id'],to_strip=t['id'],points=pts,side='component',height_lane=level+1,height_mm=1.2+level*1.5,wire='ECCW0.60 insulated solid copper; mechanically strip/tin ends, additional sleeve at crossing; maxOD1.2mm',length_mm=round(sum(math.dist(u,v)*2.54 for u,v in zip(pts,pts[1:]))+2*(1.2+level*1.5),1),end_allowance_mm=8,service_allowance_mm=4))
                    connected.append(t);remaining.remove(t);found=True;break
                if found:break
            if not found:raise ValueError('No accessible top bridge for '+net+' '+str([(s['pins'],freepads(s))for s in remaining])+' sources '+str([(s['pins'],freepads(s))for s in connected]))
    c.update(cuts=cuts,strips=segments,bridges=paths,pins=pins,router_status='COMPLETE_BETWEEN_HOLE_ADAPTER',router_ok=True,
        routing_limits=['Beta autorouter failed adjacent-pin cuts, retained unchanged in evidence/router-library.json','Formed insulated solid-wire height lanes1.2/2.7/4.2mm; no coincident path within a lane. No3D wire collision qualification','Pico removable; top-side bridges under it reserved below8mm sockets','All endpoints have a dedicated free hole; maximum insulated conductorOD1.2mm and formed-wire retention require physical process check'])
    from crossovers import annotate,export_csv
    annotate(c);export_csv(c)
    save(OUT/'data/electronics.json',c)
    print('Final between-hole layout',len(pins),'pins',len(cuts),'cuts',len(paths),'top wires',flush=True)

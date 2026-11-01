"""R3 canonical pins/placement -> physical copper and component-side wire routing.
No net label is used by the independent copper reconstruction to add an edge.
All positions are top view; only the explicitly named underside SVG is mirrored.
"""
from pathlib import Path
import json,hashlib,collections,csv,math,heapq
R=Path(__file__).resolve().parents[1]
D=json.loads((R/'inputs/design.json').read_text()); B=D['board']; M=D['mechanics']; OUT=R/'delivery'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p,d):p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
def xy(h):return [B['origin'][k]+B['pitch']*h[k]for k in range(2)]
def mounting_reserved(h):
 x,y=xy(h)
 return any(abs(x-m['xy'][0])<=m['copper_clearance']/2 and abs(y-m['xy'][1])<=m['copper_clearance']/2 for m in B.get('mounts',[]))
def components():return [c for c in D['components']if 'pads'in c]
def body_spec(c):
 ps=[xy(p['hole'])for p in c['pads'].values()];xs=[p[0]for p in ps];ys=[p[1]for p in ps];cx=(min(xs)+max(xs))/2;cy=(min(ys)+max(ys))/2;k=c['kind'];ref=c['ref']
 if ref=='U1':return dict(x=min(xs)-1.38,y=min(ys)-4.5,w=18,d=44,z=9.5,h=1.6,color='#237e73')
 if ref=='U2':return dict(x=min(xs)-1.27,y=min(ys)-1.27,w=15.24,d=15.24,z=9.5,h=1.6,color='#357d9a')
 if k=='dual_opamp':return dict(x=cx-3.175,y=cy-4.905,w=6.35,d=9.81,z=1.8,h=3.3,color='#243c45')
 if k=='relay':return dict(x=min(xs)-1.24,y=min(ys)-1.2,w=10.1,d=15.1,z=1.3,h=10.5,color='#334c56')
 if k=='npn':return dict(x=cx-2.6,y=cy-2.6,w=5.2,d=5.2,z=4.3,h=5,color='#2b414a')
 if k=='connector':return dict(x=min(xs)-2.54,y=cy-3.8,w=max(xs)-min(xs)+5.08,d=7.6,z=3,h=11,color='#4586a0')
 if k=='jumper':return dict(x=cx-1.25,y=cy-2.55,w=2.5,d=5.1,z=1,h=5.5,color='#b8a05c')
 w,dep,h=c['body_size']
 if c.get('orientation')=='v':w,dep=dep,w
 return dict(x=cx-w/2,y=cy-dep/2,w=w,d=dep,z=c['body_z'],h=h,color='#c1ae7b'if k=='resistor'else'#c7a967'if k=='capacitor'else'#4b5357')

def compile_layout():
 occupied={};cuts=[];segments=[]
 reserved={(x,y)for x in range(B['columns'])for y in range(B['rows'])if mounting_reserved([x,y])}
 for c in components():
  for pin,p in c['pads'].items():
   h=tuple(p['hole']);assert h not in occupied and h not in reserved,(c['ref'],pin,h)
   assert 0<=h[0]<B['columns']and 0<=h[1]<B['rows']
   occupied[h]=dict(contact=c['ref']+'.'+pin,net=p['net']or'NC:'+c['ref']+'.'+pin)
 for y in range(B['rows']):
  pins=sorted((h[0],v['net'])for h,v in occupied.items()if h[1]==y)
  breaks=[x+.5 for x in range(B['columns']-1)if(x,y)in reserved or(x+1,y)in reserved]
  for(a,na),(z,nz)in zip(pins,pins[1:]):
   if na!=nz:
    x=(a+z)//2+.5;breaks.append(x);cuts.append(dict(id='C'+str(len(cuts)+1).zfill(3),row=y,between=[int(x-.5),int(x+.5)],x_grid=x,width_mm=B['cut_width'],method='0.7mm groove BETWEEN eyes across full copper width; mounting squares are separate full copper removal'))
  limits=sorted(set([-.5]+breaks+[B['columns']-.5]))
  for a,z in zip(limits,limits[1:]):
   holes=[[x,y]for x in range(B['columns'])if a<x<z and(x,y)not in reserved];nets={occupied[tuple(h)]['net']for h in holes if tuple(h)in occupied}
   assert len(nets)<=1
   if nets:segments.append(dict(id='S'+str(len(segments)+1).zfill(3),net=next(iter(nets)),holes=holes,row=y))
 bynet=collections.defaultdict(list)
 for s in segments:bynet[s['net']].append(s)
 layers=D['wire']['layers_mm'];rad=D['wire']['OD_mm']/2;clear=.15
 blocks=[]
 for height in layers:
  blocked={(x/2,y/2)for x in range((B['columns']-1)*2+1)for y in range((B['rows']-1)*2+1)if mounting_reserved([x/2,y/2])}
  for xi in range((B['columns']-1)*2+1):
   for yi in range((B['rows']-1)*2+1):
    x,y=xi/2,yi/2
    xx,yy=xy([x,y])
    for c in components():
     sp=body_spec(c)
     if height+rad+clear>sp['z'] and height-rad-clear<sp['z']+sp['h'] and sp['x']-rad-clear<xx<sp['x']+sp['w']+rad+clear and sp['y']-rad-clear<yy<sp['y']+sp['d']+rad+clear:blocked.add((x,y));break
     if c['kind']=='connector':
      py=xy(next(iter(c['pads'].values()))['hole'])[1]
      if height-rad-clear<3 and sp['y']-rad-clear<yy<sp['y']+sp['d']+rad+clear and(sp['x']-rad-clear<xx<sp['x']+1+rad+clear or sp['x']+sp['w']-1-rad-clear<xx<sp['x']+sp['w']+rad+clear):blocked.add((x,y))
      my=sp['y']+sp['d']+.2
      if height+rad+clear>sp['z']+.8 and sp['x']-1.1-rad-clear<xx<sp['x']+sp['w']+1.1+rad+clear and my-rad-clear<yy<my+sp['d']+1+rad+clear:blocked.add((x,y))
    # Long purchase socket rails, not just the Nano/DAC PCB envelopes.
    for col,ya,yz in [(3,3,17),(9,3,17),(14,2,7)]:
     if abs(x-col)*B['pitch']<1.25+rad+clear and ya-(1.27+rad+clear)/B['pitch']<y<yz+(1.27+rad+clear)/B['pitch']:blocked.add((x,y))
  blocks.append(blocked)
 endpoint_uses=collections.Counter();exit_dirs=collections.defaultdict(set)
 endpoints=dict(occupied);tracks=[set()for z in layers];links=[];used=set()
 # One free anchor per used strip, protected from overflight by all other nets.
 for s in segments:
  if s['net'].startswith('NC:')or len(bynet[s['net']])<2:continue
  free=[h for h in s['holes']if tuple(h)not in occupied and any(tuple(h)not in q for q in blocks)]
  if free:
   h=min(free,key=lambda h:abs(h[0]-(s['holes'][0][0]+s['holes'][-1][0])/2));endpoints[tuple(h)]={'net':s['net'],'layer':next(i for i,q in enumerate(blocks)if tuple(h)not in q)}
 def route(a,z,layer,net):
  start,end=tuple(a),tuple(z);todo=[(0,0,start,-1,0)];cost={(start,-1,0):0};prev={};done=None
  while todo:
   _,g,h,di,runlen=heapq.heappop(todo);key=(h,di,runlen)
   if g!=cost.get(key):continue
   if h==end and runlen>=2:done=key;break
   for ni,(dx,dy)in enumerate([(.5,0),(-.5,0),(0,.5),(0,-.5)]):
    nxt=(h[0]+dx,h[1]+dy)
    if di!=-1 and(ni==(di^1)or(ni!=di and runlen<2)):continue
    if nxt==start:continue
    if h==start and start in used and(ni in exit_dirs[start]or any(nxt in block for block in blocks[:layer+1])or any(nxt in tr for tr in tracks[:layer+1])):continue
    if nxt==end and end in used and((ni^1)in exit_dirs[end]or any(h in block for block in blocks[:layer+1])or any(h in tr for tr in tracks[:layer+1])):continue
    if not(0<=nxt[0]<B['columns']and 0<=nxt[1]<B['rows']):continue
    if nxt in blocks[layer]or(nxt in tracks[layer]and nxt not in[start,end]):continue
    if nxt in occupied and nxt not in[start,end]:continue
    if nxt in endpoints and nxt not in[start,end]and endpoints[nxt]['net']!=net and layer<=endpoints[nxt].get('layer',99):continue
    if nxt in used and nxt not in[start,end]and layer<=endpoints[nxt].get('layer',99):continue
    nr=min(2,runlen+1)if di==ni else 1
    if nxt==end and nr<2:continue
    ng=g+10+(0 if di in[-1,ni]else 6);nk=(nxt,ni,nr)
    if ng<cost.get(nk,1e20):cost[nk]=ng;prev[nk]=key;heapq.heappush(todo,(ng+20*(abs(nxt[0]-end[0])+abs(nxt[1]-end[1])),ng,nxt,ni,nr))
  if done is None:return None
  key=done;path=[list(key[0])]
  while key!=(start,-1,0):key=prev[key];path.append(list(key[0]))
  path.reverse();turns=[v for i,v in enumerate(path)if i==0 or i==len(path)-1 or(v[0]-path[i-1][0],v[1]-path[i-1][1])!=(path[i+1][0]-v[0],path[i+1][1]-v[1])]
  return turns,cost[done]
 for net,segs in sorted(bynet.items(),key=lambda row:((D['routing_priority'].index(row[0])if row[0]in D['routing_priority']else 999),row[0])):
  if net.startswith('NC:')or len(segs)<2:continue
  done=[segs[0]];todo=segs[1:]
  while todo:
   choices=[]
   for a in done:
    for z in todo:
     for p in a['holes']:
      if (tuple(p)in used and endpoints[tuple(p)]['net']!=net)or(tuple(p)in occupied and occupied[tuple(p)]['contact'].split('.')[0]in['U1','U2','K1','JP1']):continue
      for q in z['holes']:
       if (tuple(q)in used and endpoints[tuple(q)]['net']!=net)or(tuple(q)in occupied and occupied[tuple(q)]['contact'].split('.')[0]in['U1','U2','K1','JP1']):continue
       if any(endpoints.get(tuple(t),{'net':net})['net']!=net for t in [p,q]):continue
       for li in range(len(layers)):
        if any(tuple(t)in blocks[li]or tuple(t)in blocks[0]or(any(tuple(t)in tr for tr in tracks[:li+1])and tuple(t)not in used)or(tuple(t)in used and li>endpoints[tuple(t)]['layer'])for t in[p,q]):continue
        if any(tuple(t)in occupied and occupied[tuple(t)]['contact'].startswith('J')and endpoint_uses[tuple(t)] for t in[p,q]):continue
        # A free eye covered by the captive spacer web cannot be an endpoint.
        if any(tuple(t)not in occupied and any(c['kind']=='connector'and abs(xy(t)[1]-xy(next(iter(c['pads'].values()))['hole'])[1])<.8 and body_spec(c)['x']<=xy(t)[0]<=body_spec(c)['x']+body_spec(c)['w']for c in components())for t in[p,q]):continue
        choices.append((abs(p[0]-q[0])+abs(p[1]-q[1])+(len(layers)-1-li)*.2,a['id'],z['id'],p,q,li,z))
   best=None
   ordered=sorted(choices,key=lambda v:v[:3]);pool=[]
   for li in range(len(layers)):pool.extend([v for v in ordered if v[5]==li][:100])
   for ch in sorted(pool,key=lambda v:v[:3]):
    _,_,_,p,q,li,z=ch;rr=route(p,q,li,net)
    if rr is None:continue
    path,cost=rr;score=cost+(len(layers)-1-li)*2
    if best is None or score<best[0]:best=(score,p,q,li,z,path)
    if best and best[0]<=ch[0]*10+8:break
   if best is None:
    save(R/'reports/routing-incomplete.json',dict(net=net,done=done,todo=todo,used=list(used),occupied={str(k):v for k,v in occupied.items()},layer_blocks=[list(s)for s in blocks],jumpers=links));raise RuntimeError('No physical top-side route for '+net+' remaining '+str([s['id']for s in todo]))
   _,p,q,li,z,path=best
   prior_used=set(used);used.update([tuple(p),tuple(q)]);endpoints[tuple(p)]={'net':net,'layer':li};endpoints[tuple(q)]={'net':net,'layer':li}
   for a,b in zip(path,path[1:]):
    for xi in range(round(min(a[0],b[0])*2),round(max(a[0],b[0])*2)+1):
     for yi in range(round(min(a[1],b[1])*2),round(max(a[1],b[1])*2)+1):tracks[li].add((xi/2,yi/2))
   coords=[xy(h)for h in path];height=layers[li]
   p3=[[coords[0][0],coords[0][1],(1.7 if occupied.get(tuple(p),{}).get('contact','').startswith('J')else 1.1)if tuple(p)in occupied or tuple(p)in prior_used else 0]]+[[x,y,height]for x,y in coords]+[[coords[-1][0],coords[-1][1],(1.7 if occupied.get(tuple(q),{}).get('contact','').startswith('J')else 1.1)if tuple(q)in occupied or tuple(q)in prior_used else 0]]
   # A lead lap starts ABOVE the board, offset0.35mm along the outgoing wire.
   # Never force a second conductor through an occupied copper eye.
   for at,neighbor,end in [(0,2,p),(-1,-3,q)]:
    if tuple(end)in prior_used:
     ii=1 if at==0 else -2;vec=[p3[neighbor][k]-p3[ii][k]for k in [0,1]];nn=math.hypot(*vec)
     for k in [0,1]:p3[at][k]+=.85*vec[k]/nn;p3[ii][k]=p3[at][k]
   radii=[(.45 if i in[1,len(p3)-2]else D['wire']['bend_radius_mm'])for i in range(1,len(p3)-1)]
   length=sum(math.dist(u,v)for u,v in zip(p3,p3[1:]))-sum(radii)*(2-math.pi/2)
   links.append(dict(id='W'+str(len(links)+1).zfill(3),net=net,start=p,end=q,path=path,layer=li+1,height_above_board_bottom_mm=height,side='component',insulated=True,wire_sku=D['wire']['sku'],wire_OD_mm=D['wire']['OD_mm'],bend_radius_mm=D['wire']['bend_radius_mm'],bend_radii_mm=radii,route_mm=round(length,2),cut_mm=math.ceil(length+6),bare_end_mm=3,start_attachment='top-side lead lap, no second wire in eye'if tuple(p)in occupied or tuple(p)in prior_used else'dedicated solder eye',end_attachment='top-side lead lap, no second wire in eye'if tuple(q)in occupied or tuple(q)in prior_used else'dedicated solder eye',path3d=p3))
   for h,vec in[(p,[path[1][i]-path[0][i]for i in[0,1]]),(q,[path[-2][i]-path[-1][i]for i in[0,1]])]:
    di=0 if vec[0]>0 else 1 if vec[0]<0 else 2 if vec[1]>0 else 3;exit_dirs[tuple(h)].add(di);endpoint_uses[tuple(h)]+=1
   done.append(z);todo.remove(z)
 return dict(revision=D['revision'],source_sha256=sha(R/'inputs/design.json'),board=B,routing=D['wire'],mounting_copper_clearances=B['mounts'],segments=segments,cuts=cuts,jumpers=links,components=components(),pin_holes=[dict(hole=list(h),**v)for h,v in occupied.items()])

def independent_check(l):
 parent={(x,y):(x,y)for x in range(B['columns'])for y in range(B['rows'])}
 def root(p):
  while parent[p]!=p:parent[p]=parent[parent[p]];p=parent[p]
  return p
 def join(a,z):parent[root(a)]=root(z)
 cuts={(c['row'],c['x_grid'])for c in l['cuts']}
 for y in range(B['rows']):
  for x in range(B['columns']-1):
   if(y,x+.5)not in cuts and not mounting_reserved([x,y])and not mounting_reserved([x+1,y]):join((x,y),(x+1,y))
 for w in l['jumpers']:join(tuple(w['start']),tuple(w['end']))
 for c in l['components']:
  for bus in c.get('buses',[]):
   for a,z in zip(bus,bus[1:]):join(tuple(c['pads'][a]['hole']),tuple(c['pads'][z]['hole']))
 groups=collections.defaultdict(set);nets=collections.defaultdict(set)
 for c in l['components']:
  for pin,p in c['pads'].items():
   n=p['net']or'NC:'+c['ref']+'.'+pin;key=root(tuple(p['hole']));groups[key].add(n);nets[n].add(key)
 errors=[dict(reason='short',nets=sorted(v))for v in groups.values()if len(v)>1]+[dict(reason='open',net=n,islands=len(v))for n,v in nets.items()if len(v)!=1]
 pink=sorted(c['ref']+'.'+pin for c in l['components']for pin,n in c['pins'].items()if n=='PINK')
 assert pink==['J2.1','K1.1','K1.12'],pink
 return dict(status='FAIL'if errors else'PASS',errors=errors,source_sha256=sha(R/'inputs/design.json'),physical_board_count=1,cuts=len(l['cuts']),top_jumpers=len(l['jumpers']),nets=len(nets),relay_open_external_pink_members=pink,proof='Union-find edges from adjacent raw copper eyes, cutter positions, jumper ends and purchased internal buses. Net names compared only AFTER connectivity reconstruction.',physical_acceptance=False)
def tables(l):
 OUT.mkdir(exist_ok=True)
 for name,rows,cols in [('cuts',l['cuts'],['id','row','between','width_mm','method']),('jumpers',l['jumpers'],['id','net','start','end','path','layer','height_above_board_bottom_mm','bend_radius_mm','route_mm','cut_mm','bare_end_mm']),('contacts',[dict(ref=c['ref'],pin=p,net=n or'NC',hole=c.get('pads',{}).get(p,{}).get('hole'),value=c['value'])for c in D['components']for p,n in c['pins'].items()],['ref','pin','net','hole','value'])]:
  with(OUT/(name+'.csv')).open('w')as f:w=csv.DictWriter(f,fieldnames=cols,extrasaction='ignore');w.writeheader();w.writerows(rows)
 save(OUT/'stripboard.json',l);save(R/'reports/electrical-connectivity.json',independent_check(l))
 (R/'firmware/YamahaTrimAdapter/PinContract.h').write_text('// Generated from inputs/design.json; TOP view, USB at minY.\n#pragma once\nconst uint8_t RELAY=4, HEARTBEAT=5;\nconst uint8_t GATE=A2, LED=13, SENSOR=A0, MONITOR=A1, EXCITATION=A3, BOAT=A6;\n')
 save(R/'reports/pin-orientation.json',dict(status='PASS',source_sha256=sha(R/'inputs/design.json'),nano='USB at minY; D13 left, D12 right',relay='FBR211 B type; bottom view mirroredY explicitly:1/12 COM,8 NO,7 NC,5/6 coil',actual_clone_pinout='OPEN'))
if __name__=='__main__':
 l=compile_layout();tables(l);print(json.dumps(independent_check(l),indent=2))

"""Conservative DB89 envelopes, all nine own-board pairs, fixed module positions."""
import sys,math,collections
from common import ROOT,read,save,sha
sys.path.insert(0,'/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pi-box-l3-routing-runtime')
from ortools.sat.python import cp_model

def main():
 d=read(ROOT/'design.json');e=read(ROOT/'inputs/l4-contact-baseline.json');m=read(ROOT/'output/adapter-mechanical.json');pairs=[p for p in e['connector_pairs']if p.get('board')and p['id']!='GPIO_SOCKET']
 removable={p['id']for p in pairs}|{'PI_BANK_HEADER','PI_BANK_PLUG','HUB_BANK_HEADER','HUB_BANK_PLUG'}
 relaxed='--relax-own-parts'in sys.argv
 if relaxed:removable|={'PI_D1','PI_D2','PI_D3','HUB_D1','HUB_D2','PI_C1','HUB_C1','IN_D0','IN_TVS','IN_C0'}
 obs=[r for r in m['rows']if not r['printed']and not r.get('hardware')and r['id']not in removable]
 model=cp_model.CpModel();choices={};sel={};intervals=collections.defaultdict(lambda:[[],[]]);costs=[]
 def rectangle(key,opts,lev,margin):
  ix=model.new_int_var(0,len(opts)-1,key+'_choice');rows=[]
  for i,o in enumerate(opts):rows.append([i,round(o['x']*10)-margin,round(o['y']*10)-margin,math.ceil(o['w']*10)+2*margin,math.ceil(o['h']*10)+2*margin,o['cost']])
  vs=[ix]+[model.new_int_var(min(r[j]for r in rows),max(r[j]for r in rows),key+'_'+str(j))for j in range(1,6)]
  model.add_allowed_assignments(vs,rows);costs.append(vs[5])
  for k in [0,1]:
   end=model.new_int_var(0,3000,key+'_end'+str(k));model.add(end==vs[1+k]+vs[3+k]);intervals[lev][k].append(model.new_interval_var(vs[1+k],vs[3+k],end,key+'_axis'+str(k)))
  return ix
 for p in pairs:
  name=p['id'];n=len(p['pins']);board=p['board'];lev='devices'if board=='GPIO_A'else'supply';target=d['boards'][board]['origin'];opts=[]
  # Width derived from DB89 4P conservative envelope, unchanged depth/height.
  width=26-(4-n)*5.08;depth=30;height=24
  for rot in [0,90]:
   w,h=(depth,width)if rot==0 else(width,depth)
   for x in range(7,math.floor(210-w),2):
    for y in range(7,math.floor(189-h),2):
     for z in ([43.8,46.8,48.8]if lev=='devices'else[10.8,12.8,14.6,14.8]):
      if z+height>(77 if lev=='devices' else 39):continue
      if any(x<cx+6 and x+w>cx-6 and y<cy+6 and y+h>cy-6 for cx,cy in d['frame']['corner_axes']):continue
      if any(all(min([x+w+.5,y+h+.5,z+height+.5][i],r['bounds_mm'][1][i])-max([x-.5,y-.5,z-.5][i],r['bounds_mm'][0][i])>.01 for i in range(3))for r in obs if r['level']==lev):continue
      # Full layer domain: proximity is an objective, never a hidden feasibility cap.
      distance=math.dist([x+w/2,y+h/2],target[:2])
      opts.append(dict(x=x,y=y,z=z,w=w,h=h,height=height,rotation=rot,cost=int(distance*10)+int((z-(43.8 if lev=='devices' else 10.8))*10)))
  opts.sort(key=lambda o:o['cost']);choices[name]=opts
  print(name,len(opts),'candidates',flush=True)
  if not opts:
   save(ROOT/'output/connector-fit.json',dict(revision='PI-2L-5',status='FAIL_NO_CANDIDATES',connector=name,envelope=[depth,width,height],scope='Conservative AABB search, not a global impossibility proof',module_positions_unchanged=True));return
  sel[name]=rectangle(name,choices[name],lev,5)
 component_choices={};component_sel={}
 if relaxed:
  for bid,board in e['boards'].items():
   if bid not in ['PI_A','HUB_A','INPUT_A']:continue
   ox,oy,_=d['boards'][bid]['origin'];bw,bh,_=board['size']
   for part in board['parts']:
    if part['kind']not in ['Schottky','TVS','radial_capacitor']:continue
    px,py,pw,ph=part['body_xywh'];key=bid+'.'+part['id'];opts=[]
    for dx in range(-30,31):
     xx=px+2.54*dx
     if xx<-.001 or xx+pw>bw+.001:continue
     for dy in range(-30,31):
      yy=py+2.54*dy
      if yy<-.001 or yy+ph>bh+.001:continue
      if any(math.hypot(max(xx-mx,0,mx-xx-pw),max(yy-my,0,my-yy-ph))<3.5 for mx,my in board['mounts']):continue
      opts.append(dict(x=ox+xx,y=oy+yy,w=pw,h=ph,local_x=xx,local_y=yy,cost=(abs(dx)+abs(dy))*25))
    component_choices[key]=opts
    if not opts:raise RuntimeError('No component candidates '+key)
    component_sel[key]=rectangle(key,opts,'supply',13)
 for dims in intervals.values():model.add_no_overlap_2d(*dims)
 model.minimize(sum(costs));s=cp_model.CpSolver();s.parameters.max_time_in_seconds=120;s.parameters.num_search_workers=1;s.parameters.random_seed=5;s.parameters.stop_after_first_solution=True;status=s.solve(model)
 out=[];part_out=[]
 if status in [cp_model.OPTIMAL,cp_model.FEASIBLE]:
  for p in pairs:
   o=choices[p['id']][s.value(sel[p['id']])];out.append(dict(id=p['id'],board=p['board'],poles=len(p['pins']),item_id=p.get('item_id'),**o))
  for key,opts in component_choices.items():part_out.append(dict(id=key,**opts[s.value(component_sel[key])]))
 save(ROOT/('output/connector-fit-relaxed.json'if relaxed else'output/connector-fit.json'),dict(revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),status=s.status_name(status),scope='Conservative AABB candidate packing; precise retention/mating/cable and tray test still required',reoptimized_own_components=relaxed,component_placements=part_out,envelope_basis='DB89 26x30x24mm4P; pole-width difference5.08mm per omitted pole, marked planning assumption',placements=out,candidate_counts={k:len(v)for k,v in choices.items()},objective=s.objective_value if out else None,seconds=s.wall_time))
 print(s.status_name(status),out,flush=True)
if __name__=='__main__':main()

"""Library-based fixed-board routing and connector placement; no hardware actions."""
import json,subprocess,math,itertools,sys
from pathlib import Path
from shapely.geometry import box,Point,LineString
sys.path.insert(0,'/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pi-box-l3-routing-runtime')
from ortools.sat.python import cp_model
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent
def route(payload):
 p=subprocess.run(['node',str(HERE/'router.mjs')],input=json.dumps(payload),text=True,capture_output=True,timeout=45)
 if p.returncode:
  (ROOT/'output/failed-router-input.json').write_text(json.dumps(payload,indent=2));raise RuntimeError(p.stderr[-1500:])
 return json.loads(p.stdout)
def top(b,start,end,diam,occupied,report):
 obstacles=[]
 for p in b['parts']:
  x,y,w,h=p.get('access_envelope_xywh',p['body_xywh']);r=diam/2+.18
  # Endpoint clearances are already independently constrained by OR-Tools.
  obstacles.append(dict(id=p['id'],rect=[x-r,y-r,w+2*r,h+2*r]))
 for n,(x,y)in enumerate(b['mounts']):
  r=3.5+diam/2+.18;obstacles.append(dict(id='mount'+str(n),rect=[x-r,y-r,2*r,2*r]))
 result=route(dict(obstacles=obstacles,links=[dict(id='bridge',start=start,end=end)],buffer=0,spacing=.25))['routes'][0]['points']
 report.append(dict(board=b['id'],engine='libavoid-js0.5.0-beta.5',start=start,end=end,path_mm=round(LineString(result).length,3),orthogonal=True))
 return result

def connector_placement(e,d,mechanical,pairs):
 obstacles=[r for r in mechanical['rows']if not r.get('printed')and not r.get('hardware')]
 model=cp_model.CpModel();choices={};variables={};cost=[]
 for p in pairs:
  name=p['id'];n=len(p['pins']);bid=p['board'];origin=d['boards'][bid]['origin'];target=[origin[0]+20,origin[1]+min(20,e['boards'][bid]['size'][1]/2)]
  level='devices'if bid=='GPIO_A'else'supply';opts=[]
  # Existing paired L3 family body: 28 x (21.82 - omitted poles*5.08) x12.2.
  # Add1mm lateral insulation and2mm height allowance for insulated tails.
  a,b,h=30,23.82-(4-n)*5.08,14.2
  for rot in [False,True]:
   ww,hh=(b,a)if rot else(a,b)
   for x in range(7,math.floor(210-ww),3):
    for y in range(7,math.floor(189-hh),3):
     for z in ([47,58,60]if level=='devices'else[9,13,22,24,24.5]):
      if z+h>(77 if level=='devices'else 39):continue
      if any(all(min([x+ww+.5,y+hh+.5,z+h+.5][i],r['bounds_mm'][1][i])-max([x-.5,y-.5,z-.5][i],r['bounds_mm'][0][i])>.01 for i in range(3))for r in obstacles if r['level']==level):continue
      if any(x<cx+5 and x+ww>cx-5 and y<cy+5 and y+hh>cy-5 for cx,cy in d['frame']['corner_axes']):continue
      if math.dist([x+ww/2,y+hh/2],target)>65:continue
      opts.append([x,y,z,ww,hh,h,rot])
  opts.sort(key=lambda o:math.dist([o[0]+o[3]/2,o[1]+o[4]/2],target));opts=opts[:160]
  if not opts:raise RuntimeError('No nominal connector placement '+name)
  choices[name]=opts;vs=[model.new_bool_var(name+str(i))for i in range(len(opts))];variables[name]=vs;model.add_exactly_one(vs);cost.extend(i*v for i,v in enumerate(vs))
 for ai,(a,opts)in enumerate(choices.items()):
  for b,zs in list(choices.items())[:ai]:
   for i,o in enumerate(opts):
    for j,z in enumerate(zs):
     if all(min([o[0]+o[3]+.5,o[1]+o[4]+.5,o[2]+o[5]+.5][k],[z[0]+z[3],z[1]+z[4],z[2]+z[5]][k])-max(o[k],z[k])>0 for k in range(3)):model.add_bool_or([variables[a][i].Not(),variables[b][j].Not()])
 model.minimize(sum(cost));solver=cp_model.CpSolver();solver.parameters.max_time_in_seconds=20;solver.parameters.num_search_workers=1;solver.parameters.random_seed=4;status=solver.solve(model)
 if status not in [cp_model.OPTIMAL,cp_model.FEASIBLE]:raise RuntimeError('Connector packing '+solver.status_name(status))
 for p in pairs:
  o=choices[p['id']][next(i for i,v in enumerate(variables[p['id']])if solver.value(v))];p['bounds_mm']=[o[:3],[o[0]+o[3],o[1]+o[4],o[2]+o[5]]];p['rotated']=bool(o[6]);p['candidate_count']=len(choices[p['id']]);p['geometry_basis']='L3 existing paired-family body plus1mm lateral and2mm tail insulation; actual generic article unconfirmed'
  p['qualification']='OPEN_ACTUAL_PAIRED_BODY_TAILS_WITHDRAWAL_AND_TIE_ACCESS';p['large_uncertainty_envelope']='24mm-height DB uncertainty envelope cannot be fitted in existing lower tier; not overridden or called confirmed'
  aa,zz=p['bounds_mm'];p['pin_points']={str(i):[aa[0]+(zz[0]-aa[0])*.5,aa[1]+(zz[1]-aa[1])*(i/(len(p['pins'])+1)),aa[2]+5]for i in range(1,len(p['pins'])+1)}
 return dict(engine='Google OR-Tools CP-SAT',status=solver.status_name(status),pairs=len(pairs),family_geometry_only=True,qualified_real_fit=False)

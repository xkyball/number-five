"""Actual OCCT intersections with spatially bounded contact allowances."""
import json,itertools,math
import build123d as b
from contract import R,D,M,OUT,save,sha
from cad import vol,cyl,box,cut,fuse
m=json.loads((OUT/'manifest.json').read_text());rows=m['rows'];meta={r['id']:r for r in rows};parts={r['id']:b.import_brep(OUT/'cad-cache'/(r['id']+'.brep'))for r in rows};bounds={i:[list(s.bounding_box().min),list(s.bounding_box().max)]for i,s in parts.items()}
valid=[dict(id=i,valid=bool(s.is_valid),solids=len(s.solids()),status='PASS'if s.is_valid and len(s.solids())==1 else'FAIL')for i,s in parts.items()]
save(R/'reports/validity.json',dict(status='PASS'if all(v['status']=='PASS'for v in valid)else'FAIL',manifest_sha256=sha(OUT/'manifest.json'),checks=valid))
def possible(a,z,pad=0):return all(bounds[a][0][k]<=bounds[z][1][k]+pad and bounds[z][0][k]<=bounds[a][1][k]+pad for k in range(3))
def region(spec):
 q=spec['zone'];return cyl(*q['start'],q['radius'],q['length'],tuple(q['axis']))
allowed={tuple(e['pair']):region(e)for e in m['expected_contacts']}
layout=json.loads((OUT/'stripboard.json').read_text());wire={w['id']:w for w in layout['jumpers']};ox,oy=M['board_xy'];bz=M['board_z'];contacts=[];unexpected=[];tested=0
for a,z in itertools.combinations(parts,2):
 if not possible(a,z):continue
 tested+=1;inter=parts[a].intersect(parts[z]);vv=vol(inter)
 if vv<.001:continue
 zone=allowed.get(tuple(sorted([a,z])));reason=None
 if zone is not None:reason='named screw thread cylinder'
 if a in wire and z in wire and wire[a]['net']==wire[z]['net']:
  # Only shared stripped solder ends may merge; same net is NOT a global waiver.
  joints=[]
  for wa in ['start','end']:
   for wz in['start','end']:
    if wire[a][wa]==wire[z][wz]:
     x,y=[D['board']['origin'][k]+D['board']['pitch']*wire[a][wa][k]for k in range(2)]
     joints.append(cyl(ox+x,oy+y,bz,1.3,4.5))
  if joints:zone=fuse(*joints);reason='same-net stripped joint at explicitly shared eye only'
 remainder=vol(cut(inter,zone))if zone is not None else vv
 row=dict(pair=[a,z],intersection_mm3=vv,outside_contact_zone_mm3=remainder,reason=reason)
 (contacts if remainder<.001 else unexpected).append(row)
save(R/'reports/collisions.json',dict(status='FAIL'if unexpected else'PASS',manifest_sha256=sha(OUT/'manifest.json'),bbox_candidate_pairs=tested,contacts=contacts,unexpected=unexpected,scope='Actual body and continuous wire sweeps; each allowance has its own spatial contact region. No arbitrary permitted volume. Purchase lead geometry remains nominal/not represented.'))
checks=[]
for t in m['tools']:
 s=cyl(*t['start'],t['diameter']/2,t['length'],tuple(t['axis']));hits=[]
 for row in rows:
  if row['id']in t['exclude']or(row['kind']in['hood','hood_screw']and not t['id'].startswith('MOUNT_')):continue
  v=vol(s.intersect(parts[row['id']]))
  if v>.001:hits.append(dict(id=row['id'],volume_mm3=v))
 checks.append(dict(id=t['id'],status='FAIL'if hits else'PASS',hits=hits,diameter=t['diameter']))
save(R/'reports/tool-access.json',dict(status='FAIL'if any(c['hits']for c in checks)else'PASS',manifest_sha256=sha(OUT/'manifest.json'),checks=checks,scope='Continuous driver cylinders; hood removed for internal access.'))
# Continuous upward escape envelope from actual planar faces; interference means blocked.
from inspect_mechanics import moving_path
fixed=[r['id']for r in rows if r['kind']not in['hood','hood_screw']]
rem=moving_path(parts,['P_HOOD'],fixed,[0,0,65])
save(R/'reports/hood-removal.json',dict(status='PASS'if rem['clear']else'FAIL'if rem['collisions']else'OPEN',manifest_sha256=sha(OUT/'manifest.json'),result=rem))
# USB service uses the actual closed geometry with only the cap/hood removed.
sp=next(q for q in m['spaces']if q['id']=='USB_PLUG');lo,hi=sp['bounds'];plug=box(*lo,*[hi[k]-lo[k]for k in range(3)]);hits=[]
for row in rows:
 if row['kind']in['hood','hood_screw','usb_cap','usb_screw']or row['id']=='U1_USB_C':continue
 v=vol(plug.intersect(parts[row['id']]))
 if v>.001:hits.append(dict(id=row['id'],volume_mm3=v))
save(R/'reports/usb-access.json',dict(status='FAIL'if hits else'PASS',manifest_sha256=sha(OUT/'manifest.json'),bounds=[lo,hi],hits=hits,procedure='remove cap; unplug J2/J3 and JP1; insert USB; real cable shell/clone socket OPEN'))
print('CHECK',dict(unexpected=len(unexpected),invalid=[r['id']for r in valid if r['status']=='FAIL'],tools=[r for r in checks if r['hits']],USB=hits,hood=rem['collisions'][:8]),flush=True)

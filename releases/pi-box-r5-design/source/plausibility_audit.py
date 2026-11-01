"""Read-only OCCT audit of the retained print set; never slices or starts a printer."""
import sys,math,itertools
import build123d as b
from OCP.BRepCheck import BRepCheck_Analyzer
from common import ROOT,read,save,sha
from build_rest import B,C,cut,union,volume

def main():
 m=read(ROOT/'output/rest-mechanical.json');d=read(ROOT/'design.json');rows={r['id']:r for r in m['rows']};cache={};result={};calls=0
 for r in m['rows']:assert sha(r['brep']['path'])==r['brep']['sha256'],r['id']
 def shape(id):
  if id not in cache:cache[id]=b.import_brep(rows[id]['brep']['path'])
  return cache[id]
 def overlap(a,z):
  aa,az=list(a.bounding_box().min),list(a.bounding_box().max);ba,bz=list(z.bounding_box().min),list(z.bounding_box().max)
  return all(min(az[k],bz[k])-max(aa[k],ba[k])>1e-5 for k in range(3))
 def path(members,obstacles,poses,label):
  nonlocal calls
  hits=[];n=0
  for vec in poses:
   for id in members:
    lo0,hi0=rows[id]['bounds_mm'];qlo=[lo0[k]+vec[k]for k in range(3)];qhi=[hi0[k]+vec[k]for k in range(3)];q=None
    for other in obstacles:
     if other in members:continue
     r=rows[other];lo,hi=r['bounds_mm']
     if not all(min(qhi[k],hi[k])-max(qlo[k],lo[k])>1e-5 for k in range(3)):continue
     if q is None:q=b.Pos(*vec)*shape(id)
     n+=1;v=volume(q.intersect(shape(other)))
     if v>.02:hits.append(dict(moving=id,other=other,translation_mm=list(vec),volume_mm3=v))
  calls+=n;print(label,'exact',n,'hits',len(hits),flush=True)
  return dict(status='PASS_SAMPLED_NOMINAL'if not hits else'FAIL',poses=len(poses),exact_tests=n,hits=hits[:30],scope='Discrete rigid-body poses; flexible wires and real tolerances not certified.')
 # All new installed print bodies against actual current geometry.
 new=list(dict.fromkeys([p['instance']for p in m['new_print_instances']]+[r['id']for r in m['rows']if r['id'].endswith('_ADAPTER')and r['printed']]))
 hits=[];intended=[];n=0;seen=set()
 for id in new:
  for other in rows:
   key=tuple(sorted([id,other]))
   if id==other or key in seen:continue
   seen.add(key)
   aa,az=rows[id]['bounds_mm'];ba,bz=rows[other]['bounds_mm']
   if not all(min(az[k],bz[k])-max(aa[k],ba[k])>1e-5 for k in range(3)):continue
   n+=1;v=volume(shape(id).intersect(shape(other)))
   if v>.02:
    rec=dict(a=id,b=other,volume_mm3=v)
    if id.startswith('HUB_FUSE_CARRIER_')and other.startswith('HF')and'_M2_'in other:intended.append(rec)
    else:hits.append(rec)
 result['installed_prints']=dict(status='PASS'if not hits else'FAIL',instances=len(new),exact_tests=n,unintended=hits,intended_petg_thread_engagement=intended)
 print('Installed',n,len(hits),flush=True)
 # Each exported riser, returned to its local frame, not a newly generated proxy.
 joints=[]
 for i,(x,y)in enumerate([(12,12),(204,12),(12,182),(204,182)],1):
  rr=b.Rot(0,0,-180 if x<100 else 0)*b.Pos(-x,-y,-40)*shape('STACK_RISER_'+str(i))
  bolt=union(C(0,0,-6.4,1.5,12),C(0,0,5.6,2.85,1.65));nut=cut(C(0,0,15.1,5.5/math.sqrt(3),2.4,6),C(0,0,15,1.26,2.6))
  screwmax=max(volume(rr.intersect(b.Pos(k,0,0)*bolt))for k in range(10));nutmax=max(volume(rr.intersect(b.Pos(k,0,0)*nut))for k in range(10));roof=volume(rr.intersect(b.Pos(0,0,2)*nut));tool=volume(rr.intersect(C(0,0,5.7,1.48,17.5)))
  joints.append(dict(id=i,screw_entry_overlap_mm3=screwmax,nut_entry_overlap_mm3=nutmax,nut_lift_blocked_mm3=roof,tool_overlap_mm3=tool,status='PASS'if max(screwmax,nutmax,tool)<1e-5 and roof>10 else'FAIL'))
 result['actual_risers']=joints
 # Service openings through actual cradle walls: conservative 2.5 x 0.8 mm tie probe.
 slots=[]
 for r in d['connector_mounts']:
  id={'PI_A':'PI_A_CONNECTOR_CARRIER','HUB_A':'HUB_A_CONNECTOR_CARRIER','INPUT_A':'INPUT_A_CONNECTOR_CARRIER','GPIO_A':'GPIO_A_CONNECTOR_CARRIER','HF1_A':'HUB_FUSE_CARRIER_1','HF2_A':'HUB_FUSE_CARRIER_2'}[r['board']]
  x=r['origin'][0];y=r['origin'][1];z=r['origin'][2];w=r['poles']*5.08+1.5
  for dx in [2.3,-1]:
   probe=B(x+dx+.35,y-3,z-3.5,2.5,w+6,.8);v=volume(shape(id).intersect(probe));slots.append(dict(id=r['id'],offset=dx,overlap_mm3=v,status='PASS'if v<1e-5 else'FAIL'))
 result['tie_slots']=slots
 # Main interlevel connector must be parked before lifting the top tray.
 upper=[id for id,r in rows.items()if r.get('level')in ['devices','external']and not id.startswith(('FRAME_','UPPER_FRAME_'))and id not in ['LEVEL_POWER_PLUG','POWER_PLUG_COLLAR']]
 fixed=[id for id in rows if id not in upper and not id.startswith('UPPER_FRAME_')and id not in ['LEVEL_POWER_PLUG','POWER_PLUG_COLLAR']]
 plug=['LEVEL_POWER_PLUG','POWER_PLUG_COLLAR'];obstacles=[id for id in rows if id not in plug and not id.startswith('UPPER_FRAME_')]
 result['power_unplug_26']=path(plug,obstacles,[(k*.5,0,0)for k in range(53)],'Power X26')
 result['rejected_park_X20']=path(plug,obstacles,[(20,0,-k*.5)for k in range(77)],'Rejected park X20')
 result['rejected_park_down38']=path(plug,obstacles,[(26,0,-k*.5)for k in range(77)],'Rejected downward park')
 result['power_park_front50']=path(plug,obstacles,[(26,-k*.5,0)for k in range(101)],'Power front50')
 # Park outside the open front before lifting; flexible leads require a real service check.
 parked={id:b.Pos(26,-50,0)*shape(id)for id in plug};lift=path(upper,fixed,[(0,0,k*.5)for k in range(121)],'Upper lift60')
 parkedhits=[]
 for k in range(121):
  for id in upper:
   lo,hi=rows[id]['bounds_mm'];q=None
   for other,z in parked.items():
    aa,az=list(z.bounding_box().min),list(z.bounding_box().max)
    if all(min(hi[j]+(k*.5 if j==2 else 0),az[j])-max(lo[j]+(k*.5 if j==2 else 0),aa[j])>1e-5 for j in range(3)):
     if q is None:q=b.Pos(0,0,k*.5)*shape(id)
     v=volume(q.intersect(z))
     if v>.02:parkedhits.append(dict(moving=id,other=other,lift_mm=k*.5,volume_mm3=v))
 lift['parked_connector_hits']=parkedhits[:20]
 if parkedhits:lift['status']='FAIL'
 result['upper_removal']=lift
 # Lower fixed connector halves: release ties before accessing PCB screws.
 header_tests=[]
 for r in d['connector_mounts']:
  if r['board']not in ['PI_A','HUB_A','INPUT_A']:continue
  id=r['id']+'_FIXED';obs=[q for q in rows if q not in [id,r['id']+'_PLUG'] and q not in upper and not q.startswith('UPPER_FRAME_')]
  # Vertical lifting to clear retaining lips; all cables remain a separate flexible acceptance.
  header_tests.append(dict(id=id,check=path([id],obs,[(0,0,k*.5)for k in range(31)],'Header '+id)))
 result['header_release']=header_tests
 result.update(revision='PI-2L-5',design_sha256=sha(ROOT/'design.json'),mechanical_sha256=sha(ROOT/'output/rest-mechanical.json'),all_211_brep_hashes_verified=True,physical_acceptance='OPEN',service_wire_slack='OPEN: X26/Y-50 parking outside front of open stack and 15mm header lift require actual flexible leads and strain relief verification; no automatic cut release.')
 save(ROOT/'output/plausibility-geometry.json',result)
 print('AUDIT SAVED',flush=True)
if __name__=='__main__':main()

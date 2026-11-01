"""Independent insertion/withdrawal checks, including full captured-nut load paths."""
import sys,math
import build123d as b
from common import ROOT,read,save,sha
from build_rest import B,C,cut,union,volume,riser
def main():
 d=read(ROOT/'design.json');m=read(ROOT/'output/rest-mechanical.json');by={r['id']:r for r in m['rows']};cache={};checks=[];hits=[]
 def load(r):
  if r['id']not in cache:cache[r['id']]=b.import_brep(r['brep']['path'])
  return cache[r['id']]
 specs=[(r['id']+'_PLUG',[1,0,0],r['id']+'_FIXED')for r in d['connector_mounts']]+[('LEVEL_POWER_PLUG',[1,0,0],'LEVEL_POWER_HEADER'),('CAN_WALL_PLUG',[0,1,0],'CAN_WALL_HEADER')]
 for id,axis,fixed in specs:
  members=[id];s=load(by[id]);collar='POWER_PLUG_COLLAR'if id=='LEVEL_POWER_PLUG'else'CAN_PLUG_COLLAR'if id=='CAN_WALL_PLUG'else None
  if collar:members.append(collar)
  for distance in [0,2,4,6,8,10]:
   moved=[b.Pos(*[v*distance for v in axis])*b.Shape.cast(load(by[member]).wrapped)for member in members];lo=[min(list(q.bounding_box().min)[k]for q in moved)for k in range(3)];hi=[max(list(q.bounding_box().max)[k]for q in moved)for k in range(3)]
   for other in m['rows']:
    if other['id']in members:continue
    aa,az=other['bounds_mm']
    if not all(min(hi[k],az[k])-max(lo[k],aa[k])>1e-5 for k in range(3)):continue
    v=sum(volume(q.intersect(load(other)))for q in moved)
    if v>.02:hits.append(dict(plug=id,distance_mm=distance,other=other['id'],overlap_mm3=round(v,5)))
  checks.append(dict(plug=id,withdrawal_mm=10,moving_members=members,checked_positions=6))
 # A lower screw is preinserted sideways before the new nut is fitted.
 rr=riser();bolt=union(C(0,0,-6.4,1.5,12),C(0,0,5.6,2.75,2.2));insert=[]
 for x in [0,1,2,3,4,5,6,7,8,9]:
  vv=volume(rr.intersect(b.Pos(x,0,0)*bolt));assert vv<1e-5,('lower screw side entry',x,vv);insert.append(x)
 nut=cut(C(0,0,15.1,5.5/math.sqrt(3),2.4,6),C(0,0,15,1.26,2.6))
 for x in [0,1,2,3,4,5,6,7,8,9]:assert volume(rr.intersect(b.Pos(x,0,0)*nut))<1e-5,('nut side entry',x)
 # A nut translated vertically must hit real roof material, reproducing the user's failure case.
 blocked=volume(rr.intersect(b.Pos(0,0,2)*nut));assert blocked>10
 assert volume(rr.intersect(C(0,0,5.7,1.48,17.5)))<1e-5,'hex tool must reach lower screw before the upper nut is fitted'
 save(ROOT/'output/rest-service-check.json',dict(status='PASS_SCOPED_NOMINAL_UNPLUG_AND_JOINT_ACCESS'if not hits else'FAIL',source_sha256=sha(ROOT/'design.json'),nominal_plug_withdrawal=checks,collisions=hits,riser_preinsert_screw_sideway=True,riser_nut_sideway=True,nut_upward_lift_blocked_mm3=blocked,tool_access='Tighten lower screws first through top channel; then insert upper nuts from outward side; then mount upper tray.',module_removal='Unplug all module interfaces; release fixed-half body ties where a header covers a mounting screw; keep tails attached to board. Then loosen four PCB screws and remove the complete board/carrier.',real_connector_fit='OPEN_PROVISIONAL_FAMILY',full_cable_bends_and_coil='OPEN_SEPARATE_REPORT'))
 print('SERVICE',len(checks),'mated pairs;',len(hits),'collisions',hits[:12],flush=True)
if __name__=='__main__':main()

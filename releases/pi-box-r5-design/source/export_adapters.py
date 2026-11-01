"""Independent printed-post and PCB/fastener checks, plus qualified adapter exports."""
import math,sys,csv
import build123d as b
import numpy as np
sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/lib/python3.12/site-packages')
import trimesh
from OCP.BRepCheck import BRepCheck_Analyzer
from common import ROOT,BASE,read,save,sha,baseline_rows
from adapters import adapter,converter
sys.path.insert(0,str(BASE/'src'))
from pi_prints import B,C,cut,union

def volume(s):return 0 if s is None else sum(x.volume for x in s.solids())
def translated(s,v):return s.moved(b.Location(v))
def main():
 p=read(ROOT/'design.json');assert read(ROOT/'output/adapter-check.json')['status']=='PASS';a=p['converter_adapter'];sh=adapter(p);conv=converter(p)
 # Independent of the generator: supplier/user top view has Y pointing DOWN.
 expected_old=[(6.1,3.2),(41.9,21.8)];expected_new=[(6.1,25-3.2),(41.9,25-21.8)]
 assert np.max(np.abs(np.asarray(expected_old)-a['old_mounts_local_cad']))<1e-8
 assert np.max(np.abs(np.asarray(expected_new)-a['board_mounts_local_cad']))<1e-8
 real_tray=read(BASE/'two-level/output/mechanical.json');row=next(r for r in real_tray['rows']if r['id']=='SUPPLY_TRAY');tray=b.import_brep(BASE/'two-level'/row['brep']['path'])
 baseparts={r['id']:r for r in baseline_rows()['rows']};bolt0=b.import_brep(baseparts['PI_U1_BOLT1']['brep']['path']);bolt0=translated(bolt0,(-26.1,-23.2,-14.6))
 # Nut and washer dimensions from the existing hardware contracts, not a new family.
 nut=cut(C(0,0,0,5.5/math.sqrt(3),2.4,6),C(0,0,-.1,1.26,2.6));washer=cut(C(0,0,0,3.5,.5),C(0,0,-.1,1.6,.7))
 insulator=cut(union(C(0,0,0,2.3,.4),C(0,0,.4,3.5,.6)),C(0,0,-.1,1.7,1.2))
 rows=[];assembly=[];hardware=[];negative=[]
 for i,y in enumerate(p['converters']['rows']):
  name='PI_U'+str(i+1)if i<3 else'HUB_U'+str(i-2);x=p['converters']['x'];ad=translated(sh,(x,y,13));cu=translated(conv,(x,y,18.6));assembly.extend([ad,cu]);local=[]
  for j,(u,v)in enumerate(expected_old,1):
   xx,yy=x+u,y+v
   ring=cut(C(xx,yy,12.9,3.3,.05),C(xx,yy,12.89,2.8,.07));actual=volume(ring.intersect(tray));assert abs(actual-ring.volume)<1e-6,'printed post not at expected source axis'
   assert volume(tray.intersect(C(xx,yy,9,1.1,3.95)))<1e-6
   shaft=translated(bolt0,(xx,yy,14.6));assert volume(shaft.intersect(ad))<1e-5;assert volume(shaft.intersect(cu))<1e-5
   assert volume(sh.intersect(C(u,v,-.01,1.6,5.7)))<1e-6
   bearing=cut(C(u,v,.2,2.8,.5),C(u,v,.19,1.8,.52));assert abs(volume(sh.intersect(bearing))-bearing.volume)<1e-6
   assembly.append(shaft);hardware.append(dict(id=name+'_TO_TRAY_'+str(j),item_id=114,reuse=True,head_z=14.6,tip_z=6.6,diameter=3,length=8,engagement_mm=6.4))
  for j,(u,v)in enumerate(expected_new,1):
   xx,yy=x+u,y+v;shaft=translated(bolt0,(xx,yy,21.7));nn=translated(nut,(xx,yy,14.1));ww=translated(washer,(xx,yy,21.2));ii=translated(insulator,(xx,yy,20.2))
   for id,body in [('screw',shaft),('washer',ww),('insulator',ii)]:
    assert volume(body.intersect(ad))<1e-5,(name,id,'adapter overlap')
    assert volume(body.intersect(cu))<1e-5,(name,id,'purchased reference overlap')
    assert volume(body.intersect(tray))<1e-5,(name,id,'existing tray overlap')
   assert volume(nn.intersect(ad))<1e-5,'nut is not seated in a real cavity'
   # The printed roof bears the nut; the whole hex cannot lift through the bore.
   roof=cut(C(u,v,3.7,2.9,.3,6),C(u,v,3.69,1.8,.32));assert abs(volume(sh.intersect(roof))-roof.volume)<1e-6
   assembly.extend([shaft,nn,ww,ii]);hardware.append(dict(id=name+'_TO_PCB_'+str(j),item_id=114,reuse=False,head_z=21.7,tip_z=13.7,length=8,nut_bottom_z=14.1,nut_top_z=16.5,full_nut_engagement_mm=2.4,washer_item_id=206,nut_item_id=100,printed_insulator=True))
  local.append(dict(printed_posts_confirmed_by_solid=True,board_two_holes_confirmed=True,fastener_no_interference=True,nut_captive_roof=True,head_tool_access='assemble adapter first, then PCB; hex keys from above',nut_access='insert from underside off tray; removal after adapter unbolted'))
  rows.append(dict(module=name,checks=local))
 # The same-diagonal mistake must fail against the independent corrected PCB.
 bad_holes=[volume(conv.intersect(C(u,v,-.1,1.4,1.8)))for u,v in expected_old]
 assert all(v>1 for v in bad_holes);negative.append(dict(case='wrong_diagonal_from_drawing_coordinates_without_Y_mapping',detected=True,pcb_overlap_mm3=bad_holes))
 negative.append(dict(case='M3x16_in_original_blind_PETG_hole',detected=14.6-16<6.6,tip_z=-1.4,allowed_tip_z=6.6));assert negative[-1]['detected']
 out=ROOT/'delivery/print';q=translated(sh,tuple(-v for v in sh.bounding_box().min));stl=out/'CONVERTER_ADAPTER.stl';mf=out/'CONVERTER_ADAPTER.3mf';step=ROOT/'delivery/cad/CONVERTER_ADAPTER.step'
 b.export_stl(q,stl,tolerance=.02,angular_tolerance=.1);mesher=b.Mesher(unit=b.Unit.MM);mesher.add_shape(q,linear_deflection=.02,angular_deflection=.1,part_number='CONVERTER_ADAPTER');mesher.write(mf);b.export_step(q,step,unit=b.Unit.MM)
 back=b.import_step(step);assert BRepCheck_Analyzer(back.wrapped).IsValid()and len(back.solids())==1 and abs(back.volume-q.volume)<1e-5
 m=trimesh.load_mesh(stl,process=True);assert m.is_watertight and m.is_winding_consistent and len(m.split())==1;assert abs(m.volume-q.volume)/q.volume<.001
 # 3MF round-trip independent of STEP: actual mesh bounds/volume and mm unit.
 import zipfile,xml.etree.ElementTree as ET
 with zipfile.ZipFile(mf)as z:
  model=ET.fromstring(z.read('3D/3dmodel.model'));assert model.attrib['unit']=='millimeter'
  ns={'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'};vs=np.asarray([[float(v.get(k))for k in ['x','y','z']]for v in model.findall('.//m:vertex',ns)])
  assert np.max(abs(vs.min(0)-m.bounds[0]))<.001 and np.max(abs(vs.max(0)-m.bounds[1]))<.001
 ip=insulator.rotate(b.Axis.X,180);ip=translated(ip,tuple(-v for v in ip.bounding_box().min));istl=out/'INSULATING_WASHER.stl';imf=out/'INSULATING_WASHER.3mf';b.export_stl(ip,istl,tolerance=.015,angular_tolerance=.1);mm=b.Mesher(unit=b.Unit.MM);mm.add_shape(ip,linear_deflection=.015,angular_deflection=.1,part_number='INSULATING_WASHER');mm.write(imf);b.export_step(ip,ROOT/'delivery/cad/INSULATING_WASHER.step',unit=b.Unit.MM)
 im=trimesh.load_mesh(istl,process=True);assert im.is_watertight and len(im.split())==1 and abs(im.volume-ip.volume)/ip.volume<.001
 # Unique leaves avoid STEP grouping identical nested child names across instances.
 leaves=[]
 for i,part in enumerate(assembly):
  for j,solid in enumerate(part.solids()):
   leaf=b.Shape.cast(solid.wrapped);leaf.label='ADAPTER_CHECK_'+str(i)+'_'+str(j);leaves.append(leaf)
 ass=ROOT/'delivery/cad/Adapter-Pruefbaugruppe.step';b.export_step(b.Compound(children=leaves,label='PI-2L-5 adapter-only verification assembly'),ass,unit=b.Unit.MM);again=b.import_step(ass);assert BRepCheck_Analyzer(again.wrapped).IsValid();assert len(again.solids())==len(leaves);assert abs(again.volume-sum(s.volume for s in leaves))<.001
 save(ROOT/'output/adapter-independent-check.json',dict(revision='PI-2L-5',status='PASS_SCOPED_ADAPTERS',source_sha256=sha(ROOT/'design.json'),five_installed_modules=rows,negative_cases=negative,hardware=hardware,stl_watertight=True,stl_one_connected_solid=True,step_3mf_readback=True,adapter_volume_mm3=sh.volume,print_translation_mm=tuple(-v for v in sh.bounding_box().min),scope='All five adapters, printed R1 support solids, nominal corrected PCB/body and exact existing fastener contracts. Real article fit, PETG thread strength and heat remain OPEN. This does not qualify pending L4 connector interfaces.',pixel_review='PENDING'))
 save(ROOT/'delivery/daten/druckteile.json',dict(revision='PI-2L-5',ready_scope='five adapters and ten insulating washers; complete interior kit OPEN',parts=[dict(id='CONVERTER_ADAPTER',quantity=5,stl=str(stl),three_mf=str(mf),sha256=sha(stl),print_orientation='flat H base down; nut pockets down; no reflection',installation='base on existing post tops13mm; PCB18.6mm',bounds_mm=m.bounds.tolist(),volume_mm3=sh.volume),dict(id='INSULATING_WASHER',quantity=10,stl=str(istl),three_mf=str(imf),sha256=sha(istl),print_orientation='wide disc down, narrow foot up; invert for mounting',installation='narrow foot to PCB, then metal washer, then M3x8 head',bounds_mm=im.bounds.tolist(),volume_mm3=ip.volume)],remaining=[dict(id='HUB_FUSE_CARRIER_1',quantity=1,status='OPEN_L4_CONNECTOR_INTEGRATION'),dict(id='HUB_FUSE_CARRIER_2',quantity=1,status='OPEN_L4_CONNECTOR_INTEGRATION'),dict(id='CAN_KEY_SHROUD',quantity=1,status='RETAINED_R2_PROTOTYPE_GEOMETRY_CONNECTOR_FIT_OPEN'),dict(id='CAN_PLUG_COLLAR',quantity=1,status='RETAINED_R2_PROTOTYPE_GEOMETRY_CONNECTOR_FIT_OPEN'),dict(id='L4_MODULE_CONNECTOR_RESTRAINTS',quantity=None,status='OPEN_LAYOUT_AND_FULL_WITHDRAWAL')]))
 with (ROOT/'delivery/daten/adapter-schrauben.csv').open('w')as f:
  w=csv.DictWriter(f,fieldnames=sorted(set().union(*(r.keys()for r in hardware))));w.writeheader();w.writerows(hardware)
 print('ADAPTER EXPORT AND INDEPENDENT CHECK PASS',len(assembly),'CAD members',round(sh.volume,2),'mm3',flush=True)
if __name__=='__main__':main()

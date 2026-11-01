"""Actual STEP/STL/3MF readbacks and full interior assembly, no external case."""
import sys,zipfile,xml.etree.ElementTree as ET,collections
import build123d as b
import numpy as np
from OCP.BRepCheck import BRepCheck_Analyzer
sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/lib/python3.12/site-packages')
import trimesh
from common import ROOT,BASE,read,save,sha
from build_rest import B,C,cut,union,volume

def export_assembly(m):
 assert m["source_sha256"]==sha(ROOT/"design.json"),"Assembly source binding changed"
 for r in m["rows"]:assert sha(r["brep"]["path"])==r["brep"]["sha256"],r["id"]
 # Add the ten new board screws, nuts, metal and insulating washers to the assembly.
 a=read(ROOT/'design.json')['converter_adapter'];leaves=[]
 for r in m['rows']:
  for j,s in enumerate(b.import_brep(r['brep']['path']).solids()):
   q=b.Shape.cast(s.wrapped);q.label=r['id']+'_'+str(j);leaves.append(q)
 for y in read(ROOT/'design.json')['converters']['rows']:
  for u,v in a['board_mounts_local_cad']:
   x=20+u;yy=y+v
   items=[union(C(x,yy,13.7,1.5,8),C(x,yy,21.7,2.75,2.2)),cut(C(x,yy,14.1,5.5/np.sqrt(3),2.4,6),C(x,yy,14,1.26,2.6)),cut(C(x,yy,21.2,3.5,.5),C(x,yy,21.1,1.6,.7)),cut(union(C(x,yy,20.2,2.3,.4),C(x,yy,20.6,3.5,.6)),C(x,yy,20.1,1.7,1.2))]
   for s in items:s.label='R5_WANDLER_HARDWARE_'+str(len(leaves));leaves.append(s)
 dst=ROOT/'delivery/cad/Innenaufbau-PI-2L-5.step';b.export_step(b.Compound(children=leaves,label='Pi Box complete inner stack R5, provisional connector family, no enclosure'),dst,unit=b.Unit.MM);back=b.import_step(dst);assert len(back.solids())==len(leaves) and BRepCheck_Analyzer(back.wrapped).IsValid();assert abs(back.volume-sum(s.volume for s in leaves))<.01
 return dict(status="PASS_CURRENT_ASSEMBLY_READBACK", source_sha256=sha(ROOT/"design.json"), mechanical_sha256=sha(ROOT/"output/rest-mechanical.json"), complete_assembly_solids=len(leaves), assembly_step_sha256=sha(dst), units="mm", valid=True, volume_difference_mm3=abs(back.volume-sum(s.volume for s in leaves)), brep_hashes_verified=len(m["rows"]))

def main():
 m=read(ROOT/'output/rest-mechanical.json');report=read(ROOT/'output/rest-geometry-check.json');assert report['status']=='PASS_SCOPED_NEW_RETENTION',report
 by={r['id']:r for r in m['rows']};inventory=read(ROOT/'checkpoints/adapter-before-height-release/delivery/daten/druckteile.json')if(ROOT/'checkpoints/adapter-before-height-release/delivery/daten/druckteile.json').exists()else read(ROOT/'delivery/daten/druckteile.json');inventory['parts']=inventory['parts'][:2]
 prints=[];checks=[]
 for p in m['new_print_instances']:
  if p['id']in {x['id']for x in prints}:continue
  sh=b.import_brep(by[p['instance']]['brep']['path']);rot=p['rotation'];sh=b.Rot(*rot)*sh;bb=sh.bounding_box();tr=[-v for v in bb.min];sh=b.Pos(*tr)*sh
  assert len(sh.solids())==1 and BRepCheck_Analyzer(sh.wrapped).IsValid(),p['id'];name=p['id'];stl=ROOT/'delivery/print'/(name+'.stl');mf=stl.with_suffix('.3mf');step=ROOT/'delivery/cad'/(name+'.step')
  b.export_step(sh,step,unit=b.Unit.MM);b.export_stl(sh,stl,tolerance=.03,angular_tolerance=.1);mesher=b.Mesher(unit=b.Unit.MM);mesher.add_shape(sh,linear_deflection=.03,angular_deflection=.1,part_number=name);mesher.write(mf)
  back=b.import_step(step);assert len(back.solids())==1 and BRepCheck_Analyzer(back.wrapped).IsValid() and abs(back.volume-sh.volume)<.001
  text=step.read_text().replace(' ','').replace('\n','');assert 'SI_UNIT(.MILLI.,.METRE.)'in text
  mesh=trimesh.load_mesh(stl,process=True);assert mesh.is_watertight and mesh.is_winding_consistent and len(mesh.split())==1,(name,'mesh');assert abs(mesh.volume-sh.volume)/sh.volume<.002
  with zipfile.ZipFile(mf)as z:
   doc=ET.fromstring(z.read('3D/3dmodel.model'));assert doc.get('unit')=='millimeter';ns={'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'};vv=np.asarray([[float(v.get(k))for k in ['x','y','z']]for v in doc.findall('.//m:vertex',ns)]);assert np.max(abs(vv.min(0)-mesh.bounds[0]))<.001 and np.max(abs(vv.max(0)-mesh.bounds[1]))<.001
  quantity=sum(1 for row in m['new_print_instances']if row['id']==name);row=dict(id=name,quantity=quantity,stl=str(stl),three_mf=str(mf),sha256=sha(stl),print_orientation=p['orientation'],print_rotation_xyz_deg=rot,print_translation_mm=tr,bounds_mm=mesh.bounds.tolist(),volume_mm3=sh.volume,support=p.get('support',False));prints.append(row);checks.append(dict(id=name,quantity=quantity,valid_closed_solid=True,step_stl_3mf_readback=True,units='mm',mesh_volume_error_mm3=abs(mesh.volume-sh.volume),sha256=sha(stl)));print('EXPORT',name,quantity,mesh.extents.round(2).tolist(),flush=True)
 inventory.update(revision='PI-2L-5',ready_scope='All currently designed rest parts, provisional manufacturer family; full USB reserve length and real fitting remain OPEN',parts=inventory['parts']+prints,remaining=[],physical_release=False)
 save(ROOT/'delivery/daten/druckteile.json',inventory)
 assembly=export_assembly(m);leaves=[None]*assembly["complete_assembly_solids"];dst=ROOT/"delivery/cad/Innenaufbau-PI-2L-5.step"
 save(ROOT/'output/rest-export-check.json',dict(status='PASS_PRINT_GEOMETRY_AND_READBACK',revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),parts=checks,complete_assembly_solids=len(leaves),assembly_step_sha256=sha(dst),physical_fit='OPEN_FAMILY_REFERENCE',slicing='PENDING',service_and_cable_scope='See separate service/routing checks; export validity alone is not full assembly acceptance'))
 print('REST EXPORTS',len(inventory['parts']),'types',sum(p['quantity']for p in inventory['parts']),'print bodies; assembly',len(leaves),'solids',flush=True)
if __name__=='__main__':
 if '--assembly-only' in sys.argv:
  m=read(ROOT/'output/rest-mechanical.json')
  for r in m['rows']:assert sha(r['brep']['path'])==r['brep']['sha256'],r['id']
  import shutil
  checkpoint=ROOT/'checkpoints/assembly-before-corrected-converter-refresh';checkpoint.mkdir(parents=True,exist_ok=True)
  for rel in ['delivery/cad/Innenaufbau-PI-2L-5.step','output/rest-export-check.json']:
   src=ROOT/rel;dst=checkpoint/src.name
   if not dst.exists():shutil.copy2(src,dst)
  result=export_assembly(m);save(ROOT/'output/assembly-export-check.json',result);print(result,flush=True)
 else:main()

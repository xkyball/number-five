"""Independent final file readback, copper/insulation regression and R1 STL datum."""
import sys,zipfile,xml.etree.ElementTree as ET
import numpy as np,build123d as b
from OCP.BRepCheck import BRepCheck_Analyzer
sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/lib/python3.12/site-packages')
import trimesh
sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/cad-python312/lib/python3.12/site-packages')
from common import ROOT,BASE,read,save,sha
from adapters import converter
from pi_prints import C,cut
def main():
 exports=[]
 for id in ['CONVERTER_ADAPTER','INSULATING_WASHER']:
  path=ROOT/'delivery/cad'/(id+'.step');s=b.import_step(path);assert BRepCheck_Analyzer(s.wrapped).IsValid()and len(s.solids())==1
  text=path.read_text();assert "SI_UNIT(.MILLI.,.METRE.)"in text.replace(' ','').replace('\n','')
  mesh=trimesh.load_mesh(ROOT/'delivery/print'/(id+'.stl'),process=True);assert mesh.is_watertight and mesh.is_winding_consistent and len(mesh.split())==1;assert abs(mesh.volume-s.volume)/s.volume<.001
  with zipfile.ZipFile(ROOT/'delivery/print'/(id+'.3mf'))as z:
   xml=ET.fromstring(z.read('3D/3dmodel.model'));assert xml.get('unit')=='millimeter';ns={'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'};vs=np.asarray([[float(v.get(k))for k in ['x','y','z']]for v in xml.findall('.//m:vertex',ns)]);assert np.max(abs(vs.min(0)-mesh.bounds[0]))<.001 and np.max(abs(vs.max(0)-mesh.bounds[1]))<.001
  exports.append(dict(id=id,step_sha256=sha(path),step_valid=True,units_mm=True,stl_connected=True,three_mf_readback=True,bounds_mm=mesh.bounds.tolist()))
 d=read(ROOT/'design.json');conv=converter(d);bare=cut(C(41.9,3.2,1.6,3.5,.5),C(41.9,3.2,1.59,1.6,.52));v=bare.intersect(conv);overlap=0 if v is None else sum(s.volume for s in v.solids());assert overlap>.1
 # Actual previously sliced STL, not a second reconstruction of the lower tray.
 src=BASE/'two-level/delivery/print/SUPPLY_TRAY.stl';r1=read(BASE/'two-level/delivery/exports.json');r=next(r for r in r1['prints']if r['id']=='SUPPLY_TRAY');assert sha(src)==r['STL_sha256'];assert r['print_rotation_xyz_deg']==[0,0,0]
 old=trimesh.load_mesh(src,process=True);shift=np.asarray(r['print_translation_mm']);probes=[]
 for y in d['converters']['rows']:
  for x0,y0 in [(6.1,3.2),(41.9,21.8)]:
   x=d['converters']['x']+x0;yy=y+y0
   probes.extend([[x+2.7,yy,12.9],[x-2.7,yy,12.9],[x,yy+2.7,12.9],[x,yy-2.7,12.9],[x,yy,12.9]])
 points=np.asarray(probes)+shift;inside=old.contains(points);expected=np.asarray([True,True,True,True,False]*10);assert np.array_equal(inside,expected)
 save(ROOT/'output/final-export-check.json',dict(revision='PI-2L-5',status='PASS_SCOPED_PRINT_SUBKIT',exports=exports,r1_stl_sha256=sha(src),r1_actual_mesh_probes=50,printed_post_axes_confirmed=True,negative_regression=dict(bare_OD7_metal_washer_intersects_nominal_copper=True,overlap_mm3=overlap,insulated_replacement_test='all ten assembled cases passed adapter-independent-check'),scope='Adapter sub-kit only; full L4 connector integration OPEN'))
 print('FINAL FILE READBACK PASS;50 R1 mesh probes;bare washer regression detected',flush=True)
if __name__=='__main__':main()

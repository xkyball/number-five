"""Independent STEP/STL/3MF readback of each retained print type."""
import sys,zipfile,xml.etree.ElementTree as ET
import build123d as b
import numpy as np
from OCP.BRepCheck import BRepCheck_Analyzer
sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/lib/python3.12/site-packages')
import trimesh
from common import ROOT,read,save,sha

def main():
 checks=[]
 for row in read(ROOT/'delivery/daten/druckteile.json')['parts']:
  id=row['id'];p=ROOT/'delivery/cad'/(id+'.step');s=b.import_step(p);mesh=trimesh.load_mesh(row['stl'],process=True);valid=BRepCheck_Analyzer(s.wrapped).IsValid();assert valid and len(s.solids())==1;assert 'SI_UNIT(.MILLI.,.METRE.)'in p.read_text().replace(' ','').replace('\n','');assert mesh.is_watertight and mesh.is_winding_consistent and len(mesh.split())==1;assert abs(mesh.volume-s.volume)/s.volume<.002
  with zipfile.ZipFile(row['three_mf'])as z:
   doc=ET.fromstring(z.read('3D/3dmodel.model'));assert doc.get('unit')=='millimeter';ns={'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'};v=np.array([[float(n.get(k))for k in ['x','y','z']]for n in doc.findall('.//m:vertex',ns)]);assert np.max(abs(np.array([v.min(0),v.max(0)])-mesh.bounds))<.001
  checks.append(dict(id=id,quantity=row['quantity'],valid_single_solid=True,units='mm',step_sha256=sha(p),stl_sha256=sha(row['stl']),three_mf_sha256=sha(row['three_mf']),volume_difference_fraction=abs(mesh.volume-s.volume)/s.volume))
 save(ROOT/'output/plausibility-export-readbacks.json',dict(status='PASS',unique_types=len(checks),body_count=sum(r['quantity']for r in checks),parts=checks));print('READBACK',len(checks),'types PASS',flush=True)
if __name__=='__main__':main()

"""Independent STL/3MF mesh validation, units and220mm build volume."""
import json,zipfile,hashlib,xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np,trimesh
R=Path(__file__).resolve().parents[1];O=R/'delivery';m=json.loads((O/'manifest.json').read_text());rows=[]
stepfile=R/'reports/functional/mechanics.json'
steps={p['id']:p for p in json.loads(stepfile.read_text())['print_steps']}if stepfile.exists()else{}
for p in m['prints']:
 stl=trimesh.load_mesh(O/'prints'/(p['id']+'.stl'),force='mesh',process=True)
 
 with zipfile.ZipFile(O/'prints'/(p['id']+'.3mf'))as z:
  model=ET.fromstring(z.read('3D/3dmodel.model'));unit=model.attrib.get('unit','millimeter')
 ns={'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'}
 objs={x.attrib['id']:x for x in model.findall('m:resources/m:object',ns)};meshes=[]
 for item in model.findall('m:build/m:item',ns):
  obj=objs[item.attrib['objectid']];mesh=obj.find('m:mesh',ns);assert mesh is not None,'Unsupported nested object'
  verts=[[float(v.attrib[k])for k in ['x','y','z']]for v in mesh.findall('m:vertices/m:vertex',ns)];faces=[[int(t.attrib[k])for k in ['v1','v2','v3']]for t in mesh.findall('m:triangles/m:triangle',ns)]
  part=trimesh.Trimesh(vertices=verts,faces=faces,process=True)
  if 'transform'in item.attrib:
   vals=list(map(float,item.attrib['transform'].split()));matrix=np.eye(4);matrix[:3,:]=np.array(vals).reshape(4,3).T;part.apply_transform(matrix)
  meshes.append(part)
 mf=trimesh.util.concatenate(meshes)
 checks=dict(stl_watertight=bool(stl.is_watertight),stl_positive_volume=bool(stl.volume>0),stl_winding=bool(stl.is_winding_consistent),threeMF_watertight=bool(mf.is_watertight),threeMF_winding=bool(mf.is_winding_consistent),threeMF_unit_mm=unit=='millimeter',bounds_match=bool(np.max(np.abs(stl.bounds-mf.bounds))<.001),volume_match=bool(abs(stl.volume-mf.volume)<max(.1,stl.volume*1e-5)),on_bed=abs(float(stl.bounds[0][2]))<.001,within_bed=bool(np.all(stl.extents<=220)))
 step=steps.get(p['id']);checks['STEP_reference_present']=step is not None
 if step:
  checks['STEP_mm']=step['explicit_mm'];checks['STEP_bounds_match']=bool(np.max(np.abs(stl.bounds-np.array(step['bounds'])))<.08);checks['STEP_volume_match']=abs(stl.volume-step['volume_mm3'])<max(.25,step['volume_mm3']*.012);checks['STEP_assembly_parity']=step['parity_status']=='PASS'
 checks={k:bool(v)for k,v in checks.items()}
 rows.append(dict(id=p['id'],status='PASS'if all(checks.values())else'FAIL',checks=checks,STL_triangles=len(stl.faces),threeMF_triangles=len(mf.faces),volume_mm3=float(stl.volume),extent_mm=stl.extents.tolist(),unit=unit))
report=dict(manifest_sha256=hashlib.sha256((O/'manifest.json').read_bytes()).hexdigest(),status='PASS'if all(r['status']=='PASS'for r in rows)else'FAIL',parts=rows,scope='Independent trimesh read of actualSTLand3MF; no healing/geometry rewriting; triangles merged for shared-vertex topology check.',print_process='Slicing, layer adhesion and support settings not tested; actual material and mechanical strength remain physical acceptance.')
(R/'reports/mesh-check.json').write_text(json.dumps(report,indent=2)+'\n');print('MESH',report['status'],len(rows),'parts')

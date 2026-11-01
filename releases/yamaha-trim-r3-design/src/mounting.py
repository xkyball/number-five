"""Mounting-hole/copper and corrected purchase-hole orientation checks."""
import json
import build123d as b
from contract import R,D,B,M,OUT,xy,components,save,sha
from cad import cyl,vol
m=json.loads((OUT/'manifest.json').read_text());pcb=b.import_brep(OUT/'cad-cache/B1_STRIPBOARD.brep');cases=[]
for mt in B['mounts']:
 x,y=mt['xy'];probe=cyl(M['board_xy'][0]+x,M['board_xy'][1]+y,M['board_z']-.1,mt['diameter']/2-.001,1.2);v=vol(pcb.intersect(probe));bad=[c['ref']+'.'+p for c in components()for p,pd in c['pads'].items()if abs(xy(pd['hole'])[0]-x)<mt['copper_clearance']/2+.5 and abs(xy(pd['hole'])[1]-y)<mt['copper_clearance']/2+.5]
 cases.append(dict(id=mt['id'],remaining_material_mm3=v,overlapping_electrical_pads=bad,status='PASS'if v<.001 and not bad else'FAIL'))
nano=next(c for c in components()if c['ref']=='U1')
expected={'16':([3,3],'D13'),'15':([9,3],'D12'),'19':([3,6],'A0'),'20':([3,7],'A1'),'22':([3,9],'A3'),'25':([3,12],'A6'),'27':([3,14],'5V'),'30':([3,17],'VIN'),'1':([9,17],'TX')}
orientation=[dict(pin=k,hole=nano['pads'][k]['hole'],name=nano['pin_names'][k],passed=nano['pads'][k]['hole']==h and nano['pin_names'][k]==name)for k,(h,name)in expected.items()]
cases.append(dict(id='NANO_ORIENTATION',status='PASS'if all(v['passed']for v in orientation)else'FAIL',checks=orientation,reference='Arduino Nano official pinout; component top view, USB at minimumY; physical clone still compare'))
bm=M['buck_mount'];proper=all(abs(25-sy-hx)<1e-6 and abs(sx-hy)<1e-6 for(sx,sy),(hx,hy)in zip(bm['corrected_source_holes'],bm['local_holes']))
cases.append(dict(id='BUCK_FRAME',status='PASS'if proper and all(q['major_radius_mm']==1.5 for q in m['fasteners'])else'FAIL',purchase_holes_unused=True,rigid_transform_determinant=1,user_corrected_holes=bm['corrected_source_holes'],no_purchase_holes_invented=True))
save(R/'reports/mounting.json',dict(status='PASS'if all(c['status']=='PASS'for c in cases)else'FAIL',manifest_sha256=sha(OUT/'manifest.json'),cases=cases,physical='Compare actual stock raster edge offset and screw heads; converter mounted by edges, not uncertain holes.'))
print('MOUNTING',[(v['id'],v['status'])for v in cases])

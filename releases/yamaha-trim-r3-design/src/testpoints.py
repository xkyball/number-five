"""Choose tinned test eyes on existing verified copper, clear for a1.2mm probe."""
import json,csv
import build123d as b
from contract import R,OUT,D,M,xy,save,sha
from cad import cyl,vol
l=json.loads((OUT/'stripboard.json').read_text());m=json.loads((OUT/'manifest.json').read_text());parts={r['id']:b.import_brep(OUT/'cad-cache'/(r['id']+'.brep'))for r in m['rows']}
pin_used={tuple(p['hole'])for p in l['pin_holes']};used=set(pin_used);endpoints={tuple(w[k])for w in l['jumpers']for k in['start','end']}
for w in l['jumpers']:
 for a,z in zip(w['path'],w['path'][1:]):
  for x in range(round(min(a[0],z[0])*2),round(max(a[0],z[0])*2)+1):
   for y in range(round(min(a[1],z[1])*2),round(max(a[1],z[1])*2)+1):used.add((x/2,y/2))

points=[]
for net in ['GND','V5','SENSOR_RAW','PINK_INT','BOAT12_PROTECTED','V5_BUCK']:
 candidates=[h for seg in l['segments']if seg['net']==net for h in seg['holes']if tuple(h)not in pin_used and(tuple(h)not in used or tuple(h)in endpoints)];found=None
 for h in sorted(candidates,key=lambda h:(min(h[0],D['board']['columns']-1-h[0]),-h[1])):
  x,y=xy(h);probe=cyl(M['board_xy'][0]+x,M['board_xy'][1]+y,M['board_z']+1.1,.6,55)
  if any(vol(probe.intersect(s))>.001 for id,s in parts.items()if id not in['B1_STRIPBOARD']and not id.startswith(('P_HOOD','HOOD_SCREW'))):continue
  found=dict(id='TP'+str(len(points)+1),net=net,hole=h,xy_mm=[x,y],access='Vertical1.2mm probe after hood removal',construction='Probe existing top jumper solder joint; no additional connection'if tuple(h)in endpoints else'Tin unused eye through to component side; no additional cross-strip connection',physical='OPEN: actual solder dome and adjacent insulated wires');used.add(tuple(h));break
 assert found,('No accessible test eye',net);points.append(found)
save(OUT/'testpoints.json',dict(source_sha256=sha(R/'inputs/design.json'),points=points));save(R/'reports/testpoints.json',dict(status='PASS',manifest_sha256=sha(OUT/'manifest.json'),points=points,scope='Existing source-verified copper segments; no component pin occupied; an existing upper jumper endpoint may double as test eye; continuous nominal vertical probe cylinder. Real solder access OPEN.'))
with(OUT/'testpoints.csv').open('w')as f:
 w=csv.DictWriter(f,fieldnames=list(points[0]));w.writeheader();w.writerows(points)
print('TESTPOINTS',[(p['id'],p['net'],p['hole'])for p in points])

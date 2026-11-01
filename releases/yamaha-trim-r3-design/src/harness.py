"""Source-bound external wiring, provisional cuts and continuous cable envelopes."""
import json,csv,math
from contract import R,D,M,OUT,components,body_spec,xy,save,sha

def rows():
 data=[]
 for i,(name,port,pins)in enumerate([('GEBER','J1',[('SENSOR_RAW','Originalgeber Signal','1'),('GND','Originalgeber Rückleiter','2')]),('YAMAHA','J2',[('PINK','Yamaha rosa','1'),('GND','Yamaha schwarz','3')]),('POWER','J3',[('FUSED12','Bordnetz+ über quellennahen1A-Sicherungshalter','1'),('GND','Bordnetz0V','4')])]):
  for j,(net,start,pin)in enumerate(pins):data.append(dict(id=name+'_'+str(j+1),net=net,start=start,end=port+'.'+pin,cable_sku='Mantech:352M0563',jacket_cut_mm=D['harness']['external_jacket_mm'][name],wire_cut_mm=D['harness']['external_jacket_mm'][name],strain_relief='PG7_'+str(i+1)+' + screwed P_CABLE_'+str(i+1),polarity='+ or signal'if j==0 else'0V return',physical='PLAN; actual cable OD/shape, cut and insertion unmeasured'))
 for i,(net,pad)in enumerate([('BOAT12_PROTECTED','IN+'),('GND','IN-'),('V5_BUCK','OUT+'),('GND','OUT-')]):data.append(dict(id='BUCK_'+str(i+1),net=net,start='U4.'+pad,end='J5.'+str(i+1),cable_sku='Communica:CAB01,22MBK',jacket_cut_mm='',wire_cut_mm=D['harness']['buck_wire_mm'][i],strain_relief='integral BUCK_LOOM_ANCHOR + solder pad service loop',polarity='printed pad marking '+pad,physical='PLAN; real pad coordinates and supplied terminal geometry unmeasured'))
 return data

def main():
 import build123d as b
 from cad import vol,cut
 from wire_space import rounded_edges
 m=json.loads((OUT/'manifest.json').read_text());parts={r['id']:b.import_brep(OUT/'cad-cache'/(r['id']+'.brep'))for r in m['rows']};h=rows()
 with(OUT/'harness.csv').open('w')as f:w=csv.DictWriter(f,fieldnames=list(h[0]));w.writeheader();w.writerows(h)
 routes=[];models=[];cableparts={}
 # Six separate conductor envelopes: nesting order avoids vertical crossings at rear.
 for i,row in enumerate(h[:6]):
  port=row['end'].split('.')[0];pin=row['end'].split('.')[1];c=next(c for c in components()if c['ref']==port);sp=body_spec(c);x=M['board_xy'][0]+xy(c['pads'][pin]['hole'])[0];end_y=M['board_xy'][1]+sp['y']+2*sp['d']+.2;end_z=M['board_z']+sp['z']+6
  entry=M['gland_x'][i//2]+(-1 if i%2==0 else 1);high=44.5-i*2
  p=[[entry,15,22],[entry,15,high],[entry,98,high],[x,98,high],[x,98,end_z],[x,end_y,end_z]]
  path,radii=rounded_edges(p,7.5);v0=[p[1][k]-p[0][k]for k in range(3)];profile=b.Wire.make_circle(.9,b.Plane(origin=p[0],z_dir=v0));s=b.Solid.sweep(profile,path);s.label=row['id'];models.append(s);cableparts[row['id']]=s
  hits=[]
  for ident,part in parts.items():
   # At its terminal the cable envelope occupies its assigned matching wire exit.
   vv=vol(s.intersect(part))
   if vv>.001:hits.append(dict(id=ident,volume_mm3=vv))
  routes.append(dict(id=row['id'],points=p,radius_mm=7.5,OD_candidate_mm=1.8,length_mm=path.length,valid=bool(s.is_valid),collisions=hits,status='FAIL'if hits or not s.is_valid else'PASS',endpoint_status='nominal connector contact/exit envelope; actual insertion depth unmeasured'))
 # The four off-board converter tails have an explicit conservative service corridor.
 # Pad locations are intentionally NOT invented as measured contacts.
 corridor=dict(id='BUCK_FOUR_TAILS',bounds=[[145,16,26],[184,89,33]],packing='four1.6mm OD conductors,2x2; local radii7.5mm; endpoint positions at actual pads OPEN',end='J5 pins1/2/3/4',known='PCB25x48; measured total height13mm; assembly placement fromdesign.json')
 mutual=[]
 for i,a in enumerate(cableparts):
  for z in list(cableparts)[i+1:]:
   v=vol(cableparts[a].intersect(cableparts[z]))
   if v>.001:mutual.append(dict(pair=[a,z],volume_mm3=v))
 b.export_step(b.Compound(label='R3 external conductor clearance envelopes',children=models),OUT/'harness-clearances.step')
 save(OUT/'harness.json',dict(revision=D['revision'],source_sha256=sha(R/'inputs/design.json'),wires=h,routes=routes,buck_corridor=corridor,mutual_collisions=mutual,cable_jacket=dict(candidate_diameter_mm=5,entry='three PG7 through fixed front wall; split saddle clamp atY8.2..11.8; mantle ends atY15',physical='round envelope only, actual twin cable section and compression must be compared'),USB_modes=dict(service='J2/J3 unplugged andJP1 removed; USB powers logic for configuration',normal='USB absent,JP1 fitted; explicit run1 can be saved for chosen cold-start autorun',live_logging='CSV firmware exists. Concurrent USB/12V operation requires verifying actual clone power paths; JP1 must be open to separate buck5V. Not claimed tested.'),physical_cuts_booked=False))
 save(R/'reports/harness-space.json',dict(status='FAIL'if mutual or any(q['status']=='FAIL'for q in routes)else'OPEN',manifest_sha256=sha(OUT/'manifest.json'),routes=routes,mutual=mutual,remaining='Converter pad positions, cable diameter/shape, terminal insertion and actual strain resistance unmeasured; four-tail corridor remains an envelope.'))
 print('HARNESS',[(r['id'],r['status'])for r in routes],'mutual',len(mutual))
if __name__=='__main__':main()

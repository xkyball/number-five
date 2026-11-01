"""R5 printable open retention and two-stage stack joints; millimetres, offscreen."""
import sys,math,copy,time
import build123d as b
from OCP.BRepCheck import BRepCheck_Analyzer
from common import ROOT,BASE,read,save,sha,baseline_rows
sys.path.insert(0,str(BASE/'src'))
from pi_prints import B,C,union,cut
def connector(n,kind):
 w=n*5.08+1.5
 if kind=='plug':return B(8,.4,2.2,20,w-.8,10)
 return cut(cut(union(B(0,0,0,13,w,12),B(0,0,0,16,w,2)),B(5,1,3,12,w-2,7)),B(8,-1,2.1,22,w+2,10.1))

def valid(sh):return len(sh.solids())==1 and BRepCheck_Analyzer(sh.wrapped).IsValid()
def volume(sh):return 0 if sh is None else sum(s.volume for s in sh.solids())

def riser():
 sh=union(B(-6,-6,0,12,12,20),B(-3,-3,19.99,6,6,3.01))
 tools=[B(-3.35,-3.35,-.1,6.7,6.7,3.3),C(0,0,-.1,1.7,23.2),
        C(0,0,5.6,3.2,2.8),B(0,-3.2,5.6,6.1,6.4,2.8),B(0,-1.8,-.1,6.1,3.6,5.7),
        C(0,0,15.1,5.8/math.sqrt(3),2.7,6),B(0,-2.95,15.1,6.1,5.9,2.7)]
 return cut(sh,*tools)

def cradle(r):
 x,y,z=r['origin'];n=r['poles'];w=n*5.08+1.5
 # Fixed half is retained only at its rear body. The removable plug and stroke stay free.
 parts=[B(x-1.5,y-2.5,z-6,18.5,w+5,2),B(x-1.5,y-2.5,z-4,16.5,2,6),B(x-1.5,y+w+.5,z-4,16.5,2,6),
        B(x-1.5,y-2.5,z-.2,1.3,3.5,2.2),B(x-1.5,y+w-1,z-.2,1.3,3.5,2.2),B(x+16.1,y-2.5,z-6,1.5,w+5,7.6)]
 parts += [B(x+6,y,z-4,8.2,1.2,4),B(x+6,y+w-1.2,z-4,8.2,1.2,4),B(x+14.2,y,z-4,1.8,w,4)]
 # Header solder-pin well remains open; two 2.5mm ties separately hold body and tails.
 sh=union(*parts)
 sh=cut(sh,B(x-1.6,y+1.2,z-6.1,15.8,w-2.4,6.2),B(x-1.6,y-.4,z-6.1,7.6,w+.8,6.2),
        B(x+2.3,y-2.6,z-3.7,3.2,w+5.2,1.2),B(x-1,y-2.6,z-3.7,3.2,w+5.2,1.2))
 return sh

def horizontal(bid,d):
 q=d['boards'][bid];ox,oy,oz=q['origin'];ww,hh,_=q['size'];floor=oz+1;mounts=[(ox+4,oy+4),(ox+ww-4,oy+4),(ox+4,oy+hh-4),(ox+ww-4,oy+hh-4)];rs=[r for r in d['connector_mounts']if r['board']==bid];deck=min(r['origin'][2]-6 for r in rs)
 parts=[C(x,y,floor,3.5,2)for x,y in mounts]
 # Open edges leave all components and top-side jumpers visible.
 for y,m in [(oy,oy+4),(oy+hh-1.6,oy+hh-4)]:
  parts.append(B(ox-3.2,y,floor,ww+3.2,1.6,2))
  for x in [ox+4,ox+ww-4]:parts.append(B(x-1.1,min(y,m),floor,2.2,abs(m-y)+1.6,2))
  parts.append(B(ox-3.2,y,floor,3.2,1.6,deck-floor+2))
 ymin=min(oy,min(r['origin'][1]-2.5 for r in rs));ymax=max(oy+hh,max(r['origin'][1]+r['poles']*5.08+4 for r in rs))
 parts.append(B(ox-3.2,ymin,deck,3.2,ymax-ymin,2))
 for r in rs:
  x,y,z=r['origin'];w=r['poles']*5.08+1.5
  for yy in [y-2.5,y+w+.5]:parts.append(B(min(ox-3.2,x-1.5),yy,z-6,max(ox,x)-min(ox-3.2,x-1.5),2,6 if bid=='GPIO_A'else z-6-deck+2))
  parts.append(cradle(r))
 sh=union(*parts);sh=cut(sh,*[C(x,y,floor-.1,1.7,3)for x,y in mounts],*[C(x,y,floor+2,3.7,deck-floor+12)for x,y in mounts])
 return sh,mounts

def fuse(bid,d,base):
 n=int(bid[2]);old=next(r for r in base['rows']if r['id']=='HUB_FUSE_CARRIER_'+str(n));sh=b.import_brep(old['brep']['path']);r=next(r for r in d['connector_mounts']if r['board']==bid);x,y,z=r['origin'];yy=109 if n==1 else 146
 # Two rising webs bridge above the flat resistor flange, below the raised body.
 pts=[(114,yy,16),(118,yy,16),(x+17,yy,32),(114,yy,32)]
 web=b.Solid.extrude(b.Face(b.Wire.make_polygon(pts,close=True)),(0,3,0))
 beam=B(114,yy,30,x+17-114,3,4)
 rear=B(x-1.5,yy,32,18.5,y-yy+2,2)
 return union(sh,web,beam,rear,cradle(r))

def hub_lift(y,front=False):
 x=168.5;z=66
 sh=union(B(162.5,y-4,z,50.5,8,2),B(162.5,y-4,z,12,8,15),B(209,y-4,z,4,8,15),B(173.5,y-4,81,37.5,8,1))
 sh=cut(sh,C(x,y,65.9,1.7,15.2),C(x,y,69,3.2,2.6),B(162.4,y-3.2,69,6.2,6.4,2.6),B(162.4,y-1.8,66,6.2,3.6,3),C(x,y,76.5,5.8/math.sqrt(3),2.7,6),B(162.4,y-2.95,76.5,6.2,5.9,2.7))
 if front:
  # Open R22 service-bow rail, not a close-fitting cable clip; tie closure is adjustable.
  ring=cut(C(143,33,72.75,25,2),C(143,33,72.65,19,2.2))
  ring=ring.intersect(B(117,7,72.7,26,52,2.2))
  extra=[ring,B(143,8,72.75,13,6,2),B(143,52,72.75,27,6,2),B(166,54,67.9,4,4.9,6.85)]
  sh=union(sh,*extra)
  sh=cut(sh,B(121,30.5,72.65,1.3,5,2.2),B(148,8.6,72.65,3.2,1.3,2.2),B(148,56.1,72.65,3.2,1.3,2.2))
 return sh

def coded_pair(key):
 w=21.82;k=3 if key=='POWER'else w-5
 r=dict(origin=[0,0,0],poles=4)
 fixed=union(cradle(r),B(14,-4,-6,2.5,2,30),B(14,-4,22.5,2.5,k+6,2),B(15,k,22.5,15,2,2))
 outer=B(17.9,-4.49,-.6,12,30.8,24.9)
 sleeve=cut(outer,B(17.8,-2.09,1.8,12.2,26,20.1),B(17.7,k-.3,21.8,12.4,2.6,3),B(21,-4.6,4,3.2,2.6,14),B(21,23.7,4,3.2,2.7,14))
 return fixed,sleeve

def qualified_adapter_rows():
 rows=list({r['id']:r for r in read(ROOT/'output/adapter-mechanical.json')['rows']if r['id'].endswith('_ADAPTER')or r['id']in ['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2']}.values())
 assert len(rows)==11  # Five converters, five printed adapters, retained USB adapter.
 for row in rows:assert sha(row['brep']['path'])==row['brep']['sha256'],('qualified adapter/converter reference changed',row['id'])
 return rows

def main():
 start=time.monotonic();d=read(ROOT/'design.json');base=baseline_rows();qualified=qualified_adapter_rows();converter_ids={'PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2'};out=ROOT/'output/brep';out.mkdir(exist_ok=True);parts={};printed=[]
 contact=read(ROOT/'delivery/daten/kontaktvertrag.json')if(ROOT/'delivery/daten/kontaktvertrag.json').exists()else read(ROOT/'inputs/l4-contact-baseline.json')
 def register(id,sh,lev,printed_=True,**extra):
  assert BRepCheck_Analyzer(sh.wrapped).IsValid()and len(sh.solids())>=1,(id,len(sh.solids()),'invalid')
  if printed_:assert len(sh.solids())==1,(id,'printed part disconnected',len(sh.solids()))
  p=out/(id+'.brep');b.export_brep(sh,p);bb=sh.bounding_box();parts[id]=dict(id=id,level=lev,printed=printed_,bounds_mm=[list(bb.min),list(bb.max)],brep=dict(path=str(p),sha256=sha(p)),volume_mm3=sh.volume,solids=len(sh.solids()),valid=True,color='#508391'if printed_ else'#52725a',**extra)
 # All upper members follow the unchanged upper tray by the same translation.
 replaced={'PI_BANK_HEADER','PI_BANK_PLUG','HUB_BANK_HEADER','HUB_BANK_PLUG','POWER_KEY_SHROUD','POWER_PLUG_COLLAR','CAN_KEY_SHROUD','CAN_PLUG_COLLAR','LEVEL_POWER_HEADER','LEVEL_POWER_PLUG','CAN_WALL_HEADER','CAN_WALL_PLUG','PG7_GLAND','PG7_LOCKNUT'}|{r['id']for r in d['connector_mounts']}
 for row in base['rows']:
  id=row['id']
  # Never overwrite corrected converter BReps with baseline bodies before
  # merging their qualified metadata. The two records share these paths.
  if id in converter_ids:continue
  if row['level']in ['case','lid','coupon']or id in ['POWER_PANEL','CAN_PANEL']or '_PANEL_SCREW'in id or id in replaced:continue
  if id.startswith('HUB_FUSE_CARRIER_'):continue
  sh=b.import_brep(row['brep']['path'])
  if row['level']in ['devices','external']and not id.startswith('FRAME_'):sh=b.Pos(0,0,20)*sh
  if id in ['HUB','HUB_POWER_PLUG']or id.startswith(('HUB_BRIDGE','EXTERNAL_USB_')):sh=b.Pos(0,0,15)*sh
  if id in contact['boards']:
   pcb=contact['boards'][id];ww,hh,tt=pcb['size'];holes=[(x,y,pcb['mounting_drill_mm'])for x,y in pcb['mounts']]+[(p['xy'][0],p['xy'][1],p['drill_mm'])for p in pcb['pads']]
   for rowno in range(pcb['rows']):
    for col in range(pcb['cols']):
     x,y=3.22+col*2.54,3.29+rowno*2.54
     if all(math.dist([x,y],mm)>4 for mm in pcb['mounts']):holes.append((x,y,1))
   sh=cut(B(0,0,0,ww,hh,tt),*[C(x,y,-.1,dd/2,tt+.2)for x,y,dd in holes]);q=d['boards'][id];sh=b.Pos(*q['origin'])*(b.Rot(90,0,0)*sh if q.get('upright')else sh)
  register(id,sh,row['level'],row['printed'],inherited_revision='PI-2L-4')
 # Keep the original frame screws and nuts as the LOWER riser joint.
 sh=riser();assert valid(sh)
 for i,(x,y)in enumerate(d['frame']['corner_axes'],1):
  rot=180 if x<100 else 0;register('STACK_RISER_'+str(i),b.Pos(x,y,40)*b.Rot(0,0,rot)*sh,'supply');printed.append(dict(id='STACK_RISER',instance='STACK_RISER_'+str(i),quantity=1,orientation='base down; open socket to bed, peg up',rotation=[0,0,0]))
  for suffix in ['', '_NUT']:
   old=parts[f'FRAME_{x}_{y}'+suffix];register('UPPER_FRAME_'+str(i)+suffix,b.Pos(0,0,20)*b.import_brep(old['brep']['path']),'devices',False,hardware=True)
 for bid in ['PI_A','HUB_A','INPUT_A','GPIO_A']:
  sh,mounts=horizontal(bid,d);id=bid+'_CONNECTOR_CARRIER';register(id,sh,'devices'if bid=='GPIO_A'else'supply');printed.append(dict(id=id,instance=id,quantity=1,orientation='flat feet down'if bid=='GPIO_A'else'left outer spine down; rotateY -90deg',rotation=[0,0,0]if bid=='GPIO_A'else[0,-90,0],support=bid=='PI_A'))
  # 2mm attachment feet require M3x10, retaining the original screw tip and PETG engagement.
  for i,(x,y)in enumerate(mounts,1):
   oldid=bid+'_BOLT'+str(i);old=b.import_brep(parts[oldid]['brep']['path']);oz=d['boards'][bid]['origin'][2];head=oz+3.5
   bolt=union(C(x,y,head-10,1.5,10),C(x,y,head,2.75,2.2));register(oldid,bolt,'devices'if bid=='GPIO_A'else'supply',False,hardware=True)
   wid=bid+'_WASHER'+str(i)
   if wid in parts:register(wid,b.Pos(0,0,2)*b.import_brep(parts[wid]['brep']['path']),parts[wid]['level'],False,hardware=True)
 for bid in ['HF1_A','HF2_A']:
  id='HUB_FUSE_CARRIER_'+bid[2];sh=fuse(bid,d,base);register(id,sh,'supply');printed.append(dict(id=id,instance=id,quantity=1,orientation='original base down; support under extended connector deck',rotation=[0,0,0],support=True))
 for i,y in [(1,62),(2,161)]:
  id='HUB_LIFT_'+str(i);register(id,hub_lift(y,i==1),'devices');printed.append(dict(id=id,instance=id,quantity=1,orientation='flat base down; support under front service-bow rail',rotation=[0,0,0],support=True))
  # Existing 8mm screw moves to the upper joint. The new lower10mm screw fully reaches the old nut.
  oldnut=next(r for r in base['rows']if r['id']==f'HUB_BRIDGE{i}_BOLT1_NUT');register(oldnut['id'],b.Pos(0,0,20)*b.import_brep(oldnut['brep']['path']),'devices',False,hardware=True)
  register('HUB_LIFT_LOWER_BOLT_'+str(i),union(C(168.5,y,59,1.5,10),C(168.5,y,69,2.75,2.2)),'devices',False,hardware=True)
  nn=cut(C(168.5,y,76.5,5.5/math.sqrt(3),2.4,6),C(168.5,y,76.4,1.26,2.6));register('HUB_LIFT_UPPER_NUT_'+str(i),nn,'devices',False,hardware=True)
 for r in d['connector_mounts']:
  # Preserve original nominal family solids. They are references, not measured article fit.
  x,y,z=r['origin'];lev='devices'if r['board']=='GPIO_A'else'supply'
  for suffix,kind in [('_FIXED','header'),('_PLUG','plug')]:register(r['id']+suffix,b.Pos(x,y,z)*connector(r['poles'],kind),lev,False,connector_id=r['id'],provisional_family=True)
 for key,origin,angle in [('POWER',[24,18,73],0),('CAN',[198,178,76],90)]:
  fixed,sleeve=coded_pair(key);transform=b.Pos(*origin)*b.Rot(0,0,angle)
  guard=transform*fixed
  if key=='POWER':
   guard=union(guard,B(16,16,63,6,27,3),B(20,16,63,4,2,6),B(20,40,63,4,3,6));guard=cut(guard,*[C(19,y,62.9,1.7,3.2)for y in [21,37]],B(15.9,15.9,62.9,1.6,1.6,3.2))
  else:
   guard=union(guard,B(166,177,70,44,2,2),B(166,170.5,66,7,8.5,3),B(203,171.5,66,7,7.5,3),B(166,177,66,7,2,6),B(203,177,66,7,2,6));guard=cut(guard,*[C(x,174,65.9,1.7,3.2)for x in [169,207]])
  for suffix,body in [('_KEY_SHROUD',guard),('_PLUG_COLLAR',transform*sleeve)]:
   id=key+suffix;register(id,body,'devices');printed.append(dict(id=id,instance=id,quantity=1,orientation='rear/open face down for collar; base down for fixed cage',rotation=[0,-90,-angle]if suffix=='_PLUG_COLLAR'else[0,0,0],support=suffix=='_KEY_SHROUD'))
  for suffix,kind in [('_HEADER','header'),('_PLUG','plug')]:register(('LEVEL_POWER'if key=='POWER'else'CAN_WALL')+suffix,transform*connector(4,kind),'devices',False,provisional_family=True)
 # Keyed roofs are intentionally different. A wrong collar has to hit the fixed rail.
 pf,pc=coded_pair('POWER');cf,cc=coded_pair('CAN')
 keycheck=dict(correct_power_mm3=volume(pf.intersect(pc)),correct_can_mm3=volume(cf.intersect(cc)),wrong_power_mm3=volume(pf.intersect(cc)),wrong_can_mm3=volume(cf.intersect(pc)))
 assert keycheck['correct_power_mm3']<1e-5 and keycheck['correct_can_mm3']<1e-5 and keycheck['wrong_power_mm3']>1 and keycheck['wrong_can_mm3']>1,keycheck
 save(ROOT/'output/coding-check.json',keycheck)
 # Merge already qualified adapter/washer/fastener replacements into the stack.
 for row in qualified:parts[row['id']]=row
 # Qualified converter hardware generated by the adapter export contains the independent pairs.
 hw=read(ROOT/'output/adapter-independent-check.json')
 save(ROOT/'output/rest-mechanical.json',dict(revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),rows=list(parts.values()),new_print_instances=printed,frame_lift_mm=20,case_excluded=True,physical_fit='OPEN_PROVISIONAL_FAMILY'))
 # Exact BRep tests for new retention against all fixed members and unplug stroke.
 newids={p['instance']for p in printed if not p.get('inherited')};cache={};hits=[];tested=0
 def load(r):
  if r['id']not in cache:cache[r['id']]=b.import_brep(r['brep']['path'])
  return cache[r['id']]
 for id in sorted(newids):
  r=parts[id]
  for other in parts.values():
   if other['id']==id or (other['id']in newids and other['id']<id):continue
   aa,az=r['bounds_mm'];ba,bz=other['bounds_mm']
   if not all(min(az[k],bz[k])-max(aa[k],ba[k])>1e-5 for k in range(3)):continue
   tested+=1;v=volume(load(r).intersect(load(other)))
   if v>.015:
    # The original M2 screws engage their existing PETG pilots. Preserve, do not call this new interference.
    if id.startswith('HUB_FUSE_CARRIER_')and other['id'].startswith('HF'+id[-1]+'_M2_'):
     old=next(q for q in base['rows']if q['id']==id);before=volume(b.import_brep(old['brep']['path']).intersect(load(other)));assert abs(before-v)<1e-5
    else:hits.append(dict(a=id,b=other['id'],mm3=round(v,5)))
 print('new printed exact collisions',hits,flush=True)
 save(ROOT/'output/rest-geometry-check.json',dict(revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),status='PASS_SCOPED_NEW_RETENTION'if not hits else'FAIL',exact_pairs_checked=tested,collisions=hits,seconds=time.monotonic()-start,full_service_check='PENDING',real_fit='OPEN_PROVISIONAL_MANUFACTURER_FAMILY'))
 print('REST GEOMETRY',len(parts),'members;',len(printed),'new printable instances',round(time.monotonic()-start,1),'s',flush=True)
if __name__=='__main__':main()

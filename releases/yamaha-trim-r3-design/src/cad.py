"""Single parameter-driven build123d source for every R3 printable part/assembly."""
import math,json,time
from pathlib import Path
import build123d as b
from OCP.OSD import OSD_ThreadPool
from bd_warehouse.fastener import SocketHeadCapScrew
from contract import R,D,B,M,OUT,components,body_spec,xy,save,sha
from wire_space import wire_shape
OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
def box(x,y,z,w,d,h):return b.Solid.make_box(w,d,h,b.Plane(origin=(x,y,z)))
def cyl(x,y,z,r,h,axis=(0,0,1)):return b.Solid.make_cylinder(r,h,b.Plane(origin=(x,y,z),z_dir=axis))
def fuse(*ss):
 q=ss[0].fuse(*ss[1:])if len(ss)>1 else ss[0]
 return b.Compound(children=list(q))if isinstance(q,b.ShapeList)else q
def cut(s,*ts):
 if isinstance(s,b.ShapeList):s=b.Compound(children=list(s))
 q=s.cut(*ts);return b.Compound(children=list(q))if isinstance(q,b.ShapeList)else q
def vol(s):return 0 if s is None else sum(abs(x.volume)for x in(s if isinstance(s,b.ShapeList)else s.solids()))
def shift(s,xyz):return s.moved(b.Location(xyz))
def build(params=None):
 p=dict(M,**(params or{}));W,dep,H,t,f=[p[k]for k in['width','depth','height','wall','floor']];ox,oy=p['board_xy'];bz=p['board_z'];bx,by=p['buck_xy'];uz=bz+11.1
 parts={};rows=[];tools=[];contacts=[];pilots=[];fasteners=[];keepers=[]
 def add(id,s,kind,color='#73929d',printed=False,name='',mate=None,datum=None):
  s.label=id;s.color=b.Color(color);bb=s.bounding_box();parts[id]=s
  rows.append(dict(id=id,kind=kind,name=name or id,printed=printed,color=color,bounds=[list(bb.min),list(bb.max)],solids=len(s.solids()),volume_mm3=vol(s),valid=bool(s.is_valid),mate=mate,datum=datum));return s
 base=box(0,0,0,W,dep,f)
 panel=cut(box(3.2,0,f,W-6.4,3,H-3.2-f),box(3.1,-.1,H-4.5,W-6.2,1.4,5))
 base=fuse(base,panel)
 for x in p['gland_x']:base=cut(base,cyl(x,-1,p['gland_z'],p['gland_hole']/2,5,(0,1,0)),cyl(x,1.6,p['gland_z'],11.7,1.5,(0,1,0)))
 n=body_spec(next(c for c in components()if c['ref']=='U1'));ux=ox+n['x']+9
 base=cut(base,box(ux-7,-1,uz-.8,14,5,8))
 hood_axes=[(6,9),(W-6,9),(6,dep-9),(W-6,dep-9)]
 # Inner raised rim overlaps the hood wall with0.3mm radial clearance.
 rim=fuse(box(t+.3,3,f,1.2,dep-6,2),box(W-t-1.5,3,f,1.2,dep-6,2),box(t+1.5,dep-t-1.5,f,W-2*t-3,1.2,2))
 for x,y in hood_axes:rim=cut(rim,box(x-4.6,y-5.4,f-.1,9.2,10.8,2.2))
 base=fuse(base,rim)
 for x in[-11,W-1]:
  for y in[15,dep-35]:
   base=fuse(base,cut(box(x,y,0,12,20,4.8),cyl(x+6,y+6,-1,2.25,7),box(x+2.5,y+13,-1,7,2.5,7)))
   tools.append(dict(id='MOUNT_'+str(x)+'_'+str(y),start=[x+6,y+6,4.9],axis=[0,0,1],diameter=8,length=40,exclude=[]))
 for x,y in hood_axes:
  base=cut(base,cyl(x,y,-1,3.3,4.6),cyl(x,y,3.4,1.7,f))
  base=fuse(base,cut(cyl(x,y,f,3.4,p['hood_gap']),cyl(x,y,f-.1,1.7,1)))
 def post(x,y,top,r=3.2):
  nonlocal base
  base=fuse(base,cyl(x,y,f,r,top-f));pilots.append(dict(x=x,y=y,z=p['blind_floor'],top=top,r=1.25,axis=[0,0,1]))
 def screw(id,x,y,seat,length=8,mate='P_BASE',member='',axis=(0,0,1),bearing=None):
  s=SocketHeadCapScrew('M3-0.5',length,'iso4762',simple=True)
  if axis==(0,0,-1):s=s.rotate(b.Axis.X,180)
  elif axis==(0,-1,0):s=s.rotate(b.Axis.X,90)
  s=s.moved(b.Location((x,y,seat))) if axis[2] else s.moved(b.Location((x,seat,y)))
  add(id,s,'hood_screw'if id.startswith('HOOD')else'usb_screw'if id.startswith('USB')else'screw','#a4b1b7',mate=mate)
  origin=[x,y,seat]if axis[2]else[x,seat,y]
  fasteners.append(dict(id=id,axis_point=origin,axis=list(axis),length_mm=length,major_radius_mm=1.5,pilot_radius_mm=1.25,mate=mate,clamped_member=member,bearing=bearing or member,blind_floor=p['blind_floor']if mate=='P_BASE'and axis[2]else None,mechanism='M3 screw directly in printed pilot; positive head bearing',position_basis='actual STEP axis and bearing measured by functional audit'))
  # The only allowed interference is the named thread cylinder, not arbitrary volumes.
  lo=[origin[k]-axis[k]*length for k in range(3)]
  contacts.append(dict(pair=sorted([id,mate]),reason='named direct screw thread zone',zone=dict(shape='cylinder',start=lo,axis=list(axis),radius=1.55,length=length+.1)))
  start=[origin[k]+axis[k]*3.1 for k in range(3)]
  tools.append(dict(id='DRIVER_'+id,start=start,axis=list(axis),diameter=5.7,length=55,exclude=[id]))
 board_axes=[];retainer_axes=[]
 for mt in B['mounts']:
  x,y=ox+mt['xy'][0],oy+mt['xy'][1];tall=mt['diameter']>5;top=bz+11.2 if tall else bz
  post(x,y,top,2.75 if tall else 3.2)
  if tall:retainer_axes.append((mt['id'],x,y,top))
  else:board_axes.append((x,y));screw('SCREW_'+mt['id'],x,y,bz+1,member='B1_STRIPBOARD')
 def keeper_frame(ref,id,axes,front_cut=0,depth=1.9):
  sp=body_spec(next(c for c in components()if c['ref']==ref));x,y,z=ox+sp['x'],oy+sp['y'],bz+11.2;w,d=sp['w'],sp['d'];gap=.15;outer=.75;overlap=.65
  ring=cut(box(x-outer,y-outer,z,w+2*outer,d+2*outer,2.4),box(x+overlap,y+overlap,z-.1,w-2*overlap,d-2*overlap,2.6))
  fence=cut(box(x-outer,y-outer,z-depth,w+2*outer,d+2*outer,depth),box(x-gap,y-gap,z-depth-.1,w+2*gap,d+2*gap,depth+.2))
  shape=fuse(ring,fence)
  if front_cut:shape=cut(shape,box(x+w/2-front_cut/2,y-outer-.1,z-depth-.1,front_cut,2,depth+2.6))
  for aid,ax,ay,az in axes:
   # Wing outside the PCB footprint; rigid side bar connects both DAC screws.
   xx=min(ax-2.75,x+w-overlap)if ax>x+w/2 else ax-2.75
   ww=max(ax+2.75,x+overlap)-xx if ax<x+w/2 else ax+2.75-xx
   wing=box(xx,min(ay-2.75,y+d-.65),z,ww,max(5.5,ay+2.75-(y+d-.65)),2.4)if ay>y+d else box(xx,ay-2.75,z,ww,5.5,2.4)
   shape=fuse(shape,wing)
   shape=cut(shape,cyl(ax,ay,z-.1,1.7,2.6));screw('SCREW_'+aid,ax,ay,z+2.4,member=id)
  add(id,shape,'module_keeper','#d49b50',True,'Verschraubter Fangrahmen '+ref)
  keepers.append(dict(member=ref,keeper=id,anchors=[a[0]for a in axes],gap_mm=gap,top_gap_mm=.1,edge_overlap_mm=overlap,mechanism='side/end fences and upper perimeter lips; frame screwed to fixed base',release='remove named screws then lift frame'))
 keeper_frame('U1','P_NANO_FRAME',[q for q in retainer_axes if q[0].startswith('NANO')],14)
 keeper_frame('U2','P_DAC_FRAME',[q for q in retainer_axes if q[0].startswith('DAC')])
 # Converter support and four external screw posts; existing module holes unused.
 buck_axes=[];bt=p['buck_z'];bzseat=bt+1.6+.15
 for x in[bx+3,bx+22]:
  for y in[by-4,by+52]:post(x,y,bzseat);buck_axes.append((x,y))
 for x in[bx+.8,bx+23]:
  for y in[by+2,by+44]:base=fuse(base,box(x,y,f,1.2,2,bt-f))
 buck_frame=fuse(box(bx-.75,by-.75,bzseat,1.4,49.5,2.4),box(bx+24.35,by-.75,bzseat,1.4,49.5,2.4))
 for y in[by-.75,by+47.35]:
  for x in[bx-.75,bx+22]:buck_frame=fuse(buck_frame,box(x,y,bzseat,3.75,1.4,2.4))
 # Side fences descend below the top lip; front/back corners stop longitudinal slide.
 for x in[bx-.75,bx+25.15]:buck_frame=fuse(buck_frame,box(x,by-.75,bt-.15,.6,49.5,1.9))
 for y in[by-.75,by+48.15]:
  for x in[bx-.75,bx+22]:buck_frame=fuse(buck_frame,box(x,y,bt-.15,3.75,.6,1.9))
 for i,(x,y)in enumerate(buck_axes,1):
  buck_frame=fuse(buck_frame,box(x-2.75,min(y-2.75,by+.1),bzseat,5.5,(by+.65-(y-2.75))if y<by else(y+2.75-(by+47.35)),2.4))if y<by else fuse(buck_frame,box(x-2.75,by+47.35,bzseat,5.5,y+2.75-(by+47.35),2.4))
  buck_frame=cut(buck_frame,cyl(x,y,bzseat-.1,1.7,2.6));screw('SCREW_BUCK_'+str(i),x,y,bzseat+2.4,member='P_BUCK_FRAME')
 buck_frame=fuse(buck_frame,box(bx-.75,by-6.75,bzseat,26.5,2.75,2.4),box(bx-.75,by+52,bzseat,26.5,2.75,2.4))
 for x,y in buck_axes:buck_frame=cut(buck_frame,cyl(x,y,bzseat-.1,1.7,2.6))
 add('P_BUCK_FRAME',buck_frame,'module_keeper','#d49b50',True,'M3-Fangrahmen Wandler; Loetpads in der Mitte frei')
 keepers.append(dict(member='U4_PCB',keeper='P_BUCK_FRAME',anchors=['SCREW_BUCK_'+str(i)for i in range(1,5)],gap_mm=.15,top_gap_mm=.15,edge_overlap_mm=.65,mechanism='screwed perimeter capture and fixed underside supports; purchase holes unused'))
 # Screw-operated cable clamps. Jaw travel0.5mm before a printed hard stop;
 # actual cable compression/friction is deliberately left to a pull test.
 cable_clamps=[]
 for i,ax in enumerate(p['gland_x'],1):
  ay=8.6;cz=p['gland_z'];rr=p['front_clamp_jacket_diameter_mm']/2
  lower=cut(box(ax-5,8.2,f,10,3.6,cz-.7-f),cyl(ax,8.1,cz,rr,3.8,(0,1,0)))
  base=fuse(base,lower)
  cap=fuse(box(ax-17.7,8.2,cz,35.4,3.6,5),cyl(ax-14.5,ay,cz,3.2,5),cyl(ax+14.5,ay,cz,3.2,5))
  cap=cut(cap,cyl(ax,8.1,cz,rr,3.8,(0,1,0)))
  for j,x in enumerate([ax-14.5,ax+14.5],1):
   post(x,ay,cz-.5)
   cap=cut(cap,cyl(x,ay,cz-.1,1.7,5.2));screw('SCREW_CABLE_'+str(i)+'_'+str(j),x,ay,cz+5,length=10,member='P_CABLE_'+str(i))
  add('P_CABLE_'+str(i),cap,'cable_clamp','#d49b50',True,'Verschraubte Kabelzugentlastung '+str(i)+';0.5mm Spannweg')
  cable_clamps.append(dict(id='P_CABLE_'+str(i),center=[ax,10,cz],diameter_candidate_mm=2*rr,mechanism='twoM3x10 into fixed base, split cable saddle,0.5mm jaw travel before hard stop',physical='OPEN actual jacket shape/diameter and pull resistance'))
 # Small existing tie is optional for bundling converter tails, not module retention.
 ax,ay=151,77
 base=fuse(base,cut(box(ax-3.5,ay,f,7,3,6),box(ax-2.6,ay-.2,f+2,5.2,3.4,1.8)))
 cable_clamps.append(dict(id='BUCK_LOOM_ANCHOR',xy=[ax,ay],mechanism='integral optional cable-tie slot; converter itself screw-captured'))
 # Board and the exact placed purchase bodies.
 board=box(ox,oy,bz,*B['outline']);holes=[cyl(ox+xy([x,y])[0],oy+xy([x,y])[1],bz-.1,.5,1.2)for x in range(B['columns'])for y in range(B['rows'])]+[cyl(ox+m['xy'][0],oy+m['xy'][1],bz-.1,m['diameter']/2,1.2)for m in B['mounts']]
 board=cut(board,*holes);add('B1_STRIPBOARD',board,'board','#ae996c',name='135x65x1 Streifenraster, Zuschnitt aus145x65')
 for c in components():
  sp=body_spec(c);x,y,z=ox+sp['x'],oy+sp['y'],bz+sp['z'];w,d,h=sp['w'],sp['d'],sp['h'];ref,kind=c['ref'],c['kind']
  if kind=='npn'or(kind=='capacitor'and c.get('polarized')):s=cyl(x+w/2,y+d/2,z,min(w,d)/2,h)
  elif kind in['resistor','diode','tvs']:
   s=cyl(x,y+d/2,z+h/2,h/2,w,(1,0,0))if w>d else cyl(x+w/2,y,z+h/2,h/2,d,(0,1,0))
  else:s=box(x,y,z,w,d,h)
  add(ref,s,'component',sp['color'],name=c['value'],datum=dict(ref=ref,source='inputs/design.json',nominal=True))
  if ref=='U1':
   add('U1_USB_C',box(x+5,y-.8,z+1.6,8,7.5,3.4),'component','#b3bfc4');add('U1_MCU',box(x+5,y+16,z+1.6,8,8,1.2),'component','#263b45')
   for i,col in enumerate([3,9],1):
    xx,yy=xy([col,3]);add('U1_SOCKET_'+str(i),box(ox+xx-1.25,oy+yy-1.27,bz+1,2.5,38.1,8.5),'socket','#30464f')
  if ref=='U2':
   xx,yy=xy([14,2]);add('U2_SOCKET',box(ox+xx-1.25,oy+yy-1.27,bz+1,2.5,15.24,8.5),'socket','#30464f');add('U2_IC',box(x+5,y+5,z+1.6,3,3,1),'component','#263b45')
  if kind=='connector':
   # A single perforated comb is captured by the header pins. Its low web
   #leaves upper wire access; two side rails set the2mm standoff.
   cy=(y+y+d)/2
   spacer=fuse(box(x,y,bz+1,1,d,2),box(x+w-1,y,bz+1,1,d,2),box(x,cy-.8,bz+1,w,1.6,.6))
   for pd in c['pads'].values():
    px,py=xy(pd['hole']);spacer=cut(spacer,cyl(ox+px,oy+py,bz+.9,.6,.8))
   add('P_SPACER_'+ref,spacer,'header_spacer','#6f9d9e',True,'2mm-Rahmen '+ref+'; Stiftlaenge real pruefen')
   my,mz=y+d+.2,z+2;mate=box(x,my,mz,w,d,8);add(ref+'_MATE',mate,'connector_mate','#6e99a8')
   roofz=mz+8+.3;anchor_y=oy+B['outline'][1]+15;axes=[(x-4.5,anchor_y),(x+w+4.5,anchor_y)]
   keeper=fuse(box(x-1.1,my-.2,roofz,w+2.2,anchor_y+2.75-my+.2,2.4),box(x-1.1,my-.2,mz-.2,1.1,d+.4,8.5),box(x+w,my-.2,mz-.2,1.1,d+.4,8.5),box(x-1.1,my+d+.2,mz-.2,w+2.2,1,8.5))
   # Lower lips keep the coded shoe with the plug; an adjustment screw provides
   #0.5mm travel before head seating instead of the R2 zero-travel clamp.
   keeper=fuse(keeper,box(x-1.1,my,mz-1.2,1.8,d,1),box(x+w-.7,my,mz-1.2,1.8,d,1))
   kx,kyc=x+w/2,my+d-.7;clampseat=mz+16
   keeper=fuse(keeper,cyl(kx,kyc,roofz,3,clampseat-.5-roofz))
   keeper=cut(keeper,cyl(kx,kyc,mz+7.5,1.25,9))
   screw('SCREW_PLUG_'+ref,kx,kyc,clampseat,mate='P_KEY_'+ref,member=ref+'_MATE')
   fasteners[-1].update(mechanism='adjustment_clamp',head_clearance_mm=.5,tip_member=ref+'_MATE',bearing=None)
   # Screwdriver holes above plug terminals and discrete cable exits through stop.
   for pin,pd in c['pads'].items():
    px=ox+xy(pd['hole'])[0];keeper=cut(keeper,cyl(px,my+3,roofz-.1,1.8,2.6),cyl(px,my+d,mz+4,1.2,2,(0,1,0)))
   # Different raised key positions mate only with the matching base fin.
   key=p['connector_keys'][ref]
   for j,(ax,ay)in enumerate(axes,1):
    post(ax,ay,roofz)
    wx=ax-2.75 if ax<x else x+w-.5;end=x+.5 if ax<x else ax+2.75
    keeper=fuse(keeper,box(wx,ay-2.75,roofz,end-wx,5.5,2.4))
    keeper=cut(keeper,cyl(ax,ay,roofz-.1,1.7,2.6));screw('SCREW_KEY_'+ref+'_'+str(j),ax,ay,roofz+2.4,member='P_KEY_'+ref)
   # Fixed polarizing fin and matching slot are spatially separate from screws.
   fx=x+w/2+key;fy=anchor_y-1;keeper=cut(keeper,box(fx-.6,my+3,mz-1.3,1.2,anchor_y-my,roofz+2.6-(mz-1.3)));base=fuse(base,box(fx-.4,fy,f,.8,2,roofz+1.2-f))
   add('P_KEY_'+ref,keeper,'key_collar','#d6a454',True,'Verschraubte feste Steckersicherung '+ref)
   keepers.append(dict(member=ref+'_MATE',keeper='P_KEY_'+ref,anchors=['SCREW_KEY_'+ref+'_'+str(i)for i in[1,2]],gap_mm=.2,top_gap_mm=.3,mechanism='coded plug shoe with lower lips and adjustable clamp; TWO additional M3 screws anchor shoe to fixed base',release='leave plug clamp tight; remove TWO base screws, withdraw coded shoe+plug10mm rearward, then lift15mm',key_offset_mm=key))
 # Converter geometry preserves user-corrected purchase holes without using them.
 hole_axes=[(bx+hx,by+hy)for hx,hy in p['buck_mount']['local_holes']]
 add('U4_PCB',cut(box(bx,by,bt,25,48,1.6),*[cyl(x,y,bt-.1,p['buck_mount']['diameter']/2,1.8)for x,y in hole_axes]),'component','#2f8274',name='XL6009AUD; actual height13mm, purchase holes unused')
 for i,(cx,cy)in enumerate([(bx+12.5,by+6.5),(bx+12.5,by+40.5)],1):add('U4_CAP_'+str(i),cyl(cx,cy,bt+1.6,4,11.4),'component','#435d68')
 add('U4_INDUCTOR',box(bx+7,by+17,bt+1.6,12,12,10),'component','#6d7776');add('U4_TRIMMER',box(bx+1.8,by+34,bt+1.6,6,8,8),'component','#4c83a7')
 old=R/'inputs/purchased'
 for i,x in enumerate(p['gland_x'],1):
  oldid='K3'if i==3 else'K1';body=b.import_brep(old/(oldid+'_GLAND.brep'));nut=b.import_brep(old/(oldid+'_NUT.brep'))
  if i!=3:
   body=shift(body,(0,-36,-24.8)).rotate(b.Axis.Z,90);nut=shift(nut,(0,-36,-24.8)).rotate(b.Axis.Z,90)
  else:body=shift(body,(-30,0,-24.8));nut=shift(nut,(-30,0,-24.8))
  add('GLAND_'+str(i),shift(body,(x,0,p['gland_z'])),'gland','#4e6771');add('GLAND_NUT_'+str(i),shift(nut,(x,0,p['gland_z'])),'gland_nut','#6b7d84')
 # USB cap sits flush; integral spigot overlaps opening, with no missing gasket.
 cap=fuse(box(ux-16,-3,uz-2,32,3,10.5),box(ux-6.5,0,uz-.3,13,1.5,7))
 for i,x in enumerate([ux-12,ux+12],1):
  cap=cut(cap,cyl(x,-3.1,uz+3,1.7,3.3,(0,1,0)));base=fuse(base,cyl(x,0,uz+3,3.5,7,(0,1,0)));base=cut(base,cyl(x,-.1,uz+3,1.25,6.1,(0,1,0)))
  screw('USB_SCREW_'+str(i),x,uz+3,-3,mate='P_BASE',member='P_USB_CAP',axis=(0,-1,0))
 add('P_USB_CAP',cap,'usb_cap','#d49b50',True,'USB-Serviceverschluss mit Ueberlappung, ohne Dichtung')
 # Crucial: blind holes are subtracted AFTER every base union, so floor cannot refill them.
 for q in pilots:base=cut(base,cyl(q['x'],q['y'],q['z'],q['r'],q['top']-q['z']+.1))
 add('P_BASE',base,'base','#9fb9c3',True,'Offener Schraubtraeger, feste Anschluesse, aeussere Laschen')
 bottom=f+p['hood_gap'];roof=H-2.4
 hood=fuse(box(0,0,roof,W,dep,2.4),box(0,0,bottom,t,dep,roof-bottom),box(W-t,0,bottom,t,dep,roof-bottom),box(t,dep-t,bottom,W-2*t,t,roof-bottom),box(t,0,H-4.2,W-2*t,1,1.8))
 for i,(x,y)in enumerate(hood_axes,1):
  xx=0 if x<W/2 else W-8.4;hood=fuse(hood,cyl(x,y,bottom,4.2,roof-bottom),box(xx,y-3,bottom,8.4,6,roof-bottom));hood=cut(hood,cyl(x,y,bottom-.1,1.25,15));screw('HOOD_SCREW_'+str(i),x,y,3.6,length=12,mate='P_HOOD',member='P_BASE',axis=(0,0,-1))
 add('P_HOOD',hood,'hood','#b8cbd2',True,'Haube mit ueberlappender Schutzkante; vier M3 von unten; keine Wasserdichtheitszusage')
 # Full continuous bridge envelopes, kept as their own physical model members.
 layout=json.loads((OUT/'stripboard.json').read_text())
 for w in layout['jumpers']:
  shape,length,radii=wire_shape(w,(ox,oy,bz));add(w['id'],shape,'wire','#a76d45',name=w['id']+' '+w['net'],datum=dict(net=w['net'],wire_source='delivery/stripboard.json',layer=w['layer']))
 spaces=[dict(id='USB_PLUG',bounds=[[ux-6.5,-30,uz-.3],[ux+6.5,oy+n['y']-.8,uz+7.2]],kind='service plug cap removed, J2/J3/JP1 unplugged'),dict(id='BUCK_RESERVED',bounds=[[bx-.2,by-.2,bt],[bx+25.2,by+48.2,bt+18]],kind='purchase height reserve'),dict(id='UPPER_HARNESS',bounds=[[14,78,32],[W-14,88,43]],kind='upper rear harness: explicit lanes/turns in harness.json; actual OD/pad positions OPEN')]
 tools.append(dict(id='BUCK_TRIMMER_DRIVER',start=[bx+4.8,by+38,bt+9.7],axis=[0,0,1],diameter=3,length=H+15,exclude=[]))
 return parts,rows,dict(parameters=p,tools=tools,expected_contacts=contacts,spaces=spaces,board_axes=board_axes,buck_axes=buck_axes,buck_purchase_hole_axes=hole_axes,retainer_axes=retainer_axes,hood_axes=hood_axes,usb_center=[ux,uz],fasteners=fasteners,keepers=keepers,pilots=pilots,cable_anchors=cable_clamps)
def view_offset(row,view,p):
 if view=='closed':return(0,0,0)
 if row['kind']=='hood':return(0,0,65 if view=='open'else 95)
 if row['kind']=='hood_screw':return(0,0,-22)
 if view=='exploded'and row['kind']in['component','socket','board','connector_mate','module_keeper','header_spacer','key_collar','cable_clamp','wire']:return(0,0,30)
 return(0,0,0)
def main():
 start=time.monotonic();OUT.mkdir(exist_ok=True);(OUT/'cad-cache').mkdir(exist_ok=True);(OUT/'prints').mkdir(exist_ok=True)
 parts,rows,extra=build()
 wanted={r['id']for r in rows};printed={r['id']for r in rows if r['printed']}
 for stale in (OUT/'cad-cache').glob('*.brep'):
  if stale.stem not in wanted:stale.unlink()
 for stale in (OUT/'prints').iterdir():
  if stale.suffix in['.step','.stl','.3mf']and stale.stem not in printed:stale.unlink()
 print('BUILT',len(rows),'in',round(time.monotonic()-start,1),flush=True)
 for row in rows:
  id=row['id'];path=OUT/'cad-cache'/(id+'.brep');b.export_brep(parts[id],path);row['brep_sha256']=sha(path)
 exports=[]
 for view in ['closed','open','exploded']:
  children=[]
  for row in rows:
   s=shift(parts[row['id']],view_offset(row,view,extra['parameters']));s.label=row['id'];s.color=b.Color(row['color']);children.append(s)
  path=OUT/(view+'.step');b.export_step(b.Compound(label=D['revision']+'_'+view,children=children),path)
  q=b.import_step(path);wanted=sum(r['volume_mm3']for r in rows);delta=abs(vol(q)-wanted)
  exports.append(dict(view=view,valid=bool(q.is_valid),solids=len(q.solids()),expected_solids=sum(r['solids']for r in rows),volume_delta_mm3=delta,status='PASS'if q.is_valid and delta<.05 and len(q.solids())==sum(r['solids']for r in rows)else'FAIL',sha256=sha(path)))
  print('STEP',view,exports[-1]['status'],flush=True)
 prints=[]
 for row in rows:
  if not row['printed']:continue
  id=row['id'];s=parts[id]
  if row['kind']=='hood':s=s.rotate(b.Axis.X,180)
  if row['kind']=='usb_cap':s=s.rotate(b.Axis.X,90)
  bb=s.bounding_box();s=shift(s,tuple(-v for v in bb.min));bb=s.bounding_box()
  for ext in ['step','stl','3mf']:
   path=OUT/'prints'/(id+'.'+ext)
   if ext=='step':b.export_step(s,path)
   elif ext=='stl':b.export_stl(s,path,tolerance=.04,angular_tolerance=.15)
   else:mm=b.Mesher();mm.add_shape(s,linear_deflection=.04,angular_deflection=.15);mm.write(path)
  prints.append(dict(id=id,name=row['name'],units='mm',quantity=1,orientation='roof on bed; X180'if row['kind']=='hood'else'outer face on bed; X90'if row['kind']=='usb_cap'else'flat underside on bed',bounds_mm=list(bb.size),within_220mm=all(v<=220 for v in bb.size),on_bed=abs(bb.min.Z)<1e-6,sha256={ext:sha(OUT/'prints'/(id+'.'+ext))for ext in['step','stl','3mf']}))
 save(OUT/'manifest.json',dict(schema='trim.open-carrier.manifest/2',revision=D['revision'],units='mm',rows=rows,prints=prints,exports=exports,**extra,source_sha256={str(p.relative_to(R)):sha(p)for p in [R/'src/cad.py',R/'src/contract.py',R/'inputs/design.json']},old_purchase_model_sha256={str(p.name):sha(p)for p in (R/'inputs/purchased').glob('K[13]_*.brep')},build_seconds=round(time.monotonic()-start,2),wire_representation=str(len(json.loads((OUT/'stripboard.json').read_text())['jumpers']))+' source-derived circular bridge sweeps; separate external harness envelopes; purchase leads not measured',real_acceptance=False))
 print('EXPORTED',len(prints),'prints',round(time.monotonic()-start,1),flush=True)
if __name__=='__main__':main()

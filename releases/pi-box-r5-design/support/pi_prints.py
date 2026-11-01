"""Direct build123d P4.3 print recipes; dimensions in millimetres.

Only project mechanical contracts are read. No CSG/SCAD evaluator or Fusion SDK.
The interior datum is (3,3): wall changes add material outside the fixed cavity.
"""
import math,re
import numpy as np
from scipy.spatial.transform import Rotation
import build123d as b

def shape(s):return b.Compound(children=list(s))if isinstance(s,b.ShapeList)else s
def B(x,y,z,w,l,h):return b.Solid.make_box(w,l,h,b.Plane(origin=(x,y,z)))
def C(x,y,z,r,h,n=0,r2=None,axis=(0,0,1)):
 if n:
  p=b.RegularPolygon(r,n);return b.extrude(p,amount=h).moved(b.Location((x,y,z)))
 return b.Solid.make_cylinder(r,h,b.Plane(origin=(x,y,z),z_dir=axis))if r2 is None or r==r2 else b.Solid.make_cone(r,r2,h,b.Plane(origin=(x,y,z),z_dir=axis))
def union(*items):
 items=[shape(x)for x in items if x is not None];return shape(items[0].fuse(*items[1:]))if len(items)>1 else items[0]
def cut(s,*tools):return shape(s.cut(*tools))if tools else s
def move(s,v):return s.moved(b.Location(tuple(v)))
def matrix(s,m):
 m=np.array(m,float);return s.moved(b.Location(tuple(m[:3,3]),tuple(Rotation.from_matrix(m[:3,:3]).as_euler('xyz',degrees=True)),b.Extrinsic.XYZ))
def T(xyz=(0,0,0),rx=0,ry=0,rz=0):
 m=np.eye(4);m[:3,:3]=Rotation.from_euler('xyz',[rx,ry,rz],degrees=True).as_matrix();m[:3,3]=xyz;return m

def hull_rects(a,c,z,h):
 edges=list(b.Wire.make_polygon([(a[0],a[1],z),(a[0]+a[2],a[1],z),(a[0]+a[2],a[1]+a[3],z),(a[0],a[1]+a[3],z)]).edges())
 edges+=list(b.Wire.make_polygon([(c[0],c[1],z),(c[0]+c[2],c[1],z),(c[0]+c[2],c[1]+c[3],z),(c[0],c[1]+c[3],z)]).edges())
 return b.extrude(b.make_hull(edges),amount=h)

def frame(d):
 f=d['stack_frame'];parts=[C(x,y,3,4.75,76)for x,y in f['post_axes_mm']];cuts=[]
 for level,key in [(44,'middle_mounts_mm'),(79,'top_mounts_mm')]:
  for (px,py),(x,y)in zip(f['post_axes_mm'],f[key]):
   by=3 if y<30 else min(166.5,y+3);reach=math.hypot(px-x,py-y);z0=max(12,level-6-reach)
   wa=b.Wire.make_rect(6,2,b.Plane(origin=(px,by+1,z0)));wb=b.Wire.make_rect(10,2,b.Plane(origin=(x,by+1,level-5)))
   parts.append(b.Solid.make_loft([wa,wb]));low=max(3,min(y-5.5,157.5));height=min(11,169-low)
   circle=b.Wire.make_circle(4.75,b.Plane(origin=(px,py,level-6)));rect=b.Wire.make_rect(11,height,b.Plane(origin=(x,low+height/2,level-6)))
   parts.append(b.extrude(b.make_hull(list(circle.edges())+list(rect.edges())),amount=6))
   cuts.extend([C(x,y,level-7,1.7,9),C(x,y,level-4.3,3.35,2.6,6),B(x-2.9,y-8,level-4.3,5.8,8,2.6)])
 for lock,(px,py)in zip(f['retaining_wall_screws'],f['post_axes_mm']):
  x,y,z=lock['xyz'];ny=162 if lock['rear']else 10
  parts.append(hull_rects([px-4.75,py-4.75,9.5,9.5],[x-4.75,ny-4.5,9.5,9],3,12))
  cuts.extend([C(x,ny+5,z,1.7,13,axis=(0,-1,0)),matrix(C(0,0,0,3.35,2.6,6),T([x,ny+1.3,z],rx=90)),B(x,ny-1.3,z-2.9,6,2.6,5.8)])
 for x,y in d['mounting_xy_mm']:cuts.append(B(x-8.4,y-8.4,2.9,16.8,16.8,2.6))
 for lock in f['retaining_wall_screws']:
  x,y,z=lock['xyz'];yy=d['box_mm'][1]-6.4 if lock['rear']else-1;cuts.append(B(x-5.4,yy,2.9,10.8,7.4,9.5))
 return parts,cuts

def base(d,p):
 w=p['wall'];ix=p['inside_x'];iy=p['inside_y'];H=p['inside_z']+6;lo=3-w;W=ix+3+w;D=iy+3+w
 positive=[cut(B(lo,lo,0,ix+2*w,iy+2*w,H-3),B(3,3,3,ix,iy,H))];tools=[]
 for x,y in d['mounting_xy_mm']:
  positive.append(B(x-8,y-8,3,16,16,2));tools.extend([C(x,y,-1,2.25,7),C(x,y,2.85,2.25,2.15,r2=4.4)])
 for x,y in d['input_clamp']['screw_axes_xy_mm']:
  top=d['input_clamp']['post_top_z_mm'];positive.append(C(x,y,3,2.8,top-3));tools.append(C(x,y,top-7.5,1.25,8.5))
 tools.extend([B(W-w-1,71,82.5,w+2,80,20),B(W-w-1,13.5,109.7,w+2,21,17.8)])
 for y,z in d['can_cartridge']['wall_bolt_axes_yz_mm']:tools.append(C(W-w-1,y,z,1.7,w+3,axis=(1,0,0)))
 tools.append(C(148,D+1,25,9.25,w+3,axis=(0,-1,0)))
 for x,z in d['printed_power_entry']['wall_fastener_axes_xz_mm']:tools.append(C(x,D+1,z,1.7,w+3,axis=(0,-1,0)))
 for xx in [lo,W-w]:
  for yy in range(24,int(D)-20,12):
   for zz in [17,47,87,119]:tools.append(B(xx-1,yy,zz,w+2,3,8))
 for x,y in d['lid_mounts_mm']:
  left=x<20;count=8 if left else 9
  for k in range(count):
   zz=H-10-count+k
   positive.append(B(x-5,3,zz,10,k+1,1)if y==8 else B(x-5,iy+2-k,zz,10,k+1,1)if y==d['box_mm'][1]-8 else B(3,y-5,zz,k+1,10,1)if left else B(ix+2-k,y-5,zz,k+1,10,1))
  positive.append(B(x-5,3,H-10,10,9,7)if y==8 else B(x-5,iy-6,H-10,10,9,7)if y==d['box_mm'][1]-8 else B(3,y-5,H-10,8,10,7)if left else B(ix-6,y-5,H-10,9,10,7))
  tools.extend([C(x,y,H-12,1.7,12),C(x,y,H-8,3.35,7,6)])
 for lock in d['stack_frame']['retaining_wall_screws']:
  x,y,z=lock['xyz'];rear=lock['rear'];yy=iy if rear else lo
  positive.append(B(x-5,yy,3,10,w+3,9));tools.extend([C(x,D+1 if rear else 7,z,1.7,w+5,axis=(0,-1,0)),C(x,D+1 if rear else lo+2.2,z,3.1,3.2,axis=(0,-1,0))])
 tools.append(B(10.1,49.3,42,1.8,5.4,100))
 # Four external M4 eyes; source external mount design retained, grows outward.
 for xx in [12,ix-6]:
  for yy,axis in [(lo,(0,-1)),(D,(0,1))]:
   if axis[1]<0:tab=B(xx-7,yy-17,0,14,18,4);cy=yy-10
   else:tab=B(xx-7,yy-1,0,14,18,4);cy=yy+10
   positive.append(tab);tools.append(C(xx,cy,-1,2.25,6))
 return cut(union(*positive),*tools)

def lid(d,p):
 w=p['wall'];lo=3-w;W=p['inside_x']+3+w;D=p['inside_y']+3+w;H=p['inside_z']+6
 s=B(lo,lo,H-3,p['inside_x']+2*w,p['inside_y']+2*w,3);tools=[C(x,y,H-4,1.7,5)for x,y in d['lid_mounts_mm']]
 for xx in range(20,int(W)-20,10):
  for yy in [25,60,100,135]:tools.append(B(xx,D-yy-15,H-4,3,15,5))
 s=cut(s,*tools)
 for x,y,ww,ll in [(100,p['inside_y']+1,40,1.5),(100,3.5,35,1.5),(3.5,p['inside_y']-44,1.5,25),(p['inside_x']+1,42,1.5,25)]:s=union(s,B(x,y,H-5,ww,ll,2))
 return cut(s,B(177.2,18.8,134.8,5.4,10.4,1.2))

def rail_cover(r):
 high=r['cover_part']=='rail-cover-high';height=24.5 if high else 17.5;length=r.get('bar_length_mm',78);offset=r.get('bar_offset_x_mm',0);end=offset+length+.2+6
 s=union(B(0,0,0,90,10,1.5),B(0,0,1.5,offset+5.8,10,height-1.5),B(end,0,1.5,90-end,10,height-1.5))
 return matrix(cut(s,*[C(x,5,height-9,1.25,10)for x in [3,87]]),r['cover_transform'])

def can_holder(d):
 s=union(cut(B(185,10,101.5,2,28,34.5),B(184.9,13.5,111.7,2.3,21,15.6)),B(180.5,20,103,1.5,8,31.5),B(165.84,13.84,106.5,16.16,22,1.5),B(165.84,13.84,106.5,1.6,22,28),B(180.5,14,110,6.5,20,1.4),B(165.84,13.84,128,16.16,22,1.5),*[B(165.84,y,106.5,5,1.64,23)for y in [12.2,35.84]])
 for y,z in d['can_cartridge']['wall_bolt_axes_yz_mm']:
  s=union(s,C(177.8,y,z,4.6,4.2,axis=(1,0,0)));s=cut(s,C(177,y,z,1.7,11,axis=(1,0,0)),matrix(C(0,0,0,3.35,2.6,6),T([178,y,z],ry=90)),B(177.9,y,z-2.9,2.8,6,5.8))
 for x,y in d['can_cartridge']['pcb_bolt_axes_xy_mm']:s=union(s,C(x,y,108,3.8,5.4))
 tools=[C(x,y,105,1.25,9.5)for x,y in d['can_cartridge']['pcb_bolt_axes_xy_mm']]+[C(176,y,105,1.65,5)for y in [16.38,21.46,26.54,31.62]]+[B(173.5,13.5,114.7,14,21,12.6),B(165.7,13.7,113.35,15.6,22.3,1.8),B(160,0,100,30,13.2,40)]+[C(x,y,114.95,3.7,3.2)for x,y in d['can_cartridge']['pcb_bolt_axes_xy_mm']]
 return move(cut(s,*tools),d.get('candidate_wall_shift_mm',[0,0,0]))

def power_entry(d,name):
 if name=='PWR_ENTRY_SHOE':return B(141.2,156.4,29,*d['printed_power_entry']['shoe_print_size_mm'])
 if name=='CL_IN':
  s=union(cut(B(0,0,0,24,10,7.5),B(5,-1,-1,14,12,6.5)),*[B(5,y,4.5,14,.8,1)for y in [0,9.2]])
  return move(cut(s,*[C(x,5,-1,1.7,10)for x in [3,21]]),d['input_clamp']['origin_mm'])
 if name=='PG7':
  s=union(B(0,0,0,24,38,2.5),C(12,19,2.4,9,3.1));tools=[C(12,19,-.1,7,5.7),C(12,19,-.05,7.8,.85,r2=7),C(12,19,4.7,7,.85,r2=7.8)]+[C(12,y,-.1,1.7,2.7)for y in [5,33]]
 else:
  s=B(0,0,0,24,38,3);tools=[C(12,19,-.1,7,3.2)]
  for y in [5,33]:tools.extend([C(12,y,-.1,1.7,3.2),C(12,y,.4,3.35,2.6,6),B(12,y-2.9,.4,12.1,5.8,2.6)])
 return matrix(cut(s,*tools),next(o for o in d['objects']if o['id']==name)['matrix'])

def tray(d,name,p):
 z=d['stage_z_mm'][name];W=p['inside_x']+6;D=p['inside_y']+6
 print('tray-start',name,flush=True)
 tr=B(5,5,z,W-10,D-10,3);positive=[];tools=[]
 notches=[(-2,9,10,12),(-2,34,10,12),(166,109,19,12),(154,153,12,20)]
 tools +=[B(x+5,y+5,z-1,ww,ll,5)for x,y,ww,ll in notches]
 if name!='pi-power':tools.append(B(4,73,z-1,5,80,5))
 tools.append(B(4,13,z-1,5.5,39,5))
 if name=='pi-power':tools.extend(C(x,y,z-1,3.3,5)for x,y in d['input_clamp']['screw_axes_xy_mm'])
 xx,yy,ww,ll=d['service_access']['rear_opening_xywh_mm'];tools.extend([B(xx,yy,z-1,ww,ll,5),B(157.5,13,z-1,35.5,24.5,8),B(4,31,z-1,17 if name=='devices'else 14,13,5)])
 for xx in [13,W-8]:
  for yy in [24,60,96,132]:
   length=14 if yy==132 else 22;r=4.5 if xx==W-8 else 4
   tools.extend([B(xx-r,yy,z-1,2*r,length,5),C(xx,yy,z-1,r,5),C(xx,yy+length,z-1,r,5)])
   for cy in [yy,yy+length]:tools.extend([C(xx,cy,z-.01,r+.61,.61,r2=r),C(xx,cy,z+2.4,r,.61,r2=r+.61)])
   for side in [-1,1]:
    x=xx+side*r
    for points in [[(x-side*.02,z+2.4),(x+side*.61,z+3.01),(x-side*.02,z+3.01)],[(x-side*.02,z+.6),(x+side*.61,z-.01),(x-side*.02,z-.01)]]:
     # Exact triangular chamfer wedge in the X/Z plane.
     face=b.Face(b.Wire.make_polygon([(a,yy,c)for a,c in points]));tools.append(b.extrude(face,amount=length,dir=(0,1,0)))
 for o in d['objects']:
  if o['group']!=name and not(name=='hub-power'and o['group']=='input-power'):continue
  m=np.array(o['matrix']);pos=m[:3,3];oid=o['id']
  if oid.endswith('_PCB')and oid!='GPIO_PCB':
   bw,bh=o.get('board_size_mm',[40,60])
   for a,c in [(1,1),(bw-1,1),(1,bh-1),(bw-1,bh-1)]:
    q=m@np.array([a,c,0,1]);positive.append(C(q[0],q[1],z+3,1.8,pos[2]-z-3))
  if re.fullmatch('(PI|HUB)_U[123]',oid):
   for dx in [3,39]:
    for dy in [3,18]:positive.append(matrix(B(dx,dy,z+3-pos[2],6,5,3),m))
   for dx in [10,40]:
    for dy in [-3,26]:tools.append(matrix(B(dx,dy,z-1-pos[2],6,2.5,5),m))
  if re.fullmatch('(PI|HUB)_R[123]',oid):
   for dx in [14,32]:
    for dy in [2,20]:positive.append(matrix(B(dx,dy,z+3-pos[2],6,5,3),m))
   for dy in [-3,29]:tools.append(matrix(B(22,dy,z-1-pos[2],6,2.5,5),m))
 ties=[(145,52),(145,88),(96,14),(120,14),(144,14),(84,11),(84,43)]if name=='pi-power'else[(94,61),(132,61),(145,93),(145,126),(163,121),(163,154),(84,11),(154,11)]if name=='hub-power'else[(139,11),(139,55)]
 tools.extend(B(x,y,z-1,6,2.5,5)for x,y in ties)
 if name=='devices':
  for oid,xs,ys in [('UNITEK',[8,107],[4,33]),('PI_CASE',[7,91.5],[8,62.3])]:
   m=np.array(next(o for o in d['objects']if o['id']==oid)['matrix'])
   for a in xs:
    for c in ys:
     q=m@np.array([a,0,c,1])if oid=='UNITEK'else m@np.array([a,c,0,1]);positive.append(B(q[0]-3.5,q[1]-3.5,z+3,7,7,3))
  for xx in [97,140]:
   for yy in [69,125]:tools.append(B(xx,yy-3,z-1,2.5,6,5))
  for xx in [5,94]:
   for yy in [73,161]:tools.append(B(xx,yy,z-1,5,2.5,5))
  positive.extend([B(138,155.5,z+3,W-153,1.5,17.5),B(15,71,z,15,8,3),B(132,96,79,12,4,3),B(140,96,82,4,4,28.8),B(139,95,110.8,6,6,1.2),B(140,96,112,4,4,4.82),B(139,95,116.8,6,6,1.2)])
  a,c,ww,ll=d['service_access']['input_tool_window_xywh_mm'];tools.append(B(a,c,z-1,ww,ll,5));tools.extend(C(x,y,z-1,1.7,5)for x,y in d['can_saddle']['screws_xy_mm'])
 if name=='hub-power':positive.extend([B(12,20,z+3,6,6,3),B(24,41,z+3,6,6,3)]);tools.append(B(30.5,30.5,z-1,2.5,6,5))
 # Board-ground-wire reliefs use exact circular capsules, not triangulated polygons.
 if name in ['pi-power','hub-power']:
  for q in d['wires']:
   if q['group']!=name or q['kind']!='solder_side'or q['net']!='GND'or q.get('pcb_owner')=='GPIO_PCB':continue
   # The board reliefs are cut per physical authored segment, retaining exact
   # circular ends. Collinear runs are coalesced before standard Booleans.
   points=np.array(q['points'],float)[:,:2];keep=[points[0]]
   for j in range(1,len(points)-1):
    u=points[j]-keep[-1];v=points[j+1]-points[j]
    if abs(u[0]*v[1]-u[1]*v[0])>1e-6:keep.append(points[j])
   keep.append(points[-1])
   for a,c in zip(keep,keep[1:]):
    length=math.dist(a,c)
    if length<1e-7:continue
    angle=math.degrees(math.atan2(c[1]-a[1],c[0]-a[0]));slot=b.SlotCenterToCenter(length,2.2)
    tool=b.extrude(slot,amount=1.2).moved(b.Location(((a[0]+c[0])/2,(a[1]+c[1])/2,z+2),(0,0,angle)))
    tr=cut(tr,tool)
 for r in d['screw_rails']:
  if r['group']!=name:continue
  m=np.array(r['matrix']);gap=m[2,3]-(z+3);x0=r.get('bar_offset_x_mm',0);length=r.get('bar_length_mm',78);width=r.get('bar_width_mm',6)
  positive.append(matrix(B(x0,0,-gap,length,width,gap),m))
  if r.get('captured_cradle'):positive.extend(matrix(B(x0-.2,yy,-gap-.02,length+.4,1,gap+3.02),m)for yy in [-1.2,width+.2])
  for x in [-3,81]:tools.append(matrix(C(x,r.get('cover_screw_y_mm',3),z-m[2,3]-1,1.7,5),m))
  for x in r['retaining_tie_x_mm']:
   for y in [-1.4,7.4]:tools.append(matrix(B(x-2.7,y-.9,z-m[2,3]-1,5.4,1.8,5),m))
 for band in d['retaining_bands']:
  if band['group']==name:
   for slot in band['slots']:tools.append(matrix(B(*slot['xyz'],*slot['size']),band['matrix']))
 if name!='pi-power':positive.append(B(9,145,z,8,5,3))
 if name=='devices':positive.append(B(10,143,z,8,3,3)) # connect the left riser lip before corner reliefs
 if name=='devices':tools.append(B(4,128,z-1,14,11,5))
 if name=='hub-power':
  f=d['short_pi_fuse_riser'];x,y=f['axis_xy_mm'];tools.append(C(x,y,z-1,f['clearance_radius_mm'],8))
  for x,y in d['hub_tht_fuses']['mounting_axes_xy_mm']:positive.append(C(x,y,z+3,3.8,7));tools.append(C(x,y,z+1.5,1.25,9.5))
 if name=='devices':
  for x,y,ww,ll in d['hub_tht_fuses']['deck_windows_xywh_mm']:tools.append(B(x,y,z-1,ww,ll,6))
 print('tray-fixtures-prepared',name,len(positive),len(tools),flush=True)
 # Plate-specific corner clearances and bearing bosses.
 if name in ['devices','hub-power']:
  rects=[(0,0,13.2,13.2),(171.8,0,14.2,13.2),(0,149.8,13.2,23.2),(171.8,149,14.2,24)]if name=='devices'else[(0,0,25,15),(160,0,26,15),(0,149.5,25,23.5),(167,142,19,15.5)]
  tools.extend(B(x,y,z-1,ww,ll,7)for x,y,ww,ll in rects);tools.extend(C(x,y,z-1,5.15,7)for x,y in d['stack_frame']['post_axes_mm'])
  tr=cut(union(tr,*positive),*tools);positive=[];tools=[]
  mounts=d['stack_frame']['top_mounts_mm'if name=='devices'else'middle_mounts_mm']
  for x,y in mounts:
   h=.2 if name=='devices'and[x,y]==[175,145]else 2.2;positive.append(C(x,y,z+2.8,3.6,h));tools.append(C(x,y,z-1,1.7,8))
 else:
  # Floor tray rises to Z9; full carrier feet begin at Z3.
  tr=cut(union(tr,*positive),*tools);positive=[];tools=[]
  print('tray-plate-cut',name,flush=True);parts,framecuts=frame(d);positive.extend(parts);tools.extend(framecuts)
  for band in d['retaining_bands']:
   if band['group']in['hub-power','devices']:
    tools.extend(matrix(B(*slot['xyz'],*slot['size']),band['matrix'])for slot in band['slots'])
  for lock in d['stack_frame']['retaining_wall_screws']:
   x,y,zz=lock['xyz'];ny=162 if lock['rear']else 10;tools.extend([C(x,ny+5,zz,1.7,13,axis=(0,-1,0)),matrix(C(0,0,0,3.35,2.6,6),T([x,ny+1.3,zz],rx=90)),B(x,ny-1.3,zz-2.9,6,2.6,5.8)])
  tools.extend([B(159,158,2,12,15,100),C(*d['short_pi_fuse_riser']['axis_xy_mm'],d['short_pi_fuse_riser']['z_mm'][0],d['short_pi_fuse_riser']['clearance_radius_mm'],d['short_pi_fuse_riser']['z_mm'][1]-d['short_pi_fuse_riser']['z_mm'][0])])
 print('tray-final-union',name,len(positive),len(tools),flush=True)
 tr=cut(union(tr,*positive),*tools)
 # Remove only the documented obsolete riser-lip offcut after the post/corner
 # relief; it is not a physical part and must not be exported as loose debris.
 if name!='pi-power':tr=cut(tr,B(8.9,145.9,z-.1,3.1,4.1,3.2))
 # Final service openings cannot be refilled by frame unions.
 tools=[B(xx,yy,2,ww,ll,150)for xx,yy,ww,ll in [d['frame_service_channels'][key]for key in ['rear_xywh_mm','can_xywh_mm']]]
 if name=='pi-power':tools.append(B(-5,-5,2,W+20,8.3,150))
 tr=cut(tr,*tools)
 from harness_contract import guides
 for guide in guides(name,z):
  x,y=guide['center_xy'];zz=guide['base_z'];outer=guide['outer_radius'];inner=guide['inner_radius'];ring=cut(C(x,y,zz,outer,3),C(x,y,zz,inner,3),B(x-outer-.1,y-guide['opening_width']/2,zz-.1,outer+.1,guide['opening_width'],3.2));bridge=B(x+outer-.2,y-1,zz,guide['bridge_end_x']-(x+outer-.2),2,3);parts=[ring,bridge]
  if 'support_base_z'in guide:parts.append(B(18.2,y-1,guide['support_base_z'],1.8,2,zz+3-guide['support_base_z']))
  tr=union(tr,*parts)
 return tr

def saddle(d):
 s=union(B(0,0,0,18,45,3),B(0,38,3,18,7,17),B(0,24,3,18,7,17));tools=[C(14,yy,12,1.25,9)for yy in [41.5,27.5]];inv=np.linalg.inv(d['can_saddle']['matrix'])
 for band in d['retaining_bands']:
  if band['owner']!='CANABLE':continue
  o=next(o for o in d['objects']if o['id']==band['id'])
  for g in o['geometry']:
   if g['shape']=='box':tools.append(matrix(B(*[x-.3 for x in g['xyz']],*[x+.6 for x in g['size']]),inv@np.array(o['matrix'])))
 return matrix(cut(s,*tools),d['can_saddle']['matrix'])

def build(d,p):
 result={'pi-box-base':base(d,p),'pi-box-lid':lid(d,p),'CAN_WALL_HOLDER':can_holder(d),'CAN_SADDLE':saddle(d)}
 result.update({key:tray(d,group,p)for key,group in [('pi-tray','pi-power'),('hub-tray','hub-power'),('device-deck','devices')]})
 result.update({r['id']+'_COVER':rail_cover(r)for r in d['screw_rails']})
 result.update({name:power_entry(d,name)for name in ['CL_IN','PWR_ENTRY_SHOE','PG7','PWR_ENTRY_BACKPLATE']})
 return result

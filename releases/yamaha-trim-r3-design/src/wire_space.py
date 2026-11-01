"""Continuous, rounded3D wire envelopes from the physical bridge contract."""
import math,json
from contract import R,D,OUT,save,sha

def rounded_edges(points,radius):
 import build123d as b
 from OCP.gp import gp_Pnt
 def vadd(a,z):return tuple(x+y for x,y in zip(a,z))
 def vsub(a,z):return tuple(x-y for x,y in zip(a,z))
 def mul(a,v):return tuple(x*v for x in a)
 def norm(a):return math.sqrt(sum(x*x for x in a))
 def unit(a):return mul(a,1/norm(a))
 edges=[];start=tuple(points[0]);radii=[]
 for i in range(1,len(points)-1):
  a,c,z=map(tuple,points[i-1:i+2]);u=unit(vsub(c,a));v=unit(vsub(z,c));theta=math.acos(max(-1,min(1,sum(x*y for x,y in zip(u,v)))))
  if theta<1e-7:continue
  rad=radius[i-1]if isinstance(radius,list)else radius
  tangent=rad*math.tan(theta/2)
  if tangent>min(norm(vsub(c,a)),norm(vsub(z,c)))*.99:raise ValueError('Insufficient straight length for specified wire bend')
  p=vsub(c,mul(u,tangent));q=vadd(c,mul(v,tangent));center=vadd(c,mul(unit(vsub(v,u)),rad/math.cos(theta/2)));mid=vadd(center,mul(unit(vsub(c,center)),rad))
  if math.dist(start,p)>1e-7:edges.append(b.Edge.make_line(start,p))
  edges.append(b.Edge.make_three_point_arc(p,mid,q));radii.append(rad);start=q
 if math.dist(start,points[-1])>1e-7:edges.append(b.Edge.make_line(start,points[-1]))
 return b.Wire(edges),radii

def wire_shape(w,offset=(0,0,0)):
 import build123d as b
 pts=[tuple(v+offset[k]for k,v in enumerate(p))for p in w['path3d']];path,radii=rounded_edges(pts,w.get('bend_radii_mm',w['bend_radius_mm']))
 profile=b.Wire.make_circle(w['wire_OD_mm']/2,b.Plane(origin=pts[0],z_dir=(0,0,1)))
 return b.Solid.sweep(profile,path),path.length,radii

def main():
 l=json.loads((OUT/'stripboard.json').read_text());records=[]
 for w in l['jumpers']:
  s,length,radii=wire_shape(w)
  records.append(dict(id=w['id'],valid=bool(s.is_valid),length_mm=length,planned_length_mm=w['route_mm'],bend_radii_mm=radii,layer=w['layer'],status='PASS'if s.is_valid and abs(length-w['route_mm'])<.02 else'FAIL'))
 save(R/'reports/wire-space.json',dict(status='PASS'if all(c['status']=='PASS'for c in records)else'FAIL',source_sha256=sha(OUT/'stripboard.json'),wires=records,scope='Continuous OCCT circular sweeps with explicit layers and circular bends; actual enamel/hand forming remains physical check.'))
 print('WIRE SHAPES',len(records))
if __name__=='__main__':main()

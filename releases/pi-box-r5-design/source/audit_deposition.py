"""Independent deposited-width check, without re-slicing or changing print files."""
import sys,zipfile,xml.etree.ElementTree as ET
import numpy as np,trimesh
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));from render_upper_bed import read_beads
from prepare_upper_print import model_triangles,triangle_difference

def main():
 t=ROOT/'print-rest/Bett-01';prep=read(t/'preparation.json');inv={p['id']:p for p in read(ROOT/'delivery/daten/druckteile.json')['parts']};d=read(ROOT/'design.json')
 a,z,w,h,k,roles,arcs=read_beads((t/'plate_1.gcode').read_text());delta=z[:,:2]-a[:,:2];unit=delta/np.linalg.norm(delta,axis=1)[:,None];side=np.c_[-unit[:,1],unit[:,0]]*w[:,None]/2
 quads=np.stack([a[:,:2]+side,z[:,:2]+side,z[:,:2]-side,a[:,:2]-side],axis=1)
 # Include a half-bead disk at endpoints, conservatively bounding all deposited XY material.
 lo=np.minimum(a[:,:2],z[:,:2])-w[:,None]/2;hi=np.maximum(a[:,:2],z[:,:2])+w[:,None]/2
 bounds=[lo.min(0).tolist(),hi.max(0).tolist()];assert np.max(abs(np.asarray(bounds)))<=110
 checks=[];q=quads[k==0];az=a[k==0,2];hh=h[k==0]
 for row in prep['parts']:
  if row['type']not in ['PI_A_CONNECTOR_CARRIER','HUB_A_CONNECTOR_CARRIER','INPUT_A_CONNECTOR_CARRIER']:continue
  meta=inv[row['type']];rot=trimesh.transformations.euler_matrix(*np.radians(meta['print_rotation_xyz_deg']),axes='rxyz')[:3,:3];pack=trimesh.transformations.rotation_matrix(np.radians(row['rotation_z_deg']),[0,0,1])[:3,:3];mesh=trimesh.load_mesh(meta['stl']);off=-(mesh.vertices@pack.T).min(0)+row['translation_mm'];ox,oy,oz=d['boards'][row['type'].replace('_CONNECTOR_CARRIER','')]['origin'];ww,ht,_=d['boards'][row['type'].replace('_CONNECTOR_CARRIER','')]['size']
  for num,(x,y)in enumerate([(ox+4,oy+4),(ox+ww-4,oy+4),(ox+4,oy+ht-4),(ox+ww-4,oy+ht-4)],1):
   p0,p1=[pack@(rot@np.array([x,y,zz])+meta['print_translation_mm'])+off for zz in [oz+1,oz+3]];axis=int(np.argmax(abs(p1-p0)));other=1-axis;low,high=sorted([p0[axis],p1[axis]]);low+=.1;high-=.1
   vals=[]
   for j in range(4):
    aa=q[:,j];bb=q[:,(j+1)%4];inside=(aa[:,axis]>=low)&(aa[:,axis]<=high);vals.append(np.where(inside,aa[:,other],np.nan))
    for bound in [low,high]:
     den=bb[:,axis]-aa[:,axis];tt=np.divide(bound-aa[:,axis],den,out=np.full(len(aa),np.nan),where=abs(den)>1e-12);vals.append(np.where((tt>=0)&(tt<=1),aa[:,other]+tt*(bb[:,other]-aa[:,other]),np.nan))
   vals=np.stack(vals);valid=np.any(np.isfinite(vals),axis=0);lo2=np.min(np.where(np.isfinite(vals),vals,np.inf),axis=0);hi2=np.max(np.where(np.isfinite(vals),vals,-np.inf),axis=0);dy=np.maximum(np.maximum(lo2-p0[other],p0[other]-hi2),0);dz=np.maximum(np.maximum(az-hh-p0[2],p0[2]-az),0);minimum=float(np.sqrt(dy[valid]**2+dz[valid]**2).min())*2;assert minimum>=3.19,(row['id'],num,minimum);checks.append(dict(id=row['id'],bore=num,nominal_clear_diameter_mm=minimum))
 # Direct source/sliced triangles and all 15 individual file readbacks.
 source=model_triangles(t/'plate-source.3mf');sliced=model_triangles(t/'Pi-Box-PI-2L-5-Restbett-01-PETG.3mf');assert set(source)==set(sliced)=={r['id']for r in prep['parts']};err=max(triangle_difference(source[id],sliced[id])for id in source)
 assert err<1e-5
 for p in inv.values():
  assert sha(p['stl'])==p['sha256'];mesh=trimesh.load_mesh(p['stl'],process=True);assert mesh.is_watertight and mesh.is_winding_consistent and len(mesh.split())==1
 save(ROOT/'output/plausibility-deposition.json',dict(status='PASS_SCOPED_BEAD_MODEL',gcode_sha256=sha(t/'plate_1.gcode'),three_mf_sha256=sha(t/'Pi-Box-PI-2L-5-Restbett-01-PETG.3mf'),deposited_xy_envelope_mm=bounds,minimum_bed_edge_margin_mm=110-float(np.max(abs(np.asarray(bounds)))),bore_checks=checks,source_to_sliced_max_triangle_error_mm=err,body_count=len(source),unique_stl_types=len(inv),limitations='Width/height metadata and conservative rectangular cross-sections; not measured print calibration, screw fit or breakaway force. Open support structures require actual removal test. Thin webs do not acquire four wall lines simply because profile requests four walls.'))
 print('DEPOSITION PASS',bounds,'bores',len(checks),flush=True)
if __name__=='__main__':main()

"""Reused offscreen CPU z-buffer rasterizer; no GUI or OpenGL context."""
import math,json,sys
import numpy as np
from numba import njit
from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
R=Path(__file__).resolve().parents[1];O=R/'delivery';C=O/'cad-cache';OUT=O/'renders';OUT.mkdir(exist_ok=True)
import hashlib
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
@njit(cache=True)
def raster(points,triangles,pids,colors,width,height):
  im=np.empty((height,width,3),np.uint8);im[:,:,0]=246;im[:,:,1]=249;im[:,:,2]=250
  depth=np.full((height,width),-1e20,np.float32);pix=np.full((height,width),-1,np.int16)
  light=np.array([-.35,-.4,.85]);light/=np.sqrt(np.sum(light*light))
  for i in range(len(triangles)):
   a,bb,c=triangles[i];p,q,r=points[a],points[bb],points[c];det=(q[1]-r[1])*(p[0]-r[0])+(r[0]-q[0])*(p[1]-r[1])
   if abs(det)<1e-6:continue
   xmin=max(0,int(math.floor(min(p[0],q[0],r[0]))));xmax=min(width-1,int(math.ceil(max(p[0],q[0],r[0]))));ymin=max(0,int(math.floor(min(p[1],q[1],r[1]))));ymax=min(height-1,int(math.ceil(max(p[1],q[1],r[1]))))
   n=np.cross(q-p,r-p);ln=np.sqrt(np.sum(n*n))
   if ln<1e-6:continue
   shade=.42+.58*abs(np.sum(n/ln*light));col=colors[pids[i]]*shade
   for y in range(ymin,ymax+1):
    for x in range(xmin,xmax+1):
     aa=((q[1]-r[1])*(x+.5-r[0])+(r[0]-q[0])*(y+.5-r[1]))/det;be=((r[1]-p[1])*(x+.5-r[0])+(p[0]-r[0])*(y+.5-r[1]))/det;cc=1-aa-be
     if aa<0 or be<0 or cc<0:continue
     z=aa*p[2]+be*q[2]+cc*r[2]
     if z>depth[y,x]:
      depth[y,x]=z;pix[y,x]=pids[i]
      for k in range(3):im[y,x,k]=min(255,int(col[k]))
  return im,depth,pix

def main():
 m=json.loads((O/'manifest.json').read_text());data=np.load(C/'render-mesh.npz');vs=data['vertices'];fs=data['faces'];ids=data['ids'];owner=data['owner'];rows=m['rows'];colors=np.array([[int(r['color'][k:k+2],16)for k in[1,3,5]]for r in rows],np.float32)
 font='/System/Library/Fonts/Supplemental/Arial.ttf';F=lambda n:ImageFont.truetype(font,n)
 records=[]
 for name,eye in [('open',(1.2,-1.65,1.6)),('top',(0,0,1)),('closed',(1.25,-1.8,1.2)),('exploded',(1.2,-1.8,1.5))]:
  world=vs.copy();keep=np.ones(len(rows),bool)
  if name in['open','top']:
   for i,r in enumerate(rows):
    if r['kind']in['hood','hood_screw']:keep[i]=False
  if name=='exploded':
   for i,r in enumerate(rows):world[owner==i]+=data['offsets_exploded'][i]
  mask=keep[ids];tri=fs[mask];pids=ids[mask]
  e=np.array(eye,dtype=float);e/=np.linalg.norm(e);right=np.cross([0,0,1],e)
  if np.linalg.norm(right)<.01:right=np.array([1.,0,0])
  right/=np.linalg.norm(right);up=np.cross(e,right);pp=world@np.column_stack([right,-up,e]);used=np.unique(tri);lo=pp[used,:2].min(axis=0);hi=pp[used,:2].max(axis=0);w,h=2000,1450;scale=min((w-120)/(hi[0]-lo[0]),(h-260)/(hi[1]-lo[1]));pp[:,:2]=(pp[:,:2]-(hi+lo)/2)*scale+[w/2,(h+100)/2];pp[:,2]*=scale
  im,z,pix=raster(pp.astype(np.float32),tri,pids,colors,w,h);edge=np.zeros((h,w),bool)
  for dy,dx in[(1,0),(0,1)]:edge|=(pix>=0)&((pix!=np.roll(pix,(dy,dx),(0,1)))|(np.abs(z-np.roll(z,(dy,dx),(0,1)))>scale*2))
  im[edge]=(im[edge].astype(float)*.6).astype(np.uint8);out=Image.fromarray(im);draw=ImageDraw.Draw(out)
  title={'open':'Offener Innentraeger · vollstaendig bestueckt','top':'Bestueckter Traeger · Draufsicht','closed':'Geschlossene Baugruppe · Haube von unten verschraubt','exploded':'Explosionsansicht · gleicher Teilebestand'}[name]
  draw.text((45,30),title,font=F(34),fill='#183a47');draw.text((45,80),'Nano + DAC + Signalstufe + Relais auf einer Streifenrasterplatine · Wandler daneben',font=F(23),fill='#55727d');draw.text((45,h-50),'R3 · gepruefte Drahtlagen · nominale Kaufteilmodelle · reale Passung/Betrieb ungemessen',font=F(22),fill='#55727d');path=OUT/(name+'.png');out.save(path,optimize=True);records.append(dict(file=name+'.png',sha256=sha(path),members=[r['id']for i,r in enumerate(rows)if keep[i]]))
 (R/'reports/render.json').write_text(json.dumps(dict(status='PASS_SOURCE_BOUND',manifest_sha256=sha(O/'manifest.json'),mesh_sha256=sha(C/'render-mesh.npz'),views=records,visual_review='PENDING'),indent=2)+'\n');print('Rendered',len(records))
if __name__=='__main__':main()

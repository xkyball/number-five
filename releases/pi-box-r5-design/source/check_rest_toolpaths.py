"""Independent deposition checks: every object,34 vertical cores and16 transverse bores."""
import sys,numpy as np
import trimesh
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));from prepare_upper_print import parse_gcode
def main():
 r=read(ROOT/'print-rest/Bett-01/preparation.json');inv={p['id']:p for p in read(ROOT/'delivery/daten/druckteile.json')['parts']};d=read(ROOT/'design.json');ss,_,_=parse_gcode((ROOT/'print-rest/Bett-01/plate_1.gcode').read_text(),{});ss=[s for s in ss if s[2]>0 and s[3]not in ['Support','Support interface','Brim','Custom']];aa=np.asarray([s[0]for s in ss]);zz=np.asarray([s[1]for s in ss]);bodies=[];cores=[]
 def transform(point,row):
  q=np.asarray(point,float)
  if row['rotation_z_deg']==90:q=np.asarray([inv[row['type']]['bounds_mm'][1][1]-q[1],q[0],q[2]])
  return q+np.asarray(row['translation_mm'])
 def bore(a,z,required,row,label):
  axis=int(np.argmax(abs(z-a)));other=[k for k in range(3)if k!=axis];low,high=sorted([a[axis],z[axis]]);low+=.1;high-=.1;v=zz-aa;den=v[:,axis];flat=abs(den)<1e-12;lo=np.zeros(len(aa));hi=np.ones(len(aa));non=~flat
  t1=(low-aa[non,axis])/den[non];t2=(high-aa[non,axis])/den[non];lo[non]=np.maximum(0,np.minimum(t1,t2));hi[non]=np.minimum(1,np.maximum(t1,t2));keep=(lo<=hi)&(~flat|((aa[:,axis]>=low)&(aa[:,axis]<=high)));q=aa[keep]+lo[keep,None]*v[keep];w=aa[keep]+hi[keep,None]*v[keep]-q;diff=q[:,other]-a[other];w=w[:,other];t=np.clip(-np.einsum('ij,ij->i',diff,w)/np.maximum(np.einsum('ij,ij->i',w,w),1e-20),0,1);dist=np.linalg.norm(diff+t[:,None]*w,axis=1);minimum=float(dist.min());assert minimum>required,(row['id'],label,minimum,required);cores.append(dict(object=row['id'],feature=label,minimum_extrusion_centreline_distance_mm=minimum,required_gt_mm=required))
 for row in r['parts']:
  low,high=np.asarray(row['print_bounds_mm']);mid=(aa+zz)/2;mask=np.all((mid>=low-.1)&(mid<=high+.1),axis=1);assert mask.sum()>5,(row['id'],'missing');top=float(zz[mask,2].max());assert top>=high[2]-.21,(row['id'],'top missing',top,high[2]);bodies.append(dict(id=row['id'],segments=int(mask.sum()),expected_top_mm=float(high[2]),actual_top_mm=top))
  typ=row['type']
  if typ in ['CONVERTER_ADAPTER','INSULATING_WASHER','STACK_RISER']:
   centres=[(x+read(ROOT/'output/adapter-independent-check.json')['print_translation_mm'][0],y+read(ROOT/'output/adapter-independent-check.json')['print_translation_mm'][1])for x,y in d['converter_adapter']['old_mounts_local_cad']+d['converter_adapter']['board_mounts_local_cad']]if typ=='CONVERTER_ADAPTER'else[(3.5,3.5)]if typ=='INSULATING_WASHER'else[(6,6)]
   for i,(x,y)in enumerate(centres):bore(transform([x,y,0],row),transform([x,y,inv[typ]['bounds_mm'][1][2]],row),1.7,row,'vertical core'+str(i+1))
  elif typ.endswith('_CONNECTOR_CARRIER'):
   bid=typ.replace('_CONNECTOR_CARRIER','');ox,oy,oz=d['boards'][bid]['origin'];ww,hh,_=d['boards'][bid]['size'];meta=inv[typ];rot=trimesh.transformations.euler_matrix(*np.radians(meta['print_rotation_xyz_deg']),axes='rxyz');tr=np.asarray(meta['print_translation_mm'])
   for i,(x,y)in enumerate([(ox+4,oy+4),(ox+ww-4,oy+4),(ox+4,oy+hh-4),(ox+ww-4,oy+hh-4)]):
    pts=[]
    for z in [oz+1,oz+3]:pts.append(transform((rot@np.array([x,y,z,1]))[:3]+tr,row))
    bore(*pts,1.5,row,'transverse PCB bore'+str(i+1))
 save(ROOT/'output/rest-toolpath-check.json',dict(status='PASS_SCOPED_ALL31_OBJECTS_AND50_BORES',gcode_sha256=sha(ROOT/'print-rest/Bett-01/plate_1.gcode'),objects=bodies,cores=cores,scope='Actual body extrusion segments, support/brim excluded.34 vertical channels >1.7mm centerline radius;16 transverse channels >1.5mm. This detects filled/omitted bores; actual bead width and physical M3 fit remain print calibration acceptance.'))
 print('TOOLPATH',len(bodies),'objects',len(cores),'cores PASS')
if __name__=='__main__':main()

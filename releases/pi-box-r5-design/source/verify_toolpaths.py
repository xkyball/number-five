"""Independent channel/feature checks on the actual exported deposition segments."""
import sys,numpy as np
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));from prepare_upper_print import parse_gcode
def main():
 out=ROOT/'print-adapters';p=read(out/'preparation.json');gc=out/p['gcode']['file'];assert sha(gc)==p['gcode']['sha256'];segments,_,_=parse_gcode(gc.read_text(),{});body=[s for s in segments if s[2]>0 and s[3]not in ['Brim','Support','Support interface','Custom']];aa=np.asarray([s[0][:2]for s in body]);zz=np.asarray([s[2]for s in body]);vv=np.asarray([s[1][:2]for s in body])-aa;vv2=np.einsum('ij,ij->i',vv,vv);checks=[]
 d=read(ROOT/'design.json');tr0=read(ROOT/'output/adapter-independent-check.json')['print_translation_mm']
 for r in p['parts']:
  tx,ty,_=r['translation_mm'];top=5.6 if r['type']=='CONVERTER_ADAPTER' else 1.0
  centres=[(x+tr0[0]+tx,y+tr0[1]+ty)for x,y in d['converter_adapter']['old_mounts_local_cad']+d['converter_adapter']['board_mounts_local_cad']]if r['type']=='CONVERTER_ADAPTER'else[(3.5+tx,3.5+ty)]
  mask=zz<=top+.001
  for c in centres:
   diff=aa[mask]-np.asarray(c);v=vv[mask];t=np.clip(-np.einsum('ij,ij->i',diff,v)/np.maximum(vv2[mask],1e-20),0,1);distance=np.linalg.norm(diff+t[:,None]*v,axis=1);minimum=float(distance.min());assert minimum>1.7,(r['id'],c,minimum);checks.append(dict(part=r['id'],centre_mm=c,minimum_extrusion_centreline_distance_mm=minimum,required_gt_mm=1.7))
 save(ROOT/'output/toolpath-independent-check.json',dict(revision='PI-2L-5',status='PASS_SCOPED_30_CHANNELS',gcode_sha256=sha(gc),channels=checks,upper_features=p['verification']['thin_features_checked'],scope='All20 adapter screw channels and10 insulating washer bores, actual sliced extrusion segment centre lines; nominal nozzle radius0.2mm. Physical dimensional calibration remains OPEN.'))
 print('INDEPENDENT GCODE',len(checks),'channels PASS',flush=True)
if __name__=='__main__':main()

"""Complement the planar hood sweep with a conservative curved-port sweep."""
import build123d as b
from common import ROOT,OUT,read,save,sha
from model import box,cyl

def main():
    q=read(OUT/'data/assembly.json');tool=box(0,35,15,q['parameters']['wall'],10,125);bb=tool.bounding_box();bad=[];tested=[]
    for r in q['parts']:
        if r['owner']=='HOOD'or r.get('assembled')is False:continue
        lo,hi=r['bounds']
        if not all(min(hi[i],list(bb.max)[i])-max(lo[i],list(bb.min)[i])>1e-6 for i in range(3)):continue
        if r['kind']=='print':
            s=b.import_brep(OUT/'brep'/(r['id']+'.brep'));v=s.intersect(tool);amount=sum(abs(x.volume)for x in v.solids())if v else 0
            tested.append(dict(id=r['id'],intersection_mm3=amount))
            if amount>1e-5:bad.append(r['id'])
        else:bad.append(r['id']+' candidate in conservative curved-port region; exact body review required')
    hood=b.import_brep(OUT/'brep/HOOD.brep');probe=cyl(-1,40,9,4,q['parameters']['wall']+2,(1,0,0));samples=[]
    for dz in[0,1,5,10,20,40,80]:
        hit=hood.moved(b.Location((0,0,dz))).intersect(probe);v=sum(abs(x.volume)for x in hit.solids())if hit else 0;samples.append(dict(lift_mm=dz,intersection_mm3=v))
        if v>1e-5:bad.append('8mm nominal transducer clearance at lift'+str(dz))
    save(OUT/'checks/hood-curved-region.json',dict(status='FAIL'if bad else'PASS',errors=bad,tested=tested,nominal_8mm_service_probe=samples,assembly_sha256=sha(OUT/'data/assembly.json'),scope='Entire wall thickness and10mm transducer-port width from lowest arch15mm to140mm, conservatively contains every upward translated curved arch. Exact printed BRep intersections; no rigid bought-body bounds in this region. Real transducer cable deliberately absent by user scope. Complements mechanics.json planar80mm lift sweep.'))
    if bad:raise SystemExit(1)
    print('Curved hood region PASS')
if __name__=='__main__':main()

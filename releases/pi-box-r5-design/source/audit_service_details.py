"""Alternative accessible tail restraint and screw access after headers are unplugged."""
import build123d as b
from common import ROOT,read,save,sha
from build_rest import B,C,union,volume

def main():
 m=read(ROOT/'output/rest-mechanical.json');d=read(ROOT/'design.json');rows={r['id']:r for r in m['rows']};cache={}
 def load(id):
  if id not in cache:cache[id]=b.import_brep(rows[id]['brep']['path'])
  return cache[id]
 def test(probe,skip=()):
  bb=probe.bounding_box();lo=list(bb.min);hi=list(bb.max);hits=[]
  for id,r in rows.items():
   if id in skip:continue
   aa,az=r['bounds_mm']
   if not all(min(hi[k],az[k])-max(lo[k],aa[k])>1e-5 for k in range(3)):continue
   v=volume(probe.intersect(load(id)))
   if v>.02:hits.append(dict(id=id,volume_mm3=v))
  return hits
 # The rear molded slot is narrowed by the GPIO bridge. Do not force a 2.5mm tie through it.
 # Open wrap around the outside of that bridge; a second tie uses the front clear slot.
 tie=union(B(66.5,139,66.8,2.5,20.3,.8),B(66.5,139,78,2.5,20.3,.8),B(66.5,139,66.8,2.5,.8,12),B(66.5,158.5,66.8,2.5,.8,12))
 hits=test(tie);out=dict(source_sha256=sha(ROOT/'design.json'),mechanical_sha256=sha(ROOT/'output/rest-mechanical.json'),gpio_rear_tie=dict(status='PASS_NOMINAL_EXTERNAL_WRAP'if not hits else'FAIL',hits=hits,tie_section_mm=[2.5,.8],bounds_mm=[list(tie.bounding_box().min),list(tie.bounding_box().max)],instruction='Do not use narrowed rear slot. Wrap second 104x2.5mm tie around outside of rear bridge and insulated pigtails. Front tie uses open front slot. Apply tail isolation and verify actual buckle orientation/strain relief before use.',physical_acceptance='OPEN: tie thickness, bend at corners, buckle and insulated wire bundle are not qualified by rigid rectangular-band probe.'))
 # Access board screw heads after unplugging all board interfaces and releasing both body/tail ties.
 checks=[]
 for bid in ['PI_A','HUB_A','INPUT_A']:
  ox,oy,oz=d['boards'][bid]['origin'];ww,hh,_=d['boards'][bid]['size'];heads=[r['id']+'_FIXED'for r in d['connector_mounts']if r['board']==bid];plugs=[q.replace('_FIXED','_PLUG')for q in heads];upper=[id for id,r in rows.items()if r['level']in ['devices','external']and not id.startswith('FRAME_')]
  for x,y in [(ox+4,oy+4),(ox+ww-4,oy+4),(ox+4,oy+hh-4),(ox+ww-4,oy+hh-4)]:
   # A 2.96mm-diameter straight shaft approach above modeled head; socket and hand grip excluded.
   probe=C(x,y,oz+5.71,1.48,43.29);found=test(probe,skip=upper+heads+plugs);checks.append(dict(board=bid,xy_mm=[x,y],hits=found,status='PASS'if not found else'FAIL'))
 out['pcb_tool_access']=checks;save(ROOT/'output/plausibility-service-details.json',out);print(out,flush=True)
if __name__=='__main__':main()

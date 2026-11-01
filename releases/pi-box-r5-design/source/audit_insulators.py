"""Actual exported insulating rings, not generator proxies; source rejection regression."""
import copy
import build123d as b
from common import ROOT,read,save,sha
from build_rest import volume
from export_rest import export_assembly

def main():
 m=read(ROOT/'output/rest-mechanical.json');rows={r['id']:r for r in m['rows']};d=read(ROOT/'design.json');washer=b.import_step(ROOT/'delivery/cad/INSULATING_WASHER.step');checks=[]
 for id,y in zip(['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2'],d['converters']['rows']):
  for u,v in d['converter_adapter']['board_mounts_local_cad']:
   installed=b.Pos(20+u,y+v,20.2)*b.Rot(180,0,0)*b.Pos(-3.5,-3.5,-1)*washer
   for other in [id,id+'_ADAPTER']:
    vv=volume(installed.intersect(b.import_brep(rows[other]['brep']['path'])));assert vv<1e-5,(id,u,v,other,vv);checks.append(dict(module=id,other=other,local_xy_mm=[u,v],overlap_mm3=vv))
 # Deliberately corrupt a copy of the metadata; no source file is changed.
 current=ROOT/'delivery/cad/Innenaufbau-PI-2L-5.step';before=sha(current);bad=copy.deepcopy(m);bad['rows'][0]['brep']['sha256']='0'*64;rejected=False
 try:export_assembly(bad)
 except AssertionError:rejected=True
 assert rejected and sha(current)==before
 save(ROOT/'output/plausibility-insulators-regression.json',dict(status='PASS',actual_exported_insulators=10,exact_tests=checks,altered_brep_hash_rejected_before_assembly_export=True,assembly_unchanged_by_negative_control=True,source_sha256=sha(ROOT/'design.json')));print('INSULATORS PASS 20 tests; changed source rejected before export',flush=True)
if __name__=='__main__':main()

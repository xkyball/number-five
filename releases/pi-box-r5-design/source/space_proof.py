"""Continuous translation-space check for the conservative paired connector box."""
import itertools
from shapely.geometry import box,mapping
from shapely.ops import unary_union
from common import ROOT,read,save

def main():
 m=read(ROOT/'output/adapter-mechanical.json');e=read(ROOT/'inputs/l4-contact-baseline.json');ids={p['id']for p in e['connector_pairs']if p.get('board')}|{'PI_BANK_HEADER','PI_BANK_PLUG','HUB_BANK_HEADER','HUB_BANK_PLUG'}
 obs=[r for r in m['rows']if r['level']=='supply'and not r['printed']and not r.get('hardware')and r['id']not in ids]
 cases=[]
 for w,depth,h in sorted(set(itertools.permutations([26,30,24]))):
  low=10.6;high=39-h
  if high<low:continue
  breaks=sorted({low,high,*[v for r in obs for v in [r['bounds_mm'][0][2]-h-.5,r['bounds_mm'][1][2]+.5]if low<v<high]})
  zs=sorted(set(breaks+[(a+b)/2 for a,b in zip(breaks,breaks[1:])]))
  for z in zs:
   domain=box(7,7,210-w,189-depth);forbidden=[];blockers=[]
   for r in obs:
    a,b=r['bounds_mm']
    if min(z+h+.5,b[2])-max(z-.5,a[2])<=1e-6:continue
    forbidden.append(box(a[0]-w-.5,a[1]-depth-.5,b[0]+.5,b[1]+.5));blockers.append(r['id'])
   free=domain.difference(unary_union(forbidden))
   cases.append(dict(size=[w,depth,h],z=z,free_origin_area_mm2=free.area,free_origins=mapping(free)if not free.is_empty else None,blocking_members=blockers))
 save(ROOT/'output/continuous-space-check.json',dict(revision='PI-2L-5',status='FAIL_CONSERVATIVE_ENVELOPE_NO_TRANSLATION'if all(q['free_origin_area_mm2']<1e-7 for q in cases)else'FEASIBLE_ORIGINS_EXIST',cases=cases,scope='Exact continuous XY translation space of axis-aligned conservative26x30x24mm4P box, all six orientations and all Z topology intervals. Uses purchased-part AABBs with0.5mm margin; excludes printed structure, all moving connectors and hardware. An empty domain proves no placement under these conservative box contracts, not that the real smaller article cannot fit.',fixed_module_positions=True))
 print('SPACE CHECK',[(q['size'],q['z'],round(q['free_origin_area_mm2'],2))for q in cases],flush=True)
if __name__=='__main__':main()

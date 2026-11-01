"""Two independent fastener pairs: frozen printed posts / real board view."""
import math,sys
import build123d as b
from OCP.BRepCheck import BRepCheck_Analyzer
from common import ROOT,BASE,read,save,sha,baseline_rows
sys.path.insert(0,str(BASE/'src'))
from pi_prints import B,C,union,cut

def adapter(p):
 a=p['converter_adapter'];old=a['old_mounts_local_cad'];new=a['board_mounts_local_cad'];r=a['boss_r'];t=a['base_t'];h=a['riser_h'];rw=a['rib_w']
 # H, rather than a sheet: two short columns, one transverse rib and four bosses.
 xs=sorted({v[0]for v in old+new});ys=sorted({v[1]for v in old+new});mid=sum(ys)/2
 positive=[B(x-rw/2,ys[0],0,rw,ys[-1]-ys[0],t)for x in xs]
 positive.append(B(xs[0],mid-rw/2,0,xs[-1]-xs[0],rw,t))
 positive += [C(x,y,0,r,t)for x,y in old+new]
 positive += [C(x,y,t-.01,3.7,h+.01)for x,y in new]
 tools=[C(x,y,-.1,a['bore_d']/2,t+h+.2)for x,y in old+new]
 tools += [C(x,y,t-a['head_counterbore_depth'],a['head_counterbore_d']/2,a['head_counterbore_depth']+.1)for x,y in old]
 tools += [C(x,y,-.1,a['nut_af']/math.sqrt(3),a['nut_depth']+.1,6)for x,y in new]
 sh=cut(union(*positive),*tools)
 # Only the vertical, external ribs are filleted; screw and nut seats stay exact.
 candidates=[e for e in sh.edges()if e.geom_type==b.GeomType.LINE and abs((e@1).Z-(e@0).Z)>t-.1 and abs((e@1).X-(e@0).X)<1e-5 and abs((e@1).Y-(e@0).Y)<1e-5]
 try:sh=sh.fillet(.6,candidates)
 except (ValueError,RuntimeError):
  # Cylindrical eyes already round all outer corners; junctions retain full section.
  pass
 assert len(sh.solids())==1 and BRepCheck_Analyzer(sh.wrapped).IsValid()
 return sh

def converter(p):
 """New nominal board reference, only two real holes; retained input is never cut."""
 a=p['converter_adapter'];reference=next(r for r in read(BASE/'inputs/retained-parts.json')if r['id']=='PI_U1');path=BASE/'inputs'/reference['evidence']['step']['path'];assert sha(path)==reference['evidence']['step']['original']['sha256'];src=b.import_step(path)
 solids=src.solids();board=cut(B(0,0,0,48,25,1.6),*[C(x,y,-.1,1.5,1.8)for x,y in a['board_mounts_local_cad']])
 # Existing simplified detail was saved in drawing-Y-down. Map that frame once.
 detail=[s.mirror(b.Plane(origin=(0,12.5,0),x_dir=(1,0,0),z_dir=(0,1,0)))for s in solids if s.bounding_box().size.Z>2 or s.bounding_box().min.Z>1.59]
 return b.Compound(children=[board,*detail])

def main():
 p=read(ROOT/'design.json');a=p['converter_adapter'];sh=adapter(p);conv=converter(p);mech=baseline_rows();parts={r['id']:r for r in mech['rows']};checks=[];out=ROOT/'output/brep';out.mkdir(exist_ok=True)
 b.export_brep(sh,out/'CONVERTER_ADAPTER_LOCAL.brep')
 def vol(q):return 0 if q is None else sum(s.volume for s in q.solids())
 cache={}
 for i,y in enumerate(p['converters']['rows']):
  name=('PI_U'+str(i+1))if i<3 else('HUB_U'+str(i-2));x=p['converters']['x'];at=b.Pos(x,y,13)*sh;cu=b.Pos(x,y,18.6)*conv
  b.export_brep(at,out/(name+'_ADAPTER.brep'));b.export_brep(cu,out/(name+'.brep'))
  for id,s in [(name+'_ADAPTER',at),(name,cu)]:
   bb=s.bounding_box();entry=dict(id=id,level='supply',printed=id.endswith('ADAPTER'),color='#487d88'if id.endswith('ADAPTER')else'#326c97',bounds_mm=[list(bb.min),list(bb.max)],solids=len(s.solids()),volume_mm3=s.volume,valid=BRepCheck_Analyzer(s.wrapped).IsValid(),brep=dict(path=str(out/(id+'.brep')),sha256=sha(out/(id+'.brep'))),basis='R5 independent printed-post / PCB-top coordinate transform; purchased detail nominal, not physical qualification')
   parts[id]=entry
  hits=[]
  for r in mech['rows']:
   if r['id']==name or r['id'].startswith(name+'_BOLT')or r['id'].startswith(name+'_WASHER')or r['id'].startswith('X_'):continue
   if r['level']not in ['supply','devices']:continue
   for tag,body in [('adapter',at),('converter',cu)]:
    aa,az=parts[name+'_ADAPTER'if tag=='adapter'else name]['bounds_mm'];ba,bz=r['bounds_mm']
    if not all(min(az[k],bz[k])-max(aa[k],ba[k])>1e-5 for k in range(3)):continue
    other=cache.setdefault(r['id'],b.import_brep(r['brep']['path']));v=vol(body.intersect(other))
    if v>.01:hits.append(dict(member=tag,other=r['id'],overlap_mm3=round(v,4)))
  checks.append(dict(id=name,base_axes=[[x+u,y+v]for u,v in a['old_mounts_local_cad']],board_axes=[[x+u,y+v]for u,v in a['board_mounts_local_cad']],adapter_base_z=13,board_bottom_z=18.6,highest_nominal_z=18.6+13.8,reserved_top_z=18.6+18,upper_tray_bottom_z=40,collisions=hits))
  print(name,'interferences',hits,flush=True)
 updated=[parts[r['id']]for r in mech['rows']if not any(r['id'].startswith(n+'_BOLT')for n in ['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2'])]
 updated += [v for k,v in parts.items()if k.endswith('_ADAPTER')]
 save(ROOT/'output/adapter-mechanical.json',dict(revision='PI-2L-5',rows=updated,source_sha256=sha(ROOT/'design.json'),mounts=mech['mounts'],frame_joints=mech['frame_joints']))
 report=dict(revision='PI-2L-5',status='PASS'if all(not c['collisions']for c in checks)else'FAIL',checks=checks,adapter_volume_mm3=sh.volume,added_height_mm=5.6,purchased_reference_scope='new PCB only two contract holes; retained nominal details mapped from drawing coordinates; no vendor fit qualification',fasteners={'to_tray':'10 reused M3x8, original6.4mm PETG engagement retained by1.6mm head-bearing floor','to_board':'10 M3x8+M3 washer0.5+captured nut2.4; nominal screw tip0.3mm below adapter, actual floor clearance checked separately'},physical_fit='OPEN')
 save(ROOT/'output/adapter-check.json',report);print(report['status'],round(sh.volume,1),'mm3 per adapter',flush=True)
if __name__=='__main__':main()

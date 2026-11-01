"""Actual coded-shoe fit and bounded parameter propagation checks."""
import json,math
import build123d as b
from contract import R,D,M,B,OUT,save,sha
from cad import build,box,vol,cut,cyl
from inspect_mechanics import axis_data,fastener_measure
m=json.loads((OUT/'manifest.json').read_text());parts={r['id']:b.import_brep(OUT/'cad-cache'/(r['id']+'.brep'))for r in m['rows']};keys=[]
for target in['J1','J2','J3','J5']:
 anchor=next(k for k in m['keepers']if k['member']==target+'_MATE');bb=parts[target+'_MATE'].bounding_box();center=bb.center();key=M['connector_keys'][target];fy=M['connector_anchor_y']-1;roof=list(parts['P_KEY_'+target].bounding_box().max)[2]
 finbox=box(center.X+key-.41,fy-.01,M['floor']-.01,.82,2.02,26)
 actualfin=parts['P_BASE'].intersect(finbox);actualfin=b.Compound(children=list(actualfin))if isinstance(actualfin,b.ShapeList)else actualfin
 for source in['J1','J2','J3','J5']:
  sb=parts[source+'_MATE'].bounding_box().center();candidate=parts['P_KEY_'+source].moved(b.Location((center.X-sb.X,center.Y-sb.Y,0)))
  vv=vol(candidate.intersect(actualfin));holefit=True;detail=[]
  for sid in anchor['anchors']:
   a=axis_data(parts[sid]);probe=cyl(*a['origin'],1.5,2.4,tuple(a['direction']));hit=vol(candidate.intersect(probe));r=fastener_measure(parts[sid],parts['P_BASE'],candidate,1.5);fit=hit<.001 and r['head_supported'];holefit&=fit;detail.append(dict(screw=sid,shaft_interference_mm3=hit,head_bearing=r['bearing_area_mm2']))
  plughit=vol(candidate.intersect(parts[target+'_MATE']));accepted=vv<.001 and holefit and plughit<.001
  keys.append(dict(port=target,shoe=source,fin_interference_mm3=vv,plug_interference_mm3=plughit,base_fastener_fit=holefit,details=detail,status='PASS'if accepted==(source==target)else'FAIL'))
save(R/'reports/keying.json',dict(status='PASS'if all(k['status']=='PASS'for k in keys)else'FAIL',manifest_sha256=sha(OUT/'manifest.json'),cases=keys,scope='Attached coded shoe, fixed fin AND two base screw axes; bare uncoded plug alone is not proven impossible to misinsert.'))
# Rebuild only the two requested parameter variants, without serial exports/renders.
variants=[]
for name,params in[('case-size',{'width':M['width']+5,'depth':M['depth']+4,'height':M['height']+3}),('board-and-buck-move',{'board_xy':[M['board_xy'][0]+.5,M['board_xy'][1]+.5],'buck_xy':[M['buck_xy'][0]+.5,M['buck_xy'][1]+.5]})]:
 p,rows,e=build(params);checks={}
 if name=='case-size':
  bb=p['P_BASE'].bounding_box();hh=p['P_HOOD'].bounding_box();checks=dict(base_width=abs(bb.size.X-215)<.001,base_depth=abs(bb.size.Y-110)<.001,hood_height=abs(hh.max.Z-51)<.001,hood_axes_follow=e['hood_axes']==[(6,9),(187,9),(6,101),(187,101)])
 else:
  for ident in['B1_STRIPBOARD','U1','U2','U4_PCB','P_NANO_FRAME','P_DAC_FRAME','P_BUCK_FRAME','P_USB_CAP','W001']:
   aa=list(p[ident].bounding_box().min);zz=list(parts[ident].bounding_box().min);checks[ident]=all(abs(aa[k]-zz[k]-(.5 if k<2 and not(ident=='P_USB_CAP'and k==1)else 0))<.001 for k in range(3))
  for q in e['fasteners']:
   if q['id'].startswith(('SCREW_B1_','SCREW_NANO_','SCREW_DAC_','SCREW_BUCK_','SCREW_KEY_')):
    r=fastener_measure(p[q['id']],p[q['mate']],p[q['bearing']],1.5);checks[q['id']]=r['status']=='PASS'
 checks['valid_printed_bodies']=all(r['valid']and r['solids']==1 for r in rows if r['printed'])
 variants.append(dict(name=name,parameters=params,checks=checks,status='PASS'if all(checks.values())else'FAIL'))
save(R/'reports/parameters.json',dict(status='PASS'if all(v['status']=='PASS'for v in variants)else'FAIL',source_sha256=sha(R/'inputs/design.json'),variants=variants,scope='Actual rebuilt bodies/axes for stated parameter changes. No arbitrary parameter range guarantee.'))
print('KEYING',[(q['port'],q['shoe'])for q in keys if q['status']=='FAIL'],'PARAMETERS',[(v['name'],v['status'])for v in variants])

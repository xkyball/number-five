"""Single L5 contact source: larger open retention, lifted stack, explicit plug halves."""
import math,copy,collections
from shapely.geometry import Point,box,LineString
from common import ROOT,read,save,sha
from l4_layout import route

def main():
 d=read(ROOT/'design.json');e=read(ROOT/'inputs/l4-contact-baseline.json');old=copy.deepcopy(e);m=read(ROOT/'output/rest-mechanical.json');by={r['id']:r for r in m['rows']};report=[]
 e['physical_contact_aliases']=[a for a in e['physical_contact_aliases']if not a['start'].startswith(('PI_BANK.','HUB_BANK.'))]
 converters=dict(zip(['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2'],d['converters']['rows']))
 def adjust(c):
  p=list(c['point_mm']);owner=c.get('owner')
  if owner in converters:p[1]=converters[owner]+25-(p[1]-converters[owner]);p[2]+=5.6
  elif p[2]>=40:p[2]+=20
  if owner in ['HUB_POWER_PLUG','HUB_RP']:p[2]+=15
  c['point_mm']=p
 for collection in ['contacts','physical_nodes']:
  for c in e[collection].values():adjust(c)
 for bid,b in e['boards'].items():b['origin']=d['boards'][bid]['origin']
 # Bank connectors move off PCB onto retained short tails. Their solder pads remain in place.
 for bid in ['PI_A','HUB_A']:
  e['boards'][bid]['parts']=[p for p in e['boards'][bid]['parts']if p['kind']!='connector']
  for p in e['boards'][bid]['pads']:
   if '_BANK.'in p['id']:p['drill_mm']=2;p['basis']='1.5mm2 stranded wire through own2mm hole; no PCB terminal body'
  for p in e['boards'][bid]['pins']:
   if '_BANK.'in p['id']:p['drill_mm']=2;p['kind']='wire_anchor'
 for r in d['connector_mounts']:
  p=next(p for p in e['connector_pairs']if p['id']==r['id']);p.update(bounds_mm=r['bounds_mm'],origin=r['origin'],mating_direction='+X',qualification='PLANNING_APPROVED_FAMILY_REFERENCE_REAL_FIT_OPEN',geometry_basis='Manufacturer family + retained large26x30x24 4P keepout; no exact article qualification',tail_policy='Short individually insulated solder tails; fixed half retained by two ties in named carrier; all PCB solder joints below.',retention_id=('HUB_FUSE_CARRIER_'+r['board'][2])if r['board'].startswith('HF')else r['board']+'_CONNECTOR_CARRIER',rotated=False)
  p.pop('large_uncertainty_envelope',None);p['pin_points']={}
  x,y,z=r['origin']
  for n in range(1,r['poles']+1):
   yy=y+3.29+5.08*(n-1);ff=[x,yy,z-2];hh=[x+30,yy,z+6];p['pin_points'][str(n)]=ff
   for half,pt in [('F',ff),('H',hh)]:e['physical_nodes'][f'{p["id"]}.{half}{n}']=dict(net=p['pins'][str(n)],point_mm=pt,owner=p['board']if half=='F'else p['id'],half=half,retention=p['retention_id'])
  if p['id'].endswith('_BANK'):
   for n,c in enumerate(p['board_contacts'],1):
    mating=next(v for v in e['mating_edges']if v['pair']==p['id']and v['pin']==n);mating['start']=f'{p["id"]}.F{n}'
    pid=f'PIG_{p["id"]}_{n}';net=p['pins'][str(n)];e['physical_segments'].append(dict(id=pid,logical_ids=[w['id']for w in e['wires']if c in [w['start'],w['end']]],start=c,end=f'{p["id"]}.F{n}',net=net,conductor_mm2=1.5,item_id=305 if net=='GND'else 304,level='lower',module=p['board'],kind='module_tail',board_pad=None,board_contact=c,strip_start_mm=5,strip_end_mm=5,service_reserve_mm=18))
    sp=e['physical_nodes'][f'S_{p["id"]}_{n}'];sp['point_mm']=[193 if p['id']=='PI_BANK'else 212,y+3.29+5.08*(n-1),48]
 # Separate the physical sides of the existing interlevel/CAN interfaces as well.
 for name,origin,angle in [('LEVEL',d['connectors']['levels']['origin'],0),('CAN_WALL',d['connectors']['can_wall']['origin'],90)]:
  p=next(p for p in e['connector_pairs']if p['id']==name);p.update(origin=origin,qualification='PLANNING_APPROVED_FAMILY_REFERENCE_REAL_FIT_OPEN',mating_direction='+X'if angle==0 else'+Y',retention_id='POWER_KEY_SHROUD'if name=='LEVEL'else'CAN_KEY_SHROUD')
  for n in range(1,5):
   x,y,z=origin;v=3.29+5.08*(n-1);ff=[x,y+v,z-2]if angle==0 else[x-v,y,z-2];hh=[x+30,y+v,z+6]if angle==0 else[x-v,y+30,z+6];c=f'{name}.{n}';e['contacts'][c]['point_mm']=ff;e['physical_nodes'][c]['point_mm']=ff
   for half,pt in [('F',ff),('H',hh)]:e['physical_nodes'][f'{name}.{half}{n}']=dict(net=p['pins'][str(n)],point_mm=pt,owner=name,half=half)
   mating=next(q for q in e['mating_edges']if q['pair']==name and q['pin']==n);mating.update(start=f'{name}.F{n}',end=f'{name}.H{n}',net=p['pins'][str(n)])
   for w in e['physical_segments']:
    for side in ['start','end']:
     if w[side]==c:w[side]=f'{name}.H{n}'if name=='LEVEL'and w['level']=='interlevel'else f'{name}.F{n}'
 # Lower main harness above components, below the upper tray; fixed connector bodies are obstacles.
 e['routing_views']={}
 def group(c):return c.rsplit('_',1)[0]if c.startswith('S_')else c.split('.')[0]
 for lev in ['lower','upper','interlevel']:
  obs=[]
  if lev=='lower':
   obs=[dict(id=r['id'],rect=[r['bounds_mm'][0][0],r['bounds_mm'][0][1],r['bounds_mm'][1][0]-r['bounds_mm'][0][0],r['bounds_mm'][1][1]-r['bounds_mm'][0][1]])for r in d['connector_mounts']if r['board']!='GPIO_A']
   obs += [dict(id='FRAME_'+str(i),rect=[x-6,y-6,12,12])for i,(x,y)in enumerate(d['frame']['corner_axes'])]
  elif lev=='upper':
   for id in ['PI_CASE','HUB','CAN_PCB','GPIO_A']:
    a,z=by[id]['bounds_mm'];obs.append(dict(id=id,rect=[a[0],a[1],z[0]-a[0],z[1]-a[1]]))
  def owner(pt):
   hs=[o for o in obs if box(o['rect'][0],o['rect'][1],o['rect'][0]+o['rect'][2],o['rect'][1]+o['rect'][3]).covers(Point(pt[:2]))];return min(hs,key=lambda o:o['rect'][2]*o['rect'][3])['id']if hs else None
  segs=[w for w in e['physical_segments']if w['level']==lev and w['kind']!='module_tail'];bundled=collections.defaultdict(list)
  for w in segs:bundled[tuple(sorted([group(w['start']),group(w['end'])]))].append(w)
  links=[];groups={}
  for i,(key,ws)in enumerate(bundled.items(),1):
   id=lev[0].upper()+'B'+str(i).zfill(2);ends=[]
   for side in [0,1]:
    pts=[e['physical_nodes'][w[('start'if side==0 else'end')if group(w['start'])==key[0]else('end'if side==0 else'start')]]['point_mm']for w in ws];ends.append([sum(p[k]for p in pts)/len(pts)for k in [0,1]])
   links.append(dict(id=id,start=ends[0],end=ends[1],start_owner=owner(ends[0]),end_owner=owner(ends[1])));groups[id]=(key,ws)
  result=route(dict(obstacles=obs,links=links,buffer=2.25 if lev=='lower'else .5,spacing=0));spines={r['id']:r['points']for r in result['routes']};bundles=[];paths={}
  for id,(key,ws)in groups.items():
   spine=spines[id];bundles.append(dict(id=id,points=spine,members=[w['id']for w in ws],electrical_join=False,actual_shared_spine=True))
   for w in ws:
    a,z=[e['physical_nodes'][w[k]]['point_mm']for k in ['start','end']];sp=spine if group(w['start'])==key[0]else list(reversed(spine));plane=104 if lev=='upper'else 48
    # Library-routed local fanouts keep pins explicit; main paths share the bundle spine.
    def fan(aa,zz):
     if math.dist(aa[:2],zz[:2])<1e-6:return[aa[:2]]
     return route(dict(obstacles=obs,links=[dict(id='fan',start=aa[:2],end=zz[:2],start_owner=owner(aa),end_owner=owner(zz))],buffer=.5,spacing=0))['routes'][0]['points']
    xy=fan(a,sp[0])+sp+fan(sp[-1],z);clean=[xy[0]]
    for p in xy[1:]:
     if math.dist(clean[-1],p)>1e-6:clean.append(p)
    w['route_mm']=[a,*[[x,y,plane]for x,y in clean],z];w['bundle_id']=id;paths[w['id']]=clean
  # Visual obstacles remain module outlines; routed connector corridors stay in paths.
  display=[]
  for id in (['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2','PI_R1','PI_R2','PI_R3','HUB_R1','HUB_R2','PI_A','HUB_A','INPUT_A']if lev=='lower'else['PI_CASE','HUB','CAN_PCB','GPIO_A']if lev=='upper'else[]):
   a,z=by[id]['bounds_mm'];display.append(dict(id=id,rect=[a[0],a[1],z[0]-a[0],z[1]-a[1]]))
  e['routing_views'][lev]=dict(engine='libavoid-js0.5.0-beta.5',obstacles=display,routing_obstacles=obs,bundles=bundles,paths=paths)
 for w in e['physical_segments']:
  if w['kind']=='module_tail':
   a,z=[e['physical_nodes'][w[k]]['point_mm']for k in ['start','end']]
   if w.get('board_pad'):
    bb=e['boards'][w['module']];entry=next(q for q in bb['entries']if q['id']==w['board_pad']);ox,oy,oz=d['boards'][w['module']]['origin'];a=[ox+entry['xy'][0],oy,oz+entry['xy'][1]]if d['boards'][w['module']].get('upright')else[ox+entry['xy'][0],oy+entry['xy'][1],oz+1]
   plane=76 if w['module']=='GPIO_A'else 30
   target=[67.5,z[1]]if w['module']=='GPIO_A'else z[:2]
   rr=route(dict(links=[dict(id=w['id'],start=a[:2],end=target)],buffer=0))['routes'][0]['points'];w['route_mm']=[a,*[[x,y,plane]for x,y in rr],*([[67.5,z[1],72]]if w['module']=='GPIO_A'else[]),z]
  else:
   pts=w['route_mm'];a,z=[e['physical_nodes'][w[k]]['point_mm']for k in ['start','end']]
   def lead(c,p):
    if c.startswith('LEVEL.F'):return[p,[p[0],p[1],70.75],[22,p[1],70.75],[22,p[1],104],[p[0],p[1],104]]
    if c.startswith('CAN_WALL.F'):return[p,[p[0],p[1],73.75],[p[0],175.5,73.75],[p[0],175.5,104],[p[0],p[1],104]]
    return[p,[p[0],p[1],104 if w['level']=='upper'else 48]]
   if w['level']=='upper':w['route_mm']=lead(w['start'],a)+pts[1:-1]+list(reversed(lead(w['end'],z)))
   elif w['level']=='interlevel':
    n=int(w['end'].split('H')[-1]);xy=[62+4*(n-1),42];obs=e['routing_views']['lower']['routing_obstacles'];rr=route(dict(obstacles=obs,links=[dict(id=w['id'],start=a[:2],end=xy)],buffer=2.25))['routes'][0]['points'];w['route_mm']=[a,*[[x,y,48]for x,y in rr],[*xy,z[2]],[xy[0],z[1],z[2]],z]
   elif 'PI_R1.2'in [w['start'],w['end']]:
    forward=w['start']=='PI_R1.2';source,target=(a,z)if forward else(z,a);xy=[154.5,34];obs=e['routing_views']['lower']['routing_obstacles'];rr=route(dict(obstacles=obs,links=[dict(id=w['id'],start=xy,end=target[:2])],buffer=2.25))['routes'][0]['points'];new=[source,[source[0],source[1],30],[*xy,30],*[[x,y,48]for x,y in rr],target];w['route_mm']=new if forward else list(reversed(new));v=e['routing_views']['lower'];v['paths'][w['id']]=rr
    for bundle in v['bundles']:
     if w['id']in bundle['members']:assert len(bundle['members'])==1;bundle['points']=rr if forward else list(reversed(rr))
  if w['id']in ['RP_FEED','RP_CC']:
   a,z=[e['physical_nodes'][w[k]]['point_mm']for k in ['start','end']];rr=route(dict(links=[dict(id=w['id'],start=a[:2],end=z[:2])],buffer=0))['routes'][0]['points'];w['route_mm']=[a,*[[x,y,88.8]for x,y in rr],z];w['inside_compartment']='HUB_POWER_PLUG solder hood, reference envelope only; supplied hood access OPEN'
  w['route_length_mm']=round(sum(math.dist(a,z)for a,z in zip(w['route_mm'],w['route_mm'][1:])),2);w['cut_mm']=math.ceil(w['route_length_mm']+w['strip_start_mm']+w['strip_end_mm']+w['service_reserve_mm']);w['routing_library']='libavoid-js0.5.0-beta.5';w['physical_cut_release']=False
 for mapping in e['logical_wire_mapping']:
  logical=next(w for w in e['wires']if w['id']==mapping['id']);extra=[]
  for c in [logical['start'],logical['end']]:
   if c.startswith(('PI_BANK.','HUB_BANK.')):
    name,n=c.split('.');extra += [f'CLAMP_{name.split("_")[0]}_{n}',f'PIG_{name}_{n}']
  mapping['physical_segments']=list(dict.fromkeys(mapping['physical_segments']+extra))
 for w in e['wires']:
  physical=next(q for q in e['physical_segments']if q['id']==w['id']);w.update(route_mm=physical['route_mm'],route_length_mm=physical['route_length_mm'],cut_mm=physical['cut_mm'],route_scope='L5 library-routed plan; local vertical leads/bends remain real assembly acceptance',physical_cut_release=False)
 # Intact data leads: original plugs, explicit uncut service volume, no hidden missing length.
 host,can=e['intact_data_cables'];host['waypoints_mm']=[[168,98,77],[190,98,77],[190,54,77],[154,54,77],[154,10,77],[184,10,77],[184,52,77],[184,52,90],[184,55,90]];host['route_length_mm']=270;host['service_storage_status']='270mm original lead: R22 right turn and front U service bow; real bend/diameter and exact loop length check OPEN';host['qualification']='PLANNING_CASE_R22_D4_5_REAL_BEND_OPEN';host['storage_plan']=d['usb_service'];can['waypoints_mm']=[[137,116,77],[167,116,107],[171,37,107],[111,37,107],[141,67,107],[141,67,134.5],[98,173,70.7]];can['route_length_mm']=None;can['service_storage_status']='Capacity case only: up to1m intact cable, R30, up to5 stacked turns atX141/Y37/Z107..134.5. Actual length and tangent exit shapes not established.';can['qualification']='OPEN_ACTUAL_LENGTH_AND_CONTINUOUS_RADIUS_ROUTE';can['storage_plan']=d['usb_service']
 assert [(w['id'],w['start'],w['end'],w['net'])for w in e['wires']]==[(w['id'],w['start'],w['end'],w['net'])for w in old['wires']]
 assert e['components']==old['components']and [p['pins']for p in e['connector_pairs']]==[p['pins']for p in old['connector_pairs']]
 e['manufacturing_status']='L5_PRINT_GEOMETRY_AND_NOMINAL_RETENTION_PASS_REAL_PARTS_AND_CABLE_PROBE_OPEN';e['connector_placement']=dict(engine='Google OR-Tools CP-SAT',status='OPTIMAL',module_pairs=9,coded_pairs=2,family_geometry_only=True,qualified_real_fit=False)
 for row in e['detachable_modules']:row['nominal_retention']='PASS_R5_CARRIER_AND10MM_WITHDRAWAL';row['real_qualification']='OPEN_ACTUAL_FIT_PIN_ORDER_INSULATION_TIE_PULL_CURRENT'
 if(ROOT/'delivery/daten/usb-service.json').exists():
  usb=read(ROOT/'delivery/daten/usb-service.json')
  for cable in e['intact_data_cables']:
   p=next(p for p in usb['paths']if p['id']==cable['id']);cable.update(waypoints_mm=p['points_mm'],route_length_mm=p['length_mm'],service_storage_status='CONTINUOUS_REFERENCE_CASE_LENGTH_AND_RADIUS_CHECKED; exact OCCT report separate; supplied cable properties remain OPEN',qualification=p['real_qualification'],reference_length_mm=p['nominal_length_mm'])
 e.update(revision='PI-2L-5',drawing_revision='L5',status='PROVISIONAL_FAMILY_HEIGHT_LAYOUT_REAL_CABLE_PROBE_OPEN',cut_release=False,physical_release=False,baseline_revision='PI-2L-4',baseline_sha256=sha(ROOT/'inputs/l4-contact-baseline.json'),layout_source_sha256=sha(ROOT/'design.json'),generator_sha256=sha(__file__),height_release=d['height_release']);save(ROOT/'delivery/daten/kontaktvertrag.json',e)
 save(ROOT/'output/contact-height-check.json',dict(status='PASS_LOGICAL_GRAPH_AND_PINMAP_EQUALITY_ROUTES_PROVISIONAL',logical_wires=64,physical_segments=len(e['physical_segments']),banks_converted_to_short_tails=8,all_routes_library_supported=True,graph_equal_to_L4=True,pinmaps_unchanged=True,full_mechanical_wire_collision_proof=False,exact_USB_storage=False,cut_release=False))
 print('CONTACT R5',len(e['physical_segments']),'segments; logical64 unchanged; local service proof OPEN',flush=True)
if __name__=='__main__':main()

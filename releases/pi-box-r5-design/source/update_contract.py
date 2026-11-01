"""Publish actual converter-frame deltas; dependent L4 cut routes are invalidated."""
import copy,csv
from common import ROOT,BASE,read,save,sha
def main():
 e=read(ROOT/'inputs/l4-contact-baseline.json');d=read(ROOT/'design.json');old=copy.deepcopy(e);changed=[]
 names=['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2'];ys=dict(zip(names,d['converters']['rows']))
 for id,c in e['contacts'].items():
  owner=c.get('owner')
  if owner not in names:continue
  before=c['point_mm'];after=[before[0],ys[owner]+25-(before[1]-ys[owner]),before[2]+5.6];c['point_mm']=after
  if id in e['physical_nodes']:e['physical_nodes'][id]['point_mm']=after
  changed.append(dict(contact=id,module=owner,net=c['net'],old_point_mm=before,new_point_mm=after,status='POSITION_UPDATED_ROUTING_OPEN'))
 ids={c['contact']for c in changed};blocked=[]
 for collection in ['wires','physical_segments']:
  for w in e[collection]:
   if w['start']in ids or w['end']in ids:
    w['previous_L4_route_mm']=w.pop('route_mm',None);w['previous_L4_cut_mm']=w.pop('cut_mm',None);w['route_mm']=None;w['cut_mm']=None;w['route_status']='OPEN_REROUTE_WITH_FINAL_CONNECTOR_LAYOUT';blocked.append(dict(collection=collection,id=w['id']))
 assert [(w['id'],w['start'],w['end'],w['net'])for w in e['wires']]==[(w['id'],w['start'],w['end'],w['net'])for w in old['wires']]
 assert e['components']==old['components'];assert [p['pins']for p in e['connector_pairs']]==[p['pins']for p in old['connector_pairs']]
 e.update(revision='PI-2L-5',drawing_revision='L5-DRAFT',status='DRAFT_MECHANICAL_INTERFACES_AND_ROUTES_OPEN',cut_release=False,physical_release=False,baseline_revision='PI-2L-4',baseline_sha256=sha(ROOT/'inputs/l4-contact-baseline.json'),coordinate_frame_changes=changed,blocked_routes=blocked,qualification='Electrical graph unchanged; converter contact positions corrected and raised. Old connector placements/routing are historical references; no L5 cut/assembly release.')
 save(ROOT/'delivery/daten/kontaktvertrag-L5-Entwurf.json',e)
 with (ROOT/'delivery/daten/wandler-anschlussaenderungen.csv').open('w',newline='')as f:
  wr=csv.DictWriter(f,fieldnames=['contact','module','net','old_point_mm','new_point_mm','status']);wr.writeheader();wr.writerows(changed)
 save(ROOT/'output/contact-delta-check.json',dict(revision='PI-2L-5',status='PASS_GRAPH_EQUALITY_ROUTES_OPEN',contact_positions_updated=len(changed),invalidated_dependent_routes=blocked,electrical_components_identical=True,logical_endpoints_and_nets_identical=True,connector_pin_maps_identical=True,full_L5_routing=False))
 print('CONTACT DELTA',len(changed),'positions,',len(blocked),'dependent route records explicitly OPEN')
if __name__=='__main__':main()

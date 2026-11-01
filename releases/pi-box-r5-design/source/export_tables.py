"""All workshop tables are projections of the single physical contact contract."""
import json,csv,collections,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];DATA=ROOT/'delivery/daten'
def table(name,rows):
 if not rows:return
 with(DATA/name).open('w',newline='')as f:
  wr=csv.DictWriter(f,fieldnames=list(dict.fromkeys(k for r in rows for k in r)));wr.writeheader();wr.writerows([{k:json.dumps(v,ensure_ascii=False)if isinstance(v,(dict,list))else v for k,v in r.items()}for r in rows])
def main():
 e=json.loads((DATA/'kontaktvertrag.json').read_text());table('physische-kabel.csv',e['physical_segments']);table('leitungszuordnung-L3-L5.csv',e['logical_wire_mapping']);table('steckkontakte.csv',[dict(connector=p['id'],board=p.get('board'),item_id=p.get('item_id',237),pin=n,net=net,fixed_half=p['id']+'.F'+n,loose_half=p['id']+'.H'+n,front_views='opposed faces mirrored; pin1 requires article continuity check',bounds_mm=p.get('bounds_mm'),qualification=p.get('qualification',p.get('real_pin_order')))for p in e['connector_pairs']for n,net in p['pins'].items()])
 table('platinenleitungen.csv',[w for w in e['physical_segments']if w['kind']=='module_tail']);table('kabelwege.csv',[dict(id=w['id'],logical_ids=w['logical_ids'],level=w['level'],start=w['start'],end=w['end'],points_mm=w['route_mm'],route_length_mm=w['route_length_mm'],cut_mm=w['cut_mm'],engine=w['routing_library'],bundle_id=w.get('bundle_id'))for w in e['physical_segments']]);table('buendel.csv',[dict(level=level,**b)for level,v in e['routing_views'].items()for b in v['bundles']]);table('steckerpaarungen.csv',e['mating_edges']);table('kontaktaliase.csv',e['physical_contact_aliases'])
 joins=[]
 for id,node in e['physical_nodes'].items():
  if node['owner']!='harness':continue
  wires=[w for w in e['physical_segments']if id in [w['start'],w['end']]]
  joins.append(dict(id=id,net=node['net'],point_mm=node['point_mm'],members=[w['id']for w in wires],method='gemeinsame isolierte Lötverzweigung; keine elektrische Verbindung zu anderen Bündeladern',relief='beidseits am nächsten vorhandenen Rahmen-Schraubpunkt',strip_plan_mm=5,real_joint_and_insulation='OPEN'))
 table('loetabzweige.csv',joins)
 note={'revision':'PI-2L-5','logical_wires':64,'physical_segments':len(e['physical_segments']),'new_paired_connectors':7,'top_bridge_segments':sum(len(b['top_routes'])for b in e['boards'].values()),'source_sha256':hashlib.sha256((DATA/'kontaktvertrag.json').read_bytes()).hexdigest(),'isolation_material':e['isolation_material'],'manufacturing_status':e['manufacturing_status']}
 (DATA/'tabellenstand.json').write_text(json.dumps(note,ensure_ascii=False,indent=2)+'\n');print('L5 tables:64 logical →',len(e['physical_segments']),'physical segments')
if __name__=='__main__':main()

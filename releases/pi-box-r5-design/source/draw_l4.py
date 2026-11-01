"""Workshop drawings: tool-routed functional views, bundle views, physical halves."""
import json,re,math,hashlib,collections
from shapely.geometry import LineString,Point,box
import draw_plans as base
from l4_layout import route,cp_model
E=base.E;D=base.D;ROOT=base.ROOT
ALIASES={w['id']:f'W{i:02}'for i,w in enumerate(E['wires'],1)}

def key(c):
 if '_BANK.'in c or c.startswith('LEVEL.'):return c
 if c.startswith(('IN.','D0.','TVS.','C0.')):return 'INPUT_A'
 if c.startswith('GPIO.'):return 'GPIO_A'
 if c.startswith('JGPIO.'):return 'GPIO_BUCHSE'
 return c.split('.')[0]

def functional(doc,title,wires,extras=()):
 nodes={};edges=[]
 for w in wires:
  a,z=key(w['start']),key(w['end'])
  if a==z:continue
  for n in [a,z]:nodes.setdefault(n,dict(id=n,width=112,height=48,ports=[],layoutOptions={'elk.portConstraints':'FIXED_SIDE'}))
  for side,n,c in [('s',a,w['start']),('t',z,w['end'])]:
   pid=w['id']+':'+side;nodes[n]['ports'].append(dict(id=pid,width=2,height=2,layoutOptions={'elk.port.side':'EAST'if side=='s'else'WEST'}))
  edges.append(dict(id=w['id'],sources=[w['id']+':s'],targets=[w['id']+':t'],labels=[dict(text=ALIASES[w['id']],width=23,height=12)]))
 graph=dict(id='root',layoutOptions={'elk.algorithm':'layered','elk.edgeRouting':'ORTHOGONAL','elk.direction':'RIGHT','elk.spacing.nodeNode':'35','elk.layered.spacing.nodeNodeBetweenLayers':'48','elk.layered.spacing.edgeNodeBetweenLayers':'14','elk.randomSeed':'4','elk.separateConnectedComponents':'true'},children=list(nodes.values()),edges=edges)
 result=route(dict(engine='elk',graph=graph));layouts=ROOT/'delivery/daten/diagramme';layouts.mkdir(exist_ok=True);(layouts/(re.sub('[^A-Za-z0-9]+','-',title)+'.json')).write_text(json.dumps(dict(engine='elkjs0.12.1',source_wire_ids=[w['id']for w in wires],graph=result),ensure_ascii=False,indent=2)+'\n')
 doc.page(title+' · Funktionsplan aus ELK');scale=min(1.05,1100/max(result['width'],1),490/max(result['height'],1));ox,oy=45,125
 def pt(x,y):return [ox+x*scale,oy+y*scale]
 for e in result['edges']:
  w=next(w for w in wires if w['id']==e['id']);co=base.wirecolor(w['net'],w['conductor_mm2'])
  for sec in e.get('sections',[]):
   pts=[sec['startPoint'],*sec.get('bendPoints',[]),sec['endPoint']];doc.line([pt(p['x'],p['y'])for p in pts],co,1.2)
  for label in e.get('labels',[]):
   x,y=pt(label['x'],label['y']);doc.rect(x-2,y-2,28,13,'white','none');doc.text(x,y+8,label['text'],8.5,co)
 for n in result['children']:
  x,y=pt(n['x'],n['y']);doc.rect(x,y,n['width']*scale,n['height']*scale,'#edf3f6','#7894a3');doc.text(x+n['width']*scale/2,y+17,n['id'],max(8,9*scale),bold=True,anchor='middle')
  hint='Pins: Anschlusstabelle'
  if '_D'in n['id']:hint='A → K / Ring'
  elif '_R'in n['id']:hint='0,1 Ohm'
  elif n['id'].startswith('HF'):hint='Sicherung auf Lötplatine'
  doc.text(x+n['width']*scale/2,y+33,hint,7.5,base.MUTED,anchor='middle')
 y=665;doc.text(40,y,'W-Kennungen verweisen auf die vollständige Anschlusstabelle. Stecker und Platinenleitungen: jeweiliger Lötplan.',10);y+=24
 for text in extras:y=doc.para(40,y,text,1110,10)+8
 doc.end()

def connector_page(doc,bid):
 ps=[p for p in E['connector_pairs']if p.get('board')==bid];doc.page(bid+' · Stecker, Gegenstücke und Platinenleitungen')
 y=122
 for p in ps:
  if p['id']=='GPIO_SOCKET':
   doc.text(40,y,'GPIO_SOCKET · gemeinsame Buchsenleiste · DB182',12,bold=True);y+=24
   doc.text(40,y,'Pi: physische Pins2/4 = Plus,6/9 = Masse; keine BCM-Signalnummern.',10);y+=35
   for xx,mirror,title in [(100,False,'Pi-Stifte F'),(580,True,'Buchsenkontakte H, gegenüber')]:
    doc.text(xx,y,title,10,bold=True)
    for row,ns in enumerate([[1,3,5,7,9],[2,4,6,8,10]]):
     if mirror:ns=list(reversed(ns))
     for j,n in enumerate(ns):
      co=base.BROWN if n in [2,4]else base.BLUE if n in [6,9]else'#9daeb9';doc.rect(xx+j*46,y+15+row*34,38,27,'#edf3f6',co);doc.text(xx+j*46+19,y+34+row*34,n,10,co,anchor='middle')
   y+=112
   for n in [2,4,6,9]:doc.text(45,y,f'GPIO.P{n} → GPIO_SOCKET.H{n} ↔ Pi-Pin {n}',10);y+=23
   y+=10;continue
  nets=p['pins'];contacts=p.get('board_contacts',[]);doc.text(40,y,p['id']+f" · DB {p.get('item_id',237)} · {len(nets)} Pole",12,bold=True)
  doc.text(510,y,'F: platinengebunden',10,bold=True);doc.text(800,y,'H: Kabelbaum / abziehbar',10,bold=True);y+=15
  # Opposed face views are deliberately mirrored; the table is functional order.
  for x,order in [(535,list(range(1,len(nets)+1))),(830,list(range(len(nets),0,-1)))]:
   for i,n in enumerate(order):doc.rect(x+i*27,y,24,22,'#dfeaf0');doc.text(x+i*27+12,y+15,n,10,anchor='middle')
  doc.text(40,y+16,'Kontaktseiten gegenüber: Ansichten gespiegelt.',9,base.MUTED);y+=41
  for n,net in nets.items():
   c=contacts[int(n)-1]if len(contacts)>=int(n)else p['id']+'.'+n
   entries=[ent for ent in E['boards'][bid]['entries']if ent.get('connector')==p['id']and ent.get('connector_pin')==int(n)]
   tail=('; Loch '+str(1+next(i for i,q in enumerate(E['boards'][bid]['pads'])if q.get('entry_id')==entries[0]['id'])))if entries else'; PCB-Kontakt'
   doc.text(45,y,f'Pin {n}: {net}',10,bold=True);doc.text(245,y,c+tail,9)
   co=base.wirecolor(net,1.5);link=route(dict(links=[dict(id=p['id']+'.'+n,start=[460,y-4],end=[600,y-4])],buffer=0))['routes'][0]['points'];doc.line(link,co,1.3)
   doc.rect(600,y-12,20,16,'#edf3f6',co);doc.text(610,y,n,8,anchor='middle');doc.line([[620,y-4],[839,y-4]],co,1,True);doc.rect(839,y-12,20,16,'#edf3f6',co);doc.text(849,y,n,8,anchor='middle')
   doc.text(890,y,f'{p["id"]}.F{n} ↔ .H{n}',8.5);y+=24
  y+=19
  if y>665:doc.end();doc.page(bid+' · Steckverbindungen (Fortsetzung)');y=125
 doc.para(40,y+5,'Bei kurzen Platinenleitungen: Enden von oben durch die angegebenen Löcher führen und ausschließlich unten verlöten. Jede Lötfahne einzeln isolieren; Zugentlastung vor der Lötstelle und vor dem Stecker.',1100,10)
 doc.para(40,y+54,'Ein einzelner Leiter je Schraubkontakt. Sammelabzweige liegen im Kabelbaum vor der Anschlussader. GPIO-Ausgang bleibt ein angelötetes Kabel mit gemeinsam abziehbarer Buchsenleiste.',1100,10)
 doc.para(40,744,'L5: offene Halter mit größerer Planhülle; Oberplatte+20mm. Hersteller-Familienbeleg vorläufig freigegeben, reale Lieferpassung und Pinfolge vor Montage prüfen.',1100,9,base.RED)
 doc.end()

def physical_tables(doc,level):
 rows=[]
 for w in E['physical_segments']:
  if w['level']!=level:continue
  a,z=w['start'],w['end'];at='';zt=''
  if w['kind']=='module_tail':
   b=E['boards'][w['module']];hole=1+next(i for i,p in enumerate(b['pads'])if (p.get('entry_id')==w['board_pad']if w.get('board_pad')else p.get('contact')==w['board_contact']));at=f"{w['module']}, Loch {hole}; unten an {a}; Isolation bis Durchlass";zt='PCB-Gegenstück: Lötfahne einzeln schrumpfen; zwei getrennte Kabelbinder, real zugprüfen'
  elif w['kind']=='single_clamp_lead':at='Kabelbaum-Abzweig einzeln löten/schrumpfen';zt='eine Ader im abziehbaren Schraubstecker; CN1.5-L8'
  else:
   old=next(q for q in E['wires']if q['id']==w['id']);at=old['terminations']['start']['attachment']+'; Bezug '+old['start'];zt=old['terminations']['end']['attachment']+'; Bezug '+old['end']
  rows.append([ALIASES.get(w['id'],'PIG')+' / '+w['id']+'\n'+w['net'],a+'\n'+at,z+'\n'+zt,f"{w['conductor_mm2']} mm² / DB {w['item_id']}\n{w['cut_mm']} mm\nblank {w['strip_start_mm']} / {w['strip_end_mm']}",w.get('factory_lead_policy','Platinenleitung / Anschlussader; erst am vormontierten Modul ablängen')])
 doc.table('Physischer Kabelbaum · sämtliche Abschnitte und beide Enden',['Kennung / Netz','Ende A','Ende B','Ader / Zuschnitt / blank','Bearbeitung'],[190,300,300,150,183],rows,8.5)
 doc.table('Steck- und Lötbefestigung · Entlastung und Sichtprüfung',['Anschluss','Ausführung','Mechanische Entlastung'],[240,500,383],[['Schraubhälften H','1,5-mm²-Litze mit passender CN1.5-L8-Hülse, ungezinnte Ader; 8 mm Plan-Abisolierung; tatsächliche Klemmung prüfen.','An benannter offener Halterung; kurzer Abziehbogen.'],['PCB-Hälften F / Platinenleitungen','Ader an Lötfahne; jede Fahne einzeln und den Verbund zusätzlich isolieren. Keine blanken losen PCB-Stecker.','Körper und Leitung getrennt anbinden. Nominal10mm Abziehweg geprüft; reale Zugprobe offen.'],['Lötverzweigung / Leistungsbauteil','Metall direkt verlöten, jeden Stoß einzeln schrumpfen; Widerstandskörper zur Kühlung frei lassen.','Vor und hinter dem Abzweig am nahen vorhandenen Schraubpunkt. F108-Werksadern von Zusatzlitze abziehen.'],['GPIO-Buchsenleiste','Gemeinsamer abziehbarer Verbund: Pi2/4 Plus, Pi6/9 Masse. Jede Kontaktlötstelle einzeln isolieren.','Adern am oberen Träger entlasten. Belastbarkeit, richtige Reihe und realer mechanischer Verbund bleiben Abnahmepunkte.']])

def spatial(doc,level,selection=None,title=''):
 view=E['routing_views'][level];segs=[w for w in E['physical_segments']if w['level']==level and w['kind']=='harness'and (selection is None or selection(w))and w['id']in view['paths']]
 doc.page('Feste Montagepositionen · '+title+' · Bündelübersicht aus libavoid')
 ox,oy,sc=45,132,2.85;doc.rect(ox,oy,216*sc,194*sc,'#fafcfd','#b0c0ca')
 obstacles=view['obstacles']
 for o in obstacles:
  x,y,w,h=o['rect'];doc.rect(ox+x*sc,oy+y*sc,w*sc,h*sc,'#edf2f5','#9bb0be',.6)
 # Same module pair = one bundle. Local pin fan-outs are on the module sheets.
 selected={w['id']for w in segs};members=[];drawn=[];labels=[]
 for item in view['bundles']:
  ws=[w for w in segs if w['id']in item['members']]
  if not ws:continue
  pts=item['points'];line=LineString(pts)
  # Draw only the corridor outside module footprints; pin detail is not concealed.
  corridors=line
  for o in obstacles:
   x,y,w,h=o['rect'];corridors=corridors.difference(box(x+.05,y+.05,x+w-.05,y+h-.05))
  for l in corridors.geoms if hasattr(corridors,'geoms')else[corridors]:
   if l.is_empty or l.geom_type!='LineString':continue
   pp=[[ox+x*sc,oy+y*sc]for x,y in l.coords];doc.line(pp,'#4d7b94',2.0);drawn.append(LineString(pp).buffer(2.2))
  # Bundle marks go in the free corridor, never inside a component rectangle.
  label=item['id'];candidates=[]
  for a,z in zip(pts,pts[1:]):
   if math.dist(a,z)>6:
    for f in [.2,.35,.5,.65,.8]:candidates.append((a[0]*(1-f)+z[0]*f,a[1]*(1-f)+z[1]*f))
  options=[]
  for xx,yy in candidates:
   x,y=ox+xx*sc,oy+yy*sc
   for vertical in [False,True]:
    ww,hh=(12,26)if vertical else(26,12);area=box(x-ww/2,y-hh/2,x+ww/2,y+hh/2)
    if any(area.intersects(box(ox+o['rect'][0]*sc,oy+o['rect'][1]*sc,ox+(o['rect'][0]+o['rect'][2])*sc,oy+(o['rect'][1]+o['rect'][3])*sc))for o in obstacles):continue
    if any(all(abs(a-b)<.01 for a,b in zip(area.bounds,q[:4]))for q in options):continue
    options.append([x-ww/2,y-hh/2,x+ww/2,y+hh/2,vertical])
  labels.append((label,options))
  members.append([label,', '.join(ALIASES.get(w['id'],w['id'])for w in ws),ws[0]['start']+' → '+ws[0]['end']])
 # Global label assignment prevents the former cluster of overlapping bundle IDs.
 model=cp_model.CpModel();vs={};penalties=[]
 for label,options in labels:
  vs[label]=[model.new_bool_var(label+'_'+str(i))for i in range(len(options))]
  if options:model.add_at_most_one(vs[label]);penalties.extend((i-100000)*v for i,v in enumerate(vs[label]))
 for i,(a,opts)in enumerate(labels):
  for b,zs in labels[:i]:
   for ai,aa in enumerate(opts):
    for bi,bb in enumerate(zs):
     if box(*aa[:4]).buffer(.8).intersects(box(*bb[:4])):model.add_bool_or([vs[a][ai].Not(),vs[b][bi].Not()])
 model.minimize(sum(penalties));solver=cp_model.CpSolver();solver.parameters.num_search_workers=1;solver.parameters.max_time_in_seconds=10;status=solver.solve(model)
 if status not in [cp_model.OPTIMAL,cp_model.FEASIBLE]:raise RuntimeError('No non-overlapping bundle labels '+title)
 unplaced=[]
 for label,opts in labels:
  if not opts or not any(solver.value(v)for v in vs[label]):unplaced.append(label);continue
  x0,y0,x1,y1,vertical=opts[next(i for i,v in enumerate(vs[label])if solver.value(v))];doc.rect(x0,y0,x1-x0,y1-y0,'white','none')
  if vertical:
   cx,cy=(x0+x1)/2,(y0+y1)/2;doc.c.saveState();doc.c.translate(cx,base.H-cy);doc.c.rotate(90);doc.c.setFont('Plan',8);doc.c.setFillColor(base.HexColor(base.INK));doc.c.drawCentredString(0,-2.5,label);doc.c.restoreState();doc.svg.append(f'<text x="{cx}" y="{cy+2.5}" text-anchor="middle" font-size="8" fill="{base.INK}" transform="rotate(-90 {cx} {cy})">{label}</text>');doc.texts.append(label);doc.bounds.append([x0,y0,x1,y1,label])
  else:doc.text((x0+x1)/2,y1-2,label,8,anchor='middle')
 for o in obstacles:
  x,y,w,h=o['rect']
  if o['id']=='GPIO_A':
   doc.line([[ox+(x+w)*sc,oy+(y+h/2)*sc],[ox+(x+w+2)*sc,oy+(y+h/2)*sc]],'#8195a0',.6);doc.text(ox+(x+w+3)*sc,oy+(y+h/2)*sc+3,o['id'],8,bold=True)
  else:
   yy=y+3 if o['id']in ['PI_A','HUB_A','INPUT_A']else y+h/2;doc.text(ox+(x+w/2)*sc,oy+yy*sc+3,o['id'],8,bold=True,anchor='middle')
 for p in E['connector_pairs']:
  if not p.get('bounds_mm')or (p['board']=='GPIO_A')!=(level=='upper'):continue
  aa,zz=p['bounds_mm'];x,y=ox+aa[0]*sc,oy+aa[1]*sc;w,h=(zz[0]-aa[0])*sc,(zz[1]-aa[1])*sc
  doc.rect(x,y,w,h,'#dceadf','#6f9980',.7);doc.text(x+w/2,y+h/2,p['id'],7.5,bold=True,anchor='middle');doc.text(x+w/2,y+h/2+11,'F / H',7,anchor='middle')
 # Fixed interlevel/CAN interface markers make every external endpoint identifiable.
 if level=='upper':
  for name,cs in [('LEVEL',[f'LEVEL.{i}'for i in range(1,5)]),('CAN_WALL',[f'CAN_WALL.{i}'for i in range(1,4)])]:
   pts=[E['contacts'][c]['point_mm']for c in cs];x,y=sum(p[0]for p in pts)/len(pts),sum(p[1]for p in pts)/len(pts);doc.rect(ox+x*sc-22,oy+y*sc-10,44,20,'#e8e0ce','#9d885c');doc.text(ox+x*sc,oy+y*sc+3,name,7,bold=True,anchor='middle')
 y=142;doc.text(700,y,'Bündel / enthaltene Leitungen',12,bold=True);y+=30
 for label,ids,ends in members:
  doc.text(700,y,label,8.5,bold=True);doc.text(747,y,ids,8.5);y+=19
 doc.para(700,712,'Bündellinien verbinden keine Netze elektrisch. Alle zugeordneten Adern folgen dem gemeinsamen Bibliotheksweg. Lokale Pinabgänge stehen vollständig in den Anschlussplänen. Kurze, hier unbeschriftete Modulabgänge sind in der folgenden Bündeltabelle über ihre beiden Endpunkte zugeordnet.',440,9)
 doc.end()
 doc.table('Bündelzuordnung · '+title,['Bündel','Leitungen','Von / nach (Pinabgänge: Kabeltabelle)'],[110,400,613],members)
 return dict(level=level,title=title,bundles=[dict(id=a,members=b,endpoints=c)for a,b,c in members],representation='actual library-routed shared spine; exact local fanouts and individual paths retained in physical_segments',label_engine='Google OR-Tools CP-SAT',labels_without_corridor_space=unplaced)

def usb_view(doc):
 doc.page('USB-Service: geplanter Stauraum; vollständige Kabelwege noch offen')
 ox,oy,sc=45,132,2.85
 doc.rect(ox,oy,216*sc,194*sc,'#fafcfd','#b0c0ca')
 m=json.loads((ROOT/'output/rest-mechanical.json').read_text())
 for id in ['PI_CASE','HUB','CAN_PCB','HOST_ADAPTER','HOST_USB_PLUG','CAN_PI_USB_PLUG','CAN_USB_PLUG']:
  r=next(r for r in m['rows']if r['id']==id);a,z=r['bounds_mm'];doc.rect(ox+a[0]*sc,oy+a[1]*sc,(z[0]-a[0])*sc,(z[1]-a[1])*sc,'#edf2f5','#9bb0be',.6)
  if id in ['PI_CASE','HUB','CAN_PCB']:doc.text(ox+(a[0]+z[0])/2*sc,oy+(a[1]+z[1])/2*sc,id,9,bold=True,anchor='middle')
 for center,radius,label in [([143,33],22,'HOST R22'),([141,37],30,'CAN R30')]:
  points=[[ox+(center[0]+radius*math.cos(i*math.pi/32))*sc,oy+(center[1]+radius*math.sin(i*math.pi/32))*sc]for i in range(65)];doc.line(points,'#578449',1.3,True)
  doc.text(ox+center[0]*sc,oy+(center[1]-(10 if radius==22 else-10))*sc,label,8,anchor='middle')
 doc.text(700,155,'USB_HOST: 270mm bleiben intakt',13,bold=True)
 doc.para(700,185,'Hub15mm angehoben. Dadurch kann das Kabel unter dem Hub statt im5,5mm schmalen Seitenspalt laufen. R22 und Ø4,5 sind Planannahmen.',430,10)
 doc.para(700,280,'USB_CAN: Kabel ebenfalls intakt. Vorsorglicher Stauraum für bis1m, R30 und bis5 Windungen über den Modulen; die reale Länge ist nicht belegt.',430,10)
 doc.para(700,390,'Die gestrichelten Bögen zeigen Kapazitätsräume, keinen fertig geprüften Kabelverlauf. Durchgängige Biegeradien, Anschlusstangenten und reale Restlänge sind noch OPEN.',430,10,base.RED)
 doc.para(700,530,'Der bisherige82mm-Außenkasten passt damit nicht mehr. Die Außenhülle wird wie beauftragt nach der realen Stecker-/Kabelanprobe festgelegt.',430,10)
 doc.para(700,660,'Keine Kabel kürzen und aus diesem Blatt keine Betriebs- oder vollständige Montagefreigabe ableiten.',430,10,base.RED)
 doc.end()

def main():
 for p in base.SVG.glob('*.svg'):p.unlink()
 for name,boards in [('01-Loetplan-Pi',['PI_A']),('02-Loetplan-Hub',['HUB_A','HF1_A','HF2_A']),('03-Loetplan-Eingang',['INPUT_A']),('04-Loetplan-GPIO',['GPIO_A'])]:
  doc=base.Doc(name,base.NAMES[boards[0]])
  for bid in boards:base.board_pages(doc,E['boards'][bid]);connector_page(doc,bid)
  doc.finish()
 views=[]
 doc=base.Doc('05-Verkabelung-Unten','Verkabelung · untere Ebene')
 isbranch=lambda w:bool(re.match(r'(PI|HUB)_(F[123]_(FEED|LOAD)|U[123]_RAW|R[123]_(IN|OUT))$',w['id']))
 lower=[w for w in E['wires']if w['level']=='lower']
 functional(doc,'Eingang und gemeinsame Verteilung',[w for w in lower if not isbranch(w)and w['net']!='GND'],['Alle Abzweige im Kabelbaum vor den einzelnen Steckeradern. Kein mehrfach belegter Schraubkontakt.'])
 for bank in ['PI','HUB']:
  functional(doc,bank+' · vollständige Wandlerzweige',[w for w in lower if isbranch(w)and w['id'].startswith(bank+'_')],['Reihenfolge: Verteilung → Sicherung → Wandler → Schottky-Diode → Ausgleichswiderstand → Sammelausgang.','Dioden liegen auf PI_A beziehungsweise HUB_A. Die trennbaren Gruppenanschlüsse stehen im jeweiligen Lötplan.'])
 functional(doc,'Untere Ebene · vollständige Rückleiter',[w for w in lower if w['net']=='GND'],['IN− und OUT− der fünf Wandler: interner Durchgang laut Plan, am Kaufteil prüfen.','Die getrennten Pi-/Hub-Rückleiter bleiben bis zum Ebenenstecker separat bezeichnet; gemeinsame Masse ausdrücklich erhalten.'])
 views.append(spatial(doc,'lower',lambda w:w['id'].startswith('PI_'),'Pi-Zweige'))
 views.append(spatial(doc,'lower',lambda w:w['id'].startswith('HUB_'),'Hub-Zweige'))
 views.append(spatial(doc,'lower',lambda w:not w['id'].startswith(('PI_','HUB_')),'Eingangsschutz'))
 physical_tables(doc,'lower');doc.table('Externe Zuleitung und Eingangssicherung',['Kennung','Von / nach','Anschluss / Leitung'],[160,400,563],[[x['id'],x['start']+' → '+x['end'],'Gemeinsames 2×1,5-mm²-Kabel DB287, 4,7m bestehendes Bereitstellungsbudget. F0 quellnah, Werksadern berücksichtigen; Quellenklemme und reale Teilung vor Zuschnitt prüfen.']for x in E['external_connections']]);doc.table('Interne Rückleiter der Wandler',['Verbindung','Von / nach','Prüfung'],[300,430,393],[[x['id'],x['start']+' ↔ '+x['end'],'Im Kaufmodul vorgesehen; realen Durchgang bestätigen. Kein zusätzlicher Drahtzuschnitt.']for x in E['internal_ground_bonds'][:5]]);doc.finish()
 doc=base.Doc('06-Verkabelung-Oben','Verkabelung · obere Ebene');functional(doc,'Obere Ebene · alle Versorgungs- und CAN-Anschlüsse',[w for w in E['wires']if w['level']=='upper'],['GPIO mit gemeinsamer abziehbarer Buchsenleiste; Pins2/4 Plus und6/9 Masse.','Hub-Rp/CC-Netz vor Löten am gelieferten Stecker prüfen. Beide USB-Datenkabel bleiben vollständig erhalten.'])
 views.append(spatial(doc,'upper',title='GPIO, Hub und CAN'));usb_view(doc);physical_tables(doc,'upper');doc.table('Beide intakten USB-Datenleitungen',['Kennung','Originalanschlüsse','Ausführung / offene Abnahme'],[170,400,553],[[c['id'],c['start']+' ↔ '+c['end'],'Nicht schneiden; an Originalbuchsen stecken und am nahen Rahmen entlasten. '+c['qualification']+'; tatsächlichen Servicebogen vor Zusammenbau prüfen.']for c in E['intact_data_cables']]);doc.table('Masseverbindungen über Pi und intakte USB-Kabel',['Verbindung','Von / nach','Prüfung'],[300,430,393],[[x['id'],x['start']+' ↔ '+x['end'],'GND ist auch über USB verbunden. Durchgang und VBUS-Rückspeisung real prüfen. Kein neuer Drahtzuschnitt.']for x in E['internal_ground_bonds'][5:]]);doc.finish()
 doc=base.Doc('07-Ebenenuebergang','Verkabelung · Ebenenübergang');base.transition_overview(doc);physical_tables(doc,'interlevel');doc.finish()
 base.save=None
 (ROOT/'output/drawings.json').write_text(json.dumps(dict(revision='PI-2L-5',drawing_revision='L5',source_sha256=hashlib.sha256((ROOT/'delivery/daten/kontaktvertrag.json').read_bytes()).hexdigest(),documents=base.MANIFEST,spatial_views=views,wire_aliases=ALIASES),ensure_ascii=False,indent=2)+'\n')
 print('L5 PDFs',len(base.MANIFEST),'pages',sum(len(d['pages'])for d in base.MANIFEST))
if __name__=='__main__':main()

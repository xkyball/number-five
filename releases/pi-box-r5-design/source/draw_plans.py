"""A3 PDF + editable SVG pages from the single L3 physical contract; no GUI."""
import json,math,html,textwrap,hashlib
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import toColor as HexColor
from reportlab.lib.pagesizes import A3,landscape
from shapely.geometry import Polygon,LineString,Point,box
from shapely.ops import unary_union
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'delivery';SVG=OUT/'svg';SVG.mkdir(parents=True,exist_ok=True)
E=json.loads((OUT/'daten/kontaktvertrag.json').read_text());D=json.loads((ROOT/'design.json').read_text());W,H=landscape(A3)
FONTS=Path('/System/Library/Fonts/Supplemental');pdfmetrics.registerFont(TTFont('Plan',str(FONTS/'Arial.ttf')));pdfmetrics.registerFont(TTFont('PlanB',str(FONTS/'Arial Bold.ttf')))
INK='#213847';MUTED='#536a76';BROWN='#95602d';BLUE='#286aa5';BLACK='#222a30';RED='#c84649'
NAMES={'PI_A':'Pi-Versorgung','HUB_A':'Hub-Versorgung und Sicherungsplatinen','INPUT_A':'Eingangsschutz','GPIO_A':'GPIO-Adapter','HF1_A':'Hub-Sicherungsplatine 1','HF2_A':'Hub-Sicherungsplatine 2'}
VALUES={46:'10SQ050 · R-6-Referenz bis Ø9,1 × 9,1; Liefermaß offen',177:'1000 µF / 35 V · Ø10 × 20 liegend; Beinabstand geformt',237:'YC100, 4-polig · gelieferte Pinfolge und Belastbarkeit offen',113:'P6KE18A · Körperreferenz 7 × 5,2; Lieferausführung offen',276:'JH7032 + 2-A-Sicherung · tatsächliche Anschlüsse offen'}
MANIFEST=[]
def wirecolor(net,section):return BLACK if section==.22 else BLUE if net=='GND'else BROWN
def fmt(v):return str(v).replace('.',',')
def short(s):return s.replace('Durchstecken, unten direkt am Bauteilbein/Metallende löten','oben durch Loch; unten direkt am Bein/Metallende löten').replace('Kabelbinder am nächsten Platinen-Befestigungspunkt, bevor Zug die Lötstelle erreicht','Kabelbinder am nächsten Platinen-Schraubpunkt').replace('festes PCB-Lötteil','festes PCB-Lötteil')
class Doc:
 def __init__(self,name,title):
  self.name=name;self.title=title;self.c=canvas.Canvas(str(OUT/(name+'.pdf')),pagesize=(W,H),invariant=1);self.c.setTitle(title+' · PI-2L-5 / L5');self.n=0;self.pages=[]
 def page(self,subtitle):
  self.n+=1;self.svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}pt" height="{H}pt" viewBox="0 0 {W} {H}">','<rect width="100%" height="100%" fill="white"/>','<g font-family="Arial,sans-serif">'];self.texts=[];self.bounds=[]
  self.text(34,38,self.title,23,bold=True);self.text(34,62,subtitle,11)
  self.text(34,82,'PI-2L-5 / Zeichnungsstand L5 · Maße und Zuschnitt in mm · Planentwurf; Kabelzuschnitt/USB-Servicewege offen',10,MUTED)
  self.line([[34,94],[W-34,94]],'#9ab0bd',.8)
 def text(self,x,y,t,size=10,fill=INK,bold=False,anchor='start'):
  t=str(t);font='PlanB'if bold else'Plan';self.c.setFont(font,size);self.c.setFillColor(HexColor(fill))
  if anchor=='middle':self.c.drawCentredString(x,H-y,t)
  elif anchor=='end':self.c.drawRightString(x,H-y,t)
  else:self.c.drawString(x,H-y,t)
  self.svg.append(f'<text x="{x:.3f}" y="{y:.3f}" font-size="{size}" fill="{fill}" text-anchor="{anchor}"'+(' font-weight="bold"'if bold else'')+'>'+html.escape(t)+'</text>');self.texts.append(t)
  tw=pdfmetrics.stringWidth(t,font,size);left=x-tw/2 if anchor=='middle'else x-tw if anchor=='end'else x;self.bounds.append([round(left,2),round(y-size,2),round(left+tw,2),round(y+2,2),t])
 def rect(self,x,y,w,h,fill='none',stroke='#8ca0ab',sw=.8,dash=False):
  self.c.setLineWidth(sw);self.c.setDash(4,3)if dash else self.c.setDash()
  if fill!='none':self.c.setFillColor(HexColor(fill))
  if stroke!='none':self.c.setStrokeColor(HexColor(stroke))
  self.c.rect(x,H-y-h,w,h,fill=int(fill!='none'),stroke=int(stroke!='none'))
  self.svg.append(f'<rect x="{x:.3f}" y="{y:.3f}" width="{w:.3f}" height="{h:.3f}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"'+(' stroke-dasharray="4 3"'if dash else'')+'/>')
 def line(self,pts,color=INK,width=1,dash=False):
  self.c.setDash(4,3)if dash else self.c.setDash();self.c.setLineWidth(width);self.c.setStrokeColor(HexColor(color));p=self.c.beginPath();p.moveTo(pts[0][0],H-pts[0][1])
  for x,y in pts[1:]:p.lineTo(x,H-y)
  self.c.drawPath(p);self.svg.append('<polyline points="'+' '.join(f'{x:.3f},{y:.3f}'for x,y in pts)+f'" fill="none" stroke="{color}" stroke-width="{width}"'+(' stroke-dasharray="4 3"'if dash else'')+'/>')
 def circle(self,x,y,r,fill='white',stroke=INK,sw=.6):
  self.c.setDash();self.c.setFillColor(HexColor(fill));self.c.setStrokeColor(HexColor(stroke));self.c.setLineWidth(sw);self.c.circle(x,H-y,r,stroke=1,fill=1);self.svg.append(f'<circle cx="{x:.3f}" cy="{y:.3f}" r="{r:.3f}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
 def polygon(self,pts,fill,stroke='none',sw=.5):
  p=self.c.beginPath();p.moveTo(pts[0][0],H-pts[0][1])
  for x,y in pts[1:]:p.lineTo(x,H-y)
  p.close();self.c.setFillColor(HexColor(fill));self.c.setLineWidth(sw)
  if stroke!='none':self.c.setStrokeColor(HexColor(stroke))
  self.c.drawPath(p,fill=1,stroke=int(stroke!='none'));self.svg.append('<polygon points="'+' '.join(f'{x:.3f},{y:.3f}'for x,y in pts)+f'" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
 def para(self,x,y,t,width=1085,size=10,fill=INK):
  lines=wrap(t,width,size)
  for line in lines:self.text(x,y,line,size,fill);y+=size*1.4
  return y
 def end(self):
  self.line([[34,H-40],[W-34,H-40]],'#aab9c1',.6);self.text(34,H-23,'Oben: isolierte Drähte. PCB-Lötstellen ausschließlich unten. Keine Brücke unter Bauteilkörpern.',9,MUTED);self.text(W-34,H-23,f'Blatt {self.n}',9,MUTED,anchor='end')
  stem=f'{self.name}-{self.n:02}';(SVG/(stem+'.svg')).write_text('\n'.join(self.svg+['</g></svg>']));self.pages.append(dict(stem=stem,texts=self.texts,bounds=self.bounds));self.c.showPage()
 def finish(self):
  self.c.save();MANIFEST.append(dict(pdf=self.name+'.pdf',title=self.title,pages=self.pages))
 def table(self,subtitle,headers,widths,rows,size=9):
  def head():
   self.page(subtitle);self.rect(34,111,sum(widths),28,'#e9f0f4','none');x=39
   for h,w in zip(headers,widths):self.text(x,129,h,9,bold=True);x+=w
   return 154
  y=head()
  for row in rows:
   cells=[wrap(str(v),w-11,size)for v,w in zip(row,widths)];height=max(map(len,cells))*(size*1.3)+14
   if y+height>H-56:self.end();y=head()
   x=39
   for lines,w in zip(cells,widths):
    for n,t in enumerate(lines):self.text(x,y+n*size*1.3,t,size)
    x+=w
   self.line([[34,y+height-7],[34+sum(widths),y+height-7]],'#dce5ea',.5);y+=height
  self.end()
def wrap(t,width,size):
 result=[]
 for para in t.split('\n'):
  line=''
  for word in para.split():
   if pdfmetrics.stringWidth((line+' '+word).strip(),'Plan',size)>width and line:result.append(line);line=''
   # Split long machine identifiers without throwing them off the page.
   if pdfmetrics.stringWidth(word,'Plan',size)>width:
    if line:result.append(line);line=''
    while pdfmetrics.stringWidth(word,'Plan',size)>width:
     k=1
     while k<len(word)and pdfmetrics.stringWidth(word[:k+1],'Plan',size)<=width:k+=1
     result.append(word[:k]);word=word[k:]
   line=(line+' '+word).strip()
  result.append(line)
 return result or['']
def copper(b):
 strips=unary_union([box(1,3.29+2.54*r-.85,b['size'][0]-1,3.29+2.54*r+.85)for r in range(b['rows'])]);rem=[Polygon(q['polygon'])for q in b['copper_clear_regions']]
 rem += [box(q['xy'][0]-q['kerf_mm']/2,q['xy'][1]-q['length_mm']/2,q['xy'][0]+q['kerf_mm']/2,q['xy'][1]+q['length_mm']/2)for q in b['cuts']]
 return strips.difference(unary_union(rem))
def board_view(s,b,mirror,x0,y0,scale):
 w,h,_=b['size'];pt=lambda x,y:(x0+(w-x if mirror else x)*scale,y0+y*scale)
 s.rect(x0,y0,w*scale,h*scale,'#fff9ea','#8d7758',1)
 if mirror:
  co=copper(b)
  for p in co.geoms if hasattr(co,'geoms')else[co]:
   if p.area>.001:s.polygon([pt(x,y)for x,y in p.exterior.coords],'#cda26c')
 for x,y in b['mounts']:
  s.circle(*pt(x,y),4*scale,'#edf0f1','#aab7bf',.5);s.circle(*pt(x,y),b['mounting_drill_mm']/2*scale,'white',INK,.8)
 for r in range(b['rows']):
  for c in range(b['cols']):
   x,y=3.22+2.54*c,3.29+2.54*r
   if all(math.dist([x,y],q)>4 for q in b['mounts']):s.circle(*pt(x,y),.35*scale,'#fffdf7','#c5bca9',.3)
 for p in b['parts']:
  if p.get('access_envelope_xywh'):
   x,y,ww,hh=p['access_envelope_xywh'];xx,yy=pt(x+ww if mirror else x,y);s.rect(xx,yy,ww*scale,hh*scale,'none','#7e9e88',.7,True)
   if not mirror:s.text(*pt(x+21,y+hh/2),'Gegenstecker',7,MUTED,anchor='middle')
 # Bodies have actual family-reference envelopes, no invented narrower diode.
 for p in b['parts']:
  x,y,ww,hh=p['body_xywh'];a,z=pt(x+ww if mirror else x,y);s.rect(a,z,ww*scale,hh*scale,'none'if mirror else'#d6e4ed','#7293a8',.8,mirror)
  if not mirror:
   s.text(*pt(x+ww/2,y+hh/2),p['id'],8,bold=True,anchor='middle')
   if 'lead_axis'in p:
    pp=p['lead_axis'];s.line([pt(*pp[0]),pt(x+ww/2,y+hh/2),pt(*pp[1])],'#71828c',1)
    # Kathode is the second endpoint of the diode axis, first for TVS.
    k=pp[0]if p['kind']=='TVS'else pp[1];dx,dy=k[0]-(x+ww/2),k[1]-(y+hh/2)
    if abs(dx)>abs(dy):bx=x+ww-.8 if dx>0 else x+.8;s.line([pt(bx,y+.4),pt(bx,y+hh-.4)],INK,2)
    else:by=y+hh-.8 if dy>0 else y+.8;s.line([pt(x+.4,by),pt(x+ww-.4,by)],INK,2)
 for r in b['top_routes']:
  s.line([pt(*q)for q in r['path_mm']],wirecolor(r['net'],r['conductor_mm2'])if not mirror else'#9aa6ae',2.1 if not mirror else.6,mirror)
 if mirror:
  for bond in b['bottom_bonds']:s.line([pt(*q)for q in bond['path_mm']],RED,2)
  for q in b['cuts']:
   x,y=q['xy'];s.line([pt(x,y-q['length_mm']/2),pt(x,y+q['length_mm']/2)],RED,.8)
 for n,p in enumerate(b['pads'],1):
  xx,yy=pt(*p['xy']);s.circle(xx,yy,p['drill_mm']/2*scale,'#f8c2a7'if mirror else'white',INK,.6)
  # One fixed offset in board coordinates mirrors with the bottom view.
  tx,ty=pt(p['xy'][0]+1.25,p['xy'][1]-1.0);s.text(tx,ty,n,7,INK,anchor='middle')
 s.text(x0,y0+h*scale+30,f'{w} × {h} × 1 mm · Raster 2,54 · Ursprung oben links der Bestückungsseite',9,MUTED)
 if mirror:s.text(x0,y0+h*scale+45,'Bereits gespiegelt: links/rechts gegenüber der Bestückungsseite vertauscht.',9,MUTED)

def board_pages(doc,b):
 doc.page(NAMES[b['id']]+' · Bestückung und gespiegelte Lötseite')
 doc.text(60,122,'OBEN / BESTÜCKUNG',13,bold=True);doc.text(650,122,'UNTEN / LÖTSEITE — GESPIEGELT',13,bold=True)
 sc=min(9,500/b['size'][1]);board_view(doc,b,False,100,161,sc);board_view(doc,b,True,690,161,sc)
 
 for j,r in enumerate(b['top_routes']):
  a=1+next(i for i,p in enumerate(b['pads'])if p.get('entry_id')==r['start_entry']);z=1+next(i for i,p in enumerate(b['pads'])if p.get('entry_id')==r['end_entry'])
  for xx in [505,1090]:
   yy=166+j*62;doc.line([[xx,yy],[xx+50,yy]],wirecolor(r['net'],r['conductor_mm2']),2);doc.text(xx,yy+13,r['id'],8,bold=True);doc.text(xx,yy+25,f'Loch {a} → {z}',8);doc.text(xx,yy+37,r['net']+' / '+fmt(r['conductor_mm2'])+' mm²',7.5)
 y=max(485,161+b['size'][1]*sc+64)
 for t in [
  'Lochnummern siehe Anschlusstabelle. Oben alle isolierten Brücken; unten rote direkte Lötwege, Kupfertrennungen und bereits gespiegelte Kontakte.',
  'Braun/Blau = 1,5 mm²; Schwarz = 0,22 mm² (DB 186). Alle Enden von oben durchstecken und ausschließlich unten verbinden.',
  'Kupferkragen, blanke Lötwege und Schraubzonen vollständig freistellen. Exakte Schnitte/Polygone: kupfertrennungen.csv / kontaktvertrag.json.'
 ]:y=doc.para(34,y,t,size=9)+5
 if b['id'].startswith('HF'):
  yy=y+14;doc.text(34,yy,'Alle vier Kontakte / Durchlässe dieser Sicherungsplatine',11,bold=True);yy+=23
  for n,p in enumerate(b['pads'],1):
   ent=next((q for q in b['entries']if q['id']==p.get('entry_id')),None);info=(ent['wire_id']+'; blank '+str(ent['strip_mm'])+'; unten an '+ent['contact'])if ent else'Bauteilbein, ausschließlich unten löten'
   doc.text(34,yy,f"{n}: {p['contact']} · X {fmt(p['xy'][0])} / Y {fmt(p['xy'][1])} · Ø{fmt(p['drill_mm'])} · {info}",9);yy+=23
  doc.text(34,yy,'Befestigung: Ø2,4 bei X/Y 3/6 und 37/6; Kupfer je Radius4 freistellen.',9);yy+=21
  doc.text(34,yy,'JH7032 / DB276 mit2A-Sicherung: Pinabstand und Anschlussform am Kaufteil offen. Ø1,2 ist ein vorläufiges Bohrmaß.',9,RED)
  doc.end();return
 doc.end()
 if b['parts']:doc.table(b['id']+' · Bauteile und Polarität',['Bauteil','Artikel / Ausführung','Einbau und Polung'],[125,450,548],[[p['id'],f"DB {p['item_id']}: "+VALUES.get(p['item_id'],p['kind']),p.get('assembly','')+' | '+p['polarity']]for p in b['parts']])
 padrows=[]
 for i,p in enumerate(b['pads'],1):
  ent=next((q for q in b['entries']if q['id']==p.get('entry_id')),None);target=next(q for q in b['pins']if q['id']==p['contact'])
  extra=(ent['wire_id']+' / '+ent['wire_end']+'; '+str(ent['strip_mm'])+' abisolieren')if ent else('1,5-mm²-Platinenleitung zum getrennten Stecker F; Ø2'if '_BANK.'in p['contact']else'Bauteilbein / Kontakt')
  padrows.append([i,p['contact']+'\n'+extra,fmt(p['xy'][0])+' / '+fmt(p['xy'][1])+'\nØ '+fmt(p['drill_mm'])+' (Plan)',p['net'],('unten an '+p['contact']+' bei '+fmt(target['xy'][0])+'/'+fmt(target['xy'][1]))if ent else'Bein führt durch eigenes Loch; nur unten löten'])
 doc.table(b['id']+' · vollständige Bohr- und Anschlusstabelle',['Nr.','Kontakt / Draht','X / Y / Bohrung','Netz','Tatsächliches Lötende'],[35,350,150,140,348],padrows)
 if b['top_routes']:
  rows=[]
  for r in b['top_routes']:
   a=next(q for q in b['entries']if q['id']==r['start_entry']);z=next(q for q in b['entries']if q['id']==r['end_entry']);nums=[1+next(i for i,p in enumerate(b['pads'])if p.get('entry_id')==q['id'])for q in[a,z]]
   rows.append([r['id'],f"{r['start']} → {r['end']}\nLoch {nums[0]} → {nums[1]}",r['net'],f"{fmt(r['conductor_mm2'])} mm² / DB {r['item_id']}",f"{r['cut_mm']} / {r['strip_start_mm']} / {r['strip_end_mm']}",'oben; beide Enden unten. Kreuzungen einzeln isoliert; keine elektrische Verbindung.'])
  doc.table(b['id']+' · sämtliche sichtbaren Drahtbrücken',['Kennung','Von → nach','Netz','Ader','Zuschnitt / blank A / B','Führung und Anschluss'],[95,270,105,160,145,248],rows)
 # Copper-clear circles and direct-joint corridors are source-defined and shown to scale.
 rows=[]
 for bond in b['bottom_bonds']:
  ent=next(q for q in b['entries']if q['id']==bond['entry']);num=1+next(i for i,p in enumerate(b['pads'])if p.get('entry_id')==ent['id']);rows.append([num,ent['wire_id'],ent['contact'],str(bond['path_mm']),'Direktmetall unten; Kupferkorridor 2,6 breit. Max. Löttiefe 3,2 ist Planreserve, real messen.'])
 if rows:doc.table(b['id']+' · unterseitige Lötverbindungen und Kupferfreistellung',['Loch','Leitung','An Kontakt','XY-Lötweg (mm)','Bearbeitung / Freiraum'],[45,170,175,300,333],rows)
 # Complete cut listing; all exact polygons additionally supplied in the JSON contract.


def solder_docs():
 for name,boards in [('01-Loetplan-Pi',['PI_A']),('02-Loetplan-Hub',['HUB_A','HF1_A','HF2_A']),('03-Loetplan-Eingang',['INPUT_A']),('04-Loetplan-GPIO',['GPIO_A'])]:
  doc=Doc(name,NAMES[boards[0]])
  for b in boards:board_pages(doc,E['boards'][b])
  doc.finish()

def node(doc,x,y,w,title,details='',height=46):
 doc.rect(x,y,w,height,'#eef3f6','#738d9d',.7);doc.text(x+w/2,y+17,title,10,bold=True,anchor='middle')
 if details:doc.text(x+w/2,y+33,details,8,anchor='middle')
def arrow(doc,pts,label,net='P12',small=False):
 co=wirecolor(net,.22 if small else 1.5);doc.line(pts,co,1.2);a,z=pts[-2:];v=math.atan2(z[1]-a[1],z[0]-a[0]);doc.line([[z[0]-5*math.cos(v-.5),z[1]-5*math.sin(v-.5)],z,[z[0]-5*math.cos(v+.5),z[1]-5*math.sin(v+.5)]],co,1)
 a,z=max(zip(pts,pts[1:]),key=lambda q:math.dist(*q));x,y=(a[0]+z[0])/2,(a[1]+z[1])/2
 if abs(a[1]-z[1])<.01:
  textwidth=pdfmetrics.stringWidth(label,'Plan',8);doc.text(x,y-(31 if abs(a[0]-z[0])<textwidth+12 else 8),label,8,co,anchor='middle')
 else:doc.text(x+6,y-6,label,8,co)
def getid(id):return next(w for w in E['wires']if w['id']==id)
def lower_overviews(doc):
 doc.page('Untere Ebene · Gesamtweg und Sammelanschlüsse — räumliche Lage siehe Folgeseite')
 node(doc,40,140,170,'12-V-Quelle / F0','Quellanschluss & F0 real prüfen');node(doc,250,140,130,'PG7 → INPUT_A','IN+ / IN−');node(doc,430,140,185,'D0 → P12 / TVS / C0','D0: A an FUSED12, K an P12');node(doc,710,140,170,'SJ12 / SJG','isolierte Lötverzweigungen')
 arrow(doc,[[210,163],[250,163]],'F0 vor Zuleitung');arrow(doc,[[380,163],[430,163]],'IN+');arrow(doc,[[615,163],[710,163]],'P12_ROOT')
 node(doc,170,300,200,'PI_BANK · 4-polig','1 P12 / 2 GND / 3 PI5 / 4 GND');node(doc,650,300,200,'HUB_BANK · 4-polig','1 P12 / 2 GND / 3 HUB5 / 4 GND')
 arrow(doc,[[760,186],[760,240],[270,240],[270,300]],'PI_BANK_IN');arrow(doc,[[800,186],[800,300]],'HUB_BANK_IN')
 node(doc,140,440,260,'3 vollständige Pi-Zweige','PI_IN / PI_G / PI_OUT');node(doc,620,440,260,'2 vollständige Hub-Zweige','HUB_IN / HUB_G / HUB_OUT');arrow(doc,[[270,346],[270,440]],'PI_INPUT_FANOUT');arrow(doc,[[750,346],[750,440]],'HUB_INPUT_FANOUT')
 node(doc,140,600,260,'PI_BANK.3 → PI_FOUT → LEVEL.1','PI_OUT_FUSE / LEVEL_PI+');node(doc,620,600,260,'HUB_BANK.3 → LEVEL.3','LEVEL_HUB+');arrow(doc,[[270,486],[270,600]],'PI_OUTPUT_FANIN');arrow(doc,[[750,486],[750,600]],'HUB_OUTPUT_FANIN')
 doc.text(40,708,'Rückleiter vollständig: J12− → IN.2 → SJG → BANK.2 → G → BANK.4 → LEVEL.2 / LEVEL.4.',11,BLUE)
 doc.text(40,730,'Je Wandler: G → IN−; OUT− ist am Kaufteil intern mit IN− verbunden (Durchgang prüfen). Keine Ausgänge vor D/R verbinden.',10)
 doc.text(40,750,'PI_BANK und HUB_BANK: außen abziehbare Schraubhälfte, innen festes Lötteil. Keine Einzelstecker an den Wandlern.',10)
 doc.end()
 doc.page('Untere Ebene · alle fünf Zweige, ihre Rückleiter und lokale Schutzverbindungen')
 for row,(bank,n)in enumerate([('PI',1),('PI',2),('PI',3),('HUB',1),('HUB',2)]):
  y=132+row*105;prefix=f'{bank}_';source='PI_IN'if bank=='PI'else'HUB_IN';f=f'{bank}_F{n}';fp=f if bank=='PI'else f'HF{n}';u=f'{bank}_U{n}';di=f'{bank}_D{n}';r=f'{bank}_R{n}'
  nodes=[(40,120,source),(200,130,f+' · 2 A'),(370,135,u),(550,115,di+' · A→K'),(710,135,r+' · 0,1 Ω'),(935,170,bank+'_OUT')]
  for x,w,t in nodes:node(doc,x,y,w,t)
  ids=[f'{bank}_F{n}_FEED',f'{bank}_F{n}_LOAD',f'{bank}_U{n}_RAW',f'{bank}_R{n}_IN',f'{bank}_R{n}_OUT']
  for (x,w,t),(xx,ww,tt),id in zip(nodes,nodes[1:],ids):arrow(doc,[[x+w,y+23],[xx,y+23]],id)
  arrow(doc,[[415,y+80],[415,y+46]],f'{bank}_U{n}_GND',net='GND');doc.text(450,y+80,bank+'_G → '+u+'.IN− = intern OUT−',9,BLUE)
 doc.text(40,686,'PI: F108-Werksadern an PI_F1/2/3 verwenden. HUB: JH7032 auf HF1_A und HF2_A, ausschließlich unten löten.',10)
 doc.text(40,710,'INPUT_A: W1 IN.1→D0.A; W2 IN.2→C0−; W3 D0.K→C0+; W4 IN.2→TVS.A; W5 D0.K→TVS.K.',10)
 doc.text(40,734,'Pi-Sammelausgang bekommt PI_FOUT vor LEVEL.1; Hub-Ausgang bleibt eigener Pluspfad bis LEVEL.3.',10)
 doc.end()

def upper_overview(doc):
 doc.page('Obere Ebene · GPIO, USB-C, CAN und beide intakten USB-Datenleitungen')
 node(doc,45,140,160,'LEVEL fest · 4-polig','1 Pi+ / 2 GND / 3 Hub+ / 4 GND')
 node(doc,350,140,230,'GPIO_A','IN+ → P2/P4; IN− → P6/P9');node(doc,830,140,270,'Pi GPIO-Steckbuchse','Pins 2/4 = 5 V; Pins 6/9 = GND');arrow(doc,[[205,155],[350,155]],'GPIO_FEED+');arrow(doc,[[205,172],[350,172]],'GPIO_FEED−',net='GND')
 arrow(doc,[[580,154],[830,154]],'GPIO_2 / GPIO_4');arrow(doc,[[580,172],[830,172]],'GPIO_6 / GPIO_9',net='GND')
 node(doc,350,320,230,'USB-C-Powerstecker','VBUS / GND / CC');node(doc,830,320,270,'UNITEK USB-Hub','Poweranschluss am gelieferten Hub prüfen')
 arrow(doc,[[155,186],[155,335],[350,335]],'HUB_POWER+');arrow(doc,[[180,186],[180,354],[350,354]],'HUB_POWER−',net='GND');arrow(doc,[[580,343],[830,343]],'stecken')
 node(doc,360,440,220,'Rp 10 kΩ · DB 33','VBUS → Rp → CC; konkrete CC-Seite offen');arrow(doc,[[415,366],[415,440]],'RP_FEED',small=True);arrow(doc,[[530,440],[530,366]],'RP_CC',small=True)
 node(doc,830,540,270,'CANable','H / L / GND → CAN_WALL.1/2/3');node(doc,350,540,230,'CAN_WALL · abziehbar','Pin 4 unbenutzt / Schirm isoliert');arrow(doc,[[830,565],[580,565]],'CAN_1 / CAN_2 / CAN_3',small=True)
 doc.line([[1100,180],[1130,180],[1130,345],[1100,345]],'#708e4a',2,True);doc.text(880,262,'USB_HOST: intaktes Werkskabel Pi USB3 ↔ Hub',10)
 doc.line([[1085,186],[1110,186],[1110,560],[1100,560]],'#708e4a',2,True);doc.text(850,510,'USB_CAN: intaktes Pi-USB2 ↔ CAN-USB-C-Kabel',10)
 doc.text(40,666,'Gemeinsame Masse: GPIO6/9 ↔ Pi USB-GND ↔ Hub Host/Power-GND und CAN USB/CAN-GND; Durchgang am Gerät prüfen.',10,BLUE)
 doc.text(40,691,'USB führt neben Daten auch VBUS: Hub-Rückspeisung real prüfen. Pi-USB-C bleibt bei GPIO-Versorgung frei.',10,RED)
 doc.text(40,716,'USB-C: vorhandenes 56-kΩ-Netz des EIE180640-Steckers und Hub-CC/Rd zuerst messen. Die 10-kΩ-Rp-Planung ist noch keine Freigabe.',10,RED)
 doc.text(40,741,'Schwarze 0,22-mm²-Adern an RP_FEED / RP_CC und CAN_1/2/3. Datenkabel vollständig erhalten, nicht umverdrahten.',10)
 doc.end()
def transition_overview(doc):
 doc.page('Ebenenübergang · vier Adern und beide physischen Steckerhälften')
 doc.text(100,142,'UNTERE EBENE',15,bold=True);doc.text(740,142,'OBERE EBENE',15,bold=True)
 labels=['1 · Pi-Plus (PI_GPIO5)','2 · Pi-Rückleiter (GND)','3 · Hub-Plus (HUB5)','4 · Hub-Rückleiter (GND)']
 for i,(id,t)in enumerate(zip(['LEVEL_PI+','LEVEL_PI-','LEVEL_HUB+','LEVEL_HUB-'],labels)):
  y=205+i*82;w=getid(id);node(doc,40,y,275,w['start'],id);node(doc,750,y,365,w['end']+' → '+('GPIO.IN+'if i==0 else'GPIO.IN−'if i==1 else'HUB_USB.VBUS'if i==2 else'HUB_USB.GND'),t)
  arrow(doc,[[315,y+23],[750,y+23]],f'{id} · 1,5 mm² · Pin {i+1}',net=w['net'])
 doc.rect(360,176,135,360,'none','#6f8fa3',2);doc.rect(570,176,135,360,'none','#6f8fa3',2)
 doc.text(426,563,'abziehbare Schraubhälfte',10,bold=True,anchor='middle');doc.text(640,563,'festes Gegenstück',10,bold=True,anchor='middle')
 doc.text(426,583,'Blick auf die Kontaktseite',10,anchor='middle');doc.text(640,583,'Blick auf die Kontaktseite',10,anchor='middle')
 # Opposed front faces MUST be mirrored. Functional pins are labelled rather than inferred from left-right.
 for side,xx,order in [('loose',390,[1,2,3,4]),('fixed',600,[4,3,2,1])]:
  for j,pin in enumerate(order):doc.rect(xx+j*24,620,22,31,'#e2edf3');doc.text(xx+j*24+11,640,pin,11,bold=True,anchor='middle')
 doc.text(40,691,'Kontaktansichten schematisch: Gegenstück gespiegelt. Physische Pin-1-Lage am gelieferten Satz per Durchgang bestätigen.',11,RED)
 doc.text(40,716,'POWER-Codierung: R2 POWER_KEY_SHROUD + POWER_PLUG_COLLAR. CAN-Satz erhält andere Codierung; nicht austauschbar verwenden.',10)
 doc.text(40,741,'Jede Hälfte am vorhandenen Rahmen entlasten; kurzer Servicebogen zum Handstecken. Pin 1/3 niemals miteinander verbinden.',10)
 doc.end()
def wire_tables(doc,level):
 rows=[]
 for w in E['wires']:
  if w['level']!=level:continue
  term=[]
  for k in ['start','end']:
   t=w['terminations'][k];entry=''
   if t['physical_pad']:
    b=E['boards'][t['module']];n=1+next(i for i,p in enumerate(b['pads'])if p.get('entry_id')==t['physical_pad']);entry='; Platinenloch '+str(n)
   kind=t['attachment'];half=t['connector_half'];iso=t['isolation']
   if 'Durchstecken'in kind:kind='oben durchstecken; unten direkt am Bein/Metallende löten';iso='Isolation bis Durchlass; blank nur unten'
   elif kind.startswith('Vorhandene F108'):kind='F108-Werksader; Zusatzader nur als Lötstoß';iso='Lötstoß einzeln schrumpfen'
   elif 'Verzweigung'in kind:kind='Kabelbaum-Abzweig fest löten';iso='jeden Abzweig einzeln schrumpfen'
   elif 'Direkt an vorhandenes'in kind:kind='direkt am IN/OUT-Lötpad';iso='Isolation bis Lötpad'
   elif 'Widerstands'in kind:kind='an Widerstands-Lötfahne löten';iso='Fahne isolieren; Körper frei'
   elif 'GPIO-Buchsenleiste'in kind:kind='GPIO-Buchsenkontakt löten; Buchse abziehbar';iso='jeden Kontakt einzeln isolieren'
   elif 'USB-C-Lötstecker'in kind:kind='USB-C/Rp-Lötpunkt; Stecker abziehbar';iso='Lötpunkte getrennt isolieren'
   elif 'Abisoliertes Ende'in kind:kind='Mantelkabel durch PG7';iso='Mantel im PG7; erst innen aufteilen'
   if 'CN1.5'in iso:iso='CN1.5-Hülse; nicht verzinnen'
   if 'Passende Hülse'in iso or '0,22mm² passende'in iso:iso='0,22-mm²-Klemmung/Hülse real prüfen'
   if half:kind+='; '+half
   anchor=t.get('anchor_label',t['module']+' / naher Rahmen-Schraubpunkt')
   term.append('\n'.join([t['contact']+entry,kind,iso,'Zugschutz: '+anchor]).strip())
  rows.append([w['id']+'\n'+w['net'],term[0],term[1],f"{fmt(w['conductor_mm2'])} mm²\nDB {w['item_id']}\n{w['cut_mm']} mm\nblank {w['terminations']['start']['strip_mm']} / {w['terminations']['end']['strip_mm']}",w['factory_lead_policy']])
 doc.table('Vollständige Anschlusstabelle · '+str(len(rows))+' Leitungen · jeweils beide Enden',['Kennung / Netz','Ende A · Befestigung, Isolation, Entlastung','Ende B · Befestigung, Isolation, Entlastung','Ader / Zuschnitt','Zuschnittregel'],[130,335,335,125,198],rows,9)
 if level=='upper':
  doc.table('Intakte USB-Datenleitungen und reale Freigaben',['Kennung','Von / nach','Bearbeitung','Offene Prüfung'],[150,350,250,373],[[r['id'],r['start']+' ↔ '+r['end'],'vollständig belassen, an Originalbuchsen stecken; an nahen Schraubpunkten entlasten',r['qualification']+'; Weg '+str(r['route_length_mm'])+' mm'+('; Servicebogen R'+str(r['service_loop']['radius_plan_mm'])+' / 340°, Rest '+str(r['remaining_service_length_mm'])+' mm'if r.get('service_loop')else'')]for r in E['intact_data_cables']])
 if level=='lower':
  doc.table('Zusätzliche externe Quellanschlüsse · außerhalb der 64 internen Leitungen',['Kennung','Von → nach','Anschluss / Ader','Länge / offene Prüfung'],[160,300,335,328],[['EXT_F0_A','Quelle + → F0.1','DB 287 · 2 × 1,5 mm² Zuleitung; Original-F108-Ader und isolierter Lötstoß; Quelle nach tatsächlicher Klemme','4,7 m gemeinsame Mantellänge als bestehender Bedarf; F0 quellnah. Teilung/Abisolierung am echten Anschluss messen.'],['EXT_F0_B','F0.2 → PG7 / J12.+','F0 nach Kurve/Quellleistung prüfen; Mantel im PG7 klemmen, Werksader von Zuschnitt abziehen','Keine neue Reservierung der 4,7 m; bestehender externer Bedarf bleibt erhalten.'],['EXT_RETURN','Quelle − → PG7 / J12.−','zweite Ader desselben 2×1,5-mm²-Kabels, ohne Schalter/Diode im Rückleiter','Quellklemme, Freigabe, Sicherung und Spannungsfall der langen Zuleitung bleiben offen.']])
 # Common ground bonds are not cut wires; distinguish these from physical harness stock.
 bonds=E['internal_ground_bonds']
 if level in ['lower','upper']:
  bonds=bonds[:5]if level=='lower'else bonds[5:]
  doc.table('Vorhandene interne Masseverbindungen · kein neuer Drahtzuschnitt',['Verbindung','Von','Nach','Prüfstand'],[290,290,290,253],[[r['id'],r['start'],r['end'],'Im Kaufteil vorhanden laut Plan; realen Durchgang prüfen.']for r in bonds])

def spatial(doc,level):
 doc.page('Räumliche Ebenenübersicht · Leitungen schematisch entlang vorhandener Montagepositionen')
 # Positions come only from contract. Connector and component labels stay outside wire field.
 if level=='lower':bids=['PI_A','HUB_A','INPUT_A','HF1_A','HF2_A'];sc=2.7
 else:bids=['GPIO_A'];sc=2.7
 ox,oy=45,130
 doc.rect(ox,oy,216*sc,194*sc,'#fafcfd','#a1b3be',1)
 spatial_labels=[];spatial_paths=[]
 bodyids=['PI_U1','PI_U2','PI_U3','HUB_U1','HUB_U2','PI_R1','PI_R2','PI_R3','HUB_R1','HUB_R2','PI_F1','PI_F2','PI_F3','PI_FOUT']if level=='lower'else['PI_CASE','HUB','CAN_PCB']
 mech=json.loads((ROOT.parent/'two-level-r2/output/mechanical.json').read_text())
 for rid in bodyids:
  row=next(r for r in mech['rows']if r['id']==rid);a,z=row['bounds_mm'];doc.rect(ox+a[0]*sc,oy+a[1]*sc,(z[0]-a[0])*sc,(z[1]-a[1])*sc,'#f0f3f4','#b2c0c8',.5);spatial_labels.append((rid,[ox+a[0]*sc,oy+a[1]*sc,ox+z[0]*sc,oy+z[1]*sc]))
 for b in bids:
  cfg=D['boards'][b];x,y,z=cfg['origin'];ww,hh,_=E['boards'][b]['size'];hh=5 if cfg.get('upright')else hh
  doc.rect(ox+x*sc,oy+y*sc,ww*sc,hh*sc,'#e4edf2','#7493a8');spatial_labels.append((b,[ox+x*sc,oy+y*sc,ox+(x+ww)*sc,oy+(y+hh)*sc]))
 for w in E['wires']:
  if w['level']!=level:continue
  pts=[[ox+q[0]*sc,oy+q[1]*sc]for q in w['route_mm']];doc.line(pts,wirecolor(w['net'],w['conductor_mm2']),.7);spatial_paths.append(LineString(pts).buffer(1.6))
  for p in [pts[0],pts[-1]]:doc.circle(*p,1.3,wirecolor(w['net'],w['conductor_mm2']),wirecolor(w['net'],w['conductor_mm2']))
 if level=='upper':
  for cable in E['intact_data_cables']:
   pts=[[ox+q[0]*sc,oy+q[1]*sc]for q in cable['waypoints_mm']];doc.line(pts,'#658c39',1.3,True);spatial_paths.append(LineString(pts).buffer(1.9))
   for p in [pts[0],pts[-1]]:doc.circle(*p,2,'#658c39','#658c39')
 placed=[]
 for label,(x0,y0,x1,y1)in spatial_labels:
  tw=pdfmetrics.stringWidth(label,'PlanB',8);candidates=[((x0+x1)/2,(y0+y1)/2),((x0+x1)/2,y0+13),((x0+x1)/2,y1-6),(x0+tw/2+5,y0+13),(x1-tw/2-5,y0+13),((x0+x1)/2,y0-5),((x0+x1)/2,y1+12)]
  candidates += [(x0+tw/2+5,yy)for yy in range(round(y0+13),round(y1-5),8)]
  for xx,yy in candidates:
   area=box(xx-tw/2-2,yy-9,xx+tw/2+2,yy+3)
   if area.bounds[0]<ox or area.bounds[2]>ox+216*sc:continue
   if not any(area.intersects(q)for q in spatial_paths+placed):doc.text(xx,yy,label,8,bold=True,anchor='middle');placed.append(area);break
  else:raise RuntimeError('No readable spatial module label '+label)
 y=150
 notes=['Drahtenden enden am wirklichen Kontakt oder dem eigenen Platinen-Durchlass.','Kupferfreie Lötstellen und Brücken siehe zugehörigen Lötplan.','Überkreuzungen ohne Knoten sind elektrisch getrennt; jede Ader isoliert.','Sammelverzweigungen sind gelötet, einzeln geschrumpft und am vorhandenen Rahmen entlastet.','Führung und Zuschnitt sind Planwerte; am bestückten Träger vor dem Schneiden prüfen.','Keine Änderungen am gedruckten Träger oder am laufenden R2-Druck.','Wege aus NetworkX; alle sechs Platinen mit Google OR-Tools platziert. Lage der Module auf den Ebenen unverändert.','USB_HOST: Servicebogen R22, 340° als Planlösung. Tatsächlicher Kabeldurchmesser und zulässiger Biegeradius offen.'if level=='upper'else'Untere Leistungswege nominal auf Z32; Enden mit Höhenwechsel zum Kontakt. Kein Nachweis mit vollständigen 3D-Kabelkörpern.']
 for t in notes:y=doc.para(685,y,t,460,11)+21
 doc.end()
def main():
 for p in SVG.glob('*.svg'):p.unlink()
 solder_docs()
 for name,title,level,overview in [('05-Verkabelung-Unten','Verkabelung · untere Ebene','lower',lower_overviews),('06-Verkabelung-Oben','Verkabelung · obere Ebene','upper',upper_overview),('07-Ebenenuebergang','Verkabelung · Ebenenübergang','interlevel',transition_overview)]:
  doc=Doc(name,title);overview(doc)
  if level!='interlevel':spatial(doc,level)
  wire_tables(doc,level);doc.finish()
 (ROOT/'output/drawings.json').write_text(json.dumps(dict(revision='PI-2L-5',drawing_revision='L5',source_sha256=hashlib.sha256((OUT/'daten/kontaktvertrag.json').read_bytes()).hexdigest(),documents=MANIFEST),ensure_ascii=False,indent=2)+'\n')
 print('PDFS',len(MANIFEST),'PAGES',sum(len(d['pages'])for d in MANIFEST))
if __name__=='__main__':main()

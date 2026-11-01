"""Technical SVGs from the exact same placed pins and copper contract as CAD."""
import json,html,math,subprocess,os,textwrap
from contract import R,D,B,M,OUT,components,body_spec,xy,save,sha
L=json.loads((OUT/'stripboard.json').read_text());S=OUT/'svg';S.mkdir(exist_ok=True);PNG=OUT/'svg-png';PNG.mkdir(exist_ok=True)
E=lambda s:html.escape(str(s),quote=True)
COL={'GND':'#334e5b','V5':'#b44d32','V5_BUCK':'#a66724','PINK':'#b54e87','PINK_INT':'#86589e','SENSOR_RAW':'#238474','SENSOR_ADC':'#467c60'}
class SVG:
 def __init__(self,name,title,subtitle,w=2400,h=1600):
  self.name=name;self.w=w;self.h=h;self.a=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="#f8faf9"/><g font-family="Arial,sans-serif" fill="#193b47">'];self.text(45,48,title,32);self.text(45,86,subtitle,20)
 def text(self,x,y,s,size=18,col='#193b47'):self.a.append(f'<text x="{x:.2f}" y="{y:.2f}" font-size="{size}" fill="{col}">{E(s)}</text>')
 def rect(self,x,y,w,h,fill,stroke='#566f77',opacity=1):self.a.append(f'<rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}" fill="{fill}" fill-opacity="{opacity}" stroke="{stroke}" stroke-width="1.3"/>')
 def line(self,pts,color='#657d84',width=2):self.a.append('<polyline points="'+' '.join(f'{x:.2f},{y:.2f}'for x,y in pts)+f'" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round"/>')
 def dot(self,x,y,r=4,fill='#b59b63'):self.a.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{r}" fill="{fill}"/>')
 def finish(self):
  self.text(45,self.h-40,'Trim-Sensor R3 · Raster immer [Spalte, Zeile], ab 0 · mm · reale Pass-/Funktionspruefung offen',18);self.text(45,self.h-15,'Quelle: inputs/design.json / delivery/stripboard.json · keine Bestands- oder Zuschnittbuchung durch diesen Plan',16)
  self.a.append('</g></svg>');p=S/(self.name+'.svg');p.write_text(''.join(self.a));return p

def board(name,title,mirror=False,cuts=False,bridge_subset=None,showbodies=True):
 v=SVG(name,title,'USB des Nano zeigt nach vorne / oben. Unterseite nur in der ausdrücklich gespiegelten Ansicht.',3200,1900);scale=18;ox,oy=65,165
 def pt(h):x,y=xy(h);return ox+(B['outline'][0]-x if mirror else x)*scale,oy+y*scale
 v.rect(ox,oy,B['outline'][0]*scale,1170,'#e0d5b5')
 for y in range(B['rows']):
  yy=pt([0,y])[1]
  if mirror or cuts:
   v.line([(ox,yy),(ox+B['outline'][0]*scale,yy)],'#b1945c',27)
  v.text(15,yy+5,y,18)
 for x in range(B['columns']):
  xx=pt([x,0])[0];v.text(xx-8,140,x,16)
  for y in range(B['rows']):v.dot(*pt([x,y]),4,'#f8faf9')
 # Complete copper-free mounting squares; bore positions are never mirrored silently.
 for mount in B.get('mounts',[]):
  xx,yy=mount['xy'];mx=B['outline'][0]-xx if mirror else xx
  if cuts:v.rect(ox+(mx-4)*scale,oy+(yy-4)*scale,8*scale,8*scale,'#fff4f0',stroke='#ad4434')
  v.dot(ox+mx*scale,oy+yy*scale,mount['diameter']/2*scale,'#f8faf9')
  v.text(ox+(mx-3)*scale,oy+(yy+3.7)*scale,mount['id'],12,'#803f31')
 if cuts:
  for c in L['cuts']:
   xx=ox+((B['outline'][0]-(B['origin'][0]+c['x_grid']*B['pitch']))if mirror else(B['origin'][0]+c['x_grid']*B['pitch']))*scale;yy=pt([0,c['row']])[1]
   v.line([(xx,yy-18),(xx,yy+18)],'#b93835',B['cut_width']*scale);v.text(xx-12,yy-22,c['id'][1:],11,'#9b2c32')
 if showbodies:
  for c in components():
   sp=body_spec(c);xx=sp['x'];xx=B['outline'][0]-xx-sp['w']if mirror else xx
   v.rect(ox+xx*scale,oy+sp['y']*scale,sp['w']*scale,sp['d']*scale,sp['color'],opacity=.82)
   v.text(ox+(xx+.3)*scale,oy+(sp['y']+sp['d']/2)*scale+5,c['ref'],18,'#fffdf5')
 for c in components():
  for pin,p in c['pads'].items():
   xx,yy=pt(p['hole']);v.dot(xx,yy,5,'#173d49');v.text(xx+7,yy-6,pin,12,'#163a45')
 if bridge_subset is not None:
  for w in bridge_subset:
   path=[pt(h)for h in w['path']];v.line(path,COL.get(w['net'],'#647ba2'),3)
   for x,y in[path[0],path[-1]]:v.dot(x,y,7,COL.get(w['net'],'#647ba2'));v.text(x+8,y+15,w['id'],13)
 # Bounded legend; full pin details have their own SVG table.
 for i,c in enumerate(components()):
  y=135+i*26;v.text(2600,y,c['ref']+'  '+c['value'][:45],15)
 v.text(70,1380,'Kupfer an8 Bohrungen jeweils8x8mm entfernen; sonst0,7mm-Nuten zwischen den Augen.',20)
 v.text(70,1410,'Lackdraht0.60mm, Plan-OD0.8mm. Lagenhoehe und Biegeradien je Draht in Brueckenliste; besetzter Pin: obere Loetlasche.',18)
 v.finish()
board('01-bestueckung','B1 · Bestueckungsseite · eine Streifenrasterplatine')
board('02-kupfer-unten','B1 · Kupferseite · gespiegelt · alle Unterbrechungen',True,True,showbodies=False)
board('03-unterbrechungen-oben','B1 · Schnittkoordinaten von oben · KEINE gespiegelten Koordinaten',False,True,showbodies=False)
# Small parallel sets keep the route overlays legible; dedicated atlas carries exact IDs and cuts.
for i in range(0,len(L['jumpers']),12):board('04-bruecken-'+str(i//12+1),'B1 · obere isolierte Bruecken '+str(i//12+1),bridge_subset=L['jumpers'][i:i+12],showbodies=False)
rows=[(c,p,pd)for c in components()for p,pd in c['pads'].items()]
v=SVG('05-pins','B1 · Pins, Netze und Rasterkoordinaten','Alle Koordinaten von oben; C/B/E und DIP-Markierungen nach Artikel vergleichen.',2400,180+len(rows)*31+80)
for i,(c,p,pd)in enumerate(rows):
 y=135+i*31
 if i%2==0:v.rect(40,y-22,2310,30,'#eaf0ef',stroke='#eaf0ef')
 for x,val in [(55,c['ref']),(165,c['value']),(800,'Pin '+p+' '+c.get('pin_names',{}).get(p,'')),(1040,str(pd['hole'])),(1330,pd['net']or'NC / isoliert lassen')]:v.text(x,y,str(val),19)
v.finish()
blocks=[(w,textwrap.wrap('Pfad '+str(w['path']),width=95))for w in L['jumpers']]
v=SVG('06-brueckenliste','B1 · Bruecken und Zuschnitt','Kupferlackdraht0.60mm / Plan-OD0.8mm. Oberseite; sechs fest zugewiesene Hoehenlagen. Enden entlacken/verzinnen; keine zweite Ader ins besetzte Auge.',2400,260+sum(85+20*len(lines)for w,lines in blocks))
y=135
for i,(w,lines)in enumerate(blocks):
 height=85+20*len(lines);v.rect(40,y-27,2310,height-4,'#eaf0ef'if i%2==0 else'#f4f7f6',stroke='#eaf0ef')
 v.text(55,y,w['id']+'  '+w['net'],22);v.text(600,y,str(w['start'])+'  →  '+str(w['end']),20);v.text(1790,y,str(w['cut_mm'])+'mm / L'+str(w['layer'])+' / Z'+str(w['height_above_board_bottom_mm'])+'mm',20)
 for j,line in enumerate(lines):v.text(600,y+25+j*20,line,18)
 v.text(600,y+40+20*len(lines),('Start: '+w['start_attachment']+'; Ziel: '+w['end_attachment']).replace('top-side solder lap at component lead; do not force second conductor through hole','obere Loetlasche am Bauteilbein').replace('dedicated solder eye','freies Loetauge').replace('top-side lead lap, no second wire in eye','obere Loetlasche am Bauteilbein; keine zweite Ader im Auge'),16)
 y+=height
v.finish()
v=SVG('07-gesamtverkabelung','Trim-Sensor · Gesamtverkabelung und Service-Trennung','Geber und Yamaha sind getrennte Signale bei gemeinsamer Masse. Kein direkter DAC-Ausgang an Yamaha.',2400,1500)
blocks=[('ORIGINALGEBER','J1 · 2P',[(1,'SENSOR_RAW'),(2,'GND')]),('YAMAHA','J2 · 3P',[(1,'PINK'),(2,'NC / gesperrt'),(3,'GND / BLACK')]),('BORDNETZ','J3 · 4P',[(1,'+12V ueber externe1A-Sicherung'),(2,'NC / gesperrt'),(3,'NC / gesperrt'),(4,'GND')])]
for i,(name,conn,pins)in enumerate(blocks):
 y=160+i*300;v.rect(55,y,580,230,'#e1ebe9');v.text(80,y+38,name,28);v.text(80,y+78,conn,24)
 for j,(pin,net)in enumerate(pins):v.text(80,y+115+j*27,str(pin)+'  '+net,20)
 v.line([(640,y+100),(890,y+100)],'#527784',4);v.text(665,y+80,'PG7 '+str(i+1)+' · Zugentlastung',18)
v.rect(895,150,1440,890,'#edf2f1');v.text(930,195,'B1 · 135 × 65 mm · gemeinsame Streifenrasterplatine',30)
for j,text in enumerate(['Geber → geschuetzte Messpuffer → Arduino A0; Erregung separat an A3','Arduino → MCP4725 → LM358 / Transistor → nachgebildeter Geberwert','Yamaha rosa → stromlos offener Relaiskontakt → Regelung UND Messung','Arduino A2 sperrt den Stromsenker beim Reset und bei Fehlern','D4: normale Relaisfreigabe; D5: Lebenszeichen fuer zweiten Spulenschalter','J3.1 → Verpolschutz → geschuetzte12V; davon Relais und Wandler','J5:1=geschuetzte12V  2=GND  3=Wandler5V  4=GND','Relais FBR211:1/12=Yamaha rosa,8=internes Signal,7 bleibt frei','Relais5/6=Spule12V; gemeinsamer Rueckleiter bleibt verbunden']):v.text(930,255+j*72,text,23)
v.rect(900,1100,1435,230,'#e4eceb');v.text(935,1145,'U4 · XL6009AUD, 48 × 25 mm, gemessene Gesamthoehe13mm',26)
v.text(935,1195,'Vier angeloetete, beschriftete Adern → abziehbarer4P-Stecker J5',23);v.text(935,1240,'IN+ → J5.1   IN− → J5.2   OUT+ → J5.3   OUT− → J5.4',23)
v.text(60,1120,'USB-Service:',27);v.text(60,1165,'J3 und J2 abziehen.',23);v.text(60,1210,'JP1 entfernen und aufbewahren.',23);v.text(60,1255,'Erst dann USB verbinden.',23)
v.text(60,1390,'Das Relais trennt die rosa Signalleitung. Die gemeinsame Masse bleibt verbunden.',21);v.finish()
# Purchased boards get explicit pin-reference SVGs, no invented internal copper.
for ref,title in [('U1','Nano USB-C ·30Pins'),('U2','MCP4725 ·6Pins'),('U4','XL6009AUD ·4Loetanschluesse')]:
 c=next(c for c in D['components']if c['ref']==ref);v=SVG('08-'+ref,title,'Kaufplatine: keine eigene Bruecke oder Streifenunterbrechung. Reale Artikel-/Pinvariante vergleichen.',1800,1000)
 if ref!='U4':
  sp=body_spec(c);scale=12;dx,dy=90-sp['x']*scale,160-sp['y']*scale;v.rect(90,160,sp['w']*scale,sp['d']*scale,sp['color'])
  for pin,pd in c['pads'].items():
   x,y=xy(pd['hole']);v.dot(dx+x*scale,dy+y*scale,5,'#efd49b')
  v.text(95,145,'USB / Vorderkante'if ref=='U1'else'Pin1 = OUT',20)
 else:
  v.rect(90,150,375,720,'#348775');v.text(120,190,'25 ×48mm',25);v.text(110,230,'H=13mm gemessen',19);v.text(100,780,'Padlage nicht vermessen',18)
 for i,(pin,net)in enumerate(c['pins'].items()):v.text(570,150+i*(22 if ref=='U1'else 75),'Pin '+pin+'  '+c.get('pin_names',{}).get(pin,pin)+'  →  '+str(net or'NC'),19 if ref=='U1'else 24)
 if ref=='U4':
  v.text(570,530,'IN+ → J5.1 / IN− → J5.2',25);v.text(570,590,'OUT+ → J5.3 / OUT− → J5.4',25);v.text(570,675,'IN'+str(D['harness']['buck_wire_mm'][:2])+'mm, OUT'+str(D['harness']['buck_wire_mm'][2:])+'mm Planadern; loesbarer Stecker.',23);v.text(570,735,'Padpositionen schematisch; aufgedruckte Polaritaet massgeblich.',20)
 v.finish()
# 1:1 coordinate aid; stock edge offset is a planning assumption, not measured.
a=['<svg xmlns="http://www.w3.org/2000/svg" width="155mm" height="85mm" viewBox="-10 -10 155 85"><rect x="-10" y="-10" width="155" height="85" fill="#fff"/><rect x="0" y="0" width="135" height="65" fill="#fff" stroke="#333" stroke-width=".2"/><g font-family="Arial" fill="#183b47">','<text x="0" y="-5" font-size="2">B1 135x65mm ·1:1 · Rasterrand3mm ist Planannahme</text>']
for x in range(B['columns']):
 for y in range(B['rows']):
  xx,yy=xy([x,y]);a.append(f'<circle cx="{xx}" cy="{yy}" r=".5" fill="none" stroke="#777" stroke-width=".1"/>')
for cut in L['cuts']:
 xx=B['origin'][0]+cut['x_grid']*B['pitch'];yy=xy([0,cut['row']])[1];a.append(f'<path d="M{xx},{yy-1}v2" stroke="#b43c37" stroke-width=".7"/>')
for mount in B.get('mounts',[]):
 xx,yy=mount['xy'];a.append(f'<rect x="{xx-4}" y="{yy-4}" width="8" height="8" fill="#ffebe7" stroke="#b43c37" stroke-width=".15"/><circle cx="{xx}" cy="{yy}" r="{mount["diameter"]/2}" fill="none" stroke="#245a7a" stroke-width=".2"/>')
a.append('<text x="0" y="71" font-size="1.8">Kupferseite gespiegelt separat; hier Ansicht VON OBEN. Vor Zuschnitt am echten Raster ausrichten.</text></g></svg>');(S/'09-rasterhilfe-1zu1.svg').write_text(''.join(a))

v=SVG('10-bohrungen','Verschraubung · B1 und vorhandener Wandler','Alle mm von der Bauteilseite. B1 vor Bestueckung bohren; Kaufmodule NICHT nachbohren.',2400,1350)
for i,mt in enumerate(B['mounts']):
 v.text(60,150+i*60,mt['id']+'   X/Y='+str(mt['xy'])+'   Bohrung '+str(mt['diameter'])+'mm   Kupfer8x8mm entfernen',24)
v.text(60,650,'B1:4x M3x8; Nano:2x M3x8; DAC:2x M3x8; alle Sackloecher nach dem Zusammenfuegen geschnitten.',25)
v.text(60,715,'Nano USB vorn: D13 links, D12 rechts. Kein zusaetzliches Nano-Modul noetig.',25)
v.text(60,805,'Wandler: alte DB-Diagonale gespiegelt gemaess direkter Nutzerkorrektur31.10.2026.',25)
v.text(60,860,'Quellrahmen IN links/OUT rechts: H1=[6.1,21.8], H2=[41.9,3.2].',26)
v.text(60,920,'Montagerahmen25x48: H1=[3.2,6.1], H2=[21.8,41.9]; starre Drehung+90Grad.',26)
v.text(60,980,'Wandler: M3-Fangrahmen mit4x M3x8. Vorhandene Modulbohrungen werden NICHT benutzt.',25)
v.text(60,1050,'Je Stecker:2x M3x8 zum Traeger plus1x M3x8 Spannschraube mit0.5mm Restweg.',25)
v.text(60,1115,'Haube4x M3x12 von unten; USB2x M3x8; drei Kabelsattel je2x M3x10. Keine zusaetzliche Dichtung.',25)
v.finish()

tp=json.loads((OUT/'testpoints.json').read_text())['points']
v=SVG('11-messpunkte','B1 · zugaengliche Messpunkte','Freie Augen oben kontaktierbar verzinnen; vorhandene obere Bruecken-Loetstelle darf Messpunkt sein. Haube abnehmen.',2400,1250)
for i,p in enumerate(tp):
 y=170+i*130;v.text(60,y,p['id']+'  '+p['net']+'   Raster '+str(p['hole'])+'   X/Y='+str([round(x,2)for x in p['xy_mm']])+'mm',28);v.text(60,y+75,('Vorhandene Bruecken-Loetstelle'if p['construction'].startswith('Probe')else'Freies Auge oben kontaktierbar verzinnen'),20);v.text(60,y+42,'1.2mm-Pruefspitze senkrecht zugaenglich im Nominalmodell; reales Loetauge und Adern pruefen.',23)
v.text(60,1060,'PINK_INT liegt HINTER dem Relais. Externes Yamaha-PINK separat an J2.1 messen.',25);v.finish()

# Render with existing central CLI policy, never a browser window.
import sys
sys.path.insert(0,str(R.parents[2]/'native-tools'))
# Actual native-tools lives next to fusion-enclosures.
from pathlib import Path
native=next(p for p in R.parents if p.name=='no5-rebuild')/'tools/native-tools';sys.path.insert(0,str(native))
from native_tools import run
records=[]
for p in sorted(S.glob('*.svg')):
 dst=PNG/(p.stem+'.png');res=run([os.environ.get('TRIM_RSVG_CLI','/opt/homebrew/bin/rsvg-convert'),'-w','1800','-o',str(dst),str(p)],capture_output=True,text=True,timeout=30)
 if res.returncode:raise RuntimeError(res.stderr)
 records.append(dict(file=p.name,sha256=sha(p),png_sha256=sha(dst)))
save(R/'reports/svg-render.json',dict(status='PASS',views=records,visual_review='PENDING'));print('SVG rendered',len(records))

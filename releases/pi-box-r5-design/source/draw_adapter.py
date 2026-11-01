"""Short, editable workshop drawing; actual mesh projection, no result opening."""
import sys,math,base64,html,subprocess,os
from pathlib import Path
import numpy as np,trimesh
from pypdf import PdfReader,PdfWriter
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));from prepare_upper_print import projection,run
W,H=1191,842
def esc(s):return html.escape(str(s))
class Page:
 def __init__(self,title,subtitle):
  self.p=[f'<svg xmlns="http://www.w3.org/2000/svg" width="420mm" height="297mm" viewBox="0 0 {W} {H}"><rect width="1191" height="842" fill="#fff"/><style>text{{font-family:Arial,DejaVu Sans,sans-serif;fill:#18303e}} .title{{font-size:25px;font-weight:bold}} .sub{{font-size:12px;fill:#526a76}}</style>'];self.text(32,43,title,25,True);self.text(32,66,subtitle,12);self.line(32,80,1158,80,'#66828f')
 def text(self,x,y,t,size=13,bold=False,color=None):self.p.append(f'<text x="{x}" y="{y}" font-size="{size}"'+(' font-weight="bold"'if bold else'')+(f' style="fill:{color}"'if color else'')+'>'+esc(t)+'</text>')
 def line(self,x,y,xx,yy,color='#344f5d',width=1.2,dash=False):self.p.append(f'<path d="M{x},{y} L{xx},{yy}" fill="none" stroke="{color}" stroke-width="{width}"'+(' stroke-dasharray="5 4"'if dash else'')+'/>')
 def circle(self,x,y,r,fill='white',stroke='#344f5d',width=1.2):self.p.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')
 def rect(self,x,y,w,h,fill='#e7eff2',stroke='#66828f'):self.p.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" stroke="{stroke}"/>')
 def lines(self,x,y,ls,size=13,spacing=22):
  for i,l in enumerate(ls):self.text(x,y+i*spacing,l,size)
 def dim(self,a,b,t,vertical=False):
  self.line(*a,*b,'#206e9a');dx,dy=(5,0)if vertical else(0,5)
  for x,y in [a,b]:self.line(x-dx,y-dy,x+dx,y+dy,'#206e9a')
  self.text((a[0]+b[0])/2+7,(a[1]+b[1])/2-7,t,12,True,'#206e9a')
 def end(self,page):
  self.line(32,804,1158,804,'#99adb6');self.text(32,825,'PI-2L-5 | Adapter-Teilpaket | mm | Reale Passung, PETG-Halt und Betrieb offen',11);self.text(1110,825,str(page)+'/2',11);return ''.join(self.p)+'</svg>'

def main():
 p=read(ROOT/'design.json');q=p['converter_adapter'];proof=read(ROOT/'output/adapter-independent-check.json');assert proof['status']=='PASS_SCOPED_ADAPTERS'
 sh=trimesh.load_mesh(ROOT/'delivery/print/CONVERTER_ADAPTER.stl',process=True);outline=projection(sh);shift=proof['print_translation_mm'];s=Page('Wandleradapter: getrennte Befestigungen','5 gleiche H-Träger und 10 Isolierscheiben. Unterplatte und Ebenenhöhe bleiben erhalten.')
 ox,oy,sc=75,160,9
 def pt(u,v):return ox+u*sc,oy+(25-v)*sc
 def ring(coords):
  pts=[pt(x-shift[0],y-shift[1])for x,y in coords];return'M'+' L'.join(f'{x:.3f},{y:.3f}'for x,y in pts)+' Z'
 polygons=[outline]if outline.geom_type=='Polygon'else list(outline.geoms)
 for poly in polygons:s.p.append('<path d="'+ring(poly.exterior.coords)+' '+ ' '.join(ring(h.coords)for h in poly.interiors)+'" fill="#d5e4e8" stroke="#355a6c" stroke-width="1.4" fill-rule="evenodd"/>')
 s.rect(ox,oy,48*sc,25*sc,'none','#8fa3aa');s.text(ox,oy-24,'Draufsicht auf die montierte Bauteilseite',15,True)
 for tag,coords,c in [('A',q['old_mounts_local_cad'],'#b17731'),('B',q['board_mounts_local_cad'],'#22688a')]:
  for i,(u,v)in enumerate(coords,1):
   x,y=pt(u,v);s.circle(x,y,(3.1 if tag=='A' else 3.7)*sc,'none',c,2);s.line(x-9,y,x+9,y,c);s.line(x,y-9,x,y+9,c);s.text(x+15,y-16,tag+str(i),15,True,c)
 s.text(45,410,'IN',18,True);s.text(485,410,'OUT',18,True)
 for u in [6.1,41.9]:
  x,y=pt(u,21.8);s.line(x,y,x,oy-10,'#78909b',1,True)
 s.dim((pt(6.1,21.8)[0],oy-9),(pt(41.9,21.8)[0],oy-9),'35,8')
 s.dim((555,pt(41.9,21.8)[1]),(555,pt(41.9,3.2)[1]),'18,6',True)
 s.lines(58,438,['A1/A2: M3×8 zur vorhandenen Unterplatte. Kopf in Ø6,2 × 2,4 tief.','B1/B2: M3×8 zum Wandler; unten gefangene M3-Mutter.','Wandler: links oben / rechts unten. A-Punkte liegen auf der anderen Diagonale.','Muttertaschen: SW5,8 ×3,5 tief, Einsetzen von unten.'],13)
 s.rect(610,108,540,280,'#f4f8fa');s.text(628,135,'Höhenkette und Schrauben',16,True)
 s.lines(628,164,['Unterplatte: vorhandene Stützenoberkante z = 13,0','Adapter: 4,0 Grundstärke + 1,6 Auflagen = 5,6','Wandlerunterseite: z = 18,6; Platine nominal 1,6','Isolierscheibe: 1,0; darüber Metallscheibe 0,5','Obere M3×8: Kopfauflage z = 21,7; Spitze z = 13,7','Mutter: z = 14,1 bis 16,5; voller Eingriff 2,4','Nominale Bauteiloberkante: z = 32,4','Planhülle bis z = 36,6; Oberplatte beginnt z = 40,0'],13,25)
 s.text(620,425,'Isolierscheibe: Einbaulage',16,True)
 sx,sy,ss=660,460,25
 s.rect(sx,sy+25,7*ss,1.6*ss,'#c2a46e');s.text(855,sy+52,'Wandlerplatine',12)
 s.rect(sx+(7-4.6)/2*ss,sy+15,4.6*ss,.4*ss,'#6e9fbd');s.rect(sx,sy,7*ss,.6*ss,'#6e9fbd');s.text(855,sy+12,'PETG: breiter Teil oben',12);s.text(855,sy+32,'kleiner Ring zur Platine',12)
 s.lines(618,570,['Gesamthöhe 1,0; Außen-Ø7; kleiner Ring Ø4,6; Bohrung Ø3,4.','Die 0,4 hohe Schulter hält den breiten Teil von kupfernahen','Anschlussflächen fern. Metallscheibe bleibt oberhalb der Isolation.','Drucklage umgekehrt: breite flache Fläche auf das Bett.'],12,21)
 s.text(58,550,'Montagefolge',16,True);s.lines(58,579,['1. Je Adapter zwei Muttern von unten einsetzen.','2. Adapter mit den bisherigen zwei M3×8 aufschrauben.','3. Wandler aufsetzen, pro Loch Isolierscheibe und Metallscheibe.','4. Mit zwei weiteren M3×8 befestigen; Unterseite kontrollieren.'],13,24)
 s.lines(58,703,['Maßbezug: Platine48×25, Lochabstände35,8×18,6 aus DB51.','Randmaße6,1/3,2 und Ø3 bleiben freigegebene Planannahmen (DB60).','Keine neuen Bohrungen am Kaufteil. Diese Zeichnung ist keine Bohrschablone.'],12,21)
 pages=[s.end(1)]
 s=Page('Druckumfang und Prüfstand','Adapter-Teilpaket digital geprüft; vollständiger Innenaufbau wegen Steckeraufnahmen noch offen.')
 s.text(42,123,'Auf dem vorbereiteten Adapterbett',18,True)
 s.lines(42,157,['5 × Wandleradapter','10 × gestufte Isolierscheibe','PETG, 0,2mm, vier Wände, 20% Gyroid, 240/85°C','Keine Stützen; Mutternester mit kurzen Brücken.','Druckvorbereitung, keine Übertragung und kein Druckstart.'],14,27)
 s.text(42,329,'Zusätzlicher Schraubbedarf',18,True);s.lines(42,362,['10 × M3×8; weitere zehn bisherige Schrauben werden weiterverwendet.','10 × M3-Mutter; 10 × Metallscheibe Ø≤7 ×0,5.','10 × gedruckte Isolierscheibe gehören bereits zum Adapterbett.'],13,24)
 s.text(42,488,'Digital geprüft',18,True);s.lines(42,522,['Alle fünf Einbauplätze gegen die vorhandenen R1-Stützen.','Getrennte Schraubpaare, tragende Mutterdächer, Unterseitenfreiheit.','Gezielte falsche Diagonale und zu lange Schraube werden erkannt.','STEP, STL und 3MF zurückgelesen; tatsächliche Druckbahnen separat.','Reale Kaufteilpassung, PETG-Gewinde und Wärme sind weiterhin offen.'],13,24)
 s.rect(655,110,495,523,'#fff4df','#b08432');s.text(675,144,'Noch kein vollständiges letztes Druckbett',18,True)
 s.lines(675,184,['Weiterhin erforderlich:','2 Hub-Sicherungsplatinenträger samt Steckeraufnahme','1 CAN-Steckeraufnahme und1 CAN-Gegenhülse','L4-Aufnahmen für alle neun Modulsteckverbindungen','Kabel-/USB-Reservehalter nach endgültiger Anordnung'],13,27)
 s.lines(675,365,['Aktueller Konflikt:','Die größere26×30×24-mm-Planhülle lässt sich mit der','aktuellen Bauteilanordnung nicht neunfach platzieren.','Eine zusätzliche gemeinsame Optimierung innerhalb','der Platinen hat noch keine qualifizierte Lösung.','Kleinere echte Steckmaße können den Konflikt lösen.','Kein stillschweigendes Verkleinern der Planhülle.'],13,25)
 s.lines(42,721,['Neue Quelle: two-level-r5/design.json und source/. R1/R2 und L4 bleiben erhalten.','Die noch offenen Schnittstellen verhindern die Freigabe des vollständigen Innenaufbaus; das Adapterbett ist ein gesonderter Teilsatz.'],12,22)
 pages.append(s.end(2));out=ROOT/'delivery/zeichnungen';writer=PdfWriter();renders=[]
 for i,svg in enumerate(pages,1):
  name='Adapter-'+str(i).zfill(2);src=out/(name+'.svg');src.write_text(svg)
  single=ROOT/'output'/(name+'-intermediate.pdf')
  run(['rsvg-convert','-f','pdf','-o',str(single),str(src)],capture_output=True,text=True,check=True)
  writer.append(PdfReader(single))
  run(['rsvg-convert','-w','1787','-h','1263','-o',str(ROOT/'output'/(name+'.png')),str(src)],capture_output=True,text=True,check=True);renders.append(name)
 final=ROOT/'delivery/Adapter-und-Druckstand-PI-2L-5.pdf';writer.write(final);r=PdfReader(final);assert len(r.pages)==2 and all(len(p.extract_text())>500 for p in r.pages)
 # Render the actual delivered PDF independently, not just the SVG sources.
 env=dict(os.environ);fc=Path('/opt/homebrew/etc/fonts/fonts.conf')
 if fc.exists():env['FONTCONFIG_FILE']=str(fc)
 rr=subprocess.run(['pdftoppm','-scale-to','1787','-png',str(final),str(ROOT/'output/pdf-review')],capture_output=True,text=True,timeout=90,env=env);assert rr.returncode==0,rr.stderr
 save(ROOT/'output/drawing-check.json',dict(revision='PI-2L-5',pdf_sha256=sha(final),pages=2,pdf_readback=True,independent_pdf_render=True,svg_pages=renders,pixel_review='PENDING'));print('DRAWING PDF RENDERED',flush=True)
if __name__=='__main__':main()

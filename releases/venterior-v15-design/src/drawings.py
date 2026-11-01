"""SVG-only electrical delivery; no report PDF or window is created."""
import csv,html,math,shutil
from collections import defaultdict
from common import ROOT,OUT,read,save,sha

COLORS=['#a33c28','#256f94','#476b24','#7e4b98','#a16b12','#22695a','#aa365d','#5267a2']
def colour(n):return '#263b48'if n=='GND'else'#a93535'if n in['PICO_3V3','USB_5V']else COLORS[sum(n.encode())%len(COLORS)]
class SVG:
    def __init__(self,title,w=2500,h=3400,subtitle=''):
        self.w=w;self.h=h;self.s=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">','<rect width="100%" height="100%" fill="#fff"/>','<g font-family="Arial,DejaVu Sans,sans-serif" fill="#203a48">'];self.text(55,65,title,35,bold=True);self.text(55,105,subtitle,19)
    def text(self,x,y,s,size=18,fill='#203a48',bold=False,anchor='start',rotate=None):
        self.s.append(f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" text-anchor="{anchor}"'+(' font-weight="bold"'if bold else'')+(f' transform="rotate({rotate} {x} {y})"'if rotate else'')+'>'+html.escape(str(s))+'</text>')
    def rect(self,x,y,w,h,fill='none',stroke='#8a9ba3',sw=1):self.s.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
    def line(self,pts,color='#42687a',width=2,dash=False,attrs=''):
        self.s.append('<polyline points="'+' '.join(f'{x:.2f},{y:.2f}'for x,y in pts)+f'" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"'+(' stroke-dasharray="7 5"'if dash else'')+attrs+'/>')
    def circle(self,x,y,r,fill='white',stroke='#786d57',sw=1):self.s.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
    def write(self,name):
        self.text(55,self.h-35,'V15 · Digitale Planung. Reale Passung, Schutz-/Fehlerprüfung und Betrieb offen. Keine Bohrschablone.',17,'#64727b')
        p=OUT/'svg'/name;p.parent.mkdir(exist_ok=True);p.write_text('\n'.join(self.s+['</g></svg>']));return p

def board_sheet(c,name,title,wires=None,back=False,focus=None):
    q={**c['board'],'size':c['board'].get('layout_size',c['board']['size']),'mounts':c['board'].get('layout_mounts',c['board']['mounts'])};scale=12;ox,oy=110,190;tablex=2150;draw_w=q['size'][0]*scale;draw_h=q['size'][1]*scale
    wires=wires or[];h=max(3200,430+max(len(wires),len(c['parts']))*38)
    g=SVG(title,3300,h,'Rückseite: Ansicht auf Kupfer, X gespiegelt; Lochkennungen bleiben gleich.'if back else'Bestückungsseite · Layout160×96mm, im Gehäuse +90° gedreht · Querbrücken oben')
    def pt(x,y):return ox+(q['size'][0]-x if back else x)*scale,oy+y*scale
    def grid(x,y):return pt(q['datum'][0]+(x-1)*2.54,q['datum'][1]+(y-1)*2.54)
    g.rect(ox,oy,draw_w,draw_h,'#fff9ec','#9f854f',2)
    if back:
        for y in range(1,q['rows']+1):
            cuts=sorted(a['after_col']for a in c['cuts']if a['y']==y);ends=[-.5,*cuts,q['cols']+.5]
            for a,z in zip(ends,ends[1:]):
                aa=q['datum'][0]+(a-.5)*2.54+.35;zz=q['datum'][0]+(z-.5)*2.54-.35;yy=q['datum'][1]+(y-1)*2.54
                g.line([pt(max(.6,aa),yy),pt(min(q['size'][0]-.6,zz),yy)],'#d1a164',14)
    for y in range(1,q['rows']+1):
        for x in range(1,q['cols']+1):
            mmx=q['datum'][0]+(x-1)*2.54;mmy=q['datum'][1]+(y-1)*2.54
            if any(math.hypot(mmx-a,mmy-b)<4.2 for a,b in q['mounts']):continue
            g.circle(*grid(x,y),.5*scale,'white','#b5a27f',.6)
    for x,y in q['mounts']:g.circle(*pt(x,y),4.2*scale,'#edf0ec','#9da9aa');g.circle(*pt(x,y),1.7*scale,'white','#647a83')
    if not back:
        for p in c['parts']:
            grey=focus is not None and p['group']!=focus
            xs=[v[0]for v in p['pad_grid'].values()];ys=[v[1]for v in p['pad_grid'].values()];x0,x1=min(xs),max(xs);y0,y1=min(ys),max(ys)
            if p['kind']in['pico','dual_opamp','dip14','dip16','dip4','header16','npn','jumper']:
                a,b=grid(x0-.35,y0-.45),grid(x1+.35,y1+.45);g.rect(*a,b[0]-a[0],b[1]-a[1],'#f4f5f4'if grey else'#e1e9e9','#9dabae'if grey else'#3f6474',2)
                g.text((a[0]+b[0])/2+(20 if p['kind']=='header16'else 0),(a[1]+b[1])/2,p['ref'],19,'#9da7aa'if grey else'#244956',True,'middle',-90 if p['kind']in['pico','header16']else None)
            else:
                a,b=grid(x0,y0),grid(x1,y1);g.line([a,b],'#b1b9b9'if grey else'#556d76',2)
                if not p.get('dnp'):
                    mid=(a[1]+b[1])/2;ww,hh=(85,255)if p.get('item_id')==86 else(136,170)if p['ref']=='C_TIM'else(32,86)
                    g.rect(a[0]-ww/2,mid-hh/2,ww,hh,'#ece6d4'if not grey else'#f0f0ec','#8c8d7b');g.text(a[0]+5,mid,p['ref'],12,'#64747a'if grey else'#273e45',False,'middle',-90)
                else:g.text(a[0]+10,(a[1]+b[1])/2,p['ref']+' DNP',12,'#9c4656',rotate=-90)
            for pin,(x,y)in p['pad_grid'].items():
                g.circle(*grid(x,y),6,'#fafefe','#546b78',1)
                if pin in['1','A','C']:g.circle(*grid(x,y),9,'none','#805c35',1)
                if p['kind']in['pico','dual_opamp','dip14','dip16','dip4','header16']:
                    xx,yy=grid(x,y);left=x==x0;g.text(xx+(10 if left else-10),yy+4,pin,12,'#526672',anchor='start'if left else'end')
    for w in wires:
        pts=[grid(*p)for p in w['points']]
        # White gap makes an insulated crossover distinguishable from a dot.
        g.line(pts,'white',5,attrs=f' data-wire-id="{w["id"]}" data-net="{w["net"]}"')
        g.line(pts,colour(w['net']),2.4)
        for a in[w['start'],w['end']]:g.circle(*grid(*a),7,colour(w['net']),'white',1)
    if back:
        for a in c['cuts']:
            x,y=grid(a['after_col']+.5,a['y']);g.line([(x,y-14),(x,y+14)],'#bc3441',5)
    for x in range(1,q['cols']+1):g.text(*grid(x,-.7),x,14,anchor='middle')
    for y in range(1,q['rows']+1):g.text(ox-16,grid(1,y)[1]+5,y,15,anchor='end')
    g.text(ox,oy+draw_h+45,f'{q["size"][0]} × {q["size"][1]} × {q["size"][2]} mm · Raster2,54mm · Montagebohrungen Ø3,4 / kupferfrei Ø8,4',20)
    g.text(ox,oy+draw_h+77,'Rohplatte ausrichten. Kreuzungen oben staffeln (kreuzungen.csv); Längen enthalten Formzugabe. Reale Formprüfung offen.',17)
    g.text(tablex,180,'Kennungen / vollständige Anschlussdaten',22,bold=True)
    if back:
        for i,a in enumerate(c['cuts']):
            xx=tablex+(i//77)*430;yy=225+(i%77)*34;g.text(xx,yy,f'R{a["y"]}: {a["after_col"]} | {a["after_col"]+1}',17)
        g.text(tablex,2920,'Trennbreite0,6mm, zwischen den Löchern.',19)
    elif wires:
        for i,w in enumerate(wires):
            yy=235+i*135;g.text(tablex,yy,w['id']+' · '+w['net'],20,colour(w['net']),True)
            g.text(tablex,yy+32,f'({w["start"][0]},{w["start"][1]}) → ({w["end"][0]},{w["end"][1]})',19)
            g.text(tablex,yy+63,f'Weg {w["length_mm"]} + Enden8 + Reserve4 mm',16)
            g.text(tablex,yy+92,f'Grundhöhe{w["height_mm"]}mm; lokale Anhebungen siehe kreuzungen.csv.',16)
    else:
        for i,p in enumerate(c['parts']):
            yy=225+i*38;g.text(tablex,yy,p['ref']+(' · DNP'if p.get('dnp')else''),17,bold=True);g.text(tablex+200,yy,p['value'][:64],16)
    return g.write(name)

def schematic(c):
    parts=c['parts'];cols=4;cw=820;heights=[max(240,90+len(p['pins'])*27)for p in parts];placements=[];ys=[180]*cols
    for p,hh in zip(parts,heights):i=min(range(cols),key=lambda j:ys[j]);placements.append((p,65+i*cw,ys[i],hh));ys[i]+=hh+35
    g=SVG('Gesamtschaltung · benannte Netze',cols*cw+100,max(ys)+100,'Gleiche Netzbezeichnung = elektrisch verbunden. NC = offen; DNP = unbestückt. Kein Firmware- oder Betriebsnachweis.')
    for p,x,y,hh in placements:
        g.rect(x,y,cw-35,hh,'#f8faf9','#9aadb4');g.text(x+15,y+30,p['ref']+' · '+p['value'][:60],19,bold=True)
        g.text(x+15,y+58,p['group']+(' · NICHT BESTÜCKEN'if p.get('dnp')else''),15,'#816132')
        for i,(pin,net)in enumerate(p['pins'].items()):
            yy=y+90+i*27;g.line([(x+20,yy),(x+90,yy)],colour(net or'NC'),2);g.circle(x+20,yy,3,'white',colour(net or'NC'));g.text(x+43,yy-6,pin,14);g.text(x+105,yy+5,net or'NC',18,colour(net or'NC'))
    return g.write('schaltplan-gesamt.svg')

def harness(c):
    p=read(ROOT/'parameters.json');board=p['board'];front=p['front'];header=next(x for x in c['parts']if x['ref']=='J_SIGNAL');wires=[]
    taps={1:'ECHO_AD: vorhandener bestätigter Abgriff',2:'J3_GND',3:'T1 blau / passiver Sendemarker',4:'J3_GND',5:'Q10-3 / TX-Injektion',6:'J3_GND',7:'T1 gelb / vorhandener Temperatursensor',8:'J3_GND',9:'D / unterer R42-Anschluss',10:'J3_GND',11:'K1 lokale Gruppe1',12:'K1 lokale Gruppe4',13:'Venterior +6V am Gerät, Kelvin-Abgriff',14:'RE_LOOP100k zum originalen GND',15:'NC / Reserve',16:'NC / Reserve'}
    # Separate GND contacts can share one device-end solder fanout. They are
    # never combined with the converter load return in a thin ADC wire.
    for i in range(1,15):
        net=header['pins'][str(i)];x,y=header['pad_grid'][str(i)];world=[board['origin'][0]+96-(board['datum'][1]+(y-1)*2.54),board['origin'][1]+board['datum'][0]+(x-1)*2.54,board['origin'][2]+12]
        device=[front['origin'][0]+front['size'][0]-5,front['origin'][1]+15+7*(i-1),front['origin'][2]+7]
        route=[device,[96,device[1],18],[96,world[1],18],world]
        length=sum(math.dist(a,b)for a,b in zip(route,route[1:]));wires.append(dict(id=f'H{i:02}',net=net,source=taps[i],target=f'J_SIGNAL.{i}',cross_section_mm2=.22,route_mm=round(length,1),termination_mm=12,service_mm=30,cut_plan_mm=5*math.ceil((length+42)/5),cut_release=False,route_basis='Conservative carrier-channel polyline; original tap coordinates estimated, page geometry not used as cable length'))
    wires+= [dict(id='P01',net='12V_IN',source='12V abgesicherte Quelle',target='X12V.1 → XL6009 IN+',cross_section_mm2=1.5,cut_plan_mm=110,cut_release=False),dict(id='P02',net='POWER_GND',source='12V Quelle GND',target='X12V.2 → XL6009 IN-',cross_section_mm2=1.5,cut_plan_mm=110,cut_release=False),dict(id='P03',net='VENT_6V',source='XL6009 OUT+',target='X6V.3 → Venterior Batterie+',cross_section_mm2=1.5,cut_plan_mm=180,cut_release=False),dict(id='P04',net='POWER_GND',source='XL6009 OUT-',target='X6V.4 → Venterior Batterie-',cross_section_mm2=1.5,cut_plan_mm=180,cut_release=False)]
    g=SVG('Gesamtverdrahtung · steckbare Baugruppen',2500,2850,'Signal KK254 1×16. X12V:1+/2−,3/4NC. X6V:3+/4−,1/2NC; unterschiedliche Kontaktbelegung.')
    boxes=[(75,200,650,180,'Originalfront + Venterior'),(1250,200,1080,180,'Eine Hauptplatine + gesockelter Pico'),(75,2280,650,130,'12V-Eingang / Wandler'),(1250,2280,1080,130,'Separater6V-Stecker zum Venterior')]
    for x,y,w,h,s in boxes:g.rect(x,y,w,h,'#edf2f2','#7b929e',2);g.text(x+20,y+50,s,27,bold=True)
    for i,w in enumerate(wires[:14]):
        yy=465+i*115;co=colour(w['net']);g.text(75,yy,w['source'],18);g.text(1250,yy,w['target']+' · '+w['net'],19,bold=True)
        g.line([(745,yy-7),(1220,yy-7)],co,3);g.text(900,yy-20,w['id'],19,co,True);g.text(75,yy+31,f'0,22mm² · Zuschnittplan {w["cut_plan_mm"]}mm einschließlich Enden/Service; am Gerüst verifizieren.',16)
    g.text(75,2150,'RE_STATE100k direkt an D/GND; RE_LOOP100k direkt am Geräte-GND. Einbauenden separat isolieren und zugentlasten.',20)
    for i,w in enumerate(wires[14:]):g.text(75,2470+i*60,f'{w["id"]}: {w["source"]} → {w["target"]}; {w["cross_section_mm2"]}mm²; Plan {w["cut_plan_mm"]}mm',20)
    g.write('gesamtverdrahtung.svg');save(OUT/'data/harness.json',dict(wires=wires,connector_orientation='Pin1 triangle/key; board top view for all pin IDs; cable counterpart viewed into mating face is separately reversed physically, never renumbered',remote_components=c['remote'],usb='Existing continuous cable: externalPi → carrier split clamp → PicoMicroB',external_protection='12V input requires coordinated fuse/transient/reverse-polarity qualification; no numerical boat-circuit rating inferred',physical_cut_release=False))
    with(OUT/'data/anschluesse.csv').open('w',newline='')as f:
        wr=csv.writer(f);wr.writerow(['ID','Netz','Von','Nach','mm2','Zuschnittplan_mm','Realer_Zuschnitt_freigegeben'])
        for w in wires:wr.writerow([w['id'],w['net'],w['source'],w['target'],w['cross_section_mm2'],w['cut_plan_mm'],False])

def original_taps(c):
    import xml.etree.ElementTree as ET
    source=ROOT/'inputs/original-contact-model.svg'
    # Original photo-derived vector is retained as orientation evidence. The
    # new table is generated from the very same canonical connector contract.
    g=SVG('Originalplatine · Lötstellen und steckbarer Anschluss',2800,3600,'Originalansichten aus dem vorhandenen Fotoabgleich · keine Spiegelung der Kontaktkennungen · nicht maßhaltig')
    root=ET.parse(source).getroot()
    g.s.append('<g transform="translate(60 155) scale(.135)">')
    for n in root:g.s.append(ET.tostring(n,encoding='unicode'))
    g.s.append('</g>')
    g.text(1900,210,'Zum Hauptstecker J_SIGNAL',23,bold=True)
    taps={1:'ECHO-AD',2:'J3 GND',3:'T1 blau',4:'J3 GND',5:'Q10 lokales Bein3',6:'J3 GND',7:'T1 gelb',8:'J3 GND',9:'D, R42 unten',10:'J3 GND',11:'K1 lokale Gruppe1',12:'K1 lokale Gruppe4',13:'Geräte-Zuleitung +6V',14:'RE_LOOP100k an GND',15:'NC',16:'NC'}
    h=next(p for p in c['parts']if p['ref']=='J_SIGNAL')
    for i in range(1,17):
        y=280+(i-1)*83;g.text(1900,y,f'{i}: {taps[i]}',22,bold=True);g.text(1900,y+30,h['pins'][str(i)]or'NC / nicht bestücken',18)
    for i,line in enumerate(['RE_STATE100k direkt zwischen D und Geräte-GND, isoliert befestigen.', 'RE_LOOP100k direkt am Geräte-GND; seine andere Seite ist die separate Leitung H14.', 'K1-Gruppe1 und4 sind die zwei im Originalfoto getrennten Kontaktgruppen.', 'Andere Tasterbeine weder aus einer Spiegelung noch aus C/B/E-Namen ableiten.', '6V separat über X6V.3+/4−,1/2NC;12V benutzt1+/2−,3/4NC. Kelvin H13 erst am Gerät.', 'H02/H04/H06/H08/H10: beschriftete GND-Abzweige, keine Lastversorgung.', 'Alle angelöteten Signaladern enden in einem abziehbaren, verriegelten Stecker.', 'Lötstellen einzeln isolieren und vor dem Stecker an der Originalschale entlasten.', 'Identität, Durchgang und Spannung am eigenen Gerät vor Anschluss prüfen.']):g.text(65,2250+i*62,line,23)
    g.write('original-loetstellen.svg')

def main():
    c=read(OUT/'data/electronics.json');(OUT/'svg').mkdir(exist_ok=True)
    # Remove only this generator's numbered bridge sheets before repagination;
    # otherwise a now-unused last page would duplicate an old wire revision.
    for old in(OUT/'svg').glob('bruecken-lage*-*.svg'):old.unlink()
    board_sheet(c,'bestueckung.svg','Hauptplatine · vollständige Bestückung')
    board_sheet(c,'kupfer-trennstellen.svg','Hauptplatine · alle Kupfertrennungen',back=True)
    for lane in sorted({w['height_lane']for w in c['bridges']}):
        ww=[w for w in c['bridges']if w['height_lane']==lane]
        for i in range(0,len(ww),12):board_sheet(c,f'bruecken-lage{lane}-{i//12+1:02}.svg',f'Oberseitige Brücken · Höhenlage {lane} · Blatt {i//12+1}',wires=ww[i:i+12])
    for group in['rx','marker-tx','aux-sense','telemetry','power','pico']:
        board_sheet(c,'funktion-'+group+'.svg','Bestückungsbereich · '+group,focus=group)
    schematic(c);harness(c);original_taps(c)
    with(OUT/'data/bruecken.csv').open('w',newline='')as f:
        wr=csv.writer(f);wr.writerow(['ID','Netz','Von_Loch_XY','Nach_Loch_XY','Netto_mm','Enden_mm','Service_mm','Zuschnittplan_mm','Seite','Hoehe_mm'])
        for w in c['bridges']:wr.writerow([w['id'],w['net'],str(w['start']),str(w['end']),w['length_mm'],8,4,math.ceil(w['length_mm']+12),'Bestückung',w['height_mm']])
    save(OUT/'data/drawings.json',dict(files=[dict(name=p.name,sha256=sha(p))for p in sorted((OUT/'svg').glob('*.svg'))],source=sha(OUT/'data/electronics.json'),wire_count=len(c['bridges']),physical_scope='One board; function views are logical sections, not additional physical modules'))
    print('SVG',len(list((OUT/'svg').glob('*.svg'))),'and full bridge/harness tables',flush=True)

if __name__=='__main__':main()

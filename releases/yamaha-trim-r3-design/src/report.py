"""Short German result plus complete R2-finding-to-R3 disposition ledger."""
import json,datetime,hashlib,sys,platform,subprocess,itertools,math
from pathlib import Path
from contract import R,D,M,OUT,save,sha

def main():
 reports={}
 for p in sorted((R/'reports').rglob('*.json')):
  if p.name in['summary.json','r2-dispositions.json','inventory-actions.json']or 'cache'in p.parts:continue
  try:v=json.loads(p.read_text())
  except Exception:continue
  if isinstance(v,dict)and any(k in v for k in['status','design_status']):reports[str(p.relative_to(R))]=dict(status='OPEN'if v.get('tool_execution',{}).get('run_status')=='FAIL'else v.get('design_status',v.get('status')),sha256=sha(p),run_status=v.get('tool_execution',{}).get('run_status',v.get('run_status','not separately recorded')))
 items=[
 ('RELAY-TEMP','Relais bei50°C Einbauort','OPEN','Der vorhandene FBR211SBD012-M wird laut Hersteller nur bis55°C Umgebung spezifiziert. Spule und Wandler erwärmen die Box.','Temperatur am Relais nach thermischem Gleichgewicht bei50°C Außenluft messen; innerhalb Herstellergrenze bleiben. Kein verfügbarer Ersatz wird erfunden.'),
 ('RELAY-LOW-LOAD','Sehr kleine Kontaktströme','OPEN','Hersteller-Mindestlast ist eine Referenz unter festgelegten Bedingungen; nahe500Ω sinkt der historische Prüfstrom auf ca7.2mA. Bei offenem/fehlerhaftem Anschluss fließt gegebenenfalls weniger.','Originalteil, NC/NO und Kontaktwiderstand bei tatsächlichen Yamaha-Strömen messen. Kontaktverschweißen bleibt durch diese Schaltung nicht beherrscht.'),
 ('ANALOG','Ausgang5–500Ω und Regelstabilität','OPEN','Diagnosemodelle zeigen die vorgesehenen Funktionen. Das Hersteller-LM358-Modell besteht die Referenzschaltung nicht und wird nicht als Funktionsbeleg verwendet.','Stabilität, untere Endlage, Sprünge, Lasten und Rückmessung mit Oszilloskop am abgesicherten Prüfstand messen.5Ω bleibt Ziel.'),
 ('INPUT','Geber-Fremdspannung und ausgeschaltete Versorgung','OPEN','D6 sperrt die direkte Speisung zurück nach5V. LM358-Puffer trennt Rohsignal von Arduino; positive Eingangssperrung bei VCC0 ist für TI dokumentiert, tatsächlicher Hersteller unbestätigt.','Bauteilmarkierung und Pinfolge prüfen,12V-Fremdspannung bei5Vaus strombegrenzt messen; negative Fehler über Vorwiderstände/1N4148 begrenzt. Keine pauschale Störimpulsfestigkeit.'),
 ('POWER','Bordnetz und USB','OPEN','Quellensicherung,10SQ050-Verpolschutz,P6KE18A und zugänglicher Wandler sind geplant. Tatsächliche Bordnetzspitzen, TVS-Energie und Clone-Rückwege sind ungemessen.','Wandler auf5V einstellen. USB-Service mit J2/J3 abgezogen und JP1 offen. Gleichzeitiges USB/12V-CSV erst nach Prüfung der Clone-Rückwege bei offenem JP1.'),
 ('RESETPHYSICAL','Arduino-Bootloader und Unterspannung','OPEN','Hostprüfungen und beide AVR-Builds laufen; diese üben den realen Bootloader, ADC, Sicherungsbits und die Abschaltzeit nicht aus.','Bandgap gegen gemessene5V kalibrieren; langsame Versorgung, Brownout und Watchdog am vorhandenen Nano prüfen. Lebenszeichenstop und Kontaktöffnung messen.'),
 ('CONTACTS','Kaufteilmaße und Anschlüsse','OPEN','Relaiszeichnung ist von unten in die Draufsicht umgerechnet.2N2222A-Anschlussfolge und Kaufmodul-Pinfolge hängen vom gelieferten Artikel ab.','Vor Bestromung durchmessen; keine erfundenen Wandlerbohrungen, vorhandene Löcher werden durch Randhalter nicht genutzt.'),
 ('PRINTFIT','Druck und Schraubhalt','OPEN','Gültige digitale Körper ergeben noch keine reale Passung oder Festigkeit.','PETG, Stützenentfernung, Schraubenhalt/Kriechen bei50°C plus Eigenerwärmung und Zug-/Kippversuche prüfen. Steckerhalter mit Dach benötigen Stützplanung.'),
 ('CABLE','Leitungen, Steckkontakte und Dichtung','OPEN','Brücken sind mit0.8mm Plan-OD modelliert; tatsächlicher Lackauftrag, obere Lötlaschen, Kabelmantel und Wandlerpadlage bleiben ungemessen. Haube ohne zusätzliche Dichtung.','Material und Lötzugang vergleichen, Buchsen/Steckerstifte müssen den2mm-Abstandhalter durchragen, Lack an Lötenden entfernen. Kein Wasserdichtheitsversprechen.'),
 ('YAMAHA','Betrieb am vorgesehenen Motor','OPEN','5.027V/201.9219Ω ist ein historisches Prüfmodell, keine Messung des aktuellen Yamaha-Systems. Gemeinsame Masse bleibt verbunden.','Leerlaufquelle und Geberendpunkte am System messen, neu kalibrieren, Anzeige und Fehleröffnen im Einsatz prüfen. Kurzschluss nahe echter0Ω-Endlage nicht sicher unterscheidbar.')]
 save(OUT/'open-items.json',dict(status='OPEN',items=[dict(id=i,subject=s,status=st,evidence=e,needed=n)for i,s,st,e,n in items],delivery_blocked=False))
 # Conditional arithmetic, clearly separate from measured stock tolerances.
 corners=[]
 for tol in [.01,.05]:
  ratios=[v[2]/sum(v)for v in itertools.product([10000*(1-tol),10000*(1+tol)],repeat=3)]
  corners.append(dict(assumed_each_resistor_tolerance=tol,third_ratio_min=min(ratios),third_ratio_max=max(ratios),uncalibrated_scale_error_percent=[100*(3*min(ratios)-1),100*(3*max(ratios)-1)]))
 budget=dict(status='OPEN',conditional_divider_corners=corners,assumed_TI_LM358_max_offset_mV_over_temperature=9,corresponding_output_offset_mV=27,ADC_5V_referred_PINK_step_mV=5000*3/1023,DAC_5V_step_mV=5000/4096,historical_target_V={str(x):5.027*x/(201.9219+x)for x in[5,100,500]},limits='Conditional arithmetic, no verified resistor grade, stock opamp manufacturer, ADC/bandgap calibration or accuracy requirement. Not an aggregate guaranteed error. Two-endpoint calibration removes some fixed offsets/scales but not temperature and nonlinearities.')
 save(OUT/'error-budget.json',budget)
 # Every original observation gets a disposition, including retained PASS scope.
 a=R.parent/'audits/open-carrier-r2-functional-r1';latest=json.loads((a/'latest.json').read_text());old=a/latest['run'];ledger=[]
 groups={'mechanics':['reports/functional/mechanics.json','reports/collisions.json','reports/keying.json','reports/wire-space.json'],'connectivity':['reports/functional/connectivity.json'],'kicad':['reports/functional/kicad.json'],'spice':['reports/spice-summary.json','reports/model-qualification.json'],'firmware':['reports/firmware.json'],'meshes':['reports/mesh-check.json'],'regressions':['reports/regressions.json'],'baseline':['checksums.json']}
 for p in sorted(old.glob('*.json')):
  v=json.loads(p.read_text())
  if not isinstance(v,dict):continue
  for f in v.get('findings',[]):
   ident=f['id'];status=f['status'];action='R2-PASS gilt nur für seine ursprüngliche Quelle; aktuelle R3-Prüfung ist getrennt verlinkt.'
   if status!='PASS':
    if ident.startswith('M-SCREW_B1'):action='Sacklöcher nach allen Trägervereinigungen geschnitten;4M3x8 mit freier Spitze inR3 erneut geometrisch gemessen.'
    elif ident.startswith('M-SCREW_BUCK'):action='M2.5 entfallen;4 vorhandeneM3x8 halten einen Randrahmen. Kaufteillöcher ungenutzt. Sackloch/Spitze inR3 gemessen.'
    elif ident in['FW-DRIFT','E-RATIOMETRIC-MONITOR']:action='Istversorgung aus kalibrierter Bandgap statt gespeichertem5V-Wert; DAC und Rückmessung kompensieren separat. Versorgungseinbruch und physisch falscher Ausgang getrennt getestet.'
    elif ident in['FW-STUCK','E-STUCK-RELAY']:action='Zwei serielle Spulentreiber: D4 Freigabe und D5-Wechselimpulse. Statische Pegel/Programmstillstand diagnostisch geprüft; reale Zeit/verschweißter Kontakt bleiben offen.'
    elif ident=='E-BACKFEED':action='Rohsignal über LM358-Puffer, Speisung durch1N4148 gegen Rückfluss getrennt. Diagnosemodell und Herstellerprinzip, tatsächlicher Bauteiltyp/12V-Versuch noch offen.'
    elif ident=='E-COMMON-MODE':action='3:1 statt2:1 Eingangsteiler, gemessene Versorgung und Laufzeitgrenzen; Rechenprüfung mit4.6V und5.5V bestätigt Abstand.'
    elif ident.startswith('M-RETENTION-'):action='Steckerhalter jetzt mit zwei zusätzlichenM3-Schrauben am festen Träger verankert. Z-Fanggeometrie und vorgesehener10mm-Ausziehweg plus Abheben bestehen; reale Stecker-/Spannkraft bleibt ungemessen.'
    elif ident=='M-CLAMP-PRELOAD':action='Stecker-Spannschrauben und Kabelsättel erhalten0.5mm Stellweg vor Kopf-/Hartanschlag. Geometrischer Stellweg gemessen; Reibung, Kompression und Anzugsmoment sind reale offene Größen.'
    elif ident=='M-WIRES':action=str(len(json.loads((OUT/'stripboard.json').read_text())['jumpers']))+' Brücken jetzt als tatsächliche kreisförmige3D-Sweeps in sechs Lagen einschließlich Rundbögen und lokal begrenzten Lötstellenkontakten geprüft. Lack-OD, Handformung und Lötlaschen am Bestand noch prüfen.'
    elif ident=='M-GLANDS':action='PG7 in fester Frontwand plus verschraubte Mantelsättel. Reale Gewindepaarung, Kabelmantelform/-maß, Zugkraft und Dichtwirkung sind nicht gemessen; keine Wasserdichtheit zugesichert.'
    elif ident.startswith('M-LOADPATH-'):action='R3 misst Schraubenauflage/Eingriff und baut den Haltegraph bis zum Grundträger aus tatsächlich geprüften Verbindungen auf. Materialfestigkeit und Vorspannung folgen daraus nicht.'
    elif ident=='M-MOTION-LIMITS':action='Positive Fanggeometrie und begrenzte Translationen/Kippwinkel sowie kontinuierliche Entnahmewege geprüft. Reale Gewindeflanken, elastische Verformung und beliebige gemeinsame6D-Bewegung bleiben nicht vollständig qualifiziert.'
    elif ident=='M-REAL':action='Digitale Passkörper sind geprüft. TatsächlicherPETG-Druck,50°C plus Eigenerwärmung, Schraubmoment, Vibration/Last und Einbauuntergrund fehlen als reale Messungen.'
    elif ident=='E-PURCHASE-BUSES':action='Bestandsrelais und nominelle Nano/DAC/Wandler-Pins im Verbindungsgraphen berücksichtigt. Echte Beschriftung, Durchgang und2N2222A-Beinfolge vor Bestromung vergleichen.'
    elif ident=='E-ERC-SCOPE':action='R3 verwendet echte KiCad-CLI-ERC mit passenden Pinarten und unabhängiger Netzliste. Versorgungstreiber werden nur aus dem expliziten Bordnetzweg abgeleitet; reale Modul-Innenschaltungen sind dadurch nicht geprüft.'
    elif ident=='E-LM358-MODEL':action='Herstellerbibliothek und offen dokumentierte Temperaturkompatibilitätsänderung scheitern schon an der Referenzprüfung. Kein bestandener Funktionsnachweis mit diesem Modell.'
    elif ident=='E-ACTIVE-LOOP':action='Geschlossener Analogkreis, Stabilität, Ausgang5Ω und Lastsprünge bleiben ohne qualifiziertes Modell oder Messung offen. Diagnostische Ideal-/Transistormodelle ersetzen das Oszilloskop nicht.'
    elif ident=='E-TOLERANCES':action='Bedingte1%/5%-Teilerecken, ADC/DAC-Schrittweiten und LM358-Offsetfolgen in error-budget.json berechnet. Tatsächliche Widerstandsklasse, Temperaturdrift, Bandgapkalibrierung und zulässiger Anzeigefehler fehlen.'
    elif ident=='E-POWER-REAL':action='Schutz- und Servicewege mit Bestandsbauteilen geführt. Bordnetz-Störimpulse/TVS-Energie, USB-Clone-Rückspeisung und verdeckte Masseunterbrechungen benötigen reale Prüfung.'
    elif ident=='FW-REAL':action='Hostfehlerfälle und beide Nano-Bootloader-Ziele übersetzt; reale Resetursache, Brownout-Sicherungsbits, ADC-Referenz und Kontakt-Abschaltzeit nicht am vorhandenen Nano gemessen.'
    else:action='R3 führt neue Nachweise und die verbleibende reale Eigenschaft ausdrücklich offen; keine Übernahme als allgemeine Funktionszusage.'
   ledger.append(dict(old_report=str(p.relative_to(a)),old_sha256=sha(p),id=ident,old_status=status,subject=f.get('subject'),disposition=action,current_evidence=groups.get(p.stem,[]),original_preserved=True))
 save(OUT/'r2-dispositions.json',dict(status='PASS_LEDGER_COMPLETE',source_audit=str(old),original_observations=len(ledger),findings=ledger))
 bound={str(p.relative_to(R)):sha(p)for folder in['src','inputs','firmware/YamahaTrimAdapter','firmware/tests','references']for p in(R/folder).rglob('*')if p.is_file()and '__pycache__'not in p.parts and p.suffix not in['.pyc','.nbc','.nbi']}
 versions=dict(python=sys.version,platform=platform.platform())
 for module in ['build123d','OCP','bd_warehouse']:
  try:versions[module]=__import__('importlib.metadata',fromlist=['version']).version(module)
  except Exception:pass
 for name,cmd in [('ngspice',['/opt/homebrew/bin/ngspice','--version']),('arduino',['/opt/homebrew/bin/arduino-cli','version']),('kicad',['/Volumes/production/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli','version']),('rsvg',['/opt/homebrew/bin/rsvg-convert','--version'])]:
  try:versions[name]=subprocess.run(cmd,capture_output=True,text=True,timeout=10).stdout.strip()[:1000]
  except Exception as ex:versions[name]=str(ex)
 save(R/'reports/source-binding.json',dict(revision=D['revision'],source_sha256=bound,tools=versions,compute_threads_max=2,windows_opened=False))
 summary=dict(revision=D['revision'],reports=reports,source_sha256=sha(R/'inputs/design.json'),R2_original_observations=len(ledger),delivery_allowed=True,physical_acceptance=False,Yamaha_acceptance=False)
 save(R/'reports/summary.json',summary)
 m=json.loads((OUT/'manifest.json').read_text());layout=json.loads((OUT/'stripboard.json').read_text());nfail=[p for p,v in reports.items()if v['status']=='FAIL'];main_failures=[p for p in nfail if not p.startswith('reports/spice/')];tool_failures=[p for p,v in reports.items()if v['run_status']=='FAIL']
 text=f'''# Trimmsensor R3 · Bau- und Prüfstand\n\nEigener Revisionsstand mit vorhandenem FBR211-12V-Relais. Die rosa Yamaha-Leitung wird vom gesamten nachbildenden Elektronikzweig einschließlich seiner Spannungsmessung getrennt, wenn das Relais stromlos ist. Originalgeber und gemeinsame Masse bleiben angeschlossen. Zwei getrennte Transistorwege benötigen Freigabe und regelmäßige Lebenszeichen aus dem geprüften Arduino-Hauptprogramm.\n\n- Gehäusekörper {M['width']}×{M['depth']}×{M['height']}mm; mit Laschen210mm breit. Eine135×65mm-Streifenrasterplatine aus vorhandenem145×65mm-Material. Wandler separat, überM3-Randrahmen gehalten.\n- {len(m['prints'])} Druckteile, {len(m['fasteners'])} M3-Schrauben, {len(layout['jumpers'])} obere Lackdrahtbrücken, {len(layout['cuts'])} Streifenunterbrechungen und8 vollständig kupferfreie Bohrbereiche. Drucklage und Einbaulage sind getrennt.\n- Haube von unten verschraubt; Schutzkante, USB-Verschluss und feste Kabelwand. Ohne zusätzliches Dichtmaterial und ohne Wasserdichtheitszusage.\n- Programm3.0 / Konfiguration3. Alte Kalibrierung wird verworfen. Zwei Endpunkte und optionale weitere Stützpunkte; kein fester124%-Wert. Standardmäßig bleibt der Ausgang ausgeschaltet.\n\nFAIL: Das ursprüngliche LM358-Herstellermodell und die protokollierte Kompatibilitätsanpassung scheitern bereits an der einfachen Referenzprüfung. Sie werden deshalb nicht für einen Funktionsnachweis verwendet. Weitere FAIL-Berichte: {', '.join(main_failures)if main_failures else 'keine in den ausgeführten Geometrie-, Netz- und Firmwareprüfungen'}. Unvollständige Werkzeugläufe: {', '.join(tool_failures)if tool_failures else 'keine'}. Der Prüfumfang ist unten begrenzt. OPEN ist kein bestandener Funktionsnachweis. Werkzeugausführung und Entwurfseigenschaft stehen getrennt in den JSON-Berichten.\n\n'''
 for i,s,st,e,n in items:text+=f'**{st} · {s}.** {e} {n}\n\n'
 text+='Die vollständige Zuordnung jedesR2-Befunds steht in `delivery/r2-dispositions.json`; Originale bleiben erhalten. Einzelheiten und Messwerte: `reports/summary.json`, `reports/functional/`, `delivery/error-budget.json`. Reale Druckpassung und Yamaha-Betrieb wurden nicht durchgeführt.\n\nAuslieferung erfolgt ausdrücklich auch mit offenen oder negativen Nachweisen. Daraus folgt keine behauptete Funktions-, Festigkeits- oder Wasserdichtheitsabnahme. Die automatische Fehlerabschaltung im Gerät bleibt aktiv.\n'
 (R/'FEHLERBERICHT.md').write_text(text)
 print('REPORT',len(ledger),'R2 observations mapped;',len(reports),'current reports; FAIL',nfail)
if __name__=='__main__':main()

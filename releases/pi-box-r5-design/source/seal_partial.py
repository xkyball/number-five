"""Seal a strictly scoped adapter sub-kit; never mark complete interior delivery."""
import json,hashlib,zipfile,shutil,datetime,fcntl
from pathlib import Path
from common import ROOT,BASE,read,save,sha
def main():
 # Functional audit gate precedes all writes; old packages remain historical.
 import importlib.util
 gatepath=BASE/'functional_release_gate.py'
 spec=importlib.util.spec_from_file_location('pi_functional_gate',gatepath)
 gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
 gate.require_current_package()
 out=ROOT/'output';delivery=ROOT/'delivery';bed=ROOT/'print-adapters';p=read(bed/'preparation.json')
 assert p['digital']in ['PASS_SCOPED_ADAPTER_SUBKIT_PIXELS_PENDING','PASS_SCOPED_ADAPTER_SUBKIT']
 assert p['source_design']['sha256']==sha(ROOT/'design.json')
 assert read(out/'adapter-independent-check.json')['source_sha256']==sha(ROOT/'design.json')
 assert read(out/'final-export-check.json')['status']=='PASS_SCOPED_PRINT_SUBKIT'
 assert read(out/'toolpath-independent-check.json')['status']=='PASS_SCOPED_30_CHANNELS'
 assert read(out/'inventory-update.json')['status']=='PASS_KNOWN_ADAPTER_DEMAND_RESERVED'
 # These exact images were inspected internally after their last generation.
 for path in [bed/'native-preview.png',bed/'toolpaths.png',bed/'first-layers.png',out/'pdf-review-1.png',out/'pdf-review-2.png',delivery/'ansichten/Adapter-Details.png']:assert path.is_file()
 p['digital']='PASS_SCOPED_ADAPTER_SUBKIT';p['verification']['pixel_review']='PASS';p['verification']['pixel_review_scope']='Actual fifteen-body native preview, all12 sampled G-code layers, four first-layer images, final adapter detail triangles and both actual PDF-rendered pages inspected internally; no result opening.'
 for file in ['adapter-independent-check.json','drawing-check.json','render-binding.json']:
  r=read(out/file);r['pixel_review']='PASS_SCOPED_ADAPTER_SUBKIT';save(out/file,r)
 p['source_check']['sha256']=sha(out/'adapter-independent-check.json');save(bed/'preparation.json',p)
 checks=delivery/'pruefung';checks.mkdir(exist_ok=True)
 for file in ['adapter-check.json','adapter-independent-check.json','final-export-check.json','contact-delta-check.json','toolpath-independent-check.json','drawing-check.json','render-binding.json','inventory-update.json','inventory-attachments.json','connector-fit.json','connector-fit-relaxed.json']:
  if(out/file).exists():shutil.copyfile(out/file,checks/file)
 # Keep intermediate single-page PDF output out of final delivery.
 for f in(delivery/'zeichnungen').glob('Adapter-*.pdf'):
  dest=out/(f.stem+'-previous-intermediate.pdf');shutil.move(str(f),dest)
 baseline=read(ROOT/'inputs/preserved-baselines.json');changed=[r['path']for r in baseline if sha(BASE/r['path'])!=r['sha256']];assert not changed,changed
 assert all(sha(BASE/r['path'])==r['sha256']for r in read(ROOT/'inputs/referenced-source-pins.json'))
 save(delivery/'daten/offene-punkte.json',dict(revision='PI-2L-5',complete_interior=False,items=[
  dict(id='L4_CONNECTOR_LAYOUT',status='OPEN',scope='Nine module connector pairs, six stripboards, including two existing banks',evidence='pruefung/connector-fit.json and connector-fit-relaxed.json',finding='2mm-raster current-component conservative-envelope packing is INFEASIBLE. Joint component/connector placement over the whole layer returned UNKNOWN after120s; this is NOT a global impossibility proof. No exact smaller supplied-article profile is qualified.',next='Measure actual mated YC1004P width/depth/height including pins; bind geometry and repeat joint placement, retention and full unplug tests. Do not silently shrink DB89.'),
  dict(id='FUSE_CARRIERS',status='OPEN',scope='Both carriers including connector support',finding='Existing R2 carrier reference preserved; final connector integration pending. Not included in the ready adapter sub-kit.'),
  dict(id='CAN_PAIR',status='OPEN',scope='CAN_KEY_SHROUD and CAN_PLUG_COLLAR',finding='Existing R2 candidate exports retained; supplied connector/retention fit not promoted to a new qualification and not included in this adapter bed.'),
  dict(id='USB_AND_HARNESS',status='OPEN',scope='Full bundle capacity, bending, extraction, intact USB reserve storage',finding='No invented qualified storage space. Final layout depends on connector seating.20 converter contact positions corrected;30 dependent route records explicitly invalidated, no L5 cut release.'),
  dict(id='REAL_ACCEPTANCE',status='OPEN_PHYSICAL',scope='Real converter fit, PETG screw hold/creep, stepped washer support, connector rating, electrical5A/3A and thermal operation',finding='Digital adapter/file checks do not grant real fit/strength or electrical operating acceptance.'),
  dict(id='LOWER_BOX_NUT_POCKETS',status='DEFERRED',scope='Original open shell fastener pockets',finding='Unchanged; enclosure excluded from current scope.')]))
 stand=f'''PI-2L-5 | Adapter-Teilpaket; vollständiger Innenaufbau OPEN

Fertig vorbereitet:5 gleiche Wandleradapter und10 gestufte Isolierscheiben.
Adapter:46,2×29×5,6mm, getrennte Schraubpaare; vorhandene Unterplatte unverändert.
Bauteilseite, IN links/OUT rechts: Wandlerlöcher links oben/rechts unten. Die DB-Zeichnung ist damit konsistent. R1 übernahm die Y-nach-unten-Lochwerte ohne Abbildung in den CAD-Montagerahmen; R5 trennt reale PCB-Draufsicht und vorhandene Stützen.50 Prüfpunkte am tatsächlichen R1-STL bestätigen dessen Befestigungsbezug.
Zusätzlich10 M3×8/10 M3-Muttern/10 Metallscheiben; zehn bisherige Schrauben weiterverwenden. Isolierscheiben vermeiden die im nominalen Modell erkannte Berührung einer blanken Metallscheibe mit einem Anschlusspad. Reale Kupfer-/Schraubenpassung bleibt zu prüfen.

Adapterbett:15 Körper, reservierte Kontur einschließlich Brim/Abstand154,8×68,8mm auf220×220.0,2mm,4Wände,20%Gyroid,PETG240/85°C, ohne Stützen. Slicer:{p['estimated_time']};{p['consumption']['slicer_g']}g;Planbudget{p['consumption']['planning_total_g']}g. Keine Druckerübertragung, kein Druckstart.
Geprüft:alle5Einbauplätze, Schrauben/Muttern/Scheiben und Unterseiten; falsche Diagonale/zu lange Schraube/blanke Scheibe als Fehlerfälle;STEP/STL/3MF-Rücklesen;30 tatsächliche G-Code-Kanäle und alle kleinen oberen Druckmerkmale. Zeichnung und Druckbahnen intern bildlich geprüft. Reale Passung, PETG-Halt und Wärme offen.

NICHT FERTIG:beide Sicherungsträger mit Steckeraufnahme, CAN-Aufnahme/Gegenhülse, übrige L4-Steckeraufnahmen und USB-/Kabelhalter. Größere Steckhülle und aktuelle Bauteilanordnung haben keine gemeinsame belegte Lösung; zusätzliche gemeinsame Optimierung blieb nach120s UNKNOWN. Das ist kein Unmöglichkeitsbeweis. Angefragte reale Steckmaße können die Hülle konkretisieren. Dieses Bett ist ausdrücklich kein vollständiges letztes Restbett.
Kontaktvertrag-L5-Entwurf:elektrischer Graph unverändert,20 Wandlerkontakte neu positioniert.30 abhängige Routeneinträge sind offen statt mit falschen Zuschnittlängen geliefert. Vorherige L4-Unterlagen bleiben als Vorgänger erhalten; daraus keine L5-Kabel zuschneiden.

DB:drei vollständige kumulative Modul26-Phasen um bekannten Adapterbedarf ergänzt und vorhandene Zusatzteile reserviert. PETG gesamt838g statt823g geplant/reserviert; keine Ausgabe, kein Verbrauch, keine Bestellung. Unbekannten Halter-/Kabelbedarf nicht erfunden.
Quellen:design.json,source/,rebuild.sh; vorhandene R1/R2/L4- und Kaufteilreferenzen plus lokale CAD-/QA-Runtimes erforderlich. Kein Fusion, keine GUI, kein Broker. Rebuild erzeugt ausschließlich den Adapter-Teilsatz, keine vollständige Innenbaugruppe. Quellenbindung:inputs/*pins.json und preserved-baselines.json.
'''
 (delivery/'STAND.txt').write_text(stand)
 previous=read(BASE/'active-design.json');saved=ROOT/'inputs/active-design-before-r5.json'
 if not saved.exists():save(saved,previous)
 save(BASE/'active-design.json',dict(revision='PI-2L-5',drawing_revision='L5_PARTIAL_ADAPTERS',source='two-level-r5/design.json',contact_source='two-level-r5/delivery/daten/kontaktvertrag-L5-Entwurf.json',contact_status='DRAFT_ROUTES_OPEN_NO_CUT_RELEASE',builder='two-level-r5/source/adapters.py',rebuild='two-level-r5/rebuild.sh',delivery='two-level-r5/delivery',print_preparation='two-level-r5/print-adapters/preparation.json',print_scope='5 adapters +10 insulating washers; complete rest kit OPEN',printed_reference_revision='PI-2L-2',printed_source='two-level-r2/design.json',printed_source_unchanged=True,previous_revision='two-level-r4/design.json',historical_baseline=previous.get('historical_baseline'),status='ADAPTER_SUBKIT_PASS_COMPLETE_INTERIOR_OPEN',digital_delivery_complete=False,physical_release=False))
 req=BASE/'ANFORDERUNGEN.txt';text=req.read_text()
 if not text.startswith('Aktuell: PI-2L-5'):req.write_text('Aktuell: PI-2L-5 | Adapter-Teilpaket PASS, vollständiger Innenaufbau OPEN\n5 Wandleradapter/10 Isolierscheiben mit geprüftem Druckbett; kein Druckstart. Stecker-/Sicherungsträger/CAN-/USB-Integration nicht abgeschlossen; reale YC100-Steckmaße angefragt. L5-Kontaktentwurf sperrt veraltete abhängige Kabelzuschnitte. R1/R2/L4 unverändert erhalten. Details:two-level-r5/delivery/STAND.txt.\n\nHistorischer Vorgänger:\n'+text)
 now=datetime.datetime.now().astimezone().isoformat(timespec='seconds');stages=[]
 for id,label,done,open_,failed in [
  ('preflight','Vorprüfung',['User top view bound; R1 printed-post/PCB coordinate frames separated; R2/L4 originals unchanged'],['Actual paired connector profiles, final fixed-layout integration'],[]),
  ('native_modules','Native Module',['2 printed adapter types;5 installed adapter checks;110-solid scoped STEP assembly and separate STEP/STL/3MF readbacks'],['All9 module connector retention/seating and additional carriers'],[]),
  ('assembly','Gesamtbaugruppe',['Five repaired converter mount positions pass against unchanged R1/R2 structure'],['Full connector/body/withdrawal/harness/USB storage and full extraction'],['Current-component conservative box packing infeasible in bounded raster model; joint placement UNKNOWN, not global failure proof']),
  ('checks','Prüfungen',['50 actual R1 mesh probes, fastener/copper regressions,30 sliced screw/washer channels','15-body bed profile, signature, actual toolpaths;2-page PDF and3D triangles internally pixel-reviewed'],['Full9-interface and full L5 cable acceptance; real print/fit/heat/current'],[]),
  ('delivery','Auslieferung',['Adapter sub-kit sources/CAD/STL/3MF/Gcode/PDF and known DB demand reserved'],['Complete final rest bed, integrated fuse carriers/CAN/connector/USB holders and updated7 electrical PDFs'],[])
 ]:stages.append(dict(id=id,label=label,planned_scope='Five adapters + four remaining bodies + complete L4 interfaces/cable holders; enclosure excluded',completed_packages=done,open_packages=open_,failed_packages=failed,status='OPEN_WITH_SCOPED_PASSES',completed=False,counts=dict(pass_count=len(done),open=len(open_),fail=len(failed))))
 record=dict(project='pi-box',variant='PI-2L-5',label='Pi-Box | Restdruck, geprüfter Adapter-Teilsatz',observed_at=now,active_source_sha256=sha(ROOT/'design.json'),contact_sha256=sha(delivery/'daten/kontaktvertrag-L5-Entwurf.json'),digital_delivery_complete=False,completed_delivery=False,delivery_scope='Adapter sub-kit only; complete interior requested but connector interfaces not qualified',stages=stages,units=dict(ready_print_types=2,ready_print_bodies=15,converter_mounts_checked=5,module_connector_pairs_pending=9,changed_contacts=20,dependent_route_records_open=30,known_additional_original_bodies=4),evidence=str(out/'final-export-check.json'),next_step='Resolve supplied YC100 pair dimensions, finish conservative-body joint layout/retention/unplug and USB harness storage, then prepare complete rest bed.')
 progressfile=BASE.parents[1]/'evidence/project-progress-current.json'
 with progressfile.with_suffix('.lock').open('a+')as lock:
  fcntl.flock(lock,fcntl.LOCK_EX);progress=read(progressfile);prior=progress.get('pi_box_two_level_current')
  if prior and prior.get('variant')!='PI-2L-5':progress.setdefault('pi_box_two_level_history',[]).append(prior)
  progress['pi_box_two_level_current']=record;progress['chat_progress']['pi_box_restprint_L5']=record;tmp=progressfile.with_suffix('.pi-l5.tmp');save(tmp,progress);tmp.replace(progressfile)
 if not(ROOT/'inputs/progress-before-r5.json').exists():shutil.copyfile(BASE/'progress-current.json',ROOT/'inputs/progress-before-r5.json')
 save(BASE/'progress-current.json',record)
 # Bind everything needed to inspect the partial delivery; no recursive package.
 manifest=dict(revision='PI-2L-5',status='ADAPTER_SUBKIT_PASS_COMPLETE_INTERIOR_OPEN',complete_interior=False,physical_release=False,baseline_files_preserved=len(baseline),files={})
 for folder in [delivery,ROOT/'source',ROOT/'inputs',bed]:
  for f in sorted(folder.rglob('*')):
   if f.is_file()and'__pycache__'not in f.parts and f.name!='liefermanifest.json':manifest['files'][str(f.relative_to(ROOT))]=sha(f)
 for n in ['design.json','rebuild.sh']:manifest['files'][n]=sha(ROOT/n)
 save(delivery/'liefermanifest.json',manifest);package=ROOT/'Pi-Box-PI-2L-5-Adapter-Teilpaket.zip'
 with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED,compresslevel=6)as z:
  for n in manifest['files']:z.write(ROOT/n,n)
  z.write(delivery/'liefermanifest.json','delivery/liefermanifest.json')
 with zipfile.ZipFile(package)as z:
  assert z.testzip()is None
  for n,h in manifest['files'].items():assert hashlib.sha256(z.read(n)).hexdigest()==h
 save(out/'release.json',dict(revision='PI-2L-5',status=manifest['status'],package=str(package),sha256=sha(package),bytes=package.stat().st_size,bound_files=len(manifest['files']),complete_interior=False,physical_release=False,printer_transport='NOT_SENT',printer_start='NOT_REQUESTED'))
 print('SEALED PARTIAL',len(manifest['files']),'files',package.stat().st_size,'bytes; full interior OPEN')
if __name__=='__main__':main()

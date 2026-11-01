"""Run only functional checks. Never rebuild CAD, open windows or auto-correct it."""
import argparse, copy, hashlib, json, os, shutil, subprocess, sys, zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from common import ROOT, OUT, TOOLS, read, save, sha
from plausibility_common import AUDIT, CONTRACT, bindings, current, finding, report, overall
from electronics import circuit
from verify import reconstruct

CAD_PY=Path(os.environ.get('V15_CAD_PY','/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/cad-comparison-20261024-b123-current/bin/python'))

def release_guard():
    p=AUDIT/'summary.json'
    if not p.exists():return ['FUNCTIONAL_AUDIT_MISSING']
    q=read(p);errors=[]
    if not current(q):errors.append('FUNCTIONAL_AUDIT_STALE')
    if q.get('execution_status')!='PASS':errors.append('FUNCTIONAL_AUDIT_EXECUTION_FAILED')
    if q.get('status')!='PASS':errors.append('FUNCTIONAL_AUDIT_'+q.get('status','OPEN'))
    for rel,digest in q.get('evidence_binding',{}).items():
        p=ROOT/rel
        if not p.is_file() or sha(p)!=digest:errors.append('FUNCTIONAL_EVIDENCE_CHANGED:'+rel)
    return errors

def connectivity():
    c=read(OUT/'data/electronics.json');expected={}
    for p in circuit()['parts']:
        for pin,net in p['pins'].items():expected[p['ref']+'.'+pin]=net if net is not None and not p.get('dnp') else 'NC_'+p['ref']+'_'+pin
    errors=reconstruct(c,expected);cases=[]
    for kind in ['cuts','bridges']:
        broken=copy.deepcopy(c);broken[kind]=[];e=reconstruct(broken,expected)
        cases.append(dict(case='omit_'+kind,caught=any(x.startswith('SHORT:' if kind=='cuts' else 'OPEN:') for x in e)))
    rows=[finding('PHYSICAL_COPPER','FAIL' if errors else 'PASS','all stripboard pins','Actual copper/cuts/bridges implement the intended nets',dict(errors=errors,pins=len(expected),negative_tests=cases),'Kupferverbund und Sollnetze stimmen im geprüften Umfang.' if not errors else 'Aufbau enthält eine Unterbrechung oder einen Kurzschluss.')]
    if not all(x['caught'] for x in cases):raise RuntimeError('Copper negative test failed')
    save(AUDIT/'connectivity.json',report('connectivity',rows))

def write_text(q):
    lines=['# Venterior V15 · elektrische und mechanische Funktionsprüfung','',
           f"Stand: {q['observed_at']} · Ergebnis **{q['status']}** · Prüflauf **{q['execution_status']}**.",'',
           f"Untersuchte Modellrevision: {q['model_revision']}. Der Prüflauf selbst verändert keine Geometrie. Historische Fehlerstände bleiben erhalten. Dieser Bericht ersetzt keine reale Pass-, Festigkeits- oder Betriebsprüfung.",'',
           f"Ergebnisse: {q['counts'].get('PASS',0)} PASS, {q['counts'].get('FAIL',0)} FAIL, {q['counts'].get('OPEN',0)} OPEN. Das sind Prüfanforderungen unterschiedlicher Größe, kein Fertigstellungsgrad.",'']
    resolved=q['baseline_failure_resolution']
    lines += [f"Von den {len(resolved)} ursprünglichen Fehlern sind {sum(s=='PASS'for s in resolved.values())} durch aktuelle nominale Geometrienachweise behoben. Elektronik, Platinen-/Modulrahmen sowie bisherige Schraubenachsen und -längen werden gegen den gesicherten Vorgänger geprüft.",'']
    for state,label in [('FAIL','Konkrete Fehler'),('OPEN','Offene Nachweise'),('PASS','Nachgewiesener Prüfumfang')]:
        lines += ['## '+label,'']
        for r in q['findings']:
            if r['status']!=state:continue
            detail=''
            e=r['evidence']
            if r['id'].startswith('FASTENER_'):
                detail=f" Eingriff {e['usable_engagement_mm']:g} mm"
                if e['required_engagement_mm'] is not None:detail+=f" / Vorgabe {e['required_engagement_mm']:g} mm"
                if e['pilot_tip_clearance_mm'] is not None:detail+=f"; Restweg bis Bohrungsende {e['pilot_tip_clearance_mm']:g} mm"
                detail+='.'
            if r['id'].startswith('RETENTION_'):
                detail=' Kollisionsfreie geprüfte Wege: '+str([x['vector_mm'] for x in e['paths'] if x['escape_proved']])+'.'
            if r['id']=='USB_CABLE_GRIP':detail=f" Modellbohrung {e['model_bore_mm']} mm, angenommenes Kabel {e['assumed_cable_od_mm']:g} mm."
            lines += [f"- **{r['subject']}** (`{r['id']}`): {r['consequence']}{detail}"]
        lines.append('')
    lines += ['## Wiederholen','', '`./check-functions.sh` führt nur diese Prüfungen ohne sichtbare App aus. Exitcode 1 bedeutet FAIL, 2 bedeutet OPEN. Mit `--allow-findings` bleibt der Ergebnisstatus erhalten, der Prozess endet nach technisch erfolgreicher Prüfung mit 0.','',
              'Details und Messwerte stehen in summary.json, mechanical.json, electrical.json sowie den KiCad-/ngspice-Unterordnern. Quellen und Ergebnisdateien sind mit SHA-256 gebunden.','']
    (AUDIT/'BERICHT.md').write_text('\n'.join(lines))

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--allow-findings',action='store_true');parser.add_argument('--skip-regression-tests',action='store_true');args=parser.parse_args()
    old=AUDIT/'summary.json'
    if old.exists():
        destination=ROOT/'evidence/functional-audit-history'/sha(old)[:16]
        if not destination.exists():destination.parent.mkdir(parents=True,exist_ok=True);shutil.copytree(AUDIT,destination)
    AUDIT.mkdir(parents=True,exist_ok=True);before=bindings();extra=[];execution=[]
    # Existing exports must still be attached to the exact unchanged CAD source.
    source=read(OUT/'data/build.json')['source_hashes'];stale=[p for p,h in source.items() if not (ROOT/p).exists() or sha(ROOT/p)!=h]
    if stale:raise RuntimeError('CAD export/source binding stale: '+','.join(stale))
    connectivity()
    commands=[]
    if not args.skip_regression_tests:commands.append(('regressions',[str(CAD_PY),'-m','unittest','discover','-s','tests','-p','test_plausibility.py','-v']))
    commands += [('mechanical',[str(CAD_PY),'src/mechanical_function.py']),('electrical',[sys.executable,'src/electrical_function.py'])]
    env=dict(os.environ,OMP_NUM_THREADS='1',OPENBLAS_NUM_THREADS='1',MKL_NUM_THREADS='1')
    for name,command in commands:
        # Never consume a stale report after a crashed checker.
        if name in ('mechanical','electrical'):(AUDIT/(name+'.json')).unlink(missing_ok=True)
        try:r=subprocess.run(command,cwd=ROOT,env=env,capture_output=True,text=True,timeout=240)
        except subprocess.TimeoutExpired as ex:
            execution.append(dict(step=name,status='FAIL',reason='240s timeout'));continue
        (AUDIT/(name+'.log')).write_text(r.stdout+r.stderr)
        execution.append(dict(step=name,status='PASS' if r.returncode==0 else 'FAIL',returncode=r.returncode))
        print(name,execution[-1]['status'],r.stdout.strip()[-500:],flush=True)
    rows=[]
    for name in ('connectivity','mechanical','electrical'):
        p=AUDIT/(name+'.json')
        if p.exists():rows += read(p)['findings']
        else:rows.append(finding('CHECKER_'+name,'OPEN',name,'Checker completes with current evidence',{},'Prüfer konnte kein aktuelles Ergebnis liefern; Logdatei prüfen.'))
    baseline=read(ROOT/'inputs/plausibility/baseline-v15-1.json');assembly=read(OUT/'data/assembly.json');parameters=read(ROOT/'parameters.json');new_fast={f['id']:f for f in assembly['fasteners']}
    invariant_errors=[]
    if sha(OUT/'data/electronics.json')!=baseline['electrical_sha256']:invariant_errors.append('ELECTRONICS_CHANGED')
    for key in ('board','converter','front'):
        if parameters[key]!=baseline[key]:invariant_errors.append(key+'_FRAME_CHANGED')
    for f in baseline['fasteners']:
        if any(new_fast.get(f['id'],{}).get(k)!=f[k]for k in('axis','direction','length')):invariant_errors.append('FASTENER_CHANGED:'+f['id'])
    rows.append(finding('REVISION_INVARIANTS','FAIL'if invariant_errors else'PASS','V15-1 to V15-2','Electrical design, purchased-module positions and original screw axes/lengths remain unchanged',invariant_errors,'Elektronik, Modulrahmen und bisherige Schraubenachsen/-längen sind unverändert.'if not invariant_errors else'Nicht beauftragte Änderung erkannt.'))
    resolved={id:next((r['status']for r in rows if r['id']==id),'MISSING')for id in baseline['failed_ids']}
    if args.skip_regression_tests:rows.append(finding('REGRESSIONS_SKIPPED','OPEN','regressions','Functional regressions pass',{},'Regressionen wurden ausdrücklich ausgelassen.'))
    if before!=bindings():execution.append(dict(step='source_stability',status='FAIL',reason='Bound inputs changed during audit'))
    q=report('complete',rows,observed_at=datetime.now(timezone.utc).isoformat(),source_revision=read(CONTRACT)['revision'],source_binding=before,execution=execution,
             model_revision=parameters['revision'],baseline_failure_resolution=resolved,
             geometry_changed_from_baseline=sha(ROOT/'src/model.py')!=baseline['model_sha256'],audit_mutates_geometry=False,manufacturing_release=False,operation_release=False,
             scope='V15 functional requirements on the revised exported geometry; audit itself does not modify geometry, actual hardware, printer or firmware')
    q['execution_status']='PASS' if all(r['status']=='PASS' for r in execution) else 'FAIL'
    if q['execution_status']=='FAIL':q['status']='FAIL'
    q['evidence_binding']={str(p.relative_to(ROOT)):sha(p) for p in AUDIT.rglob('*') if p.is_file() and p.name not in ('summary.json','BERICHT.md','supplement-manifest.json')}
    save(AUDIT/'summary.json',q);write_text(q)
    # Separate supplement preserves the original V15 package and original proofs.
    files=[p for p in AUDIT.rglob('*') if p.is_file() and p.name!='supplement-manifest.json']+[p for p in (ROOT/'inputs/plausibility').rglob('*') if p.is_file()]
    files += [p for p in (ROOT/'src').glob('*.py')]+list((ROOT/'tests').glob('test_plausibility*.py'))+[ROOT/'check-functions.sh',ROOT/'README.md',ROOT/'parameters.json']
    manifest={str(p.relative_to(ROOT)):sha(p) for p in files};save(AUDIT/'supplement-manifest.json',manifest)
    dest=ROOT/(parameters['revision']+'-Funktionspruefung.zip')
    with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(set(files+[AUDIT/'supplement-manifest.json'])):z.write(p,p.relative_to(ROOT))
    with zipfile.ZipFile(dest) as z:
        assert z.testzip() is None
        assert all(hashlib.sha256(z.read(p)).hexdigest()==h for p,h in manifest.items())
    print('Functional audit',q['status'],q['counts'],'execution',q['execution_status'],flush=True)
    if args.allow_findings and q['execution_status']=='PASS':return 0
    return {'PASS':0,'FAIL':1,'OPEN':2}[q['status']]

if __name__=='__main__':raise SystemExit(main())

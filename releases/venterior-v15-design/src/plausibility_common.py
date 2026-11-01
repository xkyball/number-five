"""Scoped functional findings. A completed audit can truthfully report FAIL."""
from collections import Counter
from pathlib import Path
from common import ROOT, OUT, read, save, sha

AUDIT = OUT / 'checks/plausibility'
CONTRACT = ROOT / 'inputs/plausibility/contract.json'

def finding(id, status, subject, requirement, evidence, consequence, **extra):
    if status not in ('PASS', 'FAIL', 'OPEN'):
        raise ValueError(status)
    return dict(id=id,status=status,subject=subject,requirement=requirement,
                evidence=evidence,consequence=consequence,**extra)

def overall(rows):
    states={r['status'] for r in rows}
    return 'FAIL' if 'FAIL' in states else 'OPEN' if 'OPEN' in states else 'PASS'

def report(area, rows, **extra):
    return dict(schema='iot.venterior-functional-audit/1',area=area,
                execution_status='PASS',status=overall(rows),
                counts=dict(Counter(r['status'] for r in rows)),findings=rows,
                physical_acceptance=False,**extra)

def bindings():
    paths=[ROOT/'parameters.json',CONTRACT,OUT/'data/assembly.json',
           OUT/'data/electronics.json',OUT/'data/build.json',
           OUT/'assemblies/Venterior-V15-closed.step',ROOT/'check-functions.sh',ROOT/'rebuild.sh']
    paths += sorted((ROOT/'src').glob('*.py'))
    paths += sorted((ROOT/'tests').glob('test_plausibility*.py'))
    paths += sorted((ROOT/'dependencies').glob('*.lock.txt'))
    paths += sorted((ROOT/'inputs/plausibility').rglob('*'))
    paths += sorted((OUT/'brep').glob('*.brep'))
    result={str(p.relative_to(ROOT)):sha(p) for p in paths if p.is_file()}
    for name in ('native_tools.py','spice_batch.py'):
        p=ROOT.parents[2]/'tools/native-tools'/name
        result[str(p)]=sha(p)
    return result

def current(report):
    return report.get('source_binding')==bindings()

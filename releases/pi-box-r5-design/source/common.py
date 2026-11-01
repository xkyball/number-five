"""PI-2L-5, millimetres, offline only; preserved predecessors are read-only."""
import hashlib,json,os
from pathlib import Path
for k in ['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']:os.environ[k]='1'
ROOT=Path(__file__).resolve().parents[1];BASE=ROOT.parent
CAD_PY='/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/cad-comparison-20261024-b123-current/bin/python'
PLAN_PY='/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/bin/python'
def read(p):return json.loads(Path(p).read_text())
def save(p,d):
 p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def baseline_rows():
 d=read(ROOT/'inputs/l4-mechanical-baseline.json')
 for r in d['rows']:
  p=Path(r['brep']['path']);r['brep']['path']=str(p if p.is_absolute()else BASE/'two-level-r2'/p)
 return d

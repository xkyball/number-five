"""Portable full nominal geometry source path;does not print,call Fusion or book stock."""
import copy,json,os,sys
from pathlib import Path
for key in ['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']:os.environ[key]='1'
r=Path(__file__).resolve().parent;sys.path[:0]=[str(r/'source'),str(r/'support')]
import common
common.BASE=r/'compat'
references=json.loads((r/'required-inherited-geometry.json').read_text())['members'];by={x['id']:x for x in references}
def baseline_rows():
 d=common.read(r/'inputs/l4-mechanical-baseline.json')
 for row in d['rows']:
  x=by[row['id']];p=r/x['path'];assert common.sha(p)==x['sha256'];row['brep']['path']=str(p)
 return d
common.baseline_rows=baseline_rows
for name in ['output/brep','delivery/cad','delivery/print','delivery/daten']:(r/name).mkdir(parents=True,exist_ok=True)
(r/'delivery/daten/kontaktvertrag.json').write_bytes((r/'inputs/current-contact-contract.json').read_bytes())
from OCP.OSD import OSD_ThreadPool
OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
import adapters,export_adapters,build_rest,export_rest
for module in [adapters,export_adapters,build_rest,export_rest]:
 print('Source stage:',module.__name__,flush=True);module.main()
print('Nominal geometry export complete;known source/current print faults and real acceptance remain separate.')

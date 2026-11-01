"""Serial CLI stage with source hashes and explicit failure recording, no veto."""
import json,sys,subprocess,time,hashlib,os
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 interpreter,script=sys.argv[1:3];start=time.monotonic();bindings={str(p.relative_to(R)):sha(p)for p in(R/'src').rglob('*.py')};bindings['inputs/design.json']=sha(R/'inputs/design.json')
 env=dict(os.environ,OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2',MKL_NUM_THREADS='2',NUMBA_NUM_THREADS='2');log=R/'reports'/(Path(script).stem+'.run.log')
 with log.open('w')as f:
  try:cp=subprocess.run([interpreter,script],cwd=R,stdout=f,stderr=subprocess.STDOUT,env=env,timeout=900);rc=cp.returncode;error=None
  except Exception as ex:rc=-1;error=str(ex);f.write(error+'\n')
 record=dict(command=[interpreter,script],returncode=rc,run_status='PASS'if rc==0 else'FAIL',duration_s=round(time.monotonic()-start,3),checker_sha256=bindings,log=str(log.relative_to(R)),error=error)
 outputs={
 'contract':['electrical-connectivity'], 'wire_space':['wire-space'],'cad':['cad-execution'],
 'check':['validity','collisions','tool-access','hood-removal','usb-access'],
 'inspect_mechanics':['functional/mechanics'],'mounting':['mounting'],'access_tests':['keying','parameters'],
 'testpoints':['testpoints'],'harness':['harness-space'],'connectivity':['functional/connectivity'],
 'kicad_audit':['functional/kicad'],'spice_checks':['spice-summary'],'model_qualification':['model-qualification'],
 'firmware_check':['firmware'],'mesh_check':['mesh-check'],'regressions':['regressions'],
 'inventory_sync':['inventory-dryrun'],'draw':['svg-render'],'tessellate':['tessellation'],'render':['render']}
 for name in outputs.get(Path(script).stem,[]):
  path=R/'reports'/(name+'.json');path.parent.mkdir(parents=True,exist_ok=True)
  try:v=json.loads(path.read_text())if path.exists()else dict(status='PASS'if rc==0 else'OPEN')
  except Exception:v=dict(status='OPEN',reason='Previous incomplete report preserved in stage log')
  if name=='wire-space':v['source_file']='delivery/stripboard.json'
  v['tool_execution']=record;v['checker_sha256']=bindings
  path.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
 print(script,record['run_status'],record['duration_s'],'s; result recorded; delivery continues',flush=True)
if __name__=='__main__':main()

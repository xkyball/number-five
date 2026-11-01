import subprocess,json,re,os
from contract import R,save,sha
F=R/'firmware';env=dict(os.environ,OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2')
r=subprocess.run(['sh',str(F/'tests/run.sh')],capture_output=True,text=True,timeout=30,env=env);assert r.returncode==0,r.stdout+r.stderr
records=[]
for cpu in ['atmega328','atmega328old']:
 cmd=[os.environ.get('TRIM_ARDUINO_CLI','/opt/homebrew/bin/arduino-cli'),'compile','--jobs','1','--fqbn','arduino:avr:nano:cpu='+cpu,'--warnings','all','--output-dir',str(F/'build'/cpu),str(F/'YamahaTrimAdapter')]
 p=subprocess.run(cmd,capture_output=True,text=True,timeout=120,env=env);(R/'reports'/('firmware-'+cpu+'.log')).write_text(p.stdout+p.stderr);records.append(dict(cpu=cpu,status='PASS'if p.returncode==0 else'FAIL',returncode=p.returncode,command=cmd,output=p.stdout,pin_contract_sha256=sha(F/'YamahaTrimAdapter/PinContract.h')))
result=dict(status='PASS'if all(x['status']=='PASS'for x in records)else'FAIL',host_tests=dict(status='PASS',output=r.stdout),builds=records,source_sha256={str(p.relative_to(R)):sha(p)for p in F.rglob('*')if p.is_file()and 'build'not in p.parts},physical_Nano='OPEN: actual clone/bootloader, watchdog-reset recovery, supply guard calibration and real output switching not exercised; no upload performed')
save(R/'reports/firmware.json',result);print('FIRMWARE',result['status'],r.stdout.strip())

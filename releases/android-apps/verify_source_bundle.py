"""Source-only check; deliberately no Gradle,device,chart or network action."""
from pathlib import Path
import hashlib,json,sys
r=Path(__file__).resolve().parent;m=json.loads((r/'manifest.json').read_text());bad=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
for row in m['files']:
 p=r/row['path']
 if not p.is_file()or sha(p)!=row['sha256']:bad.append(row['path'])
pin=json.loads((r/'gateway-config/lumina-wire.json').read_text())
assert sha(r/'gateway-config/pinned/KnobLeaseProtocol.java')==pin['sha256']
eq=json.loads((r/'shared-eq/MANIFEST.json').read_text())
assert sha(r/'tools/build_shared_eq.py')==eq['builderSha256']
assert sha(r/'shared-eq/jni.c')==eq['jniSha256']
for p,h in eq['sourceSha256'].items():assert sha(r/'shared-eq/core'/p)==h,p
up=json.loads((r/'map/docs/SOURCE_PROVENANCE.json').read_text())['OpenCPN'];third=json.loads((r/'map/chart-engine/third_party/PROVENANCE.json').read_text())
for row in up['files']+third:
 p=r/'map'/row['local_path']
 if not p.is_file()or sha(p)!=row['sha256']:bad.append('upstream:'+row['local_path'])
print(json.dumps({'files':len(m['files']),'pinnedOpenCPN':len(up['files']),'thirdParty':len(third),'LuminaWire':True,'sharedEQ':True,'chartFilesChecked':0,'AndroidBuildPerformed':False,'failures':bad},indent=2))
sys.exit(bool(bad))

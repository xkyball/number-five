"""Only the two named current recipes; no substituted whole-assembly references."""
import json,os,sys
from pathlib import Path
for key in ['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']:os.environ[key]='1'
root=Path(__file__).resolve().parent;sys.path[:0]=[str(root/'source'),str(root/'support')]
import build123d as b
from OCP.OSD import OSD_ThreadPool
OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
from build_rest import riser
from adapters import adapter
design=json.loads((root/'design.json').read_text());out=root/'generated';out.mkdir(exist_ok=True)
for name,shape in [('STACK_RISER',riser()),('CONVERTER_ADAPTER',adapter(design))]:
 b.export_step(shape,out/(name+'.step'),unit=b.Unit.MM);b.export_stl(shape,out/(name+'.stl'),tolerance=.04,angular_tolerance=.15)
print(json.dumps({'scope':'two exact parametric examples only','parts':2,'units':'mm','full_assembly':False,'real_acceptance':False}))

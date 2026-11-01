"""Portable nominal geometry export from unchanged current model.py."""
import json,os,sys
from pathlib import Path
for key in ['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']:os.environ[key]='1'
root=Path(__file__).resolve().parent;sys.path.insert(0,str(root/'src'))
import common
common.OUT=root/'generated';(common.OUT/'data').mkdir(parents=True,exist_ok=True)
(common.OUT/'data/electronics.json').write_bytes((root/'inputs/model-parts.json').read_bytes())
import build123d as b
from OCP.OSD import OSD_ThreadPool
OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
import model
parts,meta=model.make()
for view in ['closed','open','exploded']:b.export_step(model.assembly(parts,meta,view),common.OUT/(view+'.step'),unit=b.Unit.MM)
(common.OUT/'prints').mkdir(exist_ok=True)
for item in meta['parts']:
 if item['kind']!='print':continue
 shape=parts[item['id']];shape=b.Rot(*item.get('print_rotation',[0,0,0]))*shape;bounds=shape.bounding_box();shape=b.Pos(*[-v for v in bounds.min])*shape
 b.export_step(shape,common.OUT/'prints'/(item['id']+'.step'),unit=b.Unit.MM)
 b.export_stl(shape,common.OUT/'prints'/(item['id']+'.stl'),tolerance=.04,angular_tolerance=.15)
(common.OUT/'nominal-model.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps({'members':len(parts),'print_types':sum(x['kind']=='print'for x in meta['parts']),'units':'mm','real_acceptance':False,'directory':str(common.OUT)}))

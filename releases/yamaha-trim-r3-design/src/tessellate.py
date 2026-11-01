import json
import build123d as b
import numpy as np
from contract import OUT,R,sha,save
from cad import view_offset
m=json.loads((OUT/'manifest.json').read_text());vv=[];ff=[];ids=[];owners=[];offset=0
for i,row in enumerate(m['rows']):
 p=OUT/'cad-cache'/(row['id']+'.brep');assert sha(p)==row['brep_sha256'];s=b.import_brep(p);v,f=s.tessellate(.15,.22);vv.extend([tuple(q)for q in v]);ff.extend([[x+offset for x in t]for t in f]);ids.extend([i]*len(f));owners.extend([i]*len(v));offset+=len(v)
np.savez_compressed(OUT/'cad-cache/render-mesh.npz',vertices=np.array(vv,np.float32),faces=np.array(ff,np.int32),ids=np.array(ids,np.int16),owner=np.array(owners,np.int16),**{'offsets_'+view:np.array([view_offset(row,view,m['parameters'])for row in m['rows']],np.float32)for view in ['open','closed','exploded']})
print('Current triangles',len(ff))

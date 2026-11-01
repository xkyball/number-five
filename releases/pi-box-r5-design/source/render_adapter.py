"""Actual export triangles, Agg only; no GUI/preview opening."""
import numpy as np,trimesh,matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from mpl_toolkits.mplot3d.art3d import Poly3DCollection
from common import ROOT,save,sha
def main():
 fig=plt.figure(figsize=(14,5),dpi=150);sources=[]
 for i,(name,elev,title)in enumerate([('CONVERTER_ADAPTER',35,'Adapter: Oberseite'),('CONVERTER_ADAPTER',-35,'Adapter: Mutterzugang von unten'),('INSULATING_WASHER',30,'Isolierscheibe: Drucklage')],1):
  path=ROOT/'delivery/print'/(name+'.stl');m=trimesh.load_mesh(path,process=False);ax=fig.add_subplot(1,3,i,projection='3d');ax.add_collection3d(Poly3DCollection(m.triangles,facecolors='#7ea5b3',edgecolors='#45606b',linewidths=.12));lo,hi=m.bounds;cc=(lo+hi)/2;span=max(hi-lo)*.57
  for method,c in [(ax.set_xlim,cc[0]),(ax.set_ylim,cc[1]),(ax.set_zlim,cc[2])]:method(c-span,c+span)
  ax.set_box_aspect((1,1,1));ax.view_init(elev=elev,azim=-55);ax.set_axis_off();ax.set_title(title,fontsize=12);sources.append(dict(file=str(path),sha256=sha(path)))
 out=ROOT/'delivery/ansichten/Adapter-Details.png';fig.tight_layout();fig.savefig(out,facecolor='white',bbox_inches='tight');plt.close(fig);save(ROOT/'output/render-binding.json',dict(renderer='Matplotlib Agg; actual final STL triangles',sources=sources,png_sha256=sha(out),pixel_review='PENDING'))
if __name__=='__main__':main()

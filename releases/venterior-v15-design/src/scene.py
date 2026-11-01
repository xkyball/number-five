"""Internal CPU-only inspection. Exact printed STLs, nominal bought-part bounds."""
import sys
from common import OUT,ROOT,read,save,sha

def main():
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    import numpy as np
    import trimesh
    from scipy.spatial.transform import Rotation
    assembly=read(OUT/'data/assembly.json');prints={p['id']:p for p in read(OUT/'checks/print-geometry.json')['parts']};scene=[]
    for p in assembly['parts']:
        if p.get('assembled')is False or p['kind']in['hardware','component_pin']:continue
        if p['id']=='FRONT_SHELL':continue # Nominal outline is represented by PCB/LCD/keypad; full bounding box would hide them.
        if p['kind']=='print':
            m=trimesh.load_mesh(OUT/'prints'/(p['id']+'.stl'),process=False);q=prints[p['id']];rot=Rotation.from_euler('xyz',q['rotation_xyz'],degrees=True).as_matrix()
            v=(m.vertices-np.array(q['translation_mm']))@rot;f=m.faces
        else:
            bb=np.array(p['bounds']);m=trimesh.creation.box(extents=bb[1]-bb[0]);m.apply_translation(bb.mean(axis=0));v=m.vertices;f=m.faces
        scene.append(dict(id=p['id'],owner=p['owner'],color=p['color'],v=v,f=f))
    fig=plt.figure(figsize=(16,10),dpi=140)
    for i,(elev,azim,hood)in enumerate([(66,-63,False),(24,-62,True)],1):
        ax=fig.add_subplot(1,2,i,projection='3d',computed_zorder=False);ax.set_proj_type('ortho')
        for j,s in enumerate(sorted(scene,key=lambda s:float(s['v'][:,2].mean()))):
            if s['owner']=='HOOD'and not hood:continue
            tri=s['v'][s['f']]
            ax.add_collection3d(Poly3DCollection(tri,facecolors=s['color'],edgecolor='none',alpha=1 if s['owner']!='HOOD'else.35,rasterized=True,zorder=j+1))
        ax.set(xlim=(-5,220),ylim=(-15,210),zlim=(0,65));ax.set_box_aspect([225,225,85]);ax.view_init(elev,azim);ax.set_axis_off();ax.set_title('Offener Träger'if not hood else'Baugruppe mit Haube',fontsize=15)
    fig.suptitle('Interne Lageprüfung: Druckteile aus STL, Kaufteile als nominelle Hüllen; Originalfront vereinfacht',fontsize=12)
    fig.tight_layout();fig.savefig(OUT/'checks/assembly-internal.png',bbox_inches='tight');plt.close(fig)
    save(ROOT/'evidence/scene-binding.json',dict(assembly_sha256=sha(OUT/'data/assembly.json'),png_sha256=sha(OUT/'checks/assembly-internal.png'),scope='Exact print meshes inverse-transformed into assembly; purchased/reference bodies as axis-aligned nominal bounds. No geometric qualification inferred from rendering.',aborted_exact_tessellation='Own offline CPU-only STEP tessellation stopped after exceeding useful preview runtime; retained authoritative STEP roundtrip and independent STL/OCCT checks. No GUI fallback.'))
if __name__=='__main__':main()

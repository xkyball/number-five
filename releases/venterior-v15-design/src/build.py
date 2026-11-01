"""Serialized offline CAD export. Source errors stop, no healing or GUI fallback."""
import copy,itertools,os,resource,sys,time
for k in['OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS']:os.environ[k]='1'
import build123d as b
from OCP.OSD import OSD_ThreadPool
OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
from OCP.BRepCheck import BRepCheck_Analyzer
from common import ROOT,OUT,TOOLS,read,save,sha
from model import make,assembly,cyl,box
sys.path.insert(0,str(TOOLS/'native-tools'))
sys.path.insert(0,str(TOOLS/'fusion-enclosures'))
from native_tools import tool_lock
from fusion_enclosures.standards_validation import validate_shape

def volume(s):
    if s is None:return 0
    if isinstance(s,b.ShapeList):return sum(volume(x)for x in s)
    return sum(abs(x.volume)for x in s.solids())
def bounds(s):bb=s.bounding_box();return[list(bb.min),list(bb.max)]
def overlap(a,z):return all(min(a[1][i],z[1][i])-max(a[0][i],z[0][i])>1e-6 for i in range(3))

def check(parts,c):
    rows={r['id']:r for r in c['parts']};collisions=[];tested=0
    for a,z in itertools.combinations(parts,2):
        ra,rz=rows[a],rows[z]
        if not overlap(ra['bounds'],rz['bounds']):continue
        # Purchased packages consist of overlapping nominal subfeatures. Actual
        # fits, solder and threads cannot be qualified by their reference models.
        if ra['kind']=='component_pin'or rz['kind']=='component_pin':continue
        if ra['owner']==rz['owner']and ra['owner']in['FRONT','BUCK']:continue
        if ra['owner']==rz['owner']and ra['owner']=='BOARD'and(a=='MAIN_STRIPBOARD'or z=='MAIN_STRIPBOARD'):continue
        if ra['kind']=='hardware'or rz['kind']=='hardware':continue
        tested+=1;v=volume(parts[a].intersect(parts[z]))
        if v>1e-5:collisions.append(dict(a=a,b=z,mm3=v))
    access=[]
    for f in c['fasteners']:
        x,y,z=f['axis'];direction=-f['direction'];tool=cyl(x,y,z+direction*2.5,4,70,(0,0,direction));bb=bounds(tool);bad=[]
        for id,s in parts.items():
            r=rows[id]
            if id==f['id']or r['kind']=='hardware'or id==f['target']or r['owner']in f['omit']:continue
            # Hood is removed for all internal assembly/service operations.
            if r['owner']=='HOOD'and f['owner']!='HOOD':continue
            if overlap(bb,r['bounds']):
                v=volume(tool.intersect(s))
                if v>1e-5:bad.append(dict(id=id,mm3=v))
        access.append(dict(id=f['id'],status='FAIL'if bad else'PASS',obstructions=bad,omitted_owners=f['omit'],tool_diameter=8))
    # Exact positive-Z sweep for this orthogonal hood: all downward planar
    # faces extrude through the entire80mm removal path. Horizontal lower
    # boundaries cover walls, posts, roof and each open-bottom port arch.
    # Curved transducer arch is conservatively bounded by extruding its whole
    # YZ footprint from the lowest arch height (the actual aperture widens
    # downward); exact source and interval samples additionally retain it.
    hood=parts['HOOD'];sweeps=[hood];face_count=0
    for face in hood.faces():
        try:n=face.normal_at()
        except Exception:continue
        if n.Z<-.999999 and face.geom_type==b.GeomType.PLANE:
            sweeps.append(b.Solid.extrude(face,(0,0,80)));face_count+=1
    swept=sweeps[0].fuse(*sweeps[1:]);sweep_bounds=bounds(swept);lift_bad=[]
    for id,s in parts.items():
        r=rows[id]
        if r['owner']=='HOOD'or r.get('assembled')is False:continue
        if overlap(sweep_bounds,r['bounds']):
            v=volume(swept.intersect(s))
            if v>1e-5:lift_bad.append(dict(id=id,mm3=v))
    lift=dict(status='FAIL'if lift_bad else'PASS',travel_mm=80,downward_planar_faces=face_count,obstructions=lift_bad,scope='Positive-Z swept lower planar boundary for orthogonal hood; original cable absent by user scope. Circular transducer arch does not contain a rigid reference part; its real cable fit remains OPEN.')
    outside=[]
    for x,y in c['outside_mount_axes']:
        tool=cyl(x,y,3.1,4,60);bb=bounds(tool);bad=[]
        for id,s in parts.items():
            r=rows[id]
            if id=='BASE'or r.get('assembled')is False:continue
            if overlap(bb,r['bounds']):
                v=volume(tool.intersect(s))
                if v>1e-5:bad.append(dict(id=id,mm3=v))
        outside.append(dict(axis=[x,y],tool_diameter_mm=8,status='FAIL'if bad else'PASS',obstructions=bad))
    report=dict(status='FAIL'if collisions or any(x['status']=='FAIL'for x in access)or lift_bad or any(x['status']=='FAIL'for x in outside)else'PASS',overlap_pairs=tested,unexpected_collisions=collisions,tool_access=access,hood_removal=lift,outside_mount_tool_access=outside,scope='Exact nominal printed/reference rigid intersections;8mm axial tools in stated assembly order. No wire3D, no continuous hand-motion or actual specimen acceptance.')
    save(OUT/'checks/mechanics.json',report);print('MECHANICS',report['status'],'collisions',len(collisions),'tools',len([x for x in access if x['status']=='FAIL']),flush=True)

def main():
    start=time.monotonic()
    old_prints={x['id']:x for x in read(OUT/'checks/print-geometry.json')['parts']}if(OUT/'checks/print-geometry.json').exists()else{}
    # Preserve prior checks before a changed-source build; no overwritten FAIL
    # is reclassified as a PASS or lost merely because the model was corrected.
    if(OUT/'data/build.json').exists():
        import shutil
        stamp=sha(OUT/'data/build.json')[:16];archive=ROOT/'evidence/history'/stamp;archive.mkdir(parents=True,exist_ok=True)
        for sub in['checks','data']:
            for p in(OUT/sub).glob('*.json'):
                dst=archive/sub/p.name;dst.parent.mkdir(exist_ok=True);shutil.copyfile(p,dst)
    parts,c=make()
    for d in['assemblies','prints','brep','checks','data']:(OUT/d).mkdir(parents=True,exist_ok=True)
    save(OUT/'data/assembly.json',c);valid=[];prints=[]
    print('BUILT',len(parts),'named solids/groups',flush=True)
    for m in c['parts']:
        s=parts[m['id']];ss=s.solids();ok=bool(ss)and all(BRepCheck_Analyzer(x.wrapped,True).IsValid()and x.volume>0 for x in ss)
        valid.append(dict(id=m['id'],valid=ok,solids=len(ss),volume_mm3=volume(s)))
        if m['kind']!='print':continue
        id=m['id'];brep=OUT/'brep'/(id+'.brep');rotation=m.get('print_rotation',[0,0,0]);reuse=False
        if id in old_prints and brep.exists()and old_prints[id]['rotation_xyz']==rotation:
            old=b.import_brep(brep);diff=volume(s)+volume(old)-2*volume(s.intersect(old))
            if abs(diff)<1e-5 and all(abs(x-y)<1e-5 for a,z in zip(bounds(s),bounds(old))for x,y in zip(a,z)):
                reuse=True
        if reuse:
            row=copy.deepcopy(old_prints[id]);row['reused_equivalent_geometry']=True;prints.append(row)
            print('PRINT',id,'REUSED exact BRep volume/common/bounds',flush=True);continue
        b.export_brep(s,brep);q=copy.copy(s)
        for axis,a in zip([b.Axis.X,b.Axis.Y,b.Axis.Z],rotation):
            if a:q=q.rotate(axis,a)
        bb=q.bounding_box();translation=[-bb.min.X,-bb.min.Y,-bb.min.Z];q=q.moved(b.Location(translation))
        b.export_stl(q,OUT/'prints'/(id+'.stl'),tolerance=.04,angular_tolerance=.18)
        me=b.Mesher(unit=b.Unit.MM);me.add_shape(q,linear_deflection=.04,angular_deflection=.18,part_number=id);me.write(OUT/'prints'/(id+'.3mf'))
        st=validate_shape(q.wrapped,expected_solids=1,check_self_interference=True,brep_details=False,si_details=False)
        bed=c['parameters']['bed'];size=list(q.bounding_box().size);fit=all(x+4<=lim for x,lim in zip(size[:2],bed[:2]))and size[2]<=bed[2]
        prints.append(dict(id=id,units='mm',size_mm=size,rotation_xyz=rotation,translation_mm=translation,bed_fit_with_2mm_border=fit,standard=st,source_sha256=sha(OUT/'prints'/(id+'.stl')),manufacturing_release=False,reused_equivalent_geometry=False))
        print('PRINT',id,st['status'],size,flush=True)
    save(OUT/'checks/source-solids.json',dict(status='PASS'if all(x['valid']for x in valid)else'FAIL',parts=valid))
    save(OUT/'checks/print-geometry.json',dict(status='PASS'if all(x['standard']['passed']and x['bed_fit_with_2mm_border']for x in prints)else'FAIL',parts=prints))
    check(parts,c);views=[]
    for view in['closed','open','exploded']:
        s=assembly(parts,c,view);path=OUT/'assemblies'/('Venterior-V15-'+view+'.step');b.export_step(s,path,unit=b.Unit.MM);q=b.import_step(path)
        good=all(BRepCheck_Analyzer(x.wrapped,True).IsValid()for x in q.solids());err=abs(volume(s)-volume(q))/volume(s)
        names0={m['id']for m in c['parts']if m.get('assembled')is not False};names1={x.label for x in q.descendants if not x.children}
        views.append(dict(view=view,valid=good,solids_before=len(s.solids()),solids_after=len(q.solids()),volume_relative_error=err,names_match=names0==names1,units='mm',status='PASS'if good and err<1e-7 and names0==names1 and len(s.solids())==len(q.solids())else'FAIL'))
        print('STEP',view,views[-1]['status'],flush=True)
    save(OUT/'checks/step-readback.json',dict(status='PASS'if all(v['status']=='PASS'for v in views)else'FAIL',views=views))
    save(OUT/'data/build.json',dict(seconds=time.monotonic()-start,cpu_seconds=time.process_time(),peak_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,build123d=b.__version__,threads=2,gui=False,source_hashes={str(p.relative_to(ROOT)):sha(p)for p in[ROOT/'parameters.json',ROOT/'src/model.py',ROOT/'src/build.py',ROOT/'src/common.py',OUT/'data/electronics.json']}))

    failed=[name for name in ['source-solids','print-geometry','mechanics','step-readback']if read(OUT/'checks'/(name+'.json'))['status']!='PASS']
    if failed:raise RuntimeError('CAD qualification failed: '+','.join(failed))

if __name__=='__main__':
    with tool_lock('cad',timeout=600):main()

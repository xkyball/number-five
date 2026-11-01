"""Four source-bound offline slice jobs; no connection to a printer."""
import importlib.util,sys,zipfile,xml.etree.ElementTree as ET,shutil
import numpy as np
import trimesh
from common import ROOT,OUT,TOOLS,read,save,sha
sys.path.insert(0,str(TOOLS/'native-tools'))
from native_tools import run
from flashforge_preview import from_source
from flashforge_batch import slice_plate

PROTOTYPES=TOOLS/'fusion-enclosures/prototypes'
spec=importlib.util.spec_from_file_location('et600_triangle_check',PROTOTYPES/'et600-gate/src/prepare_print.py');helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
spec=importlib.util.spec_from_file_location('trim_mesh_check',PROTOTYPES/'trim-sensor/src/check_fit_print.py');meshcheck=importlib.util.module_from_spec(spec);spec.loader.exec_module(meshcheck)
NS='http://schemas.microsoft.com/3dmanufacturing/core/2015/02';ET.register_namespace('',NS)
def node(p,n,**a):return ET.SubElement(p,'{'+NS+'}'+n,{k:str(v)for k,v in a.items()})

def main():
    if read(OUT/'checks/print-geometry.json')['status']!='PASS':raise ValueError('Print preflight notPASS')
    jobs={'basis':({'BASE':(0,0)},False),'haube':({'HOOD':(0,0)},True),'halter':({'FRONT_ADAPTER_1':(-65,-35),'FRONT_ADAPTER_2':(-20,-35),'FRONT_ADAPTER_3':(25,-35),'FRONT_ADAPTER_4':(70,-35),'USB_CLAMP_1':(-45,15),'USB_CLAMP_2':(-5,15),'SCREW_COUPON':(45,20)},False),'stromaufnahme':({'POWER_CRADLE':(-35,0),'DEVICE_POWER_CRADLE':(35,0)},True)}
    jobs['halter'][0].update(USB_JAW_1=(-45,45),USB_JAW_2=(-5,45))
    jobs['stromaufnahme']=({'POWER_CRADLE':(-40,-50),'DEVICE_POWER_CRADLE':(40,-50),'POWER_CAP':(-40,0),'DEVICE_POWER_CAP':(40,0),'POWER_INSERT':(-40,50),'DEVICE_POWER_INSERT':(40,50)},True)
    expected={r['id']for r in read(OUT/'data/assembly.json')['parts']if r['kind']=='print'}
    planned=[id for positions,_ in jobs.values()for id in positions]
    if set(planned)!=expected or len(planned)!=len(expected):raise ValueError('Print plate coverage differs from assembly contract')
    template=PROTOTYPES/'trim-sensor/inputs/fit-print-preview.3mf';profile=TOOLS/'native-tools/material-profiles/tom-ad5m-04-creality-cr-petg-r1.json';reports=[]
    for name,(placements,support)in jobs.items():
        folder=OUT/'slicing'/name;folder.mkdir(parents=True,exist_ok=True)
        old_binding=read(folder/'source-binding.json')if(folder/'source-binding.json').exists()else None
        current_hashes={id:sha(OUT/'prints'/(id+'.stl'))for id in placements}
        prior_output=folder/('Venterior-V15-'+name+'-PETG.3mf');prior_support=None
        if prior_output.exists():
            with zipfile.ZipFile(prior_output)as zz:prior_support=str(__import__('json').loads(zz.read('Metadata/project_settings.config')).get('enable_support'))in['1','True','true']
        if old_binding and(current_hashes!={r['id']:r['sha256']for r in old_binding['parts']}or prior_support!=support or old_binding.get('profile_sha256')!=sha(profile)):
            tag=sha(folder/'source-binding.json')[:16];history=ROOT/'evidence/slice-history'/name/tag;history.mkdir(parents=True,exist_ok=True)
            for old in folder.iterdir():
                if old.is_file():shutil.copyfile(old,history/old.name)
        model=ET.Element('{'+NS+'}model',unit='millimeter');res=node(model,'resources');build=node(model,'build');rows=[]
        for i,(id,center)in enumerate(placements.items(),1):
            path=OUT/'prints'/(id+'.stl');m=trimesh.load_mesh(path,process=True);v=np.asarray(m.vertices);translation=np.r_[np.array(center)-(m.bounds[0,:2]+m.bounds[1,:2])/2,-m.bounds[0,2]]
            assert m.is_watertight and m.volume>0
            obj=node(res,'object',id=i,type='model',name=id);me=node(obj,'mesh');verts=node(me,'vertices');faces=node(me,'triangles')
            for vv in v:node(verts,'vertex',**{k:format(float(x),'.17g')for k,x in zip('xyz',vv)})
            for f in m.faces:node(faces,'triangle',**{'v'+str(j+1):int(x)for j,x in enumerate(f)})
            node(build,'item',objectid=i,transform=' '.join(map(str,np.r_[np.eye(3).ravel(),translation])))
            bb=m.bounds+translation;assert abs(bb[:,:2]).max()<=108 and bb[0,2]>=-1e-6
            rows.append(dict(id=id,sha256=sha(path),translation_mm=translation.tolist(),bounds_mm=bb.tolist()))
        source=folder/'source.3mf'
        with zipfile.ZipFile(template)as t,zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED)as z:
            for n in['[Content_Types].xml','_rels/.rels']:z.writestr(n,t.read(n))
            z.writestr('3D/3dmodel.model',ET.tostring(model,encoding='utf-8',xml_declaration=True))
        cached=folder/'preview-only.3mf';binding=from_source(source,cached,template)
        before=meshcheck.model_triangles(source);after=meshcheck.model_triangles(cached)
        assert set(before)==set(after)==set(placements)
        for id in before:helper.triangle_difference(before[id],after[id])
        save(folder/'source-binding.json',dict(parts=rows,preview=binding,template_sha256=sha(template),profile_sha256=sha(profile),support_enabled=support,triangle_check_helper_sha256=sha(PROTOTYPES/'et600-gate/src/prepare_print.py')))
        project='Venterior-V15-'+name+'-PETG'
        output=folder/(project+'.3mf');reuse=bool(old_binding and prior_support==support and output.exists()and old_binding.get('profile_sha256')==sha(profile)and current_hashes=={r['id']:r['sha256']for r in old_binding['parts']})
        if not reuse:
            if output.exists():output.unlink() # Original retained in slice-history above.
            slice_plate('/Applications/Flash Studio.app/Contents/MacOS/Flash Studio','/Applications/Flash Studio.app/Contents/Resources/profiles/Flashforge',cached,folder,project_name=project,profile_bundle=profile,overrides=dict(process=dict(enable_support='1'if support else'0',brim_type='no_brim',skirt_loops='0',raft_layers='0')))
        actual=meshcheck.model_triangles(output)
        assert set(actual)==set(before)
        delta={id:helper.triangle_difference(before[id],actual[id])for id in before}
        with zipfile.ZipFile(output)as z:
            code=z.read('Metadata/plate_1.gcode').decode();(folder/'offline-test.gcode').write_text(code)
            settings=__import__('json').loads(z.read('Metadata/project_settings.config'))
            (folder/'preview.png').write_bytes(z.read('Metadata/plate_1.png'))
        assert(str(settings.get('enable_support'))in['1','True','true'])==support
        extrusions=sum(1 for l in code.splitlines()if l.startswith(('G0 ','G1 '))and'E'in l)
        assert extrusions>20 and'layer' in code.lower()
        reports.append(dict(id=name,status='PASS_SLICE_AND_GEOMETRY_READBACK',parts=list(placements),editable_3mf=str(output.relative_to(OUT)),triangle_max_error_mm=delta,extrusion_moves=extrusions,support_enabled=support,reused_unchanged_slice=reuse,source_sha256=sha(source),output_sha256=sha(output),material_profile_sha256=sha(profile),printer_commands_sent=0,GUI_fallback=False,actual_print='OPEN'))
        save(OUT/'checks/slicer.json',dict(status='RUNNING'if len(reports)<len(jobs)else'PASS',jobs=reports,scope='Source/CPU-preview/native-output complete world-space triangle readback and fresh vendor batch slice; cached previews avoid native GUI thumbnail path. No real print/adhesion/strength approval.'))
        print('SLICE',name,'PASS',extrusions,'extrusion moves',flush=True)

if __name__=='__main__':main()

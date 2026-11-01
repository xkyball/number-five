"""Offscreen adapter sub-kit preparation. Never transport or start a print."""
import os,sys,json,math,zipfile,shutil,tempfile,re,subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import trimesh
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));import prepare_upper_print as old
sys.path.insert(0,'/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pi-box-l3-routing-runtime')
from ortools.sat.python import cp_model
OUT=ROOT/'print-adapters';OUT.mkdir(exist_ok=True)
PROJECT='Pi-Box-PI-2L-5-Adapter-Satz-PETG'
NS=old.NS;PROCESS=dict(old.PROCESS)
PROCESS.update(brim_width='2',enable_support='0')

def settings(name):return dict(enable_support='0',brim_type='outer_only',brim_width='1.5'if name.startswith('INSULATING')else'2')
def binding(p):return dict(file=os.path.relpath(p,OUT),sha256=sha(p))
def prepare():
 assert read(ROOT/'output/adapter-independent-check.json')['status']=='PASS_SCOPED_ADAPTERS'
 inventory=read(ROOT/'delivery/daten/druckteile.json');meshes={};rows=[]
 for part in inventory['parts']:
  m=trimesh.load_mesh(part['stl'],process=True);assert m.is_watertight and m.is_winding_consistent
  for i in range(part['quantity']):
   name=part['id']+'_'+str(i+1).zfill(2);meshes[name]=m;rows.append(dict(id=name,type=part['id'],source_stl=binding(Path(part['stl'])),object_settings=settings(name)))
 # Deterministic CP-SAT packing includes per-part brim, gap and spacing.
 model=cp_model.CpModel();ix=[];iy=[];variables={};maxx=model.new_int_var(0,2160,'maxx');maxy=model.new_int_var(0,2160,'maxy')
 for i,row in enumerate(rows):
  m=meshes[row['id']];pad=float(row['object_settings']['brim_width'])+.3+.4;w=math.ceil((m.extents[0]+2*pad)*10);h=math.ceil((m.extents[1]+2*pad)*10)
  x=model.new_int_var(0,2160-w,row['id']+'x');y=model.new_int_var(0,2160-h,row['id']+'y');ex=model.new_int_var(w,2160,row['id']+'ex');ey=model.new_int_var(h,2160,row['id']+'ey');model.add(ex==x+w);model.add(ey==y+h);model.add(maxx>=ex);model.add(maxy>=ey)
  ix.append(model.new_interval_var(x,w,ex,row['id']+'ix'));iy.append(model.new_interval_var(y,h,ey,row['id']+'iy'));variables[row['id']]=(x,y,pad)
  if i and row['type']==rows[i-1]['type']:model.add(x+y*2161>=variables[rows[i-1]['id']][0]+variables[rows[i-1]['id']][1]*2161)
 model.add_no_overlap_2d(ix,iy);model.minimize(maxy*10+maxx);solver=cp_model.CpSolver();solver.parameters.max_time_in_seconds=20;solver.parameters.num_search_workers=1;solver.parameters.random_seed=5;status=solver.solve(model);assert status in [cp_model.OPTIMAL,cp_model.FEASIBLE]
 actualw=solver.value(maxx)/10;actualh=solver.value(maxy)/10
 doc=ET.Element('{'+NS+'}model',unit='millimeter');resources=old.node(doc,'resources');build=old.node(doc,'build')
 for i,row in enumerate(rows,1):
  x,y,pad=variables[row['id']];move=np.asarray([solver.value(x)/10+pad-actualw/2,solver.value(y)/10+pad-actualh/2,0]);m=meshes[row['id']];v=m.vertices+move
  row.update(translation_mm=move.tolist(),print_bounds_mm=[v.min(0).tolist(),v.max(0).tolist()],rotation_z_deg=0,volume_mm3=float(m.volume))
  obj=old.node(resources,'object',id=i,type='model',name=row['id']);g=old.node(obj,'mesh');vs=old.node(g,'vertices');fs=old.node(g,'triangles')
  for vv in v:old.node(vs,'vertex',**{a:format(float(t),'.17g')for a,t in zip('xyz',vv)})
  for ff in m.faces:old.node(fs,'triangle',**{'v'+str(j+1):int(t)for j,t in enumerate(ff)})
  old.node(build,'item',objectid=i)
 template=BASE.parent/'trim-sensor/inputs/fit-print-preview.3mf';plate=OUT/'plate-source.3mf'
 with zipfile.ZipFile(template)as z,zipfile.ZipFile(plate,'w',zipfile.ZIP_DEFLATED)as target:
  for n in ['[Content_Types].xml','_rels/.rels']:target.writestr(n,z.read(n))
  target.writestr('3D/3dmodel.model',ET.tostring(doc,encoding='utf-8',xml_declaration=True))
 seed=OUT/'preview-no-gcode.3mf';provenance=old.from_source(plate,seed,template)
 with zipfile.ZipFile(seed)as z:files={n:z.read(n)for n in z.namelist()}
 config=ET.fromstring(files['Metadata/model_settings.config'])
 for obj in config.findall('object'):
  name=next(x.get('value')for x in obj.findall('metadata')if x.get('key')=='name')
  for k,v in settings(name).items():ET.SubElement(obj,'metadata',key=k,value=v)
 files['Metadata/model_settings.config']=ET.tostring(config,encoding='utf-8',xml_declaration=True)
 with zipfile.ZipFile(seed,'w',zipfile.ZIP_DEFLATED)as z:
  for n,data in files.items():z.writestr(n,data)
 old.require_cached_previews(seed);shutil.copyfile(old.TOOLS/'material-profiles/tom-ad5m-04-creality-cr-petg-r1.json',OUT/'material-profile.json')
 save(OUT/'preparation.json',dict(revision='PI-2L-5',scope='ADAPTER_SUBKIT_ONLY_COMPLETE_INTERIOR_STILL_OPEN',source_design=binding(ROOT/'design.json'),source_check=binding(ROOT/'output/adapter-independent-check.json'),parts=rows,physical_counts={'adapters':5,'insulating_washers':10},complete_rest_kit=False,bed_mm=[220,220],process=PROCESS,packing=dict(engine='Google OR-Tools CP-SAT',status=solver.status_name(status),allocated_bounds_mm=[actualw,actualh],seconds=solver.wall_time),digital='PENDING_SLICE_AND_PIXEL_REVIEW',transport='NOT_SENT',printer_start='NOT_REQUESTED',physical_fit='OPEN'))
 save(OUT/'preview-provenance.json',provenance);print('PREPARED',len(rows),'bodies; allocated',actualw,actualh,'mm',flush=True)

def slice_current():
 app=Path('/Applications/Flash Studio.app');q=subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],capture_output=True,text=True);(OUT/'signature-check.log').write_text(q.stdout+q.stderr);assert q.returncode==0
 old.slice_plate(app/'Contents/MacOS/Flash Studio',app/'Contents/Resources/profiles/Flashforge',OUT/'preview-no-gcode.3mf',OUT,project_name=PROJECT,profile_bundle=OUT/'material-profile.json',overrides=dict(process=PROCESS));print('OFFSCREEN SLICE COMPLETE',flush=True)

def check():
 report=read(OUT/'preparation.json');project=OUT/(PROJECT+'.3mf');before=old.model_triangles(OUT/'plate-source.3mf');after=old.model_triangles(project);names={r['id']for r in report['parts']};assert set(before)==set(after)==names
 delta=max(old.triangle_difference(before[n],after[n])for n in names);assert delta<1e-5
 cfg=old.configs(project);assert all(all(cfg[n].get(k)==v for k,v in settings(n).items())for n in names)
 with zipfile.ZipFile(project)as z:
  gc=z.read('Metadata/plate_1.gcode');(OUT/'native-preview.png').write_bytes(z.read('Metadata/plate_1.png'));ps=json.loads(z.read('Metadata/project_settings.config'));info=z.read('Metadata/slice_info.config').decode()
 assert (OUT/'plate_1.gcode').read_bytes()==gc
 for k,v in dict(layer_height='0.2',wall_loops='4',sparse_infill_density='20%',sparse_infill_pattern='gyroid',nozzle_temperature=['240'],textured_plate_temp=['85'],filament_type=['PETG'],filament_max_volumetric_speed=['6']).items():assert ps[k]==v,(k,ps[k])
 old.verify_hot_start(OUT/'plate_1.gcode','PETG',240);text=gc.decode();metrics={};segments,feed,purge=old.parse_gcode(text,metrics);paths=[s for s in segments if s[2]>0 and s[3]!='Custom'];points=np.asarray([p for a,z,l,r in paths for p in [a,z]]);assert max(abs(points[:,:2]).max(0))<109.75
 assert metrics['max_print_speed_mm_s']<=50.001 and metrics['max_flow_precision_consistency_mm3_s']<=6.001
 roles={s[3]for s in paths};assert not {'Support','Support interface','Raft','Skirt'}.intersection(roles)
 layers=sorted({s[2]for s in paths});assert abs(layers[0]-.2)<1e-5 and max(np.diff(layers))<.201
 # Every adapter riser and every thin insulating foot must survive slicing.
 features=[]
 a=read(ROOT/'design.json')['converter_adapter'];move=read(ROOT/'output/adapter-independent-check.json')['print_translation_mm']
 for row in report['parts']:
  tr=np.asarray(row['translation_mm']);upper=[s for s in paths if s[3]!='Brim'and (4.1<s[2]<5.61 if row['type']=='CONVERTER_ADAPTER'else .61<s[2]<1.01)]
  if row['type']=='CONVERTER_ADAPTER':centres=[np.asarray([x+move[0],y+move[1]])+tr[:2]for x,y in a['board_mounts_local_cad']]
  else:centres=[np.asarray([3.5,3.5])+tr[:2]]
  for c in centres:
   nearby=[s for s in upper if min(np.linalg.norm(np.asarray(s[0][:2])-c),np.linalg.norm(np.asarray(s[1][:2])-c))<4]
   assert nearby,(row['id'],'missing upper feature');features.append(dict(object=row['id'],centre=c.tolist(),present=True))
 old.render(paths,OUT/'toolpaths.png',(-100,-100,100,100),[x for x in [.2,.4,.6,.8,1,1.6,2.4,3.4,3.6,4,4.2,5.6]if x<=layers[-1]+.001],(2500,2100))
 old.render(paths,OUT/'first-layers.png',(-100,-100,100,100),[.2,.4,.8,1],(1800,1800))
 # Reopen through the same signed offscreen CLI, never a visible app host.
 with tempfile.TemporaryDirectory(prefix='pi-r5-reopen-')as td:
  q=old.run(['/Applications/Flash Studio.app/Contents/MacOS/Flash Studio','--datadir',td+'/user','--export-3mf','reopened.3mf','--outputdir',td,str(project)],capture_output=True,text=True,timeout=180);(OUT/'reopen.log').write_text(q.stdout+q.stderr);assert q.returncode==0
  again=old.model_triangles(Path(td)/'reopened.3mf');assert set(again)==names;rd=max(old.triangle_difference(after[n],again[n])for n in names);assert rd<1e-5
 grams=float(re.search(r'; total filament used \[g\] = ([\d.]+)',text)[1]);dest=OUT/(PROJECT+'-'+sha(OUT/'plate_1.gcode')[:12]+'.gcode');shutil.copyfile(OUT/'plate_1.gcode',dest)
 report.update(digital='PASS_SCOPED_ADAPTER_SUBKIT_PIXELS_PENDING',editable_3mf=binding(project),gcode=binding(dest),estimated_time=re.search(r'; estimated printing time \(normal mode\) = (.+)',text)[1],consumption=dict(slicer_g=grams,planning_total_g=math.ceil(grams*1.1+1)),verification=dict(mesh_delta_mm=delta,native_reopen_delta_mm=rd,thin_features_checked=features,gcode_bounds_mm=[points.min(0).tolist(),points.max(0).tolist()],roles=sorted(roles),metrics=metrics,pixel_review='PENDING',slicer_warnings=re.findall(r'<warning[^>]+>',info)))
 save(OUT/'preparation.json',report);print('GCODE CHECK PASS',report['consumption'],report['estimated_time'],flush=True)

if __name__=='__main__':dict(prepare=prepare,slice=slice_current,check=check)[sys.argv[1]]()

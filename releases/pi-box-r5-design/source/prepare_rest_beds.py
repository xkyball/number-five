"""Pack every required body, with brim/support allowance; signed slicer CLI only."""
import os,sys,math,zipfile,shutil,subprocess,tempfile,re
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np,trimesh
from common import ROOT,BASE,read,save,sha
sys.path.insert(0,str(BASE));import prepare_upper_print as old
sys.path.insert(0,'/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pi-box-l3-routing-runtime')
from ortools.sat.python import cp_model
OUT=ROOT/'print-rest';OUT.mkdir(exist_ok=True);NS=old.NS;PROCESS=dict(old.PROCESS,brim_width='2',enable_support='0')
PACKING_SPAN_TENTHS=2160  # 2-mm outer reserve; every brim/support allowance is inside.
def settings(row):return dict(enable_support='1'if row.get('support')else'0',brim_type='outer_only',brim_width='1.5'if row['type']=='INSULATING_WASHER'else'2')
def support_allowance(row):
 # The fuse/coded housings need the larger reserve; the slicer independently
 # rejects actual deposited-path conflicts for the entire candidate bed.
 if not row['support']:return 0
 return 4 if row['type'].startswith('HUB_FUSE_')or row['type'].endswith('KEY_SHROUD')else .8
def project(i):return 'Pi-Box-PI-2L-5-Restbett-'+str(i).zfill(2)+'-PETG'
def prepare():
 inv=read(ROOT/'delivery/daten/druckteile.json');rows=[];meshes={}
 for p in inv['parts']:
  m=trimesh.load_mesh(p['stl'],process=True);assert m.is_watertight and m.is_winding_consistent
  for i in range(p['quantity']):
   id=p['id']+'_'+str(i+1).zfill(2);meshes[id]=m;row=dict(id=id,type=p['id'],support=p.get('support',False),stl=p['stl'],stl_sha256=sha(p['stl']));row['object_settings']=settings(row);rows.append(row)
 rows.sort(key=lambda r:float(np.prod(meshes[r['id']].extents[:2])),reverse=True)
 for nb in [1,2,3]:
  model=cp_model.CpModel();intervals=[[[],[]]for _ in range(nb)];vv={};cost=[]
  for i,r in enumerate(rows):
   pad=float(r['object_settings']['brim_width'])+.3+.4+support_allowance(r);a,h=[math.ceil((v+2*pad)*10)for v in meshes[r['id']].extents[:2]]
   rot=model.new_bool_var(r['id']+'_rot');w=model.new_int_var(min(a,h),max(a,h),'w'+str(i));hh=model.new_int_var(min(a,h),max(a,h),'h'+str(i));model.add(w==a+(h-a)*rot);model.add(hh==h+(a-h)*rot)
   x=model.new_int_var(0,PACKING_SPAN_TENTHS,'x'+str(i));y=model.new_int_var(0,PACKING_SPAN_TENTHS,'y'+str(i));ex=model.new_int_var(0,PACKING_SPAN_TENTHS,'ex'+str(i));ey=model.new_int_var(0,PACKING_SPAN_TENTHS,'ey'+str(i));model.add(ex==x+w);model.add(ey==y+hh)
   bs=[model.new_bool_var('bed'+str(k)+'_'+str(i))for k in range(nb)];model.add_exactly_one(bs)
   for k,on in enumerate(bs):intervals[k][0].append(model.new_optional_interval_var(x,w,ex,on,'ix'+str(i)+'b'+str(k)));intervals[k][1].append(model.new_optional_interval_var(y,hh,ey,on,'iy'+str(i)+'b'+str(k)))
   vv[r['id']]=(x,y,rot,bs,pad);cost.extend((k*1000000+y)*on for k,on in [])
   if i==0:model.add(bs[0]==1);model.add(x==0);model.add(y==0)
   for prev in rows[:i]:
    if prev['type']==r['type']:
     px,py,pr,pbs,_=vv[prev['id']];model.add(sum(k*v for k,v in enumerate(bs))*5000000+x+y*2161>=sum(k*v for k,v in enumerate(pbs))*5000000+px+py*2161)
  for dims in intervals:model.add_no_overlap_2d(*dims)
  solver=cp_model.CpSolver();solver.parameters.num_search_workers=1;solver.parameters.random_seed=5;solver.parameters.max_time_in_seconds=120 if nb==1 else 30;solver.parameters.stop_after_first_solution=True;status=solver.solve(model)
  if status in [cp_model.OPTIMAL,cp_model.FEASIBLE]:break
 assert status in [cp_model.OPTIMAL,cp_model.FEASIBLE],solver.status_name(status)
 beds=[]
 for k in range(nb):
  target=OUT/('Bett-'+str(k+1).zfill(2));target.mkdir(exist_ok=True);members=[];doc=ET.Element('{'+NS+'}model',unit='millimeter');resources=old.node(doc,'resources');build=old.node(doc,'build')
  for r in rows:
   x,y,rr,bs,pad=vv[r['id']]
   if not solver.value(bs[k]):continue
   m=meshes[r['id']].copy();angle=90*solver.value(rr)
   if angle:m.apply_transform(trimesh.transformations.rotation_matrix(math.pi/2,[0,0,1]))
   m.apply_translation(-m.bounds[0]);move=np.asarray([solver.value(x)/10+pad-PACKING_SPAN_TENTHS/20,solver.value(y)/10+pad-PACKING_SPAN_TENTHS/20,0]);m.apply_translation(move);row=dict(r,rotation_z_deg=angle,translation_mm=move.tolist(),print_bounds_mm=m.bounds.tolist());members.append(row)
   obj=old.node(resources,'object',id=len(members),type='model',name=r['id']);mesh=old.node(obj,'mesh');vs=old.node(mesh,'vertices');fs=old.node(mesh,'triangles')
   for v in m.vertices:old.node(vs,'vertex',**{a:format(float(t),'.17g')for a,t in zip('xyz',v)})
   for f in m.faces:old.node(fs,'triangle',**{'v'+str(j+1):int(t)for j,t in enumerate(f)})
   old.node(build,'item',objectid=len(members))
  template=BASE.parent/'trim-sensor/inputs/fit-print-preview.3mf';plate=target/'plate-source.3mf'
  with zipfile.ZipFile(template)as z,zipfile.ZipFile(plate,'w',zipfile.ZIP_DEFLATED)as out:
   for n in ['[Content_Types].xml','_rels/.rels']:out.writestr(n,z.read(n))
   out.writestr('3D/3dmodel.model',ET.tostring(doc,encoding='utf-8',xml_declaration=True))
  seed=target/'preview-no-gcode.3mf';provenance=old.from_source(plate,seed,template)
  with zipfile.ZipFile(seed)as z:files={n:z.read(n)for n in z.namelist()}
  config=ET.fromstring(files['Metadata/model_settings.config'])
  for obj in config.findall('object'):
   id=next(x.get('value')for x in obj.findall('metadata')if x.get('key')=='name');row=next(r for r in members if r['id']==id)
   for a,v in row['object_settings'].items():ET.SubElement(obj,'metadata',key=a,value=v)
  files['Metadata/model_settings.config']=ET.tostring(config,encoding='utf-8',xml_declaration=True)
  with zipfile.ZipFile(seed,'w',zipfile.ZIP_DEFLATED)as z:
   for n,data in files.items():z.writestr(n,data)
  old.require_cached_previews(seed);shutil.copyfile(old.TOOLS/'material-profiles/tom-ad5m-04-creality-cr-petg-r1.json',target/'material-profile.json');save(target/'preparation.json',dict(revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),parts=members,process=PROCESS,digital='PENDING_SLICE',printer_start='NOT_REQUESTED',transport='NOT_SENT'));save(target/'preview-provenance.json',provenance);beds.append(dict(number=k+1,folder=str(target),count=len(members),parts=[r['id']for r in members]));print('BED',k+1,len(members),'bodies',flush=True)
 save(OUT/'set.json',dict(revision='PI-2L-5',source_sha256=sha(ROOT/'design.json'),packing=dict(engine='Google OR-Tools CP-SAT',bed_count=nb,status=solver.status_name(status),seconds=solver.wall_time,approach='Conservative mesh rectangles with rotation and independent brim/support allowance; not an optimal contour-nesting proof'),body_count=len(rows),types=len(inv['parts']),beds=beds,all_current_print_bodies_included=True,print_release=False,remaining_technical_checks='Local cable transitions and actual intact USB service path remain separately OPEN. No print start.'))

def slice_all():
 app=Path('/Applications/Flash Studio.app');q=subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],capture_output=True,text=True);(OUT/'signature-check.log').write_text(q.stdout+q.stderr);assert q.returncode==0
 for bed in read(OUT/'set.json')['beds']:
  t=Path(bed['folder']);old.slice_plate(app/'Contents/MacOS/Flash Studio',app/'Contents/Resources/profiles/Flashforge',t/'preview-no-gcode.3mf',t,project_name=project(bed['number']),profile_bundle=t/'material-profile.json',overrides=dict(process=PROCESS));print('SLICED',bed['number'],flush=True)

def check():
 for bed in read(OUT/'set.json')['beds']:
  t=Path(bed['folder']);r=read(t/'preparation.json');p=t/(project(bed['number'])+'.3mf');a,z=old.model_triangles(t/'plate-source.3mf'),old.model_triangles(p);assert set(a)==set(z)==set(bed['parts']);delta=max(old.triangle_difference(a[n],z[n])for n in a);assert delta<1e-5
  cfg=old.configs(p)
  for row in r['parts']:assert all(cfg[row['id']].get(k)==v for k,v in row['object_settings'].items())
  with zipfile.ZipFile(p)as zz:gc=zz.read('Metadata/plate_1.gcode');ps=__import__('json').loads(zz.read('Metadata/project_settings.config'));(t/'native-preview.png').write_bytes(zz.read('Metadata/plate_1.png'))
  assert (t/'plate_1.gcode').read_bytes()==gc
  for k,v in dict(layer_height='0.2',wall_loops='4',sparse_infill_density='20%',sparse_infill_pattern='gyroid',nozzle_temperature=['240'],textured_plate_temp=['85'],filament_type=['PETG']).items():assert ps[k]==v,(k,ps[k])
  old.verify_hot_start(t/'plate_1.gcode','PETG',240);text=gc.decode();metrics={};ss,feed,purge=old.parse_gcode(text,metrics);paths=[s for s in ss if s[2]>0 and s[3]!='Custom'];points=np.asarray([p for a,z,l,role in paths for p in [a,z]]);assert abs(points[:,:2]).max()<109.75
  assert metrics['max_print_speed_mm_s']<=50.001 and metrics['max_flow_precision_consistency_mm3_s']<=6.001
  old.render(paths,t/'toolpaths.png',(-110,-110,110,110),[.2,.4,1,2,3.2,5.6,8,12,16,20,26,34,44,50],(2500,2500));old.render(paths,t/'first-layers.png',(-110,-110,110,110),[.2,.4,.8,1],(1800,1800))
  old.require_cached_previews(p)
  with tempfile.TemporaryDirectory(prefix='pi-r5-rest-reopen-')as td:
   q=old.run(['/Applications/Flash Studio.app/Contents/MacOS/Flash Studio','--datadir',td+'/user','--export-3mf','reopened.3mf','--outputdir',td,str(p)],capture_output=True,text=True,timeout=180);(t/'reopen.log').write_text(q.stdout+q.stderr);assert q.returncode==0;again=old.model_triangles(Path(td)/'reopened.3mf');assert set(again)==set(z);assert max(old.triangle_difference(z[n],again[n])for n in z)<1e-5
  grams=float(re.search(r'; total filament used \[g\] = ([\d.]+)',text)[1]);dest=t/(project(bed['number'])+'-'+sha(t/'plate_1.gcode')[:12]+'.gcode');shutil.copyfile(t/'plate_1.gcode',dest)
  r.update(digital='CANDIDATE_SLICE_PASS_PIXEL_REVIEW_PENDING',gcode=str(dest),three_mf=str(p),slicer_g=grams,budget_g=math.ceil(grams*1.1+1),estimated_time=re.search(r'; estimated printing time \(normal mode\) = (.+)',text)[1],verification=dict(mesh_delta_mm=delta,gcode_bounds_mm=[points.min(0).tolist(),points.max(0).tolist()],roles=sorted({s[3]for s in paths}),metrics=metrics,native_reopen=True,pixel_review='PENDING'))
  save(t/'preparation.json',r);print('BED CHECK',bed['number'],grams,'g',r['estimated_time'],flush=True)
if __name__=='__main__':dict(prepare=prepare,slice=slice_all,check=check)[sys.argv[1]]()

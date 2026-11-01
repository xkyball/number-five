"""Functional fastener measurements on reimported STEP, not nominal mate tags."""
import json, math
import build123d as b
from OCP.BRepAdaptor import BRepAdaptor_Surface
from audit_support import R, OUT, read, save, sha, finding, report, Graph

EPS = .001  # numerical volume tolerance mm3, not manufacturing allowance

def shapes(v): return list(v) if isinstance(v, (list, b.ShapeList)) else [v] if v is not None else []
def volume(v): return sum(abs(s.volume) for s in shapes(v))
def overlap(a, z): return volume(a.intersect(z))
def box(lo, hi):
    return b.Box(*[hi[i]-lo[i] for i in range(3)], align=(b.Align.MIN,)*3).moved(b.Location(tuple(lo)))
def cylinder(p, radius, length, direction):
    return b.Solid.make_cylinder(radius, length, b.Plane(origin=p, z_dir=direction))
def dot(a, z): return sum(x*y for x,y in zip(a,z))
def add(a,z): return [x+y for x,y in zip(a,z)]
def mul(a,s): return [x*s for x in a]
def projections(s, direction, origin):
    bb=s.bounding_box()
    vals=[dot([v.X-origin[0],v.Y-origin[1],v.Z-origin[2]], direction) for v in s.vertices()]
    if not vals: vals=[dot(list(bb.min),direction),dot(list(bb.max),direction)]
    return min(vals),max(vals)

def axis_data(s):
    candidates=[]
    for f in s.faces():
        if f.geom_type != b.GeomType.CYLINDER: continue
        c=BRepAdaptor_Surface(f.wrapped,True).Cylinder(); v=c.Axis().Direction(); axis=[v.X(),v.Y(),v.Z()]
        # All screws in this assembly have a Cartesian axis. Measure longest shaft.
        lo,hi=projections(f,axis,[0,0,0]); candidates.append((hi-lo,c.Radius(),f,axis,c))
    length,radius,f,axis,c=max(candidates,key=lambda q:q[0])
    axis=[float(round(v)) for v in axis]; pos=c.Location(); line=[pos.X(),pos.Y(),pos.Z()]
    alo,ahi=projections(f,axis,[0,0,0])
    head=max(candidates,key=lambda q:q[1]); hlo,hhi=projections(head[2],axis,[0,0,0])
    direction=axis if (hlo+hhi)/2 < (alo+ahi)/2 else mul(axis,-1)
    fmin,fmax=projections(f,direction,[0,0,0]); off=fmin-dot(line,direction)
    origin=add(line,mul(direction,off))
    _,tip=projections(s,direction,origin)
    return dict(origin=origin,direction=direction,shank_radius=radius,head_radius=head[1],length=tip)

def pilot_data(anchor, axis):
    o,d,r=axis['origin'],axis['direction'],axis.get('thread_major_radius',axis['shank_radius']); holes=[]
    for f in anchor.faces():
        if f.geom_type != b.GeomType.CYLINDER: continue
        c=BRepAdaptor_Surface(f.wrapped,True).Cylinder(); v=c.Axis().Direction(); v=[v.X(),v.Y(),v.Z()]
        p=c.Location(); p=[p.X(),p.Y(),p.Z()]; offset=[p[i]-o[i] for i in range(3)]; radial=math.sqrt(max(0,dot(offset,offset)-dot(offset,d)**2))
        if abs(dot(v,d))>.999 and radial<.002 and c.Radius()<r-.01:
            lo,hi=projections(f,d,o)
            if hi>0: holes.append(dict(start=max(0,lo),end=hi,radius=c.Radius()))
    if not holes: return dict(engagement=0,tip_clearance=None,pilots=[])
    # A central ray independently identifies solid at the blind bottom.
    ray=b.Edge.make_line(add(o,mul(d,.001)),add(o,mul(d,axis['length']+40)))
    intervals=[]
    for e in shapes(anchor.intersect(ray)):
        lo,hi=projections(e,d,o)
        if hi-lo>.0001:intervals.append([lo,hi])
    front=min(h['start'] for h in holes)
    floors=[lo for lo,hi in intervals if lo>front+.02]
    floor=min(floors) if floors else None
    engagement=sum(max(0,min(axis['length'],h['end'])-h['start']) for h in holes)
    bottom_material=next((hi-lo for lo,hi in intervals if floor is not None and abs(lo-floor)<.001),None)
    return dict(engagement=engagement,entry=front,tip_clearance=None if floor is None else floor-axis['length'],blind_floor_from_seat=floor,residual_bottom_material_mm=bottom_material,pilots=holes,central_material_intervals=intervals)

def fastener_measure(screw, anchor, bearing, thread_major_radius=None):
    a=axis_data(screw)
    if thread_major_radius is not None:a['thread_major_radius']=thread_major_radius
    p=pilot_data(anchor,a)
    # Thin annulus immediately behind the actual under-head plane; area from occupied material.
    outer=cylinder(a['origin'],a['head_radius'],.02,a['direction'])
    inner=cylinder(a['origin'],a.get('thread_major_radius',a['shank_radius'])+.1,.02,a['direction'])
    annulus=outer.cut(inner); area=overlap(bearing,annulus)/.02
    support=area>.1
    bore_radii=[]
    for face in bearing.faces():
        if face.geom_type!=b.GeomType.CYLINDER:continue
        c=BRepAdaptor_Surface(face.wrapped,True).Cylinder();point=c.Location();offset=[point.X()-a['origin'][0],point.Y()-a['origin'][1],point.Z()-a['origin'][2]];vd=c.Axis().Direction();vd=[vd.X(),vd.Y(),vd.Z()]
        radial=math.sqrt(max(0,dot(offset,offset)-dot(offset,a['direction'])**2))
        if radial<.002 and abs(dot(vd,a['direction']))>.999 and c.Radius()>a.get('thread_major_radius',a['shank_radius'])+.001:bore_radii.append(c.Radius())
    clearance=min(bore_radii)-a.get('thread_major_radius',a['shank_radius']) if bore_radii else None
    status='FAIL' if not support or p['engagement']<=.1 or (p['tip_clearance'] is not None and p['tip_clearance']<-.01) else 'PASS'
    return dict(status=status,**a,**p,bearing_area_mm2=area,head_supported=support,nominal_lateral_clearance_before_tightening_mm=clearance,
                qualification='Nominal thread envelope only; no printed thread flank or preload strength represented')

def moving_path(parts, moving, obstacles, delta):
    """A full face-prism sweep; conservative boxes for curved parts when clear."""
    hits=[]; incomplete=[]
    for ident in moving:
        s=parts[ident];bb=s.bounding_box();mn,mx=list(bb.min),list(bb.max);lo=[min(mn[i],mn[i]+delta[i]) for i in range(3)];hi=[max(mx[i],mx[i]+delta[i]) for i in range(3)]
        envelope=box(lo,hi)
        candidates=[z for z in obstacles if overlap(envelope,parts[z])>EPS]
        if not candidates: continue
        sweep=[s]; bad=False
        for face in s.faces():
            if face.geom_type==b.GeomType.PLANE and abs(dot(list(face.normal_at()),delta))<1e-7: continue
            if face.geom_type==b.GeomType.CYLINDER:
                c=BRepAdaptor_Surface(face.wrapped,True).Cylinder();v=c.Axis().Direction();direction=[v.X(),v.Y(),v.Z()]
                # Axially translated cylindrical side walls add no volume;
                # their planar end annuli provide the exact material sweep.
                if abs(abs(dot(direction,delta))-math.sqrt(dot(delta,delta)))<1e-7:continue
            try:
                q=b.Solid.extrude(face,tuple(delta))
                if q.is_valid and abs(q.volume)>1e-8:sweep.append(q)
                elif face.geom_type!=b.GeomType.PLANE:
                    fb=face.bounding_box();fm,fx=list(fb.min),list(fb.max)
                    bound=box([min(fm[i],fm[i]+delta[i]) for i in range(3)],[max(fx[i],fx[i]+delta[i]) for i in range(3)])
                    if any(overlap(bound,parts[z])>EPS for z in candidates):bad=True
            except Exception:
                fb=face.bounding_box();fm,fx=list(fb.min),list(fb.max)
                bound=box([min(fm[i],fm[i]+delta[i]) for i in range(3)],[max(fx[i],fx[i]+delta[i]) for i in range(3)])
                if any(overlap(bound,parts[z])>EPS for z in candidates):bad=True
        if bad:incomplete.append(ident)
        for z in candidates:
            v=max(overlap(q,parts[z]) for q in sweep)
            if v>EPS:hits.append(dict(moving=ident,obstacle=z,swept_intersection_mm3=v))
    return dict(delta_mm=delta,collisions=hits,coverage='continuous face-prism sweep' if not incomplete else 'incomplete curved-face sweep',incomplete=incomplete,clear=not hits and not incomplete)

def main():
 from contract import D,OUT as DELIVERY
 m=read(DELIVERY/'manifest.json');assembly=b.import_step(DELIVERY/'closed.step');parts={s.label:s for s in assembly.children};meta={r['id']:r for r in m['rows']};fs=[];graph=Graph();fasteners=[]
 fs.append(finding('M-STEP','Gesamte Baugruppe','Alle exportierten Mitglieder aus STEP erneut einlesen und zuordnen','PASS'if set(parts)==set(meta)and assembly.is_valid else'FAIL',dict(expected_members=len(meta),actual_members=len(parts),solids=len(assembly.solids())),'Prüfung bezieht sich auf die exportierte Geometrie.'))
 parity=[]
 for ident,s in parts.items():
  old=b.import_brep(DELIVERY/'cad-cache'/(ident+'.brep'));bd=max(abs(x-y)for x,y in zip(s.bounding_box().min,old.bounding_box().min));dv=abs(volume(s)-volume(old));parity.append(dict(id=ident,bounds_delta_mm=bd,volume_delta_mm3=dv,passed=bd<.001 and dv<.001))
 fs.append(finding('M-EXPORT','STEP und Einzelkörper','Geometrische Zuordnung stimmt','PASS'if all(v['passed']for v in parity)else'FAIL',parity,'Keine Übernahme allein aus Bauteilnamen.'))
 for q in m['fasteners']:
  ident=q['id'];anchor=q['mate'];bearing=q['bearing'];kind=q['mechanism']
  if kind=='adjustment_clamp':
   a=axis_data(parts[ident]);a['thread_major_radius']=1.5;v=pilot_data(parts[anchor],a);gap=parts[ident].distance_to(parts[q['tip_member']]);headgap=v.get('entry',0)
   result=dict(**a,**v,tip_gap_mm=gap,head_travel_mm=headgap,status='PASS'if v['engagement']>4.5 and headgap>.3 and gap<.02 else'FAIL',mechanism='adjustment screw transfers force through tip and printed thread; head deliberately not seated')
  else:result=fastener_measure(parts[ident],parts[anchor],parts[bearing],1.5)
  result.update(id=ident,anchor=anchor,bearing=bearing,joined_parts=[q['clamped_member'],anchor],restrained='separation plus positive keeper features; preload/real loads unmeasured');fasteners.append(result)
  fs.append(finding('M-'+ident,ident,'Tatsächlicher Eingriff, Auflage oder Spannweg und freier Schraubenweg',result['status'],result,'Nominale Geometrie; Schraubhalt und Anzugsmoment sind reale Eigenschaften.'))
  if result['status']=='PASS':graph.add(q['clamped_member'],ident);graph.add(ident,anchor)
 # Contact-based bounded retention tests, followed by genuine release after named screws.
 motions=[];obstacles=[r['id']for r in m['rows']if r['kind']not in['hood','hood_screw','wire']]
 for k in m['keepers']:
  member,keeper=k['member'],k['keeper'];s=parts[member];tests=[]
  for axis in range(3):
   for sign in[-1,1]:
    delta=[0.,0.,0.];delta[axis]=sign*1.0
    tests.append(dict(delta=delta,overlap_mm3=overlap(s.moved(b.Location(tuple(delta))),parts[keeper])))
  lift=next(v for v in tests if v['delta']==[0.,0.,1.]);held=lift['overlap_mm3']>.001
  # Base/header contact may provide the opposite stop; absence isn't a general escape proof.
  fs.append(finding('M-CAPTURE-'+member,member,'Verschraubte Fanggeometrie stoppt gerades Abheben','PASS'if held else'OPEN',dict(keeper=keeper,translations=tests,source='STEP bodies'), 'Nachgewiesene Richtung nennen; begrenzte Bewegungsproben sind kein vollständiger6D-Festigkeitsnachweis.'))
  if held:graph.add(member,keeper)
  center=s.bounding_box().center();rotations=[]
  for axis in[(1,0,0),(0,1,0),(0,0,1)]:
   for angle in[-3,3]:rotations.append(dict(axis=axis,angle_deg=angle,overlap_mm3=overlap(s.rotate(b.Axis(center,axis),angle),parts[keeper])))
  if member.endswith('_MATE'):
   sid=member[:-5];fixed=[i for i in obstacles if i not in[member,keeper]and not i.startswith('SCREW_KEY_'+sid)and i!='SCREW_PLUG_'+sid]
   moving=[member,keeper,'SCREW_PLUG_'+sid];extract=moving_path(parts,moving,fixed,[0,10,0]);shifted={**parts,**{i:parts[i].moved(b.Location((0,10,0)))for i in moving}};up=moving_path(shifted,moving,fixed,[0,0,15])
  else:
   moving=[member]
   if member=='U1':moving+=['U1_USB_C','U1_MCU']
   elif member=='U2':moving+=['U2_IC']
   elif member=='U4_PCB':moving+=[i for i in parts if i.startswith('U4_')and i!='U4_PCB']
   fixed=[i for i in obstacles if i not in moving and i!=keeper and not any(i=='SCREW_'+a or i==a for a in k['anchors'])]
   extract=moving_path(parts,moving,fixed,[0,0,30]);up=None
  motions.append(dict(member=member,rotation_samples=rotations,release=extract,lift_after_rearward_release=up))
  clear=extract['clear']and(up is None or up['clear'])
  fs.append(finding('M-RELEASE-'+member,member,'Entnahme nach Lösen der vorgesehenen Schrauben','PASS'if clear else'FAIL'if extract['collisions']or(up and up['collisions'])else'OPEN',dict(release=extract,lift=up),'Kaufteilkontakte und reale Kabel werden gesondert verglichen.'))
 for ident in['B1_STRIPBOARD','U1','U2','U4_PCB','P_HOOD','P_USB_CAP']:
  path=graph.path(ident,'P_BASE');fs.append(finding('M-LOADPATH-'+ident,ident,'Geometrischer Halteweg bis zum Grundträger','PASS'if path else'OPEN',dict(path=path),'Ein Halteweg belegt noch keine Materialfestigkeit oder Vorspannung.'))
 fs.extend([finding('M-MOTION-LIMITS','Nano/DAC/Wandler/Stecker','Beliebige kombinierte Kipp-/Dreh-/Ausziehbewegungen','OPEN',dict(coverage='6 one-axis1mm translations, ±3degree rotations, continuous specified release paths; purchase leads nominal/not modeled'),'Keine pauschale6D-Haltezusage; reale Steckkontakte und Zug-/Kippversuch empfohlen.',scope='physical'),finding('M-GLANDS','Kabelverschraubungen und Schraubklemmen','Reale Kabel werden mechanisch gehalten','OPEN',m['cable_anchors'],'Verschraubte Sättel mit0.5mm Spannweg vorhanden; Mantelform/Durchmesser und Zugkraft nicht gemessen.',scope='physical'),finding('M-REAL','PETG und Einbau','Reale Passung und Dauerhalt bei50C plus Eigenerwärmung','OPEN',dict(missing=['Drucktoleranzen','Schraubmoment/Reibung','Lasten/Vibration','PETG-Kriechen','Untergrund und äußere Schrauben']),'Empfohlene reale Prüfungen; keine automatische Ausliefersperre.',scope='physical')])
 printsteps=[]
 for item in m['prints']:
  s=b.import_step(DELIVERY/'prints'/(item['id']+'.step'));bb=s.bounding_box();original=parts[item['id']];kind=meta[item['id']]['kind']
  if kind=='hood':original=original.rotate(b.Axis.X,180)
  if kind=='usb_cap':original=original.rotate(b.Axis.X,90)
  original=original.moved(b.Location(tuple(-v for v in original.bounding_box().min)))
  difference=volume(original.cut(s))+volume(s.cut(original))
  printsteps.append(dict(id=item['id'],valid=bool(s.is_valid),solids=len(s.solids()),bounds=[list(bb.min),list(bb.max)],volume_mm3=volume(s),assembly_symmetric_difference_mm3=difference,parity_status='PASS'if difference<.002 else'FAIL',explicit_mm='SI_UNIT(.MILLI.,.METRE.)'in(DELIVERY/'prints'/(item['id']+'.step')).read_text(),orientation=item['orientation']))
 fs.append(finding('M-PRINT-PARITY','Alle Druckteile','Einzel-STEP entspricht der Baugruppe nach dokumentierter Druckdrehung','PASS'if all(p['valid']and p['solids']==1 and p['explicit_mm']and p['parity_status']=='PASS'for p in printsteps)else'FAIL',printsteps,'Prüft Materialdifferenz und Einheiten; reale Druckpassung bleibt gesondert.'))
 report('mechanics',fs,fasteners=fasteners,print_steps=printsteps,motions=motions,graph={k:sorted(v)for k,v in graph.adj.items()},STEP_sha256=sha(DELIVERY/'closed.step'))
if __name__=='__main__':main()

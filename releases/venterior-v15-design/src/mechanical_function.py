"""Functional inspection of exported nominal solids, independent of model.make.

No owner-based collision exemptions. Declared screw targets and joints are
requirements, never proof. Physical thread strength remains OPEN.
"""
import json, math, os
from importlib import metadata
for key in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS'):
    os.environ[key]='1'
import build123d as b
from OCP.BRepAdaptor import BRepAdaptor_Surface
from OCP.OSD import OSD_ThreadPool
from common import ROOT, OUT, read, save
from plausibility_common import AUDIT, CONTRACT, finding, report

OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
EPS=1e-5

def volume(s):
    if s is None:return 0.0
    if isinstance(s,b.ShapeList):return sum(volume(x) for x in s)
    return sum(abs(x.volume) for x in s.solids())

def bbox(s):
    q=s.bounding_box();return list(q.min),list(q.max)

def overlaps(a,c):
    lo,hi=bbox(a);ll,hh=bbox(c)
    return all(min(hi[i],hh[i])-max(lo[i],ll[i])>EPS for i in range(3))

def cylinder(x,y,z,r,h,direction=1):
    return b.Solid.make_cylinder(r,h,b.Plane(origin=(x,y,z),z_dir=(0,0,direction)))

def bearing_area(shape,axis,direction,inner=1.7,outer=3):
    # A thin annulus just below the head checks material that the head bears on.
    x,y,z=axis;z+=direction*.001
    ring=cylinder(x,y,z,outer,.02,direction).cut(cylinder(x,y,z,inner,.02,direction))
    return volume(shape.intersect(ring))/.02

def pilot_intervals(target,axis,direction,radius=1.15):
    x,y,z=axis;intervals=[]
    for f in target.faces():
        if f.geom_type!=b.GeomType.CYLINDER:continue
        c=BRepAdaptor_Surface(f.wrapped,True).Cylinder();p=c.Location();v=c.Axis().Direction()
        if abs(abs(v.Z())-1)>1e-6 or math.hypot(p.X()-x,p.Y()-y)>1e-4 or abs(c.Radius()-radius)>.03:continue
        lo,hi=bbox(f);tt=sorted([direction*(lo[2]-z),direction*(hi[2]-z)])
        intervals.append(tt)
    intervals.sort();merged=[]
    for lo,hi in intervals:
        if merged and lo<=merged[-1][1]+.001:merged[-1][1]=max(merged[-1][1],hi)
        else:merged.append([lo,hi])
    return merged

def nominal_shaft_present(screw,hardware):
    x,y,z=screw['axis'];envelope=cylinder(x,y,z,1.45,screw['length'],screw['direction'])
    missing=volume(envelope)-volume(hardware.intersect(envelope))
    return dict(passed=missing<.001,missing_shaft_mm3=max(0,missing))

def material_after_pilot(target,axis,direction,depth):
    if depth is None:return None
    x,y,z=axis;start=z+direction*(depth+.002)
    hit=target.intersect(cylinder(x,y,start,.2,30,direction));intervals=[]
    chunks=list(hit) if isinstance(hit,b.ShapeList) else [hit] if hit else []
    for chunk in chunks:
        for s in chunk.solids():
            lo,hi=bbox(s);intervals.append(sorted([direction*(lo[2]-start),direction*(hi[2]-start)]))
    end=0
    for lo,hi in sorted(intervals):
        if lo>end+.01:break
        end=max(end,hi)
    return round(end+.002,5) if end else 0

def planar_contact_area(a,c):
    """Positive-area horizontal support, rather than mere bounding-box contact."""
    af=[];cf=[]
    for shape,out in [(a,af),(c,cf)]:
        for f in shape.faces():
            if f.geom_type!=b.GeomType.PLANE:continue
            n=f.normal_at()
            if abs(n.Z)>.999999:out.append((f,n.Z,bbox(f)))
    area=0
    for f,n,(lo,hi) in af:
        for g,m,(ll,hh) in cf:
            if n*m>-.99 or abs(lo[2]-ll[2])>.001:continue
            if any(min(hi[i],hh[i])-max(lo[i],ll[i])<=EPS for i in (0,1)):continue
            hit=f.intersect(g)
            if hit:
                if isinstance(hit,b.ShapeList):area+=sum(x.area for x in hit)
                else:area+=sum(x.area for x in hit.faces())
    return area

def screw_geometry(screw,target,clamped,minimum,physical_open=False,compliant_contact=None):
    axis=screw['axis'];direction=screw['direction'];length=screw['length']
    area=bearing_area(clamped[0],axis,direction)
    interfaces=[planar_contact_area(a,c) for a,c in zip(clamped,clamped[1:]+[target])]
    intervals=pilot_intervals(target,axis,direction)
    forward=[(max(0,lo),hi) for lo,hi in intervals if hi>0]
    usable=sum(max(0,min(length,hi)-lo) for lo,hi in forward)
    deepest=max((hi for lo,hi in forward),default=None)
    clearance=None if deepest is None else deepest-length
    thread_material=[]
    for lo,hi in forward:
        a=max(0,lo+.001);z=min(length,hi-.001)
        if z<=a:continue
        x,y,head=axis
        outer=cylinder(x,y,head+direction*a,1.45,z-a,direction)
        inner=cylinder(x,y,head+direction*a,1.151,z-a,direction)
        ring=outer.cut(inner)
        thread_material.append(volume(target.intersect(ring))/volume(ring))
    issues=[]
    if area<.1:issues.append('NO_HEAD_BEARING')
    bridge=None if compliant_contact is None else compliant_contact['interface_index']
    if any(x<.1 for i,x in enumerate(interfaces) if i!=bridge):issues.append('NO_POSITIVE_AREA_SUPPORT')
    if bridge is not None and not compliant_contact['passed']:issues.append('NO_COMPLIANT_LOAD_PATH')
    if not forward:issues.append('NO_COAXIAL_PILOT')
    if thread_material and min(thread_material)<.95:issues.append('NO_CONTINUOUS_THREAD_MATERIAL')
    if minimum is not None and usable+.001<minimum:issues.append('INSUFFICIENT_ENGAGEMENT')
    if clearance is not None and clearance<-.001:issues.append('BLIND_BOTTOM_OVERRUN')
    state='OPEN' if physical_open else 'FAIL' if issues else 'PASS'
    return dict(status=state,issues=issues,head_bearing_mm2=round(area,5),
                support_interfaces_mm2=[round(x,5) for x in interfaces],
                pilot_intervals_from_head_mm=intervals,
                usable_engagement_mm=round(usable,5),required_engagement_mm=minimum,
                pilot_tip_clearance_mm=None if clearance is None else round(clearance,5),
                thread_material_fractions=thread_material,
                compliant_contact=compliant_contact,
                remaining_axial_material_at_pilot_end_mm=material_after_pilot(target,axis,direction,deepest))

def continuous_escape(moving,fixed,vector):
    """A collision-free conservative box sweep proves this one escape path.

    Colliding sweeps only mean no counterexample here; they never prove that
    every possible escape is blocked. Does not invent friction or joint locks.
    """
    if sum(abs(x)>EPS for x in vector)!=1:raise ValueError('Axial path required')
    axis=next(i for i,x in enumerate(vector) if abs(x)>EPS);hits=[]
    for name,shape in moving.items():
        lo,hi=bbox(shape);lo[axis]+=min(0,vector[axis]);hi[axis]+=max(0,vector[axis])
        sweep=b.Solid.make_box(*(hi[i]-lo[i] for i in range(3)),b.Plane(origin=lo))
        for id,obstacle in fixed.items():
            if not overlaps(sweep,obstacle):continue
            amount=volume(sweep.intersect(obstacle))
            if amount>EPS:hits.append(dict(moving=name,fixed=id,intersection_mm3=round(amount,6)))
    return dict(escape_proved=not hits,vector_mm=vector,obstructions=hits,
                method='continuous conservative per-body AABB prism; empty intersection proves an escape, intersection is inconclusive')

def prismatic_escape(moving,fixed,travel):
    """Exact +Z boundary sweep for axial prismatic keeper geometry."""
    pieces=[];hits=[]
    for name,s in moving.items():
        pieces.append((name,s))
        for face in s.faces():
            if face.geom_type==b.GeomType.PLANE:
                if face.normal_at().Z>.999999:pieces.append((name,b.Solid.extrude(face,(0,0,travel))))
            elif face.geom_type==b.GeomType.CYLINDER:
                if abs(BRepAdaptor_Surface(face.wrapped,True).Cylinder().Axis().Direction().Z())<.999999:
                    raise ValueError('Non-axial curved keeper surface')
            else:raise ValueError('Unsupported prismatic keeper surface')
    for name,q in pieces:
        for id,t in fixed.items():
            if overlaps(q,t):
                v=volume(q.intersect(t))
                if v>EPS:hits.append(dict(moving=name,fixed=id,intersection_mm3=v))
    return dict(escape_proved=not hits,vector_mm=[0,0,travel],obstructions=hits,method='initial shape plus continuous extrusion of every upward planar face; parallel cylindrical holes')

def root_paths(edges,root):
    paths={root:[root]};pending=[root]
    while pending:
        a=pending.pop(0)
        for x,y in edges:
            other=y if x==a else x if y==a else None
            if other is not None and other not in paths:paths[other]=paths[a]+[other];pending.append(other)
    return paths

def cable_grip(parts,datum,requirements):
    """An analytic cable is a check probe, not a delivered 3D conductor."""
    x,y,z=datum['virtual_cable_origin'];diameter=datum['nominal_cable_mm']
    probe=b.Solid.make_cylinder(diameter/2,datum['virtual_cable_length_mm'],b.Plane(origin=(x,y,z),z_dir=(1,0,0)))
    lower=parts[datum['lower_carrier']];upper=parts[datum['upper_carrier']]
    gap=bbox(upper)[0][2]-bbox(lower)[1][2];contacts=[];radii=[]
    for id in datum['jaws']:
        contacts.append(dict(jaw=id,nominal_interference_mm3=volume(parts[id].intersect(probe))))
        for face in parts[id].faces():
            if face.geom_type!=b.GeomType.CYLINDER:continue
            c=BRepAdaptor_Surface(face.wrapped,True).Cylinder();p=c.Location()
            if abs(c.Axis().Direction().X())>.999999 and abs(p.Y()-y)<.001 and abs(p.Z()-z)<.001:radii.append(c.Radius())
    bore=2*max(radii) if len(radii)>=2 else None
    seating=[planar_contact_area(upper,parts[datum['jaws'][1]]),planar_contact_area(parts[datum['jaws'][0]],lower)]
    axial_capture={id:{str(sign):directional_witness({id:parts[id]},{'carrier':carrier},[sign,0,0])for sign in[-1,1]}
                   for id,carrier in zip(datum['jaws'],[lower,upper])}
    passed=(bore is not None and diameter-bore>=requirements['required_nominal_interference_mm']-.001
            and gap>=requirements['required_closing_gap_mm']-.001 and all(r['nominal_interference_mm3']>EPS for r in contacts) and all(x>.1 for x in seating)
            and all(w['blocked']for pair in axial_capture.values()for w in pair.values()))
    return dict(passed=passed,interface_index=1,assumed_cable_od_mm=diameter,model_bore_mm=bore,
                closing_gap_mm=gap,jaw_contacts=contacts,jaw_seating_mm2=seating,axial_jaw_capture=axial_capture,
                actual_pull_force='OPEN',actual_cable_od='OPEN',scope='Nominal interference and non-bottoming closing travel, not a material/force simulation')

def directional_witness(moving,stops,vector):
    for distance in [.25,.5,1,2,3,4,5,8,10,20,35]:
        shift=[distance*x for x in vector]
        for name,s in moving.items():
            q=s.moved(b.Location(shift))
            for id,t in stops.items():
                if overlaps(q,t):
                    v=volume(q.intersect(t))
                    if v>EPS:return dict(blocked=True,travel_mm=distance,moving=name,stop=id,intersection_mm3=v)
    return dict(blocked=False)

def main():
    c=read(OUT/'data/assembly.json');contract=read(CONTRACT)['mechanical'];rows=[]
    tree=b.import_step(OUT/'assemblies/Venterior-V15-closed.step')
    parts={x.label:x for x in tree.descendants if not x.children}
    printed={m['id']:b.import_brep(OUT/'brep'/(m['id']+'.brep')) for m in c['parts'] if m['kind']=='print' and m.get('assembled') is not False}
    parity=[]
    for id,s in printed.items():
        a,z=bbox(s);aa,zz=bbox(parts[id]);error=max(abs(x-y) for x,y in zip(a+z,aa+zz))
        ve=abs(volume(s)-volume(parts[id]))
        parity.append(dict(part=id,bounds_error_mm=error,volume_error_mm3=ve,passed=error<.001 and ve<.001))
    rows.append(finding('EXPORT_PARITY','PASS' if all(x['passed'] for x in parity) else 'FAIL','all assembled printed parts',
        'STEP and BRep nominal print solids agree in position, volume and bounds',parity,'Exportlagen und Druckkörper stimmen in diesen Vergleichsgrößen überein; keine vollständige Konturgleichheitsbehauptung.'))
    parts.update(printed);meta={r['id']:r for r in c['parts']};fast={f['id']:f for f in c['fasteners']}
    edges=[];thread_permissions={};contracts=[];hardware_bindings=[]
    for id,f in fast.items():
        rule=next((r for r in contract['fastener_rules'] if id.startswith(r['prefix'])),None)
        if rule is None:
            rows.append(finding('UNCONTRACTED_'+id,'OPEN',id,'Every fastener has an intended clamping purpose',{},'Befestigungszweck ist nicht definiert.'));continue
        idx=id.rsplit('_',1)[-1];clamps=rule.get('clamped',[rule.get('clamped_pattern','').format(index=idx)])
        target=rule['target'];record=dict(fastener=id,clamped=clamps,target=target,loose_adjustment_mm=rule.get('loose_adjustment_mm',0),tightened_requirement='No separation; lateral friction strength requires actual torque/material qualification')
        contracts.append(record)
        if f['target']!=target:
            rows.append(finding('TARGET_'+id,'FAIL',id,'Declared and required targets agree',record,'Schraube adressiert das falsche Teil.'));continue
        bridge=cable_grip(parts,c['usb']['clamp'],contract['usb_clamp'])if 'compliant_interface_index'in rule else None
        result=screw_geometry(f,parts[target],[parts[x] for x in clamps],rule['minimum_mm'],rule.get('physical_dimensions')=='OPEN',bridge)
        if result['pilot_tip_clearance_mm'] is not None and not rule.get('physical_dimensions') and result['pilot_tip_clearance_mm']<contract['minimum_pilot_tip_clearance_mm']-.001:
            result['status']='FAIL';result['issues'].append('INSUFFICIENT_TIP_CLEARANCE')
        if rule.get('minimum_back_material_mm')is not None:
            remaining=result['remaining_axial_material_at_pilot_end_mm']
            if remaining is None or remaining<rule['minimum_back_material_mm']-.001:
                result['status']='FAIL';result['issues'].append('INSUFFICIENT_CLOSED_BACK_MATERIAL')
        match=nominal_shaft_present(f,parts[id]);hardware_bindings.append(dict(fastener=id,**match))
        if not match['passed']:result['status']='FAIL';result['issues'].append('EXPORTED_SHAFT_DIFFERS_FROM_DECLARATION')
        if result['status']=='PASS':
            edges += list(zip(clamps,clamps[1:]+[target]))
        x,y,z=f['axis'];thread_permissions[id]=(target,cylinder(x,y,z,1.451,f['length'],f['direction']))
        rows.append(finding('FASTENER_'+id,result['status'],id,
            'Head bearing, continuous material support, sufficient coaxial engagement and no nominal blind-bottom overrun',result,
            'Nominale Verschraubungsgeometrie erfüllt die geprüften Bedingungen; reale Tragfähigkeit offen.' if result['status']=='PASS' else 'Originalschraube und Originalbosse müssen vermessen werden.' if result['status']=='OPEN' else 'Nominale Verschraubung verletzt eine Befestigungsbedingung; Schraubenartikel und Geometrie vor Aufbau klären.'))
    rows.append(finding('EXPORTED_HARDWARE','PASS' if all(x['passed'] for x in hardware_bindings) else 'FAIL','all screw shafts',
        'The declared screw axis, length and nominal diameter must be present in the exported STEP',hardware_bindings,'Schraubenschäfte wurden gegen die tatsächlich exportierte Geometrie geprüft.'))
    # Every hardware/other-body intersection is inspected, including same owner.
    allowed=[];unexpected=[]
    for id,(target,envelope) in thread_permissions.items():
        s=parts[id]
        for other,t in parts.items():
            if other==id or meta[other].get('assembled') is False or not overlaps(s,t):continue
            hit=s.intersect(t);amount=volume(hit)
            if amount<EPS:continue
            hits=list(hit) if isinstance(hit,b.ShapeList) else [hit]
            permitted=sum(volume(h.intersect(envelope)) for h in hits) if other==target else 0
            entry=dict(fastener=id,other=other,intersection_mm3=round(amount,6),permitted_thread_mm3=round(permitted,6))
            if amount-permitted>EPS:unexpected.append(entry)
            else:allowed.append(entry)
    rows.append(finding('HARDWARE_CONTACTS','FAIL' if unexpected else 'PASS','all fasteners',
        'Only the nominated shaft/target engagement may overlap; no owner-wide exclusions',dict(unexpected=unexpected,expected_thread_contacts=allowed),
        'Unerwartete Schraubenkollisionen müssen geprüft werden.' if unexpected else 'Alle untersuchten Schraubenkontakte sind lokal als Gewindeeingriff zugeordnet; Sacklochprüfung erfolgt separat.'))
    for r in contract['retention']:
        paths=[]
        fixed={id:s for id,s in parts.items() if id not in r['moving'] and meta[id].get('assembled') is not False}
        for i in range(3):
            for sign in (-1,1):
                v=[0,0,0];v[i]=sign*r['travel_mm']
                paths.append(continuous_escape({x:parts[x] for x in r['moving']},fixed,v))
        escapes=[p for p in paths if p['escape_proved']]
        housing={x:parts[x]for x in r['moving']if meta[x]['kind']!='component_pin'}
        witnesses={str(v):directional_witness(housing,{id:parts[id]for id in[r['holder'],r['insert'],r['cap']]},v)for v in[[-1,0,0],[1,0,0],[0,-1,0],[0,1,0],[0,0,-1],[0,0,1]]}
        insert_contact=planar_contact_area(parts[r['insert']],parts[r['cap']])
        cap_fasteners=[next((x for x in rows if x['id']=='FASTENER_'+id),{}) .get('status') for id in r['cap_screws']]
        anchored=r['holder']in root_paths(edges,contract['root'])
        nominal=not escapes and all(w['blocked']for w in witnesses.values())and insert_contact>.1 and all(x=='PASS'for x in cap_fasteners)and anchored
        evidence=dict(paths=paths,moving_together=r['moving'],housing_contact_witnesses=witnesses,insert_cap_contact_mm2=insert_contact,cap_fasteners=cap_fasteners,cage_anchored_to_base=anchored)
        rows.append(finding('RETENTION_'+r['id'],'PASS'if nominal else'FAIL',r['id'],r['required'],evidence,
            'Nominale Gehäuseanschläge und ihr verschraubter Verbindungsweg sind für die sechs geprüften Richtungen belegt; reale Passung bleibt offen.' if nominal else 'Haltewirkung oder Verbindungsweg ist nicht vollständig nachgewiesen.'))
        # A working test must detect an absent stop AND an unfastened keeper.
        without_insert={id:s for id,s in fixed.items()if id!=r['insert']}
        missing=continuous_escape({x:parts[x]for x in r['moving']},without_insert,[0,35,0])
        lifted={id:parts[id]for id in[r['insert'],r['cap']]}
        stage1_fixed={id:s for id,s in parts.items()if id not in lifted and id not in r['cap_screws']and meta[id].get('assembled')is not False and meta[id]['owner']not in('HOOD','FRONT')}
        first=prismatic_escape(lifted,stage1_fixed,35)
        service_fixed={id:s for id,s in stage1_fixed.items()if id not in r['moving']}
        second=continuous_escape({id:parts[id]for id in r['moving']},service_fixed,[0,3,0])
        third=continuous_escape({id:parts[id].moved(b.Location((0,3,0)))for id in r['moving']},service_fixed,[0,0,35])
        released=dict(escape_proved=all(x['escape_proved']for x in[first,second,third]),stages=[first,second,third],reason='Open keeper, pull insulated tails clear of rear slots, then lift housing; original front removed for internal service')
        rows.append(finding('KEEPER_NEGATIVES_'+r['id'],'PASS'if missing['escape_proved']and released['escape_proved']else'FAIL',r['id'],
            'Absent front stop or released cap exposes a removable connector group',dict(missing_stop=missing,released_keeper=released),
            'Fehlender Anschlag und gelöster Bügel werden als Verlust der Haltewirkung erkannt; Entnahme bei geöffneter Baugruppe bleibt möglich.'))
        rows.append(finding('CONNECTOR_FIT_'+r['id'],'OPEN',r['id'],'Actual YC100 body, contact faces and approved envelope fit are confirmed',dict(capacity_mm=[26,30,24],actual_contact_faces='unmeasured',rear_component_space='unmeasured'if r['id']=='X6V'else'not under original PCB',cage_top_z_mm=bbox(parts[r['cap']])[1][2],original_pcb_bottom_z_mm=c['parameters']['front']['origin'][2]+c['parameters']['front']['pcb_offset'][2]if r['id']=='X6V'else None),
            'Die innere Aufnahme lässt nominal 9,5mm unter der Originalplatine frei; das unterschreitet die frühere unbestätigte 20mm-Bauteilreserve. Rückseitige Bauteile und echte Steckerflächen müssen vor Druckfreigabe vermessen werden.'if r['id']=='X6V'else'Käfig und Einsätze sind nominal konstruiert; echte Steckerflächen müssen vor Druckfreigabe vermessen werden.'))
    grip=cable_grip(parts,c['usb']['clamp'],contract['usb_clamp'])
    rows.append(finding('USB_CABLE_GRIP','PASS'if grip['passed']else'FAIL','USB_CLAMP_1/2',
        'Cable strain relief needs a demonstrated gripping/retaining mechanism',
        grip,'Nomineller Backeneingriff und verbleibender Schließweg sind geprüft; tatsächliche Zugkraft und Mantelverträglichkeit bleiben offen.'))
    rows.append(finding('STRENGTH','OPEN','all printed joints','Service loads, screw article, torque, printed material and tolerances must be qualified',contract['strength_inputs'],'Geometrieprüfung ersetzt keinen Auszieh-, Drehmoment- oder Langzeitnachweis.'))
    slot_checks=[]
    for idx in range(1,5):
        adapter=parts['FRONT_ADAPTER_'+str(idx)]
        for id,axis,travel in [('ADAPTER_SCREW_'+str(idx),1,3),('ORIGINAL_SCREW_'+str(idx),0,6)]:
            f=fast[id]
            for shift in (-travel,0,travel):
                v=[0,0,0];v[axis]=shift;plate=adapter.moved(b.Location(v))
                area=bearing_area(plate,f['axis'],f['direction']);x,y,z=f['axis']
                clearance=volume(plate.intersect(cylinder(x,y,z,1.45,4,f['direction'])))
                slot_checks.append(dict(fastener=id,relative_plate_shift_mm=v,head_bearing_mm2=area,shaft_collision_mm3=clearance,passed=area>.1 and clearance<EPS))
    rows.append(finding('SLOT_END_BEARING','PASS' if all(x['passed'] for x in slot_checks) else 'FAIL','FRONT_ADAPTER_1..4',
        'Nominal screw shank clearance and head bearing at both slot limits and centre',slot_checks,
        'Schraubenkopfauflage und Schaftfreiheit sind an diesen nominalen Verstellpositionen geprüft; gemeinsame Bewegung, reale Schale und Klemmkraft bleiben offen.'))
    rows.append(finding('ADJUSTMENT_AND_ASSEMBLY','OPEN','FRONT_ADAPTER_1..4',
        'Adjustment before tightening and retention afterwards must both work',
        dict(loose_slots='Source declares 12mm/6mm travel; nominal centre pose inspected',tightened='Bearing/support/thread checks above',slot_extremes='Unmeasured original shell; not qualified',arbitrary_rotations='Not exhaustively searched'),
        'Schalenmaße und Randstellungen müssen vor Freigabe geprüft werden; kein pauschales Festsetzen im CAD als Nachweis.'))
    paths=root_paths(edges,contract['root'])
    save(AUDIT/'mechanical-contracts.json',dict(connections=contracts,root_paths_from_passed_geometry=paths,edges=edges,physical_load_path='OPEN'))
    result=report('mechanical',rows,tools=dict(build123d=b.__version__,ocp=metadata.version('cadquery-ocp-novtk'),gui=False,threads=2),scope='Exported STEP/BRep; nominal bearing/support and cylindrical pilots; conservative axial escape sweeps. Actual threads, friction and arbitrary motions not certified.')
    save(AUDIT/'mechanical.json',result)
    print('Mechanical functional audit',result['status'],result['counts'],flush=True)

if __name__=='__main__':main()

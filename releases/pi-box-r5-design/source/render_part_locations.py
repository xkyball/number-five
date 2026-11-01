"""Read-only matching colors/IDs for the 31 bed bodies and installed decks."""
import sys, math, argparse, csv, zipfile, xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
from common import ROOT, BASE, read, save, sha

OUT = ROOT / 'previews/teile-zuordnung'
BED = ROOT / 'print-rest/Bett-01'
COLORS = [
    '#3366cc', '#dc3912', '#ff9900', '#109618', '#990099', '#0099c6',
    '#dd4477', '#66aa00', '#b82e2e', '#316395', '#994499', '#22aa99',
    '#aaaa11', '#6633cc', '#e67300', '#8b0707', '#651067', '#329262',
    '#5574a6', '#3b3eac', '#b77322', '#16b840', '#b91383', '#f4359e',
    '#9c5935', '#a9c413', '#2a778d', '#668d1c', '#bea413', '#0c5922',
    '#743411',
]


def catalog():
    result = []
    def add(bed_id, instance, level, label, **extra):
        number = len(result) + 1
        result.append(dict(number=number, bed_id=bed_id, instance=instance,
                           level=level, label=label, color=COLORS[number-1], **extra))
    owners = ['PI_U1', 'PI_U2', 'PI_U3', 'HUB_U1', 'HUB_U2']
    names = ['Pi-Wandler 1', 'Pi-Wandler 2', 'Pi-Wandler 3', 'Hub-Wandler 1', 'Hub-Wandler 2']
    for i, (owner, name) in enumerate(zip(owners, names), 1):
        add(f'CONVERTER_ADAPTER_{i:02}', owner+'_ADAPTER', 'supply', 'Adapter '+name)
    d = read(ROOT / 'design.json')
    for i, (owner, name, y) in enumerate(zip(owners, names, d['converters']['rows'])):
        for j, (x, yy) in enumerate(d['converter_adapter']['board_mounts_local_cad']):
            add(f'INSULATING_WASHER_{2*i+j+1:02}', owner+f'_INSULATOR{j+1}',
                'supply', f'Isolierscheibe {name} '+('links' if j == 0 else 'rechts'),
                install_xyz=[20+x, y+yy, 20.2])
    for i, label in enumerate(['vorn links', 'vorn rechts', 'hinten links', 'hinten rechts'], 1):
        add(f'STACK_RISER_{i:02}', f'STACK_RISER_{i}', 'supply', 'Ebenenverbinder '+label)
    for typ, level, label in [
        ('PI_A_CONNECTOR_CARRIER', 'supply', 'Steckerträger Pi-Versorgung'),
        ('HUB_A_CONNECTOR_CARRIER', 'supply', 'Steckerträger Hub-Versorgung'),
        ('INPUT_A_CONNECTOR_CARRIER', 'supply', 'Steckerträger Eingangsschutz'),
        ('HUB_FUSE_CARRIER_1', 'supply', 'Träger Hub-Sicherung 1'),
        ('HUB_FUSE_CARRIER_2', 'supply', 'Träger Hub-Sicherung 2'),
        ('GPIO_A_CONNECTOR_CARRIER', 'devices', 'Steckerträger GPIO-Adapter'),
        ('HUB_LIFT_1', 'devices', 'Hub-Sockel vorn mit Kabelbogen'),
        ('HUB_LIFT_2', 'devices', 'Hub-Sockel hinten'),
        ('POWER_KEY_SHROUD', 'devices', 'Power-Steckeraufnahme'),
        ('POWER_PLUG_COLLAR', 'devices', 'Power-Gegenhülse'),
        ('CAN_KEY_SHROUD', 'devices', 'CAN-Steckeraufnahme'),
        ('CAN_PLUG_COLLAR', 'devices', 'CAN-Gegenhülse'),
    ]:
        add(typ+'_01', typ, level, label)
    assert len(result) == 31 and len(set(COLORS)) == 31
    return result


def mesh():
    import build123d as b
    from OCP.OSD import OSD_ThreadPool
    sys.path.append('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/lib/python3.12/site-packages')
    import trimesh
    OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
    OUT.mkdir(parents=True, exist_ok=True)
    mech_path = ROOT / 'output/rest-mechanical.json'
    mechanical = read(mech_path)
    prep = read(BED / 'preparation.json')
    assert mechanical['source_sha256'] == prep['source_sha256'] == sha(ROOT / 'design.json')
    parts = catalog()
    assert {r['bed_id'] for r in parts} == {r['id'] for r in prep['parts']}
    rows = {r['id']: r for r in mechanical['rows']}
    arrays, members = {}, []
    def add_shape(key, row, number, **extra):
        p = Path(row['brep']['path'])
        assert sha(p) == row['brep']['sha256'], key
        vertices, faces = b.import_brep(p).tessellate(.06, .15)
        tri = np.array([tuple(v) for v in vertices])[np.asarray(faces)]
        arrays[key] = tri
        members.append(dict(key=key, number=number, level=row['level'], source=row['brep'], **extra))
    for part in parts:
        key = part['instance']
        if part['bed_id'].startswith('INSULATING_WASHER'):
            path = ROOT / 'delivery/print/INSULATING_WASHER.stl'
            raw = trimesh.load_mesh(path, process=False)
            # Exact inverse of export_adapters.py: X-rotation 180°, then the
            # insulator's installed datum at PCB top20.2. No new CAD geometry.
            tri = raw.triangles.copy()
            tri -= [3.5, 3.5, 1.0]
            tri *= [1, -1, -1]
            tri += part['install_xyz']
            assert np.allclose([tri[:,:,2].min(), tri[:,:,2].max()], [20.2, 21.2], atol=1e-6)
            arrays[key] = tri
            members.append(dict(key=key, number=part['number'], level='supply',
                                source=dict(path=str(path), sha256=sha(path)),
                                transform='Inverse qualified print frame; narrow foot to PCB, original installed datum20.2mm'))
        else:
            assert rows[key]['level'] == part['level']
            add_shape(key, rows[key], part['number'])
    for key in ['SUPPLY_TRAY', 'DEVICE_TRAY', 'HUB_BRIDGE1', 'HUB_BRIDGE2']:
        add_shape(key, rows[key], 0, context=True)
    ns = {'m': 'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'}
    with zipfile.ZipFile(BED / 'plate-source.3mf') as z:
        model = ET.fromstring(z.read('3D/3dmodel.model'))
    assert model.get('unit') == 'millimeter'
    objects = {r.get('id'): r for r in model.findall('m:resources/m:object', ns)}
    by_bed_id = {r['bed_id']: r for r in parts}
    for item in model.findall('m:build/m:item', ns):
        obj = objects[item.get('objectid')]
        name = obj.get('name')
        vertices = np.asarray([[float(v.get(k)) for k in 'xyz'] for v in obj.findall('m:mesh/m:vertices/m:vertex', ns)])
        faces = np.asarray([[int(f.get('v'+str(i))) for i in (1,2,3)] for f in obj.findall('m:mesh/m:triangles/m:triangle', ns)])
        # prepare_rest_beds writes world vertices, no transformed build items.
        assert item.get('transform') is None
        arrays['BED_'+name] = vertices[faces]
        record = next(r for r in prep['parts'] if r['id'] == name)
        assert np.allclose([vertices.min(0), vertices.max(0)], record['print_bounds_mm'])
        assert sha(record['stl']) == record['stl_sha256']
    target = OUT / 'geometry.npz'
    np.savez_compressed(target, **arrays)
    report = dict(revision='PI-2L-5', source_sha256=sha(ROOT/'design.json'),
                  mechanical_sha256=sha(mech_path), preparation_sha256=sha(BED/'preparation.json'),
                  gcode_sha256=sha(prep['gcode']), three_mf_sha256=sha(prep['three_mf']),
                  plate_source_sha256=sha(BED/'plate-source.3mf'),
                  geometry_cache_sha256=sha(target), parts=parts, members=members,
                  display='All31 real printed bodies in installed frames and the bound bed. Gray printed context. Purchased modules/hardware/wires omitted for visibility. No exploded offsets.',
                  all_bed_parts_mapped_once=True, print_files_modified=False,
                  pixel_review='PENDING')
    save(OUT/'binding.json', report)
    with (OUT/'farben-und-teile.csv').open('w') as f:
        writer = csv.DictWriter(f, fieldnames=['number','color','label','level','bed_id','instance'])
        writer.writeheader()
        writer.writerows({k:r[k] for k in writer.fieldnames} for r in parts)
    print('MESHED',len(members),'installed/context bodies;31 bed bodies',flush=True)


def render(populated=False):
    sys.path.insert(0, str(BASE/'delivery/source/presentation'))
    from make_layer_views import raster
    from PIL import Image, ImageDraw, ImageFont
    from scipy.ndimage import distance_transform_edt
    p = read(OUT/'binding.json')
    assert p['source_sha256'] == sha(ROOT/'design.json')
    assert p['mechanical_sha256'] == sha(ROOT/'output/rest-mechanical.json')
    assert p['geometry_cache_sha256'] == sha(OUT/'geometry.npz')
    cache = np.load(OUT/'geometry.npz')
    by_num = {r['number']: r for r in p['parts']}
    palette = np.asarray([[183,192,198]] + [[int(c[i:i+2],16) for i in (1,3,5)] for c in COLORS], np.float32)
    width, height = 3200, 2300
    area = [65, 245, 2325, 2080]
    font_path = '/System/Library/Fonts/Supplemental/Arial'
    def font(size, bold=False):
        return ImageFont.truetype(font_path+(' Bold' if bold else '')+'.ttf', size)
    configs = [
        ('bed','01-bett-farbzuordnung.png','Druckbett · alle 31 Teile',
         'Gleiche Farben und Nummern zeigen dasselbe Einzelteil in allen Ansichten.', [1.1,-1.7,2.5]),
        ('supply','02-untere-ebene-farbzuordnung.png','Untere Ebene · Einbau der neuen Teile',
         '24 neue Druckteile farbig · vorhandene Unterplatte grau · Kaufmodule ausgeblendet.',[1.1,-1.7,3.3]),
        ('devices','03-obere-ebene-farbzuordnung.png','Obere Ebene · Einbau der neuen Teile',
         '7 neue Druckteile farbig · vorhandene Oberplatte und Hub-Bügel grau · Kaufmodule ausgeblendet.',[1.1,-1.7,3.3]),
    ]
    if populated:
        import populate_part_locations as population
        configs = [
            ('supply','04-untere-ebene-bestueckt-transparent.png','Untere Ebene · transparent bestückt',
             'Druckteile: gleiche Farben und Nummern · Bestückung transparent mit kurzen Beschriftungen.',[1.1,-1.7,3.3]),
            ('devices','05-obere-ebene-bestueckt-transparent.png','Obere Ebene · transparent bestückt',
             'Druckteile: gleiche Farben und Nummern · Bestückung transparent mit kurzen Beschriftungen.',[1.1,-1.7,3.3]),
        ]
    renders = []
    for mode, filename, title, subtitle, eye in configs:
        eye = np.asarray(eye,float);eye /= np.linalg.norm(eye)
        right=np.cross([0,0,1],eye);right/=np.linalg.norm(right);up=np.cross(eye,right)
        camera=np.column_stack([right,-up,eye])
        if mode == 'bed':
            members=[dict(key='BED_'+r['bed_id'],number=r['number']) for r in p['parts']]
            ids_wanted=list(range(1,32))
        else:
            members=[r for r in p['members'] if r['level']==mode]
            ids_wanted=[r['number'] for r in members if r['number']]
        world=np.concatenate([cache[r['key']].reshape(-1,3) for r in members])
        if mode == 'bed':
            corners=np.asarray([[x,y,z] for x in [-110,110] for y in [-110,110] for z in [0,51.5]])
            bounds=corners@camera
        else:
            bounds=world@camera
        low, high=bounds[:,:2].min(0),bounds[:,:2].max(0)
        scale=min((area[2]-area[0])/(high[0]-low[0]),(area[3]-area[1])/(high[1]-low[1]))
        center=(high+low)/2;shift=np.asarray([(area[0]+area[2])/2,(area[1]+area[3])/2])
        def project(vertices):
            q=vertices@camera;q[:,:2]=(q[:,:2]-center)*scale+shift;q[:,2]*=scale
            return q.astype(np.float32)
        vertices=[];ids=[]
        for r in members:
            t=cache[r['key']]
            vertices.append(t.reshape(-1,3));ids.extend([r['number']]*len(t))
        if mode=='bed':
            vertices.append(np.asarray([[-110,-110,-.08],[110,-110,-.08],[110,110,-.08],[-110,-110,-.08],[110,110,-.08],[-110,110,-.08]]))
            ids.extend([0,0])
        screen=project(np.concatenate(vertices));faces=np.arange(len(screen),dtype=np.int32).reshape(-1,3)
        pixels,depth,pid=raster(screen,faces,np.asarray(ids,np.int16),palette,width,height)
        edge=np.zeros(pid.shape,bool)
        for dy,dx in [(1,0),(0,1)]:
            other=np.roll(pid,(dy,dx),(0,1));dz=abs(depth-np.roll(depth,(dy,dx),(0,1)))
            edge|=(pid>=0)&((pid!=other)|(dz>scale*1.4))
        pixels[edge]=(pixels[edge]*.78).astype(np.uint8)
        population_modules=[]; population_proof={}
        if populated:
            population_modules,population_proof=population.composite(pixels,depth,project,mode,raster)
        im=Image.fromarray(pixels);d=ImageDraw.Draw(im)
        # Allocate numbered callouts onto blank/gray areas, keeping all new
        # colored parts visible. Leaders end on actual visible part pixels.
        occupied=[];callouts=[];color_mask=pid>0
        for number in ids_wanted:
            yy,xx=np.where(pid==number)
            assert len(xx)>=12,(mode,number,'part hidden; choose another camera')
            x0,x1,y0,y1=xx.min(),xx.max(),yy.min(),yy.max()
            mask=pid[y0:y1+1,x0:x1+1]==number
            dist=distance_transform_edt(np.pad(mask,1))[1:-1,1:-1]
            ay,ax=np.unravel_index(dist.argmax(),dist.shape);anchor=np.asarray([x0+ax,y0+ay])
            best=None
            for radius in [46,64,86,112,146,180]:
                for theta in np.linspace(0,2*math.pi,24,endpoint=False):
                    point=np.rint(anchor+radius*np.array([math.cos(theta),math.sin(theta)])).astype(int)
                    x,y=point;box=(x-25,y-25,x+26,y+26)
                    if x<92 or x>2295 or y<245 or y>2090:continue
                    overlap=sum(max(0,min(box[2],q[2])-max(box[0],q[0]))*max(0,min(box[3],q[3])-max(box[1],q[1])) for q in occupied)
                    score=overlap*1000+color_mask[box[1]:box[3],box[0]:box[2]].sum()*12+radius
                    if best is None or score<best[0]:best=(score,point,box)
            assert best is not None
            _,point,box=best;occupied.append(box)
            col=by_num[number]['color']
            d.line([tuple(anchor),tuple(point)],fill='white',width=7)
            d.line([tuple(anchor),tuple(point)],fill=col,width=3)
            d.ellipse([anchor[0]-4,anchor[1]-4,anchor[0]+4,anchor[1]+4],fill=col,outline='white',width=1)
            d.ellipse(box,fill='white',outline=col,width=4)
            label=f'{number:02}';box_text=d.textbbox((0,0),label,font=font(26,True))
            d.text((point[0]-(box_text[2]-box_text[0])/2,point[1]-(box_text[3]-box_text[1])/2-box_text[1]),label,font=font(26,True),fill='#203541')
            callouts.append(dict(number=number,visible_pixels=len(xx),anchor=anchor.tolist(),label_center=point.tolist(),box=[int(v) for v in box]))
        module_labels=[]
        if populated:
            module_labels=population.annotate(d,population_modules,occupied,color_mask,font)
        d.rectangle((0,0,width,190),fill='#f7f9fa')
        d.text((70,40),title,font=font(49,True),fill='#203b46')
        d.text((70,113),subtitle,font=font(27),fill='#586f7a')
        d.line((2370,240,2370,2090),fill='#d9e1e5',width=2)
        d.text((2410,243),'Farbe + Nummer = Einzelteil',font=font(31,True),fill='#203b46')
        if mode=='bed':d.text((2410,291),'01–24 unten · 25–31 oben',font=font(26),fill='#586f7a')
        else:d.text((2410,291),'Nummern wie auf dem Druckbett',font=font(26),fill='#586f7a')
        step=55 if mode=='bed' else 65
        for i,number in enumerate(sorted(ids_wanted)):
            row=by_num[number];y=350+i*step
            d.rounded_rectangle((2410,y,2450,y+32),radius=5,fill=row['color'])
            d.text((2468,y-1),f'{number:02}',font=font(26,True),fill='#243c48')
            label=row['label']
            size=26
            while d.textlength(label,font=font(size))>620:size-=1
            d.text((2520,y),label,font=font(size),fill='#324d59')
        note=('31 Teile · 220 × 220 mm · Drucklage ohne Stützen und Haftränder. Druckdateien unverändert.' if mode=='bed' else
              'Einbaulage unverändert. 06–15 sitzen über den ausgeblendeten Wandlerplatinen.' if mode=='supply' else
              'Einbaulage unverändert. Die vier Ebenenverbinder 16–19 sind in der unteren Ansicht gezeigt.')
        if populated:
            note=('06–15: Isolierscheiben über den Wandlerplatinen. Kaufmodule transparent; Einbaulagen unverändert.' if mode=='supply' else
                  'Pi-Gehäuse besonders transparent, damit Platine und Lüfter sichtbar bleiben. Einbaulagen unverändert.')
        d.text((70,2175),note,font=font(28),fill='#586f7a')
        d.text((70,2225),'PI-2L-5 · Farben dienen nur der Zuordnung; gedruckt wird mit grauem PETG.',font=font(26),fill='#73848d')
        target=OUT/filename;im.save(target)
        # A repeated number may be legible despite very similar neighboring
        # shades; every callout has its own nonoverlapping label box.
        for i,c in enumerate(callouts):
            for other in callouts[:i]:
                a,b=c['box'],other['box'];assert min(a[2],b[2])<=max(a[0],b[0]) or min(a[3],b[3])<=max(a[1],b[1]),(mode,'labels overlap')
        renders.append(dict(mode=mode,image=str(target),sha256=sha(target),part_count=len(ids_wanted),numbers=sorted(ids_wanted),callouts=callouts,module_labels=module_labels,population=population_proof,pixel_review='PENDING'))
        print('RENDERED',mode,len(ids_wanted),'parts visible and labeled',flush=True)
    if populated:
        assert set(renders[0]['numbers']).isdisjoint(renders[1]['numbers'])
        assert set(renders[0]['numbers']+renders[1]['numbers'])==set(range(1,32))
    else:
        assert set(renders[1]['numbers']).isdisjoint(renders[2]['numbers'])
        assert set(renders[1]['numbers']+renders[2]['numbers'])==set(renders[0]['numbers'])==set(range(1,32))
    prep=read(BED/'preparation.json')
    assert sha(prep['gcode'])==p['gcode_sha256'] and sha(prep['three_mf'])==p['three_mf_sha256']
    p.update(renders=renders,renderer='CPU opaque Z-buffer of actual source CAD/STL triangles. Same RGB per numbered instance; separate actual print and installed frames. Offscreen only.',color_and_number_mapping='PASS_31_UNIQUE_INSTANCES_24_LOWER_7_UPPER',print_file_hashes_unchanged=True)
    if populated:
        p.update(base_color_binding_sha256=sha(OUT/'binding.json'),population_binding_sha256=sha(OUT/'population-binding.json'),
                 display='Same opaque printed geometry, RGB colors, numbers, framing and camera as the original deck views; existing actual components added with per-pixel sorted transparency and short labels. No exploded offsets.',
                 pixel_review='PENDING')
    save(OUT/('bestueckt-binding.json' if populated else 'binding.json'),p)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['mesh','render'])
    parser.add_argument('--populated',action='store_true')
    args=parser.parse_args()
    if args.action=='mesh':mesh()
    else:render(populated=args.populated)

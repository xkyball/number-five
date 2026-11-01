"""Read-only source meshes and transparent layers for the existing color views."""
import sys, re, argparse
import numpy as np
from common import ROOT, read, save, sha

OUT = ROOT / 'previews/teile-zuordnung'


def labels(level):
    result = []
    def add(name, *ids):
        result.append(dict(label=name, members=list(ids)))
    if level == 'supply':
        for family, count in [('PI', 3), ('HUB', 2)]:
            for i in range(1, count+1):
                name = 'Pi' if family == 'PI' else 'Hub'
                add(f'Wandler {name} {i}', f'{family}_U{i}')
                add(f'Widerstand {name} {i}', f'{family}_R{i}')
        for i in range(1, 4):
            add(f'Sicherung Pi {i}', f'PI_F{i}')
        add('Pi-Ausgangssicherung', 'PI_FOUT')
        add('Hub-Sicherung 1', 'HF1_A', 'HUB_F1')
        add('Hub-Sicherung 2', 'HF2_A', 'HUB_F2')
        add('Pi-Versorgung', 'PI_A')
        add('Hub-Versorgung', 'HUB_A')
        add('Eingangsschutz', 'INPUT_A')
    else:
        add('Raspberry Pi 5', 'PI5_BOARD')
        add('Lüfter', 'PI_FAN')
        add('USB-Hub', 'HUB')
        add('CAN-Adapter', 'CAN_PCB', 'CAN_TERMINAL')
        add('GPIO-Adapter', 'GPIO_A')
        add('Ebenenstecker', 'LEVEL_POWER_HEADER', 'LEVEL_POWER_PLUG')
        add('CAN-Stecker', 'CAN_WALL_HEADER', 'CAN_WALL_PLUG')
    return result


def mesh():
    import build123d as b
    from OCP.OSD import OSD_ThreadPool
    OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
    baseline = read(OUT/'binding.json')
    source = ROOT/'output/rest-mechanical.json'
    assert sha(source) == baseline['mechanical_sha256']
    assert sha(ROOT/'design.json') == baseline['source_sha256']
    arrays, rows, omitted = {}, [], []
    for row in read(source)['rows']:
        name = row['id']
        if row['level'] not in ['supply','devices'] or row['printed']:
            continue
        # This view explains component mounting. Metal fasteners are omitted
        # to leave the colored printed insulators and their bores unobscured.
        if re.search(r'BOLT|WASHER|_NUT|^FRAME_|^UPPER_FRAME_|_MOUNT\d|_M2_|^POWER_GUARD_|^CAN_GUARD_|^CASE_FIX_|^HUB_FUSE_SUPPORT_', name):
            omitted.append(name)
            continue
        path = row['brep']['path']
        assert sha(path) == row['brep']['sha256'], name
        v, f = b.import_brep(path).tessellate(.07, .15)
        tri = np.asarray([tuple(q) for q in v])[np.asarray(f)]
        arrays[name] = tri
        alpha = .22
        if name == 'PI_CASE':
            alpha = .10
        elif name == 'PI_CASE_TOP':
            alpha = .07
        elif name.endswith('_A') or name in ['PI5_BOARD', 'CAN_PCB']:
            alpha = .17
        rows.append(dict(id=name, level=row['level'], source=row['brep'],
                         bounds_mm=row['bounds_mm'], opacity=alpha))
    for level in ['supply','devices']:
        for label in labels(level):
            assert all(k in arrays for k in label['members']), label
    cache = OUT/'population-geometry.npz'
    np.savez_compressed(cache, **arrays)
    save(OUT/'population-binding.json', dict(
        revision='PI-2L-5', source_sha256=sha(ROOT/'design.json'),
        mechanical_sha256=sha(source), color_binding_sha256=sha(OUT/'binding.json'),
        geometry_cache_sha256=sha(cache), members=rows, omitted_metal_fasteners=omitted,
        labels={level:labels(level) for level in ['supply','devices']},
        scope='Existing installed purchased modules, own PCBs/components and connector reference bodies. No new component geometry or installed transforms. Wires remain in wiring plans.',
        print_files_modified=False))
    print('POPULATION MESHES', len(rows), 'existing source bodies', flush=True)


def composite(pixels, base_depth, project, level, raster):
    """Depth-sort transparent front surfaces of each real component per pixel."""
    from numba import njit
    info = read(OUT/'population-binding.json')
    assert info['source_sha256'] == sha(ROOT/'design.json')
    assert info['geometry_cache_sha256'] == sha(OUT/'population-geometry.npz')
    cache = np.load(OUT/'population-geometry.npz')
    height, width = base_depth.shape
    indices, depths, colors, alphas = [], [], [], []
    anchors = {}
    for row in info['members']:
        if row['level'] != level:
            continue
        points = project(cache[row['id']].reshape(-1,3))
        lo = np.maximum(np.floor(points[:,:2].min(0)).astype(int)-2, [0,0])
        hi = np.minimum(np.ceil(points[:,:2].max(0)).astype(int)+3, [width,height])
        x0,y0 = lo; x1,y1 = hi
        if x1<=x0 or y1<=y0:
            continue
        local = points.copy(); local[:,:2] -= lo
        faces = np.arange(len(local),dtype=np.int32).reshape(-1,3)
        palette = np.asarray([[108,132,148]], np.float32)
        rgb, z, ids = raster(local,faces,np.zeros(len(faces),np.int16),palette,int(x1-x0),int(y1-y0))
        mask = (ids>=0) & (z > base_depth[y0:y1,x0:x1]+.001)
        yy,xx = np.where(mask)
        if not len(xx):
            continue
        middle=np.asarray([np.median(xx),np.median(yy)])
        nearest=int(np.argmin((xx-middle[0])**2+(yy-middle[1])**2))
        anchors[row['id']] = dict(point=[float(xx[nearest]+x0), float(yy[nearest]+y0)], visible_pixels=len(xx))
        edge = np.zeros(ids.shape,bool)
        for dy,dx in [(1,0),(0,1)]:
            edge |= (ids>=0)&(np.roll(ids,(dy,dx),(0,1))<0)
        alpha = np.full(len(xx),row['opacity'],np.float32)
        alpha[edge[yy,xx]] = max(row['opacity'],.38)
        indices.append(((yy+y0)*width+xx+x0).astype(np.int64))
        depths.append(z[yy,xx]); colors.append(rgb[yy,xx]); alphas.append(alpha)
    ii=np.concatenate(indices);zz=np.concatenate(depths);cc=np.concatenate(colors);aa=np.concatenate(alphas)
    order=np.lexsort((zz,ii))
    @njit
    def blend(out, index, col, alpha, order):
        for n in order:
            p=index[n];a=alpha[n]
            for k in range(3):
                out[p,k]=min(255,int(a*col[n,k]+(1-a)*out[p,k]+.5))
    blend(pixels.reshape(-1,3),ii,cc,aa,order)
    modules=[]
    for label in info['labels'][level]:
        visible=[anchors[k] for k in label['members'] if k in anchors]
        assert visible,(level,label,'no visible component surface')
        anchor=max(visible,key=lambda r:r['visible_pixels'])
        modules.append(dict(**label, anchor=anchor['point']))
    return modules, dict(front_surface_fragments=len(ii),source_body_count=sum(r['level']==level for r in info['members']),
                         compositor='Exact per-pixel back-to-front ordering of source-component front surfaces, behind opaque printed parts where appropriate. Uniform neutral blue-gray, main opacity22%; PCB17%; Pi case10%, lid7%; silhouette38%.')


def annotate(draw, modules, occupied, color_mask, font):
    """Short, separately styled component labels; printed IDs remain intact."""
    result=[]
    for module in modules:
        anchor=np.asarray(module['anchor'])
        text=module['label'];size=25
        tw=math_ceil(draw.textlength(text,font=font(size)));half=(tw+22)/2
        best=None
        for radius in [0,42,70,100,145,190,245]:
            for theta in np.linspace(0,2*np.pi,24,endpoint=False):
                point=np.rint(anchor+radius*np.array([np.cos(theta),np.sin(theta)])).astype(int)
                x,y=point;box=(int(x-half),int(y-19),int(x+half+1),int(y+20))
                if box[0]<75 or box[2]>2330 or box[1]<215 or box[3]>2120:
                    continue
                overlap=sum(max(0,min(box[2],q[2])-max(box[0],q[0]))*max(0,min(box[3],q[3])-max(box[1],q[1])) for q in occupied)
                if overlap:
                    continue
                covered=color_mask[box[1]:box[3],box[0]:box[2]].sum()
                score=covered*2+radius*2
                if best is None or score<best[0]:best=(score,point,box)
        assert best is not None,module
        _,point,box=best;occupied.append(box)
        if np.linalg.norm(anchor-point)>25:
            draw.line([tuple(anchor),tuple(point)],fill='white',width=5)
            draw.line([tuple(anchor),tuple(point)],fill='#647986',width=2)
            draw.ellipse((anchor[0]-3,anchor[1]-3,anchor[0]+3,anchor[1]+3),fill='#647986')
        draw.rounded_rectangle(box,radius=5,fill='#fbfcfd',outline='#9aabb5',width=1)
        bbox=draw.textbbox((0,0),text,font=font(size))
        draw.text((point[0]-tw/2,point[1]-(bbox[3]-bbox[1])/2-bbox[1]),text,font=font(size),fill='#385360')
        result.append(dict(label=text,members=module['members'],anchor=anchor.tolist(),box=list(box)))
    return result


def math_ceil(v):
    return int(np.ceil(v))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['mesh'])
    {'mesh':mesh}[parser.parse_args().action]()

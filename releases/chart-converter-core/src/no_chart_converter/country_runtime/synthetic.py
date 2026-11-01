"""Invented one-cell authoring fixture; never chart-derived or native evidence."""
from pathlib import Path
import json
import hashlib
import struct
from shapely.geometry import Polygon
from .inputs import file_pin
from .wire import AuthoredBlock, assemble, encode_dictionary, FEATURE
from .render_activation import encode_frame
from ..rx5.header import new_plain_vector_header


def create_synthetic_config(directory: Path) -> Path:
    """Create a fully pinned, portable private-input example in a fresh directory.

    The invented U32 rectangle, UID and source strings exercise real V15 owner
    selection, clipping, graph serialization, local metadata and readback.
    This is a host algorithm fixture, not navigation data or original acceptance.
    """
    root = Path(directory)
    root.mkdir(parents=True, exist_ok=False, mode=0o700)

    def write(name, raw):
        path = root / name
        path.write_bytes(raw)
        path.chmod(0o600)
        pin = file_pin(path)
        pin['path'] = name
        return pin

    def document(name, value):
        return write(name, (json.dumps(value, indent=2, sort_keys=True) + '\n').encode())

    cid = 'SYNTHETIC-ONE'
    uid = (1 << 32) | 1
    center = 1073807360
    ring = [(center, center), (center+65536, center),
            (center+65536, center+65536), (center, center+65536), (center, center)]
    covered = [(center-65536, center-65536), (center+131072, center-65536),
               (center+131072, center+131072), (center-65536, center+131072),
               (center-65536, center-65536)]
    co = struct.pack('<I', 0x01000000 | len(ring)) + b''.join(struct.pack('<II', *point) for point in ring)
    ix = struct.pack('<II', 1, 24 << 24)
    rec = FEATURE.pack(uid, 0, 0, 0, 123, 42, 3, 0, 0, 0)
    additional = bytes(14)
    render = encode_frame(0, {196608: b'AC(SYNTHETIC);', 196609: b'AC(SYNTHETIC);'})
    sounding_uid = (1 << 32) | 2
    detail_uid = (1 << 32) | 3
    sounding = struct.pack('<IIII', 0x01000001, center+32768, center+32768, 789)
    sound_indices = struct.pack('<II', 1, 0)
    sound_frame = encode_frame(0, {196608: b'CS(SOUNDG01);',196609: b'CS(SOUNDG01);'})
    sound_record = FEATURE.pack(sounding_uid, 0, len(render), 0, 456, 129, 4, 0, 0, 0)
    line = struct.pack('<I', 0x01000002) + struct.pack('<4I', center, center+16384, center+65536, center+16384)
    line_indices = struct.pack('<II', 1, 16 << 24)
    line_frame = encode_frame(0, {196608: b'LS(SOLD,1,OWNCL);',196609: b'LS(SOLD,1,OWNCL);'})
    line_record = FEATURE.pack(detail_uid, 0, 0, 0, 777, 30, 2, 0, 0, 0)
    catalog_records = rec + sound_record
    catalog_render = render + sound_frame
    coordinates = write('coordinates.bin', co)
    indices = write('indices.bin', ix)
    feature = write('features.bin', rec)
    catalog_feature = write('catalog-features.bin', catalog_records)
    rp = write('render.bin', catalog_render)
    ap = write('additional.bin', additional)
    empty = write('empty.bin', b'')
    bindings = document('ground-owners.json', {'owners': [{
        'preparedOwnerOrdinal': 0, 'sourceOwnerOrdinal': 0, 'uid': uid,
        'className': 'SEAARE', 'geometryProfile': 'GENUINE_CLOSED_RING24',
        'wholeOwnerRootBBox': [center, center, center+65536, center+65536],
        'resolution': {'displayPriority': 1}}]})
    no_owners = document('empty-owners.json', {'owners': []})
    header = new_plain_vector_header(bytes(range(16)))
    resources = [
        AuthoredBlock('source', 0x30, 0, b'', b'<rx5><source_chart_info id="SYNTHETIC-ONE"/></rx5>'),
        AuthoredBlock('dictionary', 8, 16, bytes(24), encode_dictionary(['SYNTHETIC'])),
        AuthoredBlock('presentation', 65, 0, b'', b'OWN SYNTHETIC RESOURCE'),
        AuthoredBlock('documents', 7, 16, bytes(24), encode_dictionary([])),
        AuthoredBlock('info', 2, 0, b'', b'<rx5><chart_info name="SYNTHETIC"/></rx5>'),
        AuthoredBlock('root', 0x6000, 1, bytes(32), b'', references=tuple((8*i, None) for i in range(4)))]
    parent = write('presentation-parent.rx5', assemble(bytes(header), resources,
        {136:'info',144:'source',160:'root',168:'dictionary',184:'presentation',200:'documents'})[0])
    composition = document('composition.json', {'cells': [cid], 'chart': parent,
        'documentMaps': {cid: {}}, 'dictionaryMaps': {cid: {'0': 0}}, 'documents': []})
    catalog = document('catalog.json', {'cells': [{'cellId': cid,
        'compiledStreams': {'render': rp, 'features': catalog_feature}}]})
    geometry = document('geometry.json', {'cells': [{'cellId': cid,
        'preparedStreams': {'features': feature, 'indices': indices, 'coordinates': coordinates},
        'ownerBindings': bindings}]})
    point_bindings = document('point-owners.json', {'owners': [{
        'preparedOwnerOrdinal':0,'sourceOwnerOrdinal':1,'uid':sounding_uid,
        'sourceSCAMIN':None,'classAcronym':'SOUNDG'}]})
    point_inputs = document('points.json', {'cells': [{'cellId': cid,
        'preparedStreams': {'features':write('point-features.bin', sound_record),
                           'indices':write('point-indices.bin', sound_indices),
                           'coordinates':write('point-coordinates.bin', sounding)},
        'ownerBindings': point_bindings, 'originalAdditional': ap}]})
    fixture = document('coverage.json', {'sources': [{'cellId': cid, 'nativeScale': 100000,
        'covered': [covered], 'excluded': []}]})
    wkb = write('fill.wkb', Polygon(ring).wkb)
    fill = document('fill-availability.json', {'sourceCells': 1, 'ownersKind3': 1,
        'inputGeometryPin': geometry, 'sourceGroundCatalog': catalog,
        'cells': [{'cellId': cid, 'fillAvailabilityWKB': wkb, 'ownersKind3': 1,
                   'kind8OrUnresolvedAdmission': False, 'coverageOrBBoxNotUsedAsFill': True}]})
    common = write('common.txt', b'LUPT 1LU1NILSYNTHPP00001LSIMPLIFIED\nDISC 1STANDARD\nINST 1SY(FAKE0001)\n')
    attrs = write('attribute-map.txt', b'1\tOWN\tOBJNAM\n')
    container = document('container.json', {'presentation': [{'id':65,'data':common}, {'id':66,'data':attrs}]})
    details = document('details.json', {'cells': [{'cellId': cid,
        'preparedStreams': {'features': empty,'indices':empty,'coordinates':empty,'render':empty},
        'ownerBindings':no_owners,'originalAdditional':ap}]})
    effective_key = hashlib.sha256(b'OWN SYNTHETIC SOURCE STYLE').hexdigest()
    selected = {'effectiveSourceKeySHA256':effective_key,'effectiveStyleID':1,
                'sourceTable':83,'sourceRCID':1,'sourcePrimitive':2}
    detail_bindings = document('detail-owners.json', {'owners': [{
        'preparedOwnerOrdinal':0,'sourceOwnerOrdinal':2,'uid':detail_uid,'kind':2,
        'className':'COALNE','sourceSCAMIN':[],
        'wholeOwnerRootBBox':[center,center+16384,center+65536,center+16384],
        'geometryProfile':'GENUINE_SIMPLE_LINE16',
        'disposition':'READY_GENUINE_SOURCE_NONPOINT_PROGRAM',
        'additionalFrameSHA256':hashlib.sha256(additional).hexdigest(),
        'originalRecord32Hex':line_record.hex(),
        'resolution':{'status':'resolved_saved_source_presentation','displayPriority':2,
                      'instruction':'LS(SOLD,1,OWNCL);','selected':selected}}]})
    details = document('details.json', {'cells':[{'cellId':cid,
        'preparedStreams':{'features':write('detail-features.bin',line_record),
                           'indices':write('detail-indices.bin',line_indices),
                           'coordinates':write('detail-coordinates.bin',line),
                           'render':write('detail-render.bin',line_frame)},
        'ownerBindings':detail_bindings,'originalAdditional':ap}]})
    remaining = document('remaining-details.json', {'cells':[{'cellId':cid,
        'preparedStreams':{'features':empty,'indices':empty,'coordinates':empty,'render':empty},
        'ownerBindings':no_owners,'originalAdditional':ap}]})
    registry = document('source-registry.json', [{'keySHA256':effective_key,
        'ownEffectiveStyleID':1,'classAcronym':'COALNE','sourceTable':83,
        'sourceRCID':1,'sourcePrimitive':2,'actualAfterPriority':2,'actualAfterCategory':68}])
    presentation = document('source-presentation.json', {'originalCommon': common})
    ca = document('ca-bindings.json', {'owners': []})
    cases = document('pixel-authorizations.json', {'rows': []})
    calls = document('pixel-calls.json', {'calls': []})
    config = {'format': 'no5-country-runtime-config/1', 'outputDirectory': 'output',
        'title': 'SYNTHETIC ONE CELL', 'editionDate': '2000-01-01',
        'expectedSourceCounts': {'sourceCells':1,'groundOwners':1,'pointOwners':1,'detailOwners':1},
        'inputs': dict(composition=composition, groundCatalog=catalog, groundGeometry=geometry,
            pointInputs=point_inputs, coverageFixture=fixture, fillAvailability=fill,
            containerInputs=container, detailInputs=details, remainingDetailInputs=remaining,
            sourceRegistry=registry, sourcePresentationIndex=presentation,
            caOwnerProgramBindings=ca, presentationParent=parent,
            pixelModelAuthorizations=cases, pixelModelCallInputs=calls)}
    return root / document('country-config.json', config)['path']

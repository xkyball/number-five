"""Invented OSENC sources for cache→country tests; no acquired chart bytes.

The source of truth is this small topology/record generator, not a prepared
country manifest or old chart. Geographic coordinates use the published local
SM convention; all object names, IDs, dates and coordinates are invented.
"""
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct
import zipfile


def canonical(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)+"\n").encode()


def rec(code, payload):
    return struct.pack("<HI", code, len(payload)+6)+payload


def geographic(x, y, lon, lat):
    z = 6378137.0*0.9996
    rad = math.pi/180
    y0 = z*math.log(math.tan(math.pi/4+lat*rad/2))
    longitude = lon+x/(z*rad)
    if not -180 <= longitude <= 180:
        longitude = (longitude+180)%360-180
    return [longitude, (2*math.atan(math.exp((y0+y)/z))-math.pi/2)/rad]


ATTRS = {116: "OBJNAM", 133: "SCAMIN", 87: "DRVAL1", 88: "DRVAL2", 75: "COLOUR", 185: "VERDAT", 158: "TXTDSC", 132: "SCAMAX"}
CLASSES = {17: "BOYLAT", 30: "COALNE", 42: "DEPARE", 129: "SOUNDG"}


def attr(code, value):
    if isinstance(value, str):
        return rec(65, struct.pack("<HB", code, 4)+value.encode()+b"\0")
    if isinstance(value, int):
        return rec(65, struct.pack("<HBI", code, 0, value))
    return rec(65, struct.pack("<HBd", code, 2, value))


def cell_payload(cell_id, index, *, scale=None, unknown_class=False, ambiguous_name=False, feature_datum=False, reference=None, source_scamax=False):
    scale = scale if scale is not None else (100000 if index == 0 else 25000)
    reference = reference or [21.02+index*.04, -16.98]
    lon, lat = reference
    west, east = lon-.02, lon+.02
    west = (west+180)%360-180 if not -180 <= west <= 180 else west
    east = (east+180)%360-180 if not -180 <= east <= 180 else east
    corners = [lat-.02,west,lat+.02,west,lat+.02,east,lat-.02,east]
    extent = {"south": corners[6], "north": corners[2], "west": corners[3], "east": corners[7]}
    body = rec(1, struct.pack("<H", 201))+rec(2, cell_id.encode()+b"\0")
    for code in (3, 5, 8):
        body += rec(code, b"20260203\0")
    body += rec(4, struct.pack("<H", 3+index))+rec(6, struct.pack("<H", index))
    body += rec(7, struct.pack("<I", scale))+rec(100, struct.pack("<8d", *corners))
    outer = [(-500.,-400.),(500.,-400.),(500.,400.),(-500.,400.)]
    hole = [(-80.,-60.),(-80.,60.),(80.,60.),(80.,-60.)]
    nodes = {11+i: point for i, point in enumerate(outer+hole)}
    refs = [[11+i,0,11+(i+1)%4] for i in range(4)]
    refs += [[15+i,0,15+(i+1)%4] for i in range(4)]
    triangle_points = []
    for x0,y0,x1,y1 in [(-500.,-400.,-80.,400.),(80.,-400.,500.,400.),(-80.,-400.,80.,-60.),(-80.,60.,80.,400.)]:
        triangle_points.extend([(x0,y0),(x1,y0),(x1,y1),(x0,y0),(x1,y1),(x0,y1)])
    def bounds(points):
        geographic_points = [geographic(x,y,lon,lat) for x,y in points]
        return [min(p[1] for p in geographic_points),max(p[1] for p in geographic_points),
                min(p[0] for p in geographic_points),max(p[0] for p in geographic_points)]
    area_bounds = bounds(outer)
    body += rec(64, struct.pack("<HHB", 42, 101, 2))
    body += attr(116, f"Area {cell_id} α")+attr(87, 0.0)+attr(88, 35.5-index*10)
    body += attr(158, f"{cell_id}_NOTE.txt")
    area = struct.pack("<4dIII2I", *area_bounds, 2, 1, len(refs), 5, 5)
    area += struct.pack("<BI4d", 4, len(triangle_points), area_bounds[2],area_bounds[3],area_bounds[0],area_bounds[1])
    area += b"".join(struct.pack("<ff",*point) for point in triangle_points)
    area += b"".join(struct.pack("<iii",*ref) for ref in refs)
    body += rec(82, area)
    line_id = 202+index*20
    line_nodes = {201:(0.,-250.),202:(100.,-250.),203:(400.,-250.),204:(-300.,-200.),205:(-150.,-200.)}
    nodes.update(line_nodes)
    line_refs = [[201,0,202],[202,-7,203],[204,0,205]]
    line_parts = [[(0.,-250.),(100.,-250.),(150.,-250.),(250.,-250.),(400.,-250.)], [(-300.,-200.),(-150.,-200.)]]
    body += rec(64, struct.pack("<HHB", 30, line_id, 1))+attr(116, f"Harbour line {cell_id}")+attr(133, 4000+index*2000)
    body += rec(81, struct.pack("<4dI", *bounds(sum(line_parts,[])),len(line_refs))+b"".join(struct.pack("<iii",*ref) for ref in line_refs))
    buoy_id = 303+index*30
    buoy_sm = [-200.,200.]
    buoy_geo = geographic(*buoy_sm,lon,lat)
    body += rec(64,struct.pack("<HHB", 555 if unknown_class else 17,buoy_id,0))
    body += attr(116,f"Buoy {cell_id}")+attr(75,"3,1")+attr(133,10000)
    if source_scamax:
        body += attr(132,50000)
    if ambiguous_name:
        body += attr(116,"Ambiguous second name")
    body += rec(80,struct.pack("<dd",buoy_geo[1],buoy_geo[0]))
    sounding_id=404+index*40
    soundings=[[-250.,80.,0.],[0.,-300.,-1.25],[300.,-300.,12.375+index]]
    body += rec(64,struct.pack("<HHB",129,sounding_id,0))+attr(116,f"Depth set {cell_id}")+attr(133,20000)
    if feature_datum:
        body += attr(185,99)
    body += rec(83,struct.pack("<4dI",*bounds([p[:2] for p in soundings]),len(soundings))+b"".join(struct.pack("<fff",*point) for point in soundings))
    edge_points=[(250.,-250.),(150.,-250.)]
    body += rec(96,struct.pack("<IiI",1,7,len(edge_points))+b"".join(struct.pack("<ff",*point) for point in edge_points))
    body += rec(97,struct.pack("<I",len(nodes))+b"".join(struct.pack("<iff",identifier,*point) for identifier,point in sorted(nodes.items())))
    coverage_sm=[(-600.,-500.),(600.,-500.),(600.,500.),(-600.,500.),(-600.,-500.)]
    excluded_sm=[(300.,100.),(400.,100.),(400.,200.),(300.,200.),(300.,100.)]
    for code, ring in [(98,coverage_sm),(99,excluded_sm)]:
        geo=[geographic(x,y,lon,lat) for x,y in ring]
        body += rec(code,struct.pack("<I",len(geo))+b"".join(struct.pack("<ff",point[1],point[0]) for point in geo))
    provenance={"originalHeader":{"name":cell_id,"display_name":f"Invented {cell_id}","edition":3+index,"update":index,
        "native_scale":scale,"extent_corners_lat_lon":corners,"reference_lat_lon":[lat,lon],
        "publish_date":"20260203","update_date":"20260203","created_date":"20260203","sounding_datum":"LAT"},
        "helperStatus":{"decrypt_status":1,"expire_status":1},
        "text":{f"{cell_id}_NOTE.txt":f"Entirely invented test document for {cell_id}; never navigation data."}}
    expectation={"id":cell_id,"nativeScale":scale,"reference":reference,"extent":extent,"featureIds":[101,line_id,buoy_id,sounding_id],
        "areaOuterSm":outer,"areaHoleSm":hole,"linePartsSm":line_parts,"buoyPosition":buoy_geo,
        "soundings":soundings,"coverageSm":coverage_sm,"excludedSm":excluded_sm}
    return body,provenance,expectation


@dataclass
class Package:
    source: Path
    catalogs: Path
    cells: list


def write_package(directory, *, unknown_class=False, ambiguous_name=False, feature_datum=False, scale=None, reference=None, source_scamax=False):
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=False)
    source=directory/"fresh-cache.zip";catalogs=directory/"catalogs";catalogs.mkdir()
    (catalogs/"s57objectclasses.csv").write_text("Code,Acronym\n"+"".join(f"{code},{name}\n" for code,name in CLASSES.items()))
    (catalogs/"s57attributes.csv").write_text("Code,Acronym\n"+"".join(f"{code},{name}\n" for code,name in ATTRS.items()))
    manifest={"format":"no5-pi-map-cache-v1","product":"INVENTED MULTI CELL TEST ONLY","licenseHost":"INVENTED",
              "authorization":"Invented fixture, no license or original-reader claim","cells":[]}
    members={};expectations=[]
    for index,identifier in enumerate(["TEST-ALPHA-X9","TEST-BETA-Y7"]):
        data,provenance,expected=cell_payload(identifier,index,scale=scale if index==0 else None,
            unknown_class=unknown_class and index==0,ambiguous_name=ambiguous_name and index==0,feature_datum=feature_datum and index==0,
            reference=reference if index==0 else None,source_scamax=source_scamax and index==0)
        provenance=canonical(provenance);cell_path=f"cells/{identifier}.senc";provenance_path=f"provenance/{identifier}.json"
        members[cell_path]=data;members[provenance_path]=provenance
        manifest["cells"].append({"name":identifier,"base":cell_path,"edition":3+index,"update":index,"featureCount":4,
            "generation":hashlib.sha256((identifier+" invented generation").encode()).hexdigest(),"issueDate":"20260203","extent":expected["extent"],
            "files":{cell_path:hashlib.sha256(data).hexdigest(),provenance_path:hashlib.sha256(provenance).hexdigest()}})
        expectations.append(expected)
    members["no5-pi-map-cache.json"]=canonical(manifest)
    with zipfile.ZipFile(source,"w",compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in members.items():archive.writestr(name,data)
    return Package(source,catalogs,expectations)


def rewrite_archive(source, destination, mutate, *, repin=False):
    with zipfile.ZipFile(source) as archive:members={name:archive.read(name) for name in archive.namelist()}
    manifest=json.loads(members["no5-pi-map-cache.json"])
    mutate(members,manifest)
    if repin:
        for row in manifest["cells"]:
            row["files"]={name:hashlib.sha256(members[name]).hexdigest() for name in row["files"]}
    members["no5-pi-map-cache.json"]=canonical(manifest)
    with zipfile.ZipFile(destination,"w",compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in members.items():archive.writestr(name,data)
    return destination


def file_pin(path):
    path=Path(path)
    return {"path":str(path),"bytes":path.stat().st_size,"sha256":hashlib.sha256(path.read_bytes()).hexdigest()}


def write_presentation_config(directory, *, missing_class=None, unknown_instruction=False):
    """Fresh declared styles and resources, without chart-owned source records.

    These generic invented commands test semantic binding only. Their successful
    host serialization is never original-library or physical-plotter acceptance.
    """
    root=Path(directory);root.mkdir(parents=True,exist_ok=False)
    common=root/"invented-common.txt"
    common.write_bytes(b"SYMB  1SYNTHETIC-ONLY\nSYMD  1FAKE0001\n")
    rules=[]
    for index,(name,primitive,table,instruction) in enumerate([
        ("DEPARE",2,78,"AC(SYNTHETIC);"),
        ("COALNE",1,83,"LS(SOLD,1,OWNCL);"),
        ("BOYLAT",0,76,"SY(FAKE0001);TX(OBJNAM,1,2,2,'15110',0,0,CHBLK,26);"),
        ("SOUNDG",0,76,"CS(SOUNDG01);")],1):
        if name==missing_class:continue
        rules.append({"id":f"invented-{name.lower()}","classAcronym":name,"sourcePrimitive":primitive,
            "sourceTable":table,"sourceRCID":index,"priority":1 if name=="DEPARE" else 4,"category":"DISPLAYBASE",
            "attributes":[],"instruction":"ZZ(UNSUPPORTED);" if unknown_instruction and name=="BOYLAT" else instruction})
    common_pin=file_pin(common);common_pin["path"]=common.name
    profile={"format":"no5-cache-country-presentation/1","nativeScaminLevelU32":0,
        "nativeScaminMultiplierU32":1,"resources":[{"id":65,"data":common_pin}],"rules":rules}
    profile_path=root/"presentation-profile.json";profile_path.write_bytes(canonical(profile))
    config={"format":"no5-cache-country-config/1","presentationProfile":file_pin(profile_path),
        "title":"INVENTED MULTI-CELL COUNTRY TEST","editionDate":"2000-02-03"}
    config_path=root/"cache-country-config.json";config_path.write_bytes(canonical(config))
    return config_path

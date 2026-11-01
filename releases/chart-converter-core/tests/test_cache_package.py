"""Complete invented cache ZIP, never acquired/licensed chart bytes."""
import hashlib
import json
from pathlib import Path
import struct
import zipfile

import pytest

from no_chart_converter import cache_decoder as decoder


def synthetic_package(root):
    def rec(code, body):
        return struct.pack('<HI', code, len(body)+6) + body
    corners = [-1.,0.,1.,0.,1.,1.,-1.,1.]
    body = rec(1,struct.pack('<H',201)) + rec(2,b'SYNTHETIC\0')
    for code in (3,5,8):
        body += rec(code,b'20260101\0')
    body += rec(4,struct.pack('<H',1)) + rec(6,struct.pack('<H',0))
    body += rec(7,struct.pack('<I',10000)) + rec(100,struct.pack('<8d',*corners))
    body += rec(64,struct.pack('<HHB',42,1,0)) + rec(80,struct.pack('<dd',0.,0.5))
    body += rec(96,struct.pack('<I',0)) + rec(97,struct.pack('<I',0))
    provenance = decoder.canonical({'originalHeader':{'name':'SYNTHETIC','edition':1,'update':0,
        'native_scale':10000,'extent_corners_lat_lon':corners,'reference_lat_lon':[0.,0.5],
        'publish_date':'20260101','update_date':'20260101','created_date':'20260101'},
        'helperStatus':{'decrypt_status':1,'expire_status':1},'text':{}})
    row = {'name':'SYNTHETIC','base':'cells/SYNTHETIC.senc','edition':1,'update':0,
        'featureCount':1,'generation':'a'*64,'issueDate':'20260101',
        'extent':{'south':-1.,'north':1.,'west':0.,'east':1.},
        'files':{'cells/SYNTHETIC.senc':decoder.sha(body),'provenance/SYNTHETIC.json':decoder.sha(provenance)}}
    manifest = {'format':'no5-pi-map-cache-v1','product':'INVENTED TEST ONLY','licenseHost':'SYNTHETIC',
                'authorization':'SYNTHETIC FIXTURE NO LICENSE CLAIM','cells':[row]}
    path = root/'synthetic.zip'
    with zipfile.ZipFile(path,'w') as archive:
        archive.writestr('no5-pi-map-cache.json',decoder.canonical(manifest))
        archive.writestr('cells/SYNTHETIC.senc',body)
        archive.writestr('provenance/SYNTHETIC.json',provenance)
    catalogs = root/'catalogs'
    catalogs.mkdir()
    (catalogs/'s57objectclasses.csv').write_text('Code,Acronym\n42,SYNTHETIC\n')
    (catalogs/'s57attributes.csv').write_text('Code,Acronym\n')
    return path,catalogs


def test_complete_cache_zip_to_ir_is_portable_and_no_clobber(tmp_path):
    source,catalogs = synthetic_package(tmp_path)
    original = source.read_bytes()
    output = tmp_path/'ir'
    manifest_path = decoder.decode(source,destination=output,catalog_dir=catalogs,
                                    expected_sha256=hashlib.sha256(original).hexdigest())
    manifest = json.loads(manifest_path.read_bytes())
    assert manifest['summary']['cells'] == 1
    assert manifest['summary']['features'] == 1
    cell = json.loads((output/manifest['cells'][0]['path']).read_bytes())
    assert cell['features'][0]['geometry']['position'] == [0.5,0.]
    assert cell['source']['authorizationMeaning'] == 'historical_original_helper_export_check_only'
    receipt = json.loads((output/'decode-receipt.json').read_bytes())
    assert receipt['newHelperRequests'] == 0
    assert source.read_bytes() == original
    with pytest.raises(decoder.DecodeError,match='OUTPUT_EXISTS'):
        decoder.decode(source,destination=output,catalog_dir=catalogs)


def test_bad_source_pin_stops_before_any_generation(tmp_path):
    source,catalogs = synthetic_package(tmp_path)
    with pytest.raises(decoder.DecodeError,match='SOURCE_HASH_MISMATCH'):
        decoder.decode(source,destination=tmp_path/'never',catalog_dir=catalogs,expected_sha256='0'*64)
    assert not (tmp_path/'never').exists()

"""Real loopback HTTP selection/export using invented RX5; no browser or network tiles."""
from contextlib import contextmanager
import hashlib
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import threading

import pytest

from fixtures import chart
from no_chart_converter.area_picker import Application, Handler, validated_request
from no_chart_converter.rx5.model import validate


@contextmanager
def local_picker(tmp_path):
    source=tmp_path/'synthetic.rx5'
    source.write_bytes(chart(coverage=True))
    app=Application(source,tmp_path/'store')
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server.application=app
    worker=threading.Thread(target=server.serve_forever,daemon=True)
    worker.start()
    try:
        yield source,app,server.server_port
    finally:
        server.shutdown();server.server_close();worker.join(timeout=2)
        app.executor.shutdown(wait=True)


def call(port,method,path,value=None,*,token=None,origin=None):
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
    headers={}
    body=None
    if value is not None:
        body=json.dumps(value)
        headers['Content-Type']='application/json'
    if token is not None:headers['X-No5-Token']=token
    if origin is not None:headers['Origin']=origin
    connection.request(method,path,body,headers)
    response=connection.getresponse()
    raw=response.read()
    result=response.status,dict(response.getheaders()),raw
    connection.close()
    return result


def test_http_selection_exports_exact_private_region_and_download(tmp_path):
    with local_picker(tmp_path) as (source,app,port):
        status,headers,raw=call(port,'GET','/api/context')
        context=json.loads(raw)
        assert status==200
        assert context['tileUrl'] is None
        assert context['coverage']['coverageGeoJSON']['features'][0]['properties']['sourceCell']=='SYNTHETIC'
        token=context['sessionToken']
        assert call(port,'POST','/api/prepare',{},token='wrong')[0]==403
        assert call(port,'POST','/api/prepare',{},token=token,origin='https://example.invalid')[0]==403
        status,_,raw=call(port,'POST','/api/prepare',{},token=token)
        assert status==202
        job=json.loads(raw);app.jobs[job['id']]['future'].result(timeout=5)
        assert app.jobs[job['id']]['status']=='complete'
        request={'name':'SYNTHETIC REGION','bbox':[-1,-1,1,1],'minLevel':0,'maxLevel':21}
        status,_,raw=call(port,'POST','/api/exports',request,token=token)
        assert status==202
        job=json.loads(raw);app.jobs[job['id']]['future'].result(timeout=5)
        result=app.jobs[job['id']]['result']
        assert app.jobs[job['id']]['status']=='complete',app.jobs[job['id']]['error']
        target=Path(result['chart']['path'])
        assert target.is_relative_to(tmp_path/'store')
        status,headers,raw=call(port,'GET',f"/api/exports/{job['id']}/download")
        assert status==200
        assert hashlib.sha256(raw).hexdigest()==result['chart']['sha256']
        assert validate(raw)['owners']==1
        assert raw==target.read_bytes()
        assert source.read_bytes()==chart(coverage=True)
        assert target.stat().st_mode&0o777==0o600
        assert call(port,'GET','/../../outside')[0]==404
        assert call(port,'GET','/app.js')[0]==200
        assert call(port,'POST','/api/exports',{**request,'path':'../../outside'},token=token)[0]==400
        outside=tmp_path/'outside';outside.write_bytes(b'PRIVATE OUTSIDE')
        result['chart']={'path':str(outside),'bytes':outside.stat().st_size,'sha256':hashlib.sha256(outside.read_bytes()).hexdigest()}
        assert call(port,'GET',f"/api/exports/{job['id']}/download")[0]==503
        assert target.read_bytes()==raw


def test_picker_requires_all_selection_levels():
    with pytest.raises(ValueError,match='0 bis 21'):
        validated_request({'bbox':[-1,-1,1,1],'minLevel':5,'maxLevel':12})

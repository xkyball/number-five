#!/usr/bin/env python3
"""No5 local area picker. Binds loopback only and never opens a browser."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import secrets
import threading
import time
from urllib.parse import quote, urlsplit
import os
from .rx5 import region
import uuid

ROOT = Path(__file__).resolve().parent
PRESETS = [
    {'id':'granger-bay','name':'Granger Bay','center':[18.4178,-33.9033],'bbox':[18.405,-33.914,18.434,-33.894],'zoom':14},
    {'id':'cape-town','name':'Kapstadt · Table Bay','center':[18.445,-33.91],'bbox':[18.34,-34.02,18.56,-33.80],'zoom':11},
    {'id':'false-bay','name':'False Bay','center':[18.60,-34.18],'bbox':[18.42,-34.37,18.87,-34.06],'zoom':10},
]

def validated_request(value):
    if not isinstance(value,dict) or set(value)-{'bbox','name','marginNm','minLevel','maxLevel'}:
        raise ValueError('Unbekannte Felder in der Gebietsauswahl.')
    bbox=value.get('bbox')
    if not isinstance(bbox,list) or len(bbox)!=4 or any(type(n) not in (int,float) or not math.isfinite(n) for n in bbox):
        raise ValueError('Das Gebiet benötigt vier endliche Koordinaten: West, Süd, Ost, Nord.')
    w,s,e,n=bbox
    if not (-180<=w<e<=180 and -85<=s<n<=85):
        raise ValueError('Das Rechteck muss innerhalb gültiger Koordinaten liegen und darf den Datumswechsel nicht kreuzen.')
    name=value.get('name','Gebietskarte')
    if not isinstance(name,str) or not 1<=len(name.strip())<=80 or any(ord(c)<32 for c in name):
        raise ValueError('Bitte einen Gebietsnamen mit 1 bis 80 Zeichen eingeben.')
    margin=value.get('marginNm',0)
    if type(margin) not in (float,int) or not math.isfinite(margin) or not 0<=margin<=20:
        raise ValueError('Die Randüberlappung muss zwischen 0 und 20 Seemeilen liegen.')
    lo=value.get('minLevel',0);hi=value.get('maxLevel',21)
    if type(lo) is not int or type(hi) is not int or lo!=0 or hi!=21:
        raise ValueError('Alle Zoomprofile von 0 bis 21 müssen für die korrekte Auswahl erhalten bleiben.')
    return {'bbox':bbox,'name':name.strip(),'marginNm':margin,'minLevel':lo,'maxLevel':hi}

class Application:
    def __init__(self, source, store, *, expected_sha256=None, tile_url=None,
                 attribution='© OpenStreetMap contributors',
                 attribution_url='https://www.openstreetmap.org/copyright'):
        self.token=secrets.token_urlsafe(32)
        self.jobs={};self.lock=threading.RLock();self.executor=ThreadPoolExecutor(max_workers=1)
        self.service=region.configure(source,store,expected_sha256=expected_sha256)
        self.output_store=Path(store).resolve()
        self.backend_error=None;self.backend_pin=region.pin(Path(region.__file__))
        self.tile_url=tile_url;self.attribution=attribution;self.attribution_url=attribution_url
        self.tile_origin='https://'+urlsplit(tile_url).netloc if tile_url else ''
        self.profile={'id':'rx5','label':Path(source).name,'sourceChart':region.pin(Path(source))}

    def require_backend(self):
        if self.service is None:raise RuntimeError('Exportbackend nicht verfügbar: '+str(self.backend_error))
        return self.service

    def context(self):
        coverage=None;error=self.backend_error
        if self.service is not None:
            try:
                coverage=self.service.coverage()
                expected=self.profile['sourceChart'];actual=coverage['source']
                if any(actual.get(key)!=expected.get(key) for key in ('bytes','sha256')):
                    raise RuntimeError('Die Ausgangskarte passt nicht zum gewählten Profil.')
            except Exception as e:error=str(e)
        with self.lock:jobs=[self.public(j) for j in self.jobs.values()]
        return {'format':'no5.area-picker.context/1','sessionToken':self.token,
                'presets':PRESETS,'coverage':coverage,'backendAvailable':self.service is not None,
                'backendError':error,'tileUrl':self.tile_url,'tileAttribution':self.attribution,
                'tileAttributionUrl':self.attribution_url,'backendCode':self.backend_pin,'profile':self.profile,'jobs':jobs}

    @staticmethod
    def public(job):
        return {k:v for k,v in job.items() if k!='future'}

    def create_job(self, kind, request=None):
        service=self.require_backend()
        with self.lock:
            if any(j['status'] in ('queued','running') for j in self.jobs.values()):
                raise BlockingIOError('Ein Export oder eine Indexvorbereitung läuft bereits.')
            ident=uuid.uuid4().hex
            job={'id':ident,'kind':kind,'status':'queued','createdAt':time.time(),'events':[],
                 'request':request,'result':None,'error':None}
            self.jobs[ident]=job
        def progress(*args,**kwargs):
            event=args[0] if len(args)==1 and isinstance(args[0],dict) else dict(kwargs)
            if not event and args:event={'message':' '.join(map(str,args))}
            # The callback is data only; never execute paths or commands from it.
            event=json.loads(json.dumps(event,default=str));event['receivedAt']=time.time()
            with self.lock:
                job['events'].append(event);job['events']=job['events'][-150:]
                job['progress']=event
        def run():
            with self.lock:job['status']='running';job['startedAt']=time.time()
            try:
                if kind=='prepare':
                    result=service.prepare_index(progress=progress)
                else:
                    result=service.export(request,progress=progress)
                result=json.loads(json.dumps(result,default=str))
                with self.lock:job['result']=result;job['status']='complete'
            except Exception as error:
                with self.lock:job['error']=str(error);job['status']='failed'
            finally:
                with self.lock:job['finishedAt']=time.time()
        job['future']=self.executor.submit(run)
        return self.public(job)

class Handler(BaseHTTPRequestHandler):
    server_version='No5AreaPicker/2'
    def log_message(self,fmt,*args):
        print(time.strftime('%Y-%m-%dT%H:%M:%S'),fmt%args,flush=True)

    @property
    def app(self):return self.server.application

    def secure_headers(self):
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','strict-origin-when-cross-origin')
        self.send_header('Content-Security-Policy',f"default-src 'self'; img-src 'self' {self.app.tile_origin} data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")

    def valid_host(self):
        host=self.headers.get('Host','')
        return host in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}')

    def json_response(self,value,status=200):
        raw=json.dumps(value,ensure_ascii=False,allow_nan=False,default=str).encode()
        self.send_response(status);self.secure_headers();self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)

    def do_GET(self):
        if not self.valid_host():return self.json_response({'error':'Ungültiger lokaler Host.'},403)
        path=urlsplit(self.path).path
        try:
            if path=='/api/context':return self.json_response(self.app.context())
            if path=='/api/exports':
                with self.app.lock:return self.json_response({'jobs':[self.app.public(j) for j in self.app.jobs.values()]})
            if path.startswith('/api/exports/'):
                parts=path.strip('/').split('/');ident=parts[2]
                if len(ident)!=32 or any(c not in '0123456789abcdef' for c in ident):return self.json_response({'error':'Unbekannter Export.'},404)
                with self.app.lock:job=self.app.jobs.get(ident)
                if job is None:return self.json_response({'error':'Unbekannter Export.'},404)
                if len(parts)==3:return self.json_response(self.app.public(job))
                if len(parts)==4 and parts[3] in ('download','manifest'):
                    return self.download(job,parts[3])
                return self.json_response({'error':'Unbekannter Endpunkt.'},404)
            files={'/':('index.html','text/html; charset=utf-8'),'/app.js':('app.js','text/javascript; charset=utf-8'),'/style.css':('style.css','text/css; charset=utf-8')}
            if path not in files:return self.json_response({'error':'Nicht gefunden.'},404)
            name,mime=files[path];raw=(ROOT/'area_static'/name).read_bytes()
            self.send_response(200);self.secure_headers();self.send_header('Content-Type',mime)
            self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception as error:self.json_response({'error':str(error)},503)

    def download(self,job,kind):
        if job['status']!='complete' or job['kind']!='export':return self.json_response({'error':'Der Export ist noch nicht erfolgreich abgeschlossen.'},409)
        result=job['result'];key={'download':'chart','manifest':'manifest'}[kind]
        item=result.get(key)
        if not isinstance(item,dict) or not {'path','bytes','sha256'}<=set(item):return self.json_response({'error':'Diese Ergebnisdatei ist nicht verfügbar.'},404)
        p=Path(item['path'])
        if (not p.is_absolute() or not p.is_relative_to(self.app.output_store)
                or p.is_symlink() or p.resolve(strict=True)!=p):
            raise ValueError('Das Ergebnis liegt außerhalb des expliziten Exportordners.')
        title=job.get('request',{}).get('name','Gebietskarte')
        stem=''.join(c if c.isalnum() or c in ('-','_') else '-' for c in title).strip('-_')[:80] or 'Gebietskarte'
        download_name=stem+('.rx5' if kind=='download' else '-manifest.json')
        descriptor=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(descriptor,'rb') as stream:
            before=os.fstat(stream.fileno());digest=hashlib.sha256()
            if before.st_size!=item['bytes']:raise ValueError('Die Ergebnisdatei hat sich verändert.')
            while chunk:=stream.read(1024*1024):digest.update(chunk)
            if digest.hexdigest()!=item['sha256']:
                raise ValueError('Die Ergebnisdatei stimmt nicht mit dem abgeschlossenen Export überein.')
            stream.seek(0)
            self.send_response(200);self.secure_headers();self.send_header('Content-Type','application/octet-stream')
            self.send_header('Content-Disposition',"attachment; filename*=UTF-8''"+quote(download_name,safe=''))
            self.send_header('Content-Length',str(item['bytes']));self.end_headers()
            while chunk:=stream.read(1024*1024):self.wfile.write(chunk)
            if region.statkey(os.fstat(stream.fileno()))!=region.statkey(before):
                raise ValueError('Die Ergebnisdatei wurde während des Downloads verändert.')

    def do_POST(self):
        if not self.valid_host():return self.json_response({'error':'Ungültiger lokaler Host.'},403)
        origin=self.headers.get('Origin')
        allowed={f'http://127.0.0.1:{self.server.server_port}',f'http://localhost:{self.server.server_port}'}
        if (origin and origin not in allowed) or not secrets.compare_digest(self.headers.get('X-No5-Token',''),self.app.token):
            return self.json_response({'error':'Die lokale Sitzung ist ungültig. Bitte die Seite neu laden.'},403)
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=8192:raise ValueError('Ungültige Anfragengröße.')
            if self.headers.get('Content-Type','').split(';')[0]!='application/json':raise ValueError('JSON wird erwartet.')
            value=json.loads(self.rfile.read(length));path=urlsplit(self.path).path
            if path=='/api/prepare':
                if value!={}:raise ValueError('Die Vorbereitung benötigt keine Dateipfade oder Optionen.')
                return self.json_response(self.app.create_job('prepare'),202)
            request=validated_request(value)
            if path=='/api/estimate':return self.json_response(self.app.require_backend().estimate(request))
            if path=='/api/exports':return self.json_response(self.app.create_job('export',request),202)
            return self.json_response({'error':'Unbekannter Endpunkt.'},404)
        except BlockingIOError as error:self.json_response({'error':str(error)},409)
        except (BrokenPipeError,ConnectionResetError):pass
        except (ValueError,TypeError,json.JSONDecodeError) as error:self.json_response({'error':str(error)},400)
        except Exception as error:self.json_response({'error':str(error)},503)

def serve(source,store,*,port=8767,expected_sha256=None,tile_url=None,
          attribution='© OpenStreetMap contributors',
          attribution_url='https://www.openstreetmap.org/copyright'):
    """Serve an explicit chart/store on loopback. Never launch a browser."""
    if type(port) is not int or not 0<=port<=65535:raise ValueError('INVALID_LOCAL_PORT')
    if tile_url is not None:
        for value in (tile_url,attribution_url):
            parsed=urlsplit(value)
            if (parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password
                    or any(c in value for c in ('\r','\n',"'",'"',';',' '))):
                raise ValueError('TILES_AND_ATTRIBUTION_REQUIRE_HTTPS')
        if not all('{'+key+'}' in tile_url for key in ('z','x','y')):
            raise ValueError('TILE_URL_REQUIRES_Z_X_Y')
    app=Application(source,store,expected_sha256=expected_sha256,tile_url=tile_url,
                    attribution=attribution,attribution_url=attribution_url)
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler);server.application=app
    print(json.dumps({'status':'LOCAL_AREA_PICKER_RUNNING',
                      'url':f'http://127.0.0.1:{server.server_port}',
                      'automaticBrowserOpen':False,'externalBasemapEnabled':tile_url is not None}),flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close();app.executor.shutdown(wait=True)
    return {'status':'LOCAL_AREA_PICKER_STOPPED'}

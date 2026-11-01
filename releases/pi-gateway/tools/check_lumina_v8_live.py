#!/usr/bin/env python3
"""Actual HTTPS/WS V8 acceptance with one ephemeral key, always revoked afterwards.

Private bootstrap and bearer values remain in memory; output contains only
named checks. No commands, routes, audio state or existing keys are modified.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.request

import aiohttp
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

SCOPES={'LUMINA_AUDIO':'audio.read,audio.command','HELM':'audio.read,audio.command,sonar.read,sonar.command',
        'MAP':'map.read,map.write','HOME':'configuration.read'}


def b64(data):return base64.urlsafe_b64encode(data).decode().rstrip('=')


def check(origin,ca,key_file):
    if key_file.stat().st_mode&0o077:raise ValueError('Owner-only bootstrap input required')
    bootstrap=key_file.read_text().strip()
    context=ssl.create_default_context(cafile=str(ca))
    key=ec.generate_private_key(ec.SECP256R1())
    public=key.public_key().public_bytes(serialization.Encoding.X962,serialization.PublicFormat.UncompressedPoint)
    key_id=hashlib.sha256(public).hexdigest()[:32]
    checks=[]
    def require(name,passed):
        checks.append({'name':name,'passed':bool(passed)})
        if not passed:raise RuntimeError(name+' failed')
    def http(path,method='GET',bearer=None,body=None,provision=False):
        headers={'Accept':'application/json'}
        if bearer:headers['Authorization']=bearer
        if provision:headers['X-No5-Gateway-Key']=bootstrap
        if body is not None:headers['Content-Type']='application/json'
        request=urllib.request.Request(origin+path,method=method,headers=headers,
            data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(request,context=context,timeout=8) as response:return response.status
        except urllib.error.HTTPError as error:return error.code
    def token(role,expiry=None,audience=None,scope=None):
        now=time.time_ns()//1_000_000
        end=now+900000 if expiry is None else expiry
        payload=(f'v=no5-gateway-token-v2\nkid={key_id}\naud={audience or origin}\nrole={role}\n'
                 f'scope={scope or SCOPES[role]}\niat={end-900000}\nexp={end}\njti={b64(os.urandom(16))}\n').encode()
        return 'Bearer N5T2.'+b64(payload)+'.'+b64(key.sign(payload,ec.ECDSA(hashes.SHA256())))
    async def stream_end(bearer,revoke=False):
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(origin.replace('https://','wss://')+'/v1/audio/stream',
                    ssl=context,headers={'Authorization':bearer},protocols=('no5.audio.fader.v1',),timeout=8) as stream:
                if revoke:require('revocation_204',await asyncio.to_thread(http,'/v2/lumina/revoke','POST',None,{'keyId':key_id},True)==204)
                deadline=time.monotonic()+8
                while time.monotonic()<deadline:
                    message=await stream.receive(timeout=max(.1,deadline-time.monotonic()))
                    if message.type in {aiohttp.WSMsgType.CLOSE,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSING}:return True
                return False
    enrolled=False
    try:
        require('enrollment_unauthenticated_401',http('/v2/lumina/enroll','POST',body={'keyId':key_id,'publicKey':b64(public)})==401)
        require('enrollment_exact_fields_400',http('/v2/lumina/enroll','POST',body={'keyId':key_id,'publicKey':b64(public),'extra':True},provision=True)==400)
        require('key_enrollment_204',http('/v2/lumina/enroll','POST',body={'keyId':key_id,'publicKey':b64(public)},provision=True)==204);enrolled=True
        paths={'/v1/status':{'HOME'},'/v1/helm':{'HELM','MAP'},'/v1/audio':{'HELM','LUMINA_AUDIO'},
               '/v1/sonar':{'HELM'},'/v1/map/routes':{'MAP'}}
        for role in SCOPES:
            bearer=token(role)
            for path,roles in paths.items():require(role+'_'+path,http(path,bearer=bearer)==(200 if role in roles else 401))
        require('wrong_audience_401',http('/v1/audio',bearer=token('HELM',audience='https://wrong.example'))==401)
        require('wrong_scope_401',http('/v1/audio',bearer=token('HELM',scope=SCOPES['HELM']+',map.write'))==401)
        require('expired_401',http('/v1/audio',bearer=token('HELM',expiry=time.time_ns()//1_000_000-1))==401)
        require('malformed_401',http('/v1/audio',bearer='Bearer N5T2.invalid.signature')==401)
        valid=token('LUMINA_AUDIO');parts=valid.split('.');raw=bytearray(base64.urlsafe_b64decode(parts[-1]+'=='));raw[-1]^=1
        require('wrong_signature_401',http('/v1/audio',bearer='.'.join(parts[:-1])+'.'+b64(raw))==401)
        require('audio_stream_expiry_closes',asyncio.run(stream_end(token('LUMINA_AUDIO',expiry=time.time_ns()//1_000_000+2000))))
        retained=token('HELM')
        require('audio_stream_revocation_closes',asyncio.run(stream_end(token('LUMINA_AUDIO'),True)))
        require('revoked_http_401',http('/v1/audio',bearer=retained)==401)
        return {'passed':True,'checks':checks,'ephemeralTestKeyRevoked':True}
    finally:
        if enrolled:http('/v2/lumina/revoke','POST',body={'keyId':key_id},provision=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin',required=True);parser.add_argument('--ca',type=Path,required=True);parser.add_argument('--bootstrap-key',type=Path,required=True)
    args=parser.parse_args();print(json.dumps(check(args.origin,args.ca,args.bootstrap_key),indent=2))

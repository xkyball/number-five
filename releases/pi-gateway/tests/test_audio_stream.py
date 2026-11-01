"""Real WS boundary: same header auth, bounded faders, revision and readback."""
import asyncio
import hashlib
import os
import time
import unittest
from dataclasses import replace
from unittest.mock import patch
from aiohttp import WSServerHandshakeError, WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from no5_pi_gateway.audio import CamillaFader, CamillaSnapshot, CamillaDspClient
from no5_pi_gateway.configuration import AudioConfig, AudioFaderLimit, CamillaDspConfig, GatewayConfig
from no5_pi_gateway.service import create_app, AUDIO_SUBPROTOCOL

KEY='test-stream-key-with-sufficient-length'
FADERS=(("MASTER",0),("SUB",1),("REAR",2),("AUX3",3),("AUX4",4))

class FakeCamilla:
    def __init__(self, snapshot): self.snapshot=snapshot; self.writes=[]
    async def set_fader_db(self, session, channel, db):
        self.writes.append((channel,db))
        faders=dict(self.snapshot.faders); faders[channel]=CamillaFader(db,faders[channel].muted)
        self.snapshot=replace(self.snapshot,faders=faders,observed_at_epoch_ms=time.time_ns()//1_000_000)
        return self.snapshot
    async def close(self): pass

class AudioStreamTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.env=patch.dict(os.environ,{'NO5_STREAM_TEST_HASH':hashlib.sha256(KEY.encode()).hexdigest()});self.env.start()
        self.camilla_config=CamillaDspConfig(enabled=True,websocket_url='ws://127.0.0.1:1234',channel_faders=FADERS)
        audio=AudioConfig(enabled=True,hardware_available=True,camilla=self.camilla_config,
            fader_limits=tuple((c,AudioFaderLimit(-60,0,1)) for c,_ in FADERS))
        app=await create_app(GatewayConfig(audio=audio,audio_gateway_key_hash_env='NO5_STREAM_TEST_HASH'))
        self.audio=app['gateway_service'].audio;self.audio._stop.set()
        snapshot=CamillaSnapshot(version='4.1.3',state='RUNNING',raw_state='Running',
            faders={c:CamillaFader(0,False) for c,_ in FADERS},config_title='Test',clipped_samples=0,observed_at_epoch_ms=time.time_ns()//1_000_000)
        self.fake=FakeCamilla(snapshot);self.audio._client=self.fake;self.audio._latest=snapshot
        self.client=TestClient(TestServer(app));await self.client.start_server()
    async def asyncTearDown(self): await self.client.close();self.env.stop()
    async def socket(self): return await self.client.ws_connect('/v1/audio/stream',protocols=[AUDIO_SUBPROTOCOL],headers={'X-No5-Gateway-Key':KEY})
    def command(self,state,number,command=None):
        return {'version':1,'kind':'audio-command','sessionId':'stream-test','requestId':f'stream-test:{number}',
            'serverSessionId':state['serverSessionId'],'expectedRevision':state['revision'],
            'command':command or {'type':'fader','channel':'MASTER','valueDb':-number}}
    async def ack(self,ws):
        while True:
            value=await ws.receive_json(timeout=2)
            if value['kind']=='audio-ack':return value
    async def test_same_header_is_required_and_protocol_is_negotiated(self):
        for headers in [{}, {'X-No5-Gateway-Key':'wrong'}, [('X-No5-Gateway-Key',KEY),('X-No5-Gateway-Key',KEY)]]:
            with self.assertRaises(WSServerHandshakeError) as caught:await self.client.ws_connect('/v1/audio/stream',protocols=[AUDIO_SUBPROTOCOL],headers=headers)
            self.assertEqual(caught.exception.status,401)
        with self.assertRaises(WSServerHandshakeError) as caught:await self.client.ws_connect('/v1/audio/stream',protocols=['wrong'],headers={'X-No5-Gateway-Key':KEY})
        self.assertEqual(caught.exception.status,426)
        ws=await self.socket();self.assertEqual(ws.protocol,AUDIO_SUBPROTOCOL)
        self.assertEqual((await ws.receive_json())['kind'],'audio');await ws.close()
    async def test_stream_confirms_each_target_and_rejects_stale_revision(self):
        ws=await self.socket();state=await ws.receive_json()
        for number in range(1,5):
            await asyncio.sleep(.04);await ws.send_json(self.command(state,number))
            ack=await self.ack(ws);self.assertEqual(ack['status'],'VERIFIED');state=ack['state']
            self.assertEqual(state['dsp']['faders']['MASTER']['db'],-number)
        await asyncio.sleep(.04);command=self.command(state,5);command['expectedRevision']-=1
        await ws.send_json(command);self.assertEqual((await self.ack(ws))['status'],'REJECTED')
        self.assertEqual(len(self.fake.writes),4);await ws.close()
    async def test_preset_active_uses_actual_path_and_keeps_equal_title_real_presets(self):
        from no5_pi_gateway.audio import CamillaPreset
        sound = CamillaPreset('sound-stereo', 'Same visible title', '/etc/camilladsp/preset_no5_sound_stereo_4_1.yml')
        operator = CamillaPreset('operator-stereo', 'Same visible title', '/etc/camilladsp/preset_operator.yml')
        self.audio._presets = (sound, operator)
        # A preserved original is outside the reduced Sound catalog. Equal
        # titles must not make either catalog variant appear active.
        self.audio._latest = replace(self.fake.snapshot, config_title=sound.title,
                                    config_path='/etc/camilladsp/preset_no5_stereo_4_1.yml')
        catalog = self.audio.payload['dsp']['presets']
        self.assertEqual([row['id'] for row in catalog], ['sound-stereo', 'operator-stereo'])
        self.assertFalse(any(row['active'] for row in catalog))
        self.audio._latest = replace(self.audio._latest, config_path=sound.path)
        self.assertEqual([row['active'] for row in self.audio.payload['dsp']['presets']], [True, False])
        # Also exercise the dangerous single-title case after allowlist reduction.
        self.audio._presets = (sound,)
        for actual_path in (None, '/etc/camilladsp/preset_no5_stereo_4_1.yml'):
            self.audio._latest = replace(self.audio._latest, config_path=actual_path)
            self.assertFalse(self.audio.payload['dsp']['presets'][0]['active'])
        self.audio._latest = replace(self.audio._latest, config_path=sound.path)
        self.assertTrue(self.audio.payload['dsp']['presets'][0]['active'])

    async def test_non_fader_and_oversize_never_reach_dsp(self):
        ws=await self.socket();state=await ws.receive_json()
        await ws.send_json(self.command(state,1,{'type':'transport','action':'PLAY'}))
        while (message:=await ws.receive()).type==WSMsgType.TEXT:pass
        self.assertIn(message.type,[WSMsgType.CLOSE,WSMsgType.CLOSED]);self.assertEqual(ws.close_code,1008);self.assertEqual(len(self.fake.writes),0)
        ws=await self.socket();await ws.receive_json();await ws.send_str('x'*4097)
        while (message:=await ws.receive()).type==WSMsgType.TEXT:pass
        self.assertEqual(len(self.fake.writes),0);await ws.close()

class PersistentCamillaTest(unittest.IsolatedAsyncioTestCase):
    async def test_one_socket_survives_observations_and_writes_without_retry_after_lost_reply(self):
        from aiohttp import web
        from no5_pi_gateway.audio import AudioError
        self.connections=0;self.writes=0;self.db=-20.;self.drop=False
        async def handler(request):
            ws=web.WebSocketResponse();await ws.prepare(request);self.connections+=1
            async for message in ws:
                root=message.json();name=root if isinstance(root,str) else next(iter(root))
                if name=='SetVolume':
                    self.db=root[name];self.writes+=1
                    if self.drop: self.drop=False;await ws.close();break
                values={'GetVersion':'4.1.3','GetState':'Running','GetVolume':self.db,'GetMute':False,
                    'GetFaders':[{'volume':self.db if i==0 else -8.,'mute':False} for i in range(5)],
                    'GetClippedSamples':0,'GetConfigTitle':'Test','GetConfigFilePath':'/tmp/test.yml'}
                await ws.send_json({name:{'result':'Ok',**({'value':values[name]} if name in values else {})}})
            return ws
        app=web.Application();app.router.add_get('/socket',handler);server=TestServer(app);await server.start_server()
        client=CamillaDspClient(CamillaDspConfig(enabled=True,websocket_url=str(server.make_url('/socket')).replace('http:','ws:'),channel_faders=FADERS),persistent=True)
        try:
            await client.read_snapshot(None)
            self.assertEqual((await client.set_fader_db(None,'MASTER',-21.)).faders['MASTER'].db,-21.)
            await client.read_snapshot(None);await client.set_fader_db(None,'MASTER',-22.)
            self.assertEqual((self.connections,self.writes),(1,2))
            self.drop=True
            with self.assertRaises(AudioError):await client.set_fader_db(None,'MASTER',-23.)
            self.assertEqual((self.connections,self.writes),(1,3))
            self.assertEqual((await client.read_snapshot(None)).faders['MASTER'].db,-23.)
            self.assertEqual((self.connections,self.writes),(2,3))
        finally:await client.close();await server.close()

class MasterAuthorityTest(unittest.IsolatedAsyncioTestCase):
    async def test_master_routes_through_receiver_and_requires_readback_without_replay(self):
        from no5_pi_gateway.audio import CamillaDspAdapter, AudioOutcomeUnknown
        from no5_pi_gateway.configuration import SpotifyConnectConfig
        audio=AudioConfig(enabled=True,hardware_available=True,media_provider='spotify_connect',
            camilla=CamillaDspConfig(enabled=True,websocket_url='ws://127.0.0.1:1234',channel_faders=FADERS),
            fader_limits=tuple((c,AudioFaderLimit(-60,0,1)) for c,_ in FADERS),
            spotify_connect=SpotifyConnectConfig(enabled=True,state_file='/tmp/public-state.json',control_socket='/tmp/control.sock',master_volume_bridge=True))
        adapter=CamillaDspAdapter(GatewayConfig(audio=audio),'gateway-test')
        snapshot=CamillaSnapshot(version='4.1.3',state='RUNNING',raw_state='Running',faders={c:CamillaFader(-20,False) for c,_ in FADERS},config_title='Test',clipped_samples=0,observed_at_epoch_ms=time.time_ns()//1_000_000)
        class ReadOnlyCamilla:
            async def read_snapshot(self,session):return replace(adapter._latest,observed_at_epoch_ms=time.time_ns()//1_000_000)
        adapter._latest=snapshot;adapter._client=ReadOnlyCamilla();calls=[]
        authority={'sessionId':'main-test','revision':1,'valueDb':-20.,'muted':False,'observedAtEpochMs':snapshot.observed_at_epoch_ms}
        async def relay(path,request_id,action,timeout_s):
            if action['type'] != 'MASTER_STATE':
                calls.append(action)
                self.assertEqual(action['expected'],{'sessionId':authority['sessionId'],'revision':authority['revision']})
                authority.update(valueDb=action.get('value_db',authority['valueDb']),muted=action.get('muted',authority['muted']),revision=authority['revision']+1)
            authority['observedAtEpochMs']=time.time_ns()//1_000_000
            return dict(authority)
        def command(n,body):
            state=adapter.payload
            return {'version':1,'kind':'audio-command','sessionId':'master-test','requestId':f'master-test:{n}','serverSessionId':state['serverSessionId'],'expectedRevision':state['revision'],'command':body}
        with patch('no5_pi_gateway.audio._spotify_control_request',relay):
            await adapter._observe_main_authority()
            request=command(1,{'type':'fader','channel':'MASTER','valueDb':-21})
            ack=await adapter.command(request);self.assertEqual(ack['status'],'VERIFIED');self.assertEqual(calls,[{'type':'MASTER_VOLUME','value_db':-21,'expected':{'sessionId':'main-test','revision':1}}])
            self.assertEqual(await adapter.command(request),ack);self.assertEqual(len(calls),1)
            self.assertEqual((await adapter.command(command(2,{'type':'mute','channel':'MASTER','muted':True})))['status'],'VERIFIED')
            self.assertEqual(calls[-1],{'type':'MASTER_MUTE','muted':True,'expected':{'sessionId':'main-test','revision':2}})
        async def lost(path,request_id,action,timeout_s):
            if action['type']=='MASTER_STATE':return await relay(path,request_id,action,timeout_s)
            calls.append('lost');raise AudioOutcomeUnknown('reply lost')
        request=command(3,{'type':'fader','channel':'MASTER','valueDb':-22})
        with patch('no5_pi_gateway.audio._spotify_control_request',lost):
            for _ in range(2):
                with self.assertRaises(AudioOutcomeUnknown):await adapter.command(request)
        self.assertEqual(calls.count('lost'),1)

    async def test_two_apps_cannot_overwrite_an_absolute_command_with_one_old_revision(self):
        from no5_pi_gateway.audio import CamillaDspAdapter
        from no5_pi_gateway.configuration import SpotifyConnectConfig
        audio=AudioConfig(enabled=True,hardware_available=True,media_provider='spotify_connect',
            camilla=CamillaDspConfig(enabled=True,websocket_url='ws://127.0.0.1:1234',channel_faders=FADERS),
            fader_limits=tuple((c,AudioFaderLimit(-60,0,1)) for c,_ in FADERS),
            spotify_connect=SpotifyConnectConfig(enabled=True,state_file='/tmp/public-state.json',control_socket='/tmp/control.sock',master_volume_bridge=True))
        adapter=CamillaDspAdapter(GatewayConfig(audio=audio),'gateway-concurrent')
        adapter._latest=CamillaSnapshot('4.1.3','RUNNING','Running',{c:CamillaFader(-20,False) for c,_ in FADERS},'Test',0,time.time_ns()//1_000_000)
        class LatestCamilla:
            async def read_snapshot(self,session):return replace(adapter._latest,observed_at_epoch_ms=time.time_ns()//1_000_000)
        adapter._client=LatestCamilla()
        authority={'sessionId':'main-test','revision':1,'valueDb':-20.,'muted':False,'observedAtEpochMs':time.time_ns()//1_000_000}
        writes=[]
        async def relay(path,request_id,action,timeout_s):
            if action['type']=='MASTER_VOLUME':
                writes.append(action)
                self.assertEqual(action['expected'],{'sessionId':'main-test','revision':authority['revision']})
                authority.update(valueDb=action['value_db'],revision=authority['revision']+1)
            return dict(authority)
        with patch('no5_pi_gateway.audio._spotify_control_request',relay):
            await adapter._observe_main_authority()
            state=adapter.payload
            def request(client,value):
                return {'version':1,'kind':'audio-command','sessionId':client,'requestId':client+':1','serverSessionId':state['serverSessionId'],'expectedRevision':state['revision'],'command':{'type':'fader','channel':'MASTER','valueDb':value}}
            a,b=await asyncio.gather(adapter.command(request('client-a',-21)),adapter.command(request('client-b',-22)))
            self.assertEqual([a['status'],b['status']],['VERIFIED','REJECTED'])
            self.assertEqual(len(writes),1)
            self.assertEqual(authority['valueDb'],-21)
            # A->B->A outside gateway polling still changes the authority version.
            old=request('client-c',-23)
            authority.update(valueDb=-20.,revision=authority['revision']+2)
            rejected=await adapter.command(old)
            self.assertEqual(rejected['status'],'REJECTED')
            self.assertEqual(len(writes),1)

    async def test_main_does_not_accept_another_writers_matching_global_value_as_its_receipt(self):
        from no5_pi_gateway.audio import CamillaDspAdapter,AudioOutcomeUnknown
        from no5_pi_gateway.configuration import SpotifyConnectConfig
        audio=AudioConfig(enabled=True,hardware_available=True,media_provider='spotify_connect',
            camilla=CamillaDspConfig(enabled=True,websocket_url='ws://127.0.0.1:1234',channel_faders=FADERS),
            fader_limits=tuple((c,AudioFaderLimit(-60,0,1)) for c,_ in FADERS),
            spotify_connect=SpotifyConnectConfig(enabled=True,state_file='/tmp/public-state.json',control_socket='/tmp/control.sock',master_volume_bridge=True))
        adapter=CamillaDspAdapter(GatewayConfig(audio=audio),'gateway-receipt')
        adapter._latest=CamillaSnapshot('4.1.3','RUNNING','Running',{c:CamillaFader(-20,False) for c,_ in FADERS},'Test',0,time.time_ns()//1_000_000)
        class LatestCamilla:
            async def read_snapshot(self,session):return replace(adapter._latest,observed_at_epoch_ms=time.time_ns()//1_000_000)
        adapter._client=LatestCamilla()
        authority={'sessionId':'main-test','revision':1,'valueDb':-20.,'muted':False,'observedAtEpochMs':time.time_ns()//1_000_000}
        writes=[]
        async def relay(path,request_id,action,timeout_s):
            if action['type']=='MASTER_VOLUME':
                writes.append(action)
                adapter._latest=replace(adapter._latest,faders=adapter._latest.faders|{'MASTER':CamillaFader(-21.,False)})
                # This command has no valid matching receipt, even though a
                # different writer happened to reach the desired global value.
                raise AudioOutcomeUnknown('owner reply lost')
            return dict(authority)
        with patch('no5_pi_gateway.audio._spotify_control_request',relay):
            await adapter._observe_main_authority();state=adapter.payload
            request={'version':1,'kind':'audio-command','sessionId':'client-a','requestId':'client-a:1','serverSessionId':state['serverSessionId'],'expectedRevision':state['revision'],'command':{'type':'fader','channel':'MASTER','valueDb':-21}}
            for _ in range(2):
                with self.assertRaises(AudioOutcomeUnknown):await adapter.command(request)
            self.assertEqual(len(writes),1)

    def test_bridge_requires_receiver_and_explicit_safe_main_range(self):
        from no5_pi_gateway.configuration import SpotifyConnectConfig, ConfigurationError
        receiver=SpotifyConnectConfig(enabled=True,state_file='/tmp/state.json',control_socket='/tmp/control.sock',master_volume_bridge=True)
        for limits in [(),(('MASTER',AudioFaderLimit(-60,1,1)),)]:
            with self.assertRaises(ConfigurationError):AudioConfig(media_provider='spotify_connect',spotify_connect=receiver,fader_limits=limits)
        self.assertTrue(AudioConfig(media_provider='spotify_connect',spotify_connect=receiver,fader_limits=(('MASTER',AudioFaderLimit(-60,0,1)),)).spotify_connect.master_volume_bridge)

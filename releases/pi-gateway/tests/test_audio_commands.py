import asyncio
import unittest
from no5_pi_gateway.audio import AudioError,AudioOutcomeUnknown
from no5_pi_gateway.audio_commands import AudioCommands

class Adapter:
    def __init__(self):self.payload={"serverSessionId":"gateway-test","revision":1};self.writes=[];self.block=asyncio.Event();self.block.set();self.unknown=False
    async def command(self,request):
        await self.block.wait()
        self.writes.append(request["command"])
        if self.unknown:raise AudioOutcomeUnknown("lost reply")
        if request["expectedRevision"]!=self.payload["revision"]:return {"status":"REJECTED","state":dict(self.payload)}
        self.payload["revision"]+=1
        return {"status":"VERIFIED","state":dict(self.payload)}

def request(number,command=None,revision=1,producer="test"):
    return {"version":1,"kind":"audio-command","sessionId":producer,"requestId":f"{producer}:{number}","serverSessionId":"gateway-test","expectedRevision":revision,"command":command or {"type":"fader","channel":"MASTER","valueDb":-number}}

class AudioCommandsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):self.adapter=Adapter();self.ring=AudioCommands(self.adapter)
    async def asyncTearDown(self):await self.ring.stop()
    async def done(self,identifier):
        for _ in range(100):
            result=self.ring.read("owner",identifier)
            if result["status"] not in {"ACCEPTED","RUNNING"}:return result
            await asyncio.sleep(.001)
        self.fail("command did not complete")
    async def test_latest_target_keeps_discrete_barriers_and_records_replacement(self):
        first=self.ring.submit("owner",request(1),lambda:True)
        second=self.ring.submit("owner",request(2),lambda:True)
        self.assertEqual(self.ring.read("owner",first["commandId"])["status"],"SUPERSEDED")
        self.assertEqual(self.ring.read("owner",first["commandId"])["replacementId"],second["commandId"])
        track=self.ring.submit("owner",request(3,{"type":"transport","action":"NEXT"}),lambda:True)
        last=self.ring.submit("owner",request(4),lambda:True)
        self.ring.start()
        self.assertEqual((await self.done(last["commandId"]))["status"],"APPLIED")
        self.assertEqual(self.adapter.writes,[request(2)["command"],request(3,{"type":"transport","action":"NEXT"})["command"],request(4)["command"]])
    async def test_slow_backend_does_not_block_admission_and_unknown_is_once_only(self):
        self.adapter.block.clear();self.adapter.unknown=True;self.ring.start()
        first=self.ring.submit("owner",request(1),lambda:True);await asyncio.sleep(.002)
        latest=None
        for n in range(2,60):latest=self.ring.submit("owner",request(n),lambda:True)
        self.assertEqual(len(self.adapter.writes),0);self.assertEqual(self.ring._size,1)
        self.adapter.block.set();self.assertEqual((await self.done(first["commandId"]))["status"],"UNKNOWN")
        same=self.ring.submit("owner",request(1),lambda:True)
        self.assertEqual(same["commandId"],first["commandId"])
        await self.done(latest["commandId"]);self.assertEqual(len(self.adapter.writes),2)
    async def test_external_revision_and_revocation_cancel_before_write(self):
        first=self.ring.submit("owner",request(1),lambda:True)
        second=self.ring.submit("other",request(1,producer="other"),lambda:True)
        revoked=self.ring.submit("owner",request(2,{"type":"transport","action":"NEXT"}),lambda:False)
        self.ring.start();await self.done(first["commandId"]);await self.done(revoked["commandId"])
        self.assertEqual(self.ring.read("other",second["commandId"])["status"],"REJECTED")
        self.assertEqual(self.ring.read("owner",revoked["commandId"])["status"],"CANCELLED")
        self.assertEqual(len(self.adapter.writes),1)
    async def test_capacity_never_discards_accepted_discrete_actions(self):
        ids=[self.ring.submit("owner",request(n,{"type":"transport","action":"NEXT"}),lambda:True)["commandId"] for n in range(1,66)]
        self.assertEqual(self.ring.read("owner",ids[-1])["reason"],"BUSY")
        self.ring.start();await self.done(ids[-2]);self.assertEqual(len(self.adapter.writes),64)
    async def test_equalizer_bank_supersedes_unsent_targets_but_never_crosses_discrete_barrier(self):
        def eq(value):return {"type":"equalizer","bankId":"master","configGeneration":"a"*64,"gainsDb":[value,0,0,0,0]}
        first=self.ring.submit("owner",request(1,eq(1)),lambda:True)
        latest=self.ring.submit("owner",request(2,eq(2)),lambda:True)
        self.assertEqual(self.ring.read("owner",first["commandId"])["status"],"SUPERSEDED")
        self.ring.submit("owner",request(3,{"type":"transport","action":"NEXT"}),lambda:True)
        final=self.ring.submit("owner",request(4,eq(3)),lambda:True)
        self.ring.start()
        receipt=await self.done(final["commandId"])
        self.assertEqual(receipt["status"],"APPLIED");self.assertEqual(receipt["verification"],"ACTIVE_CONFIG_READBACK")
        self.assertEqual(self.adapter.writes,[eq(2),{"type":"transport","action":"NEXT"},eq(3)])
    async def test_eviction_cannot_replay_an_old_request_and_owner_isolated(self):
        old=None
        for n in range(1,300):
            result=self.ring.submit("owner",request(n,{"type":"fader","channel":"MASTER","valueDb":-(n%60)}),lambda:True)
            if old is None:old=result["commandId"]
        self.assertIsNone(self.ring.read("other",result["commandId"]))
        self.assertIsNone(self.ring.read("owner",old))
        with self.assertRaises(AudioError):self.ring.submit("owner",request(1),lambda:True)
        with self.assertRaises(AudioError):self.ring.submit("owner",request(299,{"type":"transport","action":"NEXT"}),lambda:True)

class SoundReadbackReceiptTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.adapter=Adapter();self.ring=AudioCommands(self.adapter)
        self.sound={"version":2,"kind":"audio-sound","preview":{"id":"edit","expiresAtEpochMs":0},"preset":{"eqEnabled":True}}
        async def command(owner,request,valid,**kwargs):
            import time
            await self.adapter.block.wait()
            self.adapter.writes.append(request["command"])
            self.sound["preview"]["expiresAtEpochMs"]=time.time_ns()//1_000_000+5000
            return {"status":"VERIFIED","state":dict(self.adapter.payload),"verification":"AUDIO_BLOCK_READBACK","sound":self.sound}
        self.adapter.sound_command_authorized=command
        self.request=request(1,{"type":"soundKeepalive","previewId":"edit","configGeneration":"config-1"})
    async def asyncTearDown(self):await self.ring.stop()
    async def test_fast_result_contains_owned_immutable_readback_with_decreasing_lease(self):
        result=self.ring.submit("owner",self.request,lambda:True);self.ring.start()
        value=await self.ring.wait_result("owner",result["commandId"])
        self.assertEqual(value["status"],"APPLIED");self.assertEqual(value["verification"],"AUDIO_BLOCK_READBACK")
        meta=value["soundReadback"];self.assertTrue(4800<=meta["remainingLeaseMs"]<=5000)
        self.sound["preset"]["eqEnabled"]=False;meta["state"]["preset"]["eqEnabled"]=False
        await asyncio.sleep(.01)
        again=self.ring.read("owner",result["commandId"])["soundReadback"]
        self.assertTrue(again["state"]["preset"]["eqEnabled"])
        self.assertLess(again["remainingLeaseMs"],meta["remainingLeaseMs"])
        self.assertIsNone(await self.ring.wait_result("other",result["commandId"]))
        self.assertNotIn("soundReadback",self.ring.results("owner")[0])
    async def test_bounded_wait_never_cancels_or_replays_a_slow_write(self):
        self.adapter.block.clear();self.ring.start()
        result=self.ring.submit("owner",self.request,lambda:True)
        value=await self.ring.wait_result("owner",result["commandId"],.002)
        self.assertIn(value["status"],{"ACCEPTED","RUNNING"});self.assertNotIn("soundReadback",value)
        self.assertEqual(self.adapter.writes,[])
        self.adapter.block.set();done=await self.ring.wait_result("owner",result["commandId"])
        self.assertEqual(done["status"],"APPLIED")
        self.assertEqual(len(self.adapter.writes),1)
        self.assertEqual(self.ring.submit("owner",self.request,lambda:True)["commandId"],result["commandId"])
        self.assertEqual(len(self.adapter.writes),1)


class AudioCommandsBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import test_audio_stream as fixtures
        await fixtures.AudioStreamTest.asyncSetUp(self)
        self.headers={"X-No5-Gateway-Key":fixtures.KEY}
    async def asyncTearDown(self):
        import test_audio_stream as fixtures
        await fixtures.AudioStreamTest.asyncTearDown(self)
    async def test_http_202_precedes_backend_and_status_does_not_repeat_write(self):
        block=asyncio.Event();original=self.fake.set_fader_db
        async def slow(*args):await block.wait();return await original(*args)
        self.fake.set_fader_db=slow
        state=self.audio.payload
        body=request(1,revision=state["revision"]);body["serverSessionId"]=state["serverSessionId"]
        response=await self.client.post('/v1/audio/commands',json=body,headers=self.headers)
        self.assertEqual(response.status,202);receipt=await response.json();identifier=receipt['commandId']
        self.assertEqual(receipt['status'],'ACCEPTED');self.assertEqual(self.fake.writes,[])
        same=await self.client.post('/v1/audio/commands',json=body,headers=self.headers)
        self.assertEqual((await same.json())['commandId'],identifier)
        block.set()
        for _ in range(100):
            result=await (await self.client.get('/v1/audio/commands/'+identifier,headers=self.headers)).json()
            if result['status']=='APPLIED':break
            await asyncio.sleep(.001)
        self.assertEqual(result['status'],'APPLIED');self.assertEqual(self.fake.writes,[('MASTER',-1)])
        self.assertEqual((await self.client.get('/v1/audio/commands/'+identifier)).status,401)
        self.assertEqual((await self.client.get('/v1/audio/commands/unknown',headers=self.headers)).status,410)
    async def test_result_stream_auth_subprotocol_and_read_only_contract(self):
        from no5_pi_gateway.service import AUDIO_COMMANDS_SUBPROTOCOL
        from aiohttp import WSMsgType,WSServerHandshakeError
        with self.assertRaises(WSServerHandshakeError):
            await self.client.ws_connect('/v1/audio/commands/stream',protocols=['wrong'],headers=self.headers)
        socket=await self.client.ws_connect('/v1/audio/commands/stream',protocols=[AUDIO_COMMANDS_SUBPROTOCOL],headers=self.headers)
        self.assertEqual((await socket.receive_json())['kind'],'audio-command-results')
        await socket.send_str('unexpected command')
        while (await socket.receive()).type==WSMsgType.TEXT:pass
        self.assertEqual(socket.close_code,1003);await socket.close()
    async def test_equalizer_uses_202_existing_results_and_active_config_readback_only(self):
        import tempfile
        from pathlib import Path
        from dataclasses import replace
        from no5_pi_gateway.audio import CamillaDspClient
        from no5_pi_gateway.equalizer import EqualizerController
        from test_equalizer import CamillaSocket,Session,eq_command
        with tempfile.TemporaryDirectory() as directory:
            socket=CamillaSocket();socket.patch_gate=asyncio.Event();session=Session(socket)
            self.audio._audio=replace(self.audio._audio,camilla=replace(self.camilla_config,
                equalizer_state_file="/var/lib/no5-pi-gateway/master-equalizer.json"))
            self.audio._client=CamillaDspClient(self.camilla_config)
            self.audio._session_factory=lambda:session
            self.audio._equalizer=EqualizerController(str(Path(directory)/"master-equalizer.json"))
            self.audio._latest=await self.audio._client.read_snapshot(session)
            await self.audio._observe_equalizer(session)
            state=self.audio.payload
            body=request(1,eq_command(socket.config,[3,0,-1,0,0]),revision=state["revision"])
            body["serverSessionId"]=state["serverSessionId"]
            legacy=await self.client.post('/v1/audio/command',json=body,headers=self.headers)
            self.assertEqual(legacy.status,400)
            response=await self.client.post('/v1/audio/commands',json=body,headers=self.headers)
            self.assertEqual(response.status,202);receipt=await response.json();identifier=receipt["commandId"]
            self.assertEqual(socket.patch_count,0)
            repeated=await self.client.post('/v1/audio/commands',json=body,headers=self.headers)
            self.assertEqual((await repeated.json())["commandId"],identifier)
            socket.patch_gate.set()
            for _ in range(200):
                result=await (await self.client.get('/v1/audio/commands/'+identifier,headers=self.headers)).json()
                if result["status"] not in {"ACCEPTED","RUNNING"}:break
                await asyncio.sleep(.001)
            self.assertEqual(result["status"],"APPLIED");self.assertEqual(socket.patch_count,1)
            self.assertEqual(result["verification"],"ACTIVE_CONFIG_READBACK")
            self.assertEqual(result["state"]["dsp"]["equalizer"]["gainsDb"],[3,0,-1,0,0])

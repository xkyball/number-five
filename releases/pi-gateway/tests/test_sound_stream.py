import asyncio
import copy
import time
import unittest
from aiohttp import web, WSMsgType, WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer
from no5_pi_gateway.audio_commands import AudioCommands
from no5_pi_gateway.sound_stream import sound_command_stream, SUBPROTOCOL
from test_audio_commands import Adapter, request
from test_sound import preset


class SoundStreamTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.adapter = Adapter()
        self.adapter.block.clear()
        self.authorized = True
        self.reached = asyncio.Event()
        async def sound(owner, body, valid, **kwargs):
            self.reached.set()
            await self.adapter.block.wait()
            if not valid():
                return {"status": "REJECTED", "message": "authorization expired"}
            self.adapter.writes.append(copy.deepcopy(body["command"]))
            return {"status": "VERIFIED", "verification": "AUDIO_BLOCK_READBACK", "state": dict(self.adapter.payload),
                "sound": {"preview": {"id": "edit", "expiresAtEpochMs": time.time_ns()//1_000_000 + 5000}, "preset": preset(2)}}
        self.adapter.sound_command_authorized = sound
        self.actor = AudioCommands(self.adapter)
        self.actor.start()
        app = web.Application()
        app.router.add_get('/stream', lambda r: sound_command_stream(r, self.actor, "owner", lambda: self.authorized))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        self.adapter.block.set()
        await self.client.close()
        await self.actor.stop()

    def body(self, n, producer="test", kind="soundPreview"):
        command = {"type": kind, "previewId": "edit", "configGeneration": "config-1"}
        if kind == "soundPreview":
            command.update(scope="preset", expectedPresetRevision=0, expectedCalibrationRevision=0, model=preset(n))
        return request(n, command, producer=producer)

    async def connect(self):
        socket = await self.client.ws_connect('/stream', protocols=(SUBPROTOCOL,))
        ready = await socket.receive_json(timeout=1)
        self.assertEqual(ready, {"version": 1, "kind": "sound-command-ready", "serverSessionId": "gateway-test", "window": 1})
        return socket

    async def test_second_inflight_request_is_rejected_without_native_write(self):
        socket = await self.connect()
        await socket.send_json(self.body(1))
        self.assertEqual((await socket.receive_json(timeout=1))['status'], 'ACCEPTED')
        await asyncio.wait_for(self.reached.wait(), 1)
        await socket.send_json(self.body(2))
        reply = await socket.receive(timeout=1)
        self.assertIn(reply.type, (WSMsgType.CLOSE, WSMsgType.CLOSED))
        self.adapter.block.set()
        await asyncio.sleep(.03)
        self.assertEqual(self.adapter.writes, [])
        self.assertEqual(self.actor.CAPACITY, 64)
        self.assertEqual(self.actor.HISTORY, 256)

    async def test_next_request_runs_only_after_terminal_receipt(self):
        socket = await self.connect()
        await socket.send_json(self.body(1))
        self.assertEqual((await socket.receive_json(timeout=1))['status'], 'ACCEPTED')
        await asyncio.wait_for(self.reached.wait(), 1)
        self.adapter.block.set()
        self.assertEqual((await socket.receive_json(timeout=1))['status'], 'APPLIED')
        await socket.send_json(self.body(2))
        self.assertEqual((await socket.receive_json(timeout=1))['status'], 'ACCEPTED')
        self.assertEqual((await socket.receive_json(timeout=1))['status'], 'APPLIED')
        self.assertEqual([x['model']['eqPoints'][0]['gainDb'] for x in self.adapter.writes], [1, 2])

    async def test_disconnect_revokes_running_authority(self):
        socket = await self.connect()
        await socket.send_json(self.body(1))
        await socket.receive_json(timeout=1)
        await asyncio.wait_for(self.reached.wait(), 1)
        await socket.close()
        await asyncio.sleep(.01)
        self.adapter.block.set()
        await asyncio.sleep(.03)
        self.assertEqual(self.adapter.writes, [])

    async def test_discrete_work_and_producer_changes_are_rejected(self):
        for failure in ('discrete', 'producer', 'source'):
            socket = await self.connect()
            if failure == 'producer':
                await socket.send_json(self.body(1))
                await socket.receive_json(timeout=1)
                body = self.body(2, producer='other')
            else:
                body = self.body(1)
                if failure == 'source': body['serverSessionId'] = 'foreign'
                else: body['command'] = {"type": "soundSave", "previewId": "edit", "configGeneration": "config-1"}
            await socket.send_json(body)
            reply = await socket.receive(timeout=1)
            self.assertIn(reply.type, (WSMsgType.CLOSE, WSMsgType.CLOSED))
            await socket.close()

    async def test_revocation_closes_idle_stream_without_activity_renewal(self):
        socket = await self.connect()
        self.authorized = False
        reply = await socket.receive(timeout=1)
        self.assertIn(reply.type, (WSMsgType.CLOSE, WSMsgType.CLOSED))
        self.assertEqual(self.adapter.writes, [])

    async def test_protocol_is_required(self):
        with self.assertRaises(WSServerHandshakeError) as error:
            await self.client.ws_connect('/stream')
        self.assertEqual(error.exception.status, 426)


class SoundStreamBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from test_sound import SoundBoundaryTest
        await SoundBoundaryTest.asyncSetUp(self)

    async def asyncTearDown(self):
        from test_sound import SoundBoundaryTest
        await SoundBoundaryTest.asyncTearDown(self)

    async def test_real_route_requires_existing_authority_and_limits_connections(self):
        for headers in ({}, {"X-No5-Gateway-Key": "wrong"}, list(self.headers.items())*2):
            with self.assertRaises(WSServerHandshakeError) as failure:
                await self.client.ws_connect('/v1/audio/sound/stream', protocols=(SUBPROTOCOL,), headers=headers)
            self.assertEqual(failure.exception.status, 401)
        with self.assertRaises(WSServerHandshakeError) as failure:
            await self.client.ws_connect('/v1/audio/sound/stream', protocols=('wrong',), headers=self.headers)
        self.assertEqual(failure.exception.status, 426)
        sockets = []
        try:
            for _ in range(4):
                socket = await self.client.ws_connect('/v1/audio/sound/stream', protocols=(SUBPROTOCOL,), headers=self.headers)
                self.assertEqual((await socket.receive_json())['kind'], 'sound-command-ready')
                sockets.append(socket)
            with self.assertRaises(WSServerHandshakeError) as failure:
                await self.client.ws_connect('/v1/audio/sound/stream', protocols=(SUBPROTOCOL,), headers=self.headers)
            self.assertEqual(failure.exception.status, 503)
        finally:
            for socket in sockets: await socket.close()
        self.assertEqual(self.wire.calls, [])

    async def test_actual_adapter_readback_and_native_rejection_are_preserved(self):
        initial = self.audio.payload
        snapshot = await (await self.client.get('/v1/audio/sound', headers=self.headers)).json()
        self.assertTrue(snapshot['capabilities']['commandStream'])
        socket = await self.client.ws_connect('/v1/audio/sound/stream', protocols=(SUBPROTOCOL,), headers=self.headers)
        await socket.receive_json()
        body = request(1, {"type": "soundPreview", "previewId": "edit", "scope": "preset", "configGeneration": "config-1",
            "expectedPresetRevision": 0, "expectedCalibrationRevision": 0, "model": preset(2)}, producer="stream")
        body['serverSessionId'] = initial['serverSessionId']
        await socket.send_json(body)
        self.assertEqual((await socket.receive_json())['status'], 'ACCEPTED')
        completed = await socket.receive_json()
        self.assertEqual(completed['status'], 'APPLIED')
        self.assertEqual(completed['verification'], 'AUDIO_BLOCK_READBACK')
        self.assertTrue(completed['soundReadback']['state']['preset']['eqEnabled'])
        self.assertTrue(0 < completed['soundReadback']['remainingLeaseMs'] <= 5000)
        self.assertEqual(self.audio.payload['revision'], initial['revision'])
        self.wire.mode = 'reject_budget'
        body['requestId'] = 'stream:2';body['command']['model'] = preset(9, reserve=6)
        await socket.send_json(body)
        self.assertEqual((await socket.receive_json())['status'], 'ACCEPTED')
        rejected = await socket.receive_json()
        self.assertEqual(rejected['status'], 'REJECTED')
        self.assertEqual(rejected['reason'], 'EQ_COMBINED_GAIN_LIMIT')
        self.assertEqual(self.wire.state['preset'], preset(2))
        await socket.close()

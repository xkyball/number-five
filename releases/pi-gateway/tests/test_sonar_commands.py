import asyncio
from dataclasses import replace
import unittest
from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError
from no5_pi_gateway.sonar_commands import SonarCommands
from no5_pi_gateway.state import GatewayState


class SonarCommandsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.state = GatewayState(GatewayConfig(producer="m1", upstream_base_url="http://127.0.0.1:8787"), session_id="source")
        self.state.filter_supported = True
        self.writes = []; self.block = asyncio.Event(); self.block.set(); self.unknown = False
        async def exchange(base, mode):
            self.assertEqual(base, self.state.config.upstream_base_url)
            if mode is not None:
                self.writes.append(mode)
                await self.block.wait()
                if self.unknown: raise TimeoutError()
            return {"policy": {"mode": mode or "auto"}, "selection": {"effective_mode": "auto_raw", "active": False}}
        self.ring = SonarCommands(self.state, exchange)

    async def asyncTearDown(self): await self.ring.stop()

    def request(self, number, mode="auto", client="helm"):
        return {"version": 1, "serverSessionId": "source", "generation": 0, "clientId": client, "requestId": number, "mode": mode}

    async def done(self, identifier):
        for _ in range(100):
            value = self.ring.read("owner", identifier)
            if value["status"] not in {"ACCEPTED", "RUNNING"}: return value
            await asyncio.sleep(.001)
        self.fail("command did not finish")

    async def test_admission_coalesces_only_unstarted_goals_and_returns_numeric_ids(self):
        first = self.ring.submit("owner", self.request(1, "off"), lambda: True)
        last = self.ring.submit("owner", self.request(2, "on"), lambda: True)
        self.assertIs(type(last["commandId"]), int)
        self.assertEqual(self.writes, [])
        self.assertEqual(self.ring.read("owner", first["commandId"])["status"], "SUPERSEDED")
        self.ring.start()
        self.assertEqual((await self.done(last["commandId"]))["confirmedMode"], "on")
        self.assertEqual(self.writes, ["on"])

    async def test_blocked_backend_does_not_block_admission_or_source_cancellation(self):
        self.block.clear(); self.ring.start()
        first = self.ring.submit("owner", self.request(1, "off"), lambda: True)
        await asyncio.sleep(.005)
        second = self.ring.submit("owner", self.request(2, "on"), lambda: True)
        self.assertEqual(second["status"], "ACCEPTED")
        self.state.generation += 1; self.block.set()
        self.assertEqual((await self.done(first["commandId"]))["status"], "UNKNOWN")
        self.assertEqual((await self.done(second["commandId"]))["status"], "CANCELLED")
        self.assertEqual(self.writes, ["off"])

    async def test_unknown_is_not_repeated_and_owner_results_are_isolated(self):
        self.unknown = True; self.ring.start()
        value = self.ring.submit("owner", self.request(1), lambda: True)
        self.assertEqual((await self.done(value["commandId"]))["status"], "UNKNOWN")
        self.assertEqual(self.ring.submit("owner", self.request(1), lambda: True)["commandId"], value["commandId"])
        self.assertEqual(self.writes, ["auto"])
        self.assertIsNone(self.ring.read("foreign", value["commandId"]))

    async def test_bounded_capacity_retention_and_no_replay_after_eviction(self):
        values = [self.ring.submit("owner", self.request(1, client=f"producer{n}"), lambda: True) for n in range(65)]
        self.assertEqual(values[-1]["reason"], "BUSY")
        self.assertEqual(self.ring._size, 64)
        old = self.ring.submit("owner", self.request(2), lambda: True)
        for n in range(3, 310): self.ring.submit("owner", self.request(n), lambda: True)
        self.assertIsNone(self.ring.read("owner", old["commandId"]))
        with self.assertRaises(ProtocolError): self.ring.submit("owner", self.request(2), lambda: True)
        self.assertLessEqual(sum(v.status in {"APPLIED", "SUPERSEDED", "CANCELLED", "REJECTED", "UNKNOWN"} for v in self.ring._results.values()), 256)

    async def test_revocation_and_invalid_requests_never_write(self):
        value = self.ring.submit("owner", self.request(1), lambda: False)
        self.ring.start()
        self.assertEqual((await self.done(value["commandId"]))["status"], "CANCELLED")
        for invalid in [dict(self.request(2), mode="made_up"), dict(self.request(2), requestId=True), dict(self.request(2), gain=2)]:
            with self.assertRaises(ProtocolError): self.ring.submit("owner", invalid, lambda: True)
        self.assertEqual(self.writes, [])

    async def test_filter_effect_reason_is_host_selection_evidence_not_command_ack(self):
        async def exchange(base, mode):
            self.assertIsNone(mode)
            return {"policy": {"mode": "auto"}, "selection": {"effective_mode": "auto_filter", "active": True,
                    "reason": "confirmed non-multipath track fallback"}}
        self.ring.exchange = exchange
        value = await self.ring.snapshot()
        self.assertEqual(value['reason'], 'confirmed non-multipath track fallback')
        self.assertEqual(self.writes, [])
        async def long_reason(base, mode):
            return {"policy": {"mode": "auto"}, "selection": {"reason": "x" * 600}}
        self.ring.exchange = long_reason
        self.assertEqual(len((await self.ring.snapshot())['reason']), 512)
        async def bad_reason(base, mode):
            return {"policy": {"mode": "auto"}, "selection": {"reason": ["not", "text"]}}
        self.ring.exchange = bad_reason
        with self.assertRaises(ProtocolError): await self.ring.snapshot()

    async def test_read_policy_and_unsupported_producer_do_not_change_acquisition(self):
        value = await self.ring.snapshot()
        self.assertEqual(value["mode"], "auto")
        self.assertEqual(value["effectiveMode"], "auto_raw")
        self.state.config = replace(self.state.config, producer="m0")
        result = self.ring.submit("owner", self.request(1), lambda: True)
        self.assertEqual(result["reason"], "UNSUPPORTED")
        self.assertEqual(self.writes, [])


class SonarFilterBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import test_audio_stream as fixtures
        await fixtures.AudioStreamTest.asyncSetUp(self)
        self.headers = {"X-No5-Gateway-Key": fixtures.KEY}
        self.service = self.client.server.app["gateway_service"]
        await asyncio.sleep(.005)
        self.service.state.config = replace(self.service.state.config, producer="m1", upstream_base_url="http://127.0.0.1:18787")
        self.service.state.filter_supported = True
        self.block = asyncio.Event(); self.writes = []
        async def exchange(base, mode):
            if mode is not None:
                self.writes.append(mode); await self.block.wait()
            return {"policy": {"mode": mode or "auto"}, "selection": {"effective_mode": "auto_raw", "active": False}}
        self.service.sonar_commands.exchange = exchange

    async def asyncTearDown(self):
        import test_audio_stream as fixtures
        self.block.set()
        await fixtures.AudioStreamTest.asyncTearDown(self)

    async def test_202_precedes_backend_completion_and_get_result_never_repeats_write(self):
        body = {"version":1,"clientId":"helm","requestId":1,"mode":"on",
                "serverSessionId":self.service.state.server_session_id,"generation":self.service.state.generation}
        self.assertEqual((await self.client.get('/v1/sonar/filter')).status,401)
        response = await self.client.put('/v1/sonar/filter',json=body,headers=self.headers)
        self.assertEqual(response.status,202)
        receipt = await response.json(); self.assertIs(type(receipt['commandId']),int)
        self.assertEqual(receipt['status'],'ACCEPTED')
        self.block.set()
        for _ in range(100):
            response = await self.client.get('/v1/sonar/commands/'+str(receipt['commandId']),headers=self.headers)
            result = await response.json()
            if result['status']=='APPLIED':break
            await asyncio.sleep(.001)
        self.assertEqual(result['confirmedMode'],'on');self.assertEqual(self.writes,['on'])
        self.assertEqual((await self.client.get('/v1/sonar/commands/9999',headers=self.headers)).status,410)
        self.assertEqual((await self.client.put('/v1/sonar/filter',json=dict(body,gain=5),headers=self.headers)).status,400)

    async def test_adapter_sends_only_the_host_mode_field(self):
        from aiohttp import web
        from aiohttp.test_utils import TestServer
        bodies=[]
        async def host(request):
            self.assertEqual(request.method,'PUT');bodies.append(await request.json())
            return web.json_response({'policy':{'mode':'off'}})
        app=web.Application();app.router.add_put('/api/v1/detection/filter',host)
        server=TestServer(app);await server.start_server()
        try:
            result=await self.service.sonar_commands._exchange(str(server.make_url('/')).rstrip('/'),'off')
            self.assertEqual(result['policy']['mode'],'off');self.assertEqual(bodies,[{'mode':'off'}])
        finally:await server.close()

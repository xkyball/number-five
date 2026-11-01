"""Synthetic source-pressure proof; not a physical ping-rate or echo test."""
import asyncio
import contextlib
import json
import time
import unittest
from aiohttp import web
from aiohttp.test_utils import TestServer
from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import UPSTREAM_HEADER
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import SonardUpstream
from test_sonar_contract import status, latest


class SonarBurstTest(unittest.IsolatedAsyncioTestCase):
    PROOFS = {}
    async def exercise(self, *, lagged, switch=False, version='0.4.10'):
        host_count=0;host_generation=1;host_source='own';received=[];published=[];failures=[]
        finished=asyncio.Event();base=time.time_ns()
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
        async def get_status(_request):return web.json_response(status(version,source=host_source,generation=host_generation,ping=host_count))
        async def get_latest(_request):
            if not lagged:return web.Response(status=503)
            ping=max(1,host_count-3)
            body=latest(ping=ping,source=host_source,generation=host_generation)
            body['detection']['time_utc_ns']=base+(ping-1)*50_000_000
            return web.json_response(body)
        async def socket(request):
            nonlocal host_count,host_source,host_generation
            ws=web.WebSocketResponse();await ws.prepare(request)
            for number in range(1,21):
                if switch and number==11:host_source='native';host_generation=2
                host_count=number
                raw=UPSTREAM_HEADER.pack(1,1,40,number,base+(number-1)*50_000_000,27.,15.,.95,1,1024,0)+bytes([number,0])*1024
                await ws.send_bytes(raw)
                # Four close slots, then a gap: 20 Hz mean, burst delivery.
                await asyncio.sleep(.01 if number%4 else .17)
            async for _ in ws:pass
            return ws
        async def on_status(value):state.observe_status(value)
        async def on_connected():state.begin_connection()
        async def on_failure(value):failures.append(value)
        async def on_frame(frame):
            received.append(frame)
            binary=state.accept_ping(frame)
            if binary is not None:
                size=int.from_bytes(binary[:4],'little');metadata=json.loads(binary[4:4+size])
                published.append(metadata)
                self.assertEqual(binary[-2048:],frame.samples)
                self.assertLessEqual(size,4096)
            if len(received)==20:finished.set()
        app=web.Application();app.router.add_get('/api/v1/status',get_status)
        app.router.add_get('/api/v1/detection/latest',get_latest);app.router.add_get('/ws/v1/echogram',socket)
        server=TestServer(app);await server.start_server()
        config=GatewayConfig(producer='m1',upstream_base_url=str(server.make_url('/')).rstrip('/'),status_poll_s=.2)
        upstream=SonardUpstream(config,on_status,on_frame,on_connected,on_failure)
        task=asyncio.create_task(upstream.run())
        try:
            await asyncio.wait_for(finished.wait(),3)
            self.assertEqual(failures,[])
            self.assertEqual(host_count,20);self.assertEqual(len(received),20)
            self.assertEqual(len(published)+state.old_selection_frames_dropped,20)
            result={'hostPublished':host_count,'gatewayPublished':len(published),
                    'unconfirmedSource':state.unconfirmed_source_frames,
                    'oldSelectionDropped':state.old_selection_frames_dropped,'metadata':published}
            name='selectionSwitch' if switch else 'laggedAnalysis' if lagged else 'analysisUnavailable'
            self.PROOFS[name if version=='0.4.10' else version+'/'+name] = {
                key:value for key,value in result.items() if key!='metadata'}
            return result
        finally:
            await upstream.stop();task.cancel()
            with contextlib.suppress(asyncio.CancelledError):await task
            await server.close()
    async def test20hz_burst_without_optional_analysis_preserves_every_raw_frame(self):
        result=await self.exercise(lagged=False)
        self.assertEqual(result['gatewayPublished'],20);self.assertEqual(result['unconfirmedSource'],20)
        self.assertEqual(result['oldSelectionDropped'],0)
        self.assertTrue(all(row['pingSource'] is None and row['displaySelection'] is None for row in result['metadata']))
    async def test20hz_burst_with_late_analysis_does_not_deceive_source_or_drop_raw(self):
        result=await self.exercise(lagged=True)
        self.assertEqual(result['gatewayPublished'],20);self.assertEqual(result['oldSelectionDropped'],0)
        for row in result['metadata']:
            if row['displaySelection'] is not None:
                self.assertEqual(row['displaySelection']['lastPublishedPingId'],row['pingId'])
                self.assertEqual(row['pingSource'],'own')
    async def test_reviewed_0411_burst_retains_unchanged_wire_with_late_optional_analysis(self):
        result=await self.exercise(lagged=True,version='0.4.11')
        self.assertEqual(result['gatewayPublished'],20);self.assertEqual(result['oldSelectionDropped'],0)
        self.assertTrue(all(row['amplitude']['fullScaleCode']==4095 for row in result['metadata']))
    async def test_source_switch_drops_queued_old_context_but_new_raw_stream_recovers(self):
        result=await self.exercise(lagged=False,switch=True)
        self.assertGreater(result['oldSelectionDropped'],0)
        self.assertGreater(result['gatewayPublished'],0)
        self.assertTrue(all(row['pingSource'] is None for row in result['metadata']))

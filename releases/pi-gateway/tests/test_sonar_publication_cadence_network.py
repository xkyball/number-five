"""20-s synthetic HTTP/WS burst runs; no physical source, firmware or live server."""
import asyncio
from collections import deque
import contextlib
from copy import deepcopy
import json
import struct
import time
import unittest

from aiohttp import web
from aiohttp.test_utils import TestServer

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import UPSTREAM_HEADER
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import SonardUpstream,PUBLISHED_WAIT_S
from test_sonar_published import host_status,row,page


class PublicationCadenceNetworkTest(unittest.IsolatedAsyncioTestCase):
    PROOFS={}
    async def exercise(self,slow_status):
        count=0;rows=deque(maxlen=512);received=[];accepted=[];failures=[];queries=[];status_requests=0
        finished=asyncio.Event();producer_done=False;sent={};arrivals={};baseline=time.monotonic()
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
        def current_status():
            generation=2 if time.monotonic()-baseline>=10 else 1
            result=host_status(display=generation,selection=generation,ping=count,
                               source='all'if generation==2 else'own')
            result['host_package']['version']='0.4.15';return result
        async def get_status(_request):
            nonlocal status_requests
            status_requests+=1
            if slow_status and status_requests>2:await asyncio.sleep(.12)
            return web.json_response(current_status())
        async def latest(_request):return web.Response(status=503)
        async def published(request):
            start=time.monotonic();index=len(queries);cursor=int(request.query['after_ping_id'])
            self.assertEqual(request.query['limit'],'64')
            # A115ms read every ten requests stays alive under the independent1s
            # ceiling; it must not discard rows still inside each250ms frame join.
            delay=.115 if index%10==7 else .01
            record={'start':start,'requestedDelayMs':delay*1000,'cursor':cursor};queries.append(record)
            await asyncio.sleep(delay)
            candidates=[deepcopy(r)for r in rows if r['ping_id']>cursor][:64]
            body=page(candidates,cursor=cursor,oldest=rows[0]['ping_id']if rows else None,
                      latest_id=rows[-1]['ping_id']if rows else None)
            record['response']=time.monotonic()
            return web.json_response(body)
        async def socket(request):
            nonlocal count,producer_done,baseline
            ws=web.WebSocketResponse();await ws.prepare(request);baseline=time.monotonic()
            while time.monotonic()-baseline<20:
                count+=1;stamp=time.time_ns();capture=16384;display=16384 if count%7==0 else 2048
                generation=2 if time.monotonic()-baseline>=10 else 1
                maximum=struct.unpack('<f',struct.pack('<f',display*.0015))[0]
                record=row(count,stamp=stamp,display=generation,selection=generation)
                record.update(sample_count=capture,sample_period_ns=2000)
                record['display_window']={'schema':'no5.display-window/1','ping_id':count,
                    'capture_sample_count':capture,'display_sample_count':display,'display_start_sample':0,
                    'display_end_sample_exclusive':display,'capture_range_m':capture*.0015,'display_range_m':maximum,
                    'tx_reference_verified':True,'reason':'full_capture'if display==capture else'qualified_depth_window',
                    'raw_samples_modified':False,'detector_uses_full_capture':True}
                rows.append(record);sent[count]=time.monotonic()
                await ws.send_bytes(UPSTREAM_HEADER.pack(1,1,40,count,stamp,maximum,float('nan'),0.,1,1024,0)+bytes(2048))
                # Three slots in150ms:20Hz mean, with a short burst followed by a gap.
                await asyncio.sleep((.024,.024,.102)[(count-1)%3])
            producer_done=True
            if len(received)>=count:finished.set()
            async for _ in ws:pass
            return ws
        async def on_status(value):state.observe_status(value)
        async def connected():state.begin_connection()
        async def failure(value):failures.append(value)
        async def on_frame(value):
            received.append(value);arrivals[value.ping_id]=time.monotonic()
            binary=state.accept_ping(value)
            if binary is not None:
                size=int.from_bytes(binary[:4],'little');accepted.append(json.loads(binary[4:4+size]))
                self.assertLessEqual(size,4096);self.assertEqual(binary[-2048:],value.samples)
            if producer_done and len(received)>=count:finished.set()
        app=web.Application();app.router.add_get('/api/v1/status',get_status)
        app.router.add_get('/api/v1/detection/latest',latest)
        app.router.add_get('/api/v1/sonar/published-pings',published)
        app.router.add_get('/ws/v1/echogram',socket)
        async with TestServer(app)as server:
            config=GatewayConfig(producer='m1',upstream_base_url=str(server.make_url('/')).rstrip('/'),status_poll_s=1.)
            upstream=SonardUpstream(config,on_status,on_frame,connected,failure);task=asyncio.create_task(upstream.run())
            try:
                await asyncio.wait_for(finished.wait(),23.)
                self.assertFalse(failures)
                self.assertGreaterEqual(count,360);self.assertEqual(len(received),count)
                unknown=sum(v.get('pingSource')is None for v in accepted)
                proof={'durationSeconds':20,'statusResponseDelayMs':120 if slow_status else 0,
                    'hostFrames':count,'gatewayFrames':len(accepted),'unknownSourceFrames':unknown,
                    'statusRequests':status_requests,'publicationRequests':len(queries),
                    'publicationResponsesOver100ms':sum(q['requestedDelayMs']>100 for q in queries),
                    'displayWindowSizes':sorted({v.publication.display_window.display_sample_count for v in received if v.publication}),
                    'serverGenerations':len({v['generation']for v in accepted}),
                    'maxDispatchMs':round(max(arrivals[i]-sent[i]for i in arrivals)*1000,3),
                    'oldSourceEpochDropped':state.old_selection_frames_dropped}
                self.PROOFS['slow-status'if slow_status else'fast-status']=proof
                self.assertEqual(unknown,0,proof)
                self.assertEqual(proof['displayWindowSizes'],[2048,16384]);self.assertEqual(proof['serverGenerations'],2)
                self.assertLessEqual(len(queries),205) # Fixed10Hz cadence, no per-frame fetch/catch-up burst.
                return proof
            finally:
                await upstream.stop();task.cancel()
                with contextlib.suppress(asyncio.CancelledError):await task
    async def test_20s_fast_and_slow_status_with_bursts_slow_reads_windows_and_source_epoch(self):
        await asyncio.gather(self.exercise(False),self.exercise(True))

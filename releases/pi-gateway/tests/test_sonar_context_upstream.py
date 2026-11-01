"""Atomic .16 source remains independent of optional HTTP metadata and status latency."""
import asyncio
from copy import deepcopy
from dataclasses import replace
import contextlib
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import aiohttp

from no5_pi_gateway.protocol import ProtocolError
from no5_pi_gateway.sonar_context import parse_atomic_echogram
from no5_pi_gateway.sonar_contract import display_selection
from no5_pi_gateway.upstream import SonardUpstream,upstream_websocket_url
from no5_pi_gateway.state import GatewayState
from test_sonar_context import context_case,encode,modified_m1,atomic_status
from test_sonar_published import adapter_for


def adapter(root=None):
    result=adapter_for(atomic_status(root));result._host_package_version='0.4.16';return result


def envelope(root=None,m1=None,*,sequence=1,ping=None,segment=None,display=None,selection=None):
    root,raw=context_case()if root is None else(deepcopy(root),m1)
    root['publication_sequence']=str(sequence);row=root['published_ping']
    if ping is not None:
        row['ping_id']=row['display_window']['ping_id']=str(ping);raw=modified_m1(raw,ping=ping)
    if segment is not None:row['segment_id']=segment
    if display is not None:row['display_generation']=display
    if selection is not None:row['selection_generation']=selection
    return parse_atomic_echogram(encode(root,raw))


class AtomicEpochTest(unittest.IsolatedAsyncioTestCase):
    async def fetch(self,value,payload,*,entered=None,release=None):
        class Response:
            status=200
            async def __aenter__(self):
                if entered:entered.set()
                if release:await release.wait()
                return self
            async def __aexit__(self,*_):return False
        class Session:
            def get(self,*_,**__):return Response()
        async def read(*_):return payload
        with patch('no5_pi_gateway.upstream.read_json',side_effect=read):await value._fetch_status(Session())

    async def test_new_segment_status_without_ping_retires_previous_frame_context(self):
        a=adapter();first=envelope();self.assertIsNotNone(a._atomic_frame(first))
        state=GatewayState(a._config);state.begin_connection();state.observe_status(atomic_status())
        a._on_status=lambda payload:self.observe(state,payload)
        first_frame=replace(a._atomic_frame(envelope(sequence=2,ping=8)),host_package_version='0.4.16',
                            amplitude=a._amplitude,selection_generation_hint=first.publication.display_generation)
        self.assertIsNotNone(state.accept_ping(a._with_publication(first_frame)));old_generation=state.generation
        status=atomic_status();status['session']['segment_id']='22222222-2222-4222-8222-222222222222';status['state']='WAIT_PICO'
        await self.fetch(a,status)
        self.assertGreater(state.generation,old_generation);self.assertIsNone(state._last_ping)
        self.assertEqual(a._atomic_target_epoch[0][1],status['session']['segment_id'])
        self.assertIsNone(a._atomic_frame(envelope(sequence=3,ping=9)))
        self.assertEqual(a._atomic_target_epoch[0][1],status['session']['segment_id'])
        self.assertIsNotNone(a._atomic_frame(envelope(sequence=1,ping=1,segment=status['session']['segment_id'])))

    async def observe(self,state,value):state.observe_status(value)

    async def test_status_response_from_before_new_envelope_cannot_restore_old_context(self):
        a=adapter();self.assertIsNotNone(a._atomic_frame(envelope()))
        entered=asyncio.Event();release=asyncio.Event();observed=[]
        async def on_status(value):observed.append(value)
        a._on_status=on_status
        old=atomic_status();task=asyncio.create_task(self.fetch(a,old,entered=entered,release=release))
        await asyncio.wait_for(entered.wait(),.1)
        new_segment='22222222-2222-4222-8222-222222222222'
        self.assertIsNone(a._atomic_frame(envelope(sequence=1,ping=1,segment=new_segment)))
        release.set();await task
        self.assertEqual(observed,[]);self.assertEqual(a._atomic_target_epoch[0][1],new_segment)
        new=atomic_status();new['session']['segment_id']=new_segment;await self.fetch(a,new)
        self.assertEqual(len(observed),1)
        self.assertIsNotNone(a._atomic_frame(envelope(sequence=2,ping=2,segment=new_segment)))

    async def test_status_display_floor_precedes_first_new_frame_and_rejects_older_frame(self):
        a=adapter();self.assertIsNotNone(a._atomic_frame(envelope()))
        status=atomic_status();status['display_selection']['generation']+=1
        await self.fetch(a,status)
        floor=a._atomic_target_epoch
        self.assertIsNone(a._atomic_frame(envelope(sequence=2,ping=8)))
        self.assertEqual(a._atomic_target_epoch,floor)
        self.assertIsNotNone(a._atomic_frame(envelope(sequence=3,ping=9,display=floor[1])))

    async def test_unknown_actual_source_is_not_selected_source_and_sequence_gaps_are_explicit(self):
        root,m1=context_case();root['published_ping'].update(actual_source=None,source_evidence='unconfirmed')
        a=adapter(root);value=a._atomic_frame(envelope(root,m1));value=replace(value,host_package_version='0.4.16',amplitude=a._amplitude)
        self.assertIsNone(a._with_publication(value).ping_source)
        root,m1=context_case();a=adapter(root)
        self.assertIsNotNone(a._atomic_frame(envelope(sequence=10)))
        hidden_ids=a._atomic_frame(envelope(sequence=11,ping=100));self.assertEqual(hidden_ids.publication_sequence_gap,0)
        frame=a._atomic_frame(envelope(sequence=14,ping=104));self.assertEqual(frame.publication_sequence_gap,2)
        self.assertEqual(a.atomic_sequence_gaps,2)
        self.assertIsNone(a._atomic_frame(envelope(sequence=14,ping=104)))
        self.assertIsNone(a._atomic_frame(envelope(sequence=13,ping=103)))
        self.assertEqual(a._atomic_sequence,14)
        a._reset_atomic_context();self.assertIsNotNone(a._atomic_frame(envelope(sequence=1,ping=1)))
        self.assertEqual(a.atomic_sequence_gaps,0)

    async def test_old_selection_and_replayed_sequence_never_advance_confirmed_floor(self):
        a=adapter();self.assertIsNotNone(a._atomic_frame(envelope(sequence=10)))
        self.assertIsNone(a._atomic_frame(envelope(sequence=9,display=3,selection=2)))
        self.assertEqual(a._atomic_target_epoch[1:3],(2,1))
        changed=atomic_status();changed['display_selection'].update(source='native',generation=3,selection_generation=2)
        await self.fetch(a,changed);floor=a._atomic_target_epoch
        self.assertIsNone(a._atomic_frame(envelope(sequence=11,ping=8)))
        self.assertEqual(a._atomic_target_epoch,floor)
        self.assertEqual(a._atomic_retired_contexts.maxlen,64)

    async def test_only_exact_atomic_versions_use_new_endpoint_old_versions_stay_legacy(self):
        for version in ('0.4.16','0.4.17','0.4.18'):
            self.assertEqual(upstream_websocket_url('http://127.0.0.1:8787',version),'ws://127.0.0.1:8787/ws/v1/echogram-context')
        for version in (None,'0.4.13','0.4.14','0.4.15','0.4.32','0.4.17-dev'):
            self.assertEqual(upstream_websocket_url('https://example.test',version),'wss://example.test/ws/v1/echogram')


    async def test_queued_old_frame_is_dropped_before_callback_after_status_epoch_change(self):
        from collections import deque
        a=adapter();old=a._atomic_frame(envelope())
        old=replace(old,host_package_version='0.4.16',amplitude=a._amplitude)
        newer=atomic_status();newer['session']['segment_id']='22222222-2222-4222-8222-222222222222'
        await self.fetch(a,newer);pending=deque([(old,asyncio.get_running_loop().time())]);emitted=[]
        async def on_frame(value):emitted.append(value)
        a._on_frame=on_frame;wake=asyncio.Event();wake.set()
        task=asyncio.create_task(a._analysis_loop(None,pending,wake,asyncio.Lock()))
        try:
            await asyncio.sleep(.03);self.assertEqual(list(pending),[]);self.assertEqual(emitted,[])
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):await task


class AtomicWorkerLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def test_status_worker_failure_propagates_and_joins_every_sibling(self):
        a=adapter();a._config=replace(a._config,status_poll_s=.2);a._atomic_status_wake.set();entered=asyncio.Event();cancelled=asyncio.Event()
        class Socket:
            async def receive(self,**_):
                entered.set()
                try:await asyncio.Event().wait()
                finally:cancelled.set()
        async def fail(_):raise ProtocolError('synthetic invalid atomic status')
        a._fetch_status=fail
        baseline=asyncio.all_tasks()
        with self.assertRaisesRegex(ProtocolError,'synthetic invalid'):
            await asyncio.wait_for(a._receive(Socket(),None),.2)
        self.assertTrue(entered.is_set());self.assertTrue(cancelled.is_set())
        self.assertEqual(asyncio.all_tasks()-baseline,set())

    async def test_idle_disconnect_cancels_outstanding_status_without_an_orphan(self):
        a=adapter();a._config=replace(a._config,status_poll_s=.2);a._atomic_status_wake.set();entered=asyncio.Event();cancelled=asyncio.Event()
        class Socket:
            async def receive(self,**_):
                await entered.wait();return SimpleNamespace(type=aiohttp.WSMsgType.CLOSED)
        async def slow(_):
            entered.set()
            try:await asyncio.Event().wait()
            finally:cancelled.set()
        a._fetch_status=slow;baseline=asyncio.all_tasks()
        with self.assertRaisesRegex(ProtocolError,'websocket closed'):
            await asyncio.wait_for(a._receive(Socket(),None),.2)
        self.assertTrue(cancelled.is_set());self.assertEqual(asyncio.all_tasks()-baseline,set())

    async def test_status_wakeup_is_serial_and_rate_limited(self):
        a=adapter();a._config=replace(a._config,status_poll_s=.2);a._atomic_status_wake.set();calls=[];active=maximum=0
        async def fetch(_):
            nonlocal active,maximum
            active+=1;maximum=max(maximum,active);calls.append(asyncio.get_running_loop().time())
            await asyncio.sleep(.02);active-=1
            if len(calls)==3:await a.stop()
            a._atomic_status_wake.set()
        a._fetch_status=fetch;await asyncio.wait_for(a._atomic_status_loop(None),.4)
        self.assertEqual(maximum,1);self.assertEqual(len(calls),3)
        self.assertTrue(all(b-a>=.115 for a,b in zip(calls,calls[1:])))


class AtomicActualTransportTest(unittest.IsolatedAsyncioTestCase):
    async def exercise(self,atomic=True,*,oversize=False,stale_analysis=False):
        from aiohttp import web
        from aiohttp.test_utils import TestServer
        import time
        from no5_pi_gateway.configuration import GatewayConfig
        roots=[];raws=[];responses=[];received=[];failures=[];requests=[];status_reads=0
        done=asyncio.Event();all_sent=asyncio.Event();status_started=asyncio.Event();status_finished=asyncio.Event()
        status_base=atomic_status();active=max_active=0
        async def status(_):
            nonlocal status_reads
            status_reads+=1
            if atomic and status_reads>2:
                status_started.set();await asyncio.sleep(.4);status_finished.set()
            value=deepcopy(status_base);value['host_package']['version']='0.4.16'if atomic else'0.4.15'
            return web.json_response(value)
        async def optional(_):
            if stale_analysis:
                from test_sonar_contract import latest
                return web.json_response(latest(ping=7))
            return web.Response(status=503)
        async def published(request):
            from test_sonar_published import page
            requests.append(int(request.query['after_ping_id']));await asyncio.sleep(.4)
            rows=[]
            for root in roots:
                row=deepcopy(root['published_ping']);row['ping_id']=int(row['ping_id']);row['time_utc_ns']=int(row['time_utc_ns']);row['display_window']['ping_id']=int(row['display_window']['ping_id'])
                if row['ping_id']>requests[-1]:rows.append(row)
            return web.json_response(page(rows,cursor=requests[-1]))
        async def stream(request):
            nonlocal active,max_active
            ws=web.WebSocketResponse();await ws.prepare(request);active+=1;max_active=max(max_active,active)
            try:
                for index,sequence in enumerate([1,2,4,5,6,7]):
                    root,m1=context_case();stamp=time.time_ns()
                    root['publication_sequence']=str(sequence);root['published_ping']['ping_id']=str(7+index)
                    root['published_ping']['time_utc_ns']=str(stamp);root['published_ping']['display_window']['ping_id']=str(7+index)
                    m1=modified_m1(m1,ping=7+index,utc=stamp);roots.append(root);raws.append(m1)
                    if atomic:
                        metadata=__import__('json').dumps(root,separators=(',',':')).encode()
                        metadata+=b' '*(4096-len(metadata))
                        wire=encode(root,m1,metadata=metadata)
                        if oversize:wire+=b'x'
                    else:wire=m1
                    await ws.send_bytes(wire);await asyncio.sleep(.05)
                all_sent.set()
                async for _ in ws:pass
            except(ConnectionError,RuntimeError):pass
            finally:active-=1
            return ws
        async def unused(*_):pass
        async def on_frame(value):
            received.append(value)
            responses.append((time.monotonic(),status_started.is_set(),status_finished.is_set()))
            if len(received)==6:done.set()
        async def failure(reason):failures.append(reason);done.set()
        app=web.Application();app.router.add_get('/api/v1/status',status)
        app.router.add_get('/api/v1/detection/latest',optional);app.router.add_get('/api/v1/sonar/published-pings',published)
        app.router.add_get('/ws/v1/echogram-context',stream);app.router.add_get('/ws/v1/echogram',stream)
        async with TestServer(app)as server:
            source=SonardUpstream(GatewayConfig(producer='m1',upstream_base_url=str(server.make_url('/')).rstrip('/'),
                                                status_poll_s=.2 if atomic else 1.),unused,on_frame,unused,failure)
            task=asyncio.create_task(source.run())
            try:
                await asyncio.wait_for(done.wait(),2.)
                if not oversize:
                    self.assertEqual(failures,[]);self.assertEqual(len(received),6)
                    self.assertTrue(all(f.samples==r[40:]for f,r in zip(received,raws)))
                    self.assertEqual(max_active,1)
                return received,requests,responses,failures
            finally:
                await source.stop();task.cancel()
                with contextlib.suppress(asyncio.CancelledError):await task

    async def test_atomic_source_complete_without_published_http_and_status_does_not_block(self):
        received,requests,responses,_=await self.exercise()
        self.assertEqual(requests,[])
        self.assertTrue(all(f.ping_source=='own'and f.publication is not None for f in received))
        self.assertEqual([f.publication_sequence_gap for f in received],[0,0,1,0,0,0])
        self.assertTrue(any(started and not finished for _,started,finished in responses))

    async def test_400ms_http_legacy_control_has_truthful_unknown_and_no_relabel(self):
        received,requests,_,_=await self.exercise(False)
        self.assertTrue(requests);self.assertTrue(any(f.ping_source is None for f in received))
        self.assertTrue(all(f.publication_sequence is None for f in received))

    async def test_6200_plus_one_transport_cap_does_not_accept_trailer(self):
        received,_,_,failures=await self.exercise(oversize=True)
        self.assertEqual(received,[]);self.assertTrue(failures)

    async def test_stale_http_analysis_does_not_replace_atomic_source_or_depth(self):
        received,requests,_,_=await self.exercise(stale_analysis=True)
        self.assertEqual(requests,[])
        self.assertTrue(all(f.ping_source=='own'for f in received))
        self.assertTrue(all(f.presentation is None for f in received))

"""Publication cadence cannot add another whole poll period after a bounded fetch."""
import asyncio
import unittest
from unittest.mock import patch

from test_sonar_published import adapter_for,host_status
from no5_pi_gateway.upstream import PUBLISHED_POLL_S


class PublicationPollCadenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_first_frame_after_idle_does_not_wait_for_an_unused_poll_slot(self):
        adapter=adapter_for(host_status());calls=[];clock=[0.];pending=[]
        class Loop:
            def time(self):return clock[0]
        class Wake:
            def clear(self):pass
            def set(self):pass
            async def wait(self):clock[0]=.075;pending.append(object())
        async def fetch(_session,_pending):calls.append(clock[0]);adapter._stop.set()
        async def sleep(delay):
            # Model a frame arriving during a wasted idle polling sleep.
            if not pending:pending.append(object())
            clock[0]+=delay
        adapter._fetch_published=fetch
        with patch('no5_pi_gateway.upstream.asyncio.get_running_loop',return_value=Loop()), \
             patch('no5_pi_gateway.upstream.asyncio.sleep',side_effect=sleep):
            await adapter._publication_loop(None,pending,Wake())
        self.assertEqual(calls,[.075])

    async def test_one_100ms_read_does_not_add_an_extra_100ms_sleep(self):
        adapter=adapter_for(host_status());calls=[];sleeps=[];clock=[0.]
        class Loop:
            def time(self):return clock[0]
        async def fetch(_session,_pending):
            calls.append(clock[0])
            if len(calls)==1:clock[0]+=PUBLISHED_POLL_S # A read that consumes one full poll period.
            else:adapter._stop.set()
        async def sleep(delay):
            self.assertGreaterEqual(delay,0);sleeps.append(delay);clock[0]+=delay
        adapter._fetch_published=fetch
        with patch('no5_pi_gateway.upstream.asyncio.get_running_loop',return_value=Loop()), \
             patch('no5_pi_gateway.upstream.asyncio.sleep',side_effect=sleep):
            await adapter._publication_loop(None,[object()],asyncio.Event())
        self.assertAlmostEqual(calls[1]-calls[0],PUBLISHED_POLL_S,places=9)
        self.assertEqual(len(calls),2)

    async def test_short_requests_do_not_accumulate_request_latency(self):
        calls=await self.timeline([.025,.025,.025,0.])
        for actual,expected in zip(calls,[0.,.1,.2,.3]):self.assertAlmostEqual(actual,expected,places=9)

    async def test_long_event_loop_stall_skips_slots_without_catch_up_burst(self):
        calls=await self.timeline([1.05,0.,0.,0.])
        for actual,expected in zip(calls,[0.,1.05,1.15,1.25]):self.assertAlmostEqual(actual,expected,places=9)

    async def test_stop_during_fetch_does_not_sleep_or_fetch_again(self):
        self.assertEqual(await self.timeline([0.]),[0.])

    async def timeline(self,durations):
        adapter=adapter_for(host_status());calls=[];clock=[0.];sleeps=[]
        class Loop:
            def time(self):return clock[0]
        async def fetch(_session,_pending):
            calls.append(clock[0]);clock[0]+=durations[len(calls)-1]
            if len(calls)==len(durations):adapter._stop.set()
        async def sleep(delay):
            self.assertGreaterEqual(delay,0);sleeps.append(delay);clock[0]+=delay
        adapter._fetch_published=fetch
        with patch('no5_pi_gateway.upstream.asyncio.get_running_loop',return_value=Loop()), \
             patch('no5_pi_gateway.upstream.asyncio.sleep',side_effect=sleep):
            await adapter._publication_loop(None,[object()],asyncio.Event())
        self.assertEqual(len(sleeps),len(durations)-1)
        return calls

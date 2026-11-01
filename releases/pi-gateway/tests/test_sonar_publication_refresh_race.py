"""A status refresh must not hide an already validated per-ping epoch record."""
import asyncio
from collections import deque
from contextlib import suppress
from dataclasses import replace
import unittest
from unittest.mock import patch

from no5_pi_gateway.sonar_contract import display_selection
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import PUBLISHED_WAIT_S
from no5_pi_gateway.sonar_published import parse_page
from test_sonar_published import adapter_for, host_status, new_frame, page, row


class PublicationRefreshRaceTest(unittest.IsolatedAsyncioTestCase):
    async def test_old_or_other_epoch_negative_read_cannot_shortcut_the_join(self):
        for other_epoch in (False, True):
            adapter = adapter_for(host_status())
            adapter._published.accept(parse_page(page([row()]), after_ping_id=0))
            loop = asyncio.get_running_loop(); start = loop.time()
            context = (adapter._host_context, 2 if other_epoch else 1)
            adapter._analysis_unavailable = (context, start + 1 if other_epoch else start - 1)
            pending = deque([(new_frame(), start)]); received = []
            async def receive(value): received.append(value); adapter._stop.set()
            adapter._on_frame = receive
            await asyncio.wait_for(adapter._analysis_loop(None, pending, asyncio.Event(), asyncio.Lock()), 1.)
            self.assertGreaterEqual(loop.time() - start, PUBLISHED_WAIT_S)
            self.assertEqual(len(received), 1)

    async def test_fresh_unavailable_optional_analysis_does_not_delay_confirmed_raw_profile(self):
        adapter = adapter_for(host_status())
        adapter._published.accept(parse_page(page([row()]), after_ping_id=0))
        item = new_frame(); pending = deque([(item, asyncio.get_running_loop().time())])
        received = []
        async def no_analysis(_): return None
        async def receive(value): received.append(value); adapter._stop.set()
        adapter._fetch_analysis = no_analysis; adapter._on_frame = receive
        wake = asyncio.Event(); wake.set()
        optional = asyncio.create_task(adapter._optional_analysis_loop(None, pending, wake))
        try:
            await asyncio.wait_for(adapter._analysis_loop(None, pending, wake, asyncio.Lock()), .08)
            self.assertEqual(len(received), 1)
            self.assertEqual(received[0].samples, item.samples)
            self.assertEqual(received[0].ping_source, 'own')
            self.assertIsNone(received[0].presentation)
        finally:
            optional.cancel()
            with suppress(asyncio.CancelledError): await optional

    async def test_validated_epoch_record_visible_before_status_refresh_completes(self):
        old = host_status(display=1, selection=1)
        adapter = adapter_for(old)
        refresh_started, finish_refresh = asyncio.Event(), asyncio.Event()
        newer = host_status(display=2, selection=2, source='all')
        async def refresh(_):
            refresh_started.set()
            await finish_refresh.wait()
            adapter._selection = display_selection(newer['display_selection'])
            adapter._selection_generation = 2
        adapter._fetch_status = refresh
        class Response:
            status = 200
            async def __aenter__(self): return self
            async def __aexit__(self, *_): return False
        class Session:
            def get(self, *_args, **_kwargs): return Response()
        async def read(*_): return page([row(display=2, selection=2)])
        pending = deque([(new_frame(old), asyncio.get_running_loop().time())])
        with patch('no5_pi_gateway.upstream.read_json', side_effect=read):
            task = asyncio.create_task(adapter._fetch_published(Session(), pending))
            try:
                await asyncio.wait_for(refresh_started.wait(), .2)
                # The page already passed parser, exact context and immutable
                # cache validation. An unrelated slow status cannot conceal it.
                matched = adapter._published.match(pending[0][0])
                self.assertIsNotNone(matched)
                self.assertEqual(matched.display_generation, 2)
                joined = adapter._with_publication(pending[0][0])
                self.assertIsNotNone(joined.publication)
                self.assertIsNone(joined.ping_source)  # No invented selected source.
                state = GatewayState(adapter._config)
                state.begin_connection(); state.observe_status(old)
                self.assertIsNone(state.accept_ping(joined))  # Existing epoch guard.
                finish_refresh.set(); await task
                joined = adapter._with_publication(pending[0][0])
                state.observe_status(newer)
                self.assertIsNotNone(state.accept_ping(joined))
                self.assertEqual(joined.ping_source, 'own')
            finally:
                finish_refresh.set()
                task.cancel()
                with suppress(asyncio.CancelledError): await task

    async def test_slow_status_cannot_turn_timely_exact_epoch_evidence_into_unknown_old_epoch(self):
        old = host_status(display=1, selection=1)
        adapter = adapter_for(old)
        started, release = asyncio.Event(), asyncio.Event()
        item = new_frame(old)
        # The immutable page is already available within the unchanged250ms
        # join bound; the120ms status request would otherwise run past it.
        pending = deque([(item, asyncio.get_running_loop().time() - .20)])
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(old)
        delivered, exposed = [], []
        async def refresh(_): started.set(); await release.wait()
        async def receive(value):
            delivered.append(value)
            if state.accept_ping(value) is not None: exposed.append(value)
            adapter._stop.set()
        adapter._fetch_status = refresh; adapter._on_frame = receive
        class Response:
            status = 200
            async def __aenter__(self): return self
            async def __aexit__(self, *_): return False
        class Session:
            def get(self, *_args, **_kwargs): return Response()
        async def read(*_): return page([row(display=2, selection=2)])
        with patch('no5_pi_gateway.upstream.read_json', side_effect=read):
            fetch = asyncio.create_task(adapter._fetch_published(Session(), pending))
            try:
                await asyncio.wait_for(started.wait(), .2)
                await asyncio.wait_for(adapter._analysis_loop(None, pending, asyncio.Event(), asyncio.Lock()), .15)
                self.assertEqual(len(delivered), 1)
                self.assertIsNotNone(delivered[0].publication)
                self.assertEqual(exposed, [])  # Proven epoch2 cannot leak into epoch1.
                self.assertEqual(state.unconfirmed_source_frames, 0)
                self.assertEqual(state.old_selection_frames_dropped, 1)
            finally:
                release.set(); await fetch

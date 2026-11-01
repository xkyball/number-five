"""Exercise the actual aiohttp transport with a maximum legal sonar frame."""
import asyncio
import contextlib
import struct
import time
import unittest

from aiohttp import web

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.upstream import SonardUpstream
from no5_pi_gateway.protocol import UPSTREAM_HEADER
from test_sonar_presentation import analysis, UTC_NS


class UpstreamWebSocketTests(unittest.IsolatedAsyncioTestCase):
    async def _enhanced_stream(self, *, lagged=False, unavailable=False):
        received = asyncio.Event()
        frames, failures = [], []
        polls = 0
        ready_at = None

        async def status(_request):
            # The inherited M1 version is intentionally misleading.
            return web.json_response({'source': 'pico', 'state': 'RUNNING', 'version': '0.2.0',
                                      'host_package': {'name': 'no5-sonard-openecho', 'version': '0.3.5'}})

        async def latest(_request):
            nonlocal polls
            polls += 1
            if unavailable:
                return web.Response(status=503)
            payload = analysis(ping_id=6 if lagged and (ready_at is None or time.monotonic() < ready_at) else 7)
            return web.json_response(payload)

        async def stream(request):
            nonlocal ready_at
            socket = web.WebSocketResponse(); await socket.prepare(request)
            ready_at = time.monotonic() + .06
            payload = UPSTREAM_HEADER.pack(1, 1, 40, 7, UTC_NS, 27.0, 15.0, .95, 129, 1024, 0) + bytes(2048)
            await socket.send_bytes(payload)
            async for _message in socket:
                pass
            return socket

        async def noop(*_args):
            pass

        async def on_frame(frame):
            frames.append(frame); received.set()

        async def on_failure(reason):
            failures.append(reason)

        app = web.Application()
        app.router.add_get('/api/v1/status', status)
        app.router.add_get('/api/v1/detection/latest', latest)
        app.router.add_get('/ws/v1/echogram', stream)
        runner = web.AppRunner(app); await runner.setup()
        site = web.TCPSite(runner, '127.0.0.1', 0); await site.start()
        port = site._server.sockets[0].getsockname()[1]
        upstream = SonardUpstream(GatewayConfig(producer='m1', upstream_base_url=f'http://127.0.0.1:{port}'),
                                  noop, on_frame, noop, on_failure)
        task = asyncio.create_task(upstream.run())
        started = time.monotonic()
        try:
            await asyncio.wait_for(received.wait(), 2)
            return frames, failures, polls, time.monotonic() - started
        finally:
            await upstream.stop(); task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await runner.cleanup()

    async def test_lagging_http_analysis_is_joined_to_its_exact_binary_ping(self):
        frames, failures, polls, elapsed = await self._enhanced_stream(lagged=True)
        self.assertEqual(failures, [])
        self.assertGreater(polls, 1)
        self.assertGreaterEqual(elapsed, .06)
        self.assertEqual(frames[0].host_package_version, '0.3.5')
        self.assertEqual(frames[0].presentation['pingId'], 7)
        self.assertEqual(frames[0].presentation['bottom']['confidence'], .55)
        self.assertEqual(frames[0].samples, bytes(2048))

    async def test_failed_analysis_does_not_disconnect_or_use_the_legacy_bottom(self):
        frames, failures, polls, elapsed = await self._enhanced_stream(unavailable=True)
        self.assertEqual(failures, [])
        self.assertGreater(polls, 1)
        self.assertLess(elapsed, .9)
        self.assertTrue(frames[0].analysis_supported)
        self.assertIsNone(frames[0].presentation)
        self.assertEqual(frames[0].samples, bytes(2048))

    async def test_receives_full_1024_bin_sonar_frame(self):
        payload = struct.pack('<BBHQqfffIHH', 1, 1, 40, 7, time.time_ns(),
                              30.0, 8.0, 0.9, 0, 1024, 0) + bytes(2048)
        received = asyncio.Event()
        frames, failures = [], []

        async def status(_request):
            return web.json_response({'milestone': 'M0', 'state': 'SIMULATING'})

        async def stream(request):
            socket = web.WebSocketResponse()
            await socket.prepare(request)
            await socket.send_bytes(payload)
            async for _message in socket:
                pass
            return socket

        async def noop(*_args):
            pass

        async def on_frame(frame):
            frames.append(frame)
            received.set()

        async def on_failure(reason):
            failures.append(reason)

        app = web.Application()
        app.router.add_get('/api/v1/status', status)
        app.router.add_get('/ws/v1/echogram', stream)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, '127.0.0.1', 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        upstream = SonardUpstream(GatewayConfig(producer='m0',
            upstream_base_url=f'http://127.0.0.1:{port}'), noop, on_frame, noop, on_failure)
        task = asyncio.create_task(upstream.run())
        try:
            await asyncio.wait_for(received.wait(), 3)
            self.assertEqual(frames[0].ping_id, 7)
            self.assertEqual(len(frames[0].samples), 2048)
            self.assertEqual(failures, [])
        finally:
            await upstream.stop()
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await runner.cleanup()

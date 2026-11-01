"""Synthetic 0.4.13 publication joins; no physical TX or acquisition tests."""
import asyncio
import contextlib
from copy import deepcopy
from dataclasses import replace
import json
import time
import unittest

from aiohttp import web
from aiohttp.test_utils import TestServer

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError, UPSTREAM_HEADER
from no5_pi_gateway.service import GatewayService
from no5_pi_gateway.sonar_contract import amplitude, display_selection, publication_amplitude
from no5_pi_gateway.sonar_diagnostics import projection
from no5_pi_gateway.sonar_presentation import geometry
from no5_pi_gateway.sonar_published import (PublishedPingCache, parse_page, host_context, CAPACITY)
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import SonardUpstream, PUBLISHED_WAIT_S
from test_sonar_contract import status, latest
from test_sonar_presentation import frame, UTC_NS

SESSION = '01234567-89ab-4cde-8f01-23456789abcd'
SEGMENT = '11234567-89ab-4cde-8f01-23456789abcd'


def host_status(*, display=1, selection=1, source='own', ping=7, segment=SEGMENT, noise=True):
    result = status('0.4.13', source, display, ping)
    result['session']['segment_id'] = segment
    result['display_selection']['selection_generation'] = selection
    result['acquisition']['amplitude']['display_transform'] = (
        'background_reduction_then_max_pool_or_interpolate' if noise else
        'max_pool_or_interpolate_without_amplitude_scaling')
    result['noise_reduction'] = {'schema': 'no5.sonard-noise-reduction-status/1',
        'policy': {'mode': 'auto', 'revision': 1, 'algorithm': 'robust_adc_background_v1',
                   'raw_samples_modified': False, 'detection_input': 'original_adc'},
        'display_generation': display, 'latest': {'active': noise, 'output': 'background_reduced_adc_codes',
        'calibrated_acoustic_power': False, 'baseline_adc': 123.5, 'noise_sigma_adc': 4.2}}
    return result


def row(ping=7, *, stamp=UTC_NS, display=1, selection=1, noise=True, revision=1,
        source='own', segment=SEGMENT):
    return dict(ping_id=ping, time_utc_ns=stamp, session_id=SESSION, segment_id=segment,
        actual_source=source, source_evidence={'own': 'physical_own_marker_raw_match',
            'native': 'qualified_native_marker_raw_match', None: 'unconfirmed'}[source],
        valid_tx_reference=True, selection_generation=selection, display_generation=display,
        sample_count=8192, sample_period_ns=4000, noise_revision=revision, noise_active=noise)


def page(rows, *, cursor=0, oldest=None, latest_id=None, more=False, truncated=False, segment=SEGMENT):
    return {'schema': 'no5.sonard-published-pings/1', 'capacity': 512,
        'session_id': SESSION, 'segment_id': segment,
        'oldest_ping_id': oldest if oldest is not None else rows[0]['ping_id'] if rows else None,
        'latest_ping_id': latest_id if latest_id is not None else rows[-1]['ping_id'] if rows else None,
        'next_after_ping_id': rows[-1]['ping_id'] if rows else cursor,
        'has_more': more, 'truncated': truncated, 'pings': rows}


def adapter_for(value):
    async def unused(*_): pass
    adapter = SonardUpstream(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'),
                            unused, unused, unused, unused)
    adapter._host_package_version = '0.4.13'
    adapter._amplitude = amplitude(value, scale_only=True)
    adapter._selection = display_selection(value['display_selection'])
    adapter._selection_generation = adapter._selection['generation']
    adapter._host_context = host_context(value)
    adapter._published.set_context(adapter._host_context)
    return adapter


def new_frame(value=None, **kwargs):
    value = value or host_status()
    return replace(frame(flags=1), host_package_version='0.4.13',
        host_context_hint=host_context(value), amplitude=amplitude(value, scale_only=True),
        selection_generation_hint=value['display_selection']['generation'], **kwargs)


class PublishedContractTest(unittest.TestCase):
    def test_exact_identity_and_evidence_survive_absent_analysis(self):
        value = host_status()
        adapter = adapter_for(value)
        adapter._published.accept(parse_page(page([row()]), after_ping_id=0))
        joined = adapter._with_publication(new_frame(value))
        self.assertEqual(joined.ping_source, 'own')
        self.assertIsNone(joined.presentation)
        self.assertEqual(joined.display_selection['selectionGeneration'], 1)
        self.assertEqual(joined.amplitude['fullScaleCode'], 4095)
        self.assertTrue(joined.amplitude['noiseActive'])
        self.assertEqual(joined.amplitude['displayTransform'], 'background_reduction_then_max_pool_or_interpolate')
        self.assertEqual(geometry(joined)['adcSamplePeriodUs'], 4.)

    def test_header_time_session_segment_tx_and_selection_are_not_inferred(self):
        value = host_status()
        adapter = adapter_for(value)
        adapter._published.accept(parse_page(page([row()]), after_ping_id=0))
        original = new_frame(value)
        for changed in (replace(original, time_utc_ns=UTC_NS+1), replace(original, ping_id=8),
                        replace(original, host_context_hint=(SESSION, 'different')),
                        replace(original, flags=0)):
            with self.subTest(changed=changed.ping_id):
                joined = adapter._with_publication(changed)
                self.assertIsNone(joined.ping_source)
                self.assertIsNone(joined.amplitude['fullScaleCode'])
        adapter._selection['selectionGeneration'] = 2
        joined = adapter._with_publication(original)
        self.assertIsNone(joined.ping_source)
        self.assertIsNone(joined.amplitude['fullScaleCode'])

    def test_unconfirmed_published_source_remains_unknown_with_usable_display_scale(self):
        adapter = adapter_for(host_status())
        adapter._published.accept(parse_page(page([row(source=None)]), after_ping_id=0))
        joined = adapter._with_publication(new_frame())
        self.assertIsNone(joined.ping_source)
        self.assertEqual(joined.amplitude['fullScaleCode'], 4095)
        self.assertIsNotNone(joined.display_selection)

    def test_invalid_evidence_never_grants_an_origin(self):
        for fields in ({'actual_source': 'native'}, {'source_evidence': 'selected_own'},
                       {'actual_source': 'own', 'source_evidence': 'unconfirmed'},
                       {'valid_tx_reference': False, 'actual_source': 'native',
                        'source_evidence': 'qualified_native_marker_raw_match'}, {'sample_count': True},
                       {'time_utc_ns': 0}, {'noise_active': 1}, {'session_id': 'different'},
                       {'segment_id': 'different'}, {'display_generation': -1}):
            with self.subTest(fields=fields), self.assertRaises(ProtocolError):
                parse_page(page([{**row(), **fields}]), after_ping_id=0)

    def test_native_requires_explicit_qualified_marker_evidence(self):
        value = host_status(source='native')
        adapter = adapter_for(value)
        adapter._published.accept(parse_page(page([row(source='native')]), after_ping_id=0))
        self.assertEqual(adapter._with_publication(new_frame(value)).ping_source, 'native')

    def test_own_origin_can_be_known_without_a_valid_metric_tx_reference(self):
        adapter = adapter_for(host_status())
        body = page([{**row(), 'valid_tx_reference': False}])
        adapter._published.accept(parse_page(body, after_ping_id=0))
        joined = adapter._with_publication(replace(new_frame(), flags=32))
        self.assertEqual(joined.ping_source, 'own')
        self.assertEqual(geometry(joined)['txReference'], 'UNVERIFIED')
        self.assertIsNone(geometry(joined)['displayedMaxDepthM'])

    def test_page_and_cache_bounds_cursor_truncation_and_reset(self):
        cache = PublishedPingCache()
        cache.set_context((SESSION, SEGMENT))
        for start in range(1, 578, 64):
            rows = [row(n) for n in range(start, min(start+64, 578))]
            cache.accept(parse_page(page(rows, oldest=1, latest_id=577, more=start < 513),
                                    after_ping_id=cache.cursor))
        self.assertEqual(len(cache), CAPACITY)
        self.assertEqual(cache.cursor, 577)
        cache.accept(parse_page(page([row(600)], oldest=599, latest_id=600, truncated=True),
                                after_ping_id=577))
        self.assertEqual(cache.truncated_pages, 1)
        cache.set_context((SESSION, 'new-segment'))
        self.assertEqual(cache.cursor, 0)
        self.assertEqual(len(cache), 0)

    def test_page_rejects_oversize_disorder_and_lying_cursor(self):
        for body in (page([row(n) for n in range(1, 66)]), page([row(8), row(7)]),
                     {**page([row()]), 'next_after_ping_id': 8},
                     {**page([]), 'has_more': True}, {**page([row()]), 'capacity': 513},
                     {**page([row()]), 'truncated': 1}):
            with self.subTest(size=len(body['pings'])), self.assertRaises(ProtocolError):
                parse_page(body, after_ping_id=0)

    def test_segment_mixed_page_is_rejected_atomically(self):
        cache = PublishedPingCache(); cache.set_context((SESSION, SEGMENT))
        with self.assertRaises(ProtocolError):
            cache.accept(parse_page(page([row(7), row(8, segment='old-segment')]), after_ping_id=0))
        self.assertEqual(len(cache), 0)
        self.assertEqual(cache.cursor, 0)

    def test_missing_metadata_does_not_use_exact_analysis_as_source_fallback(self):
        joined = adapter_for(host_status())._with_publication(new_frame())
        self.assertIsNone(joined.ping_source)
        self.assertIsNone(joined.display_selection)
        self.assertEqual(joined.amplitude['normalizationSource'], 'UNCONFIRMED')
        self.assertNotIn('noiseActive', joined.amplitude)

    def test_missing_publication_does_not_invent_a_physical_epoch(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(value)
        adapter._published.accept(parse_page(page([row(7)]), after_ping_id=0))
        state.accept_ping(adapter._with_publication(new_frame(value)))
        confirmed_epoch = state.generation
        # A missing optional evidence read remains truthfully unconfirmed. It
        # must not masquerade as a source/policy change and erase all history.
        unknown = adapter._with_publication(replace(new_frame(value), ping_id=8))
        binary = state.accept_ping(unknown)
        self.assertIsNotNone(binary)
        self.assertEqual(state.generation, confirmed_epoch)
        self.assertIsNone(unknown.ping_source)
        self.assertIsNone(unknown.amplitude['fullScaleCode'])
        adapter._published.accept(parse_page(page([row(9)]), after_ping_id=7))
        state.accept_ping(adapter._with_publication(replace(new_frame(value), ping_id=9)))
        self.assertEqual(state.generation, confirmed_epoch)

    def test_effective_noise_active_changes_do_not_invent_a_policy_epoch(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(value)
        for number, active in ((7, True), (8, False), (9, True)):
            adapter._published.accept(parse_page(page([row(number, noise=active)]),
                                                after_ping_id=adapter._published.cursor))
            before = state.generation
            joined = adapter._with_publication(replace(new_frame(value), ping_id=number))
            binary = state.accept_ping(joined)
            self.assertIsNotNone(binary)
            self.assertEqual(joined.amplitude['noiseActive'], active)
            self.assertEqual(state.generation, before)

    def test_status_without_latest_noise_is_unconfirmed_without_partial_context(self):
        value = host_status()
        value['acquisition']['amplitude']['noise_reduction'] = {
            'schema': 'no5.sonard-noise-reduction-status/1', 'policy': {'revision': 1}, 'latest': None}
        result = amplitude(value)
        self.assertEqual(result['normalizationSource'], 'UNCONFIRMED')
        self.assertNotIn('displayTransform', result)
        # Exact publication can still use the independently checked 12-bit scale.
        base = amplitude(value, scale_only=True)
        published = parse_page(page([row()]), after_ping_id=0).rows[0]
        self.assertEqual(publication_amplitude(base, published)['fullScaleCode'], 4095)

    def test_only_confirmed_noise_policy_changes_advance_epoch(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config, now=lambda: UTC_NS//1_000_000)
        state.begin_connection(); state.observe_status(value)
        for number, active, revision in ((7, True, 1), (8, False, 1), (9, False, 2)):
            adapter._published.accept(parse_page(page([row(number, noise=active, revision=revision)]),
                                                after_ping_id=adapter._published.cursor))
            before = state.generation
            joined = adapter._with_publication(replace(new_frame(value), ping_id=number))
            raw = state.accept_ping(joined)
            self.assertIsNotNone(raw)
            self.assertEqual(state.generation, before + (1 if number == 9 else 0))
            size = int.from_bytes(raw[:4], 'little')
            self.assertLessEqual(size, 4096)
            metadata = json.loads(raw[4:4+size])
            self.assertEqual(metadata['amplitude']['noiseActive'], active)
            self.assertEqual(metadata['amplitude']['noiseRevision'], revision)
            self.assertEqual(raw[-2048:], joined.samples)
        old = state.generation
        newer = host_status(display=2)
        state.observe_status(newer)
        self.assertEqual(state.generation, old+1)
        self.assertIsNone(state.accept_ping(joined))

    def test_missing_publication_cannot_replace_newer_confirmed_noise_policy(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(value)
        adapter._published.accept(parse_page(page([row(7, revision=2)]), after_ping_id=0))
        self.assertIsNotNone(state.accept_ping(adapter._with_publication(new_frame(value))))
        epoch = state.generation
        unknown = adapter._with_publication(replace(new_frame(value), ping_id=8))
        self.assertIsNotNone(state.accept_ping(unknown))
        adapter._published.accept(parse_page(page([row(9, revision=1)]), after_ping_id=7))
        stale = adapter._with_publication(replace(new_frame(value), ping_id=9))
        self.assertIsNone(state.accept_ping(stale))
        self.assertEqual(state.generation, epoch)
        self.assertEqual(state._last_ping.ping_id, 8)
        self.assertEqual(state.old_selection_frames_dropped, 1)

    def test_host_segment_change_clears_same_numbered_context(self):
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection(); state.observe_status(host_status())
        before = state.generation
        state.observe_status(host_status(segment='new-segment'))
        self.assertEqual(state.generation, before+1)
        self.assertIsNone(state.accept_ping(new_frame()))

    def test_old_pending_high_id_cannot_enter_new_segment_before_id_one(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(value)
        old = replace(new_frame(value), ping_id=(1 << 63)-1)
        new_status = host_status(segment='new-segment', ping=1)
        state.observe_status(new_status)
        before = state.generation
        self.assertIsNone(state.accept_ping(old))
        adapter = adapter_for(new_status)
        adapter._published.accept(parse_page(page([row(1, segment='new-segment')], segment='new-segment'),
                                            after_ping_id=0))
        newest = adapter._with_publication(replace(new_frame(new_status), ping_id=1))
        self.assertIsNotNone(state.accept_ping(newest))
        self.assertEqual(state._last_ping.ping_id, 1)
        self.assertEqual(state.generation, before)

    def test_noise_diagnostics_are_bounded_observed_facts_not_radiometric_power(self):
        value = host_status()
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection(); state.observe_status(value)
        result = projection(state, value, latest())
        fields = {(item['group'], item['name']): item['value'] for item in result['fields']}
        self.assertEqual(fields['noise_reduction', 'latest.calibrated_acoustic_power'], 'false')
        self.assertEqual(fields['noise_reduction', 'latest.output'], 'background_reduced_adc_codes')
        self.assertLessEqual(len(json.dumps(result).encode()), 32768)


class PublishedNetworkTest(unittest.IsolatedAsyncioTestCase):
    PROOFS = {}
    async def exercise(self, *, metadata='valid', analysis='unavailable', delay=.0,
                       analysis_delay=0., switch=False, segment_switch=False):
        count = 0; rows = []; received = []; accepted = []; failures = []; queries = []
        finished = asyncio.Event(); base = time.time_ns(); sent_at = {}; arrived_at = {}; observed_analyses = set()
        state = GatewayState(GatewayConfig(producer='m1', upstream_base_url='http://127.0.0.1:8787'))
        def current_status():
            return host_status(display=2 if switch and count >= 11 else 1, ping=count,
                               noise=not(switch and count >= 11),
                               segment='new-segment' if segment_switch and count >= 11 else SEGMENT)
        async def get_status(_request): return web.json_response(current_status())
        async def get_latest(_request):
            if analysis == 'unavailable': return web.Response(status=503)
            # This newer optional analysis must not release an older frame
            # before its independent published evidence arrives.
            number = count+100 if analysis == 'newer' else count
            body = latest(ping=number)
            body['detection']['time_utc_ns'] = base+(number-1)*50_000_000
            body['presentation']['display_selection']['selection_generation'] = 1
            if analysis_delay: await asyncio.sleep(analysis_delay)
            if analysis == 'exact' and number > 0: observed_analyses.add(number)
            return web.json_response(body)
        async def get_published(request):
            cursor = int(request.query['after_ping_id']); queries.append(cursor)
            self.assertEqual(request.query['limit'], '64')
            if delay: await asyncio.sleep(delay)
            if metadata == 'missing': return web.Response(status=404)
            candidates = [item for item in rows if item['ping_id'] > cursor][:64]
            body = page(deepcopy(candidates), cursor=cursor,
                        oldest=rows[0]['ping_id'] if rows else None,
                        latest_id=rows[-1]['ping_id'] if rows else None,
                        segment='new-segment' if segment_switch and count >= 11 else SEGMENT)
            if metadata == 'wrong_time':
                for item in body['pings']: item['time_utc_ns'] += 1
            if metadata == 'oversize': body['padding'] = 'x'*65536
            return web.json_response(body)
        async def socket(request):
            nonlocal count
            ws = web.WebSocketResponse(); await ws.prepare(request)
            for number in range(1, 21):
                count = number
                if segment_switch and number == 11: rows.clear()
                identifier = number-10 if segment_switch and number >= 11 else number
                stamp = base+(number-1)*50_000_000
                rows.append(row(identifier, stamp=stamp, display=2 if switch and number >= 11 else 1,
                                noise=not(switch and number >= 11),
                                segment='new-segment' if segment_switch and number >= 11 else SEGMENT))
                raw = UPSTREAM_HEADER.pack(1,1,40,identifier,stamp,27.,float('nan'),0.,1,1024,0)+bytes([number,0])*1024
                sent_at[stamp] = time.monotonic()
                await ws.send_bytes(raw)
                await asyncio.sleep(.05)
            async for _ in ws: pass
            return ws
        async def on_status(value): state.observe_status(value)
        async def on_connected(): state.begin_connection()
        async def on_failure(value): failures.append(value)
        async def on_frame(value):
            received.append(value); arrived_at[value.time_utc_ns] = time.monotonic()
            binary = state.accept_ping(value)
            if binary is not None:
                size = int.from_bytes(binary[:4], 'little')
                accepted.append(json.loads(binary[4:4+size]))
                self.assertEqual(binary[-2048:], value.samples)
                self.assertLessEqual(size, 4096)
            if len(received) == 20: finished.set()
        app = web.Application()
        app.router.add_get('/api/v1/status', get_status)
        app.router.add_get('/api/v1/detection/latest', get_latest)
        app.router.add_get('/api/v1/sonar/published-pings', get_published)
        app.router.add_get('/ws/v1/echogram', socket)
        server = TestServer(app); await server.start_server()
        config = GatewayConfig(producer='m1', upstream_base_url=str(server.make_url('/')).rstrip('/'), status_poll_s=1.)
        upstream = SonardUpstream(config, on_status, on_frame, on_connected, on_failure)
        task = asyncio.create_task(upstream.run())
        try:
            await asyncio.wait_for(finished.wait(), 3)
            self.assertEqual(failures, [])
            self.assertEqual(len(received), 20)
            self.assertLess(len(queries), 20)  # Worker poll, never one fetch per frame.
            delays = [arrived_at[n]-sent_at[n] for n in sorted(arrived_at)]
            anchors = {item['pingId'] for item in accepted if item.get('presentation') is not None}
            if analysis == 'exact':
                self.assertEqual(anchors, observed_analyses)
                self.assertTrue(all(item['presentation']['pingId'] == item['pingId']
                                    for item in accepted if item['presentation'] is not None))
            self.PROOFS[f'{metadata}/{analysis}/{analysis_delay}/{delay}/{switch}/{segment_switch}'] = {
                'hostFrames': 20, 'gatewayFrames': len(accepted), 'unconfirmedSource': state.unconfirmed_source_frames,
                'observedExactAnalyses': len(observed_analyses), 'exactAnchorsPublished': len(anchors),
                'publishedRequests': len(queries), 'maxDispatchMs': round(max(delays)*1000, 3)}
            return accepted, received, state, delays
        finally:
            await upstream.stop(); task.cancel()
            with contextlib.suppress(asyncio.CancelledError): await task
            await server.close()

    async def test_absent_analysis_preserves_confirmed_origin_and_all_samples(self):
        accepted, received, state, delays = await self.exercise()
        self.assertEqual(len(accepted), 20)
        self.assertEqual(state.unconfirmed_source_frames, 0)
        self.assertTrue(all(item['pingSource'] == 'own' and item['presentation'] is None for item in accepted))
        self.assertTrue(all(item.publication is not None for item in received))
        self.assertLess(max(delays), PUBLISHED_WAIT_S+.03)

    async def test_exact_analysis_cache_preserves_observed_anchors_at_reviewed_delays(self):
        for latency in (0., .025, .075, .125):
            with self.subTest(analysis_delay=latency):
                accepted, _, state, delays = await self.exercise(analysis='exact', analysis_delay=latency, delay=.075)
                self.assertEqual(len(accepted), 20)
                self.assertEqual(state.unconfirmed_source_frames, 0)
                self.assertTrue(any(item['presentation'] is not None for item in accepted))
                self.assertLess(max(delays), PUBLISHED_WAIT_S+.03)

    async def test_newer_analysis_does_not_preempt_delayed_published_source(self):
        accepted, _, state, _ = await self.exercise(analysis='newer', delay=.075)
        self.assertEqual(len(accepted), 20)
        self.assertEqual(state.unconfirmed_source_frames, 0)
        self.assertTrue(all(item['pingSource'] == 'own' for item in accepted))

    async def test_missing_endpoint_keeps_raw_but_never_analysis_source_or_scale_fallback(self):
        accepted, _, state, _ = await self.exercise(metadata='missing', analysis='newer')
        self.assertEqual(len(accepted), 20)
        self.assertEqual(state.unconfirmed_source_frames, 20)
        self.assertTrue(all(item['pingSource'] is None and item['amplitude']['fullScaleCode'] is None for item in accepted))

    async def test_mismatched_utc_keeps_raw_without_claims(self):
        accepted, _, state, _ = await self.exercise(metadata='wrong_time')
        self.assertEqual(len(accepted), 20)
        self.assertEqual(state.unconfirmed_source_frames, 20)

    async def test_oversized_metadata_does_not_stop_binary_stream(self):
        accepted, _, state, _ = await self.exercise(metadata='oversize')
        self.assertEqual(len(accepted), 20)
        self.assertEqual(state.unconfirmed_source_frames, 20)

    async def test_display_change_is_confirmed_before_any_new_epoch_frame(self):
        accepted, _, state, _ = await self.exercise(switch=True)
        self.assertGreater(len(accepted), 0)
        old = [item for item in accepted if item['pingId'] < 11]
        new = [item for item in accepted if item['pingId'] >= 11]
        self.assertTrue(old and new)
        self.assertTrue(all(item['amplitude']['noiseActive'] for item in old))
        self.assertTrue(all(not item['amplitude']['noiseActive'] for item in new))
        self.assertLess(old[-1]['generation'], new[0]['generation'])
        self.assertTrue(all(item['displaySelection']['generation'] == 2 for item in new))

    async def test_actual_segment_restart_resets_cursor_and_accepts_smaller_ping_ids(self):
        accepted, received, state, _ = await self.exercise(segment_switch=True)
        self.assertEqual(len(received), 20)
        self.assertGreater(len(accepted), 10)
        # Gateway generation changes even though source/display generation stay1.
        generations = sorted({item['generation'] for item in accepted})
        self.assertEqual(len(generations), 2)
        after = [item for item in accepted if item['generation'] == generations[-1]]
        self.assertLess(after[0]['pingId'], 10)
        self.assertTrue(all(item['pingSource'] == 'own' for item in after))

    async def test_service_publishes_state_before_frame_when_confirmed_policy_changes(self):
        value = host_status(); adapter = adapter_for(value)
        state = GatewayState(adapter._config); state.begin_connection(); state.observe_status(value)
        service = object.__new__(GatewayService); service.state = state
        events = []
        async def state_event(): events.append(('state', state.generation))
        service.publish_state = state_event
        service.publish_binary = lambda _data: events.append(('frame', state.generation))
        for number, revision in ((7, 1), (8, 2)):
            adapter._published.accept(parse_page(page([row(number, noise=True, revision=revision)]),
                                                after_ping_id=adapter._published.cursor))
            await service._on_frame(adapter._with_publication(replace(new_frame(value), ping_id=number)))
        self.assertEqual([item[0] for item in events], ['frame', 'state', 'frame'])
        self.assertEqual(events[-2][1], events[-1][1])

    async def test_idle_analysis_worker_does_not_poll_without_a_pending_frame(self):
        adapter = adapter_for(host_status()); calls = []
        async def fetch(_session): calls.append(1)
        adapter._fetch_analysis = fetch
        wake = asyncio.Event(); wake.set()
        task = asyncio.create_task(adapter._optional_analysis_loop(None, [], wake))
        try:
            await asyncio.sleep(.07)
            self.assertEqual(calls, [])
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError): await task

"""Source provenance, explicit ADC scale, bounded diagnostics and shared jobs."""
import asyncio
from copy import deepcopy
from dataclasses import replace
import json
import unittest
from unittest.mock import patch

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError
from no5_pi_gateway.sonar_contract import amplitude, display_selection, frame_context, package_version
from no5_pi_gateway.sonar_commands import SonarCommands
from no5_pi_gateway.sonar_diagnostics import (SonarDiagnostics, projection, STATUS_TIME_BASIS,
    STATUS_PATHS, DEPTH_RATE_PATHS, REFERENCE_PATHS, TRACK_FIELDS, BLOCK_FIELDS)
from no5_pi_gateway.state import GatewayState
from test_sonar_presentation import frame, analysis, UTC_NS


def selection(source='own', generation=1, ping=7):
    return {'schema':'no5.sonard-display-selection/1','source':source,'generation':generation,
            'published_hz':18.12,'last_published_ping_id':ping,'raw_archive_source':'all',
            'raw_samples_modified':False,'automatic_native_fallback':False}


def status(version='0.4.10', source='own', generation=1, ping=7):
    return {'source':'pico','state':'RUNNING','host_package':{'name':'no5-sonard-openecho','version':version},
            'session':{'id':'01234567-89ab-4cde-8f01-23456789abcd'},
            'rates':{'ping_hz':20.7},'display_selection':selection(source,generation,ping),
            'acquisition':{'amplitude':{'schema':'no5.display-amplitude/1','encoding':'uint16_le',
                'unit':'adc_code','significant_bits':12,'fullscale_code':4095,
                'normalization_source':'HELLO_12bit_checked_host_contract',
                'display_transform':'max_pool_or_interpolate_without_amplitude_scaling','raw_samples_modified':False}}}


def latest(source='own', generation=1, ping=7):
    value=analysis(ping_id=ping)
    value['detection'].update(acquisition_source=source, blocks=[], tracks=[])
    value['presentation']['display_selection']=selection(source,generation,ping)
    return value


def reference_observation(detail=4, utc=None):
    return {'firmware_event':8,'firmware_detail':detail,
            'observation_reason':{4:'native_reference_deadline_expired',11:'qualified_native_marker'}[detail],
            'observed_device_us':1700000,'observed_utc_ns':utc,
            'utc_basis':None if utc is None else 'accepted_usb_time_sync',
            'observation_kind':'timeout_check' if detail==4 else 'marker_edge',
            'native_last_device_us':1259200,'native_period_us':386400,'native_stable':3,'slot':0,
            'meaning':'fault' if detail==4 else 'qualified','received_host_utc_ns':UTC_NS+500_000_000}


def depth_status():
    value=status('0.4.11')
    value['transmit']={'schema':'no5.depth-rate-status/1','reason':'native_reference_lost',
        'original_reference':{'schema':'no5.original-reference-observation/1',
            'latest':reference_observation(11,UTC_NS+1_000_000_000),
            'latched_fault':reference_observation(), 'arrival_age_s':.01,'observation_fresh':True},
        'rate_control':{'schema':'no5.depth-rate-control/1','rate_policy':'depth_auto',
            'planned_hz':25.0,'limiting_reason':'future_additive_reason','sample_count':8192,
            'applied_window':{'revision':12,'sample_count':8192,'next_min_spacing_us':27000,'device_us':1234567}},
        'ping_rates':{'window_s':5,'observed_s':5.,'complete':True,
            'time_basis':'device_marker_us_host_extrapolated_now','start_device_us':1000000,'end_device_us':6000000,
            'rates':{kind:{'count':count,'hz':count/5,'rolling_1s_min':None,'max_gap_s':.1}
                     for kind,count in [('own',90),('physical',89),('raw',88),('usable',85)]}}}
    return value


def state_live(version='0.4.10'):
    state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'),
                       session_id='fixture',now=lambda:UTC_NS//1_000_000)
    state.begin_connection();state.observe_status(status(version))
    context,source=frame_context(frame(),latest())
    state.accept_ping(replace(frame(),host_package_version=version,display_selection=context,
                             amplitude=amplitude(status(version)),ping_source=source,selection_generation_hint=1))
    return state


class SonarContractTest(unittest.TestCase):
    def test_only_reviewed_versions_enable_capabilities(self):
        for version in ('0.3.5','0.3.6','0.4.9','0.4.10','0.4.11','0.4.12','0.4.13','0.4.14','0.4.15','0.4.16','0.4.17','0.4.18'):
            state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
            state.observe_status(status(version));self.assertTrue(state.filter_supported)
            self.assertEqual(state.source_selection_supported,version in {'0.4.10','0.4.11','0.4.12','0.4.13','0.4.14','0.4.15','0.4.16','0.4.17','0.4.18'})
        for version in ('0.4.32','1.0.0','0.3.4',[],None):
            state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
            state.observe_status(status(version));self.assertFalse(state.filter_supported)
            self.assertFalse(state.source_selection_supported);self.assertIsNone(package_version(status(version)))
    def test_scale_is_explicit_or_unconfirmed_never_guessed_from_bins(self):
        self.assertEqual(amplitude(status())['fullScaleCode'],4095)
        for key,value in [('fullscale_code',65535),('fullscale_code',True),('significant_bits',16),('normalization_source','guess'),('display_transform','multiply64')]:
            broken=status();broken['acquisition']['amplitude'][key]=value
            self.assertIsNone(amplitude(broken)['fullScaleCode'])
            self.assertEqual(amplitude(broken)['normalizationSource'],'UNCONFIRMED')
        missing=status();missing['acquisition']={};self.assertIsNone(amplitude(missing)['fullScaleCode'])
        self.assertIsNone(amplitude(status('0.3.5')))
    def test_source_context_needs_exact_ping_nanosecond_and_selected_origin(self):
        observed=frame();matched,origin=frame_context(observed,latest())
        self.assertEqual(origin,'own');self.assertEqual(matched['observedAtEpochMs'],UTC_NS//1_000_000)
        for body in (latest(ping=8),latest(source='native')):
            body['presentation']['display_selection']=selection()
            self.assertIsNone(frame_context(observed,body)[0])
        body=latest();body['detection']['time_utc_ns']+=1
        self.assertEqual(frame_context(observed,body),(None,None))
    def test_missing_analysis_keeps_raw_bins_and_does_not_invent_ping_source(self):
        state=state_live();ping=replace(frame(ping_id=8),host_package_version='0.4.10',selection_generation_hint=1)
        wire=state.accept_ping(ping);self.assertIsNotNone(wire)
        metadata=json.loads(wire[4:4+int.from_bytes(wire[:4],'little')])
        self.assertIsNone(metadata['pingSource']);self.assertIsNone(metadata['displaySelection'])
        self.assertIsNone(metadata['bottom']['bin']);self.assertEqual(wire[-2048:],ping.samples)
        self.assertEqual(state.unconfirmed_source_frames,1)
    def test_source_switch_retires_old_context_even_if_optional_analysis_is_missing(self):
        state=state_live();before=state.generation
        state.observe_display_selection(display_selection(selection('native',2)))
        self.assertGreater(state.generation,before)
        old=replace(frame(ping_id=8),host_package_version='0.4.10',selection_generation_hint=1)
        self.assertIsNone(state.accept_ping(old));self.assertEqual(state.old_selection_frames_dropped,1)
        self.assertIsNotNone(state.accept_ping(replace(old,selection_generation_hint=2)))
        self.assertEqual(state.snapshot()['displaySelection']['source'],'native')
    def test_heartbeat_never_refreshes_old_measurement_and_state_stays_under16k(self):
        state=state_live();original=state.snapshot()['displaySelection']['observedAtEpochMs']
        state._now=lambda:UTC_NS//1_000_000+6000
        state.observe_status(status())
        self.assertEqual(state.snapshot()['displaySelection']['observedAtEpochMs'],original)
        self.assertEqual(state.status_name(),'STALE')
        self.assertLessEqual(len(json.dumps(state.snapshot()).encode()),16384)
    def test_maximum_metadata_keeps_scale_and_source_but_bounds_track_subset(self):
        state=state_live();payload=analysis(ping_id=8,source='m1_raw',confidence=.8)
        payload['presentation']['reason']='测'*200
        payload['presentation']['tracks']=[{'track_id':(1<<63)-1-index,'state':'confirmed','depth_m':index+1.,
            'confidence':.4,'classification':'multipath_like','classification_source':'rules_v1','detector':'direct_envelope'} for index in range(8)]
        from no5_pi_gateway.sonar_presentation import project
        observed=replace(frame(ping_id=8),host_package_version='0.4.10',amplitude=amplitude(status()),
            ping_source='own',display_selection=display_selection(selection(ping=8),observed_at=UTC_NS//1_000_000),
            presentation=project(frame(ping_id=8),payload))
        wire=state.accept_ping(observed);size=int.from_bytes(wire[:4],'little');metadata=json.loads(wire[4:4+size])
        self.assertLessEqual(size,4096);self.assertEqual(metadata['amplitude']['fullScaleCode'],4095)
        self.assertEqual(metadata['pingSource'],'own');self.assertEqual(wire[-2048:],observed.samples)
        shown=metadata['presentation'];self.assertEqual(shown['trackOmitted'],shown['trackTotal']-len(shown['tracks']))

    def test_reference_loss_is_status_evidence_not_connection_error_or_event_time(self):
        state=state_live();value=status();value['transmit']={'reason':'native_reference_lost','physical_markers':0}
        state.observe_status(value);result=state.snapshot()
        reason=result['observationReason'];self.assertEqual(reason['code'],'ORIGINAL_REFERENCE_LOST')
        self.assertIsNone(reason['observedAtEpochMs']);self.assertTrue(reason['latched']);self.assertTrue(reason['statusFresh'])
        self.assertEqual(reason['statusObservedAtEpochMs'],state._now());self.assertEqual(result['status'],'LIVE')
        self.assertIsNone(state._last_error)
        original=reason['statusObservedAtEpochMs'];state._now=lambda:original+6000
        self.assertFalse(state.snapshot()['observationReason']['statusFresh'])
        self.assertEqual(state.snapshot()['observationReason']['statusObservedAtEpochMs'],original)
        state.observe_status(value);self.assertTrue(state.snapshot()['observationReason']['statusFresh'])
        self.assertIsNone(state.snapshot()['observationReason']['observedAtEpochMs'])
        state.disconnect('transport test');self.assertFalse(state.snapshot()['observationReason']['statusFresh'])
        state.begin_connection();self.assertIsNone(state.snapshot()['observationReason'])

    def test_no_reference_fault_is_inferred_from_zero_counts_or_missing_packets(self):
        state=state_live()
        for value in [status(),{**status(),'transmit':{'reason':'missing_marker','physical_markers':0}},
                      {**status(),'transmit':{'reason':None,'physical_markers':0}},
                      {**status('0.4.32'),'transmit':{'reason':'native_reference_lost'}}]:
            state.observe_status(value);self.assertIsNone(state.snapshot()['observationReason'])

    def test_true_fault_time_cannot_be_replaced_by_newer_recovery_or_status_receipt(self):
        state=state_live();value=depth_status();state.observe_status(value)
        reason=state.snapshot()['observationReason']
        self.assertEqual(reason['detail'],'native_reference_deadline_expired')
        self.assertEqual(reason['observationKind'],'timeout_check');self.assertEqual(reason['firmwareDetail'],4)
        self.assertEqual(reason['observedDeviceUs'],1700000);self.assertIsNone(reason['observedAtEpochMs'])
        value['transmit']['original_reference']['latched_fault']=reference_observation(4,UTC_NS-10_000_000_000)
        state.observe_status(value);reason=state.snapshot()['observationReason']
        self.assertEqual(reason['observedAtEpochMs'],UTC_NS//1_000_000-10000)
        self.assertEqual(reason['utcBasis'],'accepted_usb_time_sync');self.assertTrue(reason['statusFresh'])
        value['transmit']['original_reference']['latched_fault']['utc_basis']='host_receipt_guess'
        state.observe_status(value);self.assertIsNone(state.snapshot()['observationReason']['observedAtEpochMs'])
        value['transmit']['reason']='none'
        state.observe_status(value);self.assertIsNone(state.snapshot()['observationReason'])
        self.assertLessEqual(len(json.dumps(state.snapshot()).encode()),16384)

    def test_invalid_reference_detail_falls_back_without_inventing_specific_cause(self):
        state=state_live()
        for key,bad in [('firmware_detail',True),('firmware_detail',5),('firmware_event',9),
                        ('meaning','qualified'),('observation_kind','marker_edge'),
                        ('observation_reason','missing_original_marker'),('observed_device_us',-1)]:
            value=depth_status();value['transmit']['original_reference']['latched_fault'][key]=bad
            state.observe_status(value);reason=state.snapshot()['observationReason']
            self.assertEqual(reason['detail'],'native_reference_lost');self.assertIsNone(reason['observedAtEpochMs'])
            self.assertNotIn('firmwareDetail',reason)
        state.select_source(state.config);self.assertIsNone(state.snapshot()['observationReason'])

    def test_reviewed_0411_preserves_raw_frame_and_source_contract_when_rates_are_missing(self):
        state=state_live();state.observe_status(status('0.4.11'))
        ping=replace(frame(ping_id=8),host_package_version='0.4.11',selection_generation_hint=1,
                     amplitude=amplitude(status('0.4.11')))
        wire=state.accept_ping(ping);self.assertEqual(wire[-2048:],ping.samples)
        metadata=json.loads(wire[4:4+int.from_bytes(wire[:4],'little')])
        self.assertEqual(metadata['amplitude']['fullScaleCode'],4095);self.assertIsNone(metadata['pingSource'])
        self.assertTrue(state.filter_supported);self.assertTrue(state.source_selection_supported)


class SonarDiagnosticsTest(unittest.IsolatedAsyncioTestCase):
    async def test_fresh_status_window_survives_lagging_analysis_without_inventing_ping_time(self):
        state=state_live('0.4.11');value=depth_status();value['display_selection']=selection(ping=8)
        async def exchange(base):return value,latest(ping=7)
        result=await SonarDiagnostics(state,exchange).snapshot()
        self.assertFalse(result['statusStale']);self.assertEqual(result['statusObservedAtEpochMs'],state._now())
        self.assertEqual(result['statusTimeBasis'],STATUS_TIME_BASIS)
        self.assertTrue(result['stale']);self.assertIsNone(result['observedAtEpochMs']);self.assertIsNone(result['pingId'])
        self.assertIn('17.0',[row['value'] for row in result['fields'] if row['name']=='ping_rates.rates.usable.hz'])

    async def test_live_http_status_can_confirm_zero_window_when_raw_ping_is_stale(self):
        state=state_live('0.4.11');state._now=lambda:UTC_NS//1_000_000+6000
        value=depth_status()
        for rate in value['transmit']['ping_rates']['rates'].values():rate.update(count=0,hz=0.)
        async def exchange(base):return value,{}
        self.assertEqual(state.status_name(),'STALE')
        result=await SonarDiagnostics(state,exchange).snapshot()
        self.assertFalse(result['statusStale']);self.assertTrue(result['stale'])
        self.assertEqual(next(row['value'] for row in result['fields'] if row['name']=='ping_rates.rates.usable.hz'),'0.0')

    async def test_cache_preserves_request_time_and_ages_status_independently(self):
        state=state_live('0.4.11');clock=[state._now()];state._now=lambda:clock[0];calls=[]
        async def exchange(base):calls.append(base);return depth_status(),latest(ping=6)
        reader=SonarDiagnostics(state,exchange);first=await reader.snapshot()
        clock[0]+=6000
        cached=await reader.snapshot()
        self.assertEqual(len(calls),1);self.assertEqual(cached['statusObservedAtEpochMs'],first['statusObservedAtEpochMs'])
        self.assertTrue(cached['statusStale']);self.assertTrue(cached['stale'])

    async def test_slow_status_response_and_foreign_or_missing_context_never_become_fresh(self):
        state=state_live('0.4.11');clock=[state._now()];state._now=lambda:clock[0]
        async def slow(base):clock[0]+=6000;return depth_status(),latest()
        late=await SonarDiagnostics(state,slow).snapshot();self.assertTrue(late['statusStale'])
        self.assertEqual(late['statusObservedAtEpochMs'],clock[0]-6000)
        for mutate in [lambda v:v['session'].update(id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'),
                       lambda v:v.pop('session'),lambda v:v.update(source='synthetic'),
                       lambda v:v.update(display_selection=selection('native',2))]:
            value=depth_status();mutate(value)
            async def foreign(base):return value,latest()
            result=await SonarDiagnostics(state,foreign).snapshot()
            self.assertTrue(result['statusStale']);self.assertIsNone(result['statusObservedAtEpochMs'])
            self.assertIsNone(result['statusTimeBasis']);self.assertTrue(result['stale'])

    async def test_monotonic_read_duration_rejects_slow_read_even_with_static_wall_clock(self):
        state=state_live('0.4.11')
        async def exchange(base):return depth_status(),latest()
        with patch('no5_pi_gateway.sonar_diagnostics.time.monotonic',side_effect=[10.,16.]):
            result=await SonarDiagnostics(state,exchange).snapshot()
        self.assertTrue(result['statusStale'])

    async def test_inflight_host_session_epoch_and_origin_changes_discard_response(self):
        for change in ('session','epoch','origin','selection','disconnect'):
            state=state_live()
            async def exchange(base):
                if change=='session':
                    value=status();value['session']['id']='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';state.observe_status(value)
                elif change=='epoch':state.generation+=1
                elif change=='selection':state.observe_display_selection(display_selection(selection('native',2)))
                elif change=='disconnect':state.disconnect('synthetic test')
                else:state.select_source(replace(state.config,upstream_base_url='http://127.0.0.1:18787'))
                return depth_status(),latest()
            with self.assertRaisesRegex(ProtocolError,'source changed'):await SonarDiagnostics(state,exchange).snapshot()

    async def test_http_status_survives_unavailable_optional_analysis_without_writes(self):
        from aiohttp import web
        from aiohttp.test_utils import TestServer
        paths=[]
        async def get_status(request):paths.append(request.path);return web.json_response(depth_status())
        async def get_analysis(request):paths.append(request.path);return web.Response(status=503)
        app=web.Application();app.router.add_get('/api/v1/status',get_status)
        app.router.add_get('/api/v1/detection/latest',get_analysis)
        server=TestServer(app);await server.start_server()
        try:
            state=state_live('0.4.11');state.config=replace(state.config,upstream_base_url=str(server.make_url('/')).rstrip('/'))
            result=await SonarDiagnostics(state).snapshot()
            self.assertFalse(result['statusStale']);self.assertTrue(result['stale']);self.assertIsNone(result['pingId'])
            self.assertEqual(paths,['/api/v1/status','/api/v1/detection/latest'])
        finally:await server.close()

    def test_rich_0411_status_cannot_starve_complete_track_and_template_block(self):
        state=state_live('0.4.11');value=depth_status()
        # Explicitly synthetic all-fields pressure, never measured device data.
        for path in STATUS_PATHS+DEPTH_RATE_PATHS+REFERENCE_PATHS:
            target=value;keys=path.split('.')
            for key in keys[:-1]:
                if not isinstance(target.get(key),dict):target[key]={}
                target=target[key]
            target.setdefault(keys[-1],'synthetic')
        track={key:('rules_v1' if key=='classification_source' else 'synthetic') for key in TRACK_FIELDS}
        track['track_id']=7
        block={key:'synthetic' for key in BLOCK_FIELDS}
        block.update(detector='direct_envelope',qualification_method='track_local_measured_template_v1',
                     template_ping_id=(1<<63)-1,template_correlation=.96,template_score=.8,
                     template_fit_preprocessing='median_gaussian_v1')
        body=latest();body['detection'].update(tracks=[track,{**track,'track_id':8}],blocks=[block])
        result=projection(state,value,body,status_request_started_at=state._now(),status_request_elapsed_s=.01)
        fields=result['fields'];keys=[(row['group'],row['name']) for row in fields]
        self.assertEqual(len(keys),len(set(keys)));self.assertLessEqual(len(fields),128)
        self.assertLessEqual(len(json.dumps(result,ensure_ascii=False).encode()),32768)
        self.assertEqual({name for group,name in keys if group=='track:7'},set(TRACK_FIELDS))
        self.assertEqual({name for group,name in keys if group=='block:0'},set(BLOCK_FIELDS))
        self.assertIn(('transmit','ping_rates.rates.usable.hz'),keys)
        self.assertIn(('transmit','ping_rates.complete'),keys);self.assertIn(('session','id'),keys)
        self.assertEqual(result['trackTotal'],2);self.assertEqual(result['trackOmitted'],1)
        self.assertEqual(result['blockTotal'],1);self.assertEqual(result['blockOmitted'],0)

    def test_0411_rates_are_read_only_and_keep_window_planning_and_reference_provenance(self):
        state=state_live();value=depth_status();state.observe_status(value)
        result=projection(state,value,latest());rows={(row['group'],row['name']):row for row in result['fields']}
        self.assertEqual(rows['transmit','rate_control.planned_hz']['value'],'25.0')
        self.assertEqual(rows['transmit','rate_control.planned_hz']['unit'],'Hz')
        self.assertEqual(rows['transmit','rate_control.limiting_reason']['value'],'future_additive_reason')
        self.assertEqual(rows['transmit','ping_rates.complete']['value'],'true')
        self.assertEqual(rows['transmit','ping_rates.time_basis']['value'],'device_marker_us_host_extrapolated_now')
        self.assertEqual(rows['transmit','ping_rates.rates.usable.hz']['value'],'17.0')
        self.assertNotIn(('transmit','ping_rates.rates.usable.rolling_1s_min'),rows)
        self.assertEqual(rows['transmit','original_reference.latest.observation_kind']['value'],'marker_edge')
        self.assertEqual(rows['transmit','original_reference.latched_fault.observation_kind']['value'],'timeout_check')
        self.assertNotIn(('transmit','original_reference.latched_fault.observed_utc_ns'),rows)
        self.assertLessEqual(len(json.dumps(result,ensure_ascii=False).encode()),32768)
        self.assertLessEqual(len(result['fields']),128)

    def test_new_rate_fields_require_exact_reviewed_host_and_schema(self):
        for version,schema in [('0.4.10','no5.depth-rate-control/1'),('0.4.32','no5.depth-rate-control/1'),
                               ('0.4.11','no5.depth-rate-control/2')]:
            value=depth_status();value['host_package']['version']=version
            value['transmit']['rate_control']['schema']=schema
            result=projection(state_live(),value,latest())
            self.assertFalse(any(row['name'].startswith(('rate_control.','ping_rates.')) for row in result['fields']))

    def test_0412_preserves_checked_scale_selection_analysis_and_usable_window_without_tx_commands(self):
        state=state_live('0.4.12');value=depth_status();value['host_package']['version']='0.4.12'
        state.observe_status(value)
        result=projection(state,value,latest(),status_request_started_at=UTC_NS//1_000_000,status_request_elapsed_s=.01)
        rows={(row['group'],row['name']):row['value'] for row in result['fields']}
        self.assertFalse(result['statusStale']);self.assertFalse(result['stale'])
        self.assertEqual(result['statusTimeBasis'],STATUS_TIME_BASIS)
        self.assertEqual(rows['host_package','version'],'0.4.12')
        self.assertEqual(rows['transmit','ping_rates.rates.usable.hz'],'17.0')
        self.assertEqual(rows['transmit','ping_rates.complete'],'true')
        self.assertEqual(amplitude(value)['fullScaleCode'],4095)
        self.assertTrue(state.filter_supported);self.assertTrue(state.source_selection_supported)
        self.assertEqual(state.snapshot()['observationReason']['observedDeviceUs'],1700000)
        self.assertNotIn(('transmit','continuous'),rows)

    def test_bounded_projection_keeps_exact_ids_and_explicit_omissions(self):
        state=state_live();body=latest();track={'track_id':(1<<63)-1,'depth_m':1.1,'classification':'bottom_like',
            'qualification_method':'rules_v1','evidence':['测'*2048]*16,'confidence':.8}
        body['detection']['tracks']=[dict(track,track_id=track['track_id']-n) for n in range(100)]
        body['detection']['blocks']=[{'start_sample':n,'end_sample':n+10,'peak_sample':n+5,
            'qualification_method':'track_local_template','template_ping_id':(1<<63)-1,
            'template_correlation':.96,'template_fit_offset_samples':2} for n in range(100)]
        result=projection(state,status(),body);encoded=json.dumps(result,separators=(',',':'),ensure_ascii=False).encode()
        self.assertLessEqual(len(encoded),32768);self.assertLessEqual(len(result['fields']),128)
        self.assertLessEqual(sum(sum(len(x) for x in row.values()) for row in result['fields']),24000)
        self.assertEqual(result['trackTotal'],100);self.assertGreater(result['trackOmitted'],0)
        self.assertEqual(result['blockTotal'],100);self.assertGreater(result['blockOmitted'],0)
        self.assertIn(str((1<<63)-1),json.dumps(result))
        self.assertFalse(result['stale'])
    def test_mismatched_source_or_time_does_not_receive_current_analysis_label(self):
        state=state_live();body=latest(source='native')
        result=projection(state,status(),body)
        self.assertIsNone(result['pingId']);self.assertTrue(result['stale'])
        self.assertFalse(any(field['group'].startswith('track:') for field in result['fields']))
    async def test_on_demand_fetch_is_shared_at_most1hz_and_context_guarded(self):
        state=state_live();calls=[]
        async def exchange(base):calls.append(base);return status(),latest()
        reader=SonarDiagnostics(state,exchange)
        self.assertEqual(calls,[])
        values=await asyncio.gather(*(reader.snapshot() for _ in range(5)))
        self.assertEqual(len(calls),1);self.assertEqual(values[0],values[-1])
        state.generation+=1
        await reader.snapshot();self.assertEqual(len(calls),2)
    async def test_failed_fetch_is_not_repeated_within_poll_window(self):
        state=state_live();calls=[]
        async def exchange(base):calls.append(base);raise TimeoutError()
        reader=SonarDiagnostics(state,exchange)
        for _ in range(2):
            with self.assertRaises((TimeoutError,ProtocolError)):await reader.snapshot()
        self.assertEqual(len(calls),1)


class SourceCommandsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.state=state_live();self.observed=selection();self.writes=[];self.unknown=False
        self.block=asyncio.Event();self.block.set()
        async def exchange(base,source):
            if source is None:return deepcopy(self.observed)
            self.writes.append(source);await self.block.wait()
            self.observed=selection(source,self.observed['generation']+1)
            if self.unknown:raise TimeoutError()
            return deepcopy(self.observed)
        self.actor=SonarCommands(self.state,source_exchange=exchange)
    async def asyncTearDown(self):self.block.set();await self.actor.stop()
    def request(self,number,source='native'):
        return {'version':1,'clientId':'helm','requestId':number,'serverSessionId':self.state.server_session_id,
                'generation':self.state.generation,'source':source}
    async def done(self,identifier):
        for _ in range(100):
            result=self.actor.read('owner',identifier)
            if result['status'] not in {'ACCEPTED','RUNNING'}:return result
            await asyncio.sleep(.001)
        self.fail('source result missing')
    async def test_source_goal_is_202_style_once_only_and_clears_epoch(self):
        before=self.state.generation;receipt=self.actor.submit('owner',self.request(1),lambda:True)
        self.assertEqual(receipt['status'],'ACCEPTED');self.assertIs(type(receipt['commandId']),int)
        self.assertEqual(self.writes,[]);self.actor.start()
        result=await self.done(receipt['commandId']);self.assertEqual(result['confirmedSource'],'native')
        self.assertEqual(result['status'],'APPLIED');self.assertGreater(self.state.generation,before)
        self.assertEqual(self.writes,['native']);self.assertIsNone(self.actor.read('foreign',receipt['commandId']))
    async def test_unknown_source_put_is_never_repeated(self):
        self.unknown=True;request=self.request(1);self.actor.start()
        receipt=self.actor.submit('owner',request,lambda:True)
        self.assertEqual((await self.done(receipt['commandId']))['status'],'UNKNOWN')
        self.assertEqual(self.actor.submit('owner',request,lambda:True)['commandId'],receipt['commandId'])
        self.assertEqual(self.writes,['native'])
    async def test_changed_host_selection_before_effect_is_preserved(self):
        receipt=self.actor.submit('owner',self.request(1),lambda:True)
        self.observed=selection('all',2);self.actor.start()
        self.assertEqual((await self.done(receipt['commandId']))['status'],'CANCELLED')
        self.assertEqual(self.writes,[])
    async def test_filter_source_share_limits_but_are_ordering_barriers(self):
        source=self.actor.submit('owner',self.request(1),lambda:True)
        request=self.request(2);request.pop('source');request['mode']='off'
        middle=self.actor.submit('owner',request,lambda:True)
        last=self.actor.submit('owner',self.request(3,'all'),lambda:True)
        self.assertEqual(self.actor.read('owner',source['commandId'])['status'],'ACCEPTED')
        self.assertEqual(self.actor._size,3)
        next_source=self.actor.submit('owner',self.request(4,'own'),lambda:True)
        self.assertEqual(self.actor.read('owner',last['commandId'])['status'],'SUPERSEDED')
        self.assertEqual(self.actor.DIAGNOSTICS,64)
        self.assertEqual(len(self.actor._diagnostics),64)


class SonarNewBoundaryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import test_audio_stream as fixture
        await fixture.AudioStreamTest.asyncSetUp(self)
        self.headers={'X-No5-Gateway-Key':fixture.KEY};self.service=self.client.server.app['gateway_service']
        self.service.state.config=replace(self.service.state.config,producer='m1',upstream_base_url='http://127.0.0.1:18787')
        self.service.state.begin_connection();self.service.state.observe_status(status())
        self.selected=selection();self.writes=[]
        async def exchange(base,source):
            if source is not None:self.writes.append(source);self.selected=selection(source,self.selected['generation']+1)
            return deepcopy(self.selected)
        self.service.sonar_commands.source_exchange=exchange
        async def diagnostics(base):return status(),latest()
        self.service.sonar_diagnostics.exchange=diagnostics
    async def asyncTearDown(self):
        import test_audio_stream as fixture
        await fixture.AudioStreamTest.asyncTearDown(self)
    async def test_source_endpoint_is_authenticated_strict_and_async(self):
        self.assertEqual((await self.client.get('/v1/sonar/source')).status,401)
        state=await (await self.client.get('/v1/sonar/source',headers=self.headers)).json()
        self.assertEqual(state['displaySelection']['source'],'own')
        request={'version':1,'clientId':'helm','requestId':1,'serverSessionId':self.service.state.server_session_id,
                 'generation':self.service.state.generation,'source':'native'}
        response=await self.client.put('/v1/sonar/source',headers=self.headers,json=request)
        self.assertEqual(response.status,202);receipt=await response.json()
        for _ in range(100):
            result=await (await self.client.get('/v1/sonar/commands/'+str(receipt['commandId']),headers=self.headers)).json()
            if result['status']=='APPLIED':break
            await asyncio.sleep(.001)
        self.assertEqual(result['confirmedSource'],'native');self.assertEqual(self.writes,['native'])
        self.assertEqual((await self.client.put('/v1/sonar/source',headers=self.headers,json={**request,'mode':'auto'})).status,400)
        self.assertEqual((await self.client.put('/v1/sonar/filter',headers=self.headers,json=request)).status,400)
        self.assertEqual(self.writes,['native'])
    async def test_diagnostics_are_readonly_authenticated_and_bounded(self):
        self.assertEqual((await self.client.get('/v1/sonar/diagnostics')).status,401)
        response=await self.client.get('/v1/sonar/diagnostics',headers=self.headers)
        self.assertEqual(response.status,200);raw=await response.read();self.assertLessEqual(len(raw),32768)
        result=json.loads(raw);self.assertEqual(result['kind'],'sonar-diagnostics')
        self.assertEqual(response.headers['Cache-Control'],'no-store');self.assertEqual(self.writes,[])
        self.assertEqual((await self.client.put('/v1/sonar/diagnostics',headers=self.headers,json={})).status,405)

"""Exact 0.4.15 read approval; no USB, acquisition, TX, transport or UI actions."""
import base64
from copy import deepcopy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import struct
import unittest

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.protocol import ProtocolError
from no5_pi_gateway.sonar_contract import package_version
from no5_pi_gateway.sonar_diagnostics import projection
from no5_pi_gateway.sonar_published import parse_page,PublishedPingCache
from no5_pi_gateway.state import GatewayState
from test_sonar_display_window import case_state,case_frame,publication_page,CASES as CASES_0414
from test_sonar_published import adapter_for

FIXTURE = Path(__file__).parent / 'fixtures/sonar-0415/stripe-interface.json'
FIXTURE_SHA = '5cf612538f8e760016586e14d3d371facb4e4c97dfc0e75bb870f255759fd4a1'
CASES = json.loads(FIXTURE.read_text())['cases']


def status_0415(case):
    value = case_state(case); value['host_package']['version'] = '0.4.15'
    return value


def adapter_0415(value):
    adapter = adapter_for(value); adapter._host_package_version = '0.4.15'
    return adapter


def frame_0415(case,value):
    return replace(case_frame(case,value),host_package_version='0.4.15')


def liveness():
    return {'state':'open','last_byte_age_s':.005,'last_packet_age_s':.02,
        'read_poll_interval_s':.01,'packet_silence_limit_s':3.,
        'poll_read_bytes':2**63-1,'raw_samples_modified':False}


class Sonar0415ReadContractTest(unittest.TestCase):
    def test_bound_fixture_has_three_identical_0414_binary_frames_and_windows(self):
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),FIXTURE_SHA)
        self.assertEqual(len(CASES),3)
        for previous,current in zip(CASES_0414,CASES):
            with self.subTest(metric=current['published_ping']['valid_tx_reference']):
                self.assertEqual(current['frame_base64'],previous['frame_base64'])
                self.assertEqual(current['published_ping']['display_window'],previous['published_ping']['display_window'])
                raw=base64.b64decode(current['frame_base64'],validate=True)
                self.assertEqual(hashlib.sha256(raw).hexdigest(),current['frame_sha256'])
                self.assertEqual(len(raw),2088)

    def test_all_three_frames_keep_sources_scale_geometry_and_4096_byte_metadata_bound(self):
        for case in CASES:
            with self.subTest(metric=case['published_ping']['valid_tx_reference']):
                value=status_0415(case); adapter=adapter_0415(value)
                adapter._published.accept(parse_page(publication_page(case['published_ping']),after_ping_id=0,host_version='0.4.15'))
                observed=frame_0415(case,value); joined=adapter._with_publication(observed)
                state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'),
                    now=lambda:observed.observed_at_epoch_ms)
                state.begin_connection();state.observe_status(value)
                wire=state.accept_ping(joined);count=int.from_bytes(wire[:4],'little');metadata=json.loads(wire[4:4+count])
                self.assertEqual(metadata['hostPackageVersion'],'0.4.15')
                self.assertEqual(metadata['pingSource'],'own');self.assertEqual(metadata['amplitude']['fullScaleCode'],4095)
                self.assertLessEqual(count,4096);self.assertEqual(wire[-2048:],observed.samples)
                if case['published_ping']['valid_tx_reference']:
                    self.assertEqual(metadata['geometry']['displayedMaxDepthM'],case['header']['displayed_max_depth_m'])
                else:
                    self.assertIsNone(metadata['geometry']['displayedMaxDepthM'])
                    self.assertEqual(metadata['geometry']['txReference'],'UNVERIFIED')

    def test_15_still_requires_window_and_exact_header_utc_host_and_source_epoch(self):
        case=CASES[1];value=status_0415(case);adapter=adapter_0415(value)
        missing=deepcopy(case['published_ping']);del missing['display_window']
        with self.assertRaises(ProtocolError):parse_page(publication_page(missing),after_ping_id=0,host_version='0.4.15')
        parsed=parse_page(publication_page(case['published_ping']),after_ping_id=0,host_version='0.4.15')
        adapter._published.accept(parsed);original=frame_0415(case,value)
        bits=struct.unpack('<I',struct.pack('<f',original.displayed_max_depth_m))[0]
        wrong=struct.unpack('<f',struct.pack('<I',bits+1))[0]
        for changed in (replace(original,displayed_max_depth_m=wrong),
                        replace(original,time_utc_ns=original.time_utc_ns+1),
                        replace(original,host_context_hint=(original.host_context_hint[0],'different-segment'))):
            joined=adapter._with_publication(changed)
            self.assertIsNone(joined.publication);self.assertIsNone(joined.ping_source)
            self.assertEqual(joined.amplitude['normalizationSource'],'UNCONFIRMED')
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection();state.observe_status(value)
        joined=adapter._with_publication(original)
        newer=deepcopy(value);newer['display_selection']['generation']+=1
        newer['display_selection']['selection_generation']+=1;state.observe_status(newer)
        self.assertIsNone(state.accept_ping(joined));self.assertEqual(state.old_selection_frames_dropped,1)

    def test_14_and_15_remain_reviewed_but_19_stays_unconfirmed(self):
        self.assertEqual(package_version(case_state(CASES_0414[0])),'0.4.14')
        self.assertEqual(package_version(status_0415(CASES[0])),'0.4.15')
        value=status_0415(CASES[0]);value['host_package']['version']='0.4.32'
        self.assertIsNone(package_version(value))
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'));state.observe_status(value)
        self.assertFalse(state.filter_supported);self.assertFalse(state.source_selection_supported)

    def test_optional_liveness_is_read_only_bounded_and_retains_integer_precision(self):
        value=status_0415(CASES[1]);value['pico']={'transport_liveness':liveness()}
        value['rates'].update(last_raw_arrival_age_s=4.,last_observed_ping_hz=17.7,ping_hz=0.)
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection();state.observe_status(value)
        result=projection(state,value,{})
        fields={(r['group'],r['name']):r for r in result['fields']}
        self.assertEqual(fields['pico','transport_liveness.poll_read_bytes']['value'],str(2**63-1))
        self.assertEqual(fields['pico','transport_liveness.last_packet_age_s']['unit'],'s')
        self.assertEqual(fields['rates','last_observed_ping_hz']['unit'],'Hz')
        self.assertEqual(fields['rates','last_raw_arrival_age_s']['value'],'4.0')
        self.assertEqual(fields['pico','transport_liveness.raw_samples_modified']['value'],'false')
        self.assertLessEqual(len(result['fields']),128);self.assertLessEqual(len(json.dumps(result).encode()),32768)
        self.assertIsNone(result['observedAtEpochMs']) # A transport/status age is no acoustic observation.
        self.assertFalse(state.snapshot()['capabilities']['canStart']);self.assertFalse(state.snapshot()['capabilities']['canStop'])

    def test_missing_new_fields_and_older_host_do_not_invent_liveness(self):
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
        state.begin_connection()
        for version in ('0.4.14','0.4.15'):
            value=case_state(CASES[0]);value['host_package']['version']=version;state.observe_status(value)
            fields=projection(state,value,{})['fields']
            self.assertFalse(any(r['name'].startswith('transport_liveness.')for r in fields))
        value['host_package']['version']='0.4.14';value['pico']={'transport_liveness':liveness()}
        state.observe_status(value)
        self.assertFalse(any(r['name'].startswith('transport_liveness.')for r in projection(state,value,{})['fields']))

    def test_status_read_after_silence_does_not_refresh_old_ping(self):
        case=CASES[1];value=status_0415(case);adapter=adapter_0415(value)
        adapter._published.accept(parse_page(publication_page(case['published_ping']),after_ping_id=0,host_version='0.4.15'))
        observed=frame_0415(case,value);now=[observed.observed_at_epoch_ms]
        state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'),now=lambda:now[0])
        state.begin_connection();state.observe_status(value);state.accept_ping(adapter._with_publication(observed))
        self.assertEqual(state.status_name(),'LIVE');generation=state.generation
        now[0]+=6000;value['rates'].update(ping_hz=0.,last_observed_ping_hz=17.7,last_raw_arrival_age_s=6.)
        value['pico']={'transport_liveness':{**liveness(),'state':'silent','last_packet_age_s':6.}}
        state.observe_status(value)
        self.assertEqual(state.status_name(),'STALE');self.assertEqual(state._last_ping.time_utc_ns,observed.time_utc_ns)
        self.assertEqual(state.generation,generation)
        resumed=replace(observed,ping_id=observed.ping_id+1,time_utc_ns=observed.time_utc_ns+6_000_000_000,
            observed_at_epoch_ms=now[0])
        publication=replace(adapter._published.match(observed),ping_id=resumed.ping_id,time_utc_ns=resumed.time_utc_ns)
        selection=deepcopy(adapter._selection);selection.update(lastPublishedPingId=resumed.ping_id,observedAtEpochMs=now[0])
        resumed=replace(resumed,publication=publication,display_selection=selection,ping_source='own')
        self.assertIsNotNone(state.accept_ping(resumed));self.assertEqual(state.status_name(),'LIVE')
        self.assertEqual(state.generation,generation)

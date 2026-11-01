"""Bounded exact .17 display read approval; no host, TX, runtime or UI mutation."""
import base64
from copy import deepcopy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import unittest

from no5_pi_gateway.configuration import GatewayConfig
from no5_pi_gateway.sonar_context import parse_atomic_echogram
from no5_pi_gateway.sonar_contract import package_version,amplitude
from no5_pi_gateway.sonar_diagnostics import projection
from no5_pi_gateway.state import GatewayState
from no5_pi_gateway.upstream import upstream_websocket_url
from test_sonar_context import atomic_status
from test_sonar_published import adapter_for

FIXTURE=Path(__file__).parent/'fixtures/sonar-0417/impulse-interface.json'
FIXTURE_SHA='4609690327ec7284fc3ac979a83bf53fe799bc9c67701e6ea600a6d820813250'
CASES=json.loads(FIXTURE.read_text())['cases']


def status_0417(case,version='0.4.17'):
    value=atomic_status(case['context_metadata']);value['host_package']['version']=version
    value['noise_reduction']=deepcopy(case['noise_reduction'])
    value['display_window']=deepcopy(case['published_ping']['display_window'])
    # The unchanged checked status scale accompanies the per-frame atomic
    # display context. It is never derived from impulse.active or samples.
    transform=('background_reduction_then_max_pool_or_interpolate'if case['published_ping']['noise_active']else
               'max_pool_or_interpolate_without_amplitude_scaling')
    value['acquisition']['amplitude']['display_transform']=transform
    value['acquisition']['amplitude']['noise_reduction']=deepcopy(case['noise_reduction'])
    return value


def project_case(case):
    status=status_0417(case);a=adapter_for(status);a._host_package_version='0.4.17'
    envelope=base64.b64decode(case['context_base64'],validate=True)
    parsed=parse_atomic_echogram(envelope);frame=a._atomic_frame(parsed)
    frame=replace(frame,host_package_version='0.4.17',analysis_supported=True,amplitude=a._amplitude,
                  selection_generation_hint=parsed.publication.display_generation)
    frame=a._with_publication(frame)
    state=GatewayState(a._config,now=lambda:frame.observed_at_epoch_ms)
    state.begin_connection();state.observe_status(status)
    wire=state.accept_ping(frame)
    if wire is None:raise AssertionError('exact .17 publication not accepted')
    size=int.from_bytes(wire[:4],'little')
    return state,frame,wire,json.loads(wire[4:4+size]),status


class Sonar0417ReadContractTest(unittest.TestCase):
    def test_exact_fixture_contains_nine_hash_bound_m1_and_atomic_pairs(self):
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),FIXTURE_SHA)
        self.assertEqual(len(CASES),9)
        for case in CASES:
            raw=base64.b64decode(case['frame_base64'],validate=True)
            atomic=base64.b64decode(case['context_base64'],validate=True)
            self.assertEqual(hashlib.sha256(raw).hexdigest(),case['frame_sha256'])
            self.assertEqual(hashlib.sha256(atomic).hexdigest(),case['context_sha256'])
            self.assertEqual(parse_atomic_echogram(atomic).original_m1,raw)
            self.assertEqual(len(raw),2088);self.assertLessEqual(len(atomic),6200)

    def test_same_raw_ab_samples_allow_intentionally_changed_display_amplitudes(self):
        by_name={c['name']:c for c in CASES}
        off,auto,on=(by_name[n]for n in ['impulse_off','impulse_auto','impulse_on'])
        self.assertEqual(off['raw_adc_base64'],auto['raw_adc_base64']);self.assertEqual(auto['raw_adc_base64'],on['raw_adc_base64'])
        a,b,c=(base64.b64decode(case['frame_base64'])for case in [off,auto,on])
        self.assertEqual(a[:40],b[:40]);self.assertEqual(b[:40],c[:40])
        self.assertNotEqual(a[40:],b[40:]);self.assertEqual(b[40:],c[40:])
        self.assertEqual(off['published_ping'],auto['published_ping'])
        self.assertEqual(auto['published_ping'],on['published_ping'])
        self.assertTrue(all(c['noise_reduction']['latest']['impulse_reduction'].get('temporal_replacement')is not True for c in CASES))

    def test_nine_actual_gateway_projections_preserve_m1_ids_samples_source_scale_and_crop(self):
        for case in CASES:
            with self.subTest(name=case['name']):
                state,frame,wire,metadata,_=project_case(case)
                m1=base64.b64decode(case['frame_base64']);size=int.from_bytes(wire[:4],'little')
                self.assertLessEqual(size,4096);self.assertEqual(wire[4+size:],m1[40:]);self.assertEqual(frame.samples,m1[40:])
                self.assertEqual(metadata['hostPackageVersion'],'0.4.17')
                self.assertEqual(metadata['pingId'],case['published_ping']['ping_id'])
                self.assertEqual(metadata['observedAtEpochMs'],case['published_ping']['time_utc_ns']//1_000_000)
                self.assertEqual(metadata['pingSource'],case['published_ping']['actual_source'])
                self.assertEqual(metadata['geometry']['displayedMaxDepthM'],case['published_ping']['display_window']['display_range_m'])
                self.assertEqual(metadata['amplitude']['fullScaleCode'],4095)
                self.assertEqual(metadata['amplitude']['noiseActive'],case['published_ping']['noise_active'])
                self.assertEqual(metadata['amplitude']['displayTransform'],'background_reduction_then_max_pool_or_interpolate')
                self.assertEqual(metadata['displaySelection']['generation'],case['published_ping']['display_generation'])
                self.assertFalse(state.snapshot()['capabilities']['canStart']);self.assertFalse(state.snapshot()['capabilities']['canStop'])

    def test_noise_active_is_total_processing_not_impulse_active(self):
        for case in CASES:
            _,_,_,metadata,_=project_case(case)
            self.assertTrue(metadata['amplitude']['noiseActive'])
        disabled=next(c for c in CASES if c['name']=='impulse_off')
        self.assertFalse(disabled['noise_reduction']['latest']['impulse_reduction']['active'])
        self.assertTrue(project_case(disabled)[3]['amplitude']['noiseActive'])

    def test_19_suffix_wrong_producer_and_bad_scale_never_gain_confirmation(self):
        for version in ['0.4.32','0.4.17-dev','0.4.17.1',' 0.4.17',17,None]:
            value=status_0417(CASES[0],version);self.assertIsNone(package_version(value))
            self.assertIsNone(amplitude(value));self.assertTrue(upstream_websocket_url('http://localhost',package_version(value)).endswith('/ws/v1/echogram'))
        value=status_0417(CASES[0]);value['host_package']['name']='another-host'
        self.assertIsNone(package_version(value));self.assertIsNone(amplitude(value))
        for key,bad in [('fullscale_code',65535),('significant_bits',16),('normalization_source','guessed'),
                        ('display_transform','impulse_corrected_scale')]:
            value=status_0417(CASES[0]);value['acquisition']['amplitude'][key]=bad
            self.assertEqual(amplitude(value)['normalizationSource'],'UNCONFIRMED')
        self.assertTrue(upstream_websocket_url('http://localhost','0.4.17').endswith('/ws/v1/echogram-context'))

    def test_impulse_diagnostics_are_exact_versioned_optional_and_bounded(self):
        state,_,_,_,value=project_case(next(c for c in CASES if c['name']=='impulse_auto'))
        result=projection(state,value,{})
        rows={(r['group'],r['name']):r for r in result['fields']}
        self.assertEqual(rows['noise_reduction','policy.impulse_mode']['value'],'auto')
        self.assertEqual(rows['noise_reduction','latest.impulse_reduction.active']['value'],'true')
        self.assertEqual(rows['noise_reduction','latest.impulse_reduction.algorithm']['value'],'repeated_rc_impulse_v1')
        self.assertEqual(rows['noise_reduction','latest.impulse_reduction.temporal_replacement']['value'],'false')
        self.assertEqual(rows['noise_reduction','latest.impulse_reduction.maximum_subtraction_adc']['unit'],'ADC_CODE')
        self.assertLessEqual(len(result['fields']),128);self.assertLessEqual(len(json.dumps(result,ensure_ascii=False).encode()),32768)
        for version in ['0.4.16','0.4.32','0.4.17-dev']:
            older=status_0417(CASES[1],version);state.observe_status(older)
            fields=projection(state,older,{})['fields']
            self.assertFalse(any('impulse_reduction.'in f['name']or f['name'].endswith('impulse_mode')for f in fields))
        absent=status_0417(CASES[0]);del absent['noise_reduction']['policy']['impulse_mode'];del absent['noise_reduction']['latest']['impulse_mode'];del absent['noise_reduction']['latest']['impulse_reduction']
        state.observe_status(absent);self.assertFalse(any('impulse'in f['name']for f in projection(state,absent,{})['fields']))
        malformed=status_0417(CASES[0]);malformed['noise_reduction']['schema']='future';state.observe_status(malformed)
        self.assertFalse(any('impulse'in f['name']for f in projection(state,malformed,{})['fields']))

    def test_large_optional_impulse_details_cannot_expand_diagnostics_budget(self):
        state,_,_,_,value=project_case(CASES[1]);noise=value['noise_reduction']['latest']['impulse_reduction']
        noise.update(reason='🟠'*100000,events=2**63-1,maximum_subtraction_adc=4095,
                     unexpected_history=['x'*100000]*100)
        result=projection(state,value,{})
        self.assertLessEqual(len(result['fields']),128);self.assertLessEqual(len(json.dumps(result,ensure_ascii=False).encode()),32768)
        rows={(r['group'],r['name']):r for r in result['fields']}
        self.assertEqual(rows['noise_reduction','latest.impulse_reduction.events']['value'],str(2**63-1))
        self.assertLessEqual(len(rows['noise_reduction','latest.impulse_reduction.reason']['value']),2048)
        self.assertFalse(any('unexpected_history'in r['name']for r in result['fields']))

    def test_16_reference_fixture_and_original_cropped_and_unverified_samples_unchanged(self):
        path=Path(__file__).parent/'fixtures/sonar-0416/echogram-context.json'
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),'b9ff41f7a72f09bfa7583f96e6bba99c3283119dd7bec197d67ba7f94d413769')
        cases=json.loads(path.read_text())['cases'][:3]
        self.assertLess(cases[1]['metadata']['published_ping']['display_window']['display_sample_count'],cases[1]['metadata']['published_ping']['sample_count'])
        for case in cases:
            m1=parse_atomic_echogram(base64.b64decode(case['envelope_base64']));self.assertEqual(hashlib.sha256(m1.original_m1).hexdigest(),case['m1_sha256'])

    def test_identical_liveness_snapshot_is_additively_readable_only_for_15_16_17_18(self):
        from test_sonar_0415_read_contract import liveness
        for version in ('0.4.15','0.4.16','0.4.17','0.4.18'):
            value=status_0417(CASES[0],version);value['pico']={'transport_liveness':liveness()}
            value['rates'].update(last_raw_arrival_age_s=.02,last_observed_ping_hz=17.)
            state=GatewayState(GatewayConfig(producer='m1',upstream_base_url='http://127.0.0.1:8787'))
            state.begin_connection();state.observe_status(value)
            fields={(r['group'],r['name']):r for r in projection(state,value,{})['fields']}
            self.assertEqual(fields['pico','transport_liveness.poll_read_bytes']['value'],str(2**63-1))
            self.assertEqual(fields['rates','last_raw_arrival_age_s']['unit'],'s')
        for version in ('0.4.14','0.4.32','0.4.17-dev'):
            value=status_0417(CASES[0],version);value['pico']={'transport_liveness':liveness()}
            state.observe_status(value)
            self.assertFalse(any(f['name'].startswith('transport_liveness.')for f in projection(state,value,{})['fields']))
        missing=status_0417(CASES[0]);state.observe_status(missing)
        self.assertFalse(any(f['name'].startswith('transport_liveness.')for f in projection(state,missing,{})['fields']))

    def test_extra_impulse_fields_cannot_displace_primary_source_ping_or_time_evidence(self):
        from test_sonar_contract import latest
        case=next(c for c in CASES if c['name']=='impulse_auto');state,frame,_,_,value=project_case(case)
        payload=latest(ping=frame.ping_id)
        payload['detection'].update(time_utc_ns=frame.time_utc_ns,acquisition_source='own',sample_period_ns=case['sample_period_ns'])
        payload['presentation']['display_selection']=deepcopy(value['display_selection'])
        value['noise_reduction']['latest']['impulse_reduction'].update(reason='🟠'*100000,algorithm='a'*100000)
        payload['detection']['tracks']=[{'track_id':n,'depth_m':1.,'state':'confirmed','evidence':['x'*10000]}for n in range(50)]
        payload['detection']['blocks']=[{'start_sample':n,'end_sample':n+1,'detector':'cfar','template_fit_preprocessing':'x'*10000}for n in range(50)]
        result=projection(state,value,payload,status_request_started_at=frame.observed_at_epoch_ms,status_request_elapsed_s=.01)
        fields={(r['group'],r['name']):r for r in result['fields']}
        self.assertEqual(result['pingId'],frame.ping_id);self.assertEqual(result['observedAtEpochMs'],frame.observed_at_epoch_ms)
        for key in [('status','state'),('host_package','version'),('display_selection','source'),('ping','time_utc_ns')]:self.assertIn(key,fields)
        self.assertEqual(fields['ping','time_utc_ns']['value'],str(frame.time_utc_ns))
        self.assertEqual(result['trackTotal'],50);self.assertGreater(result['trackOmitted'],0)
        self.assertEqual(result['blockTotal'],50);self.assertGreater(result['blockOmitted'],0)
        self.assertLessEqual(len(result['fields']),128);self.assertLessEqual(len(json.dumps(result,separators=(',',':'),ensure_ascii=False).encode()),32768)

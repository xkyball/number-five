#!/usr/bin/env python3
"""Public deterministic ADC, M1 and atomic-context A/B vectors; no device IO."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from no5_sonard.processing_m1 import max_pool_resample
from no5_sonard.protocol import pack_echogram_frame, unpack_echogram_frame
from no5_sonard_openecho import __version__
from no5_sonard_openecho.echogram_context import pack_context, unpack_context
from no5_sonard_openecho.noise_reduction import NoiseReduction


def make(path):
    root = Path(__file__).resolve().parents[1]
    retained = json.loads((root/'tests/fixtures/stripe-interface-0415.json').read_text())
    row = copy.deepcopy(retained['cases'][1]['published_ping'])
    row.update(sample_count=2048, noise_revision=2, display_generation=2)
    row['display_window'].update(capture_sample_count=2048, capture_range_m=3.072,
                                reason='capture_matches_depth_window')
    x = np.arange(2048)
    raw = 66+140*np.exp(-.5*((x-900)/22)**2)+12*np.exp(-.5*((x-580)/8)**2)
    for i in range(350,2018,22):raw[i:i+20] += 180*.78**np.arange(20)
    raw = np.rint(raw).astype(np.uint16)
    block = SimpleNamespace(start_sample=840, end_sample=960)
    train = raw.copy()
    clean_raw = np.rint(66+140*np.exp(-.5*((x-900)/22)**2)+12*np.exp(-.5*((x-580)/8)**2)).astype(np.uint16)
    cases = []
    scenarios = [('impulse_'+m,m,'own',2000,train,[block]) for m in ('off','auto','on')]
    clipped=train.copy();clipped[30]=4095
    isolated=clean_raw.copy();isolated[350:370]+=np.rint(180*.78**np.arange(20)).astype(np.uint16)
    scenarios += [('auto_no_signature','auto','own',2000,clean_raw,[block]),
        ('auto_native','auto','native',2000,train,[block]),
        ('auto_other_period','auto','own',4000,train,[block]),
        ('auto_clipped','auto','own',2000,clipped,[block]),
        ('auto_isolated','auto','own',2000,isolated,[block]),
        ('auto_protected_weak','auto','own',2000,train,[block,SimpleNamespace(start_sample=565,end_sample=595)])]
    for name,mode,source,period,raw,blocks in scenarios:
        row = copy.deepcopy(row)
        row.update(actual_source=source, source_evidence='physical_own_marker_raw_match' if source=='own' else 'qualified_native_marker_raw_match',sample_period_ns=period)
        row['display_window'].update(display_range_m=float(np.float32(2048*period*1e-9*750)),capture_range_m=2048*period*1e-9*750)
        reducer = NoiseReduction(impulse_mode=mode, revision=2)
        cleaned, decision = reducer.process(raw, ping_id=row['ping_id'], sample_period_ns=period,
            tx_zero_sample_index=0, acquisition_source=source, blocks=blocks)
        frame = pack_echogram_frame(ping_id=row['ping_id'], time_utc_ns=row['time_utc_ns'],
            max_depth_m=row['display_window']['display_range_m'], bottom_depth_m=1.35,
            bottom_confidence=1., flags=1, amplitudes=max_pool_resample(cleaned,start_index=0,bins=1024))
        header, _ = unpack_echogram_frame(frame)
        envelope = pack_context(frame,row,selected_source=source,publication_sequence=1)
        metadata, decoded = unpack_context(envelope); assert decoded == frame
        cases.append({'name':name,'raw_adc_base64':base64.b64encode(raw.tobytes()).decode(),
            'sample_period_ns':period,'blocks':[{'start_sample':v.start_sample,'end_sample':v.end_sample} for v in blocks],'published_ping':copy.deepcopy(row),
            'noise_reduction':{'schema':'no5.sonard-noise-reduction-status/1',
                'policy':reducer.policy(),'latest':decision,'display_generation':2},
            'header':{k:getattr(header,k) for k in header.__slots__},
            'frame_sha256':hashlib.sha256(frame).hexdigest(),
            'frame_base64':base64.b64encode(frame).decode(),
            'context_metadata':metadata,'context_base64':base64.b64encode(envelope).decode(),
            'context_sha256':hashlib.sha256(envelope).hexdigest(),
            'derived_adc_base64':base64.b64encode(cleaned.tobytes()).decode()})
    fixture = {'schema':'no5.impulse-interface-fixture/1','host_version':__version__,
        'synthetic_fixture':True,'raw_samples_modified':False,'physical_device_opened':False,
        'raw_adc_base64':base64.b64encode(train.tobytes()).decode(),
        'raw_sha256':hashlib.sha256(train.tobytes()).hexdigest(),
        'amplitude':{'encoding':'uint16_le','fullscale_code':4095,'significant_bits':12,'display_transform':'background_reduction_then_max_pool_or_interpolate','calibrated_acoustic_power':False},
        'protected_sample_range':[815,986], 'cases':cases,
        'wire_document_sha256':hashlib.sha256((root/'docs/INTERFACE_ECHOGRAM_CONTEXT_0416.md').read_bytes()).hexdigest()}
    with path.open('x') as f:json.dump(fixture,f,indent=2);f.write('\n')
    return {'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'cases':len(cases)}


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
    print(json.dumps(make(p.parse_args().output)))

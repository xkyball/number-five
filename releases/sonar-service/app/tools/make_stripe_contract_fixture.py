#!/usr/bin/env python3
"""Generate a synthetic same-PING contract fixture; never opens a serial port."""
import argparse
import asyncio
import base64
import hashlib
import json
import math
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace

import numpy as np
from no5_sonard.config import ServiceConfig
from no5_sonard.pico_protocol import MessageType, Packet, PingFrame
from no5_sonard.protocol import unpack_echogram_frame
from no5_sonard_openecho import __version__
from no5_sonard_openecho.depth_rate import DepthRateProfile
from no5_sonard_openecho.service import HostService
from no5_sonard_openecho.transmit import EVENT


async def make(output):
    root = Path(__file__).resolve().parents[1]
    profile = DepthRateProfile.load(root/'deployment/clip-depth-qualified-0712.json')
    with tempfile.TemporaryDirectory(prefix='sonar-fixture-') as temporary:
        service = HostService(ServiceConfig(device='/synthetic-no-device',storage_root=Path(temporary),
            sample_count=8192,sample_period_ns=2000,pretrigger_samples=0,
            trigger_threshold=150,trigger_hysteresis=8,tx_marker_requested=True,
            tx_reference_verified=True,tx_reference_basis='hardware_gp16_transducer'),
            transmit_profile=profile,display_ping_source='own')
        service._ensure_session();service._open_segment(profile.serial,8192,2000)
        tx=service.transmit
        client=SimpleNamespace(clock=SimpleNamespace(ready=True,pico_us_to_utc_ns=lambda us:1792170000000000000+us*1000),
            hello=SimpleNamespace(device_serial=profile.serial),packet_seq_gaps=0,ping_id_gaps=0,missed_ping_queue=0,
            transport=SimpleNamespace(crc_errors=0,frame_errors=0))
        # Explicit fixture-only depth window, never presented as hardware proof.
        tx.on_detection=lambda *args:None
        tx.depth_policy.snapshot=lambda:{'reason':'depth_locked','depth_age_s':.01,'sample_count':2048}
        frames=[]
        async def publish(frame):frames.append(frame)
        service.hub.publish=publish
        rows=[]
        for stripe,pid in [('off',i) for i in range(1,8)]+[('auto',8),('auto',9)]:
            service.set_noise_reduction_mode('auto',stripe_mode=stripe)
            n=16384;stamp=1000000+pid*400000
            x=np.arange(n);raw=np.full(n,65,dtype=np.uint16)
            raw=np.rint(raw+400*np.exp(-.5*((x-810)/20)**2)).astype(np.uint16)
            before=hashlib.sha256(raw.tobytes()).hexdigest()
            tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(9,pid,n,stamp,35768,pid)))
            for event in [1,2,3]:
                tx.on_packet(Packet(MessageType.TX_EVENT,1,0,EVENT.pack(event,0,pid,stamp,stamp+3,0)))
            await service._handle_ping(PingFrame(pid,stamp+3,2000,1000 if pid==9 else 0,65,465,0 if pid==9 else 1,raw),client)
            header,amplitudes=unpack_echogram_frame(frames[-1])
            assert hashlib.sha256(raw.tobytes()).hexdigest()==before
            published=service.published_pings()['pings'][-1]
            assert header.ping_id==published['ping_id']
            if pid==9:
                assert math.isnan(header.displayed_max_depth_m)
                assert published['display_window']['display_range_m'] is None
                assert published['display_window']['display_start_sample']==0
                from no5_sonard.processing_m1 import max_pool_resample
                np.testing.assert_array_equal(amplitudes,max_pool_resample(raw,start_index=0,bins=1024))
            else:
                assert header.displayed_max_depth_m==published['display_window']['display_range_m']
            if pid<7:
                continue
            rows.append({'stripe_mode':stripe,'published_ping':published,
                'presentation':service.presentation(),'noise_reduction':service.noise_reduction_status(),
                'header':{name:(None if isinstance(getattr(header,name),float) and not math.isfinite(getattr(header,name))
                                   else getattr(header,name)) for name in header.__slots__},
                'header_nonfinite_fields':[name for name in header.__slots__
                    if isinstance(getattr(header,name),float) and not math.isfinite(getattr(header,name))],
                'frame_sha256':hashlib.sha256(frames[-1]).hexdigest(),
                'frame_base64':base64.b64encode(frames[-1]).decode('ascii'),
                'samples_sha256':before})
        service._writer.close()
        fixture={'schema':'no5.stripe-interface-fixture/1','host_version':__version__,
            'synthetic_fixture':True,'physical_device_opened':False,'firmware_changed':False,
            'cases':rows,'raw_samples_modified':False,
            'manual_full_coverage':'PUT /api/v1/sonar/noise-reduction {"stripe_mode":"off"}',
            'zoom_out_cannot_recover_omitted_display_range':True}
        with output.open('x') as handle:json.dump(fixture,handle,indent=2,allow_nan=False);handle.write('\n')
        return {'fixture':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest()}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    print(json.dumps(asyncio.run(make(parser.parse_args().output)),indent=2))

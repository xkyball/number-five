import asyncio
import base64
from copy import deepcopy
import gzip
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from replay_comparison_view import ComparisonView
from no5_sonard.protocol import unpack_echogram_frame


def test_recorded_view_keeps_exact_payload_and_exposes_replay_without_opening_usb(tmp_path):
    fixture=json.loads((Path(__file__).parent/'fixtures/near-field-interface-0420.json').read_text())
    case=fixture['cases'][0]
    row={'stage':'A_reference','original_ping_id':case['published_ping']['ping_id'],
        'offset_s':0.,'frame_base64':case['frame_base64'],'published_ping':case['published_ping'],
        'noise_reduction':case['noise_reduction'],'presentation':{'ping_id':case['published_ping']['ping_id'],'bottom':None},
        'detection':{'ping_id':case['published_ping']['ping_id'],'time_utc_ns':case['published_ping']['time_utc_ns']}}
    source=tmp_path/'source';source.mkdir();out=tmp_path/'out';out.mkdir()
    with gzip.open(source/'impulse-60s-A_reference.jsonl.gz','wt') as f:f.write(json.dumps(row)+'\n')
    view=ComparisonView(source,out,'impulse-60s')
    asyncio.run(view.prepare('A_reference'))
    view.apply(row,0,1234567890000000000)
    expected_header,expected=unpack_echogram_frame(base64.b64decode(row['frame_base64']))
    header,got=unpack_echogram_frame(view.latest['frame'])
    np.testing.assert_array_equal(got,expected)
    assert header.ping_id==expected_header.ping_id+10_000_000
    assert header.time_utc_ns==1234567890000000000
    assert header.displayed_max_depth_m==expected_header.displayed_max_depth_m
    state=view.status()
    assert state['replay']['historical_not_live'] and not state['replay']['new_hardware_evidence']
    assert state['replay']['original_ping_id']==expected_header.ping_id
    assert state['replay']['original_time_utc_ns']==expected_header.time_utc_ns
    assert state['replay']['tx_commands_sent']==0 and not state['pico']['connected']
    assert view.host._client is None and view.host._writer is None
    assert not (out/'not-installed').exists()

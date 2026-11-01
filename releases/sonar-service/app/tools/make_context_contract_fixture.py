#!/usr/bin/env python3
"""Deterministic codec vectors derived from the retained .15 M1 fixture; no IO device."""
import argparse
import base64
import copy
import hashlib
import json
from pathlib import Path
import struct

from no5_sonard_openecho import __version__
from no5_sonard_openecho.echogram_context import (
    MAX_ENVELOPE_BYTES, MAX_METADATA_BYTES, MAX_M1_BYTES, PREFIX, UINT64_MAX,
    pack_context, unpack_context,
)


def make(output):
    root=Path(__file__).resolve().parents[1]
    old_path=root/'tests/fixtures/stripe-interface-0415.json'
    old=json.loads(old_path.read_text())
    cases=[]
    variants=[('full_legacy_window',0,'own'),('qualified_display_window',1,'own'),
              ('unverified_own',2,'own'),('confirmed_native',0,'native'),
              ('unconfirmed_source',2,'all'),('uint64_int64_max_utf8',1,'own'),
              ('utc_int64_min',1,'own')]
    for name,index,selected in variants:
        original=old['cases'][index]
        frame=base64.b64decode(original['frame_base64']);row=copy.deepcopy(original['published_ping'])
        sequence=1
        if name=='confirmed_native':
            row.update(actual_source='native',source_evidence='qualified_native_marker_raw_match')
        if name=='unconfirmed_source':row.update(actual_source=None,source_evidence='unconfirmed')
        if name in ('uint64_int64_max_utf8','utc_int64_min'):
            stamp=2**63-1 if name=='uint64_int64_max_utf8' else -2**63
            raw=bytearray(frame);struct.pack_into('<Qq',raw,4,UINT64_MAX,stamp);frame=bytes(raw)
            row['ping_id']=row['display_window']['ping_id']=UINT64_MAX;row['time_utc_ns']=stamp
            sequence=UINT64_MAX;row['display_window']['reason']='Messfenster · 🐟'
        before=copy.deepcopy(row)
        envelope=pack_context(frame,row,selected_source=selected,publication_sequence=sequence)
        meta,decoded=unpack_context(envelope);assert decoded==frame and row==before
        _,_,_,metadata_len,m1_len=PREFIX.unpack_from(envelope)
        cases.append({'name':name,'metadata':meta,'metadata_bytes':metadata_len,'m1_bytes':m1_len,
            'envelope_bytes':len(envelope),'m1_sha256':hashlib.sha256(frame).hexdigest(),
            'envelope_sha256':hashlib.sha256(envelope).hexdigest(),
            'envelope_base64':base64.b64encode(envelope).decode(),
            'm1_byte_identical_to_retained_0415':index if name in
                ('full_legacy_window','qualified_display_window','unverified_own') else None})
    fixture={'schema':'no5.sonard-echogram-context-fixture/1','host_version':__version__,
        'wire_schema':'no5.sonard-echogram-context/1','magic_hex':'4e354543','version':1,'flags':0,
        'prefix_struct':'<4sHHII','prefix_bytes':16,'max_metadata_bytes':MAX_METADATA_BYTES,
        'max_m1_bytes':MAX_M1_BYTES,'max_envelope_bytes':MAX_ENVELOPE_BYTES,
        'retained_0415_fixture_sha256':hashlib.sha256(old_path.read_bytes()).hexdigest(),
        'wire_document_sha256':hashlib.sha256((root/'docs/INTERFACE_ECHOGRAM_CONTEXT_0416.md').read_bytes()).hexdigest(),
        'synthetic_fixture':True,'physical_device_opened':False,'raw_samples_modified':False,
        'cases':cases,'negative_prefix_vectors':[
            {'base_case':'qualified_display_window','offset':offset,'replacement_hex':value,'must_reject':True}
            for offset,value in [(0,'00000000'),(4,'0200'),(6,'0100'),(8,'01100000'),(12,'29080000')]]}
    with output.open('x') as handle:json.dump(fixture,handle,ensure_ascii=False,indent=2);handle.write('\n')
    return {'fixture':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'cases':len(cases)}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    print(json.dumps(make(parser.parse_args().output),indent=2))

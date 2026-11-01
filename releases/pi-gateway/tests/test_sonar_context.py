"""Atomic context parser tests; original .15 M1 fixture bytes stay untouched."""
import base64
from copy import deepcopy
from dataclasses import replace
import json
import hashlib
from pathlib import Path
import struct
import unittest

from no5_pi_gateway.protocol import ProtocolError,UPSTREAM_HEADER,parse_echogram_frame
from no5_pi_gateway.sonar_context import parse_atomic_echogram,PREFIX,MAX_MESSAGE_BYTES
from no5_pi_gateway.sonar_contract import MAX_SIGNED
from no5_pi_gateway.sonar_published import parse_page
from test_sonar_published import host_status,adapter_for,page

FIXTURE=Path(__file__).parent/'fixtures/sonar-0415/stripe-interface.json'


def context_case(index=0):
    case=json.loads(FIXTURE.read_text())['cases'][index]
    row=deepcopy(case['published_ping']);row['ping_id']=str(row['ping_id']);row['time_utc_ns']=str(row['time_utc_ns'])
    row['display_window']['ping_id']=str(row['display_window']['ping_id'])
    return {'schema':'no5.sonard-echogram-context/1','selected_source':'own',
            'publication_sequence':'1','published_ping':row},base64.b64decode(case['frame_base64'])


def encode(root,m1,*,metadata=None,flags=0,version=1,magic=b'N5EC'):
    raw=json.dumps(root,separators=(',',':'),allow_nan=False).encode()if metadata is None else metadata
    return PREFIX.pack(magic,version,flags,len(raw),len(m1))+raw+m1


def modified_m1(raw,**changes):
    values=list(UPSTREAM_HEADER.unpack_from(raw))
    names=['version','kind','header','ping','utc','range','bottom','confidence','flags','bins','reserved']
    for key,value in changes.items():values[names.index(key)]=value
    return UPSTREAM_HEADER.pack(*values)+raw[40:]


def atomic_status(root=None):
    root=root or context_case()[0];row=root['published_ping']
    value=host_status(display=row['display_generation'],selection=row['selection_generation'],
                      source=root['selected_source'],ping=int(row['ping_id']),
                      segment=row['segment_id'],noise=row['noise_active'])
    value['host_package']['version']='0.4.16';value['session']['id']=row['session_id']
    value['noise_reduction']['policy']['revision']=row['noise_revision']
    return value


class AtomicContextCodecTest(unittest.TestCase):
    def test_three_original_m1_fixtures_survive_byte_exactly(self):
        for index in range(3):
            root,original=context_case(index);decoded=parse_atomic_echogram(encode(root,original))
            self.assertEqual(decoded.original_m1,original)
            self.assertEqual(decoded.frame.samples,original[40:])
            self.assertEqual(decoded.publication.ping_id,decoded.frame.ping_id)
            self.assertEqual(decoded.publication.time_utc_ns,decoded.frame.time_utc_ns)
            self.assertEqual(decoded.sequence,1)

    def test_exact_6200_byte_message_and_no_aiohttp_boundary_rejection(self):
        root,m1=context_case();metadata=json.dumps(root,separators=(',',':')).encode()
        metadata+=b' '*(4096-len(metadata));wire=encode(root,m1,metadata=metadata)
        self.assertEqual(len(wire),6200);self.assertEqual(MAX_MESSAGE_BYTES,6200)
        self.assertEqual(parse_atomic_echogram(wire).original_m1,m1)

    def test_bad_prefix_lengths_reserved_trailer_and_legacy_are_rejected(self):
        root,m1=context_case();wire=encode(root,m1)
        bad=[wire[:-1],wire+b'x',b'N5EC',m1,encode(root,m1,magic=b'BAD!'),
             encode(root,m1,version=2),encode(root,m1,flags=1),encode(root,modified_m1(m1,reserved=1)),
             encode(root,modified_m1(m1,bins=512)),encode(root,modified_m1(m1,header=41)),
             encode(root,modified_m1(m1,utc=0)),encode(root,modified_m1(m1,utc=-1)),
             PREFIX.pack(b'N5EC',1,0,0,len(m1))+m1,
             PREFIX.pack(b'N5EC',1,0,4097,len(m1))+b' '*4097+m1]
        for index,value in enumerate(bad):
            with self.subTest(index=index),self.assertRaises(ProtocolError):parse_atomic_echogram(value)

    def test_duplicate_utf8_nonfinite_overflow_float_and_wrong_root_fail(self):
        root,m1=context_case();raw=json.dumps(root,separators=(',',':')).encode()
        invalid=[b'[]',b'null',b'\xff',raw.replace(b'"schema":',b'"schema":"duplicate","schema":',1),
                 raw.replace(b'"noise_active":',b'"noise_active":true,"noise_active":',1),
                 raw.replace(b'"capture_range_m":24.576',b'"capture_range_m":NaN'),
                 raw.replace(b'"capture_range_m":24.576',b'"capture_range_m":Infinity'),
                 raw.replace(b'"capture_range_m":24.576',b'"capture_range_m":1e999')]
        for index,value in enumerate(invalid):
            with self.subTest(index=index),self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,m1,metadata=value))

    def test_exact_field_sets_and_canonical_uuid_required(self):
        root,m1=context_case();cases=[]
        r=deepcopy(root);r['extra']=False;cases.append(r)
        r=deepcopy(root);del r['selected_source'];cases.append(r)
        r=deepcopy(root);r['published_ping']['extra']=False;cases.append(r)
        r=deepcopy(root);r['published_ping']['display_window']['extra']=False;cases.append(r)
        for value in ['not-a-uuid',root['published_ping']['session_id'].upper(),None]:
            # Fixtures may contain numeric-only UUIDs; use an explicitly uppercase UUID.
            if value==root['published_ping']['session_id']:value='A1234567-89AB-4CDE-8F01-23456789ABCD'
            r=deepcopy(root);r['published_ping']['session_id']=value;cases.append(r)
        for index,r in enumerate(cases):
            with self.subTest(index=index),self.assertRaises(ProtocolError):parse_atomic_echogram(encode(r,m1))

    def test_canonical_long_strings_and_android_subset_are_explicit(self):
        root,m1=context_case()
        for field,values in [('publication_sequence',['0','01','+1',' 1','1.0',1,True,'-0',str(1<<64)]),
                             ('ping_id',['01','+7','7e0',7,True,str(1<<63)]),
                             ('time_utc_ns',['0','-0','-1','+1',1,True,str(1<<63)])]:
            for value in values:
                r=deepcopy(root)
                if field=='publication_sequence':r[field]=value
                else:r['published_ping'][field]=value
                with self.subTest(field=field,value=value),self.assertRaises(ProtocolError):parse_atomic_echogram(encode(r,m1))
        for value in [str(MAX_SIGNED+1),str((1<<64)-1)]:
            r=deepcopy(root);r['publication_sequence']=value
            with self.assertRaisesRegex(ProtocolError,'unsupported by Android V1'):parse_atomic_echogram(encode(r,m1))
        r=deepcopy(root);r['publication_sequence']=str(MAX_SIGNED)
        r['published_ping']['ping_id']=str(MAX_SIGNED);r['published_ping']['display_window']['ping_id']=str(MAX_SIGNED)
        r['published_ping']['time_utc_ns']=str(MAX_SIGNED)
        good=parse_atomic_echogram(encode(r,modified_m1(m1,ping=MAX_SIGNED,utc=MAX_SIGNED)))
        self.assertEqual(good.frame.ping_id,MAX_SIGNED);self.assertEqual(good.sequence,MAX_SIGNED)

    def test_metadata_identity_tx_f32_window_and_bound_violations_fail(self):
        root,m1=context_case();changes=[('ping_id','8'),('time_utc_ns',str(int(root['published_ping']['time_utc_ns'])+1)),
            ('display_generation',0),('selection_generation',True),('noise_revision',1<<32),
            ('sample_period_ns',0),('sample_count',16385),('valid_tx_reference',False),('noise_active',1)]
        for field,value in changes:
            r=deepcopy(root);r['published_ping'][field]=value
            with self.subTest(field=field),self.assertRaises(ProtocolError):parse_atomic_echogram(encode(r,m1))
        for field,value in [('ping_id','8'),('ping_id',8),('ping_id',True),('capture_sample_count',0),('display_sample_count',16385),
                            ('display_start_sample',-1),('display_end_sample_exclusive',16385),
                            ('display_range_m',3.072),('tx_reference_verified',False),
                            ('raw_samples_modified',True),('detector_uses_full_capture',False)]:
            r=deepcopy(root);r['published_ping']['display_window'][field]=value
            with self.subTest(window=field),self.assertRaises(ProtocolError):parse_atomic_echogram(encode(r,m1))
        # One representable binary32 ULP must not pass a matching-header guard.
        r=deepcopy(root);bits=struct.unpack('<I',struct.pack('<f',r['published_ping']['display_window']['display_range_m']))[0]
        r['published_ping']['display_window']['display_range_m']=struct.unpack('<f',struct.pack('<I',bits+1))[0]
        with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(r,m1))

    def test_unknown_source_is_not_promoted_by_selected_own_and_native_needs_evidence(self):
        root,m1=context_case();root['published_ping'].update(actual_source=None,source_evidence='unconfirmed')
        decoded=parse_atomic_echogram(encode(root,m1));self.assertIsNone(decoded.publication.actual_source)
        root['published_ping'].update(actual_source='native',source_evidence='qualified_native_marker_raw_match')
        with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,m1))
        root['selected_source']='all';self.assertEqual(parse_atomic_echogram(encode(root,m1)).publication.actual_source,'native')
        root['published_ping']['source_evidence']='unconfirmed'
        with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,m1))
        # A native marker cannot borrow the separately qualified own-depth crop.
        cropped,raw=context_case(1);cropped['selected_source']='native'
        cropped['published_ping'].update(actual_source='native',source_evidence='qualified_native_marker_raw_match')
        with self.assertRaisesRegex(ProtocolError,'only verified own'):
            parse_atomic_echogram(encode(cropped,raw))

    def test_new_reason_text_is_bounded_without_relaxing_legacy_http_reason_codes(self):
        for index in (0,2):
            root,m1=context_case(index);root['published_ping']['display_window']['reason']='Ä'*64
            self.assertEqual(parse_atomic_echogram(encode(root,m1)).publication.display_window.reason,'Ä'*64)
            root['published_ping']['display_window']['reason']='Ä'*65
            with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,m1))
        root,m1=context_case();root['published_ping']['display_window']['reason']='new specific observed reason'
        legacy=deepcopy(root['published_ping']);legacy['ping_id']=int(legacy['ping_id']);legacy['time_utc_ns']=int(legacy['time_utc_ns']);legacy['display_window']['ping_id']=int(legacy['display_window']['ping_id'])
        with self.assertRaises(ProtocolError):parse_page(page([legacy]),after_ping_id=0,host_version='0.4.15')
        for value in ['', '\ud800']:
            root['published_ping']['display_window']['reason']=value
            with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,m1))

    def test_final_owner_fixture_hash_and_five_android_supported_roundtrips(self):
        path=Path(__file__).parent/'fixtures/sonar-0416/echogram-context.json'
        raw=path.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'b9ff41f7a72f09bfa7583f96e6bba99c3283119dd7bec197d67ba7f94d413769')
        fixture=json.loads(raw);passed=rejected=0
        for case in fixture['cases']:
            encoded=base64.b64decode(case['envelope_base64'])
            self.assertEqual(hashlib.sha256(encoded).hexdigest(),case['envelope_sha256'])
            if case['name']in{'uint64_int64_max_utf8','utc_int64_min'}:
                with self.assertRaises(ProtocolError):parse_atomic_echogram(encoded)
                rejected+=1
            else:
                value=parse_atomic_echogram(encoded);passed+=1
                self.assertEqual(hashlib.sha256(value.original_m1).hexdigest(),case['m1_sha256'])
                self.assertEqual(value.publication.actual_source,case['metadata']['published_ping']['actual_source'])
        self.assertEqual((passed,rejected),(5,2))
        by_name={c['name']:c for c in fixture['cases']}
        for vector in fixture['negative_prefix_vectors']:
            wire=bytearray(base64.b64decode(by_name[vector['base_case']]['envelope_base64']))
            replacement=bytes.fromhex(vector['replacement_hex']);at=vector['offset'];wire[at:at+len(replacement)]=replacement
            with self.assertRaises(ProtocolError):parse_atomic_echogram(bytes(wire))

    def test_host_single_bin_and_sub_millisecond_utc_are_explicitly_android_unsupported(self):
        root,m1=context_case();root['published_ping']['time_utc_ns']='999999'
        with self.assertRaises(ProtocolError):parse_atomic_echogram(encode(root,modified_m1(m1,utc=999999)))
        root['published_ping']['time_utc_ns']='1000000'
        self.assertEqual(parse_atomic_echogram(encode(root,modified_m1(m1,utc=1000000))).frame.observed_at_epoch_ms,1)
        with self.assertRaisesRegex(ProtocolError,'exactly 1024 bins'):
            parse_atomic_echogram(encode(root,modified_m1(m1,utc=1000000,bins=1)[:42]))

    def test_maximum_ids_sequence_and_track_details_keep_android_metadata_below_4096(self):
        from no5_pi_gateway.state import GatewayState
        from no5_pi_gateway.sonar_presentation import project
        from test_sonar_presentation import analysis
        root,m1=context_case();row=root['published_ping']
        row['ping_id']=row['display_window']['ping_id']=str(MAX_SIGNED);row['time_utc_ns']=str(MAX_SIGNED)
        row['display_generation']=row['selection_generation']=row['noise_revision']=(1<<32)-1
        root['publication_sequence']=str(MAX_SIGNED)
        encoded=encode(root,modified_m1(m1,ping=MAX_SIGNED,utc=MAX_SIGNED));envelope=parse_atomic_echogram(encoded)
        status=atomic_status(root);a=adapter_for(status);a._host_package_version='0.4.16'
        observed=a._atomic_frame(envelope)
        observed=replace(observed,host_package_version='0.4.16',analysis_supported=True,
                         amplitude=a._amplitude,selection_generation_hint=row['display_generation'])
        observed=a._with_publication(observed)
        payload=analysis(ping_id=MAX_SIGNED,source='m1_raw',confidence=.8)
        payload['detection'].update(time_utc_ns=MAX_SIGNED,sample_period_ns=row['sample_period_ns'],tx_zero_sample_index=0)
        payload['presentation']['reason']='Ä'*512
        payload['presentation']['tracks']=[{'track_id':MAX_SIGNED-i,'state':'confirmed','depth_m':float(i+1),
            'confidence':.8,'classification':'multipath_like','classification_source':'rules_v1','detector':'direct_envelope'}for i in range(8)]
        projection=project(observed,payload);self.assertIsNotNone(projection)
        state=GatewayState(a._config);state.begin_connection();state.observe_status(status)
        wire=state.accept_ping(replace(observed,presentation=projection));size=int.from_bytes(wire[:4],'little')
        metadata=json.loads(wire[4:4+size]);self.assertLessEqual(size,4096)
        self.assertEqual(wire[-2048:],m1[40:]);self.assertEqual(metadata['publication']['sequence'],str(MAX_SIGNED))
        self.assertEqual(metadata['pingId'],MAX_SIGNED)
        self.assertEqual(metadata['presentation']['trackTotal'],8)
        self.assertEqual(metadata['presentation']['trackOmitted'],8-len(metadata['presentation']['tracks']))

"""Invented RAM owners only; no chart/Original/renderer/WD access."""
from pathlib import Path
import importlib.util
import struct
import sys
import unittest
from dataclasses import replace
from unittest.mock import patch

from no_chart_converter.rx5 import spatial_ac_filter as api

VIEW = api.View(20,1 << 19,1 << 19)
BOUNDS, _ = VIEW.rectangle()
L,B,R,T = BOUNDS
W,H = R-L,T-B


def rectangle(l,b,r,t):
    return [(l,b),(r,b),(r,t),(l,t),(l,b)]


def fixture(rings,*,program=b'AC(OWNLAND);',anchor=False,kind=3,roles=None):
    roles = [24]*len(rings) if roles is None else roles
    co = bytearray();words = []
    for points,role in zip(rings,roles):
        words.append((role << 24)|len(co));stored = list(reversed(points)) if role & 3 == 1 else points
        co += struct.pack('<I',(1 << 24)|len(stored))+b''.join(struct.pack('<II',*p) for p in stored)
    ix = struct.pack('<I',len(words))+b''.join(struct.pack('<I',w) for w in words)
    ctx = [(196608,program),(196609,program)]
    if anchor:ctx.append((65682,struct.pack('<dd',12.25,-31.5)))
    ro = struct.pack('<HI',len(ctx),0)+b''.join(struct.pack('<IH',k,len(v))+v for k,v in ctx)
    ao = struct.pack('<QHHH',77,0,0,0)
    # Nonzero original I pointer proves original FI is not silently normalized.
    record = api.wire.FEATURE.pack(12345,96,0,9,17,71,kind,4,5,6)
    return record,ix,bytes(co),ro,ao


def prepare(raw,**kw):
    return api.prepare(*raw,view=kw.pop('view',VIEW),
        paint_scope=kw.pop('paint_scope',api.PaintScope(True,'NONE_PROVEN',api.QUERY_SCOPE)),
        policy=kw.pop('policy',api.POLICY),expected_input_pins=kw.pop('expected_input_pins',api.input_pins(*raw)),**kw)


class SpatialACControls(unittest.TestCase):
    def assert_original(self,result,raw):
        self.assertFalse(result['applied']);self.assertFalse(result['nativePixelParityQualified'])
        self.assertEqual(tuple(result['original'].values()),raw)

    def test_full_cover_reuses_clipper_with_exact_rectangle_inverse(self):
        raw = fixture([rectangle(L-1000,B-1000,R+1000,T+1000)])
        result = prepare(raw);self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL')
        self.assertEqual(result['receipt']['outputPoints'],5)
        self.assertTrue(result['receipt']['exactDirectedBoundaryEqualsProtectedIntersection'])
        self.assertFalse(result['receipt']['quantizationOrRepairAdmitted'])
        self.assert_original(result,raw)

    def test_concave_splits_into_two_complete_exteriors(self):
        a,d,g,h = L+W//8,L+3*W//8,L+5*W//8,L+7*W//8
        ring = [(a,B-100),(h,B-100),(h,T+100),(g,T+100),(g,B-50),
                (d,B-50),(d,T+100),(a,T+100),(a,B-100)]
        raw = fixture([ring]);result = prepare(raw)
        self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL')
        self.assertEqual(result['receipt']['outputParts'],2);self.assertEqual(result['receipt']['outputHoles'],0)
        self.assert_original(result,raw)

    def test_hole_and_multiple_exterior_parts_are_not_dropped(self):
        outer = rectangle(L-100,B-100,R+100,T+100)
        hole = list(reversed(rectangle(L+W//4,B+H//4,R-W//4,T-H//4)))
        raw = fixture([outer,hole],roles=[24,20]);result = prepare(raw)
        self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL');self.assertEqual(result['receipt']['outputHoles'],1)
        self.assertEqual(result['receipt']['outputParts'],2);self.assert_original(result,raw)
        crossing_hole = list(reversed(rectangle(R-200,B+100,R+50,T-100)))
        raw = fixture([outer,crossing_hole],roles=[24,20]);result = prepare(raw)
        self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL')
        self.assertEqual(result['receipt']['inputHoles'],1)
        self.assertEqual(result['receipt']['outputHoles'],0)  # exact open notch, not a dropped source hole
        self.assert_original(result,raw)
        left = rectangle(L-100,B+H//4,L+W//4,T-H//4)
        right = rectangle(R-W//4,B+H//4,R+100,T-H//4)
        raw = fixture([left,right]);result = prepare(raw)
        self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL');self.assertEqual(result['receipt']['outputParts'],2)
        self.assert_original(result,raw)

    def test_outside_is_only_local_proposal_and_contained_is_byteexact_keep(self):
        raw = fixture([rectangle(R+100,B,R+200,T)])
        result = prepare(raw);self.assertEqual(result['action'],'OUTSIDE_LOCAL_PROPOSAL')
        self.assertEqual(result['receipt']['exactIntersectionArea2'],[0,1]);self.assert_original(result,raw)
        raw = fixture([rectangle(L+100,B+100,R-100,T-100)])
        result = prepare(raw);self.assertEqual(result['action'],'KEEP');self.assert_original(result,raw)

    def test_default_unknown_text_symbol_line_and_implicit_anchor_keep_early(self):
        good = fixture([rectangle(L-100,B-100,R+100,T+100)])
        with patch.object(api.ring_clip,'clip_ring_owner',side_effect=AssertionError('must KEEP before clip')):
            self.assertEqual(prepare(good,policy=None)['action'],'KEEP')
            self.assertEqual(prepare(good,paint_scope=api.PaintScope())['action'],'KEEP')
            for program in (b'CS(OWNRULE);',b'LS(SOLD,1,OWN);',b'SY(OWN);',b'TX(OBJNAM);',
                            b'AC(OWNLAND);TX(OBJNAM);',b'UNKNOWN;'):
                raw = fixture([rectangle(L-100,B-100,R+100,T+100)],program=program)
                result = prepare(raw);self.assertEqual(result['action'],'KEEP');self.assert_original(result,raw)
            raw = fixture([rectangle(L-100,B-100,R+100,T+100)],kind=2)
            self.assertEqual(prepare(raw)['action'],'KEEP')

    def test_explicit_anchor_reverse_roles_and_record_complements_preserved(self):
        raw = fixture([rectangle(L-100,B-100,R+100,T+100)],anchor=True,roles=[25])
        result = prepare(raw,paint_scope=api.PaintScope(True,'EXISTING_EXPLICIT_F64',api.QUERY_SCOPE))
        self.assertEqual(result['action'],'CLIP_LOCAL_PROPOSAL')
        self.assertEqual(result['receipt']['proposalPartRoles'],[25]);self.assert_original(result,raw)
        self.assertEqual(prepare(raw)['action'],'KEEP')

    def test_fractional_intersection_and_no_repair_are_keep(self):
        triangle = [(L-1,B+100),(L+2,B+101),(L+2,T-100),(L-1,B+100)]
        raw = fixture([triangle]);self.assertEqual(prepare(raw)['action'],'KEEP')
        bad = [(L-100,B-100),(R+100,T+100),(L-100,T+100),(R+100,B-100),(L-100,B-100)]
        self.assertEqual(prepare(fixture([bad]))['action'],'KEEP')
        outer = rectangle(L-100,B-100,R+100,T+100)
        nested = rectangle(L,B,R,T)
        self.assertEqual(prepare(fixture([outer,nested]))['action'],'KEEP')

    def test_unknown_encoding_and_work_or_pin_failure_never_partially_filter(self):
        raw = fixture([rectangle(L-100,B-100,R+100,T+100)])
        self.assertEqual(prepare(raw,limits=replace(api.Limits(),source_points=4))['action'],'KEEP')
        self.assertEqual(prepare(raw,limits=replace(api.Limits(),pair_checks=1))['action'],'KEEP')
        self.assertEqual(prepare(raw,expected_input_pins={})['action'],'KEEP')
        self.assertEqual(prepare(raw,view=api.View(0,0,0))['action'],'KEEP')
        altered = bytearray(raw[2]);struct.pack_into('<I',altered,0,5)
        self.assertEqual(prepare((raw[0],raw[1],bytes(altered),*raw[3:]))['action'],'KEEP')
        masked = fixture([rectangle(L-100,B-100,R+100,T+100)],roles=[8])
        self.assertEqual(prepare(masked)['action'],'KEEP')

    def test_exact_oracle_refuses_invented_inward_proposal(self):
        raw = fixture([rectangle(L-100,B-100,R+100,T+100)])
        real = api.ring_clip.clip_ring_owner
        def wrong(*args,**kwargs):
            co,ix,record,detail = real(*args,**kwargs);buf = bytearray(co)
            # Translate the entire proposed rectangle inward in x by1Root unit.
            # It remains a valid polygon but no longer equals the exact clip.
            for at in range(4,len(buf),8):
                x, = struct.unpack_from('<I',buf,at);struct.pack_into('<I',buf,at,x+1)
            return bytes(buf),ix,record,detail
        with patch.object(api.ring_clip,'clip_ring_owner',side_effect=wrong):
            result = prepare(raw)
        self.assertEqual(result['action'],'KEEP')
        self.assertIn('NONEXACT',result['reason']);self.assert_original(result,raw)


if __name__ == '__main__':
    unittest.main(verbosity=2)

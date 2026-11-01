"""Invented buffers only; no chart, native runtime or file-tree construction."""
import copy
import struct
import unittest

from no_chart_converter.rx5 import finite_source_ac_cull as g


def domain():
    return dict(sourceTile=[8, 3, 5], destinationTiles=[[8, 3, 5]],
                haloPixels=128, transformContract=g.TRANSFORM,
                routingBindingSHA256='1' * 64)


def fixture(*, usage=24, points=None, programme=b'AC(TESTCOLOR);'):
    b = g.bound(domain()['sourceTile'], 128)
    x, y = b[2] + 100, b[3] + 100
    points = points or [(x, y), (x+10, y), (x+10, y+10), (x, y)]
    co = struct.pack('<I', (1 << 24) | len(points)) + b''.join(
        struct.pack('<II', *p) for p in points)
    index = struct.pack('<II', 1, usage << 24)
    render = struct.pack('<HI', 2, 33) + b''.join(
        struct.pack('<IH', key, len(programme)) + programme
        for key in (196608, 196609))
    feature = struct.pack('<Q5I4B', 77, 0, 0, 12, 0, 19, 3, 0, 0, 0)
    return feature, index, co, render


def prepare(args, **kwargs):
    return g.prepare(*args, policy=g.POLICY,
                     domain=kwargs.pop('domain', domain()),
                     expected_input_pins=g.pins(*args), **kwargs)


class Tests(unittest.TestCase):
    def test_default_off_and_complete_literal_inverse(self):
        args = fixture()
        off = g.prepare(*args)
        self.assertEqual(off['action'], 'KEEP')
        self.assertIsNone(off['proposal'])
        result = prepare(args)
        self.assertEqual(result['action'], 'PROPOSE_EMPTY_DRAW_CONTEXTS')
        self.assertEqual(result['original'], dict(zip(
            ('record', 'indices', 'coordinates', 'render'), args)))
        self.assertEqual(result['proposal']['renderFrame'],
                         struct.pack('<HI', 2, 33) +
                         struct.pack('<IH', 196608, 0) +
                         struct.pack('<IH', 196609, 0))
        self.assertFalse(result['receipt']['nativePixelsQualified'])
        self.assertFalse(result['receipt']['allZoomQualified'])

    def test_touching_open_nonarea_or_mixed_programme_keeps(self):
        b = g.bound(domain()['sourceTile'], 128)
        touching = [(b[2], b[3]), (b[2]+10, b[3]),
                    (b[2]+10, b[3]+10), (b[2], b[3])]
        self.assertEqual(prepare(fixture(points=touching))['action'], 'KEEP')
        args = list(fixture())
        co = bytearray(args[2]); struct.pack_into('<II', co, len(co)-8, 1, 2)
        args[2] = bytes(co)
        self.assertEqual(prepare(args)['action'], 'KEEP')
        self.assertEqual(prepare(fixture(usage=16))['action'], 'KEEP')
        self.assertEqual(prepare(fixture(programme=b'AC(TESTCOLOR);SY(TEST);'))[
            'action'], 'KEEP')

    def test_every_ring_and_hole_are_retained_and_checked(self):
        feature, index, co, render = fixture()
        index = struct.pack('<III', 2, 24 << 24, 20 << 24)
        result = prepare((feature, index, co, render))
        self.assertEqual(result['action'], 'PROPOSE_EMPTY_DRAW_CONTEXTS')
        self.assertEqual(result['receipt']['sourceParts'], 2)
        self.assertEqual(result['receipt']['sourceHoles'], 1)
        self.assertEqual(prepare((feature, index, co, render), max_parts=1)[
            'action'], 'KEEP')
        self.assertEqual(prepare(fixture(), max_points=3)['action'], 'KEEP')

    def test_finite_bound_and_literal_inputs_fail_closed(self):
        for key, value in [('destinationTiles', [[9, 0, 0]]),
                           ('routingBindingSHA256', None),
                           ('haloPixels', 24), ('transformContract', 'unknown')]:
            d = domain(); d[key] = value
            self.assertEqual(prepare(fixture(), domain=d)['action'], 'KEEP')
        args = fixture()
        bad = copy.deepcopy(g.pins(*args)); bad['CO']['sha256'] = '0' * 64
        self.assertEqual(g.prepare(*args, policy=g.POLICY, domain=domain(),
                                  expected_input_pins=bad)['action'], 'KEEP')
        far = [(g.Q-10, g.Q-10), (g.Q-9, g.Q-10),
               (g.Q-9, g.Q-9), (g.Q-10, g.Q-10)]
        d = domain(); d['sourceTile'][0] = 18
        d['destinationTiles'][0][0] = 18
        self.assertEqual(prepare(fixture(points=far), domain=d)['action'], 'KEEP')

    def test_complete_source_seam_dedups_only_new_ro_frames(self):
        feature, index, co, render = fixture()
        second = bytearray(feature); struct.pack_into('<Q', second, 0, 78)
        features = feature + bytes(second)
        args = (features, index, co, render)
        off = g.prepare_source(*args)
        self.assertEqual(off['featureBytes'], features)
        self.assertEqual(off['renderBytes'], render)
        result = g.prepare_source(*args, policy=g.POLICY, domain=domain(),
                                  expected_input_pins=g.pins(*args))
        self.assertEqual(result['receipt']['changedOwners'], 2)
        self.assertEqual(result['receipt']['appendedRenderBytes'], 18)
        self.assertEqual(result['indexBytes'], index)
        self.assertEqual(result['coordinateBytes'], co)
        self.assertEqual(result['renderBytes'][:len(render)], render)
        inverse = bytearray(result['featureBytes'])
        for offset in (12, 44):
            self.assertEqual(struct.unpack_from('<I', inverse, offset)[0], len(render))
            inverse[offset:offset+4] = features[offset:offset+4]
        self.assertEqual(bytes(inverse), features)
        with self.assertRaises(ValueError):
            g.prepare_source(feature+feature, index, co, render,
                             policy=g.POLICY, domain=domain(),
                             expected_input_pins=g.pins(feature+feature, index, co, render))


if __name__ == '__main__':
    unittest.main()

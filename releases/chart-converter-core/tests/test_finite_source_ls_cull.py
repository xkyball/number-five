"""Invented buffers only; no chart files, original binaries or native calls."""
from fractions import Fraction
import copy
import struct
import unittest

from no_chart_converter.rx5 import finite_source_ls_cull as g


def domain():
    return dict(sourceTile=[19, 200000, 210000], levels=list(range(19, 30)),
                haloPixels=128, transformContract=g.TRANSFORM,
                painterContract=g.PAINTER, routingBindingSHA256='1'*64)


def fixture(*, points=None, parts=None, usage=16, root=1, kind=2,
            programme=b'LS(DASH,2,TEST_COLOR);', contexts=None):
    box = g.source_box(domain()['sourceTile'])
    x, y = int(box[2])+100000, int(box[3])+100000
    parts = parts or [points or [(x, y), (x+100, y+50), (x+200, y+50)]]
    co, words = bytearray(), []
    for part in parts:
        offset = len(co)
        co.extend(struct.pack('<I', root << 24 | len(part)))
        co.extend(b''.join(struct.pack('<II', *p) for p in part))
        words.append(usage << 24 | offset)
    index = struct.pack('<I', len(parts)) + b''.join(
        struct.pack('<I', word) for word in words)
    maps = contexts or [(k, programme) for k in g.DRAW]
    render = struct.pack('<HI', len(maps), 33) + b''.join(
        struct.pack('<IH', key, len(text))+text for key, text in maps)
    record = struct.pack('<Q5I4B', 77, 0, 0, 12, 0, 19, kind, 0, 0, 0)
    additional = b'opaque attributes and query data\x00\x01\x02'
    return record, index, bytes(co), render, additional


def prepare(buffers, **kwargs):
    return g.prepare(*buffers, policy=g.POLICY,
                     domain=kwargs.pop('domain', domain()),
                     expected_input_pins=g.pins(*buffers), **kwargs)


class Tests(unittest.TestCase):
    def test_default_is_object_identical_even_for_unparsed_input(self):
        buffers = fixture()
        result = g.prepare(*buffers)
        self.assertEqual(result['action'], 'KEEP')
        self.assertIsNone(result['proposal'])
        for value, original in zip(result['original'].values(), buffers):
            self.assertIs(value, original)
        source = g.prepare_source(*buffers)
        for key, original in zip(('featureBytes', 'indexBytes', 'coordinateBytes',
                                  'renderBytes', 'additionalBytes'), buffers):
            self.assertIs(source[key], original)
        bad = (b'unparsed', b'I', b'CO', b'RO', b'AO')
        self.assertEqual(g.prepare_source(*bad)['featureBytes'], bad[0])

    def test_complete_domain_has_every_descendant_axis_not_samples(self):
        buffers = fixture()
        result = prepare(buffers)
        self.assertEqual(result['action'], 'PROPOSE_EMPTY_DRAW_CONTEXTS')
        proof = result['receipt']
        self.assertEqual(proof['sourcePointOccurrences'], 3)
        self.assertFalse(proof['centerSamplesUsedAsProof'])
        self.assertFalse(proof['nativePixelsQualified'])
        self.assertFalse(proof['allZoomProductQualified'])
        rows = proof['completeSubtreeDomains']
        self.assertEqual([r['level'] for r in rows], list(range(19, 30)))
        for row in rows:
            span = 1 << (row['level']-19)
            self.assertEqual(row['x'], [200000*span, 200001*span-1])
            self.assertEqual(row['y'], [210000*span, 210001*span-1])
            self.assertEqual(row['descendantTiles'], span*span)
            self.assertGreater(Fraction(*row['strictResidualPixels']), 0)
        empty = struct.pack('<HI', 2, 33) + b''.join(
            struct.pack('<IH', k, 0) for k in g.DRAW)
        self.assertEqual(result['proposal']['renderFrame'], empty)
        self.assertEqual(result['original'], dict(zip(
            ('record', 'indices', 'coordinates', 'render', 'additional'), buffers)))

    def test_contact_crossing_and_projection_fringe_keep(self):
        box = g.source_box(domain()['sourceTile'])
        x, y = int((box[0]+box[2])/2), int((box[1]+box[3])/2)
        self.assertEqual(prepare(fixture(points=[(x, y), (x+10, y)]))[
            'action'], 'KEEP')
        crossing = [(int(box[0])-10000, y), (int(box[2])+10000, y)]
        self.assertEqual(prepare(fixture(points=crossing))['action'], 'KEEP')
        near = [(int(box[2])+10, y), (int(box[2])+20, y)]
        self.assertEqual(prepare(fixture(points=near))['action'], 'KEEP')
        self.assertIsNone(g.separation((0, 0), (0, 10),
                                      list(map(Fraction, (0, 0, 10, 10)))))

    def test_oblique_halfplane_and_conservative_part_connectors(self):
        self.assertGreater(g.separation((-5, 8), (8, 21),
                          list(map(Fraction, (0, 0, 10, 10)))), 0)
        box = g.source_box(domain()['sourceTile'])
        y = int((box[1]+box[3])/2)
        left, right = int(box[0])-100000, int(box[2])+100000
        parts = [[(left-10, y), (left, y)],
                 [(right, y), (right+10, y)]]
        # Actual LS parts are not shortened or rejoined. This policy also
        # requires hypothetical connectors to be outside; uncertainty KEEP.
        self.assertEqual(prepare(fixture(parts=parts))['action'], 'KEEP')

    def test_lc_extra_programmes_contexts_roles_and_kinds_keep(self):
        for programme in (b'LC(TESTCELL);', b'LS(DASH,3,TEST);',
                          b'LS(DASH,2,TEST);SY(TEST);',
                          b'LS(DASH,2,TEST);TX(x);', b'LS(UNKNOWN,2,TEST);'):
            self.assertEqual(prepare(fixture(programme=programme))[
                'action'], 'KEEP')
        for kwargs in ({'usage': 17}, {'usage': 8}, {'root': 0}, {'kind': 3},
                       {'contexts': [(196608, b'LS(SOLD,1,TEST);')]},
                       {'contexts': [(196608, b'LS(SOLD,1,TEST);'),
                                     (196609, b'LS(SOLD,1,TEST);'),
                                     (9, b'opaque')]}):
            self.assertEqual(prepare(fixture(**kwargs))['action'], 'KEEP')
        self.assertEqual(prepare(fixture(programme=b'LS(SOLD,1,TEST);'))[
            'action'], 'PROPOSE_EMPTY_DRAW_CONTEXTS')

    def test_bad_domains_pins_bounds_and_signed_risks_keep(self):
        for key, value in (('levels', [26]), ('sourceTile', [18, 100000, 105000]),
                           ('haloPixels', 32), ('transformContract', 'unknown'),
                           ('painterContract', 'unqualified'),
                           ('routingBindingSHA256', None)):
            d = domain(); d[key] = value
            self.assertEqual(prepare(fixture(), domain=d)['action'], 'KEEP')
        buffers = fixture()
        pins = copy.deepcopy(g.pins(*buffers)); pins['AO']['sha256'] = '0'*64
        self.assertEqual(g.prepare(*buffers, policy=g.POLICY, domain=domain(),
                                  expected_input_pins=pins)['action'], 'KEEP')
        self.assertEqual(prepare(buffers, max_points=2)['action'], 'KEEP')
        far = [(g.Q-10, g.Q-10), (g.Q-1, g.Q-1)]
        self.assertEqual(prepare(fixture(points=far))['action'], 'KEEP')
        short = list(buffers); short[1] = short[1][:-1]
        self.assertEqual(prepare(short)['action'], 'KEEP')

    def test_full_source_inverse_and_unselected_query_geometry_exact(self):
        record, index, co, ro, ao = fixture()
        second = bytearray(record); struct.pack_into('<Q', second, 0, 78)
        features = record+bytes(second)
        buffers = (features, index, co, ro, ao)
        result = g.prepare_source(*buffers, policy=g.POLICY, domain=domain(),
            owner_uids=[77], expected_input_pins=g.pins(*buffers))
        self.assertEqual(result['receipt']['changedOwners'], 1)
        self.assertEqual(result['receipt']['appendedRenderBytes'], 18)
        self.assertEqual(result['featureBytes'][32:], bytes(second))
        self.assertEqual(result['featureBytes'][:12], record[:12])
        self.assertEqual(result['featureBytes'][16:32], record[16:])
        for key, original in (('indexBytes', index), ('coordinateBytes', co),
                              ('additionalBytes', ao)):
            self.assertIs(result[key], original)
        self.assertEqual(result['renderBytes'][:len(ro)], ro)
        inverse = bytearray(result['featureBytes']); inverse[12:16] = record[12:16]
        self.assertEqual(bytes(inverse), features)
        maps, query = struct.unpack_from('<HI', result['renderBytes'], len(ro))
        self.assertEqual((maps, query), (2, 33))
        shared = g.prepare_source(*buffers, policy=g.POLICY, domain=domain(),
            owner_uids=[77, 78], expected_input_pins=g.pins(*buffers))
        self.assertEqual(shared['receipt']['changedOwners'], 2)
        self.assertEqual(shared['receipt']['appendedRenderBytes'], 18)

    def test_unsupported_source_owner_keeps_and_ambiguous_selection_refuses(self):
        buffers = fixture(programme=b'LC(TESTCELL);')
        result = g.prepare_source(*buffers, policy=g.POLICY, domain=domain(),
            owner_uids=[77], expected_input_pins=g.pins(*buffers))
        self.assertEqual(result['receipt']['changedOwners'], 0)
        for key, raw in zip(('featureBytes', 'indexBytes', 'coordinateBytes',
                             'renderBytes', 'additionalBytes'), buffers):
            self.assertEqual(result[key], raw)
        duplicate = (buffers[0]*2, *buffers[1:])
        with self.assertRaises(ValueError):
            g.prepare_source(*duplicate, policy=g.POLICY, domain=domain(),
                owner_uids=[77], expected_input_pins=g.pins(*duplicate))
        with self.assertRaises(ValueError):
            g.prepare_source(*buffers, policy=g.POLICY, domain=domain(),
                expected_input_pins=g.pins(*buffers))


if __name__ == '__main__':
    unittest.main()

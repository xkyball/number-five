"""Original own synthetic decoder geometry/record controls, ported imports only."""
import io,json,math,struct,unittest
from no_chart_converter import cache_decoder as d

def record(code, body):
    return struct.pack('<HI', code, len(body) + 6) + body

def area_fixture(rings):
    nodes, refs, index = {}, [], 1
    for ring in rings:
        ids = []
        for point in ring:
            ids.append(index)
            nodes[index] = {'positionSm': list(point)}
            index += 1
        refs.extend([ids[i], 0, ids[(i + 1) % len(ids)]] for i in range(len(ids)))
    return d.normalize_refs(refs), nodes

class BinaryFailures(unittest.TestCase):
    def fail_code(self, data, code, offset=0):
        with self.assertRaises(d.DecodeError) as raised:
            d.parse_senc(data, cell_id='SYNTHETIC', member='synthetic/test.senc', complete=False)
        self.assertEqual(raised.exception.code, code)
        self.assertEqual(raised.exception.cell_id, 'SYNTHETIC')
        self.assertEqual(raised.exception.source_record_offset, offset)

    def test_truncated_record_header(self):
        self.fail_code(b'\x01\x00\x08', 'TRUNCATED_RECORD_HEADER')

    def test_length_less_than_header(self):
        self.fail_code(struct.pack('<HI', 1, 5), 'INVALID_RECORD_LENGTH')

    def test_record_extent_overruns(self):
        self.fail_code(struct.pack('<HI', 1, 80) + b'\0\0', 'INVALID_RECORD_LENGTH')

    def test_fictitious_status_is_not_inserted_or_supported(self):
        self.fail_code(record(200, b'\0' * 28), 'UNSUPPORTED_RECORD')

    def test_unsupported_attribute_type(self):
        self.fail_code(record(65, struct.pack('<HB', 43, 1)), 'UNSUPPORTED_ATTRIBUTE_TYPE')

    def test_soundings_count_exceeds_record(self):
        self.fail_code(record(83, struct.pack('<4dI', 0, 1, 20, 21, 0xffffffff)), 'COUNT_OUT_OF_BOUNDS')

    def test_nonfinite_point(self):
        self.fail_code(record(80, struct.pack('<dd', math.nan, 20)), 'NONFINITE_VALUE')

    def test_nonfinite_numeric_attribute(self):
        self.fail_code(record(65, struct.pack('<HBd', 87, 2, math.inf)), 'NONFINITE_VALUE')

    def test_unknown_record_is_rejected(self):
        self.fail_code(record(55555, b''), 'UNSUPPORTED_RECORD')

    def test_invalid_utf8(self):
        self.fail_code(record(2, b'\xff\0'), 'INVALID_TEXT')

    def test_text_with_embedded_nul(self):
        self.fail_code(record(2, b'ab\0cd\0'), 'INVALID_TEXT')

    def test_duplicate_feature_id(self):
        a = record(64, struct.pack('<HHB', 129, 4, 0))
        self.fail_code(a + a, 'DUPLICATE_FEATURE_ID', len(a))

    def test_triangle_list_count_not_multiple_of_three(self):
        self.fail_code(record(82, struct.pack('<4dIII', 0, 1, 20, 21, 0, 1, 0) + struct.pack('<BI4d', 4, 4, 20, 21, 0, 1) + b'\0' * 32), 'INVALID_TRIANGLE')

    def test_duplicate_topology_id(self):
        self.fail_code(record(97, struct.pack('<Iiffiff', 2, 1, 0, 0, 1, 1, 1)), 'INVALID_TOPOLOGY_ID')

    def test_preserves_unsigned_strings_and_repeated_attributes(self):
        body = record(64, struct.pack('<HHB', 65535, 9, 0))
        body += record(65, struct.pack('<HBI', 65534, 0, 0xffffffff))
        body += record(65, struct.pack('<HB', 65533, 4) + 'SYNTHETIC ä λ'.encode() + b'\0')
        body += record(65, struct.pack('<HB', 65533, 4) + b'\0')
        body += record(80, struct.pack('<dd', -35, 20))
        raw = d.parse_senc(body, cell_id='SYNTHETIC', member='synthetic/a', complete=False)
        values = raw['features'][0]['attributes']
        self.assertEqual([a['value'] for a in values], [4294967295, 'SYNTHETIC ä λ', ''])
        self.assertEqual([a['code'] for a in values], [65534, 65533, 65533])

    def test_preserves_soundings_zero_negative_and_ordinals(self):
        body = record(64, struct.pack('<HHB', 129, 1, 0))
        body += record(83, struct.pack('<4dI6f', -36, -34, 19, 21, 2, 0, 0, 0, 20, 30, -1.25))
        raw = d.parse_senc(body, cell_id='SYNTHETIC', member='synthetic/a', complete=False)
        self.assertEqual(raw['features'][0]['geometry']['pointsSmDepth'], [[0, 0, 0], [20, 30, -1.25]])

class GeometryTests(unittest.TestCase):
    def setUp(self):
        self.transform = d.Transform([20, -35])

    def test_negative_edge_keeps_endpoint_order(self):
        refs = d.normalize_refs([[1, -7, 2]])
        nodes = {1: {'positionSm': [0, 0]}, 2: {'positionSm': [100, 0]}}
        edges = {7: {'coordinatesSm': [[75, 0], [25, 0]]}}
        parts, issues = d.assemble_line(refs, nodes, edges, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual(parts[0]['coordinatesSm'], [[0, 0], [25, 0], [75, 0], [100, 0]])
        self.assertEqual(refs[0]['startNodeId'], 1)
        self.assertEqual(refs[0]['endNodeId'], 2)

    def test_zero_edge_is_direct_connector(self):
        refs = d.normalize_refs([[1, 0, 2]])
        parts, issues = d.assemble_line(refs, {1: {'positionSm': [0, 0]}, 2: {'positionSm': [100, 0]}}, {}, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual(parts[0]['coordinatesSm'], [[0, 0], [100, 0]])

    def test_missing_node_is_explicit(self):
        path, issues = d.connector(d.normalize_refs([[1, 0, 2]])[0], {}, {})
        self.assertIsNone(path)
        self.assertTrue(issues[0].startswith('UNRESOLVED_NODE'))

    def test_missing_edge_is_explicit(self):
        path, issues = d.connector(d.normalize_refs([[1, 9, 2]])[0], {1: {'positionSm': [0, 0]}, 2: {'positionSm': [1, 0]}}, {})
        self.assertIsNone(path)
        self.assertTrue(issues[0].startswith('UNRESOLVED_EDGE'))

    def test_disconnected_line_is_not_bridged(self):
        refs = d.normalize_refs([[1, 0, 2], [3, 0, 4]])
        nodes = {i: {'positionSm': [float(i), 0]} for i in range(1, 5)}
        parts, issues = d.assemble_line(refs, nodes, {}, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual(len(parts), 2)
        self.assertEqual([p['edgeRefOrdinals'] for p in parts], [[0], [1]])

    def test_hole_is_classified_by_containment(self):
        refs, nodes = area_fixture([[(0, 0), (100, 0), (100, 100), (0, 100)], [(20, 20), (20, 80), (80, 80), (80, 20)]])
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual([r['role'] for r in rings], ['exterior', 'interior'])
        self.assertEqual(polygons, [{'exteriorRingId': 'r0', 'interiorRingIds': ['r1']}])
        self.assertTrue(all(r['coordinatesSm'][0] == r['coordinatesSm'][-1] for r in rings))

    def test_nested_island_is_another_exterior(self):
        refs, nodes = area_fixture([[(0, 0), (100, 0), (100, 100), (0, 100)], [(20, 20), (20, 80), (80, 80), (80, 20)], [(30, 30), (60, 30), (60, 60), (30, 60)]])
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual([r['role'] for r in rings], ['exterior', 'interior', 'exterior'])
        self.assertEqual(len(polygons), 2)

    def test_self_intersection_is_unresolved_without_repair(self):
        refs, nodes = area_fixture([[(0, 0), (100, 100), (0, 100), (100, 0)]])
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(rings[0]['role'], 'unclassified')
        self.assertEqual(polygons, [])
        self.assertTrue(any('Self-intersection' in issue for issue in issues))

    def test_boundary_touching_is_ambiguous(self):
        refs, nodes = area_fixture([[(0, 0), (100, 0), (100, 100), (0, 100)], [(0, 20), (20, 20), (20, 80), (0, 80)]])
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(polygons, [])
        self.assertTrue(any('intersect or touch' in issue for issue in issues))

    def test_multiple_disjoint_exteriors(self):
        refs, nodes = area_fixture([[(0, 0), (10, 0), (10, 10), (0, 10)], [(30, 0), (40, 0), (40, 10), (30, 10)]])
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(issues, [])
        self.assertEqual([r['role'] for r in rings], ['exterior', 'exterior'])
        self.assertEqual(len(polygons), 2)

    def test_open_boundary_is_not_closed_by_tolerance(self):
        refs = d.normalize_refs([[1, 0, 2], [2, 0, 3]])
        nodes = {1: {'positionSm': [0, 0]}, 2: {'positionSm': [10, 0]}, 3: {'positionSm': [10, 10]}}
        rings, polygons, issues = d.reconstruct_area(refs, nodes, {}, self.transform)
        self.assertEqual(rings, [])
        self.assertEqual(polygons, [])
        self.assertTrue(any('BROKEN_CHAIN' in issue for issue in issues))

    def test_inverse_sm_reference_and_axes(self):
        p = self.transform.convert([0, 0])
        self.assertEqual(p[0], 20)
        self.assertAlmostEqual(p[1], -35, places=12)
        x = 1000
        self.assertAlmostEqual(self.transform.convert([x, 0])[0], 20 + x / (d.DEGREE * d.Z), places=13)

    def test_antimeridian_wrapping_retains_sm(self):
        transform = d.Transform([179.99, 0])
        p = transform.convert([5000, 0])
        self.assertTrue(-180 <= p[0] < -179)
        self.assertEqual(d.bbox([-1, 1, 179, -179])['crossesAntimeridian'], True)

    def test_float32_quantization_bound(self):
        self.assertEqual(d.half_ulp32(1.0), 2 ** -24)
        self.assertEqual(d.half_ulp32(0.0), 2 ** -150)

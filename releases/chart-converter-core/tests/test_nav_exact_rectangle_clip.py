"""Independent finite shape/coverage controls for exact rectangle clipping."""
from fractions import Fraction as F
import unittest
from shapely.geometry import Polygon,box
from shapely.ops import unary_union
from no_chart_converter.navionics.regional_source import exact_rectangle_clip as q


def model(result):
    return unary_union([Polygon([[float(v)for v in p]for p in x['rings'][0]],
        [[[float(v)for v in p]for p in r]for r in x['rings'][1:]])for x in result['polygons']])


class ExactRectangle(unittest.TestCase):
    def check(self,rings,bounds):
        result=q.clip_polygon(rings,bounds)
        expected=Polygon(rings[0],rings[1:]).intersection(box(*bounds))
        self.assertTrue(model(result).equals(expected))
        self.assertEqual(sum((p['area']for p in result['polygons']),F(0)),result['area'])
        return result

    def test_rectangle_inside_source(self):
        self.assertEqual(self.check([[[0,0],[20,0],[20,20],[0,20],[0,0]]],[2,3,8,9])['area'],36)

    def test_source_inside_rectangle(self):
        self.check([[[2,2],[8,2],[8,8],[2,8],[2,2]]],[0,0,10,10])

    def test_concavity_can_split_into_components(self):
        rings=[[[0,0],[8,0],[8,2],[2,2],[2,6],[8,6],[8,8],[0,8],[0,0]]]
        self.assertEqual(len(self.check(rings,[4,0,7,8])['polygons']),2)

    def test_hole_and_opened_hole(self):
        rings=[[[0,0],[12,0],[12,12],[0,12],[0,0]],[[4,4],[4,8],[8,8],[8,4],[4,4]]]
        self.assertEqual(len(self.check(rings,[2,2,10,10])['polygons'][0]['rings']),2)
        self.assertEqual(len(self.check(rings,[6,2,10,10])['polygons'][0]['rings']),1)

    def test_collinear_source_and_rectangle_edges(self):
        self.check([[[0,0],[3,0],[8,0],[8,8],[0,8],[0,0]]],[0,0,4,8])

    def test_touch_only_has_no_fabricated_area(self):
        rings=[[[0,0],[2,0],[2,2],[0,2],[0,0]]]
        self.assertEqual(Polygon(rings[0]).intersection(box(2,2,4,4)).geom_type,'Point')
        x=q.clip_polygon(rings,[2,2,4,4])
        self.assertEqual(x['area'],0)
        self.assertFalse(x['polygons'])

    def test_exact_grid_partition_and_internal_edge_cancellation(self):
        rings=[[[0,0],[11,0],[11,11],[0,11],[0,0]],[[3,3],[3,8],[8,8],[8,3],[3,3]]]
        whole=q.clip_polygon(rings,[1,1,10,10]);parts=[q.clip_polygon(rings,[x,y,x+3,y+3])for x in(1,4,7)for y in(1,4,7)]
        self.assertEqual(sum((p['area']for p in parts),F(0)),whole['area'])
        self.assertEqual(q.normalize_axis_edges([e for p in parts for e in p['rectangleEdges']]),
                         q.normalize_axis_edges(whole['rectangleEdges']))


if __name__=='__main__':unittest.main()

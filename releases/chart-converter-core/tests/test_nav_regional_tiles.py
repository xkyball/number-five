"""Own finite mixed-geometry controls; no real input/Native/transport work."""
import copy
import hashlib
from io import BytesIO
import unittest
from unittest.mock import patch
from no_chart_converter.navionics.regional_source import complete_tiles as t
from no_chart_converter.navionics import atlas
from no_chart_converter.navionics.batch import plan_sha256


def atom(kind,sid,parts=None,point=None,holes=None):
    base=dict(kind=kind,source_id=sid,parent=dict(sourceId=sid),attributes=[],
              sample=None,native_class={24:41,32:29,40:126,44:136}[kind],
              layer={24:38,32:61,40:84,44:140}[kind],depth=None)
    if kind in(40,44):
        base['projected']=[point]
        if kind==44:base['depth']=dict(mantissa=123,decimal_scale=1)
    else:
        base.update(parts=parts,projected=[p for part in parts for p in part])
        if kind==24:
            base['part_metadata']=[dict(sourceRingId='r'+str(i))for i in range(len(parts))]
            base['polygon_roles']=[dict(exteriorRingId='r0',interiorRingIds=['r'+str(i)for i in range(1,len(parts))])]
    return base


def mixed():
    return [atom(24,'own:area',parts=[[[32.,32.],[480.,32.],[480.,480.],[32.,480.],[32.,32.]],
                                    [[200.,200.],[200.,300.],[300.,300.],[300.,200.],[200.,200.]]]),
            atom(32,'own:line',parts=[[[0.,128.],[512.,384.]]]),
            atom(40,'own:point',point=[256,256]),atom(44,'own:depth#p0',point=[128,128])]


def build(atoms):
    return t.build_complete_tiles(atoms,region=[0,0,512,512],tile_side=256,error_cap=2,
        range_policy=t.RANGE_POLICY,header_flags=4660,date_text='20261021')


class CompleteTileControls(unittest.TestCase):
    def test_all_types_whole_tile_context_and_exact_seams(self):
        atoms=mixed();old=copy.deepcopy(atoms);q=build(atoms)
        self.assertEqual(q['mapCount'],4)
        self.assertEqual(atoms,old)
        self.assertEqual({r['kind']for r in q['featureLedger']},{24,32,40,44})
        self.assertTrue(all(r.get('exactGEOSTopologicalPartitionEqual')or
                            r.get('exactRationalSegmentIntervalPartitionEqual')or
                            r.get('exactRationalPolygonAreaAndBoundaryPartitionEqual')for r in q['partitionCoverage']))
        self.assertTrue(q['seams'])
        self.assertTrue(all(r['exactGeometryContactEqual']for r in q['seams']))
        self.assertEqual(sum(r['sourceAtomId']=='own:point'for r in q['featureLedger']),1)
        self.assertEqual(sum(r['sourceAtomId']=='own:depth#p0'for r in q['featureLedger']),1)

    def test_adjacent_collapse_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'COLLAPSED_NO_REPAIR'):
            t.quantize_part([[1.,1.],[1.00001,1.00001]],[0,0,256,256],2,closed=False)

    def test_range_policy_never_implicit(self):
        with self.assertRaisesRegex(ValueError,'EXPLICIT_DIAGNOSTIC'):
            t.build_complete_tiles(mixed(),region=[0,0,512,512],tile_side=256,error_cap=2,
                range_policy=None,header_flags=4660,date_text='20261021')

    def test_native_scale_and_boundary_are_exact(self):
        scales=t.exact_scale([2049792,-4001792,2050048,-4001536])
        self.assertEqual(scales,[2**-21,2**-21])
        for scale in scales:self.assertEqual((32768<<14)*scale,256)

    def test_invalid_source_is_not_repaired(self):
        a=atom(24,'own:invalid',parts=[[[1.,1.],[4.,4.],[1.,4.],[4.,1.],[1.,1.]]])
        with self.assertRaisesRegex(ValueError,'SOURCE_F64_GEOMETRY_INVALID_NO_REPAIR'):
            t.source_geometry(a)

    def test_exact_rational_segment_cover_and_gap(self):
        item=t.exact_segment_interval([0.,0.],[3.,1.],[1,0,2,2])
        self.assertEqual(item[:2],(t.Fraction(1,3),t.Fraction(2,3)))
        self.assertTrue(t.exact_interval_cover(item,[(t.Fraction(1,3),t.Fraction(1,2)),
                                                    (t.Fraction(1,2),t.Fraction(2,3))]))
        with self.assertRaisesRegex(ValueError,'COVERAGE_GAP'):
            t.exact_interval_cover(item,[(t.Fraction(1,3),t.Fraction(2,5)),
                                        (t.Fraction(1,2),t.Fraction(2,3))])

    def test_large_region_refused_before_allocating_or_projecting(self):
        # If the guard regresses, these sentinels stop before an enormous
        # iteration can consume the test host's memory.
        with patch.object(t,'range',side_effect=AssertionError('grid allocated'),create=True), \
             patch.object(t,'source_geometry',side_effect=AssertionError('geometry projected')):
            with self.assertRaisesRegex(ValueError,'COMPLETE_TILE_WORK_BUDGET'):
                t.build_complete_tiles(mixed(),region=[-(2**31),-(2**31),2**31,2**31],
                    tile_side=256,error_cap=2,range_policy=t.RANGE_POLICY,
                    header_flags=4660,date_text='20261021',maximum_maps=16)

    def test_interior_hole_survives_in_one_tile(self):
        a=atom(24,'own:hole',parts=[
            [[0,0],[256,0],[256,256],[0,256],[0,0]],
            [[64,64],[64,192],[192,192],[192,64],[64,64]]])
        q=t.build_complete_tiles([a],region=[0,0,256,256],tile_side=256,
            error_cap=.006,range_policy=t.RANGE_POLICY,
            header_flags=4660,date_text='20261021')
        geometry=q['document']['maps'][0]['layers'][0]['features'][0]
        self.assertEqual(len(geometry['parts']),2)
        self.assertEqual(q['partitionCoverage'][0]['exactClippedArea'],[49152,1])

    def test_no_data_tiles_need_explicit_policy(self):
        with self.assertRaisesRegex(ValueError,'EMPTY_COMPLETE_TILE'):
            build([atom(40,'own:only-point',point=[128,128])])

    def test_mixed_complete_context_emits_with_existing_plain_writer(self):
        document=build(mixed())['document']
        spec=atlas.atlas_from_spec(document)
        plan=atlas.plan_atlas(spec,max_output_bytes=65536)
        sink=BytesIO()
        result=atlas.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(plan),
            max_output_bytes=65536)
        self.assertEqual(plan['mapCount'],4)
        self.assertEqual(result['bytesWritten'],len(sink.getvalue()))
        self.assertEqual(result['emittedStreamSHA256'],hashlib.sha256(sink.getvalue()).hexdigest())


if __name__=='__main__':unittest.main()

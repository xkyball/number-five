"""Invented RAM-only Atlas256 controls; no chart files or native execution."""
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import sys
import unittest

from no_chart_converter.navionics import atlas as old, layered_batch as layered, generic_batch
from no_chart_converter.navionics.batch import Point40, plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader
from no_chart_converter.navionics.road_authoring_registry import ROAD_UNCATEGORIZED_POLICY

# Load a separate source candidate; never overwrite the installed module/globals.
_p=Path(__file__).parents[1]/'src/no_chart_converter/navionics/atlas.py'
if _p.exists():
    _s=importlib.util.spec_from_file_location('no_chart_converter.navionics._own_atlas256_candidate',_p)
    atlas=importlib.util.module_from_spec(_s);sys.modules[_s.name]=atlas;_s.loader.exec_module(atlas)
else:
    atlas=old

BASELINE={
    1:('cee33c212a0ea59714feb542b4bf3ef9313541476dcefac399b037dba7020eef','3db481a219889c4881fb31fa5b78eaf665d5610a7a2fae0d8d38229d02af09e4'),
    2:('aab477bda8c791e57128d0c8c076e05ff78db7b5dc74c060f91d6795b9c43b74','fe7cf393fd15e96d0f61753067f2d55fb61e0584cd72186758199f6e297112a3'),
    8:('ab6c91399679dc3cf2d333514a9c4ee0dd5a389d40a3e4933ed5a268d9ffccce','2dec877f498eddff1a87f5f888ed2c38bb97983184ac0c302f24d7fa5fd5c718'),
    16:('48edf34949e22f3bc0a148e5285ee9f442a1120e8aee2be041fcd2a75eeb5504','93c88c17fec59336a0015d6223b0284961985f4aa1c819517bc0cb82c41b6d29')}


def header():return RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0)


def raw(i):
    return layered.LayeredBatchSpec(header(),[
        layered.PlainLayer(60,[Point40(f'p{i}',126,(100001,100002))]),
        layered.PlainLayer(140,[layered.DepthPoint44(f'd{i}',(100003,100004),101)])])


def road_doc(i):
    return dict(format='no5-own-plain-generic-attribute-batch/1',road_authoring_policy=ROAD_UNCATEGORIZED_POLICY,
        header=header().__dict__,date_text_1='20261017',date_text_2='20261017',
        layers=[dict(layer_id=121,layer_range=[0,100000],features=[dict(kind=32,class_code=208,
            source_id=f'OWN_ROAD:{i}',parts=[[[0,0],[10,20],[100,100]]],attributes=[],
            native_level_range={'min_level':0,'max_level':15})])])


def emit(spec,budget=1<<20,sink=None):
    plan=atlas.plan_atlas(spec,max_output_bytes=budget)
    sink=io.BytesIO()if sink is None else sink
    result=atlas.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(plan),max_output_bytes=budget)
    return plan,sink,result


def record(raw,at):
    tag=int.from_bytes(raw[at:at+2],'little');width=(1,2,4,4)[(tag>>6)&3]
    size=int.from_bytes(raw[at+2:at+2+width],'little')
    return tag&63,at+2+width,size,width


class AtlasWorkProfileTests(unittest.TestCase):
    def test_all_four_legacy_plan_and_stream_goldens_exact(self):
        for n,golden in BASELINE.items():
            with self.subTest(maps=n):
                plan,sink,_=emit(atlas.AtlasSpec([raw(i)for i in range(n)]))
                self.assertEqual((plan_sha256(plan),hashlib.sha256(sink.getvalue()).hexdigest()),golden)
                self.assertEqual(plan,old.plan_atlas(old.AtlasSpec([raw(i)for i in range(n)]),max_output_bytes=1<<20))
                self.assertNotIn('workProfile',plan['softwareProfile'])

    def test_default16_scope_and_parser_unchanged(self):
        with self.assertRaisesRegex(ValueError,'1..16'):atlas.AtlasSpec([raw(i)for i in range(17)])
        with self.assertRaises(ValueError):atlas.atlas_from_spec({'format':'no5-own-plain-atlas/1','maps':[road_doc(i)for i in range(17)]})
        with self.assertRaises(ValueError):atlas.atlas_from_spec({'format':'no5-own-plain-atlas/1','maps':[road_doc(0)],'work_profile':atlas.ATLAS_256_WORK_PROFILE})
        self.assertEqual(old.MAX_ATLAS_MAPS,16)

    def test_directory_width_boundary_and_all_256_global_pointers(self):
        for n,width in ((17,1),(63,1),(64,2),(256,2)):
            with self.subTest(maps=n):
                maps=[generic_batch.generic_batch_from_spec(road_doc(i))for i in range(n)]
                plan,sink,result=emit(atlas.AtlasSpec(maps,work_profile=atlas.ATLAS_256_WORK_PROFILE))
                b=sink.getvalue();kind,at,size,w=record(b,258)
                self.assertEqual((kind,size,w),(4,4*n,width))
                self.assertEqual(plan['mapCount'],n)
                self.assertEqual(plan['softwareProfile']['maxMaps'],256)
                self.assertFalse(plan['softwareProfile']['budgetIsFormatLimit'])
                self.assertEqual(plan['headerGroup']['bytes'],55*n)
                self.assertEqual(at+size,plan['headerGroup']['firstOffset'])
                pointers=struct.unpack_from('<'+'I'*n,b,at)
                self.assertEqual(list(pointers),[m['headerOffset']for m in plan['maps']])
                self.assertEqual(len(b),plan['totalBytes'])
                self.assertEqual(hashlib.sha256(b).hexdigest(),result['emittedStreamSHA256'])
                cursor=plan['headerGroup']['firstOffset']+55*n
                for i,row in enumerate(plan['maps']):
                    p=row['bodyPlan'];f=p['features'][0]
                    self.assertEqual(row['bodyOffset'],cursor)
                    self.assertEqual(row['headerOffset'],pointers[0]+55*i)
                    k,h,z,_=record(b,row['headerOffset']);self.assertEqual((k,z),(17,52))
                    self.assertEqual(struct.unpack_from('<I',b,h+48)[0],p['firstLayerOffset'])
                    k,ly,z,_=record(b,p['layers'][0]['recordOffset']);self.assertEqual((k,z),(18,19))
                    self.assertEqual(struct.unpack_from('<I',b,ly+3)[0],f['recordOffset'])
                    k,start,z,wf=record(b,f['recordOffset']);self.assertEqual(k,32)
                    # Line32 starts with its eight-byte U16 bounding box.
                    self.assertEqual(b[start+8:start+10],bytes([208,15]))
                    self.assertEqual(int.from_bytes(b[start+10:start+10+wf],'little'),0)
                    pointer=struct.unpack_from('<I',b,start+10+2*wf)[0]
                    self.assertEqual(pointer,f['parts'][0]['streamOffset'])
                    self.assertLess(pointer,1<<30)
                    self.assertEqual(b[pointer+f['parts'][0]['streamBytes']-1],0)
                    self.assertEqual(record(b,p['layers'][0]['featureEndOffset'])[0],0)
                    self.assertEqual(record(b,p['layerEndOffset'])[0],0)
                    cursor+=row['bodyBytes']
                self.assertEqual(cursor,len(b))

    def test_new_exact_json_profile_schema_delegates_map_parser(self):
        document={'format':'no5-own-plain-atlas-work-profile/1','work_profile':atlas.ATLAS_256_WORK_PROFILE,'maps':[road_doc(i)for i in range(17)]}
        parsed=atlas.atlas_work_profile_from_spec(document)
        self.assertEqual(len(parsed.maps),17)
        self.assertEqual(parsed.work_profile,atlas.ATLAS_256_WORK_PROFILE)
        for changed in ({**document,'format':'no5-own-plain-atlas/1'},{**document,'extra':0},{**document,'work_profile':True},{**document,'maps':[]}):
            with self.assertRaises(ValueError):atlas.atlas_work_profile_from_spec(changed)

    def test_profile_list_budget_refuses_before_parsing_any_map(self):
        from unittest.mock import patch
        with patch.object(atlas,'atlas_from_spec',side_effect=AssertionError('must not parse'))as parser:
            with self.assertRaises(ValueError):atlas.atlas_work_profile_from_spec({'format':'no5-own-plain-atlas-work-profile/1',
                'work_profile':atlas.ATLAS_256_WORK_PROFILE,'maps':[{}for _ in range(257)]})
            parser.assert_not_called()

    def test_256_is_own_cap_unknown_profiles_and_global_id_date_conflicts_refuse(self):
        with self.assertRaisesRegex(ValueError,'1..256'):atlas.AtlasSpec([raw(i)for i in range(257)],work_profile=atlas.ATLAS_256_WORK_PROFILE)
        for value in (True,256,'256','unbounded'):
            with self.subTest(profile=value),self.assertRaises(ValueError):atlas.AtlasSpec([raw(0)],work_profile=value)
        with self.assertRaisesRegex(ValueError,'source_id'):atlas.AtlasSpec([raw(0),raw(0)],work_profile=atlas.ATLAS_256_WORK_PROFILE)
        other=layered.LayeredBatchSpec(header(),raw(1).layers,'20261018','20261018')
        with self.assertRaisesRegex(ValueError,'dates'):atlas.AtlasSpec([raw(0),other],work_profile=atlas.ATLAS_256_WORK_PROFILE)

    def test_explicit_profile_changes_plan_binding_not_geometry_for_existing_small_input(self):
        maps=[raw(0),raw(1)]
        default,default_sink,_=emit(atlas.AtlasSpec(maps))
        explicit,explicit_sink,_=emit(atlas.AtlasSpec(maps,work_profile=atlas.ATLAS_256_WORK_PROFILE))
        self.assertEqual(default_sink.getvalue(),explicit_sink.getvalue())
        self.assertNotEqual(plan_sha256(default),plan_sha256(explicit))
        sink=io.BytesIO()
        with self.assertRaisesRegex(ValueError,'EXACT_EXPECTED_PLAN'):
            atlas.write_atlas(atlas.AtlasSpec(maps,work_profile=atlas.ATLAS_256_WORK_PROFILE),sink,
                expected_plan_sha256=plan_sha256(default),max_output_bytes=1<<20)
        self.assertEqual(sink.getvalue(),b'')

    def test_output_budget_still_refuses_before_sink_and_shortwrite_retries_bounded(self):
        spec=atlas.AtlasSpec([raw(i)for i in range(256)],work_profile=atlas.ATLAS_256_WORK_PROFILE)
        p=atlas.plan_atlas(spec,max_output_bytes=1<<20)
        sink=io.BytesIO()
        with self.assertRaises(ValueError):atlas.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(p),max_output_bytes=p['totalBytes']-1)
        self.assertEqual(sink.getvalue(),b'')
        class Short:
            def __init__(self):self.data=bytearray();self.maximum=0
            def write(self,data):
                self.maximum=max(self.maximum,len(data));n=min(97,len(data));self.data.extend(data[:n]);return n
        short=Short();_,_,emission=emit(spec,sink=short)
        self.assertLessEqual(short.maximum,65536)
        self.assertEqual(hashlib.sha256(short.data).hexdigest(),emission['emittedStreamSHA256'])


if __name__=='__main__':unittest.main()

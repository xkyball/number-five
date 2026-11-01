"""Invented RAM-only profile/layout controls; no country data or Native calls."""
import ast
from copy import deepcopy
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import sys
import types
import unittest
from unittest.mock import patch

from no_chart_converter.navionics import atlas as old, fragment_grouping as old_group
from no_chart_converter.navionics import generic_batch, layered_batch
from no_chart_converter.navionics.batch import Point40, plan_sha256
from no_chart_converter.navionics.writer import RawMapHeader, decode_record

D=Path(__file__).parent
P=D/'package/src/no_chart_converter/navionics'
PRIVATE=P.is_dir()
LEGACY_PARENT=not hasattr(old,'ATLAS_2048_WORK_PROFILE')
if PRIVATE:
    NAME='no_chart_converter.navionics._own_atlas2048_candidate_v129'
    s=importlib.util.spec_from_file_location(NAME,P/'atlas.py')
    atlas=importlib.util.module_from_spec(s);sys.modules[NAME]=atlas;s.loader.exec_module(atlas)
    # Bind only the candidate imports; every grouping body stays intact.
    tree=ast.parse((P/'fragment_grouping.py').read_bytes())
    for node in tree.body:
        if isinstance(node,ast.ImportFrom)and node.module=='no_chart_converter.navionics.atlas':node.module=NAME
    group=types.ModuleType('own_fragment_grouping2048_v129')
    exec(compile(ast.fix_missing_locations(tree),str(P/'fragment_grouping.py'),'exec'),group.__dict__)
else:
    atlas=old;group=old_group


def header():return RawMapHeader(0x1234,(100000,100000,101000,101000),(1024,1024),(8,20),0)


def raw(i):
    return layered_batch.LayeredBatchSpec(header(),[
        layered_batch.PlainLayer(60,[Point40('p'+str(i),126,(100001,100002))]),
        layered_batch.PlainLayer(140,[layered_batch.DepthPoint44('d'+str(i),(100003,100004),101)])])


def area_doc(i,*,header_group=None,with_range=True):
    k=i if header_group is None else header_group
    f=dict(kind=24,source_id='INVENTED:area:'+str(i),class_code=41,
        parts=[[[0,0],[100,0],[100,100],[0,100],[0,0]],
               [[25,25],[25,75],[75,75],[75,25],[25,25]]],
        attributes=[dict(native_code_u16=82,raw_integer=-10,declared_native_units=2),
                    dict(native_code_u16=83,raw_integer=300,declared_native_units=2)])
    if with_range:f['native_level_range']=dict(min_level=0,max_level=15)
    return dict(format='no5-own-plain-generic-attribute-batch/1',date_text_1='20261017',date_text_2='20261017',
        header=dict(flags=0x1234,raw_bounds=[k*20,0,k*20+20,20],raw_grid=[101,101],raw_levels=[0,15],trailing_value=0),
        layers=[dict(layer_id=38,layer_range=[0,100000],features=[f])])


def profiled(maps):
    return dict(format='no5-own-plain-atlas2048-work-profile/1',work_profile=atlas.ATLAS_2048_WORK_PROFILE,maps=maps)


def emit(api,spec,budget=1<<20):
    p=api.plan_atlas(spec,max_output_bytes=budget);sink=io.BytesIO()
    result=api.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(p),max_output_bytes=budget)
    return p,sink.getvalue(),result


def bindings(maps,historical_absent=False):
    result=[]
    for i,m in enumerate(maps):
        f=m['layers'][0]['features'][0];hist=deepcopy(m)
        if historical_absent:hist['layers'][0]['features'][0].pop('native_level_range',None)
        result.append(dict(unitOrdinal=i,mapOrdinal=i,fragmentSourceId=f['source_id'],
            savedDocumentSHA256=group.digest(dict(format='no5-own-plain-atlas/1',maps=[hist])),
            savedFinalAtlasPlanSHA256='1'*64,geometrySHA256=group.digest(f['parts']),
            attributesSHA256=group.digest(f['attributes']),headerSHA256=group.digest(m['header']),
            partVertexCounts=[5,5],historicalNativeRange=None if historical_absent else f.get('native_level_range'),
            authoredNativeRange=f.get('native_level_range')))
    return result


def grouped(api,doc,rows,*,extended=False,**kwargs):
    source=dict(originalAttributesSHA256='2'*64)
    fn=api.group_exact_maps_2048 if extended else api.group_exact_maps
    return fn(doc,rows,source,policy=api.POLICY,expected_document_sha256=api.digest(doc),
        expected_bindings_sha256=api.digest(dict(orderedUnits=rows,sourceBinding=source)),**kwargs)


class ProfileControls(unittest.TestCase):
    def test_01_all_four_legacy_goldens_and_256_outputs_exact(self):
        golden={1:('cee33c212a0ea59714feb542b4bf3ef9313541476dcefac399b037dba7020eef','3db481a219889c4881fb31fa5b78eaf665d5610a7a2fae0d8d38229d02af09e4'),
            2:('aab477bda8c791e57128d0c8c076e05ff78db7b5dc74c060f91d6795b9c43b74','fe7cf393fd15e96d0f61753067f2d55fb61e0584cd72186758199f6e297112a3'),
            8:('ab6c91399679dc3cf2d333514a9c4ee0dd5a389d40a3e4933ed5a268d9ffccce','2dec877f498eddff1a87f5f888ed2c38bb97983184ac0c302f24d7fa5fd5c718'),
            16:('48edf34949e22f3bc0a148e5285ee9f442a1120e8aee2be041fcd2a75eeb5504','93c88c17fec59336a0015d6223b0284961985f4aa1c819517bc0cb82c41b6d29')}
        for n,want in golden.items():
            maps=[raw(i)for i in range(n)];p,b,_=emit(atlas,atlas.AtlasSpec(maps))
            self.assertEqual((plan_sha256(p),hashlib.sha256(b).hexdigest()),want)
            self.assertEqual((p,b),emit(old,old.AtlasSpec(maps))[:2])
        for n in (17,63,64,256):
            maps=[raw(i)for i in range(n)]
            p,b,_=emit(atlas,atlas.AtlasSpec(maps,atlas.ATLAS_256_WORK_PROFILE))
            golden256={
                17:('3cb727fc2422f4c2fe9181279abf9a781280a34ed5d92ce9231b393a06b40d12','3469e349ab15348354e3a1a41953bf3ce10cfc2f7a1a94d62a5f58d55c5b5f74'),
                63:('8b95f942caf9a9c1a9081cee46e3543d5497a3a725ec5ed1c401df2ebe86a213','02b87c994c16dc7c29aeefc6b867da75f8e706997380ecb401911a54bedbe7ed'),
                64:('5d401ebb3390fff07b01cc9f93fbd7e5d71da6d1e9ffa3daf05ccaf92838785e','6f55fba9814cbbe9012ee60ec1e029dd9e6fc1e7366e7c9bb855d6046d00a9fc'),
                256:('5aedfc6ec228a4819063e97f3f383c6cb71071125c2f5c43349d218e1b2d5f49','5e27b697d6a6b2029e9f85ad2d729be276507c9e4bd77dbb930f16e4c24e1d4b')}
            self.assertEqual((plan_sha256(p),hashlib.sha256(b).hexdigest()),golden256[n])
            self.assertEqual((p,b),emit(old,old.AtlasSpec(maps,old.ATLAS_256_WORK_PROFILE))[:2])

    def test_02_strict_separate_optin_and_count_before_map_parser(self):
        self.assertEqual(old.MAX_ATLAS_MAPS,16);self.assertEqual(old.MAX_ATLAS_256_MAPS,256)
        for api in (old,atlas):
            with self.assertRaises(ValueError):api.atlas_from_spec(dict(format='no5-own-plain-atlas/1',maps=[area_doc(i)for i in range(17)]))
            with self.assertRaises(ValueError):api.atlas_work_profile_from_spec(profiled([area_doc(0)]))
        for n in (0,2049):
            with patch.object(atlas,'atlas_from_spec',side_effect=AssertionError('parser reached'))as parser:
                with self.assertRaises(ValueError):atlas.atlas_2048_work_profile_from_spec(profiled([{}for _ in range(n)]))
                parser.assert_not_called()
        for profile in (True,'2048','unbounded',old.ATLAS_256_WORK_PROFILE):
            with self.assertRaises(ValueError):atlas.atlas_2048_work_profile_from_spec({**profiled([area_doc(0)]),'work_profile':profile})

    def test_03_2048_directory_all_headers_body_pointers_holes_attrs_exact(self):
        document=profiled([area_doc(i)for i in range(2048)]);before=deepcopy(document)
        spec=atlas.atlas_2048_work_profile_from_spec(document);p,b,r=emit(atlas,spec)
        directory=decode_record(b,258)
        self.assertEqual((directory['kind'],directory['payloadBytes'],directory['lengthWidth']),(4,8192,2))
        self.assertEqual(p['headerGroup']['firstOffset'],8454)
        self.assertEqual(p['headerGroup']['bytes'],112640)
        self.assertEqual(p['maps'][0]['bodyOffset'],121094)
        pointers=struct.unpack_from('<2048I',b,directory['payloadStart'])
        for i,row in enumerate(p['maps']):
            self.assertEqual(row['headerOffset'],pointers[i]);h=decode_record(b,pointers[i]);at=h['payloadStart']
            source=document['maps'][i]['header'];self.assertEqual((h['kind'],h['payloadBytes']),(17,52))
            self.assertEqual(struct.unpack_from('<H',b,at)[0],source['flags'])
            self.assertEqual(list(struct.unpack_from('<4i',b,at+4)),source['raw_bounds'])
            self.assertEqual(list(struct.unpack_from('<2I',b,at+24)),source['raw_grid'])
            self.assertEqual(list(struct.unpack_from('<2I',b,at+36)),source['raw_levels'])
            body=row['bodyPlan'];self.assertEqual(struct.unpack_from('<I',b,at+48)[0],body['firstLayerOffset'])
            f=body['features'][0];self.assertEqual(f['sourceId'],'INVENTED:area:'+str(i))
            self.assertEqual([v['pointCount']for v in f['parts']],[5,5])
            self.assertEqual([(v['wire_i32'],v['declared_native_units'])for v in f['attributes']],[(-10,2),(300,2)])
            for part in f['parts']:self.assertLess(part['streamOffset'],1<<30)
        self.assertEqual(len(b),p['totalBytes']);self.assertEqual(hashlib.sha256(b).hexdigest(),r['emittedStreamSHA256'])
        self.assertEqual(document,before);self.assertFalse(p['softwareProfile']['budgetIsFormatLimit'])
        self.assertEqual(p['softwareProfile']['maxMaps'],2048)

    def test_04_profile_hash_separate_and_unchanged_text_enum_wire(self):
        feature=generic_batch.GenericAttributedFeature(Point40('light',71,(100001,100002)),[
            generic_batch.GenericAttribute(109,'Own café'),generic_batch.GenericAttribute(72,[3,4]),
            generic_batch.GenericAttribute(90,60,2)])
        m=generic_batch.GenericAttributedBatchSpec(header(),generic_batch.group_generic_features([feature]))
        p,b,_=emit(atlas,atlas.AtlasSpec([m],atlas.ATLAS_2048_WORK_PROFILE))
        oldp,oldb,_=emit(old,old.AtlasSpec([m],old.ATLAS_256_WORK_PROFILE))
        self.assertEqual(b,oldb);self.assertNotEqual(plan_sha256(p),plan_sha256(oldp))
        sink=io.BytesIO()
        with self.assertRaisesRegex(ValueError,'EXACT_EXPECTED_PLAN'):
            atlas.write_atlas(atlas.AtlasSpec([m],atlas.ATLAS_2048_WORK_PROFILE),sink,
                expected_plan_sha256=plan_sha256(oldp),max_output_bytes=1<<20)
        self.assertEqual(sink.getvalue(),b'')

    def test_05_existing_dates_ids_output_and_2049_budgets_remain(self):
        with self.assertRaisesRegex(ValueError,'1..2048'):atlas.AtlasSpec([raw(i)for i in range(2049)],atlas.ATLAS_2048_WORK_PROFILE)
        with self.assertRaisesRegex(ValueError,'source_id'):atlas.AtlasSpec([raw(0),raw(0)],atlas.ATLAS_2048_WORK_PROFILE)
        changed=layered_batch.LayeredBatchSpec(header(),raw(1).layers,'20261018','20261018')
        with self.assertRaisesRegex(ValueError,'dates'):atlas.AtlasSpec([raw(0),changed],atlas.ATLAS_2048_WORK_PROFILE)
        spec=atlas.AtlasSpec([raw(0)],atlas.ATLAS_2048_WORK_PROFILE)
        p=atlas.plan_atlas(spec,max_output_bytes=1<<20);sink=io.BytesIO()
        with self.assertRaises(ValueError):atlas.write_atlas(spec,sink,expected_plan_sha256=plan_sha256(p),max_output_bytes=p['totalBytes']-1)
        self.assertEqual(sink.getvalue(),b'')
        with self.assertRaises(ValueError):atlas.plan_atlas(spec,max_output_bytes=64*1024*1024+1)

    def test_06_grouping_legacy_value_exact_and_new2048_preserves_every_map(self):
        maps=[area_doc(i,header_group=i//2)for i in range(6)];rows=bindings(maps,historical_absent=True)
        doc=dict(format='no5-own-plain-atlas/1',maps=maps)
        legacy=grouped(group,doc,rows)
        self.assertEqual(legacy,grouped(old_group,doc,rows))
        self.assertEqual(group.digest(legacy),'50a8409c8cc5c089b158d4cd0577f3db38bf644c7b94820004cfe7ac6026d3c9')
        maps=[area_doc(i,with_range=False)for i in range(2048)];rows=bindings(maps)
        doc=profiled(maps);before=deepcopy((doc,rows));result=grouped(group,doc,rows,extended=True)
        self.assertEqual((doc,rows),before)
        self.assertEqual(result['document']['maps'],maps)
        self.assertEqual((result['ledger']['features'],result['ledger']['groupedMaps']),(2048,2048))
        self.assertEqual(result['ledger']['geometryUnionsOrReencodes'],0)
        self.assertTrue(all('native_level_range'not in m['layers'][0]['features'][0]for m in result['document']['maps']))
        with self.assertRaises(ValueError):grouped(group,doc,rows,max_maps=2048)

    def test_07_exact_grouping_keeps_style_range_holes_and_feature_work_cap(self):
        maps=[area_doc(i,header_group=0)for i in range(3)];rows=bindings(maps)
        result=grouped(group,profiled(maps),rows,extended=True)
        features=result['document']['maps'][0]['layers'][0]['features']
        self.assertEqual(features,[m['layers'][0]['features'][0]for m in maps])
        self.assertEqual(result['ledger']['partCount'],6)
        with self.assertRaisesRegex(ValueError,'FEATURE_WORK_CAP'):
            grouped(group,profiled(maps),rows,extended=True,max_features_per_group=2)
        maps[1]['layers'][0]['features'][0]['native_level_range']['max_level']=0
        rows=bindings(maps);result=grouped(group,profiled(maps),rows,extended=True)
        self.assertEqual(result['ledger']['groupedMaps'],2)

    def test_08_old_parsers_rebase_emit_and_public_namespace_unmodified(self):
        oldtree=ast.parse(Path(old.__file__).read_bytes());newtree=ast.parse((P/'atlas.py'if PRIVATE else Path(atlas.__file__)).read_bytes())
        def defs(t):return {n.name:ast.dump(n,include_attributes=False)for n in t.body if isinstance(n,ast.FunctionDef)}
        a,b=defs(oldtree),defs(newtree)
        for name in ('atlas_from_spec','atlas_work_profile_from_spec','_rebase','_atlas_chunks','_coalesced','write_atlas'):
            self.assertEqual(a[name],b[name])
        self.assertIs(sys.modules['no_chart_converter.navionics.atlas'],old)
        self.assertIs(sys.modules['no_chart_converter.navionics.fragment_grouping'],old_group)
        if LEGACY_PARENT:self.assertFalse(hasattr(old,'ATLAS_2048_WORK_PROFILE'))


if __name__=='__main__':unittest.main()

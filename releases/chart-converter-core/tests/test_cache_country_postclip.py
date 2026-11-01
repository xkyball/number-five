"""Invented cache-to-prepared adapter tests; country materialization intercepted.

The private runner injects only its reviewed candidate pipeline at this import.
After publication this same test uses the installed public import unchanged.
"""
from pathlib import Path
import copy
import hashlib
import json
import struct
import tempfile
import types
import unittest
from unittest.mock import patch

from cache_country_fixture import canonical, file_pin, write_package, write_presentation_config
from no_chart_converter.cache_country import pipeline as candidate
from no_chart_converter.country_runtime import inputs, postclip_alias, writer_extension, wire
from no_chart_converter.country_runtime import render_activation

OPTION = dict(policy=postclip_alias.POLICY,
              limits=dict(max_parts=4096, max_raw_point_occurrences=1000000,
                          max_referenced_frame_bytes=16777215,
                          max_output_index_bytes=32768))


def document(pin):
    raw=Path(pin['path']).read_bytes()
    assert len(raw)==pin['bytes'] and hashlib.sha256(raw).hexdigest()==pin['sha256']
    return json.loads(raw)


def query_starts(render):
    at=0;starts=set()
    while at<len(render):
        count,query=struct.unpack_from('<HI',render,at);at+=6;starts.add(query)
        for _ in range(count):
            _,n=struct.unpack_from('<IH',render,at);at+=6+n
    assert at==len(render)
    return starts


class CacheCountryPostclipAdapter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='no5-invented-cache-postclip-')
        cls.root=Path(cls.temp.name).resolve()
        cls.package=write_package(cls.root/'source')
        profile_config=write_presentation_config(cls.root/'profile')
        cls.config=json.loads(profile_config.read_bytes())
        profile_path=Path(cls.config['presentationProfile']['path'])
        profile=json.loads(profile_path.read_bytes())
        area=next(r for r in profile['rules'] if r['classAcronym']=='DEPARE')
        area['instruction']='AC(SYNTHETIC);LC(OWN00001);'
        profile_path.write_bytes(canonical(profile))
        cls.config['presentationProfile']=file_pin(profile_path)
        cls.config['baseDirectory']=str(profile_path.parent)
        cls.original_source=cls.package.source.read_bytes()
        cls.captured=[];cls.results={}
        def capture(prepared, *, prepare_only):
            assert prepare_only is True
            cls.captured.append(document(file_pin(prepared)))
            return dict(status='INVENTED_PREPARED_CONFIG_CAPTURE_NO_COUNTRY',nativeCalls=0)
        # The real public adapter does its synthetic IR/metadata/presentation/
        # geometry work. Only the final entire-country call is intercepted.
        with patch.object(candidate,'build_country',side_effect=capture):
            for enabled in (False,True):
                cfg=copy.deepcopy(cls.config)
                if enabled:cfg['postclipBoundaryRunAliases']=copy.deepcopy(OPTION)
                before=copy.deepcopy(cfg)
                result=candidate.export_country(cls.package.source,cls.package.catalogs,
                    cls.root/('enabled' if enabled else 'default'),config=cfg,
                    expected_sha256=hashlib.sha256(cls.original_source).hexdigest(),prepare_only=True)
                assert cfg==before
                cls.results[enabled]=result
        assert cls.package.source.read_bytes()==cls.original_source
        # Presentation prepares its tiny synthetic metadata/resource parent;
        # the complete country composer/materializer remains intercepted.
        assert not (cls.root/'default/country').exists()
        assert not (cls.root/'enabled/country').exists()
        assert sum(p.stat().st_size for p in cls.root.rglob('*') if p.is_file())<4*1024*1024

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_default_off_and_exact_explicit_option_reaches_country(self):
        default,enabled=self.captured
        self.assertNotIn('postclipBoundaryRunAliases',default)
        self.assertEqual(enabled['postclipBoundaryRunAliases'],OPTION)
        self.assertEqual(set(enabled)-set(default),{'postclipBoundaryRunAliases'})
        self.assertEqual(set(enabled['inputs']),set(inputs.REQUIRED))
        self.assertEqual(len(enabled['inputs']),15)
        self.assertEqual(self.results[True]['status'],'PASS_CACHE_TO_PREPARED')
        self.assertEqual(self.results[True]['country']['nativeCalls'],0)
        self.assertFalse((self.root/'enabled/country').exists())
        for key in ('format','title','editionDate','expectedSourceCounts'):
            self.assertEqual(default[key],enabled[key])

    def test_path_and_dict_configuration_keep_values_and_source_identity(self):
        cfg=copy.deepcopy(self.config);cfg['postclipBoundaryRunAliases']=copy.deepcopy(OPTION)
        before=copy.deepcopy(cfg)
        config_path=self.root/'explicit-config.json';config_path.write_bytes(canonical(cfg))
        dictionary=candidate.configuration(cfg)
        pathname=candidate.configuration(config_path)
        self.assertEqual(dictionary['postclipBoundaryRunAliases'],OPTION)
        self.assertEqual(pathname['postclipBoundaryRunAliases'],OPTION)
        dictionary['postclipBoundaryRunAliases']['limits']['max_parts']=1
        self.assertEqual(cfg,before)
        receipt=document(self.results[True]['adapterReceipt'])
        prepared=document(receipt['preparedConfig'])
        self.assertEqual(prepared['postclipBoundaryRunAliases'],OPTION)
        self.assertFalse(receipt['historicalOwnerManifestsUsed'])
        self.assertFalse(receipt['physicalA97AcceptanceClaim'])

    def test_invalid_explicit_options_fail_before_decode_or_output(self):
        invalid=[None,False,{},dict(OPTION,extra=0),dict(OPTION,policy='UNKNOWN'),
                 dict(OPTION,limits={}),dict(OPTION,limits=dict(OPTION['limits'],max_parts=True)),
                 dict(OPTION,limits=dict(OPTION['limits'],max_parts=0)),
                 dict(OPTION,limits=dict(OPTION['limits'],extra=1)),
                 dict(OPTION,limits=dict(OPTION['limits'],max_parts=2**32))]
        for i,value in enumerate(invalid):
            with self.subTest(value=value):
                cfg=copy.deepcopy(self.config);cfg['postclipBoundaryRunAliases']=value
                out=self.root/('invalid-'+str(i))
                with patch.object(candidate,'decode_ir',side_effect=AssertionError('must not decode'))as decode:
                    with self.assertRaises(ValueError):
                        candidate.export_country(self.package.source,self.package.catalogs,out,config=cfg,prepare_only=True)
                    decode.assert_not_called()
                self.assertFalse(out.exists())

    def test_option_does_not_reauthor_source_bindings_or_geometry(self):
        off=document(self.results[False]['preparedConfig'])
        on=document(self.results[True]['preparedConfig'])
        before=document(off['inputs']['detailInputs'])['cells']
        after=document(on['inputs']['detailInputs'])['cells']
        self.assertEqual([r['cellId']for r in before],[r['cellId']for r in after])
        for a,b in zip(before,after):
            self.assertEqual(document(a['ownerBindings']),document(b['ownerBindings']))
            for name in a['preparedStreams']:
                self.assertEqual(Path(a['preparedStreams'][name]['path']).read_bytes(),
                                 Path(b['preparedStreams'][name]['path']).read_bytes())
            self.assertEqual(Path(a['originalAdditional']['path']).read_bytes(),
                             Path(b['originalAdditional']['path']).read_bytes())

    def bound_detail_scene(self):
        prepared=document(self.results[True]['preparedConfig'])
        details=document(prepared['inputs']['detailInputs'])
        cell=details['cells'][0]
        rows=document(cell['ownerBindings'])['owners']
        raw_render=Path(cell['preparedStreams']['render']['path']).read_bytes()
        query=query_starts(raw_render)
        fi=Path(cell['preparedStreams']['features']['path']).read_bytes()
        records=list(writer_extension.F.iter_unpack(fi))
        dictionary={str(r[3]):r[3] for r in records}
        ro_prefix=render_activation.encode_frame(0,{196608:b'',196609:b''})
        global_render=bytearray(ro_prefix)
        reader=types.SimpleNamespace(read=lambda pin:candidate.read_pin(pin,self.root)[0])
        token=inputs.CURRENT.set(reader)
        try:
            ready,unresolved,receipt=writer_extension.append_source_details(cell,
                source_stream_base=dict(additionalBase=0,additionalOffsetMap={str(q):q for q in query}),
                dictionary_map=dictionary,global_render=global_render,wire=wire,source_sequence=0)
        finally:inputs.CURRENT.reset(token)
        self.assertEqual(unresolved,[])
        self.assertTrue(receipt['sourceQueriesAndSCAMINPreserved'])
        self.assertEqual([o['sourceBinding']for o in ready],rows)
        self.assertEqual(bytes(global_render),ro_prefix+raw_render)
        frames=render_activation.decode_frames(bytes(global_render),query|{0})
        starts=sorted(frames)
        ends={at:starts[i+1]if i+1<len(starts)else len(global_render)for i,at in enumerate(starts)}
        return prepared,rows,ready,bytes(global_render),ends

    def test_real_prepared_carrier_source_binding_is_not_rewritten(self):
        prepared,rows,ready,_,_=self.bound_detail_scene()
        derived=next(o for o in ready if o['sourceBinding']['primarySourceRelationship']['role']=='source_boundary_line')
        sb=derived['sourceBinding'];rel=sb['primarySourceRelationship']
        manifest=document(self.results[True]['irManifest'])
        cell_pin=dict(manifest['cells'][0]);cell_pin['path']=str(Path(self.results[True]['irManifest']['path']).parent/cell_pin['path'])
        original=document(cell_pin)['features'][sb['sourceOriginalOwnerOrdinal']]
        digest=hashlib.sha256(json.dumps(original,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()).hexdigest()
        self.assertEqual(sb['featureSourceDigest'],digest)
        self.assertEqual(sb['sourceId'],original['sourceId'])
        self.assertEqual(sb['sourceFeatureId'],original['sourceFeatureId'])
        self.assertEqual(sb['sourceOriginalOwnerOrdinal'],0)
        self.assertEqual(sb['sourceOwnerOrdinal'],4)
        self.assertEqual(rel['nativeSourceOwnerOrdinal'],4)
        self.assertEqual(derived['uid'],rel['primaryUID']|0x80000000)
        self.assertTrue(rel['queryAttrsAliasPrimary'])
        self.assertEqual(sb['resolution']['sourceRoleDerivation']['role'],'source_boundary_line')
        self.assertTrue(sb['sourceGeometryReceipt']['derivedBoundaryLineCarrier'])
        self.assertTrue(sb['resolution']['selected']['effectiveSourceKeySHA256'])
        self.assertEqual(sb,next(row for row in rows if row['uid']==derived['uid']))

    def test_enabled_cache_to_existing_late_hook_with_actual_ro(self):
        prepared,rows,ready,render,ends=self.bound_detail_scene()
        wrapped=types.SimpleNamespace(events=[])
        quantizer=types.SimpleNamespace(wrap_clipper=lambda *a,**kw:wrapped,
            clip_owner=lambda co,ix,record,**kw:(co,ix,record,dict(invented=True)),
            validate_final_owner=lambda *a,**kw:dict(passed=True,invented=True))
        kwargs=dict(requested_level=5,authoring_last_level=7,
            coverage_service=types.SimpleNamespace(for_owner=lambda *a,**kw:(object(),dict(invented=True))),
            quantizer=quantizer,visibility=lambda *a:dict(eligible=True,policy='INVENTED_EXACT_SOURCE_BINDING_ONLY'))
        default,_=writer_extension.compile_overlay_profile(ready,**kwargs)
        option=prepared['postclipBoundaryRunAliases']
        scene,receipts=writer_extension.compile_overlay_profile(ready,**kwargs,
            postclip_alias=postclip_alias.separate_boundary_runs,postclip_policy=option['policy'],
            postclip_limits=postclip_alias.Limits(**option['limits']),render_stream=render,render_frame_ends=ends)
        derived=next(o for o in scene if o['postclipAliasLedger']['status']=='APPLIED')
        original=next(o for o in default if o['uid']==derived['uid'])
        self.assertEqual([e[0]for e in original['elements']],[16,16])
        self.assertEqual([e[0]for e in derived['elements']],[16,8,16])
        self.assertEqual([e for e in derived['elements']if e[0]==16],original['elements'])
        self.assertEqual(derived['record'],original['record'])
        self.assertEqual(derived['sourceBinding'],original['sourceBinding'])
        self.assertEqual(derived['postclipAliasLedger']['newCOBodies'],0)
        self.assertTrue(any(r['postclipAliasLedger']['status']=='KEEP'for r in receipts))
        self.assertEqual([o['postclipAliasLedger']['actualROFrameSHA256']for o in scene if o['postclipAliasLedger']['status']=='APPLIED'],
            [hashlib.sha256(render[writer_extension.F.unpack(derived['record'])[2]:ends[writer_extension.F.unpack(derived['record'])[2]]]).hexdigest()])
        bad=copy.deepcopy(ready)
        b=next(o for o in bad if o['uid']==derived['uid'])
        b['sourceBinding']['primarySourceRelationship']['nativeSourceOwnerOrdinal']+=1
        with self.assertRaisesRegex(postclip_alias.Refusal,'SOURCE_OWNER_CELL_ORDINAL'):
            writer_extension.compile_overlay_profile(bad,**kwargs,
                postclip_alias=postclip_alias.separate_boundary_runs,postclip_policy=option['policy'],
                postclip_limits=postclip_alias.Limits(**option['limits']),render_stream=render,render_frame_ends=ends)


if __name__=='__main__':unittest.main()

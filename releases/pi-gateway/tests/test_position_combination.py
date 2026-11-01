"""Only synthetic receivers, times and positions; never contact a GPS or vessel."""
from dataclasses import replace
import json
import math
from pathlib import Path
import tempfile
import unittest

from no5_pi_gateway.configuration import ConfigurationError, load_config
from no5_pi_gateway.position_combination import EARTH_RADIUS_METRES
from test_position_sources import NOW, SOURCE, config, document, observation, project

SECOND = 'synthetic.second.GP'


def pair(lon=0.0001, skew=0):
    data = document()
    data['navigation']['position']['values'] = {SECOND: observation({'latitude': 0.0, 'longitude': lon}, NOW-skew, None)}
    data['navigation']['gnss']['methodQuality']['values'] = {SECOND: observation('GNSS Fix', NOW-skew, None)}
    return data


def settings(**changes):
    verified=changes.pop('combination_verified_fix_clock_sources',(SOURCE,SECOND))
    return config(combination_sources=(SOURCE, SECOND),combination_verified_fix_clock_sources=verified, **changes)


def result(data=None, **changes):
    return project(pair() if data is None else data, settings(**changes))['positionSources']['combination']


class CombinationTest(unittest.TestCase):
    def test_equal_weight_midpoint_and_conservative_original_clock(self):
        r = result(pair(skew=700))
        self.assertEqual((r['mode'], r['status'], r['reason']), ('CONSENSUS','LIVE','OK'))
        self.assertAlmostEqual(r['position']['longitudeDegrees'], 0.00005)
        self.assertEqual(r['observedAtEpochMs'], NOW-700)
        self.assertEqual(r['timeSkewMs'],700)
        self.assertEqual({x['sourceId'] for x in r['contributors']},{SOURCE,SECOND})
        self.assertIsNone(r['position']['altitudeMetres'])
        self.assertNotIn('accuracyMetres',r)

    def test_repeat_fetch_does_not_change_fix_time_or_result(self):
        data = pair()
        self.assertEqual(result(data),result(data))
        from no5_pi_gateway.helm import HelmProjector
        r = HelmProjector(settings(), 'synthetic', now=lambda: NOW+5001).project(data).payload['positionSources']['combination']
        self.assertIsNone(r['position']); self.assertEqual(r['reason'],'NO_USABLE_SOURCE')

    def test_dateline_is_not_averaged_to_greenwich(self):
        data = pair(-179.99995)
        data['navigation']['position']['value']['longitude'] = 179.99995
        self.assertAlmostEqual(abs(result(data)['position']['longitudeDegrees']),180.0)

    def test_time_gate_is_inclusive_and_never_selects_a_winner(self):
        self.assertEqual(result(pair(skew=1000))['mode'],'CONSENSUS')
        r = result(pair(skew=1001))
        self.assertEqual(r['reason'],'TIME_MISMATCH'); self.assertIsNone(r['position'])
        self.assertEqual(len(r['contributors']),2)
        self.assertEqual(result(pair(skew=1001),combination_max_time_skew_ms=1500)['mode'],'CONSENSUS')

    def test_distance_gate_and_configured_threshold(self):
        longitude = math.degrees(30.0/EARTH_RADIUS_METRES)
        self.assertEqual(result(pair(longitude*(1-1e-8)))['mode'],'CONSENSUS')
        r = result(pair(longitude*(1+1e-8)))
        self.assertEqual(r['reason'],'SOURCE_DISAGREEMENT'); self.assertIsNone(r['position'])
        self.assertEqual(result(pair(longitude*2),combination_max_separation_metres=100)['mode'],'CONSENSUS')

    def test_missing_quality_is_excluded_and_recovery_rejoins(self):
        data = pair(); del data['navigation']['gnss']['methodQuality']['values']
        r = result(data); self.assertEqual(r['mode'],'SINGLE_SOURCE')
        self.assertEqual(r['contributors'],[{'sourceId':SOURCE,'observedAtEpochMs':NOW}])
        self.assertEqual(r['excluded'][0]['reason'],'NO_VALID_FIX_QUALITY')
        self.assertEqual(result()['mode'],'CONSENSUS')

    def test_invalid_newer_quality_blocks_older_positions(self):
        for value in ['no GPS','Error','Manual input','Simulator mode','Estimated (DR) mode']:
            with self.subTest(value=value):
                data = pair(skew=500)
                data['navigation']['gnss']['methodQuality']['values'][SECOND] = observation(value,NOW,None)
                self.assertEqual(result(data)['mode'],'SINGLE_SOURCE')
        data = pair(skew=500)
        data['navigation']['gnss']['integrity'] = {'values':{SECOND:observation('Unsafe',NOW,None)}}
        self.assertEqual(result(data)['excluded'][0]['reason'],'UNSAFE_FIX')

    def test_quality_requires_fresh_exact_source_within_one_second(self):
        for delta in [1001,6000,-1]:
            data = pair(); data['navigation']['gnss']['methodQuality']['values'][SECOND]['timestamp'] = NOW-delta
            self.assertEqual(result(data)['mode'],'SINGLE_SOURCE')
        data = pair(); data['navigation']['gnss']['methodQuality']['values'][SECOND]['timestamp']=NOW-1000
        self.assertEqual(result(data)['mode'],'CONSENSUS')
        data['navigation']['gnss']['methodQuality']['values'][SECOND]['$source']='wrong'
        self.assertEqual(result(data)['mode'],'SINGLE_SOURCE')

    def test_outage_then_both_lost_does_not_retain_coordinates(self):
        data = pair(skew=5001); r=result(data)
        self.assertEqual(r['mode'],'SINGLE_SOURCE'); self.assertEqual(r['position']['longitudeDegrees'],0.0)
        data['navigation']['position']['timestamp']=NOW-5001
        self.assertEqual(result(data)['mode'],'NONE'); self.assertIsNone(result(data)['position'])
        self.assertEqual(result()['mode'],'CONSENSUS')

    def test_duplicates_and_unconfigured_receiver_are_not_extra_votes(self):
        data=pair(); pos=data['navigation']['position']
        pos['values'][SOURCE]=observation({'latitude':20.0,'longitude':20.0},NOW,None)
        pos['values']['synthetic.unconfigured']=observation({'latitude':40.0,'longitude':40.0},NOW,None)
        r=result(data); self.assertEqual(len(r['contributors']),2)
        self.assertAlmostEqual(r['position']['longitudeDegrees'],0.00005)
        self.assertEqual(project(data,settings())['positionSources']['total'],3)

    def test_catalog_omission_never_silently_becomes_single_source(self):
        data=pair(); pos=data['navigation']['position']
        for i in range(30): pos['values'][f'a.synthetic.{i:02d}']=observation({'latitude':0,'longitude':0},NOW,None)
        r=result(data); self.assertEqual(r['reason'],'SOURCE_CATALOG_TRUNCATED');self.assertIsNone(r['position'])

    def test_configuration_is_explicit_bounded_and_loaded(self):
        for refs in [(SOURCE,), (SOURCE,SOURCE),(' ',SECOND),('x\n',SECOND)]:
            with self.subTest(refs=refs),self.assertRaises(ConfigurationError): config(combination_sources=refs)
        for kwargs in [dict(combination_max_time_skew_ms=True),dict(combination_max_time_skew_ms=5001),
                       dict(combination_max_age_ms=True),dict(combination_max_age_ms=0),dict(combination_max_age_ms=5001),
                       dict(combination_fix_quality_skew_ms=True),dict(combination_fix_quality_skew_ms=-1),dict(combination_fix_quality_skew_ms=1001),
                       dict(combination_max_separation_metres=float('nan')),dict(combination_max_separation_metres=True),
                       dict(gnss_pointer=None,combination_sources=(SOURCE,SECOND))]:
            with self.subTest(kwargs=kwargs),self.assertRaises(ConfigurationError):config(**kwargs)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'config.toml';p.write_text('[helm]\nposition_pointer="/navigation/position"\ngnss_pointer="/navigation/gnss"\ncombination_sources=["synthetic.a","synthetic.b"]\ncombination_max_age_ms=4000\ncombination_fix_quality_skew_ms=500\ncombination_max_time_skew_ms=750\ncombination_max_separation_metres=20\n')
            c=load_config(p).helm
        self.assertEqual(c.combination_sources,('synthetic.a','synthetic.b'));self.assertEqual(c.combination_max_time_skew_ms,750)
        self.assertEqual(c.combination_max_age_ms,4000);self.assertEqual(c.combination_fix_quality_skew_ms,500)

    def test_configured_age_and_quality_gates_can_be_tightened(self):
        self.assertEqual(result(pair(skew=2000),combination_max_age_ms=1000)['mode'],'SINGLE_SOURCE')
        data=pair();data['navigation']['gnss']['methodQuality']['values'][SECOND]['timestamp']=NOW-501
        self.assertEqual(result(data,combination_fix_quality_skew_ms=500)['mode'],'SINGLE_SOURCE')
        data['navigation']['gnss']['methodQuality']['values'][SECOND]['timestamp']=NOW-500
        self.assertEqual(result(data,combination_fix_quality_skew_ms=500)['mode'],'CONSENSUS')
        data['navigation']['gnss']['methodQuality']['values'][SECOND]['timestamp']=NOW-1001
        data['navigation']['position']['values'][SECOND]['timestamp']=NOW-1000
        self.assertEqual(result(data,combination_max_age_ms=1000)['excluded'][0]['reason'],'NO_FRESH_FIX_QUALITY')

    def test_unverified_quality_clock_never_contributes_even_with_fresh_fields(self):
        r=result(combination_verified_fix_clock_sources=(SOURCE,))
        self.assertEqual(r['mode'],'SINGLE_SOURCE');self.assertEqual(r['excluded'][0]['reason'],'UNVERIFIED_FIX_CLOCK')
        self.assertEqual(result(combination_verified_fix_clock_sources=())['mode'],'NONE')
        for refs in [(SOURCE,SOURCE),(SOURCE,'not.configured'),([SOURCE],)]:
            with self.subTest(refs=refs),self.assertRaises(ConfigurationError):settings(combination_verified_fix_clock_sources=refs)

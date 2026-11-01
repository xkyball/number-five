import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator
from simulator.server import Simulation, ControlError
from simulator.tests.test_simulator import Clock, issue, control_command
from simulator.config import validate_settings

class SceneWeatherTests(unittest.TestCase):
    def setUp(self):
        self.clock=Clock()
        self.sim=Simulation(self.clock.monotonic,self.clock.wall)
    def test_scene_time_is_separate_from_publication_pause_and_speed(self):
        issue(self.sim,'apply',environmentMode='simulated',sceneEpochMs=946684800000,timeScale=50)
        issue(self.sim,'start');self.clock.advance(2);self.sim.sample();issue(self.sim,'pause')
        voyage=self.sim.engine.state.elapsed
        before=self.sim.get('weather')[1]
        self.clock.advance(10);self.sim.sample();after=self.sim.get('weather')[1]
        self.assertEqual(voyage,self.sim.engine.state.elapsed)
        self.assertEqual(946684800000,after['scene']['utcEpochMs'])
        self.assertEqual(10000,after['timestampEpochMs']-before['timestampEpochMs'])
        self.assertTrue(after['simulated']);self.assertEqual('SIMULATED',after['provider']['kind'])
        self.assertFalse(self.sim.weather.enabled)
    def test_live_return_clears_synthetic_cache_and_never_falls_back(self):
        issue(self.sim,'apply',environmentMode='simulated')
        self.assertEqual(200,self.sim.get('weather')[0])
        issue(self.sim,'apply',environmentMode='live',weatherEnabled=False)
        code,p=self.sim.get('weather');self.assertEqual(503,code);self.assertNotIn('scene',p)
        self.assertNotIn('weather',self.sim.cached)
    def test_stale_freezes_timestamp_failure_is_explicit_and_recovery_refreshes(self):
        issue(self.sim,'apply',environmentMode='simulated')
        old=self.sim.get('weather')[1]['timestampEpochMs']
        issue(self.sim,'apply',weatherFault='stale');self.clock.advance(35);self.sim.sample()
        p=self.sim.get('weather')[1];self.assertEqual(old,p['timestampEpochMs']);self.assertEqual('STALE',p['status'])
        issue(self.sim,'apply',weatherFault='failed');self.assertEqual(503,self.sim.get('weather')[0])
        issue(self.sim,'apply',weatherFault='none');self.assertGreater(self.sim.get('weather')[1]['timestampEpochMs'],old)
    def test_scene_apply_is_revisioned_idempotent_and_rejects_invalid_atomically(self):
        command=control_command(self.sim,'apply',dict(self.sim.settings,environmentMode='simulated'))
        a=self.sim.command(command);b=self.sim.command(command)
        self.assertEqual(a['revision'],b['revision']);self.assertTrue(b['replayed'])
        stale=control_command(self.sim,'apply',dict(self.sim.settings,sceneWindKn=9),revision=0)
        with self.assertRaises(ControlError):self.sim.command(stale)
        for key,bad in [('sceneEpochMs',True),('sceneEpochMs',0),('sceneWindKn',float('nan')),('sceneVisibilityMeters',0),('sceneWindFromDegrees',360),('environmentMode','REAL')]:
            before=copy.deepcopy(self.sim.settings)
            with self.assertRaises(ValueError):issue(self.sim,'apply',**{key:bad})
            self.assertEqual(before,self.sim.settings)
    def test_reset_keeps_applied_scene_and_restart_defaults_live(self):
        issue(self.sim,'apply',environmentMode='simulated',sceneCloudPercent=100)
        issue(self.sim,'reset');self.assertEqual(100,self.sim.get('weather')[1]['values']['cloudCoverPercent'])
        fresh=Simulation(self.clock.monotonic,self.clock.wall)
        self.assertEqual('live',fresh.settings['environmentMode'])
    def test_real_wire_schema_and_fixture(self):
        root=Path(__file__).resolve().parents[2]
        schema=json.loads((root/'contracts/no5-gateway-v1.schema.json').read_text())
        v=Draft202012Validator(schema)
        issue(self.sim,'apply',environmentMode='simulated')
        v.validate(self.sim.get('weather')[1])
        fixture=json.loads((root/'simulator/tests/fixtures/scene-weather-v1.json').read_text());v.validate(fixture)
        fixture['simulated']=False;self.assertFalse(v.is_valid(fixture))
        control=json.loads((root/'contracts/no5-simulator-control-v1.schema.json').read_text())
        # Settings schema alone avoids coupling command schema to response envelopes.
        Draft202012Validator(control['$defs']['settings']).validate(self.sim.settings)

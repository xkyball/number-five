import unittest
from simulator.sonar_profiles import generate_profile,PROFILE_DEFAULTS
from simulator.sonar import SonarSimulator,decode_ping
from simulator.server import Simulation
from simulator.tests.test_simulator import Clock

class ProfileTests(unittest.TestCase):
    def profile(self,depth=12,**changes):
        return generate_profile(depth,0,0,5,53,dict(PROFILE_DEFAULTS,targets=False,**changes))
    def test_repeatable_bounded_profiles_and_truth_separation(self):
        for material in ('mixed','sand','mud','rock','vegetation'):
            a,t,d=self.profile(bottomModel=material);b,_,_=self.profile(bottomModel=material)
            self.assertEqual(a,b);self.assertEqual(1024,len(a));self.assertTrue(all(type(x)is int and 0<=x<=65535 for x in a))
            self.assertEqual('UNKNOWN',d['classification']);self.assertNotIn('model',d)
            self.assertEqual(material,t['model'])
    def test_second_echo_is_not_clamped_to_range_edge(self):
        samples,truth,_=self.profile(depth=20)
        self.assertFalse(truth['secondEchoInWindow']);self.assertEqual(40,truth['secondEchoRangeM'])
        self.assertLess(max(samples[990:]),2000)
        samples,truth,_=self.profile(depth=8)
        self.assertTrue(truth['secondEchoInWindow']);self.assertGreater(max(samples[520:545]),2000)
    def test_pulse_beam_vegetation_and_receiver_gain_change_envelope(self):
        _,short,_=self.profile(pulseUs=50,beamDegrees=5)
        _,wide,_=self.profile(pulseUs=600,beamDegrees=40)
        self.assertGreater(wide['echoSigmaM'],short['echoSigmaM'])
        quiet,_,_=self.profile();gain,_,d=self.profile(receiverGainDb=18)
        self.assertGreater(max(gain),max(quiet));self.assertGreater(d['clippedBins'],0)
        vegetation,truth,_=self.profile(bottomModel='vegetation')
        self.assertGreater(truth['vegetationHeightM'],0)
        self.assertGreater(sum(vegetation[350:390]),sum(quiet[350:390]))
    def test_config_changes_reset_generation_without_changing_helm_depth(self):
        clock=Clock();sim=Simulation(clock.monotonic,clock.wall);sonar=SonarSimulator(sim)
        sonar.sample();old=sonar.generation
        sonar.configure({'bottomModel':'mud','pulseUs':400});sonar.sample()
        self.assertGreater(sonar.generation,old)
        packet,_=decode_ping(sonar.latest)
        self.assertAlmostEqual(packet['bottom']['bin']*.03,sim.engine.helm_values()['depth'])
        self.assertNotIn('groundTruth',packet);self.assertNotIn('classification',packet)
        self.assertEqual('mud',sonar.last_ground_truth['model'])
        for changes in ({'pulseUs':True},{'beamDegrees':0},{'noiseModel':'invented'},{'receiverGainDb':float('inf')}):
            with self.assertRaises(ValueError):sonar.configure(changes)

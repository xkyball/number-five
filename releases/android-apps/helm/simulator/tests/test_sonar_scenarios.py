import math
import unittest
from simulator.sonar_scenarios import scene,bottom,SCENARIOS
from simulator.sonar_profiles import generate_profile,PROFILE_DEFAULTS
from simulator.sonar import SonarSimulator,decode_ping
from simulator.server import Simulation
from simulator.tests.test_simulator import Clock

class SonarScenarioTests(unittest.TestCase):
    def test_scenes_generate_repeatable_raw_profiles_without_peak_normalization(self):
        peaks=[]
        for name in SCENARIOS[1:]:
            settings=dict(PROFILE_DEFAULTS,scenario=name,targets=True)
            first=generate_profile(12,0,0,3,53,settings)
            self.assertEqual(first,generate_profile(12,0,0,3,53,settings))
            samples,truth,diagnostics=first
            self.assertEqual(1024,len(samples));self.assertTrue(all(type(x)is int and 0<=x<=65535 for x in samples))
            self.assertEqual(name,truth['scenario']);self.assertEqual('UNKNOWN',diagnostics['classification'])
            self.assertNotIn('scenario',diagnostics);peaks.append(max(samples))
        self.assertTrue(all(x<65535 for x in peaks));self.assertGreater(len(set(peaks)),1)

    def test_single_fish_arch_is_slant_range_and_beam_envelope(self):
        # Cell zero target has z=6m and y=0. Its moving x follows the stated test model.
        crossings=[]
        for i in range(401):
            t=i/100
            _,fish,_,_=scene('sand_fish',t,20,True)
            if fish:
                r,a=fish[0];x=t*1.6;tx=7-3*.72+.35*math.sin(t*.25)
                self.assertAlmostEqual(math.hypot(6,x-tx),r,places=10)
                crossings.append((r,a))
        self.assertGreater(len(crossings),10)
        middle=min(range(len(crossings)),key=lambda i:crossings[i][0])
        self.assertGreater(middle,0);self.assertLess(middle,len(crossings)-1)
        self.assertGreater(crossings[0][0],crossings[middle][0]);self.assertGreater(crossings[-1][0],crossings[middle][0])
        self.assertGreater(crossings[middle][1],crossings[0][1])
        self.assertEqual([],scene('sand_fish',3,20,False)[1])
        narrow=sum(bool(scene('sand_fish',i/100,5,True)[1]) for i in range(401))
        self.assertLess(narrow,len(crossings))

    def test_rocks_change_range_and_return_spread_not_only_color(self):
        depths=[bottom('rock_weed',x) for x in range(18)]
        self.assertGreater(max(depths)-min(depths),1)
        depth,_,returns,_=scene('rock_weed',3,40,True)
        self.assertEqual(5,len(returns));self.assertGreater(max(r for r,_ in returns)-min(r for r,_ in returns),.2)
        profile,truth,_=generate_profile(12,0,0,3,53,dict(PROFILE_DEFAULTS,scenario='rock_weed',targets=True))
        self.assertAlmostEqual(depth,truth['bottomDepthM'])
        ground=round(depth/.03)
        self.assertGreater(sum(profile[max(0,ground-50):ground-10]),40000)

    def test_noise_second_echo_and_unambiguous_range(self):
        profile,truth,_=generate_profile(12,0,0,0,53,dict(PROFILE_DEFAULTS,scenario='noise_second',targets=True))
        center=round(truth['secondEchoRangeM']/.03)
        self.assertTrue(truth['secondEchoInWindow']);self.assertGreater(max(profile[center-4:center+5]),3000)
        self.assertLess(1023*.03,1500/(2*20))

    def test_scene_clock_is_acquisition_time_and_rejects_invalid_selection(self):
        clock=Clock();sim=Simulation(clock.monotonic,clock.wall);sonar=SonarSimulator(sim)
        sonar.configure({'scenario':'sand_fish','targets':True});sonar.sample()
        start=sonar.last_ground_truth['transectEastM']
        clock.advance(2);sonar.sample()
        self.assertAlmostEqual(start+3.2,sonar.last_ground_truth['transectEastM'])
        header,_=decode_ping(sonar.latest)
        self.assertNotIn('groundTruth',header);self.assertNotIn('transectEastM',header)
        before=sonar.settings.copy()
        with self.assertRaises(ValueError):sonar.configure({'scenario':'invented'})
        self.assertEqual(before,sonar.settings)

    def test_test_transect_samples_are_independent_of_voyage_time_factor(self):
        runs=[]
        for scale in (1,20):
            clock=Clock();sim=Simulation(clock.monotonic,clock.wall,time_scale=scale,running=True)
            sonar=SonarSimulator(sim);sonar.configure({'scenario':'school_edge','targets':True})
            sonar.sample();clock.advance(2);sonar.sample()
            runs.append((decode_ping(sonar.latest)[1],sonar.last_ground_truth))
        self.assertEqual(runs[0],runs[1])

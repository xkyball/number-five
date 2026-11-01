from concurrent.futures import ThreadPoolExecutor
import copy
import json
import http.client
import math
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from simulator.engine import EARTH_RADIUS_M, INITIAL_FUEL_L, KNOT, SIMULATION_EPOCH_MS, VoyageEngine
from simulator.server import Simulation, SimulationServer


class Clock:
    def __init__(self):
        self.seconds = 0.0
        self.epoch = SIMULATION_EPOCH_MS
        self.run_number = -1

    def run_id(self):
        self.run_number += 1
        return "fixture-run-" + str(self.run_number)

    def monotonic(self):
        return self.seconds

    def wall(self):
        return self.epoch + round(self.seconds * 1000)

    def advance(self, seconds):
        self.seconds += seconds


def bearing_delta(first, second):
    return (second - first + 180) % 360 - 180


def haversine(a, b):
    lat1, lat2 = map(math.radians, (a['latitudeDegrees'], b['latitudeDegrees']))
    dlat = lat2 - lat1
    dlon = math.radians(b['longitudeDegrees'] - a['longitudeDegrees'])
    return EARTH_RADIUS_M * 2 * math.asin(math.sqrt(math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2))


class VoyageTests(unittest.TestCase):
    def test_entire_voyage_stop_launch_cruise_figure8_decel_stop(self):
        engine = VoyageEngine()
        phases, maximum_speed, turns = [], 0.0, []
        for _ in range(600):
            engine.advance(.5)
            state = engine.state
            if not phases or phases[-1] != state.phase:
                phases.append(state.phase)
            maximum_speed = max(maximum_speed, state.speed_mps / KNOT)
            self.assertTrue(0 <= state.port_rpm <= 7000)
            self.assertTrue(0 <= state.starboard_rpm <= 7000)
            self.assertTrue(0 <= state.port_fuel_l <= INITIAL_FUEL_L / 2)
            if state.phase == 'FIGURE_EIGHT':
                turns.append(state.turn_rate_degrees_s)
            if state.phase == 'FULL_THROTTLE_LAUNCH':
                self.assertEqual(1.0, state.throttle)
        self.assertEqual(['STOPPED', 'FULL_THROTTLE_LAUNCH', 'CRUISE', 'FIGURE_EIGHT', 'DECELERATE', 'COMPLETE'], phases)
        self.assertGreater(maximum_speed, 28)
        self.assertLess(maximum_speed, 30.1)
        self.assertGreater(max(turns), 1)
        self.assertLess(min(turns), -1)
        self.assertEqual(0, engine.state.speed_mps)
        self.assertEqual(0, engine.state.port_rpm)
        self.assertEqual('N', engine.state.gear)
        self.assertIsNone(engine.helm_values()['courseOverGroundDegrees'])
        stopped_position = (engine.state.east_m, engine.state.north_m)
        fuel = engine.extras()['fuelUsedLiters']
        engine.advance(20)
        self.assertEqual(stopped_position, (engine.state.east_m, engine.state.north_m))
        self.assertEqual(fuel, engine.extras()['fuelUsedLiters'])

    def test_fixed_steps_make_poll_frequency_irrelevant(self):
        regular, irregular = VoyageEngine(), VoyageEngine()
        regular.advance(120)
        for chunk in [0.01, 0.03, .11, 29.25, 10.6, 80]:
            irregular.advance(chunk)
        self.assertEqual(regular.state, irregular.state)
        self.assertEqual(regular.extras(), irregular.extras())

    def test_trip_distance_matches_integrated_speed_and_geodetic_displacements(self):
        engine = VoyageEngine()
        speed_integral, gps_distance = 0.0, 0.0
        before = engine.helm_values()
        previous_speed = 0.0
        for _ in range(5200):
            engine.advance(engine.DT)
            values = engine.helm_values()
            speed_integral += (previous_speed + values['speed'] * KNOT) * engine.DT / 2
            gps_distance += haversine(before, values)
            before, previous_speed = values, values['speed'] * KNOT
        self.assertAlmostEqual(engine.state.distance_m, speed_integral, places=8)
        self.assertAlmostEqual(engine.state.distance_m, gps_distance, delta=.08)
        self.assertAlmostEqual(engine.extras()['tripDistanceNm'] * 1852, speed_integral, places=8)

    def test_true_heading_matches_trajectory_derivative_and_turn_rate(self):
        engine = VoyageEngine()
        max_tangent_error, max_turn_error = 0, 0
        for _ in range(4800):
            previous = copy.copy(engine.state)
            engine.advance(engine.DT)
            current = engine.state
            if current.phase != 'FIGURE_EIGHT' or previous.phase != current.phase:
                continue
            de, dn = current.east_m - previous.east_m, current.north_m - previous.north_m
            tangent = math.degrees(math.atan2(de, dn)) % 360
            midpoint = (previous.heading_degrees + bearing_delta(previous.heading_degrees, current.heading_degrees) / 2) % 360
            max_tangent_error = max(max_tangent_error, abs(bearing_delta(midpoint, tangent)))
            calculated_rate = bearing_delta(previous.heading_degrees, current.heading_degrees) / engine.DT
            expected_rate = (previous.turn_rate_degrees_s + current.turn_rate_degrees_s) / 2
            max_turn_error = max(max_turn_error, abs(calculated_rate - expected_rate))
        self.assertLess(max_tangent_error, .03)
        self.assertLess(max_turn_error, .06)

    def test_figure8_closes_and_has_continuous_tangents(self):
        engine = VoyageEngine()
        start = engine.route.at(0)
        end = engine.route.at(engine.route.length)
        self.assertAlmostEqual(start[0], end[0], places=8)
        self.assertAlmostEqual(start[1], end[1], places=8)
        self.assertAlmostEqual(start[2], end[2], places=8)
        crossing = engine.route.at(engine.route.length / 2)
        self.assertAlmostEqual(0, crossing[0], delta=.001)
        self.assertAlmostEqual(0, crossing[1], delta=.001)
        self.assertGreater(abs(bearing_delta(start[2], crossing[2])), 60)

    def test_fuel_flow_integral_and_trip_efficiency(self):
        engine = VoyageEngine()
        consumed, previous_flow = 0, 0
        for _ in range(5200):
            engine.advance(engine.DT)
            values = engine.helm_values()
            flow = values['totalFuelFlowLph']
            self.assertAlmostEqual(flow, values['portFuelFlowLph'] + values['starboardFuelFlowLph'])
            consumed += (previous_flow + flow) * engine.DT / 7200
            previous_flow = flow
        extras = engine.extras()
        self.assertAlmostEqual(consumed, extras['fuelUsedLiters'], places=8)
        self.assertAlmostEqual(INITIAL_FUEL_L, engine.helm_values()['fuelRemainingLiters'] + consumed, places=8)
        self.assertAlmostEqual(extras['efficiencyNmPerLiter'], extras['tripDistanceNm'] / consumed)

    def test_synthetic_depth_follows_position_and_holds_while_stationary(self):
        engine = VoyageEngine()
        initial = engine.helm_values()['depth']
        engine.advance(6)
        self.assertEqual(initial, engine.helm_values()['depth'])
        depths = []
        for _ in range(2600):
            before = engine.helm_values()['depth']
            engine.advance(.1)
            depth = engine.helm_values()['depth']
            self.assertEqual(VoyageEngine.synthetic_depth(engine.state.east_m, engine.state.north_m), depth)
            self.assertTrue(6.5 <= depth <= 17.5)
            self.assertLess(abs(depth - before), .1)
            depths.append(depth)
        self.assertGreater(max(depths) - min(depths), 4)
        settled = engine.helm_values()['depth']
        engine.advance(60)
        self.assertEqual(settled, engine.helm_values()['depth'])

    def test_trim_affects_speed_flow_and_per_tank_consumption(self):
        optimal, raised = VoyageEngine(), VoyageEngine()
        raised.set_trim(100, 100)
        optimal.advance(50)
        raised.advance(50)
        self.assertGreater(optimal.state.speed_mps, raised.state.speed_mps)
        self.assertLess(optimal.state.port_flow_lph, raised.state.port_flow_lph)
        self.assertGreater(optimal.extras()['efficiencyNmPerLiter'], raised.extras()['efficiencyNmPerLiter'])
        unequal = VoyageEngine()
        unequal.set_trim(port=100)
        unequal.advance(50)
        self.assertLess(unequal.state.port_fuel_l, unequal.state.starboard_fuel_l)
        self.assertEqual(35, unequal.starboard_trim)

    def test_reset_reproduces_initial_state_and_nulls_are_explicit(self):
        engine = VoyageEngine()
        original = engine.helm_values()
        engine.advance(200)
        engine.set_trim(90, 45)
        engine.reset()
        self.assertEqual(original, engine.helm_values())
        self.assertEqual(12.0, original['depth'])
        self.assertIsNone(original['waterTemp'])
        self.assertEqual(0, original['speed'])
        self.assertEqual(0, original['portRpm'])

    def test_invalid_inputs_do_not_mutate_state(self):
        engine = VoyageEngine()
        for value in (float('nan'), float('inf'), -1, 101, True, '35'):
            with self.assertRaises(ValueError):
                engine.set_trim(port=value, starboard=80)
            self.assertEqual(35, engine.starboard_trim)
        for value in (-1, float('inf'), True, 86401):
            with self.assertRaises(ValueError):
                engine.advance(value)
        self.assertEqual(0, engine.state.elapsed)

    def test_long_stationary_gap_preserves_trip_and_cools_engines(self):
        engine = VoyageEngine()
        engine.advance(300)
        completed = engine.extras()
        position = engine.helm_values()['latitudeDegrees'], engine.helm_values()['longitudeDegrees']
        engine.advance(86400)
        self.assertEqual(completed, engine.extras())
        self.assertEqual(position, (engine.helm_values()['latitudeDegrees'], engine.helm_values()['longitudeDegrees']))
        self.assertAlmostEqual(19, engine.helm_values()['portCoolantC'])
        self.assertEqual(86700, engine.state.elapsed)

    def test_many_small_clock_updates_do_not_lose_a_simulation_step(self):
        batched, polled = VoyageEngine(), VoyageEngine()
        batched.advance(1000)
        for _ in range(10000):
            polled.advance(.1)
        self.assertEqual(1000, polled.state.elapsed)
        self.assertEqual(batched.extras(), polled.extras())
        self.assertAlmostEqual(batched.state.port_coolant_c, polled.state.port_coolant_c, places=9)


class DisabledWeather:
    def configure(self,enabled,fault='none'):self.enabled,self.fault=enabled,fault
    def get(self):return 503,{'status':'UNAVAILABLE','error':'Test provider deliberately absent','simulated':False}
    def status(self):return {'status':'UNAVAILABLE','enabled':self.enabled,'error':'Test provider deliberately absent'}
    def start(self):pass
    def close(self):pass


def control_command(sim,action,settings=None,request_id=None,revision=None):
    import uuid
    command={'version':1,'serverInstanceId':sim.server_instance_id,'expectedRevision':sim.revision if revision is None else revision,
             'requestId':request_id or uuid.uuid4().hex,'action':action}
    if settings is not None:command['settings']=settings
    return command


def issue(sim,action,**changes):
    return sim.command(control_command(sim,action,dict(sim.settings,**changes) if action=='apply' else None))


class SimulationTests(unittest.TestCase):
    def setUp(self):
        self.clock=Clock()
        self.sim=Simulation(self.clock.monotonic,self.clock.wall,run_id_factory=self.clock.run_id,
                            server_id_factory=lambda:'fixture-instance',weather=DisabledWeather())
    def advance(self,seconds):self.clock.advance(seconds);self.sim.sample()
    def test_initial_ready_is_separate_from_standing_phase(self):
        self.advance(10)
        self.assertEqual('READY',self.sim.run_status);self.assertEqual('STOPPED',self.sim.engine.state.phase)
        self.assertEqual(0,self.sim.engine.state.elapsed);self.assertFalse(self.sim.running)
        issue(self.sim,'start');self.advance(4)
        self.assertEqual('RUNNING',self.sim.run_status);self.assertEqual('STOPPED',self.sim.engine.state.phase)
        self.assertAlmostEqual(4,self.sim.control_state()['timeline']['phaseRemainingSeconds'])
        self.advance(5);self.assertEqual('FULL_THROTTLE_LAUNCH',self.sim.engine.state.phase)
    def test_pause_resume_hold_physics_but_publish_fresh_wall_time(self):
        issue(self.sim,'start');self.advance(20);issue(self.sim,'pause')
        before=self.sim.get('helm')[1];self.advance(30);after=self.sim.get('helm')[1]
        self.assertEqual(before['values'],after['values']);self.assertEqual(before['simulation']['elapsedSeconds'],after['simulation']['elapsedSeconds'])
        self.assertEqual(30000,after['timestampEpochMs']-before['timestampEpochMs'])
        self.assertFalse(after['running']);issue(self.sim,'resume');self.advance(2)
        self.assertEqual(22,self.sim.engine.state.elapsed)
    def test_stop_zeroes_motion_preserves_trip_and_start_is_new_run(self):
        issue(self.sim,'start');self.advance(50);trip=self.sim.engine.extras();run=self.sim.run_id
        issue(self.sim,'stop');self.advance(10)
        self.assertEqual(trip['tripDistanceNm'],self.sim.engine.extras()['tripDistanceNm'])
        self.assertEqual(0,self.sim.engine.state.speed_mps);self.assertEqual(0,self.sim.engine.state.port_rpm)
        issue(self.sim,'start');self.assertNotEqual(run,self.sim.run_id);self.assertEqual(0,self.sim.engine.state.elapsed)
    def test_reset_retains_settings_returns_ready_and_changes_continuity(self):
        issue(self.sim,'apply',timeScale=10,portTrimPercent=80,portInitialFuelLiters=120)
        issue(self.sim,'start');self.advance(5);run=self.sim.run_id;issue(self.sim,'reset')
        self.assertNotEqual(run,self.sim.run_id);self.assertEqual('READY',self.sim.run_status)
        self.assertEqual(10,self.sim.time_scale);self.assertEqual(80,self.sim.engine.port_trim)
        self.assertEqual(120,self.sim.engine.state.port_fuel_l);self.assertEqual(0,self.sim.engine.extras()['fuelUsedLiters'])
        self.assertEqual(0,self.sim.engine.state.elapsed)
    def test_complete_stops_run_clock_and_never_reports_running(self):
        issue(self.sim,'start');self.advance(300);ended=self.sim.get('helm')[1]
        self.assertEqual('COMPLETED',ended['simulation']['runStatus']);self.assertFalse(ended['running'])
        self.assertEqual('COMPLETE',ended['simulation']['phase']);self.advance(100)
        self.assertEqual(ended['simulation']['elapsedSeconds'],self.sim.engine.state.elapsed)
        self.assertEqual(0,self.sim.get('helm')[1]['values']['speed'])
    def test_manual_speed_heading_and_fuel_are_consistent(self):
        issue(self.sim,'apply',mode='manual',manualSpeedKn=10,manualHeadingDegrees=90,portInitialFuelLiters=100,starboardInitialFuelLiters=150)
        issue(self.sim,'start');self.advance(30)
        v=self.sim.get('helm')[1]['values'];self.assertAlmostEqual(10,v['speed'],delta=.1)
        self.assertAlmostEqual(90,v['heading']);self.assertGreater(self.sim.engine.state.east_m,0)
        self.assertAlmostEqual(0,self.sim.engine.state.north_m,delta=.001)
        self.assertAlmostEqual(250,v['fuelRemainingLiters']+self.sim.engine.extras()['fuelUsedLiters'])
        before=self.sim.engine.state.heading_degrees;issue(self.sim,'apply',manualHeadingDegrees=180);self.advance(.05)
        self.assertLessEqual(abs(self.sim.engine.state.heading_degrees-before),.61)
        self.assertIsNone(self.sim.control_state()['timeline']['remainingSeconds'])
    def test_time_scale_change_is_not_retroactive(self):
        issue(self.sim,'start');self.clock.advance(5);issue(self.sim,'apply',timeScale=10);self.advance(3)
        self.assertEqual(35,self.sim.engine.state.elapsed)
        self.assertEqual(self.clock.wall(),self.sim.get('helm')[1]['timestampEpochMs'])
    def test_cold_settings_require_stop_and_all_fields_validate_before_mutation(self):
        from simulator.server import ControlError
        issue(self.sim,'start');before=copy.deepcopy(self.sim.settings);run=self.sim.run_id;revision=self.sim.revision
        for patch in ({'mode':'manual'},{'routeScale':2},{'portInitialFuelLiters':20},{'clockStartEpochMs':1791000000000}):
            with self.assertRaises(ControlError):issue(self.sim,'apply',**patch)
            self.assertEqual(before,self.sim.settings);self.assertEqual(run,self.sim.run_id);self.assertEqual(revision,self.sim.revision)
        for patch in ({'portTrimPercent':40,'starboardTrimPercent':101},{'timeScale':True},{'routeScale':float('nan')},{'weatherEnabled':'yes'}):
            with self.assertRaises(ValueError):issue(self.sim,'apply',**patch)
            self.assertEqual(before,self.sim.settings)
    def test_receipt_idempotency_reuse_and_restart_protection(self):
        from simulator.server import ControlError
        command=control_command(self.sim,'start');ack=self.sim.command(command);self.advance(10)
        replay=self.sim.command(command);self.assertTrue(replay['replayed']);self.assertEqual(ack['revision'],replay['revision'])
        self.assertEqual(10,self.sim.engine.state.elapsed);self.assertEqual(ack['requestId'],self.sim.control_state(command['requestId'])['receipt']['requestId'])
        with self.assertRaises(ControlError):self.sim.command(dict(command,action='pause'))
        fresh=control_command(self.sim,'pause');fresh['serverInstanceId']='obsolete'
        with self.assertRaises(ControlError):self.sim.command(fresh)
        self.assertTrue(self.sim.running)
    def test_two_clients_cannot_overwrite_the_same_revision(self):
        commands=[control_command(self.sim,'apply',dict(self.sim.settings,portTrimPercent=n)) for n in (20,80)]
        def attempt(command):
            try:self.sim.command(command);return 'APPLIED'
            except ValueError:return 'CONFLICT'
        with ThreadPoolExecutor(max_workers=2) as executor:results=list(executor.map(attempt,commands))
        self.assertEqual(['APPLIED','CONFLICT'],sorted(results));self.assertEqual(1,self.sim.revision)
    def test_reads_never_publish_and_helm_stale_keeps_old_observation(self):
        initial=self.sim.get('helm')[1];self.clock.advance(10)
        for _ in range(20):self.assertEqual(initial,self.sim.get('helm')[1])
        issue(self.sim,'apply',helmFault='stale');before=self.sim.get('helm')[1];issue(self.sim,'start');self.advance(10)
        after=self.sim.get('helm')[1];self.assertEqual('STALE',after['status']);self.assertEqual(before['timestampEpochMs'],after['timestampEpochMs'])
        self.assertEqual(before['values'],after['values']);issue(self.sim,'apply',helmFault='none')
        self.assertEqual('LIVE',self.sim.get('helm')[1]['status']);self.assertGreater(self.sim.get('helm')[1]['values']['speed'],0)
    def test_invalid_lifecycle_and_unrevisioned_commands_are_rejected(self):
        for command in ({'command':'start'},control_command(self.sim,'resume'),dict(control_command(self.sim,'start'),version=True)):
            with self.assertRaises(ValueError):self.sim.command(command)
        self.assertEqual('READY',self.sim.run_status);self.assertEqual(0,self.sim.revision)
    def test_canonical_helm_and_status_validate(self):
        try:import jsonschema
        except ImportError:self.skipTest('Optional jsonschema is not installed')
        schema=json.loads((Path(__file__).parents[2]/'contracts/no5-gateway-v1.schema.json').read_text())
        for kind in ('helm','status'):jsonschema.validate(self.sim.get(kind)[1],schema)
    def test_empty_port_tank_stops_only_port_engine(self):
        issue(self.sim,'apply',mode='manual',portInitialFuelLiters=0);issue(self.sim,'start');self.advance(20)
        v=self.sim.get('helm')[1]['values'];self.assertEqual(0,v['portRpm']);self.assertEqual(0,v['portFuelFlowLph']);self.assertGreater(v['starboardRpm'],0)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.sim=Simulation(weather=DisabledWeather())
        self.server=SimulationServer(('127.0.0.1',0),self.sim)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base='http://127.0.0.1:'+str(self.server.server_address[1])
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join(3)
    def request(self,path,body=None,origin=None):
        headers={'Content-Type':'application/json'}
        if origin:headers['Origin']=origin
        data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
        try:response=urlopen(Request(self.base+path,data=data,headers=headers),timeout=3)
        except HTTPError as error:response=error
        with response:return response.status,response.headers,response.read()
    def test_real_routes_and_control_lifecycle(self):
        for path in ('/','/v1/helm','/v1/audio','/v1/sonar','/v1/status','/v1/simulation'):
            code,headers,body=self.request(path);self.assertEqual(200,code);self.assertEqual('no-store',headers['Cache-Control'])
        self.assertEqual(503,self.request('/v1/weather')[0]);self.assertEqual(404,self.request('/v1/fake')[0])
        command=control_command(self.sim,'start');code,_,body=self.request('/v1/simulation',command)
        self.assertEqual(200,code);ack=json.loads(body);self.assertEqual('RUNNING',ack['state']['runStatus'])
        receipt=json.loads(self.request('/v1/simulation?requestId='+command['requestId'])[2])['receipt']
        self.assertEqual('APPLIED',receipt['outcome'])
    def test_conflict_returns_current_state_no_replay(self):
        command=control_command(self.sim,'start');issue(self.sim,'apply',timeScale=2)
        code,_,body=self.request('/v1/simulation',command);self.assertEqual(409,code)
        self.assertEqual('REVISION_CONFLICT',json.loads(body)['code']);self.assertFalse(self.sim.running)
    def test_json_duplicates_nan_origin_and_legacy_rejected(self):
        for body in (b'{"action":"start","action":"stop"}',b'[]',b'{"x":NaN}',b'x'*9000):self.assertEqual(400,self.request('/v1/simulation',body)[0])
        self.assertEqual(400,self.request('/v1/simulation',{'command':'start'})[0])
        self.assertEqual(403,self.request('/v1/simulation',control_command(self.sim,'start'),'http://untrusted.example')[0])
    def test_persistent_socket_and_fragmented_post(self):
        connection=http.client.HTTPConnection(*self.server.server_address,timeout=3)
        try:
            connection.request('GET','/v1/helm');r=connection.getresponse();r.read();sock=connection.sock
            for _ in range(5):connection.request('GET','/v1/helm');r=connection.getresponse();r.read();self.assertIs(sock,connection.sock)
            body=json.dumps(control_command(self.sim,'start')).encode();connection.putrequest('POST','/v1/simulation');connection.putheader('Content-Type','application/json');connection.putheader('Content-Length',len(body));connection.endheaders()
            for i in range(0,len(body),9):connection.send(body[i:i+9])
            r=connection.getresponse();self.assertEqual(200,r.status);self.assertEqual('APPLIED',json.loads(r.read())['outcome'])
        finally:connection.close()
    def test_independent_20hz_sampler_with_weather_unavailable(self):
        start=self.sim.sample_count;time.sleep(1.05);produced=self.sim.sample_count-start
        self.assertGreaterEqual(produced,17);self.assertLessEqual(produced,23)
        self.assertEqual('READY',self.sim.run_status);self.assertGreater(self.server.sonar.state()['lastPingId'],1)
    def test_concurrent_helm_audio_weather_sonar_reads(self):
        paths=['/v1/helm','/v1/audio','/v1/weather','/v1/sonar','/v1/status','/v1/simulation']*2
        with ThreadPoolExecutor(max_workers=6) as executor:results=list(executor.map(lambda path:self.request(path)[0],paths))
        self.assertEqual([200,200,503,200,200,200]*2,results)


if __name__=='__main__':unittest.main()

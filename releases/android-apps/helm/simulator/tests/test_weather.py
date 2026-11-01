import copy
import io
import json
from pathlib import Path
import threading
import time
import unittest
from simulator.weather import RealWeatherProvider
from simulator.server import Simulation, SimulationServer


class Response(io.BytesIO):
    def geturl(self):return 'https://api.open-meteo.com/v1/forecast?test=1'


class WeatherTests(unittest.TestCase):
    def setUp(self):
        self.now=1790996400000;self.mono=100.0;self.calls=[]
        self.data={'latitude':-34.3,'longitude':18.6,'current_units':{'time':'unixtime','temperature_2m':'°C',
            'relative_humidity_2m':'%','pressure_msl':'hPa','wind_speed_10m':'kn','wind_direction_10m':'°'},
            'current':{'time':self.now//1000,'temperature_2m':0.0,'relative_humidity_2m':68,
                       'pressure_msl':1015.2,'wind_speed_10m':0.0,'wind_direction_10m':360}}
        def opener(request,timeout):
            self.calls.append(request.full_url)
            return Response(json.dumps(self.data).encode())
        self.weather=RealWeatherProvider(lambda:(-34.3,18.6),lambda:self.mono,lambda:self.now,opener)
    def test_real_model_provenance_units_zero_and_cache_contract(self):
        self.assertTrue(self.weather.refresh());code,body=self.weather.get()
        self.assertEqual(200,code);self.assertFalse(body['simulated']);self.assertEqual('REAL',body['sourceKind'])
        self.assertEqual('MEAN_SEA_LEVEL',body['pressureReference']);self.assertEqual('FORECAST',body['provider']['kind']);self.assertEqual('MODELLED_CURRENT_WEATHER',body['provenance']['measurementKind'])
        self.assertNotIn('simulation',body);self.assertEqual(0,body['values']['windSpeedKn']);self.assertEqual(0,body['values']['airTemperatureC'])
        self.assertEqual(0,body['values']['windDirectionDegrees']);self.assertEqual(self.now,body['observedAtEpochMs'])
        self.assertIn('timezone=UTC',self.calls[0]);self.assertIn('wind_speed_unit=kn',self.calls[0])
        self.assertFalse(self.weather.refresh());self.assertEqual(1,len(self.calls))
        try:import jsonschema
        except ImportError:return
        schema=json.loads((Path(__file__).parents[2]/'contracts/no5-gateway-v1.schema.json').read_text())
        jsonschema.validate(body,schema)
    def test_reads_do_not_refresh_any_timestamp(self):
        self.weather.refresh();before=self.weather.get()[1];self.now+=10000;after=self.weather.get()[1]
        self.assertEqual(before['timestampEpochMs'],after['timestampEpochMs']);self.assertEqual(before['observedAtEpochMs'],after['observedAtEpochMs'])
        self.assertEqual(10,after['cache']['ageSeconds']);self.assertEqual(1,len(self.calls))
    def test_initial_provider_failure_has_no_fabricated_weather(self):
        self.weather.opener=lambda *a,**kw:(_ for _ in ()).throw(OSError('offline'))
        self.assertFalse(self.weather.refresh());code,body=self.weather.get();self.assertEqual(503,code)
        self.assertFalse(body['syntheticFallback']);self.assertNotIn('values',body);self.assertFalse(body['simulated'])
    def test_failure_retains_stale_real_cache_and_expires_it(self):
        self.weather.refresh();original=self.weather.get()[1];self.mono+=301;self.now+=301000
        self.weather.opener=lambda *a,**kw:(_ for _ in ()).throw(OSError('offline'));self.weather.refresh()
        self.assertEqual('STALE',self.weather.get()[1]['status']);self.assertEqual(original['timestampEpochMs'],self.weather.get()[1]['timestampEpochMs'])
        self.now+=7200000;self.assertEqual(503,self.weather.get()[0])
    def test_disabled_sharing_clears_cache_and_never_calls_provider(self):
        self.weather.refresh();self.weather.configure(False);self.mono+=1000
        self.assertFalse(self.weather.refresh());self.assertEqual(1,len(self.calls));self.assertIsNone(self.weather.cached)
        self.assertEqual(503,self.weather.get()[0]);self.assertEqual('OFF',self.weather.status()['sharing'])
    def test_no_custom_or_missing_personal_coordinate_fallback(self):
        for coords in ((None,None),(52.5,13.4),(float('nan'),18.6)):
            self.weather.coordinates=lambda:coords;self.weather.next_attempt=0
            self.assertFalse(self.weather.refresh());self.assertEqual([],self.calls)
    def test_bad_time_units_values_and_empty_response_fail_closed(self):
        variants=[]
        for patch in ({'time':(self.now+600000)//1000},{'time':(self.now-7300000)//1000},{'temperature_2m':float('nan')},
                      {'temperature_2m':None,'wind_speed_10m':None,'wind_direction_10m':None,'relative_humidity_2m':None,'pressure_msl':None}):
            item=copy.deepcopy(self.data);item['current'].update(patch);variants.append(item)
        item=copy.deepcopy(self.data);item['current_units']['wind_speed_10m']='km/h';variants.append(item)
        for data in variants:
            self.data=data;self.weather.next_attempt=0;self.assertFalse(self.weather.refresh());self.assertIsNone(self.weather.cached)
    def test_inflight_result_is_discarded_after_optout(self):
        entered,released=threading.Event(),threading.Event()
        def slow(*args,**kwargs):entered.set();released.wait(2);return Response(json.dumps(self.data).encode())
        self.weather.opener=slow;t=threading.Thread(target=self.weather.refresh);t.start();self.assertTrue(entered.wait(1))
        self.weather.configure(False);released.set();t.join(2)
        self.assertIsNone(self.weather.cached);self.assertEqual(503,self.weather.get()[0])
    def test_fault_freeze_and_recovery_never_date_cache_from_voyage_time(self):
        self.weather.refresh();stamp=self.weather.get()[1]['timestampEpochMs'];self.weather.configure(True,'stale')
        self.mono+=100;self.now+=100000;self.assertFalse(self.weather.refresh());self.assertEqual('STALE',self.weather.get()[1]['status'])
        self.assertEqual(stamp,self.weather.get()[1]['timestampEpochMs']);self.weather.configure(True,'failed');self.assertEqual(503,self.weather.get()[0])
        self.weather.configure(True,'none');self.data['current']['time']=self.now//1000;self.assertTrue(self.weather.refresh());self.assertEqual('LIVE',self.weather.get()[1]['status'])
    def test_slow_weather_network_never_blocks_20hz_helm_or_audio(self):
        entered,released=threading.Event(),threading.Event()
        def slow(*args,**kwargs):entered.set();released.wait(3);raise OSError('test timeout')
        weather=RealWeatherProvider(lambda:(-34.3,18.6),opener=slow)
        simulation=Simulation(weather=weather);server=SimulationServer(('127.0.0.1',0),simulation)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            self.assertTrue(entered.wait(1));before=simulation.sample_count;time.sleep(1.05)
            self.assertGreaterEqual(simulation.sample_count-before,17);self.assertEqual(200,simulation.get('helm')[0]);self.assertEqual(200,server.audio.get()[0])
        finally:released.set();server.shutdown();server.server_close();thread.join(2)


if __name__=='__main__':unittest.main()

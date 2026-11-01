"""Independent real Open-Meteo model weather. Never fabricates observations.

Current weather uses real UTC, never the voyage clock. Only the simulator's
fixed synthetic boat coordinates are accepted; this is not a personal-location
input or a phone-location client. No credentials, disk cache or hidden fallback.
"""
import copy
import json
import math
import threading
import time
import uuid
from urllib.parse import urlencode
from urllib.request import Request, urlopen

WEATHER_UNITS = {
    'airTemperatureC': 'degC', 'windSpeedKn': 'kn', 'windDirectionDegrees': 'deg_true_from',
    'relativeHumidityPercent': 'percent', 'pressureHpa': 'hPa',
    'latitudeDegrees': 'deg', 'longitudeDegrees': 'deg',
}
PROVIDER = {'id':'OPEN_METEO','label':'Open-Meteo · weather model','kind':'FORECAST',
            'modelled':True,'attributionUrl':'https://open-meteo.com/',
            'documentationUrl':'https://open-meteo.com/en/docs'}


class RealWeatherProvider:
    REFRESH_SECONDS = 300
    RETRY_SECONDS = 60
    FRESH_MODEL_MS = 30 * 60 * 1000
    STALE_LIMIT_MS = 2 * 60 * 60 * 1000
    MAX_RESPONSE_BYTES = 65536

    def __init__(self, coordinates, monotonic=time.monotonic,
                 wall_epoch_ms=lambda: time.time_ns() // 1_000_000, opener=urlopen):
        self.coordinates, self.monotonic, self.wall_epoch_ms, self.opener = coordinates, monotonic, wall_epoch_ms, opener
        self.lock = threading.RLock()
        self.enabled = True
        self.fault = 'none'
        self.cached = None
        self.last_error = None
        self.next_attempt = 0.0
        self.last_attempt_ms = None
        self.inflight = False
        self.generation = 0
        self.session_id = uuid.uuid4().hex
        self.stop_event = threading.Event()
        self.worker = None

    def configure(self, enabled, fault='none'):
        if not isinstance(enabled,bool) or fault not in ('none','stale','failed'):
            raise ValueError('invalid weather controls')
        with self.lock:
            if (self.enabled,self.fault) != (enabled,fault):
                self.generation += 1
                self.next_attempt = 0
            self.enabled, self.fault = enabled, fault
            if not enabled:
                self.cached = None
                self.last_error = None

    def start(self):
        if self.worker is None:
            self.worker = threading.Thread(target=self._loop,name='no5-real-weather',daemon=True)
            self.worker.start()

    def close(self):
        self.stop_event.set()
        if self.worker is not None:
            self.worker.join(timeout=9)

    def _loop(self):
        while not self.stop_event.is_set():
            self.refresh()
            self.stop_event.wait(1)

    def refresh(self):
        with self.lock:
            if not self.enabled or self.fault != 'none' or self.inflight or self.monotonic() < self.next_attempt:
                return False
            generation = self.generation
            self.inflight = True
            self.last_attempt_ms = int(self.wall_epoch_ms())
        try:
            lat, lon = self.coordinates()
            # Bound the only accepted input to the known synthetic test region.
            # A future real-Pi integration must establish its own explicit consent.
            if not all(isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x) for x in (lat,lon)) or not (-34.6 <= lat <= -34.0 and 18.3 <= lon <= 18.9):
                raise ValueError('No authorized synthetic boat coordinate is available')
            params = {'latitude':round(lat,4),'longitude':round(lon,4),
                      'current':'temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m',
                      'wind_speed_unit':'kn','temperature_unit':'celsius','timezone':'UTC','timeformat':'unixtime','forecast_days':1}
            request = Request('https://api.open-meteo.com/v1/forecast?' + urlencode(params),
                              headers={'Accept':'application/json','User-Agent':'No5-External-Simulator/0.7'})
            with self.opener(request,timeout=8) as response:
                # Reject unexpected redirects/hosts, including accidental credential endpoints.
                url = response.geturl() if hasattr(response,'geturl') else request.full_url
                if not url.startswith('https://api.open-meteo.com/v1/forecast?'):
                    raise ValueError('Unexpected weather response URL')
                raw = response.read(self.MAX_RESPONSE_BYTES+1)
            if len(raw) > self.MAX_RESPONSE_BYTES:
                raise ValueError('Weather response exceeds 64 KiB')
            data = json.loads(raw,parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite weather number')))
            payload = self._decode(data,lat,lon)
            with self.lock:
                if generation != self.generation or not self.enabled or self.fault != 'none':
                    return False
                self.cached = payload
                self.last_error = None
                self.next_attempt = self.monotonic() + self.REFRESH_SECONDS
            return True
        except Exception as error:
            with self.lock:
                if generation == self.generation:
                    self.last_error = type(error).__name__ + ': ' + str(error)[:180]
                    self.next_attempt = self.monotonic() + self.RETRY_SECONDS
            return False
        finally:
            with self.lock:
                self.inflight = False

    def _decode(self,data,lat,lon):
        now = int(self.wall_epoch_ms())
        current, units = data['current'], data['current_units']
        required = {'temperature_2m':'°C','relative_humidity_2m':'%','pressure_msl':'hPa',
                    'wind_speed_10m':'kn','wind_direction_10m':'°','time':'unixtime'}
        if any(units.get(key) != unit for key,unit in required.items()):
            raise ValueError('Unexpected Open-Meteo units')
        observed = current['time']
        if isinstance(observed,bool) or not isinstance(observed,(float,int)) or not math.isfinite(observed):
            raise ValueError('Missing model valid time')
        observed = round(observed*1000)
        if observed <= 0 or observed > now + 300000 or now-observed > self.STALE_LIMIT_MS:
            raise ValueError('Weather model valid time outside the accepted real-UTC window')
        def number(key,low,high):
            v=current.get(key)
            if v is None:return None
            if isinstance(v,bool) or not isinstance(v,(float,int)) or not math.isfinite(v) or not low<=v<=high:
                raise ValueError('Invalid weather field: '+key)
            return v
        values={'airTemperatureC':number('temperature_2m',-80,80),'windSpeedKn':number('wind_speed_10m',0,250),
                'windDirectionDegrees':number('wind_direction_10m',0,360),
                'relativeHumidityPercent':number('relative_humidity_2m',0,100),'pressureHpa':number('pressure_msl',800,1200)}
        if values['windDirectionDegrees'] is not None:values['windDirectionDegrees'] %= 360
        # Provider grid-cell coordinates describe the observation, not invented boat precision.
        gridlat,gridlon=data.get('latitude'),data.get('longitude')
        if not all(isinstance(x,(int,float)) and not isinstance(x,bool) and math.isfinite(x) for x in (gridlat,gridlon)) or not -90<=gridlat<=90 or not -180<=gridlon<=180:
            raise ValueError('Missing provider grid location')
        values.update(latitudeDegrees=gridlat,longitudeDegrees=gridlon)
        if all(values[key] is None for key in ('airTemperatureC','windSpeedKn','relativeHumidityPercent','pressureHpa')):
            raise ValueError('Weather response contains no observations')
        return {'version':1,'kind':'weather','source':'NO5_PI_SIMULATOR','serverSessionId':self.session_id,
                'sourceKind':'REAL','simulated':False,'status':'LIVE',
                'timestampEpochMs':now,'publishedAtEpochMs':now,'observedAtEpochMs':observed,
                'units':WEATHER_UNITS.copy(),'provider':PROVIDER.copy(),'pressureReference':'MEAN_SEA_LEVEL',
                'delivery':{'gatewayId':'NO5_PI_SIMULATOR','transport':'HTTP'},
                'capabilities':{'airTemperature':values['airTemperatureC'] is not None,
                    'wind':values['windSpeedKn'] is not None or values['windDirectionDegrees'] is not None,
                    'humidity':values['relativeHumidityPercent'] is not None,'pressure':values['pressureHpa'] is not None,'observedLocation':True},
                'values':values,'provenance':{'locationKind':'SYNTHETIC_BOAT_TEST_COORDINATES','requestedLatitude':round(lat,4),
                    'requestedLongitude':round(lon,4),'fetchedAtEpochMs':now,'validAtEpochMs':observed,
                    'model':'Open-Meteo best_match','measurementKind':'MODELLED_CURRENT_WEATHER'}}

    def get(self):
        with self.lock:
            reason = 'Weather coordinate sharing is off' if not self.enabled else 'Injected weather failure' if self.fault=='failed' else None
            now = int(self.wall_epoch_ms())
            if reason is None and self.cached is None:reason = self.last_error or 'Waiting for first real Open-Meteo response'
            if reason is None and now - self.cached['observedAtEpochMs'] > self.STALE_LIMIT_MS:reason = 'Cached model observation expired'
            if reason:
                return 503,{'version':1,'kind':'weather','status':'UNAVAILABLE','provider':PROVIDER.copy(),
                            'sourceKind':'REAL','simulated':False,'error':reason,'syntheticFallback':False}
            result=copy.deepcopy(self.cached)
            if self.fault=='stale' or self.last_error or now-result['observedAtEpochMs']>self.FRESH_MODEL_MS or now-result['timestampEpochMs']>self.REFRESH_SECONDS*2000:
                result['status']='STALE'
            result['cache']={'ageSeconds':max(0,(now-result['timestampEpochMs'])/1000),
                             'modelAgeSeconds':max(0,(now-result['observedAtEpochMs'])/1000),
                             'refreshIntervalSeconds':self.REFRESH_SECONDS,'lastError':self.last_error}
            return 200,result

    def status(self):
        code,payload=self.get()
        with self.lock:
            return {'enabled':self.enabled,'sharing':'SYNTHETIC_BOAT_COORDINATES_ONLY' if self.enabled else 'OFF',
                    'provider':PROVIDER.copy(),'status':payload['status'],'inFlight':self.inflight,
                    'observedAtEpochMs':payload.get('observedAtEpochMs'),'fetchedAtEpochMs':payload.get('timestampEpochMs'),
                    'cache':payload.get('cache'),'error':payload.get('error') or self.last_error,
                    'values':payload.get('values'),'fault':self.fault,'syntheticFallback':False}

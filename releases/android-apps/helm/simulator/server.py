"""Version 1 simulation HTTP gateway, deliberately isolated from hardware."""
import argparse
from collections import OrderedDict
from pathlib import Path
import copy
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import socket
import threading
import time
import uuid
from urllib.parse import urlsplit, parse_qs

from .engine import SIMULATION_EPOCH_MS, VoyageEngine
from .config import DEFAULTS, COLD_SETTINGS, validate_settings
from .weather import RealWeatherProvider
from .scene_weather import scene_weather
from .sonar import SonarSimulator
from .sonar_transport import serve_sonar_stream

SOURCE = "NO5_PI_SIMULATOR"
HELM_UNITS = {
    "speed": "kn", "heading": "deg_true", "courseOverGroundDegrees": "deg_true",
    "depth": "m", "waterTemp": "degC", "rpm": "rpm", "trim": "percent",
    "voltage": "V", "fuel": "percent", "coolant": "degC", "oil": "bar",
    "fuelFlow": "L/h", "position": "deg", "fuelRemainingLiters": "L",
}
WEATHER_UNITS = {
    "airTemperatureC": "degC", "windSpeedKn": "kn", "windDirectionDegrees": "deg_true_from",
    "relativeHumidityPercent": "percent", "pressureHpa": "hPa",
    "latitudeDegrees": "deg", "longitudeDegrees": "deg",
}


class ControlError(ValueError):
    def __init__(self, message, code='INVALID_SETTINGS', http_status=400):
        super().__init__(message)
        self.code, self.http_status = code, http_status


class Simulation:
    """Authoritative lifecycle and revisioned settings, independent of HTTP reads.

    READY is armed, RUNNING advances physics (including the standing phase),
    PAUSED preserves all values, STOPPED zeroes motion, COMPLETED ends a voyage.
    Producer and receipts have independent clocks; no request is replayed on
    reconnection. Request IDs are process-scoped and bounded to 256 receipts.
    """
    def __init__(self, monotonic=time.monotonic, wall_epoch_ms=lambda: time.time_ns() // 1_000_000,
                 time_scale=1.0, scenario='figure8', running=False, run_id_factory=lambda: uuid.uuid4().hex,
                 server_id_factory=lambda: uuid.uuid4().hex, weather=None):
        self.monotonic, self.wall_epoch_ms = monotonic, wall_epoch_ms
        self.lock = threading.RLock()
        self.settings = validate_settings(dict(DEFAULTS,timeScale=time_scale,scenario=scenario))
        self.engine = VoyageEngine(scenario, self.settings)
        self.run_status = 'RUNNING' if running else 'READY'
        self.last_tick = monotonic()
        self.run_id_factory = run_id_factory
        self.run_id = run_id_factory()
        self.server_instance_id = server_id_factory()
        self.revision = 0
        self.last_command = None
        self.receipts = OrderedDict()
        self.cached = {}
        self.sample_epoch_ms = int(self.wall_epoch_ms())
        self.sample_count = 0
        self.weather = weather or RealWeatherProvider(self.weather_coordinates,monotonic,wall_epoch_ms)
        self.weather.configure(self.settings['weatherEnabled'],self.settings['weatherFault'])
        self._publish()

    @property
    def running(self):return self.run_status == 'RUNNING'
    @property
    def time_scale(self):return self.settings['timeScale']
    @property
    def faults(self):return {'helm':self.settings['helmFault'],'weather':self.settings['weatherFault']}

    def weather_coordinates(self):
        with self.lock:
            v=self.engine.helm_values()
            return v['latitudeDegrees'],v['longitudeDegrees']

    def _sync(self):
        current = self.monotonic()
        elapsed = max(0.0,current-self.last_tick)
        self.last_tick = current
        if self.running:
            remaining = elapsed*self.time_scale
            # A finite scenario finishes at its physical end, not at a client gap.
            while remaining > 0 and self.running:
                chunk=min(remaining,1.0)
                self.engine.advance(chunk, stop_at_complete=True)
                remaining-=chunk
                if self.engine.state.phase == 'COMPLETE':
                    self.run_status='COMPLETED'
                    self.revision+=1

    def _simulation_metadata(self):
        s=self.engine.state
        return {'elapsedSeconds':s.elapsed,'observedAtEpochMs':self.settings['clockStartEpochMs']+round(s.elapsed*1000),
                'running':self.running,'timeScale':self.time_scale,'phase':s.phase,'scenario':self.engine.scenario,
                'runId':self.run_id,'runStatus':self.run_status,'mode':self.settings['mode']}

    def _snapshot(self,kind):
        metadata=self._simulation_metadata()
        payload={'version':1,'kind':kind,'source':SOURCE,'sourceKind':'SIMULATED','simulated':True,
                 'timestampEpochMs':self.sample_epoch_ms,'publishedAtEpochMs':self.sample_epoch_ms,'serverSessionId':self.run_id,
                 'status':'LIVE','simulation':metadata,'running':self.running,
                 'capabilities':{'telemetry':True,'position':True,'depth':True,'waterTemperature':False,'commands':False},
                 'vesselTime':datetime.fromtimestamp(metadata['observedAtEpochMs']/1000,timezone.utc).strftime('%H:%M:%S'),
                 'units':HELM_UNITS.copy(),'values':self.engine.helm_values(),'extras':self.engine.extras()}
        payload['fieldQuality']={key:{'status':'LIVE' if value is not None else 'UNAVAILABLE',
                                     'timestampEpochMs':self.sample_epoch_ms if value is not None else None}
                                 for key,value in dict(payload['values'],**{k:payload['extras'][k] for k in (
                                     'tripDistanceNm','tripDurationSeconds','fuelUsedLiters','efficiencyNmPerLiter')}).items()
                                 if key not in ('portGear','starboardGear')}
        return payload

    def _publish(self):
        self.sample_epoch_ms=int(self.wall_epoch_ms())
        self.sample_count+=1
        if self.settings['helmFault']=='none' or 'helm' not in self.cached:
            self.cached['helm']=self._snapshot('helm')
        if self.settings['environmentMode']=='simulated' and (self.settings['weatherFault']=='none' or 'weather' not in self.cached):
            self.cached['weather']=scene_weather(self.settings,self.sample_epoch_ms,self.server_instance_id,
                                                self.revision,self.weather_coordinates())

    def sample(self):
        with self.lock:
            self._sync()
            self._publish()

    def _timeline(self):
        if self.settings['mode']=='manual' or self.settings['scenario']=='idle':
            return {'phases':[],'phaseElapsedSeconds':self.engine.state.phase_elapsed,
                    'phaseRemainingSeconds':None,'remainingSeconds':None,'nextPhase':None,'estimated':False}
        s=self.engine.state
        figure_seconds=self.engine.route.length / (19*0.514444*(self.engine.trim_factor(self.engine.port_trim)+self.engine.trim_factor(self.engine.starboard_trim))/2*.78)
        definitions=[('STOPPED','Standing',self.settings['standingSeconds']),('FULL_THROTTLE_LAUNCH','Full throttle',self.settings['launchSeconds']),
                     ('CRUISE','Cruise',self.settings['cruiseSeconds']),('FIGURE_EIGHT','Figure eight',figure_seconds),
                     ('DECELERATE','Decelerate',self.settings['decelerateSeconds'])]
        ids=[x[0] for x in definitions]
        index=ids.index(s.phase) if s.phase in ids else len(ids)
        phases=[{'id':key,'label':label,'durationSeconds':duration,'estimated':key=='FIGURE_EIGHT',
                 'status':'DONE' if i<index else 'ACTIVE' if i==index else 'NEXT'} for i,(key,label,duration) in enumerate(definitions)]
        left=max(0,definitions[index][2]-s.phase_elapsed) if index<len(ids) else 0
        if s.phase=='FIGURE_EIGHT':
            left=(1-s.figure_distance_m/self.engine.route.length)*figure_seconds
        total=left+sum(x[2] for x in definitions[index+1:])
        return {'phases':phases,'phaseElapsedSeconds':s.phase_elapsed,'phaseRemainingSeconds':left,
                'remainingSeconds':total,'nextPhase':definitions[index+1][1] if index+1<len(ids) else 'Complete' if index<len(ids) else None,
                'estimated':index<=ids.index('FIGURE_EIGHT')}

    def control_state(self,request_id=None):
        with self.lock:
            state={'version':1,'kind':'simulation.control','serverInstanceId':self.server_instance_id,
                   'revision':self.revision,'runId':self.run_id,'runStatus':self.run_status,
                   'actual':self._simulation_metadata(),'appliedSettings':copy.deepcopy(self.settings),
                   'timeline':self._timeline(),'telemetry':self.engine.helm_values(),'trip':self.engine.extras(),
                   'sampleCount':self.sample_count,'publishedAtEpochMs':self.sample_epoch_ms,
                   'serverTimeEpochMs':int(self.wall_epoch_ms()),'lastCommand':copy.deepcopy(self.last_command),
                   'weather':self.weather_status(),'coldSettings':sorted(COLD_SETTINGS),
                   'allowedActions':{'start':self.run_status in ('READY','STOPPED','COMPLETED'),
                        'pause':self.run_status=='RUNNING','resume':self.run_status=='PAUSED',
                        'stop':self.run_status in ('RUNNING','PAUSED'),'reset':True,'apply':True},
                   'receipt':copy.deepcopy(self.receipts.get(request_id,{}).get('ack')) if request_id else None}
            return state

    def weather_status(self):
        if self.settings['environmentMode']=='live':return dict(self.weather.status(),environmentMode='live')
        code,payload=self.get('weather')
        return dict(payload,enabled=True,environmentMode='simulated',fetchedAtEpochMs=payload.get('timestampEpochMs'))

    def get(self,kind):
        if kind=='weather':
            with self.lock:
                if self.settings['environmentMode']=='live':return self.weather.get()
                if self.settings['weatherFault']=='failed':
                    return 503,{'version':1,'status':'UNAVAILABLE','sourceKind':'SIMULATED','simulated':True,'error':'Injected scene weather failure'}
                payload=copy.deepcopy(self.cached['weather'])
                if self.settings['weatherFault']=='stale':payload['status']='STALE'
                return 200,payload
        with self.lock:
            if kind=='status':
                return 200,{'version':1,'kind':'status','source':SOURCE,'sourceKind':'SIMULATED','simulated':True,
                    'timestampEpochMs':self.sample_epoch_ms,'publishedAtEpochMs':self.sample_epoch_ms,'serverSessionId':self.run_id,
                    'simulation':self._simulation_metadata(),'faults':self.faults,
                    'availableEndpoints':['/v1/helm','/v1/weather','/v1/audio','/v1/status','/v1/simulation'],
                    'capabilities':{'helm':True,'weather':True,'audio':True,'sonar':False,'hardwareWrites':False}}
            if kind!='helm':return 404,{'error':'unknown endpoint'}
            if self.settings['helmFault']=='failed':return 503,{'version':1,'error':'Injected HELM failure','sourceKind':'SIMULATED','simulated':True}
            payload=copy.deepcopy(self.cached['helm'])
            if self.settings['helmFault']=='stale':payload['status']='STALE'
            return 200,payload

    def command(self,command):
        if not isinstance(command,dict):raise ControlError('Expected a control command object')
        required={'version','serverInstanceId','expectedRevision','requestId','action'}
        if set(command) not in (required,required|{'settings'}) or type(command.get('version')) is not int or command.get('version')!=1:
            raise ControlError('Expected version, serverInstanceId, expectedRevision, requestId, action and optional complete settings')
        request_id=command['requestId']
        if not isinstance(request_id,str) or not 8<=len(request_id)<=100 or not all(c.isalnum() or c in '-_.' for c in request_id):
            raise ControlError('requestId must be 8–100 letters, numbers, hyphens, underscores or dots')
        if isinstance(command['expectedRevision'],bool) or not isinstance(command['expectedRevision'],int) or command['expectedRevision']<0:
            raise ControlError('expectedRevision must be a nonnegative integer')
        action=command['action']
        if action not in ('apply','start','pause','resume','stop','reset'):
            raise ControlError('Unknown simulator action')
        if (action=='apply') != ('settings' in command):raise ControlError('Only apply requires complete settings')
        candidate=validate_settings(command['settings']) if action=='apply' else None
        fingerprint=json.dumps(command,sort_keys=True,allow_nan=False,separators=(',',':'))
        with self.lock:
            if command['serverInstanceId']!=self.server_instance_id:
                raise ControlError('Server restarted. Reload state and explicitly submit a new command.','SESSION_CHANGED',409)
            if request_id in self.receipts:
                previous=self.receipts[request_id]
                if fingerprint!=previous['fingerprint']:raise ControlError('requestId was already used with different content','REQUEST_ID_REUSED',409)
                return dict(copy.deepcopy(previous['ack']),replayed=True,state=self.control_state())
            if command['expectedRevision']!=self.revision:
                raise ControlError('State changed elsewhere. Review current settings before applying again.','REVISION_CONFLICT',409)
            if not self.control_state()['allowedActions'][action]:
                raise ControlError(f'{action} is not valid while {self.run_status.lower()}','INVALID_TRANSITION',409)
            cold_changed=candidate is not None and any(candidate[k]!=self.settings[k] for k in COLD_SETTINGS)
            if cold_changed and self.run_status in ('RUNNING','PAUSED'):
                raise ControlError('Stop the voyage before changing mode, scenario, route, acceleration, initial fuel or clock.','STOP_REQUIRED',409)
            # Everything validates before clocks, settings, engine or revision mutate.
            self._sync()
            if not self.control_state()['allowedActions'][action]:
                self._publish()
                raise ControlError('Voyage completed before this action. Review the current state.','INVALID_TRANSITION',409)
            if action=='apply':
                environment_changed=candidate['environmentMode'] != self.settings['environmentMode']
                if environment_changed:self.cached.pop('weather',None)
                self.settings=candidate
                self.engine.configure(candidate,reset=cold_changed)
                if cold_changed:
                    self.run_id=self.run_id_factory()
                    self.run_status='READY'
                    self.cached.clear()
                self.weather.configure(candidate['weatherEnabled'] and candidate['environmentMode']=='live',candidate['weatherFault'])
            elif action=='reset' or (action=='start' and self.run_status in ('STOPPED','COMPLETED')):
                self.engine.configure(self.settings,reset=True)
                self.run_id=self.run_id_factory()
                self.run_status='RUNNING' if action=='start' else 'READY'
                self.cached.clear()
            elif action=='start':self.run_status='RUNNING'
            elif action=='pause':self.run_status='PAUSED'
            elif action=='resume':self.run_status='RUNNING'
            elif action=='stop':
                self.run_status='STOPPED'
                self.engine.stop()
            self.last_tick=self.monotonic()
            self.revision+=1
            self.last_command={'requestId':request_id,'action':action,'revision':self.revision,'outcome':'APPLIED',
                               'appliedAtEpochMs':int(self.wall_epoch_ms()),'runId':self.run_id}
            self._publish()
            ack={'version':1,'kind':'simulation.ack','requestId':request_id,'serverInstanceId':self.server_instance_id,
                 'revision':self.revision,'outcome':'APPLIED','replayed':False,'runId':self.run_id,'action':action}
            self.receipts[request_id]={'fingerprint':fingerprint,'ack':copy.deepcopy(ack)}
            while len(self.receipts)>256:self.receipts.popitem(last=False)
            return dict(ack,state=self.control_state())


CONTROL_HTML = Path(__file__).with_name('control.html').read_text(encoding='utf-8')

class SimulationServer(ThreadingHTTPServer):
    """One independent producer and at most 16 persistent HTTP connection workers."""
    daemon_threads = True
    allow_reuse_address = True
    SAMPLE_INTERVAL = .05
    MAX_CONNECTIONS = 16

    def __init__(self, address, simulation=None):
        self.simulation = simulation or Simulation()
        from .audio import AudioSimulator
        self.audio = AudioSimulator(monotonic=self.simulation.monotonic, wall_epoch_ms=self.simulation.wall_epoch_ms)
        from .audio_commands import AudioCommands
        self.audio_commands = AudioCommands(self.audio)
        self.sonar = SonarSimulator(self.simulation)
        self._sampler_stop = threading.Event()
        self._sampler = None
        self._connections = threading.BoundedSemaphore(self.MAX_CONNECTIONS)
        self._sonar_streams = threading.BoundedSemaphore(4)
        super().__init__(address, SimulationHandler)

    def _sample_forever(self):
        while not self._sampler_stop.is_set():
            started = time.monotonic()
            self.simulation.sample()
            self.audio.sample()
            self.sonar.sample()
            elapsed = time.monotonic() - started
            # Missed publication slots are skipped. Physics still integrates all elapsed time.
            delay = self.SAMPLE_INTERVAL - elapsed if elapsed < self.SAMPLE_INTERVAL else self.SAMPLE_INTERVAL
            self._sampler_stop.wait(delay)

    def serve_forever(self, poll_interval=.2):
        self.audio_commands.start()
        self._sampler = threading.Thread(target=self._sample_forever, name="no5-simulation-sampler", daemon=True)
        self._sampler.start()
        self.simulation.weather.start()
        try:
            super().serve_forever(poll_interval)
        finally:
            self._sampler_stop.set()
            self._sampler.join(timeout=3)
            self.simulation.weather.close()

    def process_request(self, request, client_address):
        if not self._connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connections.release()

    def server_close(self):
        self.audio_commands.close()
        self._sampler_stop.set()
        self.simulation.weather.close()
        super().server_close()
        if self._sampler is not None:
            self._sampler.join(timeout=3)


class SimulationHandler(BaseHTTPRequestHandler):
    server_version = "No5Simulation/1"
    protocol_version = "HTTP/1.1"
    rbufsize = 0

    def setup(self):
        super().setup()
        self.connection.settimeout(5)
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def _response(self, code, body, content_type="application/json; charset=utf-8"):
        encoded = json.dumps(body, allow_nan=False, separators=(",", ":")).encode() if not isinstance(body, str) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        # Failed requests may leave an unread body, so only successes are reusable.
        if code >= 400:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/":
            self._response(200, CONTROL_HTML, "text/html; charset=utf-8")
        elif urlsplit(self.path).path == '/v1/simulation':
            query=parse_qs(urlsplit(self.path).query)
            request_id=query.get('requestId',[None])[0]
            self._response(200,self.server.simulation.control_state(request_id))
        elif self.path == '/v1/sonar':
            self._response(200,self.server.sonar.state())
        elif self.path == '/v1/sonar/stream':
            if not self.server._sonar_streams.acquire(blocking=False):
                self._response(503,{'error':'Four SONAR stream clients are already connected'})
                return
            try:
                serve_sonar_stream(self,self.server.sonar,self.server._sampler_stop)
            finally:
                self.server._sonar_streams.release()
        elif self.path == "/v1/audio":
            code, body = self.server.audio.get()
            self._response(code, body)
        elif self.path.startswith('/v1/audio/commands/'):
            body = self.server.audio_commands.read(self.path.removeprefix('/v1/audio/commands/'))
            self._response(200 if body is not None else 410, body or {'error':'Command result unavailable'})
        elif self.path in ("/v1/helm", "/v1/weather", "/v1/status"):
            code, body = self.server.simulation.get(self.path.rsplit("/", 1)[1])
            if self.path == '/v1/status':
                body['capabilities']['sonar']=True
                body['availableEndpoints'].extend(['/v1/sonar','/v1/sonar/stream'])
            self._response(code, body)
        else:
            self._response(404, {"error": "unknown endpoint"})

    @staticmethod
    def _unique_keys(pairs):
        result = {}
        for key,value in pairs:
            if key in result:raise ValueError('Duplicate JSON key: '+key)
            result[key]=value
        return result

    def _read_body(self,length):
        deadline=time.monotonic()+5
        body=bytearray()
        while len(body)<length:
            remaining=deadline-time.monotonic()
            if remaining<=0:raise ValueError('Request body deadline exceeded')
            self.connection.settimeout(remaining)
            chunk=self.rfile.read(length-len(body))
            if not chunk:raise ValueError('Incomplete request body')
            body.extend(chunk)
        self.connection.settimeout(5)
        return bytes(body)

    def do_POST(self):
        if self.path not in ("/v1/simulation", "/v1/audio/command", "/v1/audio/commands", "/v1/sonar/command", "/v1/simulation/sonar"):
            self._response(404, {"error": "only explicit simulator command endpoints accept POST"})
            return
        origin = self.headers.get("Origin")
        if origin and (urlsplit(origin).scheme not in ("http", "https") or urlsplit(origin).netloc != self.headers.get("Host")):
            self._response(403, {"error": "cross-origin simulation command rejected"})
            return
        if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
            self._response(415, {"error": "Content-Type application/json required"})
            return
        try:
            if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length',[])) != 1:
                raise ValueError('Exactly one Content-Length and no Transfer-Encoding required')
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 8192:
                raise ValueError("JSON command length must be from 1 to 8192 bytes")
            command = json.loads(self._read_body(length), object_pairs_hook=self._unique_keys, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite JSON number")))
            if self.path == '/v1/sonar/command':
                response = self.server.sonar.command(command)
            elif self.path == '/v1/simulation/sonar':
                response = self.server.sonar.configure_command(command)
            elif self.path == "/v1/audio/command":
                response = self.server.audio.command(command)
            elif self.path == '/v1/audio/commands':
                if length > 4096:
                    raise ValueError('Audio admission exceeds 4096 bytes')
                self._response(202, self.server.audio_commands.submit(command))
                return
            elif isinstance(command, dict) and command.get("command") == "audioFault":
                if set(command) != {"command", "value"}:
                    raise ValueError("audioFault requires value")
                self.server.audio.set_fault(command["value"])
                response = self.server.simulation.get("status")[1]
            elif isinstance(command, dict) and command.get("command") == "audioOffline":
                if set(command) != {"command", "target", "offline"}:
                    raise ValueError("audioOffline requires target and offline")
                self.server.audio.set_offline(command["target"], command["offline"])
                response = self.server.simulation.get("status")[1]
            elif isinstance(command, dict) and command.get("command") == "audioFailNextWrite":
                if set(command) != {"command"}:
                    raise ValueError("audioFailNextWrite has no parameters")
                self.server.audio.fail_next_write()
                response = self.server.simulation.get("status")[1]
            else:
                response = self.server.simulation.command(command)
            self._response(200, response)
        except ControlError as error:
            self._response(error.http_status, {'error':str(error),'code':error.code,'state':self.server.simulation.control_state()})
        except (ValueError, TypeError, UnicodeDecodeError, TimeoutError) as error:
            self._response(400, {"error": str(error)})

    def log_message(self, format, *args):
        # Quiet by default: poll traffic otherwise hides useful start/stop output.
        pass


def main():
    parser = argparse.ArgumentParser(description="No5 simulation-only Pi HTTP gateway; no hardware access")
    parser.add_argument("--host", default="127.0.0.1", help="bind address; default loopback; LAN requires explicit --host")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--time-scale", type=float, default=1.0, help="0.1 to 50 simulation seconds per real second")
    parser.add_argument("--scenario", choices=VoyageEngine.SCENARIOS, default="figure8")
    parser.add_argument("--paused", action="store_true", help="compatibility alias; READY is the default")
    parser.add_argument("--autostart", action="store_true", help="explicitly start the voyage on launch")
    parser.add_argument("--weather-off", action="store_true", help="disable Open-Meteo requests / coordinate sharing")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535:
        parser.error("port must be from 0 to 65535")
    try:
        simulation = Simulation(time_scale=args.time_scale, scenario=args.scenario, running=args.autostart and not args.paused)
        if args.weather_off:
            simulation.settings["weatherEnabled"]=False
            simulation.weather.configure(False)
        server = SimulationServer((args.host, args.port), simulation)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    address, port = server.server_address
    print(f"No5 SIMULATION ONLY at http://{address}:{port}/", flush=True)
    print("Simulation controls only. Open-Meteo real model weather uses synthetic test coordinates; --weather-off disables sharing. No hardware writes. Ctrl+C stops.", flush=True)
    if args.host not in ("127.0.0.1", "localhost"):
        print("LAN binding explicitly enabled. Use only on a trusted development network; do not expose to the Internet.", flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

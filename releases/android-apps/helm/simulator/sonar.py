"""Canonical SONAR V1 simulator. Synthetic acoustic profiles, never sensor evidence."""
import copy
import json
import math
import statistics
import struct
import threading
import uuid
from .sonar_profiles import PROFILE_DEFAULTS, PROFILE_BOUNDS, PROFILE_CHOICES, generate_profile

BINS = 1024
MAX_HEADER = 4096
FORMAT = {"bins": BINS, "sampleType": "UINT16", "byteOrder": "LITTLE_ENDIAN"}
SOURCE = {"version": 1, "source": "NO5_PI_SIMULATOR", "sourceKind": "SIMULATED", "simulated": True}


def encode_ping(header, samples):
    if len(samples) != BINS or any(type(x) is not int or not 0 <= x <= 65535 for x in samples):
        raise ValueError("exactly 1024 UINT16 samples required")
    metadata = json.dumps(header, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if not 1 <= len(metadata) <= MAX_HEADER:
        raise ValueError("ping metadata exceeds limit")
    return struct.pack("<I", len(metadata)) + metadata + struct.pack("<1024H", *samples)


def decode_ping(data):
    """Test/reference decoder. Production Android independently validates semantics."""
    if len(data) < 4:
        raise ValueError("truncated ping")
    size = struct.unpack_from("<I", data)[0]
    if not 1 <= size <= MAX_HEADER or len(data) != 4 + size + BINS * 2:
        raise ValueError("invalid ping length")
    header = json.loads(data[4:4 + size].decode("utf-8"))
    return header, struct.unpack_from("<1024H", data, 4 + size)


class SonarSimulator:
    """One wall-clock producer; readers cannot advance or refresh the latest ping.

    Lock order is simulation.lock then self.lock. Snapshot emission never blocks
    acquisition on a client: latest is a single immutable binary-message slot.
    """
    def __init__(self, simulation, session_factory=lambda: uuid.uuid4().hex):
        self.simulation = simulation
        self.lock = threading.RLock()
        self.server_session_id = session_factory()
        self.generation = 1
        self.revision = 0
        self.calibration_revision = 1
        self.settings = {"cadenceHz": 10.0, "calibrated": True, "bottomValid": True,
                         "fault": "none", "targets": False, **PROFILE_DEFAULTS}
        self.last_ground_truth = None
        self.last_profile_diagnostics = None
        self.running = True
        self.run_id = simulation.run_id
        self.next_at = simulation.monotonic()
        self.scenario_started = self.next_at
        self.ping_id = -1
        self.latest = None
        self.observed_at = None
        self.last_requests = {}
        self.max_clients = 128

    def _calibration(self):
        return {"revision": self.calibration_revision, "valid": self.settings["calibrated"],
                "samplePeriodUs": 40.0, "soundSpeedMps": 1500.0,
                "depthReference": "TRANSDUCER", "transducerOffsetM": None, "keelOffsetM": None}

    def _state(self):
        fault = self.settings["fault"]
        return dict(SOURCE, kind="sonar.state", serverSessionId=self.server_session_id,
                    generation=self.generation, revision=self.revision,
                    publishedAtEpochMs=int(self.simulation.wall_epoch_ms()),
                    running=self.running and fault != "failed",
                    status="UNAVAILABLE" if fault == "failed" else "STALE" if fault == "stale" else "LIVE" if self.running else "IDLE",
                    capabilities={"canStart": fault != "failed", "canStop": fault != "failed", "bottomDetection": True},
                    format=FORMAT.copy(), calibration=self._calibration(), cadenceHz=self.settings["cadenceHz"],
                    lastPingId=self.ping_id if self.ping_id >= 0 else None,
                    observedAtEpochMs=self.observed_at, settings=self.settings.copy())

    def state(self):
        with self.lock:
            return self._state()

    def stream_snapshot(self):
        with self.lock:
            return self._state(), self.latest

    def _new_generation(self):
        self.generation += 1
        self.revision += 1
        self.latest = None
        self.ping_id = -1
        self.observed_at = None
        self.next_at = self.simulation.monotonic()
        self.scenario_started = self.next_at

    def _validate_settings(self, changes):
        if not isinstance(changes, dict) or not changes or set(changes) - set(self.settings):
            raise ValueError("unknown or empty SONAR settings")
        for key, value in changes.items():
            if key == "cadenceHz":
                if type(value) not in (int, float) or not math.isfinite(value) or not 1 <= value <= 20:
                    raise ValueError("cadenceHz must be a finite number from 1 to 20")
            elif key == "fault":
                if value not in ("none", "stale", "failed"):
                    raise ValueError("SONAR fault must be none, stale or failed")
            elif key in PROFILE_BOUNDS:
                low,high=PROFILE_BOUNDS[key]
                if type(value) not in (int,float) or not math.isfinite(value) or not low<=value<=high:
                    raise ValueError(key+' outside supported illustrative range')
            elif key in PROFILE_CHOICES:
                if value not in PROFILE_CHOICES[key]:raise ValueError('unknown '+key)
            elif type(value) is not bool:
                raise ValueError(key + " must be boolean")

    def configure(self, changes):
        self._validate_settings(changes)
        with self.lock:
            previous = self.settings.copy()
            self.settings.update(changes)
            if self.settings != previous:
                self.revision += 1
                if any(previous[k] != self.settings[k] for k in ("calibrated",*PROFILE_DEFAULTS)):
                    self.calibration_revision += 1
                if any(previous[k] != self.settings[k] for k in ("cadenceHz", "calibrated", "targets", *PROFILE_DEFAULTS)):
                    self._new_generation()
            return self._state()

    def configure_command(self, command):
        return self._command(command, configuring=True)

    def command(self, command):
        return self._command(command)

    def _command(self, command, configuring=False):
        required = {"version", "serverSessionId", "generation", "expectedRevision", "clientId", "requestId", "command"}
        if configuring:
            required.add("settings")
        if not isinstance(command, dict) or set(command) != required:
            raise ValueError("invalid SONAR command fields")
        if type(command["version"]) is not int or command["version"] != 1:
            raise ValueError("unsupported SONAR command version")
        for key in ("generation", "expectedRevision", "requestId"):
            if type(command[key]) is not int or not 0 <= command[key] <= 2**63 - 1:
                raise ValueError(key + " must be a nonnegative int64")
        for key in ("serverSessionId", "clientId"):
            value = command[key]
            if not isinstance(value, str) or not 1 <= len(value) <= 200 or any(ord(x) < 32 for x in value):
                raise ValueError("invalid " + key)
        if command["command"] not in (("configure",) if configuring else ("start", "stop")):
            raise ValueError("unsupported SONAR command")
        if configuring:
            self._validate_settings(command["settings"])
        with self.lock:
            client = command["clientId"]
            reason = None
            if command["serverSessionId"] != self.server_session_id or command["generation"] != self.generation:
                reason = "WRONG_SESSION"
            elif command["requestId"] <= self.last_requests.get(client, -1):
                reason = "STALE_REQUEST"
            elif client not in self.last_requests and len(self.last_requests) >= self.max_clients:
                reason = "CLIENT_LIMIT"
            else:
                # Consume even a rejected request so it cannot later become a valid write.
                self.last_requests[client] = command["requestId"]
                if command["expectedRevision"] != self.revision:
                    reason = "STALE_REVISION"
                elif self.settings["fault"] == "failed" and not configuring:
                    reason = "UNAVAILABLE"
                elif configuring:
                    self.configure(command["settings"])
                else:
                    requested = command["command"] == "start"
                    if requested != self.running:
                        self.running = requested
                        self.revision += 1
                        self.next_at = self.simulation.monotonic()
            return {"version": 1, "kind": "sonar.ack", "serverSessionId": self.server_session_id,
                    "generation": self.generation, "clientId": client, "requestId": command["requestId"],
                    "outcome": "REJECTED" if reason else "VERIFIED", "reason": reason, "state": self._state()}

    def sample(self):
        with self.simulation.lock:
            run_id = self.simulation.run_id
            s = self.simulation.engine.state
            depth = self.simulation.engine.synthetic_depth(s.east_m, s.north_m)
            east, north, elapsed = s.east_m, s.north_m, s.elapsed
            with self.lock:
                if run_id != self.run_id:
                    self.run_id = run_id
                    self._new_generation()
                now = self.simulation.monotonic()
                if not self.running or self.settings["fault"] != "none" or now < self.next_at:
                    return
                # No catch-up burst. Entire missed slots are visible as sequence gaps.
                period = 1.0 / self.settings["cadenceHz"]
                skipped = max(0, int((now - self.next_at) / period))
                self.ping_id += 1 + skipped
                self.next_at = now + period
                observed = int(self.simulation.wall_epoch_ms())
                # A backwards wall clock is a new clock segment, never a stale replay.
                if self.observed_at is not None and observed < self.observed_at:
                    self._new_generation()
                    self.ping_id = 0
                    self.next_at = now + period
                self.observed_at = observed
                bottom_bin = depth / .03  # 40 us × 1500 m/s / 2.
                valid_bottom = self.settings["bottomValid"] and 0 <= bottom_bin < BINS
                confidence = .94
                samples,self.last_ground_truth,self.last_profile_diagnostics = generate_profile(
                    depth,east,north,(elapsed if self.settings['scenario']=='navigation' else max(0,now-self.scenario_started)),self.ping_id,self.settings)
                if self.settings['scenario']!='navigation':
                    # Detect from the generated envelope. Scenario truth stays off the wire.
                    # M0 baseline: seven-bin smoothing with zero-padding, 0.65m
                    # blanking, post-blanking median/MAD and pseudo-SNR confidence.
                    # No truth depth is consulted for the detected bottom.
                    smoothed=[sum(samples[max(0,i-3):min(BINS,i+4)])/7 for i in range(BINS)]
                    blank=max(2,int(.65/.03))
                    noise=smoothed[blank:min(BINS,blank+max(32,BINS//12))]
                    floor=statistics.median(noise)
                    mad=statistics.median(abs(v-floor) for v in noise)+1
                    candidate=max(range(blank,BINS),key=smoothed.__getitem__)
                    snr=20*math.log10(max(1,smoothed[candidate]-floor)/max(1,1.4826*mad))
                    confidence=min(1,max(0,(snr-6)/24))
                    valid_bottom=self.settings['bottomValid'] and confidence>.1
                    bottom_bin=float(candidate)
                header = dict(SOURCE, kind="sonar.ping", serverSessionId=self.server_session_id,
                              generation=self.generation, pingId=self.ping_id,
                              observedAtEpochMs=observed, publishedAtEpochMs=observed,
                              format=FORMAT.copy(), calibration=self._calibration(),
                              bottom={"bin": bottom_bin if valid_bottom else None,
                                      "confidence": confidence if valid_bottom else None,
                                      "strength": max(samples)/65535 if valid_bottom else None},
                              simulation={"runId": run_id, "elapsedSeconds": elapsed})
                self.latest = encode_ping(header, samples)

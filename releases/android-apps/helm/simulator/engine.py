"""Deterministic illustrative voyage dynamics. No hardware or external services.

Units internally: metres, seconds, radians, litres. No claim of calibrated vessel
hydrodynamics, tank capacity, operating limits, weather or navigable water.
"""
from bisect import bisect_right
from dataclasses import dataclass
from decimal import Decimal
import math

from .config import DEFAULTS, validate_settings

KNOT = 1852.0 / 3600.0
EARTH_RADIUS_M = 6_371_008.8
SIMULATION_EPOCH_MS = 1_790_996_400_000
INITIAL_FUEL_L = 420.0
TANK_CAPACITY_L = 250.0  # Each tank: a simulator constant, not No5 hardware data.


def clamp(value, low, high):
    return max(low, min(high, value))


def true_bearing(east, north):
    return math.degrees(math.atan2(east, north)) % 360.0


def position_from_local(east, north, latitude=-34.3, longitude=18.6):
    """Map the small local trajectory to a spherical Earth via direct geodesic."""
    angle = math.hypot(east, north) / EARTH_RADIUS_M
    bearing = math.atan2(east, north)
    lat = math.radians(latitude)
    lng = math.radians(longitude)
    out_lat = math.asin(math.sin(lat) * math.cos(angle) + math.cos(lat) * math.sin(angle) * math.cos(bearing))
    out_lng = lng + math.atan2(math.sin(bearing) * math.sin(angle) * math.cos(lat), math.cos(angle) - math.sin(lat) * math.sin(out_lat))
    return math.degrees(out_lat), (math.degrees(out_lng) + 180.0) % 360.0 - 180.0


class FigureEight:
    """Smooth Gerono figure eight, traversed by arc length rather than angle.

    x=260*sin(u), y=145*sin(2u); an 8192-cell lookup inverts integrated arc
    length. Position, tangent and curvature all come from this same curve.
    """
    A, B, CELLS = 260.0, 145.0, 8192

    def __init__(self, scale=1.0):
        self.A, self.B = 260.0 * scale, 145.0 * scale
        self.du = math.tau / self.CELLS
        self.arc = [0.0]
        previous = self._norm(0.0)
        for index in range(1, self.CELLS + 1):
            current = self._norm(index * self.du)
            self.arc.append(self.arc[-1] + (previous + current) * self.du / 2)
            previous = current
        self.length = self.arc[-1]
        length = self._norm(0.0)
        self.tangent = self.A / length, 2 * self.B / length

    def _norm(self, u):
        return math.hypot(self.A * math.cos(u), 2 * self.B * math.cos(2 * u))

    def at(self, distance):
        distance = clamp(distance, 0.0, self.length)
        cell = min(self.CELLS - 1, max(0, bisect_right(self.arc, distance) - 1))
        fraction = (distance - self.arc[cell]) / (self.arc[cell + 1] - self.arc[cell])
        u = (cell + fraction) * self.du
        east, north = self.A * math.sin(u), self.B * math.sin(2 * u)
        de, dn = self.A * math.cos(u), 2 * self.B * math.cos(2 * u)
        dde, ddn = -self.A * math.sin(u), -4 * self.B * math.sin(2 * u)
        curvature = (dn * dde - de * ddn) / math.hypot(de, dn) ** 3
        return east, north, true_bearing(de, dn), curvature


@dataclass
class VoyageState:
    elapsed: float = 0.0
    phase: str = "STOPPED"
    phase_elapsed: float = 0.0
    speed_mps: float = 0.0
    distance_m: float = 0.0
    figure_distance_m: float = 0.0
    entry_distance_m: float = 0.0
    decel_initial_mps: float = 0.0
    decel_distance_m: float = 0.0
    east_m: float = 0.0
    north_m: float = 0.0
    heading_degrees: float = true_bearing(260, 290)
    turn_rate_degrees_s: float = 0.0
    port_rpm: float = 0.0
    starboard_rpm: float = 0.0
    port_flow_lph: float = 0.0
    starboard_flow_lph: float = 0.0
    port_fuel_l: float = INITIAL_FUEL_L / 2
    starboard_fuel_l: float = INITIAL_FUEL_L / 2
    port_coolant_c: float = 19.0
    starboard_coolant_c: float = 19.0
    throttle: float = 0.0
    gear: str = "N"
    moving_seconds: float = 0.0


class VoyageEngine:
    """Fixed-step model. Caller controls time; no clock or IO hidden here.

    advance(a); advance(b) and advance(a+b) produce the same states. Sub-step
    elapsed time is retained, never lost; observable model time is quantized to
    DT. Thus HTTP polling frequency cannot change fuel, position or dynamics.
    """
    DT = 0.05
    SCENARIOS = ("figure8", "idle")

    def __init__(self, scenario="figure8", settings=None):
        self.settings = validate_settings(dict(DEFAULTS, scenario=scenario) if settings is None else settings)
        self.route = FigureEight(self.settings['routeScale'])
        self.reset(scenario)

    def configure(self, settings, reset=False):
        self.settings = validate_settings(settings)
        if reset:
            self.route = FigureEight(self.settings['routeScale'])
            self.reset(self.settings['scenario'])
        else:
            self.set_trim(self.settings['portTrimPercent'], self.settings['starboardTrimPercent'])

    def stop(self):
        s = self.state
        s.speed_mps = s.port_rpm = s.starboard_rpm = 0.0
        s.port_flow_lph = s.starboard_flow_lph = s.throttle = s.turn_rate_degrees_s = 0.0
        s.gear = 'N' 

    def reset(self, scenario=None):
        selected = scenario or getattr(self, "scenario", "figure8")
        if selected not in self.SCENARIOS:
            raise ValueError("scenario must be figure8 or idle")
        self.scenario = selected
        self.state = VoyageState()
        self.port_trim = self.settings['portTrimPercent']
        self.starboard_trim = self.settings['starboardTrimPercent']
        self.state.port_fuel_l = self.settings['portInitialFuelLiters']
        self.state.starboard_fuel_l = self.settings['starboardInitialFuelLiters']
        self.initial_fuel_l = self.state.port_fuel_l + self.state.starboard_fuel_l
        if self.settings['mode'] == 'manual':
            self.state.phase = 'MANUAL'
            self.state.heading_degrees = self.settings['manualHeadingDegrees']
        self._requested_seconds = Decimal(0)
        self._step_count = 0

    def set_trim(self, port=None, starboard=None):
        for value in (port, starboard):
            if value is not None and (isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not 0 <= value <= 100):
                raise ValueError("trim must be a finite percent from 0 to 100")
        if port is not None:
            self.port_trim = float(port)
        if starboard is not None:
            self.starboard_trim = float(starboard)

    def advance(self, seconds, stop_at_complete=False):
        if isinstance(seconds, bool) or not isinstance(seconds, (float, int)) or not math.isfinite(seconds) or not 0 <= seconds <= 86_400:
            raise ValueError("advance must be finite seconds from 0 to 86400")
        self._requested_seconds += Decimal(str(seconds))
        # Decimal accumulation prevents repeated 100 ms polls from eventually
        # losing a whole 50 ms step through binary floating-point summation.
        steps = int((self._requested_seconds + Decimal("0.0000000001")) / Decimal(str(self.DT)))
        while self._step_count < steps:
            if self.state.phase == "COMPLETE" or (self.scenario == "idle" and self.state.phase == "STOPPED"):
                # Once stationary, an overnight client gap must not replay a
                # million identical no-motion steps on the HTTP request thread.
                # This is the closed form of the same discrete cooling rule.
                count = steps - self._step_count
                cooling = (1.0 - self.DT / 180) ** count
                self.state.port_coolant_c = 19.0 + (self.state.port_coolant_c - 19.0) * cooling
                self.state.starboard_coolant_c = self.state.port_coolant_c
                self.state.phase_elapsed += count * self.DT
                self._step_count = steps
                self.state.elapsed = steps * self.DT
                break
            self._step_count += 1
            self._step()
            if stop_at_complete and self.state.phase == "COMPLETE":
                break
        return self.state

    @staticmethod
    def trim_factor(trim):
        # Explicitly illustrative. High/low trim reduces speed and efficiency.
        return 1.0 - 0.32 * ((trim - 35.0) / 65.0) ** 2

    def _change_phase(self, phase):
        s = self.state
        s.phase, s.phase_elapsed = phase, 0.0
        if phase == "FIGURE_EIGHT":
            s.entry_distance_m = s.distance_m
        elif phase == "DECELERATE":
            s.decel_initial_mps = s.speed_mps
        elif phase == "COMPLETE":
            s.speed_mps = 0.0

    def _step(self):
        s, dt = self.state, self.DT
        if self.scenario == "figure8" and self.settings["mode"] == "scenario":
            if s.phase == "STOPPED" and s.phase_elapsed >= self.settings["standingSeconds"] - 1e-8:
                self._change_phase("FULL_THROTTLE_LAUNCH")
            elif s.phase == "FULL_THROTTLE_LAUNCH" and s.phase_elapsed >= self.settings["launchSeconds"] - 1e-8:
                self._change_phase("CRUISE")
            elif s.phase == "CRUISE" and s.phase_elapsed >= self.settings["cruiseSeconds"] - 1e-8:
                self._change_phase("FIGURE_EIGHT")
            elif s.phase == "FIGURE_EIGHT" and s.figure_distance_m >= self.route.length - 1e-8:
                self._change_phase("DECELERATE")
            elif s.phase == "DECELERATE" and s.phase_elapsed >= self.settings["decelerateSeconds"] - 1e-8:
                self._change_phase("COMPLETE")
        old_speed, old_port_flow, old_starboard_flow = s.speed_mps, s.port_flow_lph, s.starboard_flow_lph
        factor = (self.trim_factor(self.port_trim) + self.trim_factor(self.starboard_trim)) / 2
        moving = s.phase not in ("STOPPED", "COMPLETE")
        if s.phase == "MANUAL":
            target = self.settings['manualSpeedKn'] * KNOT * factor
            throttle = min(1.0, self.settings['manualSpeedKn'] / 30.0)
        elif s.phase == "FULL_THROTTLE_LAUNCH":
            throttle, target = 1.0, self.settings["launchSpeedKn"] * KNOT * factor
        elif s.phase == "CRUISE":
            throttle, target = 0.74, self.settings["cruiseSpeedKn"] * KNOT * factor
        elif s.phase == "FIGURE_EIGHT":
            throttle = 0.65 + 0.14 * math.sin(s.phase_elapsed / 11)
            target = (19.0 + 5.0 * math.sin(s.phase_elapsed / 11)) * KNOT * factor
            # Illustrative comfort turn limit; route geometry determines curvature.
            curvature = abs(self.route.at(s.figure_distance_m)[3])
            target = min(target, math.sqrt(1.35 / max(curvature, 1e-8)))
        elif s.phase == "DECELERATE":
            fraction = max(0.0, 1 - (s.phase_elapsed + dt) / self.settings["decelerateSeconds"])
            throttle, target = 0.45 * fraction, s.decel_initial_mps * fraction
        else:
            throttle, target = 0.0, 0.0
        if s.port_fuel_l <= 0 and s.starboard_fuel_l <= 0:
            target, throttle = 0.0, 0.0
        target *= ((1 if s.port_fuel_l > 0 else 0) + (1 if s.starboard_fuel_l > 0 else 0)) / 2
        moving = moving and (s.port_fuel_l > 0 or s.starboard_fuel_l > 0)
        s.throttle, s.gear = throttle, "F" if moving else "N"
        if s.phase == "DECELERATE":
            s.speed_mps = target
        else:
            acceleration = clamp((target - old_speed) / 3.5, -self.settings["decelerationMps2"], self.settings["accelerationMps2"])
            s.speed_mps = max(0.0, old_speed + acceleration * dt)
        travelled = (old_speed + s.speed_mps) * dt / 2
        s.distance_m += travelled
        if travelled > 0:
            s.moving_seconds += dt
        te, tn = self.route.tangent
        if s.phase == 'MANUAL':
            delta = (self.settings['manualHeadingDegrees'] - s.heading_degrees + 180) % 360 - 180
            turn = clamp(delta, -12.0 * dt, 12.0 * dt)
            s.heading_degrees = (s.heading_degrees + turn) % 360
            s.turn_rate_degrees_s = turn / dt
            s.east_m += travelled * math.sin(math.radians(s.heading_degrees))
            s.north_m += travelled * math.cos(math.radians(s.heading_degrees))
        elif s.phase == "FIGURE_EIGHT":
            next_arc = s.figure_distance_m + travelled
            excess = max(0.0, next_arc - self.route.length)
            s.figure_distance_m = min(self.route.length, next_arc)
            s.decel_distance_m += excess
            e, n, heading, curvature = self.route.at(s.figure_distance_m)
            s.east_m = te * (s.entry_distance_m + s.decel_distance_m) + e
            s.north_m = tn * (s.entry_distance_m + s.decel_distance_m) + n
            s.heading_degrees = heading
            s.turn_rate_degrees_s = math.degrees(curvature * s.speed_mps)
        elif s.phase == "DECELERATE" or s.phase == "COMPLETE":
            s.decel_distance_m += travelled
            s.east_m = te * (s.entry_distance_m + s.decel_distance_m)
            s.north_m = tn * (s.entry_distance_m + s.decel_distance_m)
            s.heading_degrees = true_bearing(te, tn)
            s.turn_rate_degrees_s = 0.0
        else:
            s.east_m, s.north_m = te * s.distance_m, tn * s.distance_m
            s.turn_rate_degrees_s = 0.0
        rpm_target = 700 + 5600 * throttle if moving else 0.0
        # Matching twin engines; no unmodeled prop/steering asymmetry implied.
        s.port_rpm += (rpm_target - s.port_rpm) * min(1.0, dt / 0.6)
        s.starboard_rpm += (rpm_target - s.starboard_rpm) * min(1.0, dt / 0.6)
        if not moving:
            s.port_rpm = s.starboard_rpm = 0.0
        def flow(trim, rpm):
            return (2.0 + 0.000000000245 * rpm ** 3) / self.trim_factor(trim) if moving else 0.0
        s.port_flow_lph, s.starboard_flow_lph = flow(self.port_trim, s.port_rpm), flow(self.starboard_trim, s.starboard_rpm)
        if s.port_fuel_l <= 0:
            s.port_flow_lph = s.port_rpm = 0.0
        if s.starboard_fuel_l <= 0:
            s.starboard_flow_lph = s.starboard_rpm = 0.0
        s.port_fuel_l = max(0.0, s.port_fuel_l - (old_port_flow + s.port_flow_lph) * dt / 7200)
        s.starboard_fuel_l = max(0.0, s.starboard_fuel_l - (old_starboard_flow + s.starboard_flow_lph) * dt / 7200)
        target_coolant = 73.0 + 12.0 * throttle if moving else 19.0
        s.port_coolant_c += (target_coolant - s.port_coolant_c) * dt / (65 if moving else 180)
        s.starboard_coolant_c = s.port_coolant_c
        s.elapsed = self._step_count * dt
        s.phase_elapsed += dt

    @staticmethod
    def synthetic_depth(east_m, north_m):
        """Illustrative smooth seabed in local coordinates, never navigation/sensor evidence."""
        return 12.0 + 3.5 * math.sin(east_m / 180.0) + 2.0 * math.sin(north_m / 140.0)

    def helm_values(self):
        s = self.state
        lat, lng = position_from_local(s.east_m, s.north_m)
        engine_on = s.port_rpm > 0
        oil = 1.0 + 3.7 * (s.port_rpm / 6300) if engine_on else 0.0
        return {
            "portRpm": s.port_rpm, "starboardRpm": s.starboard_rpm,
            "portTrim": self.port_trim, "starboardTrim": self.starboard_trim,
            "heading": s.heading_degrees, "speed": s.speed_mps / KNOT,
            "depth": self.synthetic_depth(s.east_m, s.north_m), "waterTemp": None,
            "portVoltage": 14.2 if engine_on else 12.6, "starboardVoltage": 14.2 if engine_on else 12.6,
            "houseVoltage": 12.7,
            "portFuel": s.port_fuel_l / TANK_CAPACITY_L * 100,
            "starboardFuel": s.starboard_fuel_l / TANK_CAPACITY_L * 100,
            "portCoolantC": s.port_coolant_c, "starboardCoolantC": s.starboard_coolant_c,
            "portOilBar": oil, "starboardOilBar": oil,
            "portFuelFlowLph": s.port_flow_lph, "starboardFuelFlowLph": s.starboard_flow_lph,
            "totalFuelFlowLph": s.port_flow_lph + s.starboard_flow_lph,
            "portGear": s.gear, "starboardGear": s.gear,
            "latitudeDegrees": lat, "longitudeDegrees": lng,
            "fuelRemainingLiters": s.port_fuel_l + s.starboard_fuel_l,
            "courseOverGroundDegrees": s.heading_degrees if s.speed_mps > 0.05 else None,
        }

    def extras(self):
        s = self.state
        used = self.initial_fuel_l - s.port_fuel_l - s.starboard_fuel_l
        distance_nm = s.distance_m / 1852
        return {
            "tripDistanceNm": distance_nm, "tripDurationSeconds": s.moving_seconds,
            "fuelUsedLiters": used, "efficiencyNmPerLiter": distance_nm / used if used > 0.001 else None,
            "turnRateDegreesPerSecond": s.turn_rate_degrees_s, "throttlePercent": s.throttle * 100,
        }

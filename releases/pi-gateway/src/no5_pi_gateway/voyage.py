"""Deterministic voyage model ported from the existing No5 HELM simulator.

This is deliberately an illustrative display model, never a navigational,
engine, tank or weather measurement.  It preserves the existing Figure-Eight
route, phase sequence, fixed 50 ms integration, twin-engine/fuel dynamics and
geodesic projection so the Pi overlay is not a weaker replacement simulator.
"""

from __future__ import annotations

from bisect import bisect_right
from dataclasses import dataclass
from decimal import Decimal
import math
from typing import Any


KNOT = 1852.0 / 3600.0
EARTH_RADIUS_M = 6_371_008.8
TANK_CAPACITY_L = 250.0

DEFAULT_VOYAGE_SETTINGS: dict[str, object] = {
    "running": True,
    "timeScale": 1.0,
    "scenario": "figure8",
    "standingSeconds": 8.0,
    "launchSeconds": 22.0,
    "cruiseSeconds": 25.0,
    "decelerateSeconds": 24.0,
    "routeScale": 1.0,
    "launchSpeedKn": 30.0,
    "cruiseSpeedKn": 24.0,
    "accelerationMps2": 0.9,
    "decelerationMps2": 1.2,
    "portTrimPercent": 35.0,
    "starboardTrimPercent": 35.0,
    "portInitialFuelLiters": 210.0,
    "starboardInitialFuelLiters": 210.0,
}

_RANGES: dict[str, tuple[float, float]] = {
    "timeScale": (0.1, 50.0), "standingSeconds": (0.0, 120.0),
    "launchSeconds": (1.0, 180.0), "cruiseSeconds": (1.0, 600.0),
    "decelerateSeconds": (1.0, 120.0), "routeScale": (0.25, 4.0),
    "launchSpeedKn": (1.0, 45.0), "cruiseSpeedKn": (1.0, 35.0),
    "accelerationMps2": (0.1, 2.0), "decelerationMps2": (0.1, 3.0),
    "portTrimPercent": (0.0, 100.0), "starboardTrimPercent": (0.0, 100.0),
    "portInitialFuelLiters": (0.0, TANK_CAPACITY_L),
    "starboardInitialFuelLiters": (0.0, TANK_CAPACITY_L),
}


def _number(value: object, name: str, lower: float, upper: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"voyage.{name} must be numeric")
    result = float(value)
    if not math.isfinite(result) or not lower <= result <= upper:
        raise ValueError(f"voyage.{name} must be within {lower}..{upper}")
    return result


def validate_voyage_settings(value: object) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != set(DEFAULT_VOYAGE_SETTINGS):
        raise ValueError("voyage settings must contain every supported setting exactly once")
    if not isinstance(value["running"], bool):
        raise ValueError("voyage.running must be boolean")
    if value["scenario"] not in {"figure8", "idle"}:
        raise ValueError("voyage.scenario must be figure8 or idle")
    validated = {"running": value["running"], "scenario": value["scenario"]}
    for name, (lower, upper) in _RANGES.items():
        validated[name] = _number(value[name], name, lower, upper)
    return validated


def _bearing(east: float, north: float) -> float:
    return math.degrees(math.atan2(east, north)) % 360.0


def _position(east: float, north: float, latitude: float = -34.3, longitude: float = 18.6) -> tuple[float, float]:
    angle = math.hypot(east, north) / EARTH_RADIUS_M
    bearing = math.atan2(east, north)
    lat = math.radians(latitude)
    lon = math.radians(longitude)
    out_lat = math.asin(math.sin(lat) * math.cos(angle) + math.cos(lat) * math.sin(angle) * math.cos(bearing))
    out_lon = lon + math.atan2(math.sin(bearing) * math.sin(angle) * math.cos(lat),
                               math.cos(angle) - math.sin(lat) * math.sin(out_lat))
    return math.degrees(out_lat), (math.degrees(out_lon) + 180.0) % 360.0 - 180.0


class _FigureEight:
    """The existing Gerono course, traversed by arc length."""

    CELLS = 8192

    def __init__(self, scale: float) -> None:
        self.a, self.b = 260.0 * scale, 145.0 * scale
        self._du = math.tau / self.CELLS
        self._arc = [0.0]
        previous = self._norm(0.0)
        for index in range(1, self.CELLS + 1):
            current = self._norm(index * self._du)
            self._arc.append(self._arc[-1] + (previous + current) * self._du / 2)
            previous = current
        self.length = self._arc[-1]
        normal = self._norm(0.0)
        self.tangent = self.a / normal, 2.0 * self.b / normal

    def _norm(self, angle: float) -> float:
        return math.hypot(self.a * math.cos(angle), 2.0 * self.b * math.cos(2.0 * angle))

    def at(self, distance: float) -> tuple[float, float, float, float]:
        distance = max(0.0, min(self.length, distance))
        cell = min(self.CELLS - 1, max(0, bisect_right(self._arc, distance) - 1))
        fraction = (distance - self._arc[cell]) / (self._arc[cell + 1] - self._arc[cell])
        angle = (cell + fraction) * self._du
        east, north = self.a * math.sin(angle), self.b * math.sin(2.0 * angle)
        de, dn = self.a * math.cos(angle), 2.0 * self.b * math.cos(2.0 * angle)
        dde, ddn = -self.a * math.sin(angle), -4.0 * self.b * math.sin(2.0 * angle)
        curvature = (dn * dde - de * ddn) / math.hypot(de, dn) ** 3
        return east, north, _bearing(de, dn), curvature


@dataclass(slots=True)
class _State:
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
    heading: float = _bearing(260.0, 290.0)
    port_rpm: float = 0.0
    starboard_rpm: float = 0.0
    port_flow: float = 0.0
    starboard_flow: float = 0.0
    port_fuel: float = 105.0
    starboard_fuel: float = 105.0
    coolant: float = 19.0
    throttle: float = 0.0
    moving_seconds: float = 0.0


class VoyageEngine:
    """Port of the HELM simulator's deterministic phase and vessel dynamics."""

    DT = 0.05

    def __init__(self, settings: dict[str, object] | None = None) -> None:
        self.settings = validate_voyage_settings(settings or DEFAULT_VOYAGE_SETTINGS)
        self._route = _FigureEight(float(self.settings["routeScale"]))
        self.reset()

    def reset(self) -> None:
        self.state = _State(
            port_fuel=float(self.settings["portInitialFuelLiters"]),
            starboard_fuel=float(self.settings["starboardInitialFuelLiters"]),
        )
        self._initial_fuel = self.state.port_fuel + self.state.starboard_fuel
        self._requested = Decimal(0)
        self._steps = 0

    def configure(self, settings: dict[str, object]) -> None:
        candidate = validate_voyage_settings(settings)
        reset_fields = {"scenario", "routeScale", "portInitialFuelLiters", "starboardInitialFuelLiters"}
        reset = any(candidate[name] != self.settings[name] for name in reset_fields)
        self.settings = candidate
        if reset:
            self._route = _FigureEight(float(candidate["routeScale"]))
            self.reset()

    @staticmethod
    def _trim_factor(trim: float) -> float:
        return 1.0 - 0.32 * ((trim - 35.0) / 65.0) ** 2

    def advance(self, seconds: float) -> None:
        if seconds < 0 or not math.isfinite(seconds):
            raise ValueError("voyage advance must be a finite positive duration")
        self._requested += Decimal(str(seconds))
        target_steps = int((self._requested + Decimal("0.0000000001")) / Decimal(str(self.DT)))
        while self._steps < target_steps:
            self._steps += 1
            self._step()

    def _phase(self, phase: str) -> None:
        self.state.phase, self.state.phase_elapsed = phase, 0.0
        if phase == "FIGURE_EIGHT":
            self.state.entry_distance_m = self.state.distance_m
        elif phase == "DECELERATE":
            self.state.decel_initial_mps = self.state.speed_mps
        elif phase == "COMPLETE":
            self.state.speed_mps = 0.0

    def _step(self) -> None:
        s, dt, setting = self.state, self.DT, self.settings
        if setting["scenario"] == "figure8":
            if s.phase == "STOPPED" and s.phase_elapsed >= float(setting["standingSeconds"]):
                self._phase("FULL_THROTTLE_LAUNCH")
            elif s.phase == "FULL_THROTTLE_LAUNCH" and s.phase_elapsed >= float(setting["launchSeconds"]):
                self._phase("CRUISE")
            elif s.phase == "CRUISE" and s.phase_elapsed >= float(setting["cruiseSeconds"]):
                self._phase("FIGURE_EIGHT")
            elif s.phase == "FIGURE_EIGHT" and s.figure_distance_m >= self._route.length:
                self._phase("DECELERATE")
            elif s.phase == "DECELERATE" and s.phase_elapsed >= float(setting["decelerateSeconds"]):
                self._phase("COMPLETE")
        old_speed, old_port_flow, old_starboard_flow = s.speed_mps, s.port_flow, s.starboard_flow
        trim = (self._trim_factor(float(setting["portTrimPercent"])) +
                self._trim_factor(float(setting["starboardTrimPercent"]))) / 2.0
        if s.phase == "FULL_THROTTLE_LAUNCH":
            throttle, target = 1.0, float(setting["launchSpeedKn"]) * KNOT * trim
        elif s.phase == "CRUISE":
            throttle, target = 0.74, float(setting["cruiseSpeedKn"]) * KNOT * trim
        elif s.phase == "FIGURE_EIGHT":
            throttle = 0.65 + 0.14 * math.sin(s.phase_elapsed / 11.0)
            target = (19.0 + 5.0 * math.sin(s.phase_elapsed / 11.0)) * KNOT * trim
            target = min(target, math.sqrt(1.35 / max(abs(self._route.at(s.figure_distance_m)[3]), 1e-8)))
        elif s.phase == "DECELERATE":
            fraction = max(0.0, 1.0 - (s.phase_elapsed + dt) / float(setting["decelerateSeconds"]))
            throttle, target = 0.45 * fraction, s.decel_initial_mps * fraction
        else:
            throttle, target = 0.0, 0.0
        fueled = (int(s.port_fuel > 0) + int(s.starboard_fuel > 0)) / 2.0
        target *= fueled
        moving = s.phase not in {"STOPPED", "COMPLETE"} and fueled > 0
        s.throttle = throttle if moving else 0.0
        acceleration = max(-float(setting["decelerationMps2"]),
                           min(float(setting["accelerationMps2"]), (target - old_speed) / 3.5))
        s.speed_mps = target if s.phase == "DECELERATE" else max(0.0, old_speed + acceleration * dt)
        travelled = (old_speed + s.speed_mps) * dt / 2.0
        s.distance_m += travelled
        if travelled > 0:
            s.moving_seconds += dt
        tangent_east, tangent_north = self._route.tangent
        if s.phase == "FIGURE_EIGHT":
            next_arc = s.figure_distance_m + travelled
            s.figure_distance_m = min(self._route.length, next_arc)
            s.decel_distance_m += max(0.0, next_arc - self._route.length)
            east, north, s.heading, _curve = self._route.at(s.figure_distance_m)
            s.east_m = tangent_east * (s.entry_distance_m + s.decel_distance_m) + east
            s.north_m = tangent_north * (s.entry_distance_m + s.decel_distance_m) + north
        else:
            s.east_m, s.north_m = tangent_east * s.distance_m, tangent_north * s.distance_m
            s.heading = _bearing(tangent_east, tangent_north)
        rpm_target = 700.0 + 5600.0 * s.throttle if moving else 0.0
        s.port_rpm += (rpm_target - s.port_rpm) * min(1.0, dt / 0.6)
        s.starboard_rpm = s.port_rpm
        if not moving:
            s.port_rpm = s.starboard_rpm = 0.0
        def flow(trim_value: float, rpm: float) -> float:
            return (2.0 + 0.000000000245 * rpm ** 3) / self._trim_factor(trim_value) if moving else 0.0
        s.port_flow = flow(float(setting["portTrimPercent"]), s.port_rpm)
        s.starboard_flow = flow(float(setting["starboardTrimPercent"]), s.starboard_rpm)
        s.port_fuel = max(0.0, s.port_fuel - (old_port_flow + s.port_flow) * dt / 7200.0)
        s.starboard_fuel = max(0.0, s.starboard_fuel - (old_starboard_flow + s.starboard_flow) * dt / 7200.0)
        target_coolant = 73.0 + 12.0 * s.throttle if moving else 19.0
        s.coolant += (target_coolant - s.coolant) * dt / (65.0 if moving else 180.0)
        s.elapsed = self._steps * dt
        s.phase_elapsed += dt

    def helm_values(self) -> dict[str, object]:
        s, setting = self.state, self.settings
        latitude, longitude = _position(s.east_m, s.north_m)
        engine_on = s.port_rpm > 0
        oil = 1.0 + 3.7 * (s.port_rpm / 6300.0) if engine_on else 0.0
        # The original simulator left water temperature unavailable. For the
        # selectable-overlay service every V1 field has a deterministic
        # generated value; it remains explicitly simulated at the envelope.
        water_temp = 15.0 + 1.8 * math.sin(s.elapsed / 140.0)
        return {
            "portRpm": s.port_rpm, "starboardRpm": s.starboard_rpm,
            "portTrim": float(setting["portTrimPercent"]), "starboardTrim": float(setting["starboardTrimPercent"]),
            "portFuel": s.port_fuel / TANK_CAPACITY_L * 100.0, "starboardFuel": s.starboard_fuel / TANK_CAPACITY_L * 100.0,
            "heading": s.heading, "speed": s.speed_mps / KNOT,
            "depth": 12.0 + 3.5 * math.sin(s.east_m / 180.0) + 2.0 * math.sin(s.north_m / 140.0),
            "waterTemp": water_temp, "portVoltage": 14.2 if engine_on else 12.6,
            "starboardVoltage": 14.2 if engine_on else 12.6, "houseVoltage": 12.7,
            "portCoolantC": s.coolant, "starboardCoolantC": s.coolant,
            "portOilBar": oil, "starboardOilBar": oil,
            "portFuelFlowLph": s.port_flow, "starboardFuelFlowLph": s.starboard_flow,
            "totalFuelFlowLph": s.port_flow + s.starboard_flow,
            "portGear": "F" if engine_on else "N", "starboardGear": "F" if engine_on else "N",
            "latitudeDegrees": latitude, "longitudeDegrees": longitude,
            "fuelRemainingLiters": s.port_fuel + s.starboard_fuel,
            "courseOverGroundDegrees": s.heading,
        }

    def extras(self) -> dict[str, float]:
        s = self.state
        used = self._initial_fuel - s.port_fuel - s.starboard_fuel
        distance_nm = s.distance_m / 1852.0
        return {
            "tripDistanceNm": distance_nm, "tripDurationSeconds": s.moving_seconds,
            "fuelUsedLiters": used, "efficiencyNmPerLiter": distance_nm / used if used > 0.001 else 0.0,
        }

    def weather_values(self) -> dict[str, float]:
        """A deterministic voyage-coupled weather scene, not a forecast."""

        s = self.state
        latitude, longitude = _position(s.east_m, s.north_m)
        return {
            "airTemperatureC": 18.0 + 2.4 * math.sin(s.elapsed / 320.0),
            "windSpeedKn": 12.0 + 3.0 * math.sin(s.elapsed / 95.0),
            "windDirectionDegrees": (147.0 + 18.0 * math.sin(s.elapsed / 180.0)) % 360.0,
            "relativeHumidityPercent": 68.0 + 9.0 * math.sin(s.elapsed / 210.0 + 0.6),
            "pressureHpa": 1014.0 + 4.0 * math.sin(s.elapsed / 420.0),
            "latitudeDegrees": latitude, "longitudeDegrees": longitude,
            "cloudCoverPercent": 28.0 + 22.0 * math.sin(s.elapsed / 260.0 + 1.1),
            "visibilityMeters": 28_000.0 + 6_000.0 * math.sin(s.elapsed / 240.0),
        }

    def metadata(self) -> dict[str, object]:
        return {
            "elapsedSeconds": self.state.elapsed,
            "running": self.settings["running"],
            "timeScale": self.settings["timeScale"],
            "phase": self.state.phase,
            "scenario": self.settings["scenario"],
        }

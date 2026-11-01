"""Stateless horizontal consensus of explicitly commissioned independent receivers.

No receipt clock, accuracy estimate, motion, source priority or Signal K write.
Thresholds are operational agreement gates, not a claimed error bound.
"""
from __future__ import annotations

import math

from .configuration import SignalKConfig

METHOD = "EQUAL_WEIGHT_SPHERICAL_V1"
EARTH_RADIUS_METRES = 6_371_008.8
FIX_METHODS = {"GNSS Fix", "DGNSS fix", "Precise GNSS", "RTK fixed integer", "RTK Fixed Integer", "RTK float"}


def _eligible(source: dict, now: int, config: SignalKConfig) -> str | None:
    stamp = source["observedAtEpochMs"]
    if source["status"] != "LIVE" or source["position"] is None or stamp is None or not 0 <= now - stamp <= config.combination_max_age_ms:
        return "NO_FRESH_POSITION"
    quality = source["gnss"]
    method = quality.get("methodQuality")
    if not method or method["status"] != "LIVE" or method["value"] not in FIX_METHODS:
        return "NO_VALID_FIX_QUALITY"
    if not 0 <= now - method["observedAtEpochMs"] <= config.combination_max_age_ms:
        return "NO_FRESH_FIX_QUALITY"
    if abs(method["observedAtEpochMs"] - stamp) > config.combination_fix_quality_skew_ms:
        return "FIX_QUALITY_TIME_MISMATCH"
    integrity = quality.get("integrity")
    if integrity and (integrity["status"] == "INVALID" or
                      integrity["value"] == "Unsafe" and integrity["observedAtEpochMs"] >= stamp):
        return "UNSAFE_FIX"
    return None


def _vector(position: dict) -> tuple[float, float, float]:
    lat, lon = map(math.radians, (position["latitudeDegrees"], position["longitudeDegrees"]))
    return math.cos(lat) * math.cos(lon), math.cos(lat) * math.sin(lon), math.sin(lat)


def separation(a: dict, b: dict) -> float:
    # atan2 avoids acos precision loss for metre-scale distances.
    u, v = _vector(a), _vector(b)
    cross = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
    return EARTH_RADIUS_METRES * math.atan2(math.hypot(*cross), sum(x*y for x, y in zip(u, v)))


def combine(sources: list[dict], config: SignalKConfig, now: int, *, truncated: bool = False) -> dict:
    if not config.combination_sources:
        return {"available": False, "reason": "COMBINATION_NOT_CONFIGURED"}
    result = {"available": True, "version": 1, "method": METHOD, "mode": "NONE",
              "status": "UNAVAILABLE", "reason": "NO_USABLE_SOURCE", "position": None,
              "observedAtEpochMs": None, "contributors": [], "excluded": [],
              "timeSkewMs": None, "separationMetres": None}
    by_id = {s["sourceId"]: s for s in sources}
    usable = []
    for ref in sorted(config.combination_sources):
        source = by_id.get(ref)
        reason = ("SOURCE_UNAVAILABLE" if source is None else
                  "UNVERIFIED_FIX_CLOCK" if ref not in config.combination_verified_fix_clock_sources else
                  _eligible(source, now, config))
        if reason:
            result["excluded"].append({"sourceId": ref, "reason": reason})
        else:
            usable.append(source)
    if truncated:
        result["reason"] = "SOURCE_CATALOG_TRUNCATED"
        return result
    result["contributors"] = [{"sourceId": s["sourceId"], "observedAtEpochMs": s["observedAtEpochMs"]} for s in usable]
    if not usable:
        return result
    stamps = [s["observedAtEpochMs"] for s in usable]
    result["observedAtEpochMs"] = min(stamps)
    result["timeSkewMs"] = max(stamps) - min(stamps)
    if result["timeSkewMs"] > config.combination_max_time_skew_ms:
        result.update(status="INVALID", reason="TIME_MISMATCH")
        return result
    spread = max((separation(a["position"], b["position"]) for i, a in enumerate(usable) for b in usable[i+1:]), default=0.0)
    result["separationMetres"] = spread
    if spread > config.combination_max_separation_metres:
        result.update(status="INVALID", reason="SOURCE_DISAGREEMENT")
        return result
    vectors = [_vector(s["position"]) for s in usable]
    x, y, z = (math.fsum(v[i] for v in vectors) for i in range(3))
    position = {"latitudeDegrees": math.degrees(math.atan2(z, math.hypot(x, y))),
                "longitudeDegrees": math.degrees(math.atan2(y, x)), "altitudeMetres": None}
    # SINGLE_SOURCE is exact passthrough, including true zero coordinates.
    if len(usable) == 1:
        position = dict(usable[0]["position"], altitudeMetres=None)
    result.update(mode="CONSENSUS" if len(usable) > 1 else "SINGLE_SOURCE",
                  status="LIVE", reason="OK" if len(usable) > 1 else "SINGLE_SOURCE", position=position)
    return result

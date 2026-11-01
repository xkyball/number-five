"""Bounded Signal K route resources through the existing authenticated gateway.

The token detects changes since MAP opened the document. Signal K itself has no
cross-client compare-and-swap guarantee; a readback verifies every accepted write.
No telemetry/course/autopilot endpoints are reachable through this adapter.
"""
from __future__ import annotations
import asyncio
import hashlib
import json
import math
from pathlib import Path
from uuid import UUID
from .configuration import MapRoutesConfig

MAX_BYTES = 1024 * 1024

def resource_id(value: str) -> str:
    parsed = str(UUID(value))
    if parsed != value: raise ValueError("Canonical route UUID required")
    return parsed

def route_value(value: object) -> dict:
    if not isinstance(value, dict): raise ValueError("Route must be an object")
    feature = value.get("feature", {})
    if not isinstance(feature, dict): raise ValueError("Route feature must be an object")
    geometry = feature.get("geometry", {}) if isinstance(feature, dict) else {}
    points = geometry.get("coordinates") if isinstance(geometry, dict) else None
    if feature.get("type") != "Feature" or geometry.get("type") != "LineString" or not isinstance(points, list) or not 2 <= len(points) <= 5000:
        raise ValueError("Route needs a LineString with 2..5000 points")
    name = value.get("name", "")
    if not isinstance(name, str) or not name.strip() or len(name) > 200: raise ValueError("Route name required")
    for point in points:
        if not isinstance(point, list) or len(point) not in (2, 3): raise ValueError("Invalid GeoJSON coordinate")
        if any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in point): raise ValueError("Invalid coordinate")
        if not -180 <= point[0] <= 180 or not -85 <= point[1] <= 85: raise ValueError("Coordinate outside MAP renderer limits")
    if any(a[:2] == b[:2] or a[1] == b[1] and abs(a[0] - b[0]) == 360 for a, b in zip(points, points[1:])):
        raise ValueError("Identical adjacent route points")
    properties = feature.get("properties", {})
    if not isinstance(properties, dict): raise ValueError("Route properties must be an object")
    metadata = properties.get("coordinatesMeta", [])
    if not isinstance(metadata, list) or len(metadata) > len(points): raise ValueError("Invalid point metadata")
    ids = []
    for meta in metadata:
        if not isinstance(meta, dict): raise ValueError("Invalid point metadata")
        name = meta.get("name", "")
        if not isinstance(name, str) or len(name) > 200: raise ValueError("Invalid point name")
        if "no5PointId" in meta:
            point_id = meta["no5PointId"]
            if not isinstance(point_id, str) or not point_id.strip() or len(point_id) > 150: raise ValueError("Invalid point identity")
            ids.append(point_id)
    if len(ids) != len(set(ids)): raise ValueError("Duplicate point identity")
    if len(json.dumps(value, allow_nan=False).encode()) > MAX_BYTES: raise ValueError("Route too large")
    return value

def fingerprint(route: dict) -> str:
    return hashlib.sha256(json.dumps(route, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

class RouteConflict(Exception): pass
class RoutesUnavailable(Exception): pass

class MapRoutes:
    def __init__(self, config: MapRoutesConfig):
        self.config = config
        self.lock = asyncio.Lock()

    async def request(self, method: str, id: str | None = None, value: dict | None = None):
        import aiohttp
        if not self.config.enabled: raise RoutesUnavailable("Signal K routes are not configured")
        headers = {"Accept": "application/json", "Cache-Control": "no-cache, no-store"}
        if self.config.token_file:
            try:
                with Path(self.config.token_file).open() as file: token = file.read(4097).strip()
            except (OSError, UnicodeError) as error:
                raise RoutesUnavailable("Signal K authorization is unavailable") from error
            if not token or len(token) > 4096 or "\n" in token or "\r" in token: raise RoutesUnavailable("Signal K authorization is unavailable")
            headers["Authorization"] = "Bearer " + token
        url = self.config.resources_url.rstrip("/") + ("/" + resource_id(id) if id else "")
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8), headers=headers) as session:
                async with session.request(method, url, json=value, allow_redirects=False) as response:
                    if response.status == 404 and id and method == "GET": return None
                    if response.status not in (200, 201, 204): raise RoutesUnavailable(f"Signal K returned HTTP {response.status}")
                    data = bytearray()
                    async for part in response.content.iter_chunked(8192):
                        data.extend(part)
                        if len(data) > MAX_BYTES: raise RoutesUnavailable("Signal K response too large")
                    result = json.loads(data) if data else None
                    # Signal K 2.13 resources API returns {} for a missing UUID.
                    if method == "GET" and id and result == {}: return None
                    if method in {"PUT", "DELETE"} and isinstance(result, dict) and (result.get("state") not in (None, "COMPLETED") or result.get("statusCode", 200) not in (200, 201, 204)):
                        raise RoutesUnavailable("Signal K has not completed the write")
                    return result
        except RoutesUnavailable: raise
        except Exception as error: raise RoutesUnavailable("Signal K route request failed") from error

    async def list(self) -> dict:
        values = await self.request("GET")
        if not isinstance(values, dict) or len(values) > 512: raise RoutesUnavailable("Signal K route collection invalid")
        routes = {}; issues = []
        for id, value in values.items():
            try:
                resource_id(id); route_value(value)
                routes[id] = {"route": value, "token": fingerprint(value)}
            except (ValueError, AttributeError, TypeError): issues.append(id)
        return {"routes": routes, "unavailableIds": issues}

    async def save(self, id: str, expected: str | None, value: dict) -> dict:
        resource_id(id); route_value(value)
        if expected is not None and (not isinstance(expected, str) or len(expected) != 64): raise ValueError("Invalid base token")
        async with self.lock:
            old = await self.request("GET", id)
            if old is not None:
                # A repeated complete payload is safe after a lost response.
                if self.equivalent(old, value): return {"route": old, "token": fingerprint(old)}
                if expected is None or fingerprint(old) != expected: raise RouteConflict("Route changed in Signal K; reload before saving")
            elif expected is not None: raise RouteConflict("Route was removed in Signal K; reload before saving")
            await self.request("PUT", id, value)
            confirmed = await self.request("GET", id)
            if confirmed is None or not self.equivalent(confirmed, value): raise RoutesUnavailable("Signal K readback differs; draft retained")
            return {"route": confirmed, "token": fingerprint(confirmed)}

    @staticmethod
    def equivalent(actual: dict, wanted: dict) -> bool:
        # Provider-added timestamp/$source are not client payload fields.
        return all(actual.get(k) == v for k, v in wanted.items())

    async def delete(self, id: str, expected: str) -> None:
        resource_id(id)
        if not isinstance(expected, str) or len(expected) != 64: raise ValueError("Known route token required")
        async with self.lock:
            old = await self.request("GET", id)
            if old is None: return
            if fingerprint(old) != expected: raise RouteConflict("Route changed in Signal K; reload before deletion")
            await self.request("DELETE", id)
            if await self.request("GET", id) is not None: raise RoutesUnavailable("Signal K deletion not confirmed")

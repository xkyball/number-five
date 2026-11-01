"""HTTP/WebSocket boundary for the existing No5 SONAR V1 application contract."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from .configuration import GatewayConfig
from .lumina_tokens import LuminaTokenRegistry
from .lumina_registry_client import LuminaRegistryWriter, RegistryWriteUnavailable
from .sound import SoundError, SOUND_TYPES
from .audio_commands import AudioCommands
from .sonar_commands import SonarCommands
from .sonar_diagnostics import SonarDiagnostics
from .audio import AudioError, AudioOutcomeUnknown, CamillaDspAdapter, validate_audio_command
from .camilla_gui_auth import CamillaGuiAuthError, CamillaGuiSessionAuth, verifies_gateway_key
from .helm import HelmAdapter
from .media import MediaUnavailable, MediaChanged, ReadOnlyMedia
from .protocol import ProtocolError, strict_json_object
from .simulation import SimulationControlError, SimulationController
from .state import GatewayState
from .upstream import SonardUpstream
from .weather import WeatherAdapter
from .sonar_forwarding import ForwardingLease, ForwardingConflict


SONAR_SUBPROTOCOL = "no5.sonar.v1"
AUDIO_SUBPROTOCOL = "no5.audio.fader.v1"
AUDIO_COMMANDS_SUBPROTOCOL = "no5.audio.commands.v1"
AUDIO_SPECTRUM_SUBPROTOCOL = "no5.audio.spectrum.v1"


@dataclass(slots=True, eq=False)
class _Subscriber:
    socket: Any
    queue: asyncio.Queue[tuple[str, str | bytes]] = field(default_factory=lambda: asyncio.Queue(maxsize=8))

    def offer(self, kind: str, value: str | bytes) -> None:
        try:
            self.queue.put_nowait((kind, value))
        except asyncio.QueueFull:
            # Latest data is useful; slow readers must never delay acquisition.
            # State is emitted every second, so a discarded old event is repaired
            # promptly without unbounded client buffering.
            with __import__("contextlib").suppress(asyncio.QueueEmpty):
                self.queue.get_nowait()
            with __import__("contextlib").suppress(asyncio.QueueFull):
                self.queue.put_nowait((kind, value))


class GatewayService:
    def __init__(self, config: GatewayConfig) -> None:
        self.config = config
        self.state = GatewayState(config)
        self.lumina_tokens = (LuminaTokenRegistry(config.lumina_tokens.registry_file, config.lumina_tokens.audience) if config.lumina_tokens.enabled else None)
        self.lumina_registry = (LuminaRegistryWriter(config.lumina_tokens.registry_socket) if config.lumina_tokens.enabled else None)
        # One server session binds the existing SONAR and the shared V1 gateway
        # features.  It is intentionally regenerated only on process restart.
        self.helm = HelmAdapter(config, self.state.server_session_id)
        self.audio = CamillaDspAdapter(config, self.state.server_session_id)
        self.audio_commands=AudioCommands(self.audio)
        self.sonar_commands = SonarCommands(self.state)
        self.sonar_diagnostics = SonarDiagnostics(self.state)
        self.media = ReadOnlyMedia(config)
        self.weather = WeatherAdapter(config, self.state.server_session_id)
        self.simulation = SimulationController(config, self.state.server_session_id)
        self.forwarding = ForwardingLease(os.environ.get('NO5_SONAR_FORWARDING_POLICY'))
        self._sonar_route_lock = asyncio.Lock()
        self._camilla_gui_sessions: CamillaGuiSessionAuth | None = None
        self._camilla_gui_session_error: str | None = None
        if config.camilla_gui.enabled:
            secret = os.environ.get(config.camilla_gui.session_secret_env)
            if not secret:
                self._camilla_gui_session_error = "CamillaGUI session authentication is not configured"
            else:
                try:
                    self._camilla_gui_sessions = CamillaGuiSessionAuth(
                        secret, self.state.server_session_id, config.camilla_gui.session_max_age_s,
                    )
                except CamillaGuiAuthError:
                    # Do not include secret material or its validation detail in
                    # public output or process logs.
                    self._camilla_gui_session_error = "CamillaGUI session authentication is unavailable"
        # Status publication is timer-driven. A status GET must not invent a
        # new producer timestamp merely because an Android client polled it.
        self._status_published_at_epoch_ms = time.time_ns() // 1_000_000
        self._subscribers: set[_Subscriber] = set()
        self._audio_stream_count = 0
        self._spectrum_stream_count = 0
        self._sound_stream_count = 0
        self._upstream = SonardUpstream(config, self._on_status, self._on_frame,
                                        self._on_connected, self._on_failure)
        self._upstream_task: asyncio.Task[None] | None = None
        self._state_task: asyncio.Task[None] | None = None
        self._helm_task: asyncio.Task[None] | None = None
        self._audio_task: asyncio.Task[None] | None = None
        self._weather_task: asyncio.Task[None] | None = None
        self._simulation_task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        self.audio_commands.start()
        self.sonar_commands.start()
        # A persisted M0/M1 choice is applied before the first connection. If
        # its provisioned endpoint was later removed, retain the configured
        # production source and surface that safely through the control state.
        try:
            selected = self.simulation.source_config()
        except SimulationControlError:
            selected = self.config
        if selected != self.state.config:
            self.state.select_source(selected)
            self._upstream = SonardUpstream(selected, self._on_status, self._on_frame,
                                            self._on_connected, self._on_failure)
        self._upstream_task = asyncio.create_task(self._upstream.run(), name="no5-sonar-upstream")
        self._state_task = asyncio.create_task(self._state_publisher(), name="no5-sonar-state-publisher")
        self._helm_task = asyncio.create_task(self.helm.run(), name="no5-helm-upstream")
        self._audio_task = asyncio.create_task(self.audio.run(), name="no5-camilladsp-upstream")
        self._weather_task = asyncio.create_task(self.weather.run(), name="no5-weather-upstream")
        if self.simulation.configured:
            self._simulation_task = asyncio.create_task(self._simulation_publisher(), name="no5-local-simulation")

    async def stop(self) -> None:
        await self.audio_commands.stop()
        await self.sonar_commands.stop()
        await self._upstream.stop()
        await self.helm.stop()
        await self.audio.stop()
        await self.media.close()
        await self.weather.stop()
        for task in (self._upstream_task, self._state_task, self._helm_task, self._audio_task, self._weather_task,
                     self._simulation_task):
            if task is not None:
                task.cancel()
        for task in (self._upstream_task, self._state_task, self._helm_task, self._audio_task, self._weather_task,
                     self._simulation_task):
            if task is not None:
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        self._upstream_task = None
        self._state_task = None
        self._helm_task = None
        self._audio_task = None
        self._weather_task = None
        self._simulation_task = None

    async def _state_publisher(self) -> None:
        while True:
            await self.refresh_forwarding()
            self._status_published_at_epoch_ms = time.time_ns() // 1_000_000
            await self.publish_state()
            await asyncio.sleep(1.0)

    async def _simulation_publisher(self) -> None:
        """Keep dynamic display overlays fresh; never emit a synthetic ping."""

        while True:
            self.simulation.advance()
            await self.publish_state()
            await asyncio.sleep(0.25)

    async def _on_connected(self) -> None:
        self.state.begin_connection()
        await self.publish_state()

    async def _on_status(self, status: dict[str, object]) -> None:
        self.state.observe_status(status)
        await self.publish_state()

    async def _on_frame(self, frame: object) -> None:
        generation = self.state.generation
        binary = self.state.accept_ping(frame)  # type: ignore[arg-type]
        if binary is not None:
            if self.state.generation != generation:
                # A confirmed display/noise epoch may precede the status poll.
                # Clients must see the new state before its first binary frame.
                await self.publish_state()
            self.publish_binary(binary)

    async def _on_failure(self, reason: str) -> None:
        self.state.disconnect(reason)
        await self.publish_state()

    def _state_text(self) -> str:
        return json.dumps(self.state.snapshot(), separators=(",", ":"), allow_nan=False)

    async def select_sonar_source(self, selected: str) -> None:
        """Replace one explicitly provisioned M0/M1 reader without overlap."""

        target = self.forwarding.source_config(self.simulation.source_config(selected))
        await self._replace_sonar_upstream(target)

    async def refresh_forwarding(self):
        try:original=self.simulation.source_config()
        except SimulationControlError:original=self.config
        await self._replace_sonar_upstream(self.forwarding.source_config(original))

    async def _replace_sonar_upstream(self, target):
        async with self._sonar_route_lock:
            await self._replace_sonar_upstream_locked(target)

    async def _replace_sonar_upstream_locked(self, target):
        if target == self.state.config:
            return
        await self._upstream.stop()
        if self._upstream_task is not None:
            self._upstream_task.cancel()
            try:
                await self._upstream_task
            except asyncio.CancelledError:
                pass
        self.state.select_source(target)
        self._upstream = SonardUpstream(target, self._on_status, self._on_frame, self._on_connected, self._on_failure)
        self._upstream_task = asyncio.create_task(self._upstream.run(), name="no5-sonar-upstream")

    async def publish_state(self) -> None:
        text = self._state_text()
        for subscriber in tuple(self._subscribers):
            subscriber.offer("text", text)

    def publish_binary(self, binary: bytes) -> None:
        for subscriber in tuple(self._subscribers):
            subscriber.offer("binary", binary)

    async def add_subscriber(self, socket: Any) -> _Subscriber:
        subscriber = _Subscriber(socket)
        self._subscribers.add(subscriber)
        # The very first application message is always the state snapshot.
        subscriber.offer("text", self._state_text())
        return subscriber

    def remove_subscriber(self, subscriber: _Subscriber) -> None:
        self._subscribers.discard(subscriber)

    async def pump_subscriber(self, subscriber: _Subscriber, authorization: str = "", scope: str = "sonar.read") -> None:
        while not subscriber.socket.closed:
            # A V2 bearer is checked before every frame. Expiry or registry
            # revocation therefore closes an already-upgraded stream promptly.
            if authorization.startswith("Bearer N5T2.") and not self.authorizes_lumina_token(authorization, scope):
                await subscriber.socket.close(code=1008, message=b"Lumina bearer expired or revoked")
                return
            try:
                kind, value = await asyncio.wait_for(subscriber.queue.get(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            if kind == "text":
                await asyncio.wait_for(subscriber.socket.send_str(value), timeout=1.0)
            else:
                await asyncio.wait_for(subscriber.socket.send_bytes(value), timeout=1.0)

    def command_token(self) -> str | None:
        value = os.environ.get(self.config.command_token_env)
        return value if value else None

    def audio_gateway_key_hash(self) -> str | None:
        """Return the root-provisioned verifier for the existing proxy key.

        The AUDIO backend must not trust an Nginx-added marker: it still
        listens on loopback and a local caller could forge such a marker. The
        service instead compares a hash of the supplied existing gateway
        header with a root-provisioned verifier. The raw header key is never
        copied into the service environment.
        """

        value = os.environ.get(self.config.audio_gateway_key_hash_env)
        if value is None or len(value) != 64:
            return None
        try:
            int(value, 16)
        except ValueError:
            return None
        return value.lower()

    def authorizes_audio_gateway_header(self, values: list[str]) -> bool:
        """Check exactly one supplied header against the root verifier."""

        expected = self.audio_gateway_key_hash()
        if expected is None or len(values) != 1:
            return False
        try:
            digest = hashlib.sha256(values[0].encode("ascii")).hexdigest()
        except UnicodeEncodeError:
            return False
        return hmac.compare_digest(digest, expected)

    def authorizes_lumina_token(self, authorization: str, scope: str) -> bool:
        return self.lumina_tokens is not None and self.lumina_tokens.verify(authorization, scope) is not None

    def authorizes_camilla_gui_key(self, candidate: str) -> bool:
        """Validate the existing gateway key without retaining its raw value."""

        return verifies_gateway_key(candidate, self.audio_gateway_key_hash())

    @property
    def camilla_gui_sessions(self) -> CamillaGuiSessionAuth | None:
        return self._camilla_gui_sessions

    @property
    def camilla_gui_session_error(self) -> str | None:
        return self._camilla_gui_session_error

    def simulation_token(self) -> str | None:
        if not self.simulation.configured:
            return None
        value = os.environ.get(self.config.simulation.token_env)
        return value if value else None

    def gateway_status(self) -> dict[str, object]:
        """Shared `/v1/status` without pretending unavailable inputs are live."""

        sonar_enabled = self.state.config.producer != "disabled"
        helm_payload = self.simulation.helm_payload(self.helm.snapshot)
        weather_payload = self.simulation.weather_payload(self.weather.payload)
        sonar_payload = self.state.snapshot()
        helm_enabled = self.helm.configured or self.simulation.helm_active
        audio_enabled = self.audio.configured and self.audio.payload is not None
        weather_enabled = (self.weather.configured and self.weather.payload is not None) or self.simulation.weather_active
        endpoints = ["/v1/status"]
        if sonar_enabled:
            endpoints.append("/v1/sonar")
        if helm_enabled:
            endpoints.append("/v1/helm")
        if audio_enabled:
            endpoints.append("/v1/audio")
        if weather_enabled:
            endpoints.append("/v1/weather")
        return {
            "version": 1,
            "kind": "status",
            "source": "no5-pi-gateway",
            "sourceKind": "REAL",
            "simulated": False,
            "timestampEpochMs": self._status_published_at_epoch_ms,
            "publishedAtEpochMs": self._status_published_at_epoch_ms,
            "serverSessionId": self.state.server_session_id,
            "simulation": None,
            "availableEndpoints": endpoints,
            "capabilities": {
                "helm": helm_enabled,
                "weather": weather_enabled,
                "audio": audio_enabled,
                "sonar": sonar_enabled,
                "hardwareWrites": self.audio.write_available,
            },
            "adapters": {
                "sonar": {
                    "status": sonar_payload["status"],
                    "source": sonar_payload["source"],
                    "sourceKind": sonar_payload["sourceKind"],
                    "observedAtEpochMs": sonar_payload["observedAtEpochMs"],
                },
                "helm": {
                    "configured": helm_enabled,
                    "status": None if helm_payload is None else helm_payload["status"],
                    "source": None if helm_payload is None else helm_payload["source"],
                    "lastError": self.helm.last_error,
                },
                "audio": {
                    "configured": self.audio.configured,
                    "status": self.audio.audio_status,
                    "mediaStatus": self.audio.media_status,
                    "source": self.config.audio.source_name,
                    "dspSource": "camilladsp",
                    "dspObservedAtEpochMs": (None if self.audio.snapshot is None
                                               else self.audio.snapshot.observed_at_epoch_ms),
                    "reason": self.audio.availability_reason,
                },
                "weather": {
                    "configured": weather_enabled,
                    "status": None if weather_payload is None else weather_payload["status"],
                    "source": None if weather_payload is None else weather_payload["source"],
                    "lastError": self.weather.last_error,
                },
                "simulation": {
                    "configured": self.simulation.configured,
                    "active": self.simulation.any_active,
                    "lastError": self.simulation.last_error,
                },
            },
        }


def _requested_subprotocols(header: str | None) -> set[str]:
    if not header:
        return set()
    return {value.strip() for value in header.split(",") if value.strip()}


async def create_app(config: GatewayConfig) -> Any:
    """Create the aiohttp application only in a runtime with its dependency present."""

    from aiohttp import WSMsgType, web

    service = GatewayService(config)
    app = web.Application(client_max_size=4096)
    app["gateway_service"] = service
    from .map_routes import MapRoutes, RouteConflict, RoutesUnavailable
    map_routes = MapRoutes(config.map_routes)

    async def map_route_resources(request: Any) -> Any:
        require_audio_gateway_key(request, "map.read" if request.method == "GET" else "map.write")
        try:
            if request.method == "GET": result = await map_routes.list()
            else:
                # Increase only this resource request's bound, not other gateway commands.
                data = await request.clone(client_max_size=1024 * 1024).json()
                if request.method == "DELETE":
                    if not isinstance(data, dict) or set(data) != {"expectedToken"}: raise ValueError("Invalid delete request")
                    await map_routes.delete(request.match_info["id"], data["expectedToken"])
                    result = {"deleted": True}
                else:
                    if not isinstance(data, dict) or set(data) != {"route", "expectedToken"}: raise ValueError("Invalid route request")
                    result = await map_routes.save(request.match_info["id"], data["expectedToken"], data["route"])
            return web.json_response(result, headers={"Cache-Control": "no-store"})
        except RouteConflict as error: raise web.HTTPConflict(text=str(error)) from error
        except (ValueError, TypeError, AttributeError) as error: raise web.HTTPBadRequest(text="Invalid route resource") from error
        except RoutesUnavailable as error: raise web.HTTPServiceUnavailable(text=str(error)) from error

    async def snapshot(_request: Any) -> Any:
        require_audio_gateway_key(_request, "sonar.read")
        return web.json_response(service.state.snapshot())

    async def helm_snapshot(_request: Any) -> Any:
        require_any_lumina_scope(_request, ("sonar.read", "map.read"))
        payload = service.simulation.helm_payload(service.helm.snapshot)
        if payload is None:
            raise web.HTTPServiceUnavailable(text=service.helm.last_error or "HELM source is unavailable")
        return web.json_response(payload)

    async def audio_snapshot(_request: Any) -> Any:
        require_audio_gateway_key(_request)
        payload = service.audio.payload
        if payload is None:
            raise web.HTTPServiceUnavailable(text=service.audio.availability_reason or "AUDIO source is unavailable")
        return web.json_response(payload)

    async def weather_snapshot(_request: Any) -> Any:
        require_audio_gateway_key(_request, "sonar.read")
        payload = service.simulation.weather_payload(service.weather.payload)
        if payload is None:
            raise web.HTTPServiceUnavailable(text=service.weather.last_error or "weather source is unavailable")
        return web.json_response(payload)

    async def media_snapshot(request: Any) -> Any:
        require_audio_gateway_key(request)
        try:
            return web.json_response(await service.media.snapshot(), headers={"Cache-Control": "no-store"})
        except MediaUnavailable as exc:
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc

    async def media_artwork(request: Any) -> Any:
        require_audio_gateway_key(request)
        try:
            data, mime = await (service.media.playlist_artwork(request.match_info["identifier"])
                                if request.path.startswith("/v1/audio/playlist-artwork/")
                                else service.media.artwork(request.match_info["identifier"]))
            return web.Response(body=data, content_type=mime, headers={"Cache-Control": "no-store"})
        except MediaChanged as exc:
            raise web.HTTPConflict(text=str(exc)) from exc
        except MediaUnavailable as exc:
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc

    async def media_navigation(request: Any) -> Any:
        require_audio_gateway_key(request)
        try:
            return web.json_response(await service.media.snapshot(navigation=True), headers={"Cache-Control": "no-store"})
        except MediaUnavailable as exc:
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc

    async def media_select(request: Any) -> Any:
        require_audio_gateway_key(request)
        try:
            raw = await request.read()
            if len(raw) > 4096:
                raise web.HTTPBadRequest(text="Selection exceeds its bounds")
            value = strict_json_object(raw)
            return web.json_response(await service.media.select(value), headers={"Cache-Control": "no-store"})
        except ProtocolError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc

    async def media_browse(request: Any) -> Any:
        require_audio_gateway_key(request)
        expected = request.query.get("receiverSessionId", "")
        kind = request.query.get("type", "")
        allowed = {"receiverSessionId", "type", "offset", "limit"}
        allowed |= {"uri"} if kind == "PLAYLIST" else {"query"} if kind == "SEARCH" else set()
        if kind not in {"PLAYLISTS", "PLAYLIST", "LIKED", "SEARCH"} or set(request.query) - allowed:
            raise web.HTTPBadRequest(text="Browse query is unsupported")
        if any(len(request.query.getall(key)) != 1 for key in request.query):
            raise web.HTTPBadRequest(text="Duplicate browse parameter")
        try:
            offset, limit = int(request.query.get("offset", "0")), int(request.query.get("limit", "20"))
        except ValueError as exc:
            raise web.HTTPBadRequest(text="Browse page is invalid") from exc
        if not 0 <= offset <= 5000 or not 1 <= limit <= 40:
            raise web.HTTPBadRequest(text="Browse page is outside its bounds")
        query = {"type": kind, "offset": offset, "limit": limit}
        for key in ("uri", "query"):
            if key in allowed:
                query[key] = request.query.get(key, "")
        try:
            return web.json_response(await service.media.browse(expected, query), headers={"Cache-Control": "no-store"})
        except MediaChanged as exc:
            raise web.HTTPConflict(text=str(exc)) from exc
        except MediaUnavailable as exc:
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc

    async def bootstrap_authorized(request: Any) -> bool:
        if service.authorizes_audio_gateway_header(request.headers.getall("X-No5-Gateway-Key", [])): return True
        passwords=request.headers.getall("X-No5-Gateway-Password", [])
        if len(passwords)!=1 or not __import__("re").fullmatch(r"[0-9a-f]{64}",passwords[0]): return False
        # The password verifier owns the PBKDF2 record. The gateway only asks
        # its loopback /check endpoint and never reads a password verifier.
        def check() -> bool:
            import urllib.error, urllib.request
            try:
                request_value=urllib.request.Request("http://127.0.0.1:8790/check",headers={"X-No5-Gateway-Password":passwords[0]})
                with urllib.request.urlopen(request_value,timeout=2) as response: return response.status==204
            except (OSError, urllib.error.HTTPError): return False
        return await asyncio.to_thread(check)

    async def lumina_enroll(request: Any) -> Any:
        if service.lumina_tokens is None or service.lumina_registry is None: raise web.HTTPNotFound()
        if not await bootstrap_authorized(request): raise web.HTTPUnauthorized(text="bootstrap gateway authentication required")
        try:
            raw=await request.content.read(4097); value=strict_json_object(raw)
            if set(value)!={"keyId","publicKey"}: raise ProtocolError("invalid enrollment")
            service.lumina_registry.enroll(value["keyId"],value["publicKey"])
        except (ValueError, ProtocolError): raise web.HTTPBadRequest(text="invalid Lumina enrollment")
        except RegistryWriteUnavailable: raise web.HTTPServiceUnavailable(text="Lumina registry helper unavailable")
        return web.Response(status=204,headers={"Cache-Control":"no-store"})
    async def lumina_revoke(request: Any) -> Any:
        if service.lumina_tokens is None or service.lumina_registry is None: raise web.HTTPNotFound()
        if not await bootstrap_authorized(request): raise web.HTTPUnauthorized(text="bootstrap gateway authentication required")
        try:
            value=strict_json_object(await request.content.read(1025));
            if set(value)!={"keyId"}: raise ProtocolError("invalid revocation")
            service.lumina_registry.revoke(value["keyId"])
        except (ValueError, ProtocolError): raise web.HTTPBadRequest(text="invalid Lumina revocation")
        except RegistryWriteUnavailable: raise web.HTTPServiceUnavailable(text="Lumina registry helper unavailable")
        return web.Response(status=204,headers={"Cache-Control":"no-store"})

    async def gateway_status(request: Any) -> Any:
        require_audio_gateway_key(request, "configuration.read")
        return web.json_response(service.gateway_status())

    async def healthz(_request: Any) -> Any:
        # Process liveness is intentionally separate from SONAR data availability.
        sonar_payload = service.state.snapshot()
        return web.json_response({"status": "ok", "sonarStatus": sonar_payload["status"],
                                  "helmConfigured": service.helm.configured or service.simulation.helm_active,
                                  "audioConfigured": service.audio.configured,
                                  "weatherConfigured": service.weather.configured or service.simulation.weather_active,
                                  "simulationConfigured": service.simulation.configured,
                                  "simulationActive": service.simulation.any_active})

    async def readyz(_request: Any) -> Any:
        helm_payload = service.simulation.helm_payload(service.helm.snapshot)
        weather_payload = service.simulation.weather_payload(service.weather.payload)
        sonar_payload = service.state.snapshot()
        return web.json_response({"ready": True, "sonarStatus": sonar_payload["status"],
                                  "helmStatus": None if helm_payload is None else helm_payload["status"],
                                  "audioStatus": service.audio.audio_status,
                                  "audioReason": service.audio.availability_reason,
                                  "weatherStatus": None if weather_payload is None else weather_payload["status"],
                                  "weatherReason": service.weather.last_error,
                                  "simulationActive": service.simulation.any_active})

    def require_local_simulation(request: Any) -> None:
        # The listener normally binds to loopback. Keep this check in place in
        # case a future deployment changes that bind policy. Nginx separately
        # denies /admin paths, so a header-authenticated LAN request cannot
        # reach the local control API.
        if request.remote not in {"127.0.0.1", "::1"}:
            raise web.HTTPForbidden(text="local simulation control is loopback-only")

    def require_simulation_bearer(request: Any) -> None:
        token = service.simulation_token()
        if token is None:
            raise web.HTTPServiceUnavailable(text="simulation authentication is not configured")
        supplied = request.headers.get("Authorization", "")
        if not supplied.startswith("Bearer ") or not hmac.compare_digest(supplied[7:], token):
            raise web.HTTPUnauthorized(text="simulation bearer authentication required")

    async def sonar_forwarding(request: Any) -> Any:
        require_local_simulation(request)
        require_simulation_bearer(request)
        try:
            if request.method=='PUT':
                payload=await request.json()
                service.forwarding.acquire(payload)
            elif request.method=='DELETE':
                service.forwarding.release(request.headers.get('X-No5-Forwarding-Owner',''))
            await service.refresh_forwarding()
            return web.json_response(service.forwarding.snapshot(),headers={'Cache-Control':'no-store'})
        except ForwardingConflict as exc:
            raise web.HTTPConflict(text=str(exc)) from exc

    def require_any_lumina_scope(request: Any, scopes: tuple[str, ...]) -> None:
        if len(request.headers.getall("Authorization", [])) > 1:
            raise web.HTTPUnauthorized(text="duplicate gateway bearer")
        if any(service.authorizes_lumina_token(request.headers.get("Authorization", ""), scope) for scope in scopes): return
        require_audio_gateway_key(request, scopes[0])

    def require_audio_gateway_key(request: Any, scope: str = "audio.read") -> None:
        """Require the same X-No5-Gateway-Key proof as the TLS front door.

        Nginx remains the public TLS/key boundary. This independent check
        prevents a process with loopback access from bypassing that boundary
        by talking to the backend directly or forging a proxy marker.
        """

        if len(request.headers.getall("Authorization", [])) > 1:
            raise web.HTTPUnauthorized(text="duplicate gateway bearer")
        if service.authorizes_lumina_token(request.headers.get("Authorization", ""), scope): return
        # An invalid, expired or out-of-scope bearer must not fall through to
        # legacy credentials, even when both headers were supplied.
        if request.headers.getall("Authorization", []):
            raise web.HTTPUnauthorized(text="gateway bearer token rejected")
        if service.lumina_tokens is not None and not service.config.lumina_tokens.allow_legacy_gateway_key:
            raise web.HTTPUnauthorized(text="lumina bearer token required")
        expected = service.audio_gateway_key_hash()
        if expected is None: raise web.HTTPServiceUnavailable(text="audio gateway authentication is not configured")
        if not service.authorizes_audio_gateway_header(request.headers.getall("X-No5-Gateway-Key", [])):
            raise web.HTTPUnauthorized(text="gateway authentication required")

    def require_local_camilla_gui(request: Any) -> None:
        # The Nginx browser boundary is the only intended caller.  These
        # routes remain defensively loopback-only even if a future config
        # accidentally broadens the gateway listener.
        if request.remote not in {"127.0.0.1", "::1"}:
            raise web.HTTPForbidden(text="CamillaGUI session endpoint is loopback-only")
        if not service.config.camilla_gui.enabled:
            raise web.HTTPNotFound()

    def require_camilla_gui_origin(request: Any) -> None:
        if request.headers.get("Origin") != service.config.camilla_gui.public_origin:
            raise web.HTTPForbidden(text="CamillaGUI origin is not allowed")

    async def camilla_gui_login(request: Any) -> Any:
        require_local_camilla_gui(request)
        require_camilla_gui_origin(request)
        sessions = service.camilla_gui_sessions
        if sessions is None:
            raise web.HTTPServiceUnavailable(text=service.camilla_gui_session_error or
                                             "CamillaGUI session authentication is unavailable")
        if request.content_type != "application/json":
            raise web.HTTPUnsupportedMediaType(text="CamillaGUI login requires JSON")
        raw = await request.content.read(1025)
        if len(raw) > 1024:
            raise web.HTTPRequestEntityTooLarge(max_size=1024, actual_size=len(raw))
        try:
            payload = strict_json_object(raw)
        except ProtocolError as exc:
            raise web.HTTPBadRequest(text="invalid CamillaGUI login request") from exc
        candidate = payload.get("gatewayKey")
        if set(payload) != {"gatewayKey"} or not isinstance(candidate, str):
            raise web.HTTPUnauthorized(text="gateway authentication required")
        if not service.authorizes_camilla_gui_key(candidate):
            raise web.HTTPUnauthorized(text="gateway authentication required")
        response = web.json_response({"ok": True}, headers={"Cache-Control": "no-store"})
        response.set_cookie(
            service.config.camilla_gui.cookie_name, sessions.issue(),
            max_age=service.config.camilla_gui.session_max_age_s,
            secure=True, httponly=True, samesite="Strict", path="/camilladsp/",
        )
        return response

    async def camilla_gui_auth(request: Any) -> Any:
        require_local_camilla_gui(request)
        sessions = service.camilla_gui_sessions
        if sessions is None:
            raise web.HTTPServiceUnavailable(text=service.camilla_gui_session_error or
                                             "CamillaGUI session authentication is unavailable")
        supplied = request.cookies.get(service.config.camilla_gui.cookie_name)
        if not sessions.verify(supplied):
            raise web.HTTPUnauthorized(text="CamillaGUI browser session required")
        return web.Response(status=204, headers={"Cache-Control": "no-store"})

    async def camilla_gui_logout(request: Any) -> Any:
        require_local_camilla_gui(request)
        require_camilla_gui_origin(request)
        response = web.Response(status=204, headers={"Cache-Control": "no-store"})
        response.set_cookie(
            service.config.camilla_gui.cookie_name, "", max_age=0,
            expires="Thu, 01 Jan 1970 00:00:00 GMT", secure=True, httponly=True,
            samesite="Strict", path="/camilladsp/",
        )
        return response

    async def simulation_ui(request: Any) -> Any:
        require_local_simulation(request)
        if not service.simulation.configured:
            raise web.HTTPServiceUnavailable(text="local simulation control is disabled")
        from importlib.resources import files
        body = files("no5_pi_gateway").joinpath("simulation_ui.html").read_text(encoding="utf-8")
        return web.Response(text=body, content_type="text/html", headers={"Cache-Control": "no-store"})

    async def simulation_state(request: Any) -> Any:
        require_local_simulation(request)
        require_simulation_bearer(request)
        return web.json_response(service.simulation.control_state(), headers={"Cache-Control": "no-store"})

    async def simulation_command(request: Any) -> Any:
        require_local_simulation(request)
        require_simulation_bearer(request)
        raw = await request.content.read(4097)
        if len(raw) > 4096:
            raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
        try:
            payload = strict_json_object(raw)
            previous_mode = service.simulation.sonar_mode
            ack = service.simulation.apply(payload)
            if service.simulation.sonar_mode != previous_mode:
                await service.select_sonar_source(service.simulation.sonar_mode)
        except (ProtocolError, SimulationControlError) as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        await service.publish_state()
        return web.json_response(ack, headers={"Cache-Control": "no-store"})

    async def command(request: Any) -> Any:
        if service.authorizes_lumina_token(request.headers.get("Authorization", ""), "sonar.command"):
            raw = await request.content.read(4097)
            if len(raw) > 4096: raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
            try: return web.json_response(service.state.reject_command(strict_json_object(raw)).as_contract())
            except ProtocolError as exc: raise web.HTTPBadRequest(text=str(exc)) from exc
        token = service.command_token()
        if token is None:
            raise web.HTTPServiceUnavailable(text="command authentication is not configured")
        supplied = request.headers.get("Authorization", "")
        if not supplied.startswith("Bearer ") or not hmac.compare_digest(supplied[7:], token):
            raise web.HTTPUnauthorized(text="bearer authentication required")
        raw = await request.content.read(4097)
        if len(raw) > 4096:
            raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
        try:
            payload = strict_json_object(raw)
            ack = service.state.reject_command(payload)
        except ProtocolError as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        return web.json_response(ack.as_contract())

    async def audio_command(request: Any) -> Any:
        require_audio_gateway_key(request, "audio.command")
        raw = await request.content.read(4097)
        if len(raw) > 4096:
            raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
        try:
            payload = strict_json_object(raw)
            validate_audio_command(payload)
            if payload["command"]["type"] == "equalizer" or payload["command"]["type"] in SOUND_TYPES:
                raise web.HTTPBadRequest(text="Sound editing requires the asynchronous /v1/audio/commands endpoint")
            ack = await service.audio.command(payload)
        except AudioOutcomeUnknown as exc:
            # The receiver accepted a local Spirc action but the mandatory
            # player-state readback did not arrive.  This is neither a
            # rejection nor a verified write; clients reconcile with GET and
            # must not replay the same request.
            raise web.HTTPServiceUnavailable(text=str(exc)) from exc
        except (ProtocolError, AudioError) as exc:
            raise web.HTTPBadRequest(text=str(exc)) from exc
        return web.json_response(ack)

    def sonar_command_owner(request, scope):
        require_audio_gateway_key(request, scope)
        authorization = request.headers.get("Authorization", "")
        if authorization:
            claims = service.lumina_tokens.verify(authorization, scope)
            return claims.key_id + ":" + claims.role, lambda: service.authorizes_lumina_token(authorization, "sonar.command")
        return "legacy", lambda: service.authorizes_audio_gateway_header(request.headers.getall("X-No5-Gateway-Key", []))

    async def sonar_filter(request):
        owner, valid = sonar_command_owner(request, "sonar.read" if request.method == "GET" else "sonar.command")
        if request.method == "GET":
            try: payload = await service.sonar_commands.snapshot()
            except Exception: raise web.HTTPServiceUnavailable(text="Sonard filter unavailable")
            return web.json_response(payload, headers={"Cache-Control": "no-store"})
        raw = bytearray()
        async for chunk in request.content.iter_chunked(1024):
            raw.extend(chunk)
            if len(raw) > 4096: raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
        try:
            body = strict_json_object(bytes(raw))
            if "mode" not in body: raise ProtocolError("filter goal requires mode")
            payload = service.sonar_commands.submit(owner, body, valid)
        except ProtocolError as exc: raise web.HTTPBadRequest(text=str(exc)) from exc
        return web.json_response(payload, status=202, headers={"Cache-Control": "no-store"})

    async def sonar_source(request):
        owner, valid = sonar_command_owner(request, "sonar.read" if request.method == "GET" else "sonar.command")
        if request.method == "GET":
            try: payload = await service.sonar_commands.source_snapshot()
            except Exception: raise web.HTTPServiceUnavailable(text="Sonard source selection unavailable")
            return web.json_response(payload, headers={"Cache-Control": "no-store"})
        raw = bytearray()
        async for chunk in request.content.iter_chunked(1024):
            raw.extend(chunk)
            if len(raw) > 4096: raise web.HTTPRequestEntityTooLarge(max_size=4096, actual_size=len(raw))
        try:
            body = strict_json_object(bytes(raw))
            if "source" not in body: raise ProtocolError("source goal requires source")
            payload = service.sonar_commands.submit(owner, body, valid)
        except ProtocolError as exc: raise web.HTTPBadRequest(text=str(exc)) from exc
        return web.json_response(payload, status=202, headers={"Cache-Control": "no-store"})

    async def sonar_diagnostics(request):
        sonar_command_owner(request, "sonar.read")
        try: payload = await service.sonar_diagnostics.snapshot()
        except Exception: raise web.HTTPServiceUnavailable(text="Sonard diagnostics unavailable")
        encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()
        if len(encoded) > 32768: raise web.HTTPServiceUnavailable(text="Sonard diagnostics exceeds bounds")
        return web.Response(body=encoded, content_type="application/json", headers={"Cache-Control": "no-store"})

    async def sonar_command_result(request):
        owner, _ = sonar_command_owner(request, "sonar.read")
        try: identifier = int(request.match_info["id"])
        except ValueError: raise web.HTTPGone(text="command result unavailable; reconcile without replay")
        payload = service.sonar_commands.read(owner, identifier)
        if payload is None: raise web.HTTPGone(text="command result unavailable; reconcile without replay")
        return web.json_response(payload, headers={"Cache-Control": "no-store"})

    def audio_command_owner(request):
        require_audio_gateway_key(request,"audio.command")
        authorization=request.headers.get("Authorization","")
        if authorization:
            claims=service.lumina_tokens.verify(authorization,"audio.command")
            owner=claims.key_id+":"+claims.role
            return owner,lambda: service.authorizes_lumina_token(authorization,"audio.command")
        return "legacy",lambda: service.authorizes_audio_gateway_header(request.headers.getall("X-No5-Gateway-Key",[]))

    async def audio_commands(request):
        owner,valid=audio_command_owner(request)
        raw=await request.content.read(16385)
        if len(raw)>16384:raise web.HTTPRequestEntityTooLarge(max_size=16384,actual_size=len(raw))
        try:
            payload=strict_json_object(raw)
            receipt=service.audio_commands.submit(owner,payload,valid)
        except (ProtocolError,AudioError) as exc:raise web.HTTPBadRequest(text=str(exc)) from exc
        # SOUND only: at most150ms completion wait removes a redundant poll for fast native writes.
        # Admission/expiry/idempotency remain owned by the same actor; timeout never cancels it.
        if payload["command"]["type"] in SOUND_TYPES:
            receipt=await service.audio_commands.wait_result(owner,receipt["commandId"])
        return web.json_response(receipt,status=202,headers={"Cache-Control":"no-store"})

    def sound_read_owner(request):
        require_audio_gateway_key(request, "audio.read")
        authorization = request.headers.get("Authorization", "")
        if authorization:
            claims = service.lumina_tokens.verify(authorization, "audio.read")
            return claims.key_id + ":" + claims.role
        return "legacy"

    async def audio_sound(request):
        owner = sound_read_owner(request)
        if service.audio.sound is None:
            raise web.HTTPServiceUnavailable(text="Sound processor not provisioned")
        payload = service.audio.sound.snapshot(owner)
        payload["writable"] = payload["writable"] and service.audio.write_available
        payload["capabilities"]["commandStream"] = True
        return web.json_response(payload, headers={"Cache-Control": "no-store"})

    async def audio_sound_commands_stream(request):
        from .sound_stream import sound_command_stream
        owner, valid = audio_command_owner(request)
        if service._sound_stream_count >= 4:
            raise web.HTTPServiceUnavailable(text="Sound stream capacity reached")
        service._sound_stream_count += 1
        try:
            return await sound_command_stream(request, service.audio_commands, owner, valid)
        finally:
            service._sound_stream_count -= 1

    async def audio_spectrum(request):
        sound_read_owner(request)
        sound = service.audio.sound
        if sound is None or not sound.snapshot()["capabilities"]["spectrum"]:
            raise web.HTTPServiceUnavailable(text="Spectrum tap unavailable")
        if service._spectrum_stream_count >= 4:
            raise web.HTTPServiceUnavailable(text="Spectrum stream capacity reached")
        if AUDIO_SPECTRUM_SUBPROTOCOL not in _requested_subprotocols(request.headers.get("Sec-WebSocket-Protocol")):
            raise web.HTTPUpgradeRequired(headers={"Sec-WebSocket-Protocol": AUDIO_SPECTRUM_SUBPROTOCOL})
        service._spectrum_stream_count += 1
        try: await sound.spectrum_attach()
        except Exception:
            service._spectrum_stream_count -= 1
            raise web.HTTPServiceUnavailable(text="Spectrum tap activation not confirmed")
        socket = web.WebSocketResponse(protocols=(AUDIO_SPECTRUM_SUBPROTOCOL,), heartbeat=5, max_msg_size=4096)
        authorization = request.headers.get("Authorization", "")
        async def publish():
            previous = None
            waiting = False
            while not socket.closed:
                if authorization and not service.authorizes_lumina_token(authorization, "audio.read"):
                    await socket.close(code=1008, message=b"authorization expired or revoked"); return
                try:
                    frame = await sound.spectrum_latest()
                except Exception:
                    await socket.close(code=1011, message=b"spectrum source unavailable"); return
                if frame is not None:
                    identity = frame["sourceGeneration"], frame["sequence"]
                    if identity != previous:
                        await socket.send_json(frame); previous = identity
                        waiting = False
                elif not waiting:
                    await socket.send_json({"version": 1, "kind": "audio-spectrum-status", "available": False, "reason": "WAITING"})
                    waiting = True
                await asyncio.sleep(.1)
        task = None
        try:
            await socket.prepare(request)
            task = asyncio.create_task(publish(), name="no5-audio-spectrum-publisher")
            async for message in socket:
                if message.type not in {WSMsgType.PING, WSMsgType.PONG}:
                    await socket.close(code=1003, message=b"spectrum stream is read-only"); break
        finally:
            service._spectrum_stream_count -= 1
            if task is not None:
                task.cancel()
                try: await task
                except (asyncio.CancelledError, ConnectionError): pass
            await sound.spectrum_detach()
        return socket

    async def audio_command_result(request):
        owner,_=audio_command_owner(request)
        receipt=service.audio_commands.read(owner,request.match_info["id"])
        if receipt is None:raise web.HTTPGone(text="command result unavailable; reconcile without replay")
        return web.json_response(receipt,headers={"Cache-Control":"no-store"})

    async def audio_command_results_stream(request):
        owner,valid=audio_command_owner(request)
        if AUDIO_COMMANDS_SUBPROTOCOL not in _requested_subprotocols(request.headers.get("Sec-WebSocket-Protocol")):
            raise web.HTTPUpgradeRequired(headers={"Sec-WebSocket-Protocol":AUDIO_COMMANDS_SUBPROTOCOL})
        socket=web.WebSocketResponse(protocols=(AUDIO_COMMANDS_SUBPROTOCOL,),heartbeat=5,max_msg_size=4096)
        await socket.prepare(request)
        async def publish():
            while not socket.closed:
                if not valid():await socket.close(code=1008,message=b"authorization expired or revoked");return
                # Bounded snapshot repairs missed/superseded result events after reconnect.
                results=service.audio_commands.results(owner)
                for result in results:result.pop("state",None)
                await socket.send_json({"version":1,"kind":"audio-command-results","results":results})
                await asyncio.sleep(.1)
        task=asyncio.create_task(publish(),name="no5-audio-command-results")
        try:
            async for message in socket:
                if message.type not in {WSMsgType.PING,WSMsgType.PONG}:await socket.close(code=1003,message=b"results stream is read-only");break
        finally:
            task.cancel()
            try:await task
            except (asyncio.CancelledError,ConnectionError):pass
        return socket

    async def stream(request: Any) -> Any:
        require_audio_gateway_key(request, "sonar.read")
        if SONAR_SUBPROTOCOL not in _requested_subprotocols(request.headers.get("Sec-WebSocket-Protocol")):
            raise web.HTTPUpgradeRequired(headers={"Sec-WebSocket-Protocol": SONAR_SUBPROTOCOL},
                                          text="no5.sonar.v1 websocket subprotocol is required")
        socket = web.WebSocketResponse(protocols=(SONAR_SUBPROTOCOL,), heartbeat=10.0,
                                       max_msg_size=4096)
        await socket.prepare(request)
        subscriber = await service.add_subscriber(socket)
        writer = asyncio.create_task(service.pump_subscriber(subscriber, request.headers.get("Authorization", ""), "sonar.read"), name="no5-sonar-client")
        try:
            async for message in socket:
                # The SONAR protocol has no client data messages; RFC6455 ping/pong
                # is handled by aiohttp.  Closing rejects accidental command paths.
                if message.type not in {WSMsgType.PING, WSMsgType.PONG}:
                    await socket.close(code=1003, message=b"SONAR stream is read-only")
                    break
        finally:
            writer.cancel()
            try:
                await writer
            except asyncio.CancelledError:
                pass
            service.remove_subscriber(subscriber)
        return socket

    async def audio_stream(request: Any) -> Any:
        require_audio_gateway_key(request)
        if AUDIO_SUBPROTOCOL not in _requested_subprotocols(request.headers.get("Sec-WebSocket-Protocol")):
            raise web.HTTPUpgradeRequired(headers={"Sec-WebSocket-Protocol": AUDIO_SUBPROTOCOL})
        if service.audio.payload is None:
            raise web.HTTPServiceUnavailable(text="AUDIO observation unavailable")
        if service._audio_stream_count >= 8:
            raise web.HTTPServiceUnavailable(text="AUDIO stream capacity reached")
        socket = web.WebSocketResponse(protocols=(AUDIO_SUBPROTOCOL,), heartbeat=5.0, max_msg_size=4096)
        await socket.prepare(request)
        service._audio_stream_count += 1
        send_lock = asyncio.Lock()
        async def send(value: dict[str, object]) -> None:
            async with send_lock:
                await socket.send_str(json.dumps(value, separators=(",", ":"), allow_nan=False))
        authorization=request.headers.get("Authorization", "")
        async def observe() -> None:
            while not socket.closed:
                if authorization.startswith("Bearer N5T2.") and not service.authorizes_lumina_token(authorization, "audio.read"):
                    await socket.close(code=1008, message=b"Lumina bearer expired or revoked")
                    return
                payload = service.audio.payload
                if payload is None:
                    await socket.close(code=1011, message=b"AUDIO observation unavailable")
                    return
                await send(payload)
                await asyncio.sleep(0.1)
        publisher = asyncio.create_task(observe(), name="no5-audio-fader-observer")
        previous_request = 0.0
        try:
            async for message in socket:
                if message.type != WSMsgType.TEXT:
                    await socket.close(code=1003, message=b"AUDIO fader stream accepts bounded JSON only")
                    break
                now = asyncio.get_running_loop().time()
                if previous_request and now-previous_request < 0.015:
                    await socket.close(code=1008, message=b"AUDIO fader stream rate exceeded")
                    break
                previous_request = now
                try:
                    payload = strict_json_object(message.data.encode("utf-8"))
                    validate_audio_command(payload)
                    if payload["command"]["type"] != "fader":
                        raise AudioError("AUDIO stream permits only catalog fader commands")
                    ack = await service.audio.command(payload)
                    await send(ack)
                except (ProtocolError, AudioError):
                    await socket.close(code=1008, message=b"AUDIO fader request unconfirmed; reconcile without replay")
                    break
        finally:
            service._audio_stream_count -= 1
            publisher.cancel()
            try:
                await publisher
            except (asyncio.CancelledError, ConnectionError):
                pass
        return socket

    async def on_startup(_app: Any) -> None:
        await service.start()

    async def on_cleanup(_app: Any) -> None:
        await service.stop()

    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    app.router.add_get("/v1/sonar", snapshot)
    app.router.add_get("/v1/helm", helm_snapshot)
    app.router.add_get("/v1/map/routes", map_route_resources)
    app.router.add_put("/v1/map/routes/{id}", map_route_resources)
    app.router.add_delete("/v1/map/routes/{id}", map_route_resources)
    app.router.add_get("/v1/audio", audio_snapshot)
    app.router.add_get("/v1/audio/sound", audio_sound)
    app.router.add_get("/v1/audio/sound/stream", audio_sound_commands_stream)
    app.router.add_get("/v1/audio/spectrum", audio_spectrum)
    app.router.add_get("/v1/audio/stream", audio_stream)
    app.router.add_get("/v1/audio/media", media_snapshot)
    app.router.add_get("/v1/audio/artwork/{identifier}", media_artwork)
    app.router.add_get("/v1/audio/playlist-artwork/{identifier}", media_artwork)
    app.router.add_get("/v1/audio/browse", media_browse)
    app.router.add_get("/v1/audio/navigation", media_navigation)
    app.router.add_post("/v1/audio/select", media_select)
    app.router.add_get("/v1/weather", weather_snapshot)
    app.router.add_get("/v1/sonar/stream", stream)
    app.router.add_post("/v1/sonar/command", command)
    app.router.add_get("/v1/sonar/diagnostics", sonar_diagnostics)
    app.router.add_get("/v1/sonar/source", sonar_source)
    app.router.add_put("/v1/sonar/source", sonar_source)
    app.router.add_get("/v1/sonar/filter", sonar_filter)
    app.router.add_put("/v1/sonar/filter", sonar_filter)
    app.router.add_get("/v1/sonar/commands/{id}", sonar_command_result)
    app.router.add_post("/v1/audio/command", audio_command)
    app.router.add_post("/v1/audio/commands", audio_commands)
    app.router.add_get("/v1/audio/commands/stream", audio_command_results_stream)
    app.router.add_get("/v1/audio/commands/{id}", audio_command_result)
    app.router.add_post("/v2/lumina/enroll", lumina_enroll)
    app.router.add_post("/v2/lumina/revoke", lumina_revoke)
    app.router.add_get("/v1/status", gateway_status)
    app.router.add_get("/admin/simulation", simulation_ui)
    app.router.add_get('/admin/v1/sonar/forwarding',sonar_forwarding)
    app.router.add_put('/admin/v1/sonar/forwarding',sonar_forwarding)
    app.router.add_delete('/admin/v1/sonar/forwarding',sonar_forwarding)
    app.router.add_get("/admin/v1/simulation", simulation_state)
    app.router.add_post("/admin/v1/simulation", simulation_command)
    # These loopback-only paths are intentionally not public API. Nginx maps
    # the browser's /camilladsp/session/logout and internal auth subrequest
    # here; its external /internal/ route is an unconditional 404.
    app.router.add_post("/internal/camillagui/login", camilla_gui_login)
    app.router.add_get("/internal/camillagui/auth", camilla_gui_auth)
    app.router.add_post("/internal/camillagui/logout", camilla_gui_logout)
    app.router.add_get("/healthz", healthz)
    app.router.add_get("/readyz", readyz)
    return app

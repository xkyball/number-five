"""Gateway state, provenance and command admission independent of HTTP tooling."""

from __future__ import annotations

from dataclasses import dataclass
import time
import uuid

from .configuration import GatewayConfig
from .protocol import EchogramFrame, ProtocolError, encode_android_ping
from .sonar_presentation import EMPTY_BOTTOM, geometry
from .sonar_contract import (ANALYSIS_VERSIONS, SOURCE_VERSIONS, package_version,
                             amplitude, display_selection, observation_reason, host_session_id, clone,
                             PUBLISHED_VERSIONS)
from .sonar_published import host_context


MAX_SIGNED_64 = (1 << 63) - 1


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


@dataclass(frozen=True, slots=True)
class CommandAck:
    client_id: str
    request_id: int
    outcome: str
    reason: str | None
    state: dict[str, object]

    def as_contract(self) -> dict[str, object]:
        return {
            "version": 1,
            "kind": "sonar.ack",
            "serverSessionId": self.state["serverSessionId"],
            "generation": self.state["generation"],
            "clientId": self.client_id,
            "requestId": self.request_id,
            "outcome": self.outcome,
            "reason": self.reason,
            "state": self.state,
        }


class CommandLedger:
    """Bounded no-eviction watermark tracker for acknowledged/rejected requests."""

    def __init__(self, maximum_clients: int = 32) -> None:
        self._maximum_clients = maximum_clients
        self._last_request: dict[str, int] = {}

    def consume(self, client_id: str, request_id: int) -> str | None:
        previous = self._last_request.get(client_id)
        if previous is None:
            if len(self._last_request) >= self._maximum_clients:
                return "REQUEST_LEDGER_FULL"
        elif request_id <= previous:
            return "STALE_REQUEST"
        # Do not evict old clients: a rejected request stays consumed so that it
        # cannot become valid later when the gateway state changes.
        self._last_request[client_id] = request_id
        return None


class GatewayState:
    """Maps upstream evidence into the existing Android SONAR V1 contract."""

    def __init__(self, config: GatewayConfig, *, session_id: str | None = None,
                 now: callable = epoch_ms) -> None:
        self.config = config
        self.server_session_id = session_id or str(uuid.uuid4())
        self._now = now
        self.generation = 0
        self.revision = 0
        self._connected = False
        self._upstream_running = False
        self._upstream_state: str | None = None
        self._last_ping: EchogramFrame | None = None
        self._last_received_epoch_ms: int | None = None
        self._declared_cadence_hz = config.declared_cadence_hz
        self._last_error: str | None = None
        self.commands = CommandLedger()
        self.filter_supported = False
        self.source_selection_supported = False
        self.diagnostics_supported = False
        self.display_selection = None
        self.amplitude = None
        self.host_package_version = None
        self.upstream_session_id = None
        self.upstream_publication_context = None
        self._publication_policy = None
        self._observation_reason = None
        self.unconfirmed_source_frames = 0
        self.old_selection_frames_dropped = 0

    def begin_connection(self) -> None:
        self.filter_supported = False
        self.source_selection_supported = False
        self.diagnostics_supported = False
        self.display_selection = None
        self.amplitude = None
        self.host_package_version = None
        self.upstream_session_id = None
        self.upstream_publication_context = None
        self._publication_policy = None
        self._observation_reason = None
        self.generation += 1
        self._connected = True
        self._upstream_running = False
        self._upstream_state = None
        self._last_ping = None
        self._last_received_epoch_ms = None
        self._last_error = None

    def select_source(self, config: GatewayConfig) -> None:
        """Atomically forget observations from a different M0/M1 producer.

        The service calls this before it starts the replacement upstream task,
        so an old producer can never be labelled as the newly selected one.
        """

        self.config = config
        self.filter_supported = self.source_selection_supported = self.diagnostics_supported = False
        self.display_selection = self.amplitude = self.host_package_version = None
        self.upstream_session_id = None
        self.upstream_publication_context = None
        self._publication_policy = None
        self._observation_reason = None
        self.generation += 1
        self._connected = False
        self._upstream_running = False
        self._upstream_state = None
        self._last_ping = None
        self._last_received_epoch_ms = None
        self._declared_cadence_hz = config.declared_cadence_hz
        self._last_error = None

    def disconnect(self, reason: str) -> None:
        self.filter_supported = self.source_selection_supported = self.diagnostics_supported = False
        self._connected = False
        self._upstream_running = False
        self._last_error = reason[:200]

    def observe_status(self, upstream: object) -> None:
        if not isinstance(upstream, dict):
            raise ProtocolError("upstream status must be an object")
        source = upstream.get("source")
        state = upstream.get("state")
        if not isinstance(source, str) or not isinstance(state, str):
            raise ProtocolError("upstream status needs source and state strings")
        if self.config.producer == "m0" and source != "synthetic":
            raise ProtocolError("M0 contract may only advertise synthetic source data")
        if self.config.producer == "m1" and source != "pico":
            raise ProtocolError("M1 gateway supports only the confirmed pico source")
        self._upstream_state = state
        self.host_package_version = package_version(upstream) if self.config.producer == "m1" else None
        self.upstream_session_id = host_session_id(upstream) if self.host_package_version in SOURCE_VERSIONS else None
        context = host_context(upstream) if self.host_package_version in PUBLISHED_VERSIONS else None
        if (self.upstream_publication_context is not None and context is not None and
                context != self.upstream_publication_context):
            self.generation += 1
            self._last_ping = self._last_received_epoch_ms = None
            self.display_selection = None
            self._publication_policy = None
        self.upstream_publication_context = context
        self.filter_supported = self.host_package_version in ANALYSIS_VERSIONS
        self.diagnostics_supported = self.host_package_version in SOURCE_VERSIONS
        self.amplitude = amplitude(upstream) if self.config.producer == "m1" else None
        self._observation_reason = (observation_reason(upstream, status_received_at=self._now())
                                    if self.config.producer == "m1" else None)
        self.source_selection_supported = False
        if self.host_package_version in SOURCE_VERSIONS:
            try:
                selected = display_selection(upstream.get("display_selection"))
                if (self._last_ping is not None and self._last_ping.ping_id == selected['lastPublishedPingId'] and
                        self._last_ping.display_selection is not None and
                        self._last_ping.display_selection['generation'] == selected['generation']):
                    selected['observedAtEpochMs'] = self._last_ping.observed_at_epoch_ms
                self.observe_display_selection(selected)
                self.source_selection_supported = True
            except ProtocolError:
                self._last_error = "Sonard display selection unconfirmed"
        self._upstream_running = state in {"SIMULATING", "RUNNING"}
        rates = upstream.get("rates")
        candidate: object = None
        if isinstance(rates, dict):
            candidate = rates.get("ping_hz")
        if isinstance(candidate, (int, float)) and not isinstance(candidate, bool) and candidate > 0:
            self._declared_cadence_hz = min(20.0, max(0.1, float(candidate)))

    def observe_display_selection(self, selected):
        """A host selection epoch is not the gateway transport epoch."""
        previous = self.display_selection
        if previous is not None:
            if selected['generation'] < previous['generation']:
                raise ProtocolError("older Sonard selection generation")
            if selected['generation'] == previous['generation'] and selected['source'] != previous['source']:
                raise ProtocolError("Sonard changed source without selection generation")
            if selected['generation'] != previous['generation']:
                self.generation += 1
                self._last_ping = self._last_received_epoch_ms = None
                self._publication_policy = None
        selected = clone(selected)
        if (selected.get('observedAtEpochMs') is None and previous is not None and
                selected['generation'] == previous['generation'] and
                selected['lastPublishedPingId'] == previous['lastPublishedPingId']):
            selected['observedAtEpochMs'] = previous['observedAtEpochMs']
        self.display_selection = selected

    def accept_ping(self, ping: EchogramFrame) -> bytes | None:
        """Accept one latest upstream packet; return Android binary framing or none."""

        if ping.host_package_version in PUBLISHED_VERSIONS:
            publication = ping.publication
            selected = self.display_selection
            if (ping.host_context_hint is None or
                    ping.host_context_hint != self.upstream_publication_context or
                    (publication is not None and
                     (selected is None or publication.display_generation != selected['generation'] or
                      publication.selection_generation != selected.get('selectionGeneration')))):
                self.old_selection_frames_dropped += 1
                return None
        if self.display_selection is not None and ping.host_package_version in SOURCE_VERSIONS:
            # Missing optional analysis never drops a compatible raw frame.
            # It has no claimed own/native source or canonical bottom. A known
            # old selection epoch is different: its queued data must not enter
            # a newer selection, even if analysis never catches up.
            if ping.display_selection is None:
                current = self.display_selection
                if (current is not None and ping.selection_generation_hint is not None and
                        ping.selection_generation_hint < current['generation']):
                    self.old_selection_frames_dropped += 1
                    return None
                self.unconfirmed_source_frames += 1
            else:
                try: self.observe_display_selection(ping.display_selection)
                except ProtocolError:
                    self.old_selection_frames_dropped += 1
                    return None
        if ping.host_package_version in PUBLISHED_VERSIONS and ping.publication is not None:
            publication = ping.publication
            policy = (publication.selection_generation, publication.display_generation,
                      publication.noise_revision)
            previous_policy = self._publication_policy
            if (previous_policy is not None and policy[:2] == previous_policy[:2] and
                    policy[2] < previous_policy[2]):
                self.old_selection_frames_dropped += 1
                return None
            # Absence of publication evidence cannot replace a confirmed epoch.
            # Effective per-ping noiseActive is an interpretation boundary, not
            # a policy change. Its full amplitude context still travels with
            # every frame so clients neither blend nor relabel those profiles.
            if previous_policy is not None and policy != previous_policy:
                self.generation += 1
                self._last_ping = self._last_received_epoch_ms = None
            self._publication_policy = policy
        previous = self._last_ping
        if previous is not None:
            if ping.ping_id == previous.ping_id and ping.observed_at_epoch_ms == previous.observed_at_epoch_ms:
                return None  # duplicate is not a fresh observation
            explicit_context = (ping.host_package_version in PUBLISHED_VERSIONS and
                                ping.host_context_hint == self.upstream_publication_context and
                                ping.host_context_hint is not None)
            if (ping.ping_id < previous.ping_id or
                    (ping.observed_at_epoch_ms < previous.observed_at_epoch_ms and not explicit_context)):
                # Legacy producers expose no acquisition identity, so a clock
                # regression is their only reset evidence. A validated modern
                # session/segment already identifies the acquisition: a clock-fit
                # adjustment must not invent an epoch and clear client history.
                # Keep the original timestamp; freshness and client ordering still
                # reject stale/out-of-order evidence without retiming any sample.
                self.generation += 1
                self._last_ping = None
                self._last_received_epoch_ms = None
        self._last_ping = ping
        self._last_received_epoch_ms = self._now()
        return encode_android_ping(self.ping_metadata(ping), ping.samples)

    def _fresh(self) -> bool:
        if self._last_ping is None or self._last_received_epoch_ms is None:
            return False
        # Freshness intentionally follows producer source time, not arrival time.
        # Arrival time only bounds whether this adapter still has a live feed.
        source_age = self._now() - self._last_ping.observed_at_epoch_ms
        arrival_age = self._now() - self._last_received_epoch_ms
        return (-5_000 <= source_age <= self.config.stale_after_s * 1000 and
                arrival_age <= self.config.stale_after_s * 1000)

    def status_name(self) -> str:
        if not self._connected:
            return "UNAVAILABLE"
        if not self._upstream_running:
            return "IDLE"
        return "LIVE" if self._fresh() else "STALE"

    def snapshot(self) -> dict[str, object]:
        status = self.status_name()
        running = self._connected and self._upstream_running
        last = self._last_ping
        reason = clone(self._observation_reason)
        if reason is not None:
            age = self._now() - reason['statusObservedAtEpochMs']
            reason['statusFresh'] = self._connected and -5000 <= age <= self.config.stale_after_s * 1000
        # Neither supported producer offers verified app-facing start/stop
        # semantics.  Advertising false prevents speculative upstream writes.
        return {
            "version": 1,
            "kind": "sonar.state",
            "source": self.config.source_name,
            "sourceKind": self.config.source_kind,
            "simulated": self.config.simulated,
            "serverSessionId": self.server_session_id,
            "generation": self.generation,
            "revision": self.revision,
            "publishedAtEpochMs": self._now(),
            "running": running,
            "status": status,
            "capabilities": {
                "canStart": False,
                "canStop": False,
                "bottomDetection": True,
                "filterControl": self.filter_supported,
                "confirmedTracks": self.filter_supported,
                "sourceSelection": self.source_selection_supported,
                "diagnostics": self.diagnostics_supported,
            },
            "format": {
                "bins": 1024,
                "sampleType": "UINT16",
                "byteOrder": "LITTLE_ENDIAN",
            },
            "calibration": self.config.calibration.as_contract(),
            "cadenceHz": self._declared_cadence_hz,
            "observedAtEpochMs": None if last is None else last.observed_at_epoch_ms,
            "lastPingId": None if last is None else last.ping_id,
            "displaySelection": clone(self.display_selection),
            "amplitude": clone(self.amplitude),
            "hostPackageVersion": self.host_package_version,
            "observationReason": reason,
        }

    def ping_metadata(self, ping: EchogramFrame) -> dict[str, object]:
        result = {
            "version": 1,
            "kind": "sonar.ping",
            "source": self.config.source_name,
            "sourceKind": self.config.source_kind,
            "simulated": self.config.simulated,
            "serverSessionId": self.server_session_id,
            "generation": self.generation,
            "pingId": ping.ping_id,
            "observedAtEpochMs": ping.observed_at_epoch_ms,
            "publishedAtEpochMs": self._now(),
            "format": {
                "bins": 1024,
                "sampleType": "UINT16",
                "byteOrder": "LITTLE_ENDIAN",
            },
            "calibration": self.config.calibration.as_contract(),
            "bottom": ping.bottom(),
            "displaySelection": clone(ping.display_selection),
            "amplitude": clone(ping.amplitude),
            "pingSource": ping.ping_source,
        }
        if ping.analysis_supported:
            result.update({"geometry": geometry(ping), "presentation": None if ping.presentation is None else
                           {key: value for key, value in ping.presentation.items() if key not in {"bottom", "samplePeriodNs"}},
                           "hostPackageVersion": ping.host_package_version,
                           "legacyBottom": ping.bottom(),
                           "bottom": (dict(EMPTY_BOTTOM) if ping.presentation is None
                                      else ping.presentation["bottom"])})
        if ping.publication is not None:
            result['publication'] = {'version': 1, 'sourceEvidence': ping.publication.source_evidence}
            if ping.publication_sequence is not None:
                result['publication'].update(sequence=str(ping.publication_sequence),
                                             sequenceGap=ping.publication_sequence_gap)
        import json
        presentation = result.get("presentation")
        while (len(json.dumps(result, separators=(",", ":"), allow_nan=False).encode()) > 4096 and
               isinstance(presentation, dict) and presentation.get("tracks")):
            presentation["tracks"] = presentation["tracks"][:-1]
            presentation["trackOmitted"] = presentation["trackTotal"] - len(presentation["tracks"])
        return result

    def reject_command(self, command: dict[str, object]) -> CommandAck:
        """Validate and consume a command without ever writing to M0/M1."""

        required = {
            "version", "serverSessionId", "generation", "expectedRevision",
            "clientId", "requestId", "command",
        }
        if set(command) != required:
            raise ProtocolError("command must contain exactly the SONAR V1 envelope fields")
        if command["version"] != 1:
            raise ProtocolError("unsupported command version")
        client_id = command["clientId"]
        request_id = command["requestId"]
        if (not isinstance(client_id, str) or not 1 <= len(client_id) <= 200 or
                not client_id.isprintable()):
            raise ProtocolError("clientId must be 1..200 printable characters")
        for field in ("generation", "expectedRevision", "requestId"):
            value = command[field]
            if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= MAX_SIGNED_64:
                raise ProtocolError(f"{field} must be a non-negative signed 64-bit integer")
        if command["command"] not in {"start", "stop"}:
            raise ProtocolError("command must be start or stop")
        if not isinstance(command["serverSessionId"], str):
            raise ProtocolError("serverSessionId must be a string")
        state = self.snapshot()
        watermark_error = self.commands.consume(client_id, request_id)
        reason: str
        if watermark_error:
            reason = watermark_error
        elif command["serverSessionId"] != self.server_session_id:
            reason = "WRONG_SESSION"
        elif command["generation"] != self.generation:
            reason = "WRONG_GENERATION"
        elif command["expectedRevision"] != self.revision:
            reason = "STALE_REVISION"
        else:
            # M0 controls only its simulator and M1 has no REST start/stop API.
            # The rejection state is readback; no upstream mutation was attempted.
            reason = "UPSTREAM_COMMAND_UNAVAILABLE"
        return CommandAck(client_id, request_id, "REJECTED", reason, state)

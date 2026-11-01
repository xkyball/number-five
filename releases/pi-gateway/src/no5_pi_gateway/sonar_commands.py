"""Bounded filter goals for the existing Sonard API; no acquisition/TX writes."""
from __future__ import annotations
import asyncio
from collections import OrderedDict
from dataclasses import dataclass
import json
import time
from .protocol import ProtocolError, strict_json_object
from .sonar_contract import display_selection, SOURCES
from .sonar_diagnostics import read_json

MODES = {"off", "auto", "on"}
TERMINAL = {"APPLIED", "SUPERSEDED", "CANCELLED", "REJECTED", "UNKNOWN"}


@dataclass(slots=True)
class Job:
    identifier: int
    owner: str
    request: dict
    valid: object
    accepted: int
    status: str = "ACCEPTED"
    reason: str = "NONE"
    replacement: int | None = None
    confirmed: str | None = None
    selection_generation: int | None = None

    def receipt(self):
        result = {"version": 1, "kind": "sonar-command-result", "commandId": self.identifier,
                "clientId": self.request["clientId"], "requestId": self.request["requestId"],
                "serverSessionId": self.request["serverSessionId"], "generation": self.request["generation"],
                "status": self.status, "reason": self.reason,
                "replacementId": self.replacement, "acceptedAtEpochMs": self.accepted}
        key = "source" if "source" in self.request else "mode"
        result.update({key: self.request[key], "confirmedSource" if key == "source" else "confirmedMode": self.confirmed})
        return result


class SonarCommands:
    CAPACITY = 64
    HISTORY = 256
    DIAGNOSTICS = 64

    def __init__(self, state, exchange=None, source_exchange=None):
        self.state = state
        self.exchange = exchange or self._exchange
        self.source_exchange = source_exchange or self._source_exchange
        self._diagnostics = [None] * self.DIAGNOSTICS
        self._diagnostic_counter = 0
        self._pending = [None] * self.CAPACITY
        self._head = self._size = self._counter = 0
        self._results = OrderedDict()
        self._requests = {}
        self._watermarks = {}
        self._closed = False
        self._wake = asyncio.Event()
        self._task = None

    def start(self):
        self._task = asyncio.create_task(self._run(), name="no5-sonar-filter-actor")

    def current(self, request):
        return (request["serverSessionId"] == self.state.server_session_id and
                request["generation"] == self.state.generation)

    def available(self, operation="filter"):
        supported = self.state.source_selection_supported if operation == "source" else self.state.filter_supported
        return supported and self.state.config.producer == "m1"

    def submit(self, owner, request, valid):
        key = "source" if "source" in request else "mode"
        operation = "source" if key == "source" else "filter"
        if set(request) != {"version", "serverSessionId", "generation", "clientId", "requestId", key}:
            raise ProtocolError("invalid sonar goal request fields")
        if (type(request["version"]) is not int or request["version"] != 1 or
                type(request["generation"]) is not int or not 0 <= request["generation"] < 2**63 or
                type(request["requestId"]) is not int or not 0 < request["requestId"] < 2**63 or
                not isinstance(request[key], str) or request[key] not in (SOURCES if key == "source" else MODES)):
            raise ProtocolError("invalid sonar goal request values")
        for key in ("clientId", "serverSessionId"):
            value = request[key]
            if not isinstance(value, str) or not 1 <= len(value) <= 200 or any(ord(c) < 32 for c in value):
                raise ProtocolError("invalid filter request identity")
        producer = owner, request["clientId"]
        key = producer, request["requestId"]
        previous = self._requests.get(key)
        if previous is not None:
            if previous.request != request: raise ProtocolError("request ID reused with another filter goal")
            return previous.receipt()
        if request["requestId"] <= self._watermarks.get(producer, 0):
            raise ProtocolError("old filter request; reconcile without replay")
        if producer not in self._watermarks and len(self._watermarks) >= 256:
            raise ProtocolError("filter producer capacity reached")
        self._watermarks[producer] = request["requestId"]
        self._counter += 1
        job = Job(self._counter, owner, dict(request), valid, time.time_ns() // 1_000_000,
                  selection_generation=(None if self.state.display_selection is None else self.state.display_selection['generation']))
        self._results[job.identifier] = job
        self._requests[key] = job
        if self._closed: self._finish(job, "REJECTED", "CLOSED")
        elif not self.current(request): self._finish(job, "CANCELLED", "SOURCE_CHANGED")
        elif not self.available(operation): self._finish(job, "REJECTED", "UNSUPPORTED")
        else:
            replaced = False
            for age in range(self._size - 1, -1, -1):
                position = (self._head + age) % self.CAPACITY
                old = self._pending[position]
                if (old.owner, old.request["clientId"]) == producer:
                    if ("source" in old.request) != ("source" in request): break  # source/filter ordering barrier
                    old.replacement = job.identifier
                    self._finish(old, "SUPERSEDED", "NEWER_TARGET")
                    self._pending[position] = job
                    replaced = True
                    break
            if not replaced:
                if self._size == self.CAPACITY: self._finish(job, "REJECTED", "BUSY")
                else:
                    self._pending[(self._head + self._size) % self.CAPACITY] = job
                    self._size += 1
            self._wake.set()
        self._trim()
        return job.receipt()

    def read(self, owner, identifier):
        job = self._results.get(identifier)
        return job.receipt() if job is not None and job.owner == owner else None

    def _take(self):
        job = self._pending[self._head]
        self._pending[self._head] = None
        self._head = (self._head + 1) % self.CAPACITY
        self._size -= 1
        return job

    def _trim(self):
        count = sum(j.status in TERMINAL for j in self._results.values())
        for identifier, job in tuple(self._results.items()):
            if count <= self.HISTORY: break
            if job.status in TERMINAL:
                self._results.pop(identifier)
                self._requests.pop(((job.owner, job.request["clientId"]), job.request["requestId"]))
                count -= 1

    def _finish(self, job, status, reason):
        job.status, job.reason, job.valid = status, reason, None
        self._diagnostics[self._diagnostic_counter % self.DIAGNOSTICS] = {
            "commandId": job.identifier, "operation": "source" if "source" in job.request else "filter",
            "status": status, "reason": reason, "acceptedAtEpochMs": job.accepted,
            "finishedAtEpochMs": time.time_ns() // 1_000_000}
        self._diagnostic_counter += 1
        self._trim()

    async def source_snapshot(self):
        if not self.available("source"): raise ProtocolError("Sonard source selection unavailable")
        generation = self.state.generation; base = self.state.config.upstream_base_url
        selected = display_selection(await self.source_exchange(base, None))
        if generation != self.state.generation or base != self.state.config.upstream_base_url:
            raise ProtocolError("Sonard source changed")
        self.state.observe_display_selection(selected)
        return {"version": 1, "kind": "sonar-source", "serverSessionId": self.state.server_session_id,
                "generation": self.state.generation, "displaySelection": selected}


    async def snapshot(self):
        if not self.available(): raise ProtocolError("Sonard filter unavailable")
        generation = self.state.generation
        base = self.state.config.upstream_base_url
        payload = await self.exchange(base, None)
        if generation != self.state.generation or base != self.state.config.upstream_base_url:
            raise ProtocolError("Sonard source changed")
        policy = payload.get("policy")
        if not isinstance(policy, dict) or policy.get("mode") not in MODES:
            raise ProtocolError("invalid Sonard filter policy")
        selection = payload.get("selection")
        reason = selection.get("reason") if isinstance(selection, dict) else None
        if reason is not None and not isinstance(reason, str):
            raise ProtocolError("invalid Sonard filter selection reason")
        return {"version": 1, "kind": "sonar-filter", "serverSessionId": self.state.server_session_id,
                "generation": generation, "mode": policy["mode"],
                "effectiveMode": selection.get("effective_mode") if isinstance(selection, dict) else None,
                "active": selection.get("active") if isinstance(selection, dict) else None,
                "reason": None if reason is None else reason[:512]}

    async def _run(self):
        while not self._closed:
            if not self._size:
                self._wake.clear()
                await self._wake.wait()
                continue
            job = self._take()
            if not job.valid(): self._finish(job, "CANCELLED", "AUTHORIZATION_EXPIRED"); continue
            if not self.current(job.request): self._finish(job, "CANCELLED", "SOURCE_CHANGED"); continue
            operation = "source" if "source" in job.request else "filter"
            if not self.available(operation): self._finish(job, "REJECTED", "UNSUPPORTED"); continue
            job.status = "RUNNING"
            try:
                if operation == "source":
                    # Fresh preflight detects a changed host selection before
                    # sending. Host PUT itself has no CAS primitive; never
                    # invent an atomic upstream guarantee or retry its outcome.
                    base = self.state.config.upstream_base_url
                    before = display_selection(await self.source_exchange(base, None))
                    if not job.valid():
                        self._finish(job, "CANCELLED", "AUTHORIZATION_EXPIRED"); continue
                    if (not self.current(job.request) or before['generation'] != job.selection_generation or
                            self.state.display_selection is None or before['source'] != self.state.display_selection['source']):
                        self._finish(job, "CANCELLED", "SOURCE_CHANGED"); continue
                    confirmed = display_selection(await self.source_exchange(base, job.request['source']))
                    if (not self.current(job.request) or not job.valid() or confirmed['source'] != job.request['source'] or
                            confirmed['generation'] < before['generation'] or
                            (confirmed['source'] != before['source'] and confirmed['generation'] == before['generation'])):
                        self._finish(job, "UNKNOWN", "COMPLETION_UNKNOWN")
                    else:
                        self.state.observe_display_selection(confirmed)
                        job.confirmed = confirmed['source']
                        self._finish(job, "APPLIED", "NONE")
                    continue
                # Exactly one PUT. A missing/ambiguous answer is never retried.
                payload = await self.exchange(self.state.config.upstream_base_url, job.request["mode"])
                policy = payload.get("policy")
                if not self.current(job.request) or not isinstance(policy, dict) or policy.get("mode") != job.request["mode"]:
                    self._finish(job, "UNKNOWN", "COMPLETION_UNKNOWN")
                else:
                    job.confirmed = policy["mode"]
                    self._finish(job, "APPLIED", "NONE")
            except asyncio.CancelledError:
                self._finish(job, "UNKNOWN", "COMPLETION_UNKNOWN")
                raise
            except Exception:
                self._finish(job, "UNKNOWN", "COMPLETION_UNKNOWN")

    async def stop(self):
        self._closed = True
        self._wake.set()
        while self._size: self._finish(self._take(), "CANCELLED", "CLOSED")
        if self._task:
            self._task.cancel()
            try: await self._task
            except asyncio.CancelledError: pass
            self._task = None

    async def _exchange(self, base, mode):
        import aiohttp
        timeout = aiohttp.ClientTimeout(total=self.state.config.upstream_timeout_s)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            kwargs = {} if mode is None else {"json": {"mode": mode}}
            async with session.request("GET" if mode is None else "PUT", base + "/api/v1/detection/filter", **kwargs) as response:
                if response.status != 200: raise ProtocolError("Sonard filter response not confirmed")
                raw = bytearray()
                async for chunk in response.content.iter_chunked(8192):
                    raw.extend(chunk)
                    if len(raw) > 256 * 1024: raise ProtocolError("Sonard filter response exceeds bounds")
                return strict_json_object(bytes(raw))

    async def _source_exchange(self, base, source):
        import aiohttp
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.state.config.upstream_timeout_s)) as session:
            kwargs = {} if source is None else {"json": {"source": source}}
            async with session.request("GET" if source is None else "PUT", base + "/api/v1/sonar/display-selection", **kwargs) as response:
                if response.status != 200: raise ProtocolError("Sonard source response not confirmed")
                return await read_json(response, 16384)

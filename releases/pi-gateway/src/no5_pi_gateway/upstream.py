"""Read-only M0/M1 `no5-sonard` upstream client."""

from __future__ import annotations

import asyncio
import contextlib
from collections import deque, OrderedDict
from dataclasses import replace
from typing import Awaitable, Callable
from urllib.parse import urlparse, urlunparse

from .configuration import GatewayConfig
from .protocol import EchogramFrame, ProtocolError, parse_echogram_frame
from .sonar_presentation import project
from .sonar_contract import (package_version, amplitude, frame_context, display_selection,
                             publication_amplitude, PUBLISHED_VERSIONS, ATOMIC_CONTEXT_VERSIONS, clone)
from .sonar_diagnostics import read_json
from .sonar_published import PublishedPingCache, host_context, parse_page, MAX_BODY_BYTES
from .sonar_context import parse_atomic_echogram, MAX_MESSAGE_BYTES as ATOMIC_MAX_MESSAGE_BYTES


StatusCallback = Callable[[dict[str, object]], Awaitable[None]]
FrameCallback = Callable[[object], Awaitable[None]]
ConnectionCallback = Callable[[], Awaitable[None]]
FailureCallback = Callable[[str], Awaitable[None]]


def upstream_websocket_url(base_url: str, host_version: str | None = None) -> str:
    parsed = urlparse(base_url)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    path = "/ws/v1/echogram-context" if host_version in ATOMIC_CONTEXT_VERSIONS else "/ws/v1/echogram"
    return urlunparse((scheme, parsed.netloc, path, "", "", ""))


class SonardUpstream:
    """Reconnectable, read-only adapter for the confirmed upstream endpoints."""

    def __init__(self, config: GatewayConfig, on_status: StatusCallback,
                 on_frame: FrameCallback, on_connected: ConnectionCallback,
                 on_failure: FailureCallback) -> None:
        self._config = config
        self._on_status = on_status
        self._on_frame = on_frame
        self._on_connected = on_connected
        self._on_failure = on_failure
        self._stop = asyncio.Event()
        self._host_package_version: str | None = None
        self._amplitude = None
        self._selection_generation = None
        self._selection = None
        self._host_context = None
        self._published = PublishedPingCache()
        self._analysis_payload = None
        self._analysis_context = None
        self._analysis_cache = OrderedDict()
        self._analysis_unavailable = None
        self._reset_atomic_context()

    async def stop(self) -> None:
        self._stop.set()

    async def run(self) -> None:
        if self._config.producer == "disabled":
            await self._on_failure("No upstream producer configured")
            return
        # Keep the dependency local to runtime paths: protocol tests can run on
        # a development host before the Pi's aiohttp package is installed.
        import aiohttp

        assert self._config.upstream_base_url is not None
        timeout = aiohttp.ClientTimeout(total=self._config.upstream_timeout_s)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            while not self._stop.is_set():
                try:
                    self._reset_atomic_context()
                    await self._fetch_status(session)
                    async with session.ws_connect(upstream_websocket_url(self._config.upstream_base_url, self._host_package_version),
                                                  heartbeat=10.0,
                                                  max_msg_size=(ATOMIC_MAX_MESSAGE_BYTES + 1 if
                                                      self._host_package_version in ATOMIC_CONTEXT_VERSIONS else
                                                      UPSTREAM_MAX_FRAME_BYTES)) as socket:
                        await self._on_connected()
                        # begin_connection creates a fresh local generation;
                        # refresh status immediately so it retains only current
                        # upstream running/capability evidence.
                        await self._fetch_status(session)
                        await self._receive(socket, session)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    await self._on_failure(f"upstream unavailable: {type(exc).__name__}")
                if not self._stop.is_set():
                    with contextlib.suppress(asyncio.TimeoutError):
                        await asyncio.wait_for(self._stop.wait(), self._config.reconnect_delay_s)

    async def _fetch_status(self, session: object) -> None:
        assert self._config.upstream_base_url is not None
        requested_epoch = self._atomic_target_epoch
        async with session.get(f"{self._config.upstream_base_url}/api/v1/status") as response:
            if response.status != 200:
                raise ProtocolError(f"upstream status returned HTTP {response.status}")
            payload = await read_json(response)
        if not isinstance(payload, dict):
            raise ProtocolError("upstream status JSON must be an object")
        candidate_version = package_version(payload)
        if candidate_version in ATOMIC_CONTEXT_VERSIONS or self._atomic_target_epoch is not None:
            if candidate_version not in ATOMIC_CONTEXT_VERSIONS:
                raise ProtocolError('atomic status producer changed to an unsupported version')
            candidate = display_selection(payload.get('display_selection'))
            context = host_context(payload)
            if context is None or 'selectionGeneration' not in candidate:
                raise ProtocolError('atomic status context or selection is missing')
            epoch = (context, candidate['generation'], candidate['selectionGeneration'], candidate['source'])
            target = self._atomic_target_epoch
            if target is not None:
                target_context, display, selection, source = target
                if context != target_context:
                    # A fresh status can report a new segment even before its
                    # first ping. An older request cannot overrule an envelope
                    # or status epoch observed while that request was pending.
                    if target != requested_epoch or context in self._atomic_retired_contexts:
                        return
                elif (candidate['generation'] < display or candidate['selectionGeneration'] < selection or
                      (candidate['generation'] == display and candidate['source'] != source)):
                    return
            self._adopt_atomic_epoch(epoch)
        self._host_package_version = package_version(payload)
        self._amplitude = amplitude(payload, scale_only=True)
        previous_selection = self._selection
        self._selection_generation = None
        self._selection = None
        try:
            self._selection = display_selection(payload.get("display_selection"))
            self._selection_generation = self._selection['generation']
        except ProtocolError: pass
        context = host_context(payload)
        selection_epoch = lambda value: (None if value is None else
                                         (value['generation'], value.get('selectionGeneration')))
        if (context != self._host_context or
                selection_epoch(previous_selection) != selection_epoch(self._selection)):
            self._analysis_cache.clear()
            self._analysis_payload = None
            self._analysis_context = None
            self._analysis_unavailable = None
        self._host_context = context
        self._published.set_context(self._host_context)
        await self._on_status(payload)

    async def _receive(self, socket: object, session: object) -> None:
        import aiohttp

        pending: deque[tuple[EchogramFrame, float]] = deque()
        wake = asyncio.Event()
        lock = asyncio.Lock()
        atomic = self._host_package_version in ATOMIC_CONTEXT_VERSIONS
        tasks = [asyncio.create_task(self._analysis_loop(session, pending, wake, lock))]
        if self._host_package_version in PUBLISHED_VERSIONS:
            self._analysis_payload = None
            self._analysis_unavailable = None
            tasks.append(asyncio.create_task(self._optional_analysis_loop(session, pending, wake)))
            if atomic:
                tasks.append(asyncio.create_task(self._atomic_status_loop(session)))
            else:
                tasks.append(asyncio.create_task(self._publication_loop(session, pending, wake)))
        try:
            if atomic:
                # Supervise every worker: idle socket disconnects and failed
                # status reads must cancel siblings, never leave orphan tasks.
                tasks.append(asyncio.create_task(self._receive_frames(socket, session, pending, wake, lock)))
                done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    await task
            else:
                await self._receive_frames(socket, session, pending, wake, lock)
        finally:
            for task in tasks:
                task.cancel()
            if atomic:
                # Preserve the supervised failure while fully joining every
                # cancelled sibling before a replacement connection can start.
                await asyncio.gather(*tasks, return_exceptions=True)
            else:
                for task in tasks:
                    with contextlib.suppress(asyncio.CancelledError):
                        await task

    def _reset_atomic_context(self):
        self._atomic_target_epoch = None
        self._atomic_retired_contexts = deque(maxlen=64)
        self._atomic_sequence = None
        self._atomic_status_wake = asyncio.Event()
        self.atomic_sequence_gaps = 0
        self.atomic_rejected_frames = 0

    def _adopt_atomic_epoch(self, epoch):
        previous = self._atomic_target_epoch
        if previous is not None and previous[0] != epoch[0]:
            self._atomic_retired_contexts.append(previous[0])
            self._atomic_sequence = None
            self.atomic_sequence_gaps = 0
        self._atomic_target_epoch = epoch

    def _atomic_frame(self, envelope):
        epoch = envelope.epoch
        context, display, selection, source = epoch
        previous = self._atomic_target_epoch
        if previous is not None:
            old_context, old_display, old_selection, old_source = previous
            if context != old_context:
                if context in self._atomic_retired_contexts:
                    self.atomic_rejected_frames += 1
                    return None
            elif (display < old_display or selection < old_selection or
                  (display == old_display and source != old_source)):
                self.atomic_rejected_frames += 1
                return None
        if (previous is not None and previous[0] == context and self._atomic_sequence is not None and
                envelope.sequence <= self._atomic_sequence):
            self.atomic_rejected_frames += 1
            return None
        self._adopt_atomic_epoch(epoch)
        if self._atomic_sequence is not None and envelope.sequence <= self._atomic_sequence:
            self.atomic_rejected_frames += 1
            return None
        gap = 0 if self._atomic_sequence is None else envelope.sequence - self._atomic_sequence - 1
        self._atomic_sequence = envelope.sequence
        self.atomic_sequence_gaps += gap
        selected = self._selection
        if (self._host_context != context or selected is None or selected['generation'] != display or
                selected.get('selectionGeneration') != selection or selected['source'] != source):
            self.atomic_rejected_frames += 1
            self._atomic_status_wake.set()
            return None
        return replace(envelope.frame, publication=envelope.publication, display_selection=clone(selected),
                       publication_sequence=envelope.sequence, publication_sequence_gap=gap)

    async def _atomic_status_loop(self, session):
        loop = asyncio.get_running_loop()
        next_poll = loop.time() + self._config.status_poll_s
        next_allowed = loop.time()
        while not self._stop.is_set():
            with contextlib.suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self._atomic_status_wake.wait(), max(0., next_poll - loop.time()))
            self._atomic_status_wake.clear()
            await asyncio.sleep(max(0., next_allowed - loop.time()))
            if self._stop.is_set():
                return
            await self._fetch_status(session)
            next_allowed = loop.time() + .1
            next_poll = loop.time() + self._config.status_poll_s

    async def _receive_frames(self, socket: object, session: object,
                              pending: deque, wake: asyncio.Event, lock: asyncio.Lock) -> None:
        import aiohttp

        next_status_poll = asyncio.get_running_loop().time() + self._config.status_poll_s
        while not self._stop.is_set():
            try:
                message = await socket.receive(timeout=0.25)
            except asyncio.TimeoutError:
                message = None
            if message is not None:
                if message.type is aiohttp.WSMsgType.BINARY:
                    atomic = self._host_package_version in ATOMIC_CONTEXT_VERSIONS
                    if atomic:
                        frame = self._atomic_frame(parse_atomic_echogram(message.data))
                        if frame is None:
                            continue
                    else:
                        frame = parse_echogram_frame(message.data)
                    if self._host_package_version is None:
                        await self._on_frame(frame)
                    else:
                        frame = replace(frame, analysis_supported=True,
                                        host_package_version=self._host_package_version, amplitude=self._amplitude,
                                        selection_generation_hint=(frame.publication.display_generation if atomic else
                                                                   self._selection_generation),
                                        host_context_hint=(frame.host_context_hint if atomic else self._host_context))
                        async with lock:
                            if len(pending) == ANALYSIS_QUEUE_CAPACITY:
                                oldest, _ = pending.popleft()
                                await self._on_frame(self._with_publication(oldest))
                            pending.append((frame, asyncio.get_running_loop().time()))
                            wake.set()
                elif message.type in {aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSING,
                                      aiohttp.WSMsgType.ERROR}:
                    raise ProtocolError("upstream echogram websocket closed")
                elif message.type is aiohttp.WSMsgType.TEXT:
                    raise ProtocolError("upstream echogram websocket sent unexpected text")
            if (self._host_package_version not in ATOMIC_CONTEXT_VERSIONS and
                    asyncio.get_running_loop().time() >= next_status_poll):
                await self._fetch_status(session)
                next_status_poll = asyncio.get_running_loop().time() + self._config.status_poll_s

    async def _analysis_loop(self, session: object, pending: deque,
                             wake: asyncio.Event, lock: asyncio.Lock) -> None:
        """Bounded same-ping join; a lagging/failed HTTP analysis never relabels data.

        Frame reception continues during HTTP requests. A skipped/late analysis
        leaves that frame's canonical bottom unknown; it never falls back to the
        independent legacy detector on an enhanced host.
        """
        assert self._config.upstream_base_url is not None
        loop = asyncio.get_running_loop()
        while not self._stop.is_set():
            if not pending:
                wake.clear()
                await wake.wait()
            if self._host_package_version in PUBLISHED_VERSIONS:
                payload = self._analysis_payload
            else:
                payload = await self._fetch_analysis(session)
            async with lock:
                while pending:
                    frame, received = pending[0]
                    if frame.host_package_version in ATOMIC_CONTEXT_VERSIONS:
                        publication = frame.publication
                        selected = frame.display_selection
                        epoch = (None if publication is None or not isinstance(selected, dict) else
                                 (frame.host_context_hint, publication.display_generation,
                                  publication.selection_generation, selected['source']))
                        if epoch != self._atomic_target_epoch:
                            # A later confirmed/observed epoch retires queued
                            # old frames before their callback can publish them.
                            pending.popleft()
                            self.atomic_rejected_frames += 1
                            continue
                    joined = self._with_publication(frame)
                    projection = None
                    evidence = None
                    if frame.host_package_version in PUBLISHED_VERSIONS:
                        cached = self._cache_analysis(frame, payload)
                        if cached is not None:
                            projection, evidence = cached
                    elif payload is not None:
                        with contextlib.suppress(ProtocolError):
                            projection = project(frame, payload)
                    if frame.host_package_version in PUBLISHED_VERSIONS:
                        # An exact analysis still cannot supply missing origin
                        # evidence. It also cannot cross the publication epoch.
                        publication = joined.publication
                        if (publication is None or evidence is None or
                                evidence != (publication.display_generation, publication.selection_generation,
                                             publication.sample_period_ns, publication.actual_source)):
                            projection = None
                    detection = payload.get("detection") if isinstance(payload, dict) else None
                    superseded = (isinstance(detection, dict) and
                                  type(detection.get("ping_id")) is int and detection["ping_id"] > frame.ping_id and
                                  type(detection.get("time_utc_ns")) is int and detection["time_utc_ns"] >= frame.time_utc_ns)
                    if frame.host_package_version in PUBLISHED_VERSIONS:
                        # A newer optional analysis is not evidence that the
                        # independently recorded publication will never arrive.
                        if joined.publication is None and loop.time() - received < PUBLISHED_WAIT_S:
                            break
                        if projection is None and joined.publication is not None:
                            presentation = payload.get('presentation') if isinstance(payload, dict) else None
                            selected = presentation.get('display_selection') if isinstance(presentation, dict) else None
                            publication = joined.publication
                            superseded = (superseded and self._analysis_context == frame.host_context_hint and
                                          isinstance(selected, dict) and
                                          selected.get('generation') == publication.display_generation and
                                          selected.get('selection_generation') == publication.selection_generation)
                            absent = self._analysis_unavailable
                            unavailable_after_frame = (absent is not None and
                                absent[0] == (frame.host_context_hint, publication.display_generation) and
                                absent[1] >= received)
                            # A completed same-epoch optional read with no
                            # interpretation cannot improve this already
                            # proven raw profile. Do not hold it until the
                            # maximum join deadline. Older/different-epoch
                            # negative reads cannot shortcut a later frame.
                            if (not superseded and not unavailable_after_frame and
                                    loop.time() - received < PUBLISHED_WAIT_S):
                                break
                    elif projection is None and not superseded and loop.time() - received < ANALYSIS_WAIT_S:
                        break
                    pending.popleft()
                    if frame.host_package_version in PUBLISHED_VERSIONS:
                        await self._on_frame(replace(joined, presentation=projection))
                    else:
                        selection, origin = frame_context(frame, payload) if projection is not None else (None, None)
                        await self._on_frame(replace(frame, presentation=projection, display_selection=selection, ping_source=origin))
            remaining = (PUBLISHED_WAIT_S - (loop.time() - pending[0][1]) if
                         pending and self._host_package_version in PUBLISHED_VERSIONS else .02)
            await asyncio.sleep(min(.02, max(.001, remaining)))

    async def _publication_loop(self, session, pending, wake):
        # No network work or wait per frame. One page per fixed cycle is enough
        # for 640 publications/s, with a finite host/cache retention horizon.
        loop = asyncio.get_running_loop()
        next_poll = loop.time()
        while not self._stop.is_set():
            if not pending:
                # An idle timer slot is not an HTTP request. The first new
                # frame may use the already available rate budget immediately.
                wake.clear()
                if not pending:
                    await wake.wait()
                if self._stop.is_set():
                    return
                if not pending:
                    continue
                next_poll = max(next_poll, loop.time())
            await self._fetch_published(session, pending)
            wake.set()
            # The request already consumes this cycle's budget. Adding a whole
            # extra100ms sleep after a100ms timeout can postpone valid evidence
            # beyond a frame's250ms join deadline. Keep monotonic start deadlines
            # and skip missed cycles rather than issuing a catch-up burst.
            next_poll += PUBLISHED_POLL_S
            stamp = loop.time()
            if next_poll < stamp:
                next_poll = stamp
            if self._stop.is_set():
                return
            await asyncio.sleep(max(0., next_poll - stamp))

    async def _optional_analysis_loop(self, session, pending, wake):
        while not self._stop.is_set():
            if not pending:
                await wake.wait()
                if not pending:
                    await asyncio.sleep(.02)
                    continue
            context = (self._host_context, self._selection_generation)
            payload = await self._fetch_analysis(session)
            if context != (self._host_context, self._selection_generation):
                continue
            self._analysis_payload = payload
            self._analysis_context = context[0]
            self._analysis_unavailable = ((context, asyncio.get_running_loop().time())
                                          if payload is None else None)
            # Cache only the bounded display projection for an actually queued
            # binary header, never 64 copies of a 256-KiB diagnostic response.
            for frame, _ in pending:
                self._cache_analysis(frame, self._analysis_payload)
            wake.set()
            await asyncio.sleep(.02)

    def _cache_analysis(self, frame, payload):
        key = (frame.host_context_hint, frame.ping_id, frame.time_utc_ns)
        if payload is not None:
            with contextlib.suppress(ProtocolError):
                projection = project(frame, payload)
                detection = payload.get('detection')
                presentation = payload.get('presentation')
                selected = presentation.get('display_selection') if isinstance(presentation, dict) else None
                if projection is not None and isinstance(detection, dict) and isinstance(selected, dict):
                    evidence = (selected.get('generation'), selected.get('selection_generation'),
                                detection.get('sample_period_ns'), detection.get('acquisition_source'))
                    self._analysis_cache[key] = (projection, evidence)
                    if len(self._analysis_cache) > ANALYSIS_CACHE_CAPACITY:
                        self._analysis_cache.popitem(last=False)
        return self._analysis_cache.get(key)

    async def _fetch_analysis(self, session):
        payload = None
        try:
            timeout = .2 if self._host_package_version in PUBLISHED_VERSIONS else .1
            async with asyncio.timeout(timeout):
                async with session.get(f"{self._config.upstream_base_url}/api/v1/detection/latest") as response:
                    if response.status == 200:
                        payload = await read_json(response, ANALYSIS_MAX_BYTES)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Interpretation is optional; the raw stream remains readable.
            pass
        return payload

    async def _fetch_published(self, session, pending):
        try:
            # On reconnect request the first queued frame, not 512 obsolete
            # publications preceding it. Subsequent requests advance one page.
            first = next((frame.ping_id for frame, _ in pending
                          if frame.host_context_hint == self._published.context), None)
            cursor = self._published.cursor
            if not len(self._published) and first is not None:
                cursor = max(cursor, first - 1)
            request_context = self._published.context
            # This request can start before later queued frames. Its lifetime
            # must not discard an exact row still within those frames'250ms
            # join deadline. Only one bounded read runs in the poll worker;
            # expired frames remain UNKNOWN and are never relabelled later.
            async with asyncio.timeout(PUBLISHED_READ_TIMEOUT_S):
                url = f"{self._config.upstream_base_url}/api/v1/sonar/published-pings"
                async with session.get(url, params={'after_ping_id': cursor, 'limit': 64}) as response:
                    if response.status != 200:
                        return
                    page = parse_page(await read_json(response, MAX_BODY_BYTES), after_ping_id=cursor,
                                      host_version=self._host_package_version)
            if page.context != request_context:
                await self._fetch_status(session)
                # This cursor belonged to another USB segment. Even a nonempty
                # response must not advance the replacement segment's cursor.
                return
            if page.context != self._published.context:
                await self._fetch_status(session)
                if page.context != self._published.context:
                    return
                # A new host/segment restarts the cursor. Never adopt an old
                # segment's request watermark just because its page was empty.
                if not page.rows and cursor != self._published.cursor:
                    return
            self._published.accept(page)
            # The validated immutable row already identifies this ping's
            # epoch. Cache it before waiting for a slower selection refresh;
            # otherwise the independent250ms join can emit an UNKNOWN frame
            # into the old selection while its exact new epoch is concealed.
            # GatewayState still rejects an incompatible epoch, and source
            # claims still require the confirmed matching selection.
            selected = self._selection
            if any(selected is None or row.display_generation > selected['generation']
                   for row in page.rows):
                await self._fetch_status(session)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Missing/corrupt evidence may remove a claim, never create one or
            # stop the independently valid binary stream.
            return

    def _with_publication(self, frame):
        if frame.host_package_version not in PUBLISHED_VERSIONS:
            return frame
        publication = (frame.publication if frame.host_package_version in ATOMIC_CONTEXT_VERSIONS else
                       self._published.match(frame))
        selected = self._selection
        if (publication is None or selected is None or
                selected.get('selectionGeneration') != publication.selection_generation or
                selected['generation'] != publication.display_generation or
                (publication.actual_source is not None and
                 selected['source'] not in {'all', publication.actual_source})):
            return replace(frame, publication=publication,
                           amplitude=publication_amplitude(None, None))
        selected = clone(selected)
        selected.update(lastPublishedPingId=frame.ping_id, observedAtEpochMs=frame.observed_at_epoch_ms)
        return replace(frame, publication=publication, display_selection=selected,
                       ping_source=publication.actual_source,
                       amplitude=publication_amplitude(frame.amplitude, publication))


# aiohttp 3.14 rejects messages whose length equals max_msg_size. Keep one
# byte of transport headroom; parse_echogram_frame still enforces 2088 bytes.
UPSTREAM_MAX_FRAME_BYTES = 40 + 1024 * 2 + 1
ANALYSIS_QUEUE_CAPACITY = 32
ANALYSIS_WAIT_S = .5
ANALYSIS_MAX_BYTES = 256 * 1024
PUBLISHED_POLL_S = .1
PUBLISHED_WAIT_S = .25
PUBLISHED_READ_TIMEOUT_S = 1.
ANALYSIS_CACHE_CAPACITY = 64

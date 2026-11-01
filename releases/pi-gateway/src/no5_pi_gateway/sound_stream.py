"""Bounded duplex delivery into the existing AUDIO actor; never a second writer.

Admission and completion have separate messages. At most one request per socket
is outstanding; the client retains one replaceable unsent goal. The actor's 64-slot queue, coalescing, barriers, high-water marks,
authorization and five-second activity deadlines remain authoritative.
"""
from __future__ import annotations

import asyncio
import json
from aiohttp import web, WSMsgType
from .audio import AudioError, validate_audio_command
from .protocol import ProtocolError, strict_json_object

SUBPROTOCOL = "no5.audio.sound.v1"
WINDOW = 1


async def sound_command_stream(request, actor, owner, authorized):
    if SUBPROTOCOL not in [part.strip() for part in request.headers.get("Sec-WebSocket-Protocol", "").split(",")]:
        raise web.HTTPUpgradeRequired(headers={"Sec-WebSocket-Protocol": SUBPROTOCOL})
    current = actor.adapter.payload
    if current is None:
        raise web.HTTPServiceUnavailable(text="AUDIO observation unavailable")
    server = current["serverSessionId"]
    socket = web.WebSocketResponse(protocols=(SUBPROTOCOL,), heartbeat=5, max_msg_size=16384)
    await socket.prepare(request)
    live = True
    producer = None
    pending = {}
    outbox = asyncio.Queue(maxsize=16)
    overflow = asyncio.Event()

    def valid():
        return live and not socket.closed and not overflow.is_set() and authorized()

    def emit(value):
        # No network wait or unbounded task creation on actor completion/admission.
        try:
            outbox.put_nowait(json.dumps(value, separators=(",", ":"), allow_nan=False))
        except asyncio.QueueFull:
            overflow.set()

    async def watch(request_id, identifier):
        try:
            while valid():
                result = await actor.wait_result(owner, identifier)
                if result is None:
                    overflow.set()
                    return
                if result["status"] not in {"ACCEPTED", "RUNNING"}:
                    emit(result)
                    return
        finally:
            pending.pop(request_id, None)

    async def publish():
        try:
            while valid():
                try:
                    value = await asyncio.wait_for(outbox.get(), .25)
                except asyncio.TimeoutError:
                    continue
                # Slow/disconnected readers must not retain preview authority forever.
                await asyncio.wait_for(socket.send_str(value), 1.)
        finally:
            overflow.set()
            await socket.close(code=1008, message=b"sound stream authority ended")

    emit({"version": 1, "kind": "sound-command-ready", "serverSessionId": server, "window": WINDOW})
    publisher = asyncio.create_task(publish(), name="no5-sound-command-publisher")
    try:
        async for message in socket:
            if message.type != WSMsgType.TEXT or not valid():
                await socket.close(code=1008, message=b"sound stream unavailable")
                break
            try:
                payload = strict_json_object(message.data.encode("utf-8"))
                validate_audio_command(payload)
                if payload.get("command", {}).get("type") not in {"soundPreview", "soundKeepalive"}:
                    raise AudioError("sound stream accepts preview activity only")
                if payload.get("serverSessionId") != server:
                    raise AudioError("source changed")
                session = payload.get("sessionId")
                if producer is not None and producer != session:
                    raise AudioError("producer changed")
                producer = session
                request_id = payload.get("requestId")
                if request_id not in pending and len(pending) >= WINDOW:
                    raise AudioError("sound stream window exceeded")
                result = actor.submit(owner, payload, valid)
                emit(result)
                if result["status"] in {"ACCEPTED", "RUNNING"} and request_id not in pending:
                    pending[request_id] = asyncio.create_task(watch(request_id, result["commandId"]), name="no5-sound-command-receipt")
            except (ProtocolError, AudioError, TypeError, AttributeError):
                await socket.close(code=1008, message=b"sound request unconfirmed; reconcile without replay")
                break
    finally:
        live = False
        tasks = [publisher, *pending.values()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await socket.close()
    return socket

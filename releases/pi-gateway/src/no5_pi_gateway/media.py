"""Session-bound Spotify reads and a bounded, credential-free artwork proxy.

This side channel does not acquire the AUDIO command lock or write to DSP.
The old AUDIO V1 payload and artworkId catalogue are deliberately unchanged.
"""
from __future__ import annotations

import asyncio
import copy
from collections import OrderedDict
import hashlib
import ipaddress
from pathlib import Path
import re
import stat
import struct
import time
from typing import Any
import uuid
import zlib

from .configuration import GatewayConfig
from .protocol import ProtocolError, strict_json_object

MAX_REPLY = 131_072
MAX_IMAGE = 1_048_576
_URI = re.compile(r"spotify:(?:track|episode|playlist|album|artist):[A-Za-z0-9]{22}\Z")
_IMAGE = re.compile(r"https://i\.scdn\.co/image/[A-Za-z0-9]{32,128}\Z")
_PLAYLIST_IMAGE = re.compile(r"https://(?:i\.scdn\.co/image/[A-Za-z0-9]{32,128}|mosaic\.scdn\.co/(?:100|300|640)/[a-fA-F0-9]{40,320})\Z")
_SESSION = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")


class MediaUnavailable(ProtocolError):
    pass


class MediaChanged(MediaUnavailable):
    pass


def _text(value: object, *, nullable: bool = True) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str) or not 1 <= len(value) <= 200 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise MediaUnavailable("Media text is invalid")
    return value


def _integer(value: object, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise MediaUnavailable("Media number is invalid")
    return value


def _timestamp(value: object) -> int:
    value = _integer(value, 1, 9_007_199_254_740_991)
    age = time.time_ns() // 1_000_000 - value
    if not -5000 <= age <= 15_000:
        raise MediaUnavailable("Media observation is stale")
    return value


def _item(value: object, *, playlist: bool = False) -> dict[str, object]:
    if not isinstance(value, dict) or (not {"uri", "title", "artist"} <= set(value) or set(value) - {"uri", "title", "artist"} - ({"playlist"} if playlist else set())):
        raise MediaUnavailable("Queue item is invalid")
    uri = value["uri"]
    if not isinstance(uri, str) or not _URI.fullmatch(uri):
        raise MediaUnavailable("Queue URI is invalid")
    result = {"uri": uri, "title": _text(value["title"]), "artist": _text(value["artist"])}
    if "playlist" in value:
        details = value["playlist"]
        if not uri.startswith("spotify:playlist:") or not isinstance(details, dict) or set(details) != {"owner", "description", "trackCount", "collaborative", "artwork"}:
            raise MediaUnavailable("Playlist details are invalid")
        _text(details["owner"]); _text(details["description"])
        if details["trackCount"] is not None: _integer(details["trackCount"], 0, 1_000_000)
        if details["collaborative"] is not None and type(details["collaborative"]) is not bool:
            raise MediaUnavailable("Playlist collaboration is invalid")
        art = details["artwork"]
        if art is not None and (not isinstance(art, dict) or set(art) != {"url"} or not isinstance(art["url"], str) or not _PLAYLIST_IMAGE.fullmatch(art["url"])):
            raise MediaUnavailable("Playlist artwork is invalid")
        result["playlist"] = details
    return result


def validate_media(value: object) -> dict[str, Any]:
    if (not isinstance(value, dict) or set(value) != {"version", "kind", "receiverSessionId", "available",
            "observedAtEpochMs", "track", "artwork", "queue"} or type(value["version"]) is not int
            or value["version"] != 1 or value["kind"] != "spotify-media"
            or not isinstance(value["receiverSessionId"], str) or not _SESSION.fullmatch(value["receiverSessionId"])
            or type(value["available"]) is not bool):
        raise MediaUnavailable("Receiver media schema is unsupported")
    _timestamp(value["observedAtEpochMs"])
    track = value["track"]
    if value["available"]:
        if not isinstance(track, dict) or set(track) != {"id", "uri", "title", "artists", "album", "durationMs"}:
            raise MediaUnavailable("Receiver track is invalid")
        for key in ("id", "title", "album"):
            _text(track[key])
        if track["uri"] is not None and (not isinstance(track["uri"], str) or not _URI.fullmatch(track["uri"])):
            raise MediaUnavailable("Receiver track URI is invalid")
        if not isinstance(track["artists"], list) or len(track["artists"]) > 10:
            raise MediaUnavailable("Receiver artists are invalid")
        for artist in track["artists"]:
            _text(artist, nullable=False)
        if track["durationMs"] is not None:
            _integer(track["durationMs"], 1, 86_400_000)
    elif any(value[k] is not None for k in ("track", "artwork", "queue")):
        raise MediaUnavailable("Unavailable receiver leaked old media")
    artwork = value["artwork"]
    if artwork is not None:
        if (not isinstance(artwork, dict) or set(artwork) != {"url", "width", "height"}
                or not isinstance(artwork["url"], str) or not _IMAGE.fullmatch(artwork["url"])
                or not isinstance(track, dict) or track["uri"] is None):
            raise MediaUnavailable("Artwork descriptor is invalid")
        _integer(artwork["width"], 1, 2048)
        _integer(artwork["height"], 1, 2048)
        if artwork["width"] * artwork["height"] > 1_048_576:
            raise MediaUnavailable("Artwork exceeds its pixel bounds")
    queue = value["queue"]
    if queue is not None:
        if not isinstance(queue, dict) or set(queue) != {"revision", "contextUri", "observedAtEpochMs", "previous", "next", "complete"}:
            raise MediaUnavailable("Receiver queue is invalid")
        _text(queue["revision"])
        if queue["contextUri"] is not None and (not isinstance(queue["contextUri"], str) or not _URI.fullmatch(queue["contextUri"])):
            raise MediaUnavailable("Queue context is invalid")
        _timestamp(queue["observedAtEpochMs"])
        if queue["complete"] is not False:
            raise MediaUnavailable("An observed queue is not a complete playlist")
        for key, maximum in (("previous", 10), ("next", 80)):
            if not isinstance(queue[key], list) or len(queue[key]) > maximum:
                raise MediaUnavailable("Queue exceeds its bounds")
            for item in queue[key]:
                _item(item)
    return value


def validate_browse(value: object, expected: str) -> dict[str, Any]:
    if (not isinstance(value, dict) or set(value) != {"version", "kind", "receiverSessionId", "observedAtEpochMs", "page"}
            or type(value["version"]) is not int or value["version"] != 1 or value["kind"] != "spotify-browse"):
        raise MediaUnavailable("Receiver browse schema is unsupported")
    if value["receiverSessionId"] != expected:
        raise MediaChanged("Connect session changed")
    _timestamp(value["observedAtEpochMs"])
    page = value["page"]
    required = {"offset", "limit", "revision", "items", "nextOffset", "complete"}
    if not isinstance(page, dict) or not required <= set(page) or set(page) - required - {"unresolvedPages", "collectionRevision"}:
        raise MediaUnavailable("Browse page is invalid")
    offset = _integer(page["offset"], 0, 5000)
    limit = _integer(page["limit"], 1, 40)
    _text(page["revision"])
    if not isinstance(page["items"], list) or len(page["items"]) > limit:
        raise MediaUnavailable("Browse page exceeds its bounds")
    for item in page["items"]:
        _item(item, playlist=True)
    if "collectionRevision" in page: _text(page["collectionRevision"])
    if type(page["complete"]) is not bool or ("unresolvedPages" in page and type(page["unresolvedPages"]) is not bool):
        raise MediaUnavailable("Browse completeness is invalid")
    if page["nextOffset"] is not None:
        _integer(page["nextOffset"], offset + 1, offset + limit)
        if page["complete"]:
            raise MediaUnavailable("Complete browse page has a continuation")
    return value


async def receiver_read(path: Path, action: dict[str, object]) -> dict[str, Any]:
    if not stat.S_ISSOCK(path.lstat().st_mode):
        raise MediaUnavailable("Receiver read socket is unavailable")
    request_id = "media-" + uuid.uuid4().hex
    writer = None
    try:
        async with asyncio.timeout(3.8):
            reader, writer = await asyncio.open_unix_connection(str(path), limit=MAX_REPLY + 1)
            import json
            writer.write((json.dumps({"version": 1, "kind": "spotify-control", "requestId": request_id,
                                      "action": action}, separators=(",", ":")) + "\n").encode())
            await writer.drain()
            line = await reader.readline()
            if not line.endswith(b"\n") or len(line) > MAX_REPLY:
                raise MediaUnavailable("Receiver read reply exceeds its bounds")
            result = strict_json_object(line)
            if (set(result) != {"version", "kind", "requestId", "status", "message", "confirmation"}
                    or type(result["version"]) is not int or result["version"] != 1 or result["kind"] != "spotify-control-ack"
                    or result["requestId"] != request_id or result["status"] != "VERIFIED"
                    or not isinstance(result["confirmation"], dict)):
                raise MediaUnavailable("Receiver read is unavailable or unconfirmed")
            return result["confirmation"]
    except (OSError, TimeoutError, ValueError) as exc:
        raise MediaUnavailable("Receiver read is unavailable") from exc
    finally:
        if writer is not None:
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), 0.2)
            except (OSError, TimeoutError):
                pass


def image_info(data: bytes) -> tuple[str, int, int]:
    """Validate bounded PNG structure or JPEG frame dimensions before decoding."""
    if not 20 <= len(data) <= MAX_IMAGE:
        raise MediaUnavailable("Artwork size is invalid")
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        position, width, height, ended, saw_data = 8, 0, 0, False, False
        while position + 12 <= len(data):
            length = int.from_bytes(data[position:position+4], "big")
            kind = data[position+4:position+8]
            end = position + 12 + length
            if end > len(data) or zlib.crc32(data[position+4:end-4]) & 0xffffffff != int.from_bytes(data[end-4:end], "big"):
                raise MediaUnavailable("Artwork PNG is invalid")
            if position == 8:
                if kind != b"IHDR" or length != 13:
                    raise MediaUnavailable("Artwork PNG header is invalid")
                width, height = struct.unpack(">II", data[position+8:position+16])
            elif kind in {b"IHDR", b"acTL"}:
                raise MediaUnavailable("Artwork PNG is unsupported")
            saw_data = saw_data or kind == b"IDAT"
            position = end
            if kind == b"IEND":
                ended = length == 0 and position == len(data)
                break
        if not ended or not saw_data:
            raise MediaUnavailable("Artwork PNG is incomplete")
        mime = "image/png"
    elif data.startswith(b"\xff\xd8") and data.endswith(b"\xff\xd9"):
        position, width, height = 2, 0, 0
        while position + 4 <= len(data):
            if data[position] != 255:
                break
            while position < len(data) and data[position] == 255:
                position += 1
            if position >= len(data):
                break
            marker = data[position]
            position += 1
            if marker in {0xd8, 0xd9, 0x01} or 0xd0 <= marker <= 0xd7:
                continue
            length = int.from_bytes(data[position:position+2], "big")
            if length < 2 or position + length > len(data):
                raise MediaUnavailable("Artwork JPEG is invalid")
            if marker in {0xc0, 0xc1, 0xc2}:
                if length < 8:
                    raise MediaUnavailable("Artwork JPEG frame is invalid")
                height, width = struct.unpack(">HH", data[position+3:position+7])
                break
            position += length
        mime = "image/jpeg"
    else:
        raise MediaUnavailable("Artwork format is unsupported")
    if not 1 <= width <= 2048 or not 1 <= height <= 2048 or width * height > 1_048_576:
        raise MediaUnavailable("Artwork dimensions exceed the decode bounds")
    return mime, width, height


class ReadOnlyMedia:
    def __init__(self, config: GatewayConfig) -> None:
        self.config = config.audio
        self._closed = False
        self._reads = asyncio.Semaphore(4)
        self._images = asyncio.Semaphore(2)
        self._session: Any = None
        self._resolver: Any = None
        self._cache: OrderedDict[str, tuple[bytes, str]] = OrderedDict()
        self._playlist_descriptors: OrderedDict[str, tuple[str, str, str, float]] = OrderedDict()
        self._playlist_session: str | None = None
        self._selection_lock = asyncio.Lock()
        self._receipts: OrderedDict[str, tuple[str, dict[str, Any]]] = OrderedDict()

    async def _read(self, action: dict[str, object]) -> dict[str, Any]:
        if self._closed or not self.config.enabled or self.config.media_provider != "spotify_connect" or not self.config.spotify_connect.enabled:
            raise MediaUnavailable("Spotify media reader is not configured")
        if self._reads.locked():
            raise MediaUnavailable("Spotify media readers are busy")
        async with self._reads:
            try:
                return await receiver_read(Path(self.config.spotify_connect.control_socket or ""), action)
            except OSError as exc:
                raise MediaUnavailable("Receiver read socket is unavailable") from exc

    async def _snapshot(self) -> dict[str, Any]:
        return validate_media(await self._read({"type": "READ_MEDIA"}))

    @staticmethod
    def _image_id(snapshot: dict[str, Any]) -> str | None:
        if snapshot["artwork"] is None:
            return None
        value = snapshot["receiverSessionId"] + "\n" + snapshot["track"]["uri"] + "\n" + snapshot["artwork"]["url"]
        return hashlib.sha256(value.encode()).hexdigest()

    async def snapshot(self, navigation: bool = False) -> dict[str, Any]:
        snapshot = (validate_media(await self._read({"type": "READ_NAVIGATION"}))
                    if navigation else await self._snapshot())
        artwork = snapshot["artwork"]
        result = dict(snapshot)
        if artwork is not None:
            identifier = self._image_id(snapshot)
            result["artwork"] = {"id": identifier, "path": "/v1/audio/artwork/" + str(identifier),
                                 "width": artwork["width"], "height": artwork["height"]}
        return result

    async def select(self, request: object) -> dict[str, Any]:
        """One session/queue-bound write. Unknown outcomes are never sent again."""
        import json
        if (not isinstance(request, dict) or set(request) !=
                {"version", "kind", "requestId", "receiverSessionId", "queueRevision", "selection"}
                or type(request["version"]) is not int or request["version"] != 1
                or request["kind"] != "spotify-selection"):
            raise MediaUnavailable("Selection schema is invalid")
        rid, expected, revision, selected = (request[k] for k in
            ("requestId", "receiverSessionId", "queueRevision", "selection"))
        if (not isinstance(rid, str) or not re.fullmatch(r"[A-Za-z0-9:_-]{1,200}", rid)
                or not isinstance(expected, str) or not _SESSION.fullmatch(expected)
                or not isinstance(revision, str) or not re.fullmatch(r"[a-f0-9-]{36}", revision)
                or not isinstance(selected, dict)):
            raise MediaUnavailable("Selection identity is invalid")
        kind = selected.get("type")
        fields = ({"type", "uri", "shuffle", "pageRevision"} if kind == "PLAYLIST_START"
                  else {"type", "index", "uri"} | ({"query", "pageRevision"} if kind == "COLLECTION" else set()))
        if kind not in {"QUEUE", "COLLECTION", "PLAYLIST_START"} or set(selected) != fields:
            raise MediaUnavailable("Selection target is invalid")
        index = 0 if kind == "PLAYLIST_START" else _integer(selected["index"], 0, 79 if kind == "QUEUE" else 39)
        uri = _text(selected["uri"], nullable=False)
        if not isinstance(uri, str) or not re.fullmatch(r"spotify:playlist:[A-Za-z0-9]{22}" if kind == "PLAYLIST_START" else r"spotify:(?:track|episode):[A-Za-z0-9]{22}", uri):
            raise MediaUnavailable("Selection URI is invalid")
        action: dict[str, object] = {"type": "SELECT_QUEUE" if kind == "QUEUE" else "SELECT_COLLECTION",
            "expected_session": expected, "expected_queue_revision": revision, "index": index, "uri": uri}
        if kind == "PLAYLIST_START":
            if type(selected["shuffle"]) is not bool: raise MediaUnavailable("Playlist shuffle is invalid")
            _text(selected["pageRevision"], nullable=False)
            action = {"type":"START_PLAYLIST", "expected_session":expected, "expected_queue_revision":revision,
                      "uri":uri, "shuffle":selected["shuffle"], "expected_page_revision":selected["pageRevision"]}
        if kind == "COLLECTION":
            query = selected["query"]
            if not isinstance(query, dict) or query.get("type") not in {"PLAYLIST", "LIKED", "SEARCH"}:
                raise MediaUnavailable("Selected collection is invalid")
            qkind = query["type"]
            qfields = {"type", "offset", "limit"} | ({"uri"} if qkind == "PLAYLIST" else {"query"} if qkind == "SEARCH" else set())
            if set(query) != qfields:
                raise MediaUnavailable("Selected collection query is invalid")
            _integer(query["offset"], 0, 5000)
            limit = _integer(query["limit"], 1, 40)
            if index >= limit:
                raise MediaUnavailable("Selected collection index is invalid")
            if qkind == "PLAYLIST" and not re.fullmatch(r"spotify:playlist:[A-Za-z0-9]{22}", str(query["uri"])):
                raise MediaUnavailable("Selected playlist is invalid")
            if qkind == "SEARCH":
                _text(query["query"], nullable=False)
            _text(selected["pageRevision"])
            action.update(query=query, expected_page_revision=selected["pageRevision"])
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

        def response(status: str, message: str, confirmation: object = None) -> dict[str, Any]:
            return {"version": 1, "kind": "spotify-selection-ack", "requestId": rid,
                "receiverSessionId": expected, "status": status, "message": message, "confirmation": confirmation}
        if rid in self._receipts:
            known, receipt = self._receipts[rid]
            if known != fingerprint:
                return response("REJECTED", "Request identity was reused for another selection")
            return copy.deepcopy(receipt)
        if self._selection_lock.locked():
            return response("REJECTED", "A selection is already pending; no write sent")
        async with self._selection_lock:
            try:
                before = await self._snapshot()
                queue = before["queue"]
                if (not before["available"] or before["receiverSessionId"] != expected
                        or queue is None or queue["revision"] != revision):
                    result = response("REJECTED", "Connect session or queue changed; no write sent")
                elif kind == "QUEUE" and (index >= len(queue["next"]) or queue["next"][index]["uri"] != uri):
                    result = response("REJECTED", "Observed queue entry changed; no write sent")
                else:
                    status, message, confirmed = await receiver_selection(
                        Path(self.config.spotify_connect.control_socket or ""), rid, action)
                    if status == "VERIFIED":
                        required = {"version", "kind", "receiverSessionId", "trackUri", "entryId", "observedAtEpochMs", "playerSequence"}
                        if kind == "PLAYLIST_START": required |= {"playlistUri", "shuffle"}
                        if (not isinstance(confirmed, dict) or set(confirmed) != required
                                or type(confirmed["version"]) is not int or confirmed["version"] != 1
                                or confirmed["kind"] != ("spotify-playlist-start-readback" if kind == "PLAYLIST_START" else "spotify-selection-readback")
                                or confirmed["receiverSessionId"] != expected
                                or not isinstance(confirmed["trackUri"], str)
                                or not re.fullmatch(r"spotify:(?:track|episode):[A-Za-z0-9]{22}", confirmed["trackUri"])
                                or (kind != "PLAYLIST_START" and confirmed["trackUri"] != uri)
                                or (kind == "PLAYLIST_START" and (confirmed["playlistUri"] != uri or type(confirmed["shuffle"]) is not bool or confirmed["shuffle"] != selected["shuffle"]))
                                or not isinstance(confirmed["entryId"], str) or len(confirmed["entryId"]) > 200):
                            raise MediaUnavailable("Selected-song readback is invalid")
                        _timestamp(confirmed["observedAtEpochMs"])
                        _integer(confirmed["playerSequence"], 1, 9_007_199_254_740_991)
                    result = response(status, message, confirmed)
            except MediaUnavailable:
                result = response("UNKNOWN", "Selection could not be confirmed; no replay")
            self._receipts[rid] = (fingerprint, result)
            while len(self._receipts) > 128:
                self._receipts.popitem(last=False)
            return copy.deepcopy(result)

    async def browse(self, expected: str, query: dict[str, object]) -> dict[str, Any]:
        if not isinstance(expected, str) or not _SESSION.fullmatch(expected):
            raise MediaUnavailable("Receiver session is invalid")
        response = await self._read({"type": "BROWSE", "expected_session": expected, "query": query})
        result = copy.deepcopy(validate_browse(response, expected))
        if self._playlist_session != expected:
            self._playlist_session = expected
            self._playlist_descriptors.clear()
        for row in result["page"]["items"]:
            art = (row.get("playlist") or {}).get("artwork")
            if art is None: continue
            identifier = hashlib.sha256((expected + "\n" + row["uri"] + "\n" + art["url"]).encode()).hexdigest()
            self._playlist_descriptors[identifier] = (expected, row["uri"], art["url"], time.monotonic())
            self._playlist_descriptors.move_to_end(identifier)
            while len(self._playlist_descriptors) > 5000: self._playlist_descriptors.popitem(last=False)
            row["playlist"]["artwork"] = {"id": identifier, "path": "/v1/audio/playlist-artwork/" + identifier}
        return result

    async def artwork(self, identifier: str) -> tuple[bytes, str]:
        if self._closed:
            raise MediaUnavailable("Media reader is closed")
        if not re.fullmatch(r"[a-f0-9]{64}", identifier):
            raise MediaUnavailable("Artwork identifier is invalid")
        before = await self._snapshot()
        if self._image_id(before) != identifier:
            raise MediaChanged("Artwork track or session changed")
        if identifier in self._cache:
            self._cache.move_to_end(identifier)
            return self._cache[identifier]
        data, mime = await self._download_image(before["artwork"]["url"], (before["artwork"]["width"], before["artwork"]["height"]))
        after = await self._snapshot()
        if self._image_id(after) != identifier: raise MediaChanged("Artwork track or session changed during download")
        if self._closed: raise MediaUnavailable("Media reader is closed")
        self._cache[identifier] = (data, mime)
        while len(self._cache) > 32: self._cache.popitem(last=False)
        return data, mime

    async def playlist_artwork(self, identifier: str) -> tuple[bytes, str]:
        if self._closed or not re.fullmatch(r"[a-f0-9]{64}", identifier): raise MediaUnavailable("Playlist artwork identifier is invalid")
        descriptor = self._playlist_descriptors.get(identifier)
        if descriptor is None or time.monotonic() - descriptor[3] > 600: raise MediaChanged("Playlist artwork descriptor expired")
        before = await self._snapshot()
        if not before["available"] or before["receiverSessionId"] != descriptor[0]: raise MediaChanged("Playlist artwork session changed")
        if identifier in self._cache:
            self._cache.move_to_end(identifier)
            return self._cache[identifier]
        data, mime = await self._download_image(descriptor[2], None)
        after = await self._snapshot()
        if self._closed or not after["available"] or after["receiverSessionId"] != descriptor[0] or self._playlist_descriptors.get(identifier) != descriptor:
            raise MediaChanged("Playlist artwork session changed during download")
        self._cache[identifier] = (data, mime)
        while len(self._cache) > 32: self._cache.popitem(last=False)
        return data, mime

    async def _download_image(self, url: str, dimensions: tuple[int, int] | None) -> tuple[bytes, str]:
        if self._images.locked():
            raise MediaUnavailable("Artwork readers are busy")
        async with self._images:
            if self._session is None:
                import aiohttp
                from aiohttp.abc import AbstractResolver
                class PublicResolver(AbstractResolver):
                    def __init__(self) -> None:
                        self.delegate = aiohttp.resolver.DefaultResolver()
                    async def resolve(self, host: str, port: int = 0, family: int = 0) -> list[dict[str, Any]]:
                        addresses = await self.delegate.resolve(host, port, family)
                        if not addresses or any(not ipaddress.ip_address(a["host"]).is_global for a in addresses):
                            raise OSError("Artwork CDN address is not public")
                        return addresses
                    async def close(self) -> None:
                        await self.delegate.close()
                self._resolver = PublicResolver()
                self._session = aiohttp.ClientSession(cookie_jar=aiohttp.DummyCookieJar(), auto_decompress=False,
                    timeout=aiohttp.ClientTimeout(total=4.0, sock_connect=2.0),
                    connector=aiohttp.TCPConnector(resolver=self._resolver, limit=2, use_dns_cache=False))
            import aiohttp
            try:
                async with self._session.get(url, allow_redirects=False,
                        headers={"Accept": "image/jpeg, image/png", "Accept-Encoding": "identity"}) as response:
                    if (response.status != 200 or response.content_type not in {"image/jpeg", "image/png"}
                            or getattr(response, "headers", {}).get("Content-Encoding", "identity").lower() != "identity"):
                        raise MediaUnavailable("Artwork CDN reply is unsupported")
                    if response.content_length is not None and response.content_length > MAX_IMAGE:
                        raise MediaUnavailable("Artwork CDN reply exceeds its bounds")
                    chunks, size = [], 0
                    async for chunk in response.content.iter_chunked(32_768):
                        size += len(chunk)
                        if size > MAX_IMAGE:
                            raise MediaUnavailable("Artwork CDN reply exceeds its bounds")
                        chunks.append(chunk)
                    data = b"".join(chunks)
                    mime, width, height = await asyncio.to_thread(image_info, data)
                    if mime != response.content_type or (dimensions is not None and (width, height) != dimensions):
                        raise MediaUnavailable("Artwork descriptor does not match the actual image")
            except (aiohttp.ClientError, OSError, TimeoutError) as exc:
                raise MediaUnavailable("Artwork CDN read failed") from exc
            return data, mime

    async def close(self) -> None:
        self._closed = True
        if self._session is not None:
            await self._session.close()
            self._session = None
        self._cache.clear()
        self._playlist_descriptors.clear()
        self._receipts.clear()
        if self._resolver is not None:
            await self._resolver.close()
            self._resolver = None


async def receiver_selection(path: Path, request_id: str, action: dict[str, object]) -> tuple[str, str, object]:
    """A single write and receipt, without retries or automatic state-changing reads."""
    writer = None
    try:
        if not stat.S_ISSOCK(path.lstat().st_mode):
            return "REJECTED", "Receiver control socket is unavailable; no write sent", None
        async with asyncio.timeout(9.0):
            reader, writer = await asyncio.open_unix_connection(str(path), limit=MAX_REPLY + 1)
            import json
            writer.write((json.dumps({"version": 1, "kind": "spotify-control",
                "requestId": request_id, "action": action}, separators=(",", ":")) + "\n").encode())
            await writer.drain()
            line = await reader.readline()
            if not line.endswith(b"\n") or len(line) > MAX_REPLY:
                raise MediaUnavailable("Selection receipt exceeds its bounds")
            value = strict_json_object(line)
            required = {"version", "kind", "requestId", "status", "message"}
            if (not isinstance(value, dict) or not required <= set(value) or set(value) - required - {"confirmation"}
                    or type(value["version"]) is not int or value["version"] != 1
                    or value["kind"] != "spotify-control-ack" or value["requestId"] != request_id
                    or value["status"] not in {"VERIFIED", "REJECTED", "UNKNOWN"}):
                raise MediaUnavailable("Selection receipt is invalid")
            message = _text(value["message"], nullable=False)
            return value["status"], str(message), value.get("confirmation")
    except (OSError, ValueError, TimeoutError, MediaUnavailable):
        return "UNKNOWN", "Receiver selection was not confirmed; no replay", None
    finally:
        if writer is not None:
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), 0.2)
            except (OSError, TimeoutError):
                pass

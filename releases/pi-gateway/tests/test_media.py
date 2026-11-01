from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch
import zlib

from no5_pi_gateway.configuration import AudioConfig, CamillaDspConfig, GatewayConfig, SpotifyConnectConfig
from no5_pi_gateway.media import (MediaChanged, MediaUnavailable, ReadOnlyMedia, image_info,
                                 receiver_read, validate_browse, validate_media)

URI = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
CDN = "https://i.scdn.co/image/" + "a" * 40

def snapshot():
    now = time.time_ns() // 1_000_000
    return {"version": 1, "kind": "spotify-media", "receiverSessionId": "media-test", "available": True,
        "observedAtEpochMs": now, "track": {"id": URI.rsplit(":", 1)[1], "uri": URI,
        "title": "Track", "artists": ["Artist"], "album": None, "durationMs": 100000},
        "artwork": {"url": CDN, "width": 1, "height": 1},
        "queue": {"revision": "queue-1", "contextUri": None, "observedAtEpochMs": now,
                  "previous": [], "next": [{"uri": URI, "title": None, "artist": None}], "complete": False}}

def png(width=1, height=1):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\0\0\0\0")) + chunk(b"IEND", b""))

class ValidationTest(unittest.TestCase):
    def test_actual_metadata_is_accepted_and_unknown_fields_rejected(self):
        self.assertEqual(validate_media(snapshot())["track"]["uri"], URI)
        value = snapshot(); value["key"] = "must-not-appear"
        with self.assertRaises(MediaUnavailable): validate_media(value)

    def test_cdn_spoofing_credentials_redirect_paths_and_unavailable_old_cover_are_rejected(self):
        for url in ("http://i.scdn.co/image/" + "a" * 40, "https://i.scdn.co.evil/image/" + "a" * 40,
                    "https://user@i.scdn.co/image/" + "a" * 40, CDN + "/../private", CDN + "?token=x"):
            value = snapshot(); value["artwork"]["url"] = url
            with self.assertRaises(MediaUnavailable): validate_media(value)
        value = snapshot(); value["available"] = False
        with self.assertRaises(MediaUnavailable): validate_media(value)

    def test_stale_times_boolean_dimensions_and_queue_overflow_are_rejected(self):
        value = snapshot(); value["observedAtEpochMs"] -= 16000
        with self.assertRaises(MediaUnavailable): validate_media(value)
        value = snapshot(); value["artwork"]["width"] = True
        with self.assertRaises(MediaUnavailable): validate_media(value)
        value = snapshot(); value["queue"]["next"] *= 81
        with self.assertRaises(MediaUnavailable): validate_media(value)

    def test_png_crc_dimensions_truncation_and_wrong_format(self):
        self.assertEqual(image_info(png()), ("image/png", 1, 1))
        for data in (png()[:-1], png(4000, 4000), b"not an image" * 3,
                     png()[:-5] + bytes([png()[-5] ^ 1]) + png()[-4:]):
            with self.assertRaises(MediaUnavailable): image_info(data)

    def test_browse_requires_same_session_and_bounded_items(self):
        value = {"version": 1, "kind": "spotify-browse", "receiverSessionId": "media-test",
                 "observedAtEpochMs": time.time_ns() // 1000000,
                 "page": {"offset": 0, "limit": 1, "revision": None, "items": [], "nextOffset": None,
                          "complete": False, "unresolvedPages": True}}
        self.assertFalse(validate_browse(value, "media-test")["page"]["complete"])
        with self.assertRaises(MediaChanged): validate_browse(value, "new-session")
        value["page"]["items"] = [{"uri": URI, "title": None, "artist": None}] * 2
        with self.assertRaises(MediaUnavailable): validate_browse(value, "media-test")

class FakeContent:
    def __init__(self, data): self.data = data
    async def iter_chunked(self, _size): yield self.data

class FakeReply:
    status = 200
    content_type = "image/png"
    def __init__(self, data): self.content = FakeContent(data); self.content_length = len(data)
    async def __aenter__(self): return self
    async def __aexit__(self, *args): pass

class FakeHttp:
    def __init__(self, data): self.data = data; self.calls = []
    def get(self, url, **kwargs): self.calls.append((url, kwargs)); return FakeReply(self.data)
    async def close(self): pass

class ReaderTest(unittest.IsolatedAsyncioTestCase):
    def reader(self):
        return ReadOnlyMedia(GatewayConfig(audio=AudioConfig(enabled=True, media_provider="spotify_connect",
            camilla=CamillaDspConfig(enabled=True, websocket_url="ws://127.0.0.1:1234",
                channel_faders=(("MASTER",0),("SUB",1),("REAR",2),("AUX3",3),("AUX4",4))),
            spotify_connect=SpotifyConnectConfig(enabled=True, state_file="/unused/state", control_socket="/unused/socket"))))

    async def test_image_is_bound_to_track_session_and_never_forwards_a_key_or_follows_redirects(self):
        reader = self.reader(); reader._snapshot = AsyncMock(return_value=snapshot())
        http = FakeHttp(png()); reader._session = http
        public = await reader.snapshot()
        self.assertNotIn("url", public["artwork"])
        identifier = public["artwork"]["id"]
        self.assertEqual(await reader.artwork(identifier), (png(), "image/png"))
        self.assertEqual(await reader.artwork(identifier), (png(), "image/png"))
        self.assertEqual(len(http.calls), 1)
        self.assertFalse(http.calls[0][1]["allow_redirects"])
        self.assertEqual(http.calls[0][1]["headers"], {"Accept": "image/jpeg, image/png", "Accept-Encoding": "identity"})
        reader._snapshot.return_value = dict(snapshot(), receiverSessionId="new-session")
        with self.assertRaises(MediaChanged): await reader.artwork(identifier)
        await reader.close()

    async def test_track_change_during_download_discards_image_and_old_pending_result(self):
        reader = self.reader(); before = snapshot(); after = copy.deepcopy(before)
        after["track"]["uri"] = "spotify:track:6rqhFgbbKwnb9MLmUQDhG6"
        reader._snapshot = AsyncMock(side_effect=[before, after]); reader._session = FakeHttp(png())
        with self.assertRaises(MediaChanged): await reader.artwork(reader._image_id(before))
        self.assertEqual(len(reader._cache), 0)
        await reader.close()

    async def test_oversized_or_descriptor_mismatched_images_do_not_enter_cache(self):
        reader = self.reader(); reader._snapshot = AsyncMock(return_value=snapshot())
        reader._session = FakeHttp(png(2, 1))
        with self.assertRaises(MediaUnavailable): await reader.artwork(reader._image_id(snapshot()))
        self.assertFalse(reader._cache)
        await reader.close()

    async def test_compressed_body_redirect_and_closed_reader_fail_without_caching(self):
        for status, headers in ((302, {}), (200, {"Content-Encoding": "gzip"})):
            reader = self.reader(); reader._snapshot = AsyncMock(return_value=snapshot())
            reply = FakeReply(png()); reply.status = status; reply.headers = headers
            http = FakeHttp(png()); http.get = lambda *args, **kwargs: reply; reader._session = http
            with self.assertRaises(MediaUnavailable): await reader.artwork(reader._image_id(snapshot()))
            self.assertFalse(reader._cache); await reader.close()
            with self.assertRaises(MediaUnavailable): await reader.artwork(reader._image_id(snapshot()))

    async def test_real_local_read_socket_has_matching_receipt_and_only_read_action(self):
        with tempfile.TemporaryDirectory(prefix="no5-media-", dir="/tmp") as directory:
            path = Path(directory) / "read.sock"
            actions = []
            async def respond(reader, writer):
                request = json.loads(await reader.readline()); actions.append(request["action"])
                writer.write((json.dumps({"version": 1, "kind": "spotify-control-ack", "requestId": request["requestId"],
                    "status": "VERIFIED", "message": "Receiver readback verified", "confirmation": snapshot()}) + "\n").encode())
                await writer.drain(); writer.close(); await writer.wait_closed()
            server = await asyncio.start_unix_server(respond, path=str(path))
            try:
                self.assertEqual(validate_media(await receiver_read(path, {"type": "READ_MEDIA"}))["track"]["uri"], URI)
                self.assertEqual(actions, [{"type": "READ_MEDIA"}])
            finally:
                server.close(); await server.wait_closed()

class AuthenticationTest(unittest.IsolatedAsyncioTestCase):
    async def test_all_new_routes_require_existing_key_and_bad_queries_do_not_reach_receiver(self):
        from aiohttp.test_utils import TestClient, TestServer
        from no5_pi_gateway.service import create_app
        key = "test-existing-gateway-key"
        with patch.dict(os.environ, {"NO5_MEDIA_TEST_HASH": hashlib.sha256(key.encode()).hexdigest()}):
            app = await create_app(GatewayConfig(audio_gateway_key_hash_env="NO5_MEDIA_TEST_HASH"))
            service = app["gateway_service"]
            service.media.snapshot = AsyncMock(return_value=snapshot())
            service.media.browse = AsyncMock(return_value={})
            service.media.artwork = AsyncMock(return_value=(png(), "image/png"))
            service.media.playlist_artwork = AsyncMock(return_value=(png(), "image/png"))
            client = TestClient(TestServer(app)); await client.start_server()
            try:
                paths = ["/v1/audio/media", "/v1/audio/navigation", "/v1/audio/artwork/" + "a" * 64, "/v1/audio/playlist-artwork/" + "a" * 64,
                         "/v1/audio/browse?type=LIKED&receiverSessionId=media-test"]
                for path in paths:
                    self.assertEqual((await client.get(path)).status, 401)
                    self.assertEqual((await client.get(path, headers={"X-No5-Gateway-Key": "wrong"})).status, 401)
                    self.assertEqual((await client.get(path, headers=[("X-No5-Gateway-Key", key)] * 2)).status, 401)
                    self.assertEqual((await client.get(path, headers={"X-No5-Gateway-Key": key})).status, 200)
                self.assertEqual((await client.get("/v1/audio/browse?type=LIKED&limit=41",
                                                  headers={"X-No5-Gateway-Key": key})).status, 400)
                self.assertEqual(service.media.browse.await_count, 1)
                self.assertEqual((await client.post("/v1/audio/select",json=selection_request())).status,401)
                self.assertEqual((await client.post("/v1/audio/select",data=b'{"version":1,"version":1}',headers={"X-No5-Gateway-Key":key})).status,400)
            finally:
                await client.close()

REVISION = '01234567-89ab-4cde-8fab-0123456789ab'

def selection_request():
    return {'version':1,'kind':'spotify-selection','requestId':'selection-test:1',
            'receiverSessionId':'media-test','queueRevision':REVISION,
            'selection':{'type':'QUEUE','index':0,'uri':URI}}

def selection_readback():
    return {'version':1,'kind':'spotify-selection-readback','receiverSessionId':'media-test',
            'trackUri':URI,'entryId':'unique-entry','observedAtEpochMs':time.time_ns()//1000000,'playerSequence':4}

class SelectionTest(unittest.IsolatedAsyncioTestCase):
    reader = ReaderTest.reader
    def prepared(self):
        reader=self.reader(); value=snapshot(); value['queue']['revision']=REVISION
        reader._snapshot=AsyncMock(return_value=value)
        return reader

    async def test_verified_receipt_is_individual_and_duplicate_id_never_writes_again(self):
        reader=self.prepared()
        with patch('no5_pi_gateway.media.receiver_selection',AsyncMock(return_value=('VERIFIED','Observed player',selection_readback()))) as write:
            first=await reader.select(selection_request()); second=await reader.select(selection_request())
            self.assertEqual(first,second); self.assertEqual(first['status'],'VERIFIED'); self.assertEqual(write.await_count,1)
            self.assertEqual(write.await_args.args[2]['type'],'SELECT_QUEUE')
            altered=selection_request(); altered['selection']['index']=1
            self.assertEqual((await reader.select(altered))['status'],'REJECTED'); self.assertEqual(write.await_count,1)

    async def test_changed_queue_or_session_and_wrong_ordinal_cannot_write(self):
        for field in ('revision','session','ordinal'):
            reader=self.prepared()
            if field=='revision': reader._snapshot.return_value['queue']['revision']='changed'
            elif field=='session': reader._snapshot.return_value['receiverSessionId']='other-session'
            else: reader._snapshot.return_value['queue']['next']=[]
            with patch('no5_pi_gateway.media.receiver_selection',AsyncMock()) as write:
                self.assertEqual((await reader.select(selection_request()))['status'],'REJECTED'); write.assert_not_awaited()

    async def test_unknown_or_wrong_target_receipt_is_terminal_without_replay(self):
        for status in ('UNKNOWN','VERIFIED'):
            reader=self.prepared(); confirmed=selection_readback(); confirmed['trackUri']='spotify:track:6rqhFgbbKwnb9MLmUQDhG6'
            with patch('no5_pi_gateway.media.receiver_selection',AsyncMock(return_value=(status,'Uncertain',confirmed))) as write:
                self.assertEqual((await reader.select(selection_request()))['status'],'UNKNOWN')
                self.assertEqual((await reader.select(selection_request()))['status'],'UNKNOWN'); self.assertEqual(write.await_count,1)

    async def test_malformed_selection_is_rejected_before_any_receiver_access(self):
        reader=self.prepared()
        for mutate in (lambda v:v.update(extra=True),lambda v:v['selection'].update(index=True),lambda v:v['selection'].update(uri='https://example.org'),lambda v:v['selection'].update(index=80)):
            value=selection_request(); mutate(value)
            with self.assertRaises(MediaUnavailable): await reader.select(value)
        reader._snapshot.assert_not_awaited()

    async def test_collection_query_and_observed_page_identity_reach_receiver_once(self):
        reader=self.prepared(); value=selection_request()
        value['selection'].update(type='COLLECTION',query={'type':'LIKED','offset':20,'limit':20},pageRevision='no5-page-identity')
        with patch('no5_pi_gateway.media.receiver_selection',AsyncMock(return_value=('REJECTED','Page changed',None))) as write:
            self.assertEqual((await reader.select(value))['status'],'REJECTED')
            self.assertEqual(write.await_args.args[2]['expected_page_revision'],'no5-page-identity')
            self.assertEqual(write.await_args.args[2]['query']['offset'],20)


class PlaylistDetailTest(unittest.IsolatedAsyncioTestCase):
    reader=ReaderTest.reader
    async def test_playlist_cover_is_observed_session_bound_and_never_exposes_cdn_url(self):
        reader=self.reader(); reader._snapshot=AsyncMock(return_value=snapshot());reader._session=FakeHttp(png())
        value={"version":1,"kind":"spotify-browse","receiverSessionId":"media-test","observedAtEpochMs":time.time_ns()//1000000,
            "page":{"offset":0,"limit":20,"revision":"page","collectionRevision":"root","items":[{"uri":"spotify:playlist:4uLU6hMCjMI75M1A2tKUQC","title":"Playlist","artist":None,
                "playlist":{"owner":"Actual owner","description":"Actual description","trackCount":0,"collaborative":False,"artwork":{"url":CDN}}}],"nextOffset":None,"complete":True}}
        reader._read=AsyncMock(return_value=value)
        public=await reader.browse("media-test",{"type":"PLAYLISTS","offset":0,"limit":20});details=public['page']['items'][0]['playlist']
        self.assertEqual(details['trackCount'],0);self.assertNotIn('url',details['artwork']);identifier=details['artwork']['id']
        self.assertEqual(await reader.playlist_artwork(identifier),(png(),'image/png'))
        reader._snapshot.return_value=dict(snapshot(),receiverSessionId='changed')
        with self.assertRaises(MediaChanged):await reader.playlist_artwork(identifier)
        with self.assertRaises(MediaChanged):await reader.playlist_artwork('f'*64)
        await reader.close()

    async def test_playlist_shuffle_start_validates_real_context_mode_and_deduplicates_unknown_outcomes(self):
        reader=SelectionTest.prepared(self)
        request=selection_request();request['selection']={'type':'PLAYLIST_START','uri':'spotify:playlist:4uLU6hMCjMI75M1A2tKUQC','shuffle':True,'pageRevision':'observed-page'}
        confirmation=dict(selection_readback(),kind='spotify-playlist-start-readback',playlistUri=request['selection']['uri'],shuffle=True)
        with patch('no5_pi_gateway.media.receiver_selection',AsyncMock(return_value=('VERIFIED','Confirmed',confirmation))) as write:
            self.assertEqual((await reader.select(request))['status'],'VERIFIED');self.assertEqual((await reader.select(request))['status'],'VERIFIED')
            self.assertEqual(write.await_count,1);self.assertEqual(write.await_args.args[2]['type'],'START_PLAYLIST')
        reader=SelectionTest.prepared(self);confirmation['shuffle']=False
        with patch('no5_pi_gateway.media.receiver_selection',AsyncMock(return_value=('VERIFIED','Wrong mode',confirmation))) as write:
            self.assertEqual((await reader.select(request))['status'],'UNKNOWN');self.assertEqual((await reader.select(request))['status'],'UNKNOWN');self.assertEqual(write.await_count,1)
        await reader.close()

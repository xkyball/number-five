from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
import json
from pathlib import Path
import tempfile
import time
import unittest

from no5_pi_gateway.audio import (
    AudioError, AudioOutcomeUnknown, CamillaDspAdapter, CamillaDspClient, CamillaFader, CamillaPreset, CamillaSnapshot,
    RaspotifyMediaSnapshot,
    _read_strict_state_file, event_snapshot, validate_audio_command, write_event_state,
)
from no5_pi_gateway.configuration import (
    AudioConfig, AudioFaderLimit, CamillaDspConfig, ConfigurationError, GatewayConfig, RaspotifyConfig,
    SpotifyConnectConfig,
)


FADERS = (("MASTER", 0), ("SUB", 1), ("REAR", 2), ("AUX3", 3), ("AUX4", 4))


def camilla_config() -> CamillaDspConfig:
    return CamillaDspConfig(enabled=True, websocket_url="ws://127.0.0.1:1234", channel_faders=FADERS)


def writable_audio_config(path: str) -> AudioConfig:
    return AudioConfig(
        enabled=True, media_provider="raspotify_onevent_file", hardware_available=True,
        fader_limits=tuple((channel, AudioFaderLimit(-60.0, 0.0, 1.0)) for channel, _index in FADERS),
        camilla=camilla_config(), raspotify=RaspotifyConfig(enabled=True, state_file=path),
    )


def spotify_audio_config(state_path: str, control_socket: str, *, timeout_s: float = 0.25) -> AudioConfig:
    return AudioConfig(
        enabled=True, media_provider="spotify_connect", hardware_available=None,
        camilla=camilla_config(),
        spotify_connect=SpotifyConnectConfig(
            enabled=True, state_file=state_path, control_socket=control_socket,
            poll_interval_s=0.1, command_timeout_s=timeout_s,
        ),
    )


def spotify_state(snapshot: RaspotifyMediaSnapshot) -> bytes:
    return json.dumps({
        "version": 1, "kind": "spotify-connect-state", "connected": snapshot.connected,
        "active": snapshot.active, "state": snapshot.state, "trackId": snapshot.track_id,
        "title": snapshot.title, "artists": list(snapshot.artists), "album": snapshot.album,
        "durationMs": snapshot.duration_ms, "positionMs": snapshot.position_ms,
        "shuffle": snapshot.shuffle, "repeat": snapshot.repeat,
        "observedAtEpochMs": snapshot.observed_at_epoch_ms,
    }, separators=(",", ":")).encode("utf-8")


@dataclass
class _Message:
    type: str
    data: str


class _Socket:
    def __init__(self, replies: list[dict[str, object]]) -> None:
        self._replies = [_Message("TEXT", json.dumps(reply)) for reply in replies]
        self.sent: list[object] = []

    async def send_str(self, value: str) -> None:
        self.sent.append(json.loads(value))

    async def receive(self, *, timeout: float) -> _Message:
        del timeout
        return self._replies.pop(0)


class _SocketContext:
    def __init__(self, socket: _Socket) -> None:
        self.socket = socket

    async def __aenter__(self) -> _Socket:
        return self.socket

    async def __aexit__(self, *unused: object) -> None:
        return None


class _Session:
    def __init__(self, socket: _Socket) -> None:
        self.socket = socket

    def ws_connect(self, *unused: object, **unused_named: object) -> _SocketContext:
        return _SocketContext(self.socket)


def _snapshot_replies(master_db: float, master_muted: bool, *, title: str = "No 5 - Stereo 4.1",
                      config_path: str = "/etc/camilladsp/no5.yml") -> list[dict[str, object]]:
    return [
        {"GetVersion": {"result": "Ok", "value": "4.1.3"}},
        {"GetState": {"result": "Ok", "value": "Running"}},
        {"GetFaders": {"result": "Ok", "value": [
            {"volume": master_db, "mute": master_muted},
            {"volume": -8.0, "mute": False}, {"volume": -9.0, "mute": False},
            {"volume": -10.0, "mute": False}, {"volume": -11.0, "mute": False},
        ]}},
        {"GetClippedSamples": {"result": "Ok", "value": 0}},
        {"GetConfigTitle": {"result": "Ok", "value": title}},
        {"GetConfigFilePath": {"result": "Ok", "value": config_path}},
    ]


class CamillaAdapterTest(unittest.TestCase):
    def test_master_write_uses_camilla_default_control_then_requires_readback(self) -> None:
        socket = _Socket([
            {"SetVolume": {"result": "Ok"}},
            *_snapshot_replies(-12.0, False),
        ])
        snapshot = asyncio.run(CamillaDspClient(camilla_config(), now=lambda: 99).set_fader_db(
            _Session(socket), "MASTER", -12.0,
        ))
        self.assertEqual(socket.sent[0], {"SetVolume": -12.0})
        self.assertNotIn({"SetFaderVolume": [0, -12.0]}, socket.sent)
        self.assertEqual(snapshot.faders["MASTER"].db, -12.0)
        self.assertEqual(snapshot.observed_at_epoch_ms, 99)

    def test_preset_write_validates_then_reloads_and_requires_exact_readback(self) -> None:
        preset = CamillaPreset("camilla-preset_immersive", "No 5 - Immersive 4.1", "/etc/camilladsp/preset_immersive.yml")
        socket = _Socket([
            {"ReadConfigFile": {"result": "Ok", "value": "title: No 5 - Immersive 4.1\n"}},
            {"SetConfigFilePath": {"result": "Ok"}},
            {"Reload": {"result": "Ok"}},
            *_snapshot_replies(-12.0, False, title=preset.title, config_path=preset.path),
        ])
        snapshot = asyncio.run(CamillaDspClient(camilla_config()).apply_preset(
            _Session(socket), preset, "/etc/camilladsp/no5.yml",
        ))
        self.assertEqual(snapshot.config_path, preset.path)
        self.assertEqual(snapshot.config_title, preset.title)
        self.assertEqual(socket.sent[:3], [
            {"ReadConfigFile": preset.path}, {"SetConfigFilePath": preset.path}, "Reload",
        ])

    def test_changed_preset_title_is_rejected_before_any_config_write(self) -> None:
        preset = CamillaPreset("camilla-preset_immersive", "No 5 - Immersive 4.1", "/etc/camilladsp/preset_immersive.yml")
        socket = _Socket([{"ReadConfigFile": {"result": "Ok", "value": "title: Changed since validation\n"}}])
        with self.assertRaisesRegex(AudioError, "title changed"):
            asyncio.run(CamillaDspClient(camilla_config()).apply_preset(
                _Session(socket), preset, "/etc/camilladsp/no5.yml",
            ))
        self.assertEqual(socket.sent, [{"ReadConfigFile": preset.path}])

    def test_preset_readback_mismatch_restores_previous_config_and_is_not_verified(self) -> None:
        preset = CamillaPreset("camilla-preset_immersive", "No 5 - Immersive 4.1", "/etc/camilladsp/preset_immersive.yml")
        previous = "/etc/camilladsp/no5.yml"
        for title, path in [("Unexpected title", preset.path), (preset.title, previous)]:
            with self.subTest(title=title, path=path):
                socket = _Socket([
                    {"ReadConfigFile": {"result": "Ok", "value": "title: No 5 - Immersive 4.1\n"}},
                    {"SetConfigFilePath": {"result": "Ok"}},
                    {"Reload": {"result": "Ok"}},
                    *_snapshot_replies(-12.0, False, title=title, config_path=path),
                    {"SetConfigFilePath": {"result": "Ok"}},
                    {"Reload": {"result": "Ok"}},
                    *_snapshot_replies(-12.0, False, config_path=previous),
                ])
                with self.assertRaisesRegex(AudioError, "post-action readback"):
                    asyncio.run(CamillaDspClient(camilla_config()).apply_preset(_Session(socket), preset, previous))
                selections = [value for value in socket.sent if isinstance(value, dict) and "SetConfigFilePath" in value]
                self.assertEqual(selections, [{"SetConfigFilePath": preset.path}, {"SetConfigFilePath": previous}])
                self.assertEqual(socket.sent.count("Reload"), 2)

    def test_mismatched_readback_is_an_error_not_a_verified_write(self) -> None:
        socket = _Socket([
            {"SetMute": {"result": "Ok"}},
            *_snapshot_replies(-12.0, False),
        ])
        with self.assertRaises(AudioError):
            asyncio.run(CamillaDspClient(camilla_config()).set_fader_mute(_Session(socket), "MASTER", True))
        self.assertEqual(socket.sent[0], {"SetMute": True})

    def test_main_readback_uses_one_actual_fader_reply_during_external_updates(self) -> None:
        replies = _snapshot_replies(-12.0, False)
        socket = _Socket(replies)
        snapshot = asyncio.run(CamillaDspClient(camilla_config()).read_snapshot(_Session(socket)))
        self.assertEqual(snapshot.faders["MASTER"], CamillaFader(-12.0, False))
        self.assertEqual(socket.sent.count("GetFaders"), 1)
        self.assertNotIn("GetVolume", socket.sent)
        self.assertNotIn("GetMute", socket.sent)

    def test_single_reply_main_still_rejects_malformed_actual_values(self) -> None:
        for main in [{"volume": -11.0, "mute": 1}, {"volume": float("nan"), "mute": False},
                     {"volume": -11.0}, {"volume": -11.0, "mute": False, "invented": 0}]:
            with self.subTest(main=main):
                replies = _snapshot_replies(-12.0, False)
                replies[2]["GetFaders"]["value"][0] = main  # type: ignore[index]
                with self.assertRaises(AudioError):
                    asyncio.run(CamillaDspClient(camilla_config()).read_snapshot(_Session(_Socket(replies))))

    def test_preset_catalog_is_scoped_by_filename_and_validated_by_camilla(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            preset = Path(directory) / "preset_saloon.yml"
            preset.write_text("title: ignored locally\n", encoding="utf-8")
            (Path(directory) / "backup.yml").write_text("title: never offered\n", encoding="utf-8")
            config = CamillaDspConfig(
                enabled=True, websocket_url="ws://127.0.0.1:1234", channel_faders=FADERS,
                preset_directory=directory, preset_pattern="preset_*.yml", preset_files=("preset_saloon.yml",),
            )
            socket = _Socket([{"ReadConfigFile": {"result": "Ok", "value": "title: Saloon 4.1\n"}}])
            presets = asyncio.run(CamillaDspClient(config)._read_presets(socket))
            self.assertEqual([(item.identifier, item.title) for item in presets], [("camilla-preset_saloon", "Saloon 4.1")])
            self.assertEqual(socket.sent, [{"ReadConfigFile": str(preset)}])

    def test_fader_labels_can_describe_physical_aux_controls_without_zone_claims(self) -> None:
        config = CamillaDspConfig(
            enabled=True, websocket_url="ws://127.0.0.1:1234", channel_faders=FADERS,
            channel_labels=(("MASTER", "Master"), ("SUB", "Aux 1"), ("REAR", "Aux 2"),
                            ("AUX3", "Aux 3"), ("AUX4", "Aux 4")),
        )
        self.assertEqual(config.fader_labels, {"MASTER": "Master", "SUB": "Aux 1", "REAR": "Aux 2",
                                               "AUX3": "Aux 3", "AUX4": "Aux 4"})

    def test_command_validation_is_strict_before_any_write_path(self) -> None:
        payload = {
            "version": 1, "kind": "audio-command", "sessionId": "phone_1", "requestId": "phone_1:2",
            "serverSessionId": "gateway-1", "expectedRevision": 0,
            "command": {"type": "fader", "channel": "MASTER", "valueDb": -10.0},
        }
        validate_audio_command(payload)
        payload["requestId"] = "other:2"
        with self.assertRaises(AudioError):
            validate_audio_command(payload)

    def test_configuration_refuses_reviewed_hardware_without_all_fader_ranges(self) -> None:
        with self.assertRaises(ConfigurationError):
            AudioConfig(enabled=True, hardware_available=True, camilla=camilla_config())

    def test_configuration_requires_every_range_when_hardware_is_reviewed(self) -> None:
        with self.assertRaises(ConfigurationError):
            AudioConfig(enabled=True, hardware_available=True,
                        fader_limits=(("MASTER", AudioFaderLimit(-60.0, 0.0, 1.0)),),
                        camilla=camilla_config())

    def test_dsp_diagnostic_remains_available_without_media_source(self) -> None:
        adapter = CamillaDspAdapter(GatewayConfig(audio=AudioConfig(enabled=True, camilla=camilla_config())), "session-1")
        adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
            version="4.1.3", state="RUNNING", raw_state="Running", faders={}, config_title=None,
            clipped_samples=0, observed_at_epoch_ms=time.time_ns() // 1_000_000,
        )
        self.assertEqual(adapter.dsp_status, "LIVE")
        self.assertIsNone(adapter.availability_reason)
        payload = adapter.payload
        assert payload is not None
        self.assertEqual(payload["audioSource"]["quality"]["status"], "UNAVAILABLE")  # type: ignore[index]
        self.assertEqual(payload["media"]["state"], "STOPPED")  # type: ignore[index]

    def test_documented_librespot_events_preserve_unknowns_and_never_create_a_clock(self) -> None:
        started = event_snapshot("session_connected", {}, None, now=lambda: 100)
        changed = event_snapshot("track_changed", {
            "TRACK_ID": "spotify:track:1", "NAME": "Wake", "ARTISTS": "Artist A\nArtist B",
            "ALBUM": "Sea", "DURATION_MS": "1000",
        }, started, now=lambda: 101)
        playing = event_snapshot("playing", {"POSITION_MS": "12"}, changed, now=lambda: 102)
        self.assertTrue(playing.connected)
        self.assertTrue(playing.active)
        self.assertEqual(playing.state, "PLAYING")
        self.assertEqual(playing.position_ms, 12)
        self.assertEqual(playing.artists, ("Artist A", "Artist B"))
        # Future librespot events with no mapped V1 semantics cannot make media fresh.
        self.assertEqual(event_snapshot("loading", {}, playing, now=lambda: 999).observed_at_epoch_ms, 102)

    def test_event_writer_is_atomic_state_that_the_gateway_can_validate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "media.json"
            write_event_state(path, "session_connected", {}, now=lambda: 100)
            write_event_state(path, "track_changed", {
                "TRACK_ID": "spotify:track:1", "NAME": "Wake", "ARTISTS": "Artist A",
                "ALBUM": "Sea", "DURATION_MS": "1000",
            }, now=lambda: 101)
            write_event_state(path, "playing", {"POSITION_MS": "12"}, now=lambda: 102)
            snapshot = _read_strict_state_file(path)
            self.assertEqual(snapshot.title, "Wake")
            self.assertEqual(snapshot.position_ms, 12)
            self.assertEqual(path.stat().st_mode & 0o777, 0o640)

    def test_position_events_refresh_real_progress_but_other_known_events_do_not(self) -> None:
        previous = RaspotifyMediaSnapshot(
            connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
            artists=("Artist A",), album="Sea", duration_ms=1_000, position_ms=12,
            shuffle=False, repeat=False, observed_at_epoch_ms=100,
        )
        progressed = event_snapshot("position_changed", {"POSITION_MS": "250"}, previous, now=lambda: 200)
        self.assertEqual(progressed.state, "PLAYING")
        self.assertEqual(progressed.position_ms, 250)
        self.assertEqual(progressed.observed_at_epoch_ms, 200)
        passive = event_snapshot("session_client_changed", {}, progressed, now=lambda: 300)
        self.assertEqual(passive.position_ms, 250)
        self.assertEqual(passive.observed_at_epoch_ms, 200)

    def test_audio_payload_needs_real_dsp_and_event_evidence_but_advertises_no_writes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            audio = AudioConfig(
                enabled=True, camilla=camilla_config(), media_provider="raspotify_onevent_file",
                raspotify=RaspotifyConfig(enabled=True, state_file=str(Path(directory) / "media.json")),
                # Display ranges are useful before command permission has
                # been granted. They must never turn a control writable.
                fader_limits=(("MASTER", AudioFaderLimit(-60.0, 0.0, 1.0)),),
            )
            adapter = CamillaDspAdapter(GatewayConfig(audio=audio), "session-1")
            now = time.time_ns() // 1_000_000
            adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
                version="4.1.3", state="RUNNING", raw_state="Running",
                faders={"MASTER": type("F", (), {"db": -12.0, "muted": False})(),
                         "SUB": type("F", (), {"db": -8.0, "muted": False})(),
                         "REAR": type("F", (), {"db": -9.0, "muted": False})(),
                         "AUX3": type("F", (), {"db": -10.0, "muted": False})(),
                         "AUX4": type("F", (), {"db": -11.0, "muted": False})()},
                config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
            )
            adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
                artists=("Artist A",), album="Sea", duration_ms=1000, position_ms=12,
                shuffle=False, repeat=False, observed_at_epoch_ms=now,
            )
            payload = adapter.payload
            assert payload is not None
            self.assertEqual(payload["authority"], "CAMILLA_DSP_MASTER")
            self.assertEqual(payload["capabilities"], {
                "playPause": False, "nextPrevious": False, "seek": False, "shuffleRepeat": False,
                "faderWrite": False, "mute": False, "presetWrite": False,
            })
            self.assertIsNone(payload["dsp"]["hardwareAvailable"])  # type: ignore[index]
            self.assertEqual(payload["dsp"]["faderControls"][0]["limit"], {  # type: ignore[index]
                "minDb": -60.0, "maxDb": 0.0, "stepDb": 1.0,
            })
            self.assertEqual(payload["limits"], {  # type: ignore[index]
                "MASTER": {"minDb": -60.0, "maxDb": 0.0, "stepDb": 1.0},
            })

    def test_fader_command_is_idempotent_revision_bound_and_readback_verified(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            adapter = CamillaDspAdapter(GatewayConfig(audio=writable_audio_config(str(Path(directory) / "media.json"))), "gateway-1")
            now = time.time_ns() // 1_000_000
            original = CamillaSnapshot(
                version="4.1.3", state="RUNNING", raw_state="Running",
                faders={"MASTER": CamillaFader(-5.0, False), "SUB": CamillaFader(-8.0, False),
                         "REAR": CamillaFader(-9.0, False), "AUX3": CamillaFader(-10.0, False),
                         "AUX4": CamillaFader(-11.0, False)},
                config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
            )
            adapter._latest = original  # type: ignore[assignment]
            adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
                artists=("Artist A",), album="Sea", duration_ms=1000, position_ms=12,
                shuffle=False, repeat=False, observed_at_epoch_ms=now,
            )

            class Client:
                writes = 0

                async def set_fader_db(self, unused_session: object, channel: str, value: object) -> CamillaSnapshot:
                    self.writes += 1
                    assert channel == "MASTER" and value == -6.0
                    return CamillaSnapshot(
                        version="4.1.3", state="RUNNING", raw_state="Running",
                        faders=original.faders | {"MASTER": CamillaFader(-6.0, False)},
                        config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
                    )

                async def set_fader_mute(self, unused_session: object, channel: str, value: object) -> CamillaSnapshot:
                    raise AssertionError("mute was not requested")

            client = Client()
            adapter._client = client  # type: ignore[assignment]
            class SessionContext:
                async def __aenter__(self) -> object:
                    return object()

                async def __aexit__(self, *unused: object) -> None:
                    return None
            adapter._session_factory = SessionContext  # type: ignore[assignment]
            before = adapter.payload
            assert before is not None
            request = {
                "version": 1, "kind": "audio-command", "sessionId": "phone_1", "requestId": "phone_1:2",
                "serverSessionId": "gateway-1", "expectedRevision": before["revision"],
                "command": {"type": "fader", "channel": "MASTER", "valueDb": -6.0},
            }
            # A normal DSP poll can complete between Android GET and POST.
            # Fresh observation times must not invalidate an unchanged control state.
            adapter._latest = replace(original, observed_at_epoch_ms=now + 1)
            adapter._media = replace(adapter._media, position_ms=13, observed_at_epoch_ms=now + 1)
            ack = asyncio.run(adapter.command(request))
            self.assertEqual(ack["status"], "VERIFIED")
            self.assertEqual(client.writes, 1)
            self.assertEqual(ack["state"]["dsp"]["faders"]["MASTER"]["db"], -6.0)  # type: ignore[index]
            self.assertTrue(ack["state"]["capabilities"]["faderWrite"])  # type: ignore[index]
            self.assertEqual(asyncio.run(adapter.command(request)), ack)
            self.assertEqual(client.writes, 1)

            # An explicit same-value action still has a newer verified ACK.
            noop = request | {"requestId": "phone_1:3", "expectedRevision": ack["revision"]}
            noop_ack = asyncio.run(adapter.command(noop))
            self.assertEqual(noop_ack["status"], "VERIFIED")
            self.assertGreater(noop_ack["revision"], ack["revision"])
            self.assertEqual(client.writes, 2)
            self.assertEqual(asyncio.run(adapter.command(noop)), noop_ack)
            self.assertEqual(client.writes, 2)

            # A real prior fader change still rejects an old command before I/O.
            stale = request | {"requestId": "phone_1:4"}
            self.assertEqual(asyncio.run(adapter.command(stale))["status"], "REJECTED")
            self.assertEqual(client.writes, 2)

    def test_observation_refresh_updates_payload_without_changing_control_revision(self) -> None:
        adapter = CamillaDspAdapter(GatewayConfig(audio=writable_audio_config("/unused/media.json")), "gateway-1")
        now = time.time_ns() // 1_000_000
        adapter._latest = CamillaSnapshot("4.1.3", "RUNNING", "Running",
            {channel: CamillaFader(-5.0, False) for channel, _ in FADERS}, "Stereo", 0, now)
        adapter._media = RaspotifyMediaSnapshot(True, True, "PLAYING", "track-1", "Song", ("Artist",),
            "Album", 100_000, 12, False, False, now)
        before = adapter.payload
        assert before is not None
        adapter._latest = replace(adapter._latest, observed_at_epoch_ms=now + 1, clipped_samples=2)
        adapter._media = replace(adapter._media, observed_at_epoch_ms=now + 1, position_ms=1012)
        after = adapter.payload
        assert after is not None
        self.assertEqual(after["revision"], before["revision"])
        self.assertEqual(after["dsp"]["quality"]["timestampEpochMs"], now + 1)
        self.assertEqual(after["audioSource"]["quality"]["timestampEpochMs"], now + 1)
        self.assertEqual(after["dsp"]["clippedSamples"], 2)
        self.assertEqual(after["media"]["positionMs"], 1012)

    def test_control_and_freshness_changes_still_advance_revision(self) -> None:
        adapter = CamillaDspAdapter(GatewayConfig(audio=writable_audio_config("/unused/media.json")), "gateway-1")
        now = time.time_ns() // 1_000_000
        adapter._latest = CamillaSnapshot("4.1.3", "RUNNING", "Running",
            {channel: CamillaFader(-5.0, False) for channel, _ in FADERS}, "Stereo", 0, now)
        adapter._media = RaspotifyMediaSnapshot(True, True, "PLAYING", "track-1", "Song", ("Artist",),
            "Album", 100_000, 12, False, False, now)
        previous = adapter.payload
        assert previous is not None
        changes = (
            ("_latest", {"faders": adapter._latest.faders | {"MASTER": CamillaFader(-6.0, False)}}),
            ("_latest", {"faders": adapter._latest.faders | {"MASTER": CamillaFader(-6.0, True)}}),
            ("_latest", {"config_title": "Immersive", "config_path": "/preset/immersive.yml"}),
            ("_media", {"state": "PAUSED"}),
            ("_media", {"track_id": "track-2"}),
            ("_media", {"shuffle": True, "repeat": True}),
            ("_media", {"connected": False, "active": False}),
            ("_latest", {"observed_at_epoch_ms": now - 3001}),
        )
        for attribute, values in changes:
            with self.subTest(attribute=attribute, values=values):
                setattr(adapter, attribute, replace(getattr(adapter, attribute), **values))
                current = adapter.payload
                assert current is not None
                self.assertGreater(current["revision"], previous["revision"])
                previous = current
        self.assertEqual(previous["dsp"]["quality"]["status"], "STALE")
        self.assertFalse(previous["capabilities"]["faderWrite"])

    def test_transport_command_is_rejected_without_camilla_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            adapter = CamillaDspAdapter(GatewayConfig(audio=writable_audio_config(str(Path(directory) / "media.json"))), "gateway-1")
            now = time.time_ns() // 1_000_000
            adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
                version="4.1.3", state="RUNNING", raw_state="Running",
                faders={channel: CamillaFader(-5.0, False) for channel, _index in FADERS},
                config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
            )
            adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                connected=True, active=False, state="PAUSED", track_id=None, title=None, artists=(), album=None,
                duration_ms=None, position_ms=None, shuffle=None, repeat=None, observed_at_epoch_ms=now,
            )
            before = adapter.payload
            assert before is not None
            request = {
                "version": 1, "kind": "audio-command", "sessionId": "phone_1", "requestId": "phone_1:2",
                "serverSessionId": "gateway-1", "expectedRevision": before["revision"],
                "command": {"type": "transport", "action": "PLAY"},
            }
            ack = asyncio.run(adapter.command(request))
            self.assertEqual("REJECTED", ack["status"])
            self.assertIn("transport", ack["message"])

    def test_spotify_transport_keeps_paused_context_but_refuses_missing_connection_or_context(self) -> None:
        import socket
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory) / "spotify-state.json"
            control_path = Path(directory) / "control.sock"
            adapter = CamillaDspAdapter(
                GatewayConfig(audio=spotify_audio_config(str(state_path), str(control_path))), "gateway-1"
            )
            now = time.time_ns() // 1_000_000
            media = RaspotifyMediaSnapshot(
                connected=True, active=True, state="PAUSED", track_id="track-1", title="Song",
                artists=(), album=None, duration_ms=100_000, position_ms=12_000,
                shuffle=False, repeat=False, observed_at_epoch_ms=now,
            )
            with socket.socket(socket.AF_UNIX) as control:
                control.bind(str(control_path))
                adapter._media = media
                self.assertTrue(all(adapter._transport_capabilities().values()))
                for change in ({"connected": False}, {"active": False}, {"track_id": None},
                               {"observed_at_epoch_ms": now - 60_000}):
                    adapter._media = replace(media, **change)
                    self.assertFalse(any(adapter._transport_capabilities().values()), change)

    def test_spotify_transport_requires_real_receiver_readback_not_camilla_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory) / "spotify-state.json"
            control_path = Path(directory) / "spotify-control.sock"
            adapter = CamillaDspAdapter(
                GatewayConfig(audio=spotify_audio_config(str(state_path), str(control_path))), "gateway-1"
            )
            try:
                now = time.time_ns() // 1_000_000
                adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
                    version="4.1.3", state="RUNNING", raw_state="Running",
                    faders={channel: CamillaFader(-5.0, False) for channel, _index in FADERS},
                    config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
                )
                adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                    connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
                    artists=("Artist",), album="Sea", duration_ms=100_000, position_ms=12_000,
                    shuffle=False, repeat=False, observed_at_epoch_ms=now,
                )
                state_path.write_bytes(spotify_state(adapter._media))
                adapter._spotify_control_available = lambda: True  # type: ignore[method-assign]
                sent: list[dict[str, object]] = []

                async def send(request_id: str, action: dict[str, object]) -> None:
                    self.assertEqual(request_id, "phone_1:2")
                    sent.append(action)
                    observed = time.time_ns() // 1_000_000
                    state_path.write_bytes(spotify_state(RaspotifyMediaSnapshot(
                        connected=True, active=True, state="PAUSED", track_id="spotify:track:1", title="Wake",
                        artists=("Artist",), album="Sea", duration_ms=100_000, position_ms=12_020,
                        shuffle=False, repeat=False, observed_at_epoch_ms=observed,
                    )))

                adapter._send_spotify_control = send  # type: ignore[method-assign]
                before = adapter.payload
                assert before is not None
                self.assertTrue(before["capabilities"]["playPause"])  # type: ignore[index]
                self.assertFalse(before["capabilities"]["faderWrite"])  # type: ignore[index]
                request = {
                    "version": 1, "kind": "audio-command", "sessionId": "phone_1", "requestId": "phone_1:2",
                    "serverSessionId": "gateway-1", "expectedRevision": before["revision"],
                    "command": {"type": "transport", "action": "PAUSE"},
                }
                ack = asyncio.run(adapter.command(request))
                self.assertEqual(ack["status"], "VERIFIED")
                self.assertEqual(sent, [{"type": "PAUSE"}])
                self.assertEqual(ack["state"]["media"]["state"], "PAUSED")  # type: ignore[index]
                self.assertEqual(asyncio.run(adapter.command(request)), ack)
                self.assertEqual(len(sent), 1)
            finally:
                pass

    def test_spotify_transport_readback_timeout_is_unknown_and_never_replayed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory) / "spotify-state.json"
            control_path = Path(directory) / "spotify-control.sock"
            adapter = CamillaDspAdapter(
                GatewayConfig(audio=spotify_audio_config(str(state_path), str(control_path), timeout_s=0.25)), "gateway-1"
            )
            try:
                now = time.time_ns() // 1_000_000
                adapter._latest = CamillaSnapshot(  # type: ignore[assignment]
                    version="4.1.3", state="RUNNING", raw_state="Running",
                    faders={channel: CamillaFader(-5.0, False) for channel, _index in FADERS},
                    config_title="No 5 - Stereo 4.1", clipped_samples=0, observed_at_epoch_ms=now,
                )
                adapter._media = RaspotifyMediaSnapshot(  # type: ignore[assignment]
                    connected=True, active=True, state="PLAYING", track_id="spotify:track:1", title="Wake",
                    artists=("Artist",), album="Sea", duration_ms=100_000, position_ms=12_000,
                    shuffle=False, repeat=False, observed_at_epoch_ms=now,
                )
                state_path.write_bytes(spotify_state(adapter._media))
                adapter._spotify_control_available = lambda: True  # type: ignore[method-assign]
                calls = 0

                async def send(_request_id: str, _action: dict[str, object]) -> None:
                    nonlocal calls
                    calls += 1

                adapter._send_spotify_control = send  # type: ignore[method-assign]
                before = adapter.payload
                assert before is not None
                request = {
                    "version": 1, "kind": "audio-command", "sessionId": "phone_1", "requestId": "phone_1:2",
                    "serverSessionId": "gateway-1", "expectedRevision": before["revision"],
                    "command": {"type": "transport", "action": "PAUSE"},
                }
                with self.assertRaises(AudioOutcomeUnknown):
                    asyncio.run(adapter.command(request))
                with self.assertRaises(AudioOutcomeUnknown):
                    asyncio.run(adapter.command(request))
                self.assertEqual(calls, 1)
            finally:
                pass

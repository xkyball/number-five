# No5 Spotify Connect receiver

This is the staged replacement for the existing `no5-raspotify.service`. It
uses the reviewed `librespot` 0.8.0 Spotify Connect implementation directly so
that the receiver, rather than a shell callback, owns real playback events and
the local control handle.

It preserves the known ALSA route `camilladsp` and writes a distinct,
gateway-compatible, atomically replaced `spotify-connect-state` file. Unlike the stock
standalone binary, it enables a one-second `PlayerEvent::PositionChanged`
interval and writes only those real player events; it never extrapolates track
position in a UI timer. A paused context is re-observed each second
only while the receiver-owned Connect session, player thread and event reader
remain healthy. Its position stays unchanged. A disconnected, inactive or
context-less receiver and a stalled playing clock cannot be refreshed by this
idle observation. Event and idle publications share one serialized atomic
writer. Thus a normal pause does not eventually disable the resume control.

## Spotify Connect name

The optional public configuration file `/etc/no5-spotify-connect/environment`
sets the advertised name:

```ini
NO5_SPOTIFY_DEVICE_NAME="No5 (MarineOS Lab)"
```

With the file absent, or this setting omitted, the name remains **No5**.
Use a distinct, non-empty name for each host/VM on the same network.
The unit passes the whole name as one `--name` argument, including spaces;
direct launches can also use `--name "..."`. Rename only requires restarting
`no5-spotify-connect.service`, without restarting CamillaDSP or the gateway.
The existing receiver derives its persistent Spotify device ID from that name,
so distinct names also give the instances distinct device IDs.

`deploy/environment.example` is a public template. Preserve an existing custom
configuration during upgrades; Spotify credentials stay in the private cache.
In the local UTM lab the initial name uses the readable VM name plus a short
UUID tag, currently **No5 (no5-marineos-lab · 912F)**. The tag distinguishes
duplicate UTM display names. A manually configured name survives provisioning.

## Local control boundary

The receiver listens only on `/run/no5-spotify-connect/control.sock`, with
mode `0660`, group `no5gateway` and no TCP listener. The gateway sends strict,
single-line JSON requests (maximum 4096 bytes):

```json
{"version":1,"kind":"spotify-control","requestId":"gateway:1","action":{"type":"PAUSE"}}
```

Supported actions are `PLAY`, `PAUSE`, `NEXT`, `PREVIOUS`, `SEEK` with
`position_ms`, `SHUFFLE` with `enabled`, and `REPEAT` with `enabled`. An
`ACCEPTED` response confirms delivery to librespot's local `Spirc` handle;
the gateway must still await a new state observation before reporting a
verified command to HELM.

## Build and switch plan

`make check` and `make test` run without ALSA, so they are portable contract
checks. On lysmarine first confirm `cargo`, `rustc` 1.85+, `pkg-config`,
`libasound2-dev` and the existing `camilladsp` device route. Then run
`make pi-release`, which performs `cargo build --release --features alsa`.
Install the resulting binary in a new
`/opt/no5-spotify-connect/releases/<id>` directory, point `current` at it,
and install the supplied systemd unit. The exact staged activation and rollback
commands are in [DEPLOYMENT.md](DEPLOYMENT.md).

Do not start it alongside `no5-raspotify.service` on the same host: they share
the CamillaDSP input. The unit encodes this as a systemd conflict. Retain
the old unit and binary for rollback; switching receivers interrupts current
playback once. Acceptance needs a real Connect selection, play/pause,
next/previous, seek, shuffle/repeat, one-second real position events, audio
route confirmation through CamillaDSP, and gateway command readback.

## Shared Camilla Main (opt-in)

`--camilla-volume-bridge --camilla-socket ws://127.0.0.1:1234
--main-min-db -60 --main-max-db 0 --main-step-db 1` selects the reviewed
Camilla4.1.3 Main authority instead of the PCM soft mixer. The ranges must match
the gateway's confirmed physical Main catalog. The gateway's separate
`--camilla-main-authority` option enables local `MASTER_VOLUME` (`value_db`)
and `MASTER_MUTE` (`muted`) relay actions. No extra listener, token, trust or
socket permission is required; other player actions retain their existing gate.

The first startup adopts the old combined gain, quantizing downward and waiting
for the configured DSP ramp before creating the Player. An atomic public
`state.volume-authority.json` marker records prepared/active adoption. Restart
uses current Camilla values without applying the cached Spotify attenuation
again. Confirmed DSP values synchronize back to the active Connect session;
unchanged echoes, stale targets, failed sockets and replaced sessions are
rejected/discarded. PCM remains at unity gain. Main mute retains its previous
dB target; Connect zero maps to mute.

Rollback must restore the previous binary/start options **and** the public
pre-adoption Camilla Main/mute readback before starting the old soft mixer.
Keeping the migrated Main gain with the old mixer would attenuate twice.
Source/socket tests are not phone-volume or physical-knob acceptance.

The current [Spotify feature research](SPOTIFY_FEATURES.md) distinguishes cover,
queue, playlist, library and search capabilities in the existing Connect session
from optional Spotify Web API authorization. It is a source-backed proposal,
not an implementation or live-account feature acceptance.

### LAN discovery on multi-interface hosts

Set `NO5_SPOTIFY_ZEROCONF_IPS` in the target-private receiver environment to a comma-separated list of that target's observed LAN unicast addresses, or repeat `--zeroconf-ip`. This restricts the embedded mDNS responder's advertised addresses; it leaves the discovery HTTP listener reachable over the existing LAN and Mesh paths. Production restore must supply a current nonempty LAN list and exclude tunnel link-local addresses. An absent list retains legacy discovery behavior for compatibility. Recheck the private list after LAN address changes.

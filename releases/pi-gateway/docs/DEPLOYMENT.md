# Build, installation and rollback plan

This is the generic installation plan and prepared package/unit. The first
approved LYS Marine installation is recorded separately in
`LYSMARINE_GATEWAY_DEPLOYMENT_2026-10-06.md`. Do not install over an existing
service until its name, owner, listener and current package have been recorded.

## Local build

From this project directory:

```sh
make test
make package
```

`make package` invokes `uv build` and produces a standard wheel in `dist/`.
It may resolve the declared Python build backend on a clean development host;
it does not download or embed `no5-sonard`, a token, M0/M1 code, or reference
archives.

## Pi prerequisites to verify before changing the host

1. Confirm Debian Bookworm/aarch64, Python 3.11+, the existing Signal K,
   CamillaDSP and Raspotify services, their listeners, and the absence of a
   conflicting `no5-pi-gateway` unit.  Do not stop or replace any existing
   service as part of this check. Capture only read-only evidence: an actual
   Signal K snapshot shape/timestamps and CamillaDSP's version/fader map.
2. Confirm the selected upstream is local and healthy using its read-only
   `/healthz`, `/readyz` and `/api/v1/status`, then observe a read-only
   `/ws/v1/echogram` frame.  `readyz: WAIT_PICO` is not data availability.
3. Keep `[helm]` and `[audio]` disabled until their mappings are accepted. A
   Signal K URL must be a known read-only snapshot and every field must retain
   source observation time. CamillaDSP's local WebSocket URL and fader map must
   be confirmed. For AUDIO, inspect the installed Raspotify/librespot unit and
   configuration first: its service account, the supported `LIBRESPOT_ONEVENT`
   setting and the exact emitted events must be recorded read-only. Raspotify
   alone does not enable `/v1/audio` or writes.
4. For M0, retain `producer = "m0"` and verify the state reads `SIMULATED`.
   For M1, do not set `calibration.valid = true` until Pico firmware, USB/COBS
   CRC transport, clock sync, electrical Venterior wiring and a calibration
   procedure have all passed a physical acceptance record.
5. Make `aiohttp` available from an approved Pi package source.  Create a
   release-local virtual environment and install the built wheel plus its
   approved dependency artifacts.  No credentials are required or included by
   this project.

## Staged installation

Use versioned release directories so rollback is a symlink change, not an
overwrite:

```text
/opt/no5-pi-gateway/releases/<version>/
/opt/no5-pi-gateway/current -> releases/<version>
/etc/no5-pi-gateway/config.toml
/etc/no5-pi-gateway/environment
/var/lib/no5-pi-gateway/
```

Install the provided unit as `no5-pi-gateway.service`, create the dedicated
`no5gateway` service account and copy
`config/no5-pi-gateway.example.toml` to `/etc/no5-pi-gateway/config.toml`.
Start with `source.producer = "disabled"`; verify `GET /v1/sonar` reports
`UNAVAILABLE`.  Then configure the confirmed loopback M0 or M1 URL, reload the
unit, start it, and verify only the app contract:

1. `GET /v1/sonar` has source, source kind, source time, freshness state and
   `canStart=false`, `canStop=false`.
2. The WebSocket selects `no5.sonar.v1` and sends `sonar.state` before any
   binary ping.
3. A binary ping has a valid little-endian metadata length and exactly 2048
   raw sample bytes after metadata.
4. The app shows provenance correctly.  M0 must never appear real; M1 must
   remain uncalibrated until acceptance.

After the separate HELM acceptance, enable its section and verify `GET
/v1/helm` reports the configured source, original field timestamps, nullable
unknowns and no `SIMULATED` provenance.

## AUDIO event-bridge acceptance

The prepared bridge is a local observation adapter, not a Spotify control
client. On lysmarine the package unit and a user-level `raspotify.service`
were found competing; use the supplied `no5-raspotify.service` as the sole
receiver. It preserves the known `camilladsp` ALSA route and connects the
documented `--onevent PROGRAM` hook through the supplied fixed wrapper.

1. Create `no5media`; make `/var/lib/no5-media` an administrator-owned setgid
   directory (`root:no5media`, `2770`), add `user` to the group and use the
   provided gateway drop-in to give `no5gateway` read access. Group write and
   execute are required because the bridge atomically creates and replaces its
   `0640` state file and lock file. Never use world-readable or world-writable
   permissions.
2. Install `deploy/systemd/no5-raspotify.service` and
   `deploy/libexec/no5-raspotify-event`. Disable the package-level
   `raspotify.service` and the user-level competing `raspotify.service`, but
   retain all their unit/configuration files for rollback. Then enable only
   `no5-raspotify.service`. Its command is explicit: `--device camilladsp` and
   `--onevent /usr/local/libexec/no5-raspotify-event`; it does not alter
   CamillaDSP or create a second audio route.
3. Start AUDIO only with `media_provider = "raspotify_onevent_file"`,
   `[audio.raspotify].enabled = true`, its same absolute `state_file`, and the
   independently confirmed CamillaDSP section. Check a real session connect,
   track change, playing, paused, stopped, shuffle and repeat event. Verify
   `/v1/audio` carries their timestamps/provenance, reports `artworkId: null`,
   has all capabilities false and uses `CAMILLA_DSP_MASTER` without Android
   volume.
4. Stop the event input or remove its readable file and verify the route turns
   `503` after its freshness window. It must never replace the last state with
   made-up media data. No AUDIO command may be sent during this acceptance.

`GET /v1/status` remains the diagnostic path when CamillaDSP is configured but
the media state file is missing. The AUDIO contract needs both pieces of real
evidence; Camilla master state alone is not a full application AUDIO snapshot.

Camilla fader/mute commands require `hardware_available = true`, all five
reviewed `audio.fader_limits`, and the existing TLS proxy
`X-No5-Gateway-Key`. The gateway keeps only a root-provisioned
SHA-256 verifier of that key in `/etc/no5-pi-gateway/environment`; never put
the raw key in TOML, Git, logs or an Android build. Command acknowledgements
are post-readback only. The onevent adapter does not change this rule: it has
no Spotify transport command or readback implementation.

### CamillaDSP preset catalogue

`audio.camilla.preset_files` is an explicit allowlist of intended operating
presets. Keep dated `*_before_*` files as rollback material if needed, but do
not publish them through a filename glob merely because CamillaDSP can parse
them. The deployment template allows only the current Stereo 4.1 and
Immersive 4.1 presets. Each allowed file is still validated by CamillaDSP
before it is exposed to the app.

### Controlled Spotify Connect receiver

`projects/no5-spotify-connect` is the staged replacement when the app needs
real Spotify transport. Build it on the Pi with its ALSA feature, install a
versioned release and its supplied unit, but leave `no5-raspotify.service`
enabled until the binary, local state directory and unit verification have all
passed. It uses the same `camilladsp` ALSA device, owns no network control
listener, and communicates with this gateway only over the group-restricted
`/run/no5-spotify-connect/control.sock`.

The atomic provider switch changes exactly these AUDIO values together with
the receiver unit transition:

```toml
[audio]
media_provider = "spotify_connect"

[audio.raspotify]
enabled = false

[audio.spotify_connect]
enabled = true
state_file = "/var/lib/no5-media/spotify-connect-state.json"
control_socket = "/run/no5-spotify-connect/control.sock"
poll_interval_s = 0.25
command_timeout_s = 3.0
```

Verify a real Connect session, play/pause, next/previous, seek,
shuffle/repeat and one-second source position events. Every POST must return
`VERIFIED` only after its state file shows a later matching player event. A
503 means the Spirc delivery outcome is unknown and must be reconciled with a
fresh GET; do not replay the request. If acceptance fails, restore the prior
gateway config, stop the new receiver and start `no5-raspotify.service`.

## Weather acceptance

Enable `[weather]` only after a read-only Signal K snapshot contains both
current latitude and longitude with their own source timestamps. Enter those
four exact paths; do not substitute a marina, phone or remembered position.
Verify `GET /v1/weather` reports `FORECAST`, `REAL`, distinct requested/grid
locations, model validity and fetch times. Disable it again if the boat's
outbound internet policy or coordinate sharing policy does not allow the
Open-Meteo HTTPS request.

## Local simulation overlay (optional)

The simulator is part of this process but is disabled by default. It is a
loopback-only, bearer-authenticated local control surface; it does not add a
new Android API and cannot write Signal K, CamillaDSP, Raspotify/librespot or
`no5-sonard`. Install the corresponding Nginx configuration before enabling
it, so `/admin/` is denied on the TLS/LAN listener. Then follow
[LOCAL_SIMULATION.md](LOCAL_SIMULATION.md) for the separate token, state-file
and SSH-tunnel procedure. Do not enable it until the local operator explicitly
accepts that any HELM overlay is visible to the app as `SIMULATED`.

## Rollback

If any contract or health check fails, stop only `no5-pi-gateway`, repoint
`/opt/no5-pi-gateway/current` to the previous release, and start that same
unit.  If no prior gateway release exists, disable this new unit.  Leave
`no5-sonard`, Signal K, CamillaDSP and Raspotify untouched.  Preserve journal
logs and the gateway configuration for diagnosis; do not delete raw SONAR
archives.

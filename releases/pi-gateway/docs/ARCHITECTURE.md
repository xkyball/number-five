# Architecture and contract boundary

`no5-pi-gateway` is a new project in the `no-pi` repository.  It is the shared
app-facing V1 boundary for SONAR, HELM and AUDIO, and deliberately does not
replace Signal K, CamillaDSP, Raspotify, or `no5-sonard`.

```text
No5 Android app
  GET /v1/helm, /v1/audio, /v1/sonar, /v1/status
  WS  /v1/sonar/stream  (no5.sonar.v1)
  POST /v1/sonar/command, /v1/audio/command
             |
             v
       no5-pi-gateway
       /      |        \
      v       v         v
 Signal K  CamillaDSP  no5-sonard M0/M1
 snapshot WebSocket
             |
             v
 Raspotify/librespot --onevent (legacy observation-only)
                 or
 No5 Spotify Connect receiver -- local Unix socket + atomic state file
```

The layers have intentionally different contracts.  The gateway does not add a
second Android API and it never calls M0's simulator start/stop endpoints or
assumes M1 has start/stop endpoints. Signal K routes and field paths are local
configuration, not hard-coded guesses. This project never controls Android
volume: master volume and mute are CamillaDSP's documented default Main control
only. See `CENTRAL_GATEWAY.md` for feature availability and the Spotify
receiver acceptance. The Android HELM source polls exactly `/v1/helm`; it does
not use a HELM snapshot sub-path or revision stream. It orders samples through
the source session and timestamps.

## Source truth and freshness

| Configured source | Android provenance | What is delivered | What is not claimed |
| --- | --- | --- | --- |
| `disabled` | unavailable | no frame | no fallback or generated ping |
| `m0` | `SIMULATED` | M0's raw 1024 `UInt16` frame and direct range scale | physical echo or depth measurement |
| `m1` | `REAL` raw Pico-host transport | raw M1 frames only | validated Venterior electrical/acoustic path or calibrated nautical depth |

The adapter parses the exact 40-byte little-endian
`<BBHQqfffIHH>` upstream header and requires the app contract's 1024 bins.  It
maps a bin using `bin * displayed_max_depth_m / 1023`; it does not invent sound
speed, sample period, transducer offset, or keel offset.  Android's required
`soundSpeedMps` field is a documented schema placeholder while
`calibration.valid=false`; consumers must not treat it as a calibration.

For `no5-sonard-openecho` 0.3.5, optional V1 display geometry and same-ping
canonical Presentation are separate from ADC calibration and the legacy
header detector. See [SONARD_035.md](SONARD_035.md) for the bounded HTTP/WS join,
nonzero-TX conversion, confidence rules and empirical-reference limits. Older
M0/M1 contracts and their source provenance remain supported.

Raw bins are passed untouched.  The UI retains responsibility for its agreed
display transfer: gain `20 log10(1.4)`, reciprocal gamma `1/0.72`, threshold
zero and no per-ping normalization.  The gateway does not apply gamma or
rescale data.

M0/M1 lack the Android session, generation and calibration-revision metadata.
The gateway creates a fresh `serverSessionId` at process start and advances a
local `generation` when its upstream WebSocket reconnects or a packet's source
clock/ping ID moves backward.  This is adapter provenance, not a claim about
an upstream hardware session.

`/readyz` means the gateway process can serve its configured contract.  It is
not SONAR availability; clients must use `sonar.state.status`, source time and
freshness.  M1 `WAIT_PICO` in particular remains `IDLE`/unavailable for data,
not live evidence.

## SONAR command safety

The command endpoint requires a bearer token from an environment variable,
strictly bounds the body to 4096 bytes, rejects duplicate JSON keys, validates
the complete revision-bound envelope and retains request watermarks without
eviction.  Both shipped adapters advertise `canStart=false` and
`canStop=false`; every authenticated command receives a `REJECTED` ACK with an
authoritative state readback.  No test and no production request writes to the
SONAR upstream daemon. A future writable SONAR adapter must first implement explicit
authentication, validated command mapping, post-action readback and a new test
suite before its capability can become true.

AUDIO has its own authenticated, revision-bound CamillaDSP write path for
faders, mute and validated presets. See `AUDIO_CATALOG.md` for catalog scope,
readback and rollback behavior. The shipped deployment keeps writes disabled.

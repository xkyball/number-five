# Central Pi gateway scope

This project is the local, versioned No5 V1 boundary. It has one process and
one `serverSessionId`, while its upstreams remain independently configured and
their evidence remains independently fresh.

| App feature | Route | Productive input | Current delivery | Write state |
| --- | --- | --- | --- | --- |
| HELM | `GET /v1/helm` | Explicit configured Signal K JSON snapshot | Implemented, read-only | Never writable |
| Weather | `GET /v1/weather` | Explicit fresh Signal K position plus Open-Meteo HTTPS forecast | Implemented, configuration-dependent | Never writable |
| AUDIO/DSP | `GET /v1/audio`, `POST /v1/audio/command` | CamillaDSP 4.1.3 WebSocket plus legacy onevent state or the No5 Spotify Connect receiver | Implemented, configuration-dependent | Camilla fader/mute/validated preset and receiver transport, authenticated and readback-verified |
| SONAR | `GET /v1/sonar`, stream | `no5-sonard` M0/M1 | Implemented | M0/M1 controls explicitly rejected |
| diagnostics | `GET /v1/status` | Gateway's local observations | Implemented | None |

## HELM / Signal K and Android-client proof

The separately owned current HELM client is a polling client. Its source uses
exactly `GET /v1/helm` at 50 ms; it has no
`/no5/helm/v1/snapshot` route or HELM WebSocket/revision stream. The Build47
HTTP transport supports the origin-bound proxy key and CA described in
`TLS_PROXY.md`. Ordering is by `serverSessionId` plus producer and
per-field timestamps, not by a HELM `revision` field. The optional contract
test reads that checkout without modifying it and validates both its real
fixture and a gateway projection against its current JSON schema:

```sh
NO5_HELM_CLIENT_ROOT=/absolute/path/to/no-apps/helm make test
```

It pins the reviewed schema/fixture hashes so a client-contract change fails
locally before this gateway is updated.

Signal K is not treated as a single universal REST schema. The administrator
must configure an absolute read-only `snapshot_url`, an RFC6901
`published_at_pointer`, a `vessel_time_pointer`, and each No5 field's value and
original observation time. The adapter accepts only bounded uncompressed JSON
responses, no redirects, and no duplicate keys. A request receipt is never a
measurement time.

Each No5 field is independently marked `LIVE`, `STALE`, `LOST`, `INVALID`, or
`UNAVAILABLE`; absent values remain `null`. The adapter only converts units
when the mapping requests one of the declared transforms. Incomplete latitude
or longitude removes both coordinates. Gear is suppressed when its paired RPM
observation is not usable. A malformed or failed source response clears the
published HELM snapshot rather than serving an old payload as new evidence.

## AUDIO / CamillaDSP and Spotify Connect

CamillaDSP's [documented WebSocket API](https://github.com/HEnquist/camilladsp/blob/master/websocket.md)
is suitable for DSP evidence: the adapter
requires `GetVersion == 4.1.3`, reads processing state, Main volume/mute,
declared faders, clipped sample count, and config title. It has no configured
network default; the local URL and the Main/AUX index map are explicit.

Raspotify is a wrapper around librespot. Its documented `--onevent PROGRAM`
hook invokes a program for playback events and supplies metadata/state values
such as `TRACK_ID`, `NAME`, `ARTISTS`, `ALBUM`, `DURATION_MS`, `POSITION_MS`,
`SHUFFLE`, and `REPEAT`/`REPEAT_TRACK`. This project supplies
`no5-raspotify-event-bridge`: it accepts only that known event vocabulary and
writes a bounded, atomic local JSON state file. The gateway only reads that
file. It reports the event's own observation timestamp, preserves unknowns as
`null`, does not infer a progressing playback clock, and does not turn a cover
URL into an invented No5 artwork ID.

With an accepted fresh/stale Camilla snapshot, `GET /v1/audio` delivers AUDIO V1
state independently of Spotify playback. Raspotify and CamillaDSP freshness
are separate. A missing, invalid or lost onevent file makes media unavailable
without hiding accepted DSP observations: title/duration/position remain
unknown, with explicit media quality. A stale recorded event is published as
`STALE`. Installing Raspotify alone does not establish fresh media evidence.

The AUDIO schema lets CamillaDSP remain the `CAMILLA_DSP_MASTER` authority
independently of media metadata. Missing event evidence is represented by
the unavailable media section, not invented playback. Main volume/mute use
`SetVolume`/`SetMute`, never Android
volume; non-master faders use the declared Camilla operations. When local configuration declares reviewed
hardware evidence and an explicit per-fader limit, the gateway admits fader
and mute commands with the existing `X-No5-Gateway-Key`, exact session/revision matching,
bounded request-id idempotency and a new CamillaDSP readback. A preset command
also needs an ID in the validated local catalog and exact file/title readback
after reload; see `AUDIO_CATALOG.md`. Session/revision/catalog checks reject
before writing. A failure after preset path selection attempts restoration of
the previous file and raises an error instead of reporting `VERIFIED`.
The backend verifies a root-provisioned SHA-256 verifier of that existing
header for AUDIO reads and writes, so an unauthenticated loopback caller
cannot bypass the TLS proxy. There is no separate AUDIO bearer or per-user
write permission. No live enabling or deployment is performed by repository
integration.

AUDIO `revision` guards control state rather than the polling clock. New DSP or
receiver observation timestamps, playback-position samples and clip counts are
published with their real values without advancing it. Changes to faders, mute,
preset/configuration, media state/track, catalog, quality or write capabilities
still advance the revision and reject an older command before upstream I/O.
Each verified action advances it even when the requested value was already set.
Cached acknowledgements remain idempotent; clients must not replay rejected or
unconfirmed writes automatically.

`dsp.faderControls[].id` is the stable Camilla targeting key; it is not a
user-facing zone name. The configured `label` is only a default. A HELM client
may store any local display name and visibility/order setting keyed by `id`.
Each configured `limit` (`minDb`, `maxDb`, `stepDb`) is published in the
individual fader and in top-level `limits` even while writes are disabled, so
the UI can show an honest read-only range. Range publication never grants
write capability; clients must still require `faderWrite` or `mute`.

The documented onevent hook remains output-only. Transport is enabled only
with `media_provider = "spotify_connect"` and the separately packaged
`no5-spotify-connect.service`. It owns librespot 0.8's in-process `Spirc`
handle, the existing `camilladsp` ALSA route, a one-second real player-position
event interval, an atomic `spotify-connect-state` file and a group-restricted
Unix socket. The gateway sends one strict bounded socket request, then waits
for a newer player observation before returning `VERIFIED`. A socket response
without matching readback is HTTP 503/unknown outcome, is remembered against
the request ID and cannot be replayed. No TCP control listener, second bearer
or raw Spotify credential is added.

The two receiver services conflict. The legacy `no5-raspotify.service` stays
running until the replacement is staged and starts; the switch is a single
rollbackable transaction and interrupts playback once. The `spotify_connect`
provider publishes `playPause`, `nextPrevious`, `seek` and `shuffleRepeat`
only where the current real receiver state makes each action meaningful.

## Weather / Open-Meteo

`GET /v1/weather` is independent of HELM and AUDIO. It is enabled only with
an explicit, read-only Signal K snapshot URL and four RFC6901 paths for the
latitude/longitude values and their original observation times. Both position
observations must be fresh; no cached, fixed or handset coordinate can trigger
a request. The provider URL is fixed to Open-Meteo HTTPS, redirects and
compressed/non-JSON responses are rejected, and no provider credential or
weather write route exists.

The response is explicitly `REAL` / `FORECAST`, with separate requested boat
and provider-grid locations, fetch time, model-valid time, units and pressure
reference. A current provider error may preserve only a bounded stale forecast;
an initial error or expired model cache returns unavailable rather than a
plausible replacement.

## Required Pi acceptance before enabling inputs

1. Capture and retain one redacted, read-only Signal K snapshot. Decide which
   timestamps are the producer publication and individual sensor observations,
   then enter those exact pointers in `/etc/no5-pi-gateway/config.toml`.
2. Confirm the CamillaDSP WebSocket listener, its actual version, and the
   physical meaning of Main/Aux fader indices without changing its config.
3. Verify the installed Raspotify/librespot service identity and its actual
   onevent configuration, then install the supplied event bridge with a
   dedicated shared state directory. Exercise session, track, play/pause,
   stop, shuffle and repeat events and retain the resulting redacted files.
   Keep AUDIO writes disabled until the authenticated client and the configured
   per-fader safety limits are accepted.
4. Confirm the normal SONAR M0/M1 checks in `DEPLOYMENT.md`. M1 still needs
   Pico, USB/COBS/CRC, clock-sync, wiring, and acoustic calibration acceptance.

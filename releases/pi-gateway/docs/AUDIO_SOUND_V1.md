# HELM sound contract v2

The existing filename is retained for references. Public sound reads, native
sound state and private documents explicitly version the changed EQ policy.
Historical v1 file/live evidence below does not accept this v2 implementation.

This optional capability requires the pinned No5 extension of CamillaDSP 4.1.3
and an approved generated sound profile. A stock Camilla binary or an original
profile is honestly unavailable. Ordinary faders, media and original presets
continue independently. No client can submit YAML, paths or arbitrary processors.

## Endpoints and limits

- `GET /v1/audio/sound`: `audio.read`, `Cache-Control: no-store`.
- `POST /v1/audio/commands`: existing `audio.command` envelope, `202`; bounded
  JSON up to 16384 bytes. All pre-existing fader-stream limits remain 4096.
- Existing result polling/stream: `APPLIED` for sound means an observed matching
  `appliedRevision` from the audio processing thread, verification
  `AUDIO_BLOCK_READBACK`. `PREPARED` alone is not application.
- `WSS /v1/audio/spectrum`: `audio.read`, subprotocol `no5.audio.spectrum.v1`,
  read only, at most four connections, latest frames only, at most 10 Hz.
  Input messages close the socket. Token expiry/revocation closes streams.

GET returns version 2, kind `audio-sound`, `available`, `writable`, `reason`,
`configGeneration`, `sourceGeneration`, numeric `presetRevision`,
`calibrationRevision`, `configurationRevision`, `preset`, `calibration`,
`headroomDb`, `response`, `effectiveCalibration`, `preview`, `capabilities` and
required `eqPolicy`:

```json
{"minReserveDb":6,"maxReserveDb":18,"defaultReserveDb":12,"positiveBandsMustNotOverlap":true}
```
Revisions are independent of the legacy AUDIO fader revision. The legacy
`expectedRevision` is still required in the command envelope but is not used
for sound CAS. Gateway `serverSessionId` and sound generations remain binding.
A processed sound receipt does not force a new generic fader/media revision.
Actual control signature changes still advance it and reject stale fader CAS;
ordinary verified fader/media writes keep their existing revision behavior.

`GET /v1/audio` adds only a bounded optional `dsp.sound` summary and
`capabilities.sound`. Its fader publication does not calculate curves, perform
FFT work or await the sound controller. Its summary additionally exposes
observed `eqEnabled`, selected `reserveDb` and the same `eqPolicy`.

## Models

A preset has exactly:

```json
{"id":"no5-sound-stereo_4_1","name":"Stereo","routingTemplate":"stereo_4_1",
 "eqEnabled":true,"reserveDb":12,
 "eqPoints":[{"id":"point-1","frequencyHz":1000,"gainDb":2,"q":1,"widthLocked":false}],
 "room":{"amount":0,"size":0.5}}
```

There are at most 16 unique stable ASCII IDs of at most 64 bytes. Frequency is
20..20000 Hz, gain -12..12 dB, Q .3..10. Names have at most 100 characters and
128 UTF-8 bytes. A locked point retains `frequencyHz/q` bandwidth when its
frequency moves. Unlocking deliberately permits a new bandwidth. The native
Camilla coefficients are the audio authority; the published response evaluates
the same RBJ peaking coefficients. `eqEnabled` and finite `reserveDb`6..18 are
mandatory in every current model, including native lease fallbacks. Enabling
uses exactly that fixed reserve; point dragging and Neutral never change it.
The headroom readback must equal the chosen reserve when enabled and0 when
disabled. Main is never compensated, and there is no dynamic margin.

Disabled EQ bypasses both filters and reserve while keeping all points, IDs,
widths, room and separate calibration. New native profiles start disabled.
Neutral changes only gains, preserving the explicit switch and reserve.
When enabled, positive-gain visible Q bands (clamped20..20000Hz) may touch but
must not overlap. Cuts and disabled retained curves do not trigger that rule.
The Gateway validates this bounded geometry; the native preparer is the sole
final combined-curve budget authority (65536 log frequencies plus exact point
centres, peak at most the selected reserve with1e-8dB numerical tolerance).
It does not scale the whole curve. Admission never runs that expensive
calculation on the fader event loop. Known no-effect errors remain
`REJECTED/EQ_POSITIVE_BANDS_OVERLAP` or `REJECTED/EQ_COMBINED_GAIN_LIMIT` in the
existing result ring. A malformed/lost acknowledgment remains UNKNOWN.

Only `front_stereo_fullrange`, `stereo_4_1`, `immersive_4_1` are approved.
Editing cannot switch routing. Front-only rejects any nonzero room amount and
does not enable Rear/Sub. Original root preset files remain selectable.

Calibration has exactly `geometryEnabled`, `listenerM`, `speakers`. Every
position is null until measured, or `{x,y,z}` metres with z nullable. Five
logical speakers are required: `FRONT_LEFT`, `FRONT_RIGHT`, `REAR_LEFT`,
`REAR_RIGHT`, `SUB`; each has `id`, `positionM`, `manualDelayMs`, `manualGainDb`.
Manual delay range is -50..50 ms, gain -24..24 dB. Effective delays are shifted
to a common nonnegative zero and bounded to 50 ms. Effective gains attenuate
relative to a common zero, never change Main. The physical output order is
FL, FR, SUB, SUB, RL, RR; both Sub values are identical.

`effectiveCalibration` exposes physical `delaysMs`/`gainsDb`,
`dimensions` (2/3/null), `approximate`, and five `speakers` entries with
`id`, `distanceM`, `delayMs`, `gainDb`. Geometry is an approximation, not
acoustic calibration or a model of Sub phase/crossover/enclosure response.

## Commands and preview ownership

All sound command bodies include `type`, `previewId` and `configGeneration`.

- `soundPreview`: additionally `scope` (`preset` or `calibration`),
  `expectedPresetRevision`, `expectedCalibrationRevision`, and a complete
  scoped `model`.
- `soundSave` / `soundDiscard`: same fields except `model`.
- `soundKeepalive`: no additional fields.

One owner (authenticated key plus role) and one preview ID hold the shared
preview. Only the edited scope's revision is compared. Keepalive at most one
second apart extends the five-second timeout from its server admission time.
Backlogged activity cannot start a new late preview or extend a lost producer;
expired queued sound commands are cancelled before effect. Saving a preset never saves a
pending calibration, and the inverse is also true. Faders/media are never in
the rollback document. Preview IDs/owners do not appear in diagnostics.

The existing command ring remains 64 waiting, 256 terminal results and 64
diagnostic entries. Unstarted preview targets supersede the same owner's
same scope/preview; keepalives coalesce. Save/discard/media discrete actions
are barriers. Unknown writes are not retried. Request-ID high-water marks
prevent replay after result eviction.

Before every effect the gateway journals only the rollback baseline and exact
configuration/source/target revision, fsyncs it atomically, rechecks authority,
then sends one CAS request. After an uncertain send or process loss, it first
observes the DSP. A matching own application can be rolled back; an outstanding
prepared revision remains journalled until it resolves; a foreign configuration
or revision is preserved. A prepared acknowledgment is never falsely reported
as an applied audio block.

## Local Camilla wire

A separate serialized socket and lock carry `GetNo5Sound`, `SetNo5Sound`,
`SetNo5Spectrum`, `GetNo5Spectrum`. No fader operation lock is held while waiting
for sound preparation, processing-block readback or durable storage.

`SetNo5Sound` receives `expectedConfigGeneration`, `expectedAppliedRevision`,
`revision`, `preset`, physical `calibration:{delaysMs,gainsDb}`, and
`transitionMs:50`, required `expectedSourceGeneration`, and `validForMs:1..5000`
computed immediately before the send from the server admission deadline.
`previewLease` is null for an ordinary persistent/recovery target or
`{id,validForMs,fallbackPreset,fallbackCalibration}` for a preview. The first
preview atomically installs its native fallback and expiry with the target;
there is no unprotected first-lease interval. Reply value is
`{status:"PREPARED",revision,headroomDb}`.
`GetNo5Sound` returns schema `no5.camilla-sound/2`, required `eqPolicy`, config/source generations,
prepared/applied revisions, sampleRateHz 48000, approved routingTemplate, preset,
calibration, headroomDb, verification `PROCESSED_BLOCK`, transitionMs 50,
`previewLease:null|{id,fallbackPreset,fallbackCalibration}` and
`lastRollback:null|{leaseId,fromRevision,toRevision}`. A rollback observation is
published only after its processing block, never when merely prepared.

`SetNo5SoundLease` takes the native lease ID, expected config/source generations,
expected applied revision, `validForMs:0..5000`, and complete checked fallback
preset/physical calibration. Its CAS also requires prepared==applied. Zero
releases; a positive timeout renews and may update the baseline. The exact ACK
is `{status:"RELEASED"|"RENEWED",id,appliedRevision}`. A nullable lease on a new
ordinary target ends the prior lease atomically, so a stale timer cannot undo
that target later.

Scope-save first persists its baseline and a native synchronization intent.
Only a matching native lease ACK permits a successful save result. An uncertain
release/renewal produces UNKNOWN. Observing the same source/config/revision,
matching model, no pending transition and the matching fallback or released
lease can reconcile it without retry. A clearly owned processed automatic
rollback may restore a confirmed durable baseline as a new recovery goal;
a foreign revision is preserved. The private journal retains this distinction.

Spectrum frames have schema `no5.audio-spectrum/1`, sequence, sourceGeneration,
samplePosition, sampleTimeNs, timebase `CAPTURE_MONOTONIC_NS`, sampleRateHz 48000,
fftSize 8192, window `HANN`, tap `POST_EQ_PRE_ROOM`, at most 256 increasing
frequenciesHz 20..20000, matching binsDbfs, two peaksDbfs and overRangeSamples.
overRangeSamples counts float samples at or above full scale in the FFT window;
it is not a playback/DAC clip counter. The existing AUDIO DSP clippedSamples
remains separate. The original sample clock is retained; HTTP/WS receipt invents no fresh UTC
time. Frames are at most 32768 encoded bytes. Old source generations and
out-of-order frames are dropped. A 2-second native consumer TTL is renewed
while visible clients are connected; the final detach disables analysis.

## Storage, restore and migration

The opt-in TOML field is
`audio.camilla.sound_state_file=/var/lib/no5-pi-gateway/sound-state.json`.
The file is gateway-owned mode 0600, bounded to 256 KiB, atomically replaced
and fsynced including its directory. At most 64 saved presets/config bindings
exist. No bearer, key, password or caller session is stored in it.

Current documents use `no5.sound-state/2`. The exact owned v1 schema is accepted
only by the local document reader: each old preset and journal fallback gains
`eqEnabled:false,reserveDb:12`, preserving every curve, room, calibration,
revision, binding and recovery field. Reading does not rewrite the file or
apply an audio effect; the next confirmed owned persistence writes v2. Old
neutral presets therefore do not silently become12dB quieter. Over-budget or
overlapping legacy curves remain losslessly editable while disabled. Network
writers and native v2 requests receive no compatibility defaults: omitting
either field is rejected, so an older writer cannot undo the user's switch.

On a pristine approved stage (prepared/applied revision zero) saved EQ/room is
restored only through a configuration generation saved by this owner. The
separate device calibration follows every approved stage. A nonzero foreign
DSP revision is preserved. Corrupt sound state disables sound editing, not
otherwise healthy faders/media or the whole gateway.

Administrative `sound_migrate.capture_legacy` binds actual pre-switch
GetConfigJson/GetConfigFilePath to the provisioner's approved root profile
manifest. It captures actual legacy EQ values using the same explicit disabled/
default12 migration policy. A runtime no5.yml alias needs an explicit
fresh-content-hash alias binding to one approved canonical source; path similarity
alone is never accepted. Root-file neutral gains are
not mistaken for active EQ. `import_legacy` then binds that capture to the
new pristine stage's actual GetNo5Sound generation with a private-store CAS.
It refuses unresolved journals or newer user presets. The previous
master-equalizer.json and original profile files remain untouched. This is
not an app endpoint and is run only by the coordinated deployment recipe.

## Targeted evidence

`tests/test_sound.py` exercises strict models, paired Sub geometry, RBJ response,
separate scoped save/discard, repeated preview rollback to the original state,
owner/config/revision conflicts, loss of Set reply, prepared-without-application,
revocation before effect, width lock, restart restore, spectrum latest fanout,
authenticated real aiohttp HTTP/WS boundaries and fader WS during blocked sound
application. These tests model a processing owner; they are not native DSP,
acoustic or physical Lumina acceptance. Native file tests and deployment evidence
are separate gates in the rebuild delivery.

### Native preview expiry evidence

The native preview worker now expires without a live gateway owner. Prepared
preview targets expire before processing adoption; the native fallback job
remains eligible through producer loss and is processed when samples return.
The gateway recognizes its source/config/lease/revision-bound lastRollback and
clears its journal without sending the expired draft again.

`tools/probe_native_sound_lease.py` records a real native run with a stopped
gateway observer, autonomous five-second rollback, a producer stopped before
preview admission, expiry before application, resumption of the owned fallback,
and an injected loss of the native save-release ACK after delivery. This is
synthetic file PCM and real loopback WebSocket evidence; physical ALSA and
Lumina checks remain separate deployment gates.

### Coordinated real ALSA probe

`tools/probe_live_sound.py` is inert without `--execute`. The activation owner
supplies the installed config, root-only bootstrap credential path and private
rebuild evidence directory. A fresh ephemeral HELM key is enrolled and revoked;
only its public identity is retained privately for exact cleanup. No token,
private key or bootstrap value is serialized. Existing registry rows are checked.

The probe continuously consumes fader WSS publications and persists every raw
ACK and the last verified fader trajectory before cleanup. At most one new CAS
request is allowed after the exact pre-write revision-conflict rejection and a
fresh unchanged complete fader vector. Lost replies and UNKNOWN never retry.
Per-goal latency, including any new CAS attempt, remains bounded by 250 ms.
Private bounded traces keep field values; public reports contain timings and
names of changed observable control fields. Hidden owner CAS is not inferred.

Real ALSA source identity, spectrum sample clocks and non-silent input are
checked independently. Silent advancing spectra do not pass live-audio evidence.
Unknown music cannot prove a calibrated frequency response; the native file
PCM proof remains the quantitative EQ measurement. No source or Spotify action
is injected. Save is not exercised here. Only the probe's own previews and
fully matched fader trajectory can be restored. A cleanup error is recorded
separately and never replaces the original acceptance failure.

`tools/cleanup_live_sound_fader.py` is a one-shot cleanup for the exact recorded
three-VERIFIED/one-REJECTED failed probe. It requires the complete known own fader
vector, unchanged sources/configuration and already-restored sound models. It
sends one new current CAS request and never retries an uncertain result. It is
not a reset, and refuses any ambiguous current state.

### Preset family catalog

Editable Sound profiles retain the original visible names. The root rebuild
activation policy replaces only its exact source filenames with their bound
Sound destinations in the explicit catalog allowlist. Originals and migration
identity remain unchanged for deliberate administrator restoration. Unbound
real presets stay independent, including equal-title presets. No application
protocol extension or title-based deduplication is used. `dsp.presets[].active`
is true only for the actual observed DSP config path; a coincidentally matching
title or missing path cannot identify the active variant.

## Verified command readback extension (2026-10-21)

Successful SOUND result receipts may include `soundReadback` with schema
`no5.sound-command-readback/1`, the complete existing v2 `state`, monotonic
`ageMs`, and `remainingLeaseMs` (null without a preview, otherwise0..5000).
The state is copied from the adapter's verified native response, never from
requested control values. Result polling and repeated POSTs do not renew it;
age increases and the remaining lease decreases. The bounded status stream
omits this field rather than repeatedly transmitting historical sound models.

SOUND POST admission may wait at most40ms for completion. Timeout returns the
existing accepted/running receipt and never cancels or repeats the actor job.
Other AUDIO admission remains immediate. Existing capacities, authorization,
idempotency and native admission/lease deadlines remain unchanged.

New clients accept this optional state only after command/session/server/source,
configuration and revision checks, the existing verification marker, and
conservative age/remaining-lease checks including the whole request duration.
Missing, malformed or old snapshots use live reconciliation. Older clients
continue their existing GET reconciliation and can ignore this extension.

The latency follow-up increases only the bounded SOUND completion wait from40ms to150ms and adds optional diagnostic `workMs` (admission to verified adapter readback). It does not extend command admission, client hard deadlines or native leases. The status stream still omits historical sound snapshots.

# Bounded Sonard 0.4.10/0.4.11 additions to Gateway SONAR V1

These changes expose existing host reads and the existing display-source setter.
They add no acquisition/TX, firmware, recording or raw-archive command. M0 and
older compatible binary streams retain the existing transport. No new scope,
credential propagation, enrollment or direct HELM-to-Sonard connection is used.

## Capabilities and provenance

`GET /v1/sonar` and its state stream retain the existing fields and add optional
`displaySelection`, `amplitude`, `hostPackageVersion`, `observationReason`. Capabilities add
`sourceSelection` and `diagnostics`. Enhanced analysis/filter support is limited
to reviewed package identities `no5-sonard-openecho` 0.3.5, 0.3.6, 0.4.9,
0.4.10 and 0.4.11; source/diagnostics additions require exactly 0.4.10 or
0.4.11. A larger version number is
not a compatibility grant. Source control additionally requires a validated
host selection schema and no automatic native fallback.

`displaySelection`:

```json
{"version":1,"source":"own","generation":1,"publishedHz":18.12,"lastPublishedPingId":7,"observedAtEpochMs":1700000000000,"rawArchiveSource":"all","automaticNativeFallback":false}
```

Its generation is the **host selection epoch**, distinct from outer Gateway
`serverSessionId`/`generation`. Source is `own`, `native` or `all`.
`publishedHz` is the host's selected publication rate, not a measured 5-second
app rate, a run minimum, usable-depth rate or render FPS. The historical
`cadenceHz` remains the capped compatibility/configuration value. No rate is
relabeled or synthesized from a requested schedule.

`observedAtEpochMs` is null unless actually tied to that published ping. Reading
status or source again never makes an older measurement fresh. A selection
change retires prior ping context and advances the Gateway epoch; reconnect and
ID/time regression keep the existing transport-epoch behavior.

`amplitude`:

```json
{"version":1,"encoding":"UINT16_LE","unit":"ADC_CODE","significantBits":12,"fullScaleCode":4095,"normalizationSource":"HELLO_12bit_checked_host_contract","rawSamplesModified":false}
```

This comes from the checked host amplitude schema including its declared
max-pool/interpolation transform without amplitude rescaling. Unknown/invalid
0.4.10/0.4.11 amplitude is an explicit block with null bits/fullscale, normalization
`UNCONFIRMED` and null raw-modification claim. Clients must not use a package
version fallback when that explicit unconfirmed block is present. Legacy hosts
without this additive contract get null. The Gateway never scales raw samples.

## Reference observation, distinct from connectivity

The compact `observationReason` is optional version1 state evidence, delivered
both by `GET /v1/sonar` and the existing state stream. It is present only while
the actual reviewed host status reports `transmit.reason=native_reference_lost`:

```json
{"version":1,"code":"ORIGINAL_REFERENCE_LOST","detail":"native_reference_lost","observedAtEpochMs":null,"statusObservedAtEpochMs":1700000000000,"latched":true,"statusFresh":true}
```

This is an original-reference loss, not proof of an absent marker, a powered-off
transducer or battery state. Fault time remains null on0.4.10. Status time is
the actual HTTP status reception, not a TX start, ping time or renewed WS
publication time. `statusFresh` follows that reception and the configured
status TTL (default5s); disconnect immediately makes it false. A new connection
or upstream change clears the observation. A changed current host reason clears
the compact warning; an old fault does not masquerade as current failure.
This observation never creates a transport/connection error.

The reviewed0.4.11 `no5.original-reference-observation/1` can additionally
provide `firmwareDetail`(1..4), `observedDeviceUs`(microseconds),
`observationKind`(`marker_edge` or `timeout_check`) and nullable `utcBasis`.
These come only from a validated `latched_fault`, with its exact firmware event,
reason and kind. `observedAtEpochMs` is populated only for its original
`observed_utc_ns` with `utc_basis=accepted_usb_time_sync`. Detail4 is a reference
deadline check, not an observed new marker. A later qualified recovery in
`latest` never supplies or refreshes fault time. `latched` continues to label
the historical nature of that fault; fresh status is not fresh error evidence.

The source review binds0.4.11 to its additive unchanged display-selection,
12-bit amplitude and40-byte binary-header contracts and Pico0.7.11's explicit
profile identity. It grants no firmware update, new TX command or acceptance of
0.4.12. Changing raw acquisition window length does not change the1024 display
bin contract; physical stream range remains the received geometry.

## Per-ping additions and bounds

Ping metadata adds the same optional objects plus `pingSource` (`own`, `native`
or null). The host binary header does not itself identify the source. Only an
exact ping-ID **and original UTC-nanosecond** analysis match, a matching source
selection, and its generation can confirm per-ping provenance. `all` remains
an allowed selection while the actual ping source is independently own/native.

Absent or delayed optional analysis does not suppress otherwise compatible raw
images. Such frames carry null provenance/selection and no canonical bottom;
clients must not label them from the state's selected source. A known queued
older selection context is discarded after a source change. The receive-time
selection generation is only an internal retirement hint, never evidence of an
individual ping's origin. No missing own ping is silently replaced by a claimed
native measurement. A bounded same-ping join remains in the existing upstream
owner. Raw archive bytes and host publication counts are unaffected.

Metadata stays at **4096 bytes** and state at **16384 bytes**. Optional confirmed
track overlays are reduced further if needed for the complete metadata budget;
`trackTotal` and `trackOmitted` remain truthful. Amplitude, provenance and sample
bytes are retained. Large IDs remain integers, and multibyte reasons are tested.

`GET /v1/sonar/filter` additionally returns optional `reason`, read solely from
host `selection.reason`, text or null, bounded to512 characters. A command ACK
reason is not substituted for the host's explanation of the effective filter.

## Source command

`GET /v1/sonar/source` (`sonar.read`) returns:

```json
{"version":1,"kind":"sonar-source","serverSessionId":"fixture","generation":1,"displaySelection":{"version":1,"source":"own","generation":1,"publishedHz":18.12,"lastPublishedPingId":7,"observedAtEpochMs":null,"rawArchiveSource":"all","automaticNativeFallback":false}}
```

`PUT /v1/sonar/source` (`sonar.command`) takes exactly:

```json
{"version":1,"serverSessionId":"fixture","generation":1,"clientId":"helm","requestId":1,"source":"native"}
```

Admission returns HTTP202 and a numeric `commandId`. Completion is read through
the existing owner-isolated `GET /v1/sonar/commands/{id}`. Result fields are the
existing filter receipt with `source`/`confirmedSource` instead of
`mode`/`confirmedMode`; statuses and no-replay rules are unchanged. The source
setter receives only `{"source":"native"}` at the existing host endpoint.

Filter and source share 64 pending slots, 256 terminal results and 64 bounded
diagnostics. They share request-ID watermarks per owner/clientId. A producer
uses one increasing sequence or distinct client IDs. Only unstarted same-kind
absolute goals supersede; source/filter boundaries preserve order. Immediately
before a source write a fresh host read must still match the admitted selection
and Gateway epoch, and authorization is checked again. Host PUT offers no CAS
primitive; the Gateway does not claim an atomic cross-process guarantee that
this API lacks. Any uncertain post-write result is UNKNOWN and never repeated.
Expired/evicted receipts return410; a new ID is not permission to replay UNKNOWN.

## Visible-only diagnostic read

`GET /v1/sonar/diagnostics` (`sonar.read`) returns version1,
kind `sonar-diagnostics`, serverSessionId, generation, nullable pingId,
observedAtEpochMs and source, `available`, `stale`, `fields`, trackTotal,
trackOmitted, blockTotal and blockOmitted. Additive optional status evidence is
`statusObservedAtEpochMs`, `statusStale`, `statusTimeBasis`.

Status evidence is independent of the asynchronous analysis ping. Its basis
code is exactly `GATEWAY_STATUS_REQUEST_STARTED_AT_UTC`: the Gateway records
UTC before beginning the two-read exchange, a conservative lower bound for the
status read. It does not claim a new ping/firmware observation timestamp. Both
request elapsed monotonic time and UTC age must fit the configured status TTL
(default5s), and the connected upstream must still have the exact confirmed
host `session.id` UUID, package version and display-selection source/epoch.
Origin, Gateway epoch, host session and selection are checked before/after the
read. A missing/foreign session or selection leaves status time/basis null and
statusStale true; an otherwise matching slow response retains its old request
time and remains stale. The cache never renews that timestamp. A live status
can prove a complete zero-rate window while the raw ping is stale; raw-ping
framing freshness is not an HTTP-status freshness requirement. Missing/failed
optional analysis cannot erase a valid status read. The existing analysis
`observedAtEpochMs`/`stale` remain separately source-/ping-bound and never borrow
the status time.

A field is `{group,name,value:String,unit?:String}`. Groups include status,
host_package, session, acquisition, display_selection, rates, pico, clock,
transmit, ping, presentation, policy, `track:<id>` and `block:<index>`.
No raw host object or arbitrary configuration is forwarded. It projects
existing `/api/v1/status` and `/api/v1/detection/latest` reads. Run-average
usable Hz stays the specifically named host `transmit.run.usable_hz`; no
unavailable rolling minimum or largest ping gap is invented.

For reviewed0.4.11 only, `transmit.rate_control` requires its explicit
`no5.depth-rate-control/1` schema under `no5.depth-rate-status/1`. Bounded scalar
fields use group `transmit`, names such as `rate_control.planned_hz`,
`rate_control.applied_window.sample_count`, `ping_rates.complete`,
`ping_rates.time_basis`, `ping_rates.rates.own.hz` and
`ping_rates.rates.usable.rolling_1s_min`. Planning, five-second own-only counts,
window completeness, time basis and actual applied-window revision are kept
distinct. Null minima or absent rate objects stay absent. Unknown additive
reason strings are preserved as bounded text. `original_reference.latest` and
`original_reference.latched_fault` are separate prefixed diagnostic fields;
fresh recovery markers do not relabel historical faults. Essential source,
host/session and usable five-second window evidence takes priority, followed
by one complete matching track and template block when the existing budgets
permit. Optional status details and further records use the remainder. Duplicate
`group.name` keys are never emitted. These additions are read-only;
the Gateway exposes no rate-policy, ARM, FIRE or depth-window write endpoint.

The response has at most128 fields, group/name80 characters, value2048,
unit40, combined field-text24000 characters and **32768 UTF-8 bytes**. Long
text explicitly ends `[TRUNCATED]`. Track/block records are admitted whole,
with explicit omitted counts. Unknown scalar measurements are
absent rather than fabricated zeros. IDs retain exact decimal text. Diagnostic
analysis is labeled current only for a matching ping/source/selection epoch and
original timestamp; cached heartbeat reads do not refresh it.

Only a visible requesting consumer triggers work; there is no background
Gateway diagnostic poll. Clients stop requesting when hidden and poll no more
than1Hz. Concurrent consumers share a one-second fetch cache; failed reads also
have a one-second retry window. Cache and in-flight responses are source/epoch
bound. Read failures return503; authentication still returns401 before any host
request. Responses are no-store. No transmit action is exposed.

## Targeted evidence and remaining gate

`tests/test_sonar_contract.py` covers explicit scale, reviewed-version gates,
source evidence, generation retirement, maximum metadata, diagnostic limits,
async source admission, no-effect cancellation, UNKNOWN no replay, owner
isolation and HTTP auth/strict bodies. It also checks exact0.4.11 compatibility,
future-version rejection, true/null reference-event time, fresh/latched status,
disconnect/recovery, false zero-count inference and bounded optional rate data.
`tests/test_sonar_burst.py` uses a labeled
synthetic 20Hz mean burst fixture, with lagging/missing analysis and source
changes. It counts host publications, Gateway publications, unconfirmed sources
and discarded older contexts; it is not a physical rate or echo acceptance.
Physical Sonard/Gateway/HELM delivery remains a separate pending release gate.

## Reviewed 0.4.13 published evidence and display contrast

The reviewed wheel is `no5_sonard_openecho-0.4.13-py3-none-any.whl`, SHA256
`dcd462f74ea7c0414545585ab2bea75c29076a238857e738a266177803932662`.
Its `GET /api/v1/sonar/published-pings` records binary-publication facts before
the socket publish, independently of optional detection HTTP observations.
One worker requests at most64 records per100ms cycle; a512-record cache is
bound to the exact host session and USB segment. Requests and responses are
bounded (65536 bytes), and a context change resets the cursor. A mixed-segment
page is invalid. A truncated host ring is counted, never filled with guesses.

Actual origin requires exact ping ID, UTC nanoseconds, host/segment identity,
source-selection generation and the binary TX-reference bit. Only
`physical_own_marker_raw_match` proves own and
`qualified_native_marker_raw_match` proves native. A selected source, or even
an exactly matching optional analysis, does not replace missing publication
evidence. Known old contexts are dropped before ping-order checks.

`displaySelection.generation` remains the host display epoch;
`selectionGeneration` is the separate source-only counter. Noise-policy changes
advance the display epoch. Each confirmed frame adds
`amplitude.displayTransform`, `noiseRevision`, and `noiseActive` together. The
full scale remains4095 ADC codes. Reduced profiles are **ADC contrast after
background subtraction**, not raw or calibrated acoustic power. The unchanged
raw archive is distinct from this display transformation. Missing publication
facts yield explicit `UNCONFIRMED` scale/context, never a guessed original profile.
Noise details are read-only bounded diagnostics; no new control endpoint exists.

A64-entry cache retains only bounded, exact-header analysis projections.
Publication and optional analysis share a250ms join deadline. Exact analysis
can be used immediately; a newer analysis can release an older frame only
after publication is confirmed and its host/display/selection context agrees.
Unobserved or late analysis leaves a gap in canonical bottom information.
Analysis HTTP does not run without pending frames. Confirmed host/segment,
source-selection, display or noise-policy changes separate the Gateway epoch;
state is published before its first binary frame. Missing publication evidence
never replaces a previously confirmed policy or invents an epoch. Effective
per-ping noise reduction remains an explicit amplitude interpretation context:
clients must separate those image segments without erasing the full history. The
4096-byte frame-metadata limit is unchanged, with optional tracks reduced first.

`tests/test_sonar_published.py` checks bounded parsing/cache/cursors, segment
restart with smaller IDs, exact identity failures, raw preservation, unavailable
analysis,0/25/75/125ms analysis delays, delayed publication evidence, distinct
policy epochs, missing-evidence recovery without invented epochs, per-ping noise
contexts, and state-before-frame ordering. Synthetic tests do not replace
the coordinated live Gateway/HELM acceptance.

## Reviewed 0.4.14 display window (read-only)

This additive reader is bound to wheel SHA256
`77cd48bb9094eb7257e638c2d574594dbad4b5a78087cbbafc0cbad96a237179`
and synthetic interface fixture SHA256
`89114aac6059689254eb003176ff213442149a6a4e9c7a8cab4f80627ad9f4dc`.
Exact0.4.14 joins the reviewed version lists;0.4.15 and unknown future packages
remain unconfirmed. This is no physical host/firmware or interval-pattern
qualification. The changed host `DepthRateController` is outside this reader's
scope; no new TX, noise-policy or stripe-policy command is exposed.

The existing bounded publication worker/cache now requires each0.4.14 record
to contain `display_window` (`no5.display-window/1`). Integer sample boundaries
must remain within the original `sample_count`; `capture_sample_count` must
still identify the entire unmodified ADC capture. A shorter window is accepted
only for proven own/TX-referenced input. Its declared positive binary32 range
must match the actual40-byte binary header **exactly**, with the same ping,
UTC nanoseconds, host/segment, source-selection generation and TX bit. JSON's
full-capture range is binary64, so its binary32 rounding is used at that bound;
no arbitrary header-match tolerance is introduced.

Unverified input instead declares start0, the entire sample window, null
capture/display ranges and `unverified_time_reference`; its actual header range
must be NaN. No meter axis or canonical bottom is invented. Missing or malformed
window evidence cannot confirm a0.4.14 frame's origin or normalization. The
otherwise valid raw display frame can still follow the existing unconfirmed
path, without creating a physical/policy epoch.

The header remains HELM's authoritative display geometry. The V1 binary and
4096-byte metadata bounds are unchanged. Physical bottom/track depths are
projected into that actual display range. A15m track outside a3.072m display is
omitted from the overlay and counted, never clamped to its edge; bounded
diagnostics retain its physical depth. Automatic window changes do not invent a
source/policy generation. Out-of-window data remain in the original recording;
zoom-out alone cannot recover samples omitted by the host display window.

Read-only diagnostics additionally expose observed display-window boundaries,
ranges, TX verification, reason and original-sample claims, plus observed stripe
policy/algorithm details within the existing128-field/32768-byte budget. This
adds no polling loop or write route. The full three-frame fixture roundtrip,
nonzero-pretrigger UNVERIFIED countercase, source/header mismatches, legacy0.4.13,
metadata bounds, physical offscreen tracks and dynamic-window epoch stability
are covered by `tests/test_sonar_display_window.py`.

## Reviewed 0.4.15 transport-status reader

The exact0.4.15 reader is bound to wheel SHA256
`1f3abbe481d4803d7bdef7c57adbe3f4de220a7e09521c2a6847e0208aaf39f9`,
synthetic host fixture SHA256
`5cf612538f8e760016586e14d3d371facb4e4c97dfc0e75bb870f255759fd4a1`
and closure SHA256
`799d2c1a61eaeeaa76b53cf95faade2ef12dc7ffbdf0a365b1e24d8b08ed06e2`.
The actual wheel diff changes only the package version, `wire.py` transport
liveness and `service.py` status projection. Detection, display window, noise,
TX and depth-rate policy sources are byte-identical to the reviewed0.4.14.
All three complete2088-byte M1 frames and their windows are identical; fixture
runs naturally have different generated session/segment UUIDs and measured
publication rates. Those observations are not erased or relabeled.

The existing five exact version sets now include0.4.15;0.4.16 remains unknown.
The required window, exact binary32 header match, host/segment/source epochs,
UNVERIFIED geometry, bounded rings and binary metadata are unchanged. No new
write, scope, polling loop, transport implementation or physical TX behavior is
added by the Gateway reader. This source review does not itself qualify actual
USB timing or hardware recovery.

Optional bounded diagnostics read only observed
`pico.transport_liveness.{state,last_byte_age_s,last_packet_age_s,read_poll_interval_s,packet_silence_limit_s,poll_read_bytes,raw_samples_modified}`
and `rates.{last_raw_arrival_age_s,last_observed_ping_hz}`. Missing optional
fields remain absent. These are transport/host-arrival observations, never an
acoustic timestamp or proof of a new ping. The host's remembered rate is kept
separate from its current rate; fresh status cannot refresh old ping evidence.

## Publication polling and per-frame interpretation have separate deadlines

One publication worker uses fixed100ms monotonic start deadlines. Request time
consumes that cycle; missed periods are skipped without a catch-up backlog.
There is exactly one publication read in flight, never a new request per raw
frame. The page64/cache512 limits and all source/session/window/header guards
remain strict.

A publication read has its own fixed1.0s upper bound, additionally subject to
any stricter configured session timeout. It does not inherit a possible60s
session timeout. The independent per-frame interpretation deadline remains
250ms: missing evidence still emits UNKNOWN, and a late response never mutates
or republishes a frame already emitted. Raw-frame reception, status work and
frame deadline processing continue while this read awaits I/O.

The preceding100ms read-abort bound was not the frame deadline. In a passive45s
Pi trace,19/722 frames were UNKNOWN. All19 had exact original host rows, but
all first matching HTTP responses had already been closed by the client near
100ms. Nine bodies arrived before their frame's250ms deadline; two of these
belonged to a306.100ms request that began before those particular raw frames.
A250ms request timeout would still discard those two usable observations.
Ten bodies arrived genuinely late and must remain UNKNOWN. Kernel/body arrival
is a bound on application availability, not a measured cache-insertion time.
This correction does not claim to solve the10 late-body cases or identify
whether host execution or SSH transport caused each latency burst.

The public relative timing record is
`verification/sonar/pi-f390-loopback-http-timeout-rca45s-20261016.json`.
Tests replay all19 timing pairs through the actual fetch/parser and frame join:
9 timely rows become usable,10 late rows remain UNKNOWN without replay.
Other cases cover a hanging read limited to1s, stricter session timeout,
independent status/frame progress, cancellation, old-segment cursor protection,
and one in-flight read without bursts. The fixed-cadence and20s synthetic
fast/120ms-status cases remain separate from real-device evidence.

## Exact0.4.16 atomic read transport

The reviewed0.4.16 source candidate adds `/ws/v1/echogram-context`. Only this
exact version selects it;0.4.13–0.4.15 retain the legacy M1/HTTP publication
path. The new16-byte N5EC prefix binds at most4096 UTF-8 JSON bytes and the
unchanged original M1 bytes in one WebSocket message (maximum6200; aiohttp
transport cap6201). The strict codec rejects duplicate/unknown fields,
nonfinite JSON, padding, wrong lengths, noncanonical decimal/UUID values,
TX/source/window/header disagreement and a cropped native/unknown-source
window. Shared Published-record geometry validation remains authoritative;
only the new documented1–128-byte free-text window reason differs from legacy
HTTP's fixed reason codes.

The host wire's wider integer/bin domain is not an Android capability change.
Android V1 still requires1024 bins, signed-Long IDs/sequences, positive UTC
whose millisecond projection is positive, and the existing bounded sampling
interval. Unsupported host boundary cases are explicitly rejected without
wrapping, rounding or substituting a source.

There are no Published-HTTP requests on the atomic path. The immutable row
travels on its frame; optional exact-ping analysis retains its independent
250ms bounded join. The frame callback cannot publish a queued old epoch after
an observed/confirmed newer one. A separate serial status worker leaves binary
reception/disconnect detection responsive to slow HTTP. Early refresh requests
are limited to one per100ms plus request time. A request-start epoch snapshot
prevents delayed old status from replacing a newer envelope/confirmation floor;
a fresh status can confirm a new segment before its first ping and immediately
retire the prior context. Worker failure propagates through the existing
unavailable/reconnect path, and all old tasks are cancelled and joined before
a new connection starts.

The optional Android `publication` object retains `version`/`sourceEvidence`
and adds `sequence` (canonical decimal string) plus `sequenceGap` (number of
omitted publication sequences). Physical ping-ID gaps do not imply missing
selected publications. Counters reset on a new segment/connection; no missing
measurements are synthesized. Up to64 retired contexts are retained only for
in-process rejection, without identity logs. The4096-byte Android metadata
budget and existing command rings remain unchanged.

Source proof binds wheel590af733… and corrected fixtureb9ff41f7…; formal
activation, packaging and physical acceptance remain separate steps. The
first three embedded M1 frames and complete Published rows remain byte-for-
byte/equal to the retained0.4.15 references. New tests include exact6200-byte
messages, malformed envelopes, Android-only subset rejection, sequence/epoch
changes, orphan-free shutdown, stale analysis, and an actual aiohttp test with
400ms HTTP delays: atomic sources stay complete without Published GETs while
the legacy negative control correctly retains UNKNOWN for late evidence.

# No5 confirmed-volume notification

Upstream: librespot-connect 0.8.0, crates.io distribution; upstream source is retained under the MIT license (LICENSE). This path override is scoped to the No5 receiver.

Changes in src/spirc.rs:
- Add one coalesced RefreshMixerVolume notification. It carries no value and reads Mixer.volume() when processed. It never calls Mixer.set_volume() or writes a previous volume.
- Publish the confirmed mixer value through the existing Connect volume notification. Remote SetVolume commands keep their separate command path.
- Use a fixed 500 ms batching deadline so continuous activity cannot postpone volume publication forever.
- Regression tests cover delayed observations, one pending notification, and failed delivery.

Do not replace this notification with Spirc.set_volume(): that method invokes Mixer.set_volume() asynchronously, and delayed self-feedback becomes an incoming volume command.

Read-only media extension: publish at most 10 previous / 80 next observed queue entries through a shared snapshot. A one-second passive actor heartbeat keeps paused observations fresh, without issuing commands. Snapshot clears on actor shutdown. This is an observed queue, never a complete playlist.

Confirmed navigation extension (2026-10-09): two actor commands validate an opaque queue version immediately before selection and return individual oneshot receipts. Queue ordinals preserve duplicate UID identity; pre-resolved collection contexts use an exact index without the regular load fallback. Existing Main notification, queue and repeat/shuffle behavior remain. `ResolveContext::append_context` is crate-visible solely to preserve actual continuation pointers from a prepared context. No account or volume values are captured in this vendor source. New regressions cover duplicate/hidden/unavailable ordinals and a lost receipt without replay. Upstream MIT LICENSE is retained.

Playlist start extension (2026-10-10): the same actor selection command carries an optional explicit shuffle mode. It checks queue identity before mutation, validates the prepared context/first entry, chooses a playable random entry for shuffle, and publishes the actual context, shuffle option and per-operation target for receiver-thread readback. Ordered start disables shuffle and repeat-track. Ordinary song selection keeps its prior options. Neither operation invokes the mixer. Lost receipts remain UNKNOWN without replay.

Bounded AP connection (2026-10-15): `Spirc::new` wraps the entire existing `Session::connect` future in a five-second cooperative attempt deadline, with at most six suspended attempts and a thirty-second cooperative AP wait budget. In core 0.8.0 the TCP connect is evaluated before its inner handshake timeout. Cancelling this outer future drops that socket and continues the same session's already-advanced AP resolver deque; it does not introduce fixed AP addresses or reset the resolver. Returned core errors, including explicit authentication failures, are returned immediately because core already owns their AP failover loop. Session validity and uninitialized username/auth data are checked before each attempt and after cancellation. The final authentication await precedes a synchronous, uninterrupted session commit in the pinned core source; no initialized session is connected twice. Final failure invalidates the session. Seven deterministic timer tests use actual pending futures and drop counters, including successful failover, attempt/overall limits, unchanged authentication errors, and refusal to retry changed session state. No volume, playback, navigation or media operation is retried by this connection wrapper. The core crate remains the unmodified, pinned registry dependency. Test execution and device verification are separate release evidence; the presence of tests here is not a PASS claim.

Budget limitation: core 0.8.0 `socket.rs` calls blocking libc hostname resolution through `ToSocketAddrs` before the asynchronous TCP wait. Tokio deadlines cannot preempt that synchronous call, so wall-clock time can exceed either cooperative AP budget. The preceding `client_token()` and subsequent Login5 `auth_token()` also have no new deadline in this patch. This is not a hard thirty-second overall setup deadline and does not establish an exact DNS/TCP/handshake boundary.

Setup diagnostics: direct stderr emits only fixed `CLIENT_TOKEN`, `AP_CONNECT` and `LOGIN5` BEGIN/READY/FAILED phase constants, monotonic `elapsedMs` and, on failure, the `ErrorKind` enum. Elapsed time is relative to that phase's BEGIN; `AP_TIMEOUT` elapsed time is relative to AP connection entry and carries only a numeric attempt and fixed deadline error class. At most six timeout records are emitted for a production setup. No logger initialization, dependencies or runtime log filter are introduced. Tokens, accounts, session identifiers, hosts, URLs, message bodies and raw error text are never included in these phase records. The markers distinguish a stalled client-token stage, AP connection stage, and post-AP Login5 stage; they do not claim to distinguish blocking DNS from TCP inside the AP stage.

Receiver recovery in the calling `src/main.rs`: a setup error invalidates even a partially authenticated Session and clears published control/browse/playback availability, but preserves the existing Discovery object, listener and port. Consumed credentials are not requeued. The next setup awaits a new explicit Discovery client request, with no autonomous timer/busy retry or media/audio replay. Persisted cached credentials are retained byte-for-byte. A fresh Session is created only for that subsequent client request. Recovery has four targeted source tests; actual executed results belong to the release evidence.

Prepared, not installed: typed FAILED diagnostics additionally report only `cause=HTTP_STATUS|IO|UNCLASSIFIED`, numeric `httpStatus` (0 if absent), deepest typed `ioKind` (`NONE` if absent) and its matching numeric `osError` (0 if absent), a typed `timeout` flag, numeric cause depth and a depth-limit boolean. The walker inspects at most eight causes, including the immediate public Core Error box and custom I/O box, without formatting or parsing any error message. It recognizes only public core `HttpClientError::StatusCode` and standard I/O errors; `Unavailable` alone never implies a TCP/connect failure, because HTTP 500/421/503/451 share that core kind. Timeout is supported only by a typed `DeadlineExceeded` kind or `TimedOut` I/O cause. Unknown and cyclic sources stop at the fixed bound. No hyper dependency, URL/header/body inspection or transport guess is introduced. Eight targeted offline tests cover pinned-core HTTP conversions, portable timeout/refused I/O, matching raw errno/deepest I/O, nested boxes, sensitive errors whose formatting panics, the eighth-cause boundary, cyclic sources, and preservation of the original observed error. Runtime/pins remain unchanged until a separately coordinated build and installation; source tests here are not an execution claim.

Prepared, not installed (post-Login5 observation): direct stderr now also brackets the existing Dealer start and Connect-state HTTP publication with fixed DEALER/CONNECT_PUBLISH phase names and the same bounded, typed failure fields. CONNECT_REGISTERED is emitted only after the returned Cluster protobuf parses and the existing connect_established flag is set. A malformed Cluster emits only fixed CONNECT_STATE_PARSE_FAILED and its elapsed duration, with no body or parse-error formatting. Existing transport, retry, authentication, publication, parsing failure, and audio semantics are unchanged. LOGIN5_READY alone is not Connect registration or playback acceptance. The sensitive-error preservation regression now exercises both post-login stage wrappers too. No logger initialization or raw WebSocket URL, bearer, connection/session identifier, account or body output is added. Source inspection also confirms that the caller already rebinds Player to a replacement Session; no missing Player.set_session fix is justified. Runtime deployment and real Dealer/registration/playback evidence remain pending.

Prepared WebSocket HTTP classification: the Connect diagnostic crate directly names the already-locked tungstenite0.28.0 public error type (exact version, handshake only, default features disabled). Only Error::Http.response.status() is inspected; response headers, bodies, URLs, accounts and tokens are not formatted. Tests cover401/403/429/503 directly and through a source-bearing wrapper, plus a distinct WebSocket I/O timeout. No dependency versions change.

Pinned core diagnostic prerequisite: registry core0.8.0 DealerManager wraps launch errors in private DealerError::LaunchFailure without a std::error::Error source, hiding the underlying cause from any safe caller. The accompanying librespot-core-0.8.0-dealer-error-source.patch adds only #[source] to that field; it does not change the request, token, TLS, retry or Dealer operation. Apply only to an explicitly validated isolated build input whose src/dealer/manager.rs SHA256 is b4d5abaace7e379799dd271f83ed5b5b60e46c29ef95e666ddfffb989be8b82f; expected patched SHA25699e500db4ee1c160f943f7ba00778981a031a862fd7569a7ab83e4d87eee63d7. Preserve the public patch and both hashes in any candidate build receipt; CAS-restore temporary cache source afterward. Production source/runtime restore recipes remain pinned to the unchanged installed receiver until separately updated and accepted. Do not mutate the user's shared global Cargo registry or infer that this error-chain correction itself repairs Connect.

Prepared initial-connection retries (2026-10-15, not installed): one shared
`src/no5_setup_retry.rs` implements at most five attempts per ClientToken,
Login5 and initial Dealer-establishment stage, with 1/2/4/8-second backoff.
Only typed transient I/O, deadlines and HTTP 408/421/425/500/502/503/504 qualify.
Authentication/permission denials (also nested), unknown causes, certificate
failures and other HTTP statuses stop. HTTP 429 is not retried by this wrapper;
core retains its own Retry-After handling. Session invalidation cancels a wait
or in-flight future, observed at a 100-ms polling interval. No session-wide
restart, command, mixer, playback, navigation or Connect-state publication is
replayed. The existing separately bounded AP path is unchanged.

Core's Builder::launch macro evaluates its block before tokio::spawn: its first
URL lookup and WebSocket connect are awaited by DealerManager.start. An initial
error currently returns to the caller; the reconnect loop only starts after
successful initial establishment. DEALER_READY therefore does imply successful
initial transport establishment, but not Connect registration or playback.
The separate `librespot-core-0.8.0-initial-retry.patch`, applied after the source
diagnostic prerequisite, retries this initial establishment on the same builder
and handlers, reacquires the URL each time, and returns the final typed failure.
Only the socket/TLS/WebSocket connect future has a new cooperative 15-second
attempt timeout. Token HTTP operations keep their existing waits/challenges;
blocking libc DNS cannot be preempted. Five attempts are not a total wall-clock
deadline or a maximum count of underlying HTTP/challenge requests.

`tools/prepare_core_initial_retry.py --core-source <pinned-pristine-core>` checks
the public patch/manifest against exact core input hashes and the canonical
shared helper; `--write` regenerates only those public artifacts. It never
changes a registry. A Linux build must apply both core patches solely inside
the owned existing build cache, verify all input/output hashes from the adjacent
JSON manifest, and CAS-restore original files/remove only its added helper files
afterward. It must not alter the shared global registry or install a receiver.
Actual executed tests/builds and unverified live behavior are recorded separately
in no5-rebuild/inputs/receiver-initial-retry-prepared-20261015.json.

Live follow-up (candidate only): a real Login5 TCP timeout at 129526 ms was
followed by successful retry attempt 2 at 130845 ms; no HTTP rejection was
observed. The first candidate still did not confirm Dealer/Connect registration.
Repeated Discovery requests can replace the pending actor. The caller now
coalesces only byte-identical complete Credentials while its Session remains
valid; changed credentials, stream closure and invalid sessions retain their
existing replacement/recovery path. Fixed COALESCED/REPLACING/TASK_ENDED markers
contain no credential identity or fingerprint. Three regressions cover the
pending-actor race, changed credential data and invalid-session recovery.

The candidate core patch also bounds native-TLS HTTP TCP establishment to ten
seconds through HttpConnector, keeping the existing TLS verification, HTTP
response/challenge and Retry-After behavior. It does not retry HTTP operations
itself. This is not a DNS, TLS, response-body or overall setup deadline. Both dev
and release core artifacts must be explicitly invalidated in the owned cache
before compilation and removed after restoring sources; Cargo treats registry
sources as immutable. A release without actual core recompilation is excluded.

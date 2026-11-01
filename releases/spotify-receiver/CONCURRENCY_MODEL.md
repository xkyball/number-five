# No5 AUDIO concurrency decision — 2026-10-09

Research completed before the concurrency implementation. The receiver and gateway correction is now active in the lab; the actual validation and remaining runtime gaps are recorded in [validation.json](evidence/concurrency-20261009/validation.json). The source contract below does not extend guarantees to independent clients writing directly to CamillaDSP.

## Findings from primary sources and current source

CamillaDSP 4.1.3 starts a separate handler thread for each WebSocket client. SetVolume replaces the shared target. AdjustVolume reads the old target, adds a delta, and stores a new target in separate calls. There is no documented revision/CAS parameter. Therefore two direct clients have neither an application-level conflict contract nor a proven atomic multi-client increment. No5 must not advertise those guarantees for independent direct Camilla writers.

Tokio recommends a dedicated task owning a network client, with messages and replies to its callers, and bounded queues/backpressure. watch retains only the latest value: appropriate for observations, not for independently acknowledged commands from different users or different operations (e.g. mute plus volume).

RFC 9110 If-Match specifies checking a strong revision before applying a mutation to avoid lost updates. We apply that principle to the existing WSS command envelope and to the final local Main authority; WSS JSON revisions are our application protocol, not literal HTTP If-Match headers.

librespot 0.8.0 Spirc.set_volume queues a command which eventually invokes Mixer.set_volume. It is therefore unsuitable for sending confirmed mixer feedback: delayed own feedback can become a new input command. Incoming Connect volume requests have an absolute value, not a No5 revision or a reliable relative-step intent.

The existing No5 Main path already routes gateway commands through the receiver bridge. However, its single watch slot can silently replace another producer/operation, the socket confirms only admission, and gateway readback can match a value written by a different producer. Equality with the latest value does not identify a delayed self-echo.

## Contract chosen for No5

1. One actor owns the existing Main WebSocket and admits a bounded FIFO of commands. Phone and gateway use the same actor. Accepted operations from separate clients are not silently coalesced. Continuous fader previews may be coalesced only before admission, within one client and one gesture.
2. Main has an authority session and monotonically increasing revision. An absolute gateway write carries the expected authority version; it is checked immediately before the write in the owner. A stale write is rejected without DSP side effects and reports current state. A new session invalidates old requests.
3. Relative steps are calculated from current confirmed state inside that same actor, in admission order, with the reviewed bounds. They are not client-side read-plus-absolute-set operations. Spotify absolute requests keep their actual absolute semantics and are ordered on receipt; No5 cannot infer a phone user's gesture time or transform an absolute value into a delta.
4. Each command receives its own readback result. Queue admission is not successful execution. Lost post-write readback is UNKNOWN, is never replayed automatically, and cannot become success because another client happens to reach the same value.
5. Feedback publishes observed actual state and its revision. It cannot enter a command path. Connect notifications coalesce without carrying historical values; the Connect actor reads the latest confirmed mixer value when handling the notification.
6. Old/expired, disconnected or superseded previews are discarded; reconnect observes current Main before admitting writes. No polling loop restores an old desired value. No client has permanent priority. Native queue limits and TTL are explicit implementation limits to test, not latency guarantees.

## Expected scenarios

- Starting at -30 dB, relative +1 then -1 becomes -29 then -30, if neither reviewed boundary clamps the steps. Both receive individual confirmations.
- Two absolute setters based on revision 7: the first succeeds and advances revision; the second is rejected as stale rather than overwriting the first.
- A fresh phone absolute request followed by a fresh gateway command is serialized. A gateway command prepared before that phone change is rejected.
- Volume then mute from different clients cannot replace each other in a shared latest-value slot.
- A delayed Connect observation after two real changes reads the latest value and never writes either prior value to DSP.
- An independent direct Camilla writer bypasses the actor. Its observed value is adopted and invalidates stale No5 state; full serializable/CAS guarantees cannot be claimed while such a writer remains independent. To cover such writers, they must participate in the same coordinator or Camilla itself must implement transactional revision-aware writes. No access or firewall change is part of this source correction.

## Validation required

Two-producer absolute conflict, ordered up/down steps, volume/mute overlap, individual receipts, queue-full and expiry rejection, epoch changes, lost readback/no replay, delayed notifications, and stable real-client Spotify/Main observation. Existing single-client fader tests do not establish multi-client correctness.

## Primary sources

- https://github.com/HEnquist/camilladsp/blob/v4.1.3/src/socketserver.rs (per-client threads, SetVolume, AdjustVolume)
- https://github.com/HEnquist/camilladsp/blob/v4.1.3/websocket.md (actual command contract)
- https://tokio.rs/tokio/tutorial/channels (exclusive resource owner, replies, bounded queues)
- https://docs.rs/tokio/latest/tokio/sync/watch/index.html (latest-value observations)
- https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1 (strong preconditions, lost updates)
- https://github.com/librespot-org/librespot/blob/v0.8.0/connect/src/spirc.rs (command versus feedback path)

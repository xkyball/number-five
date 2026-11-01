# Shared UI state V1

`com.no5.ui.No5UiClient` binds explicitly to Control's independent
`com.no5.lumina.control.UiStateService`. The descriptor is
`com.no5.lumina.control.IUiStateV1`, version 1. This is UI state only: it cannot
issue gateway sessions, transport credentials or accept knob commands.

The public client exposes `start()`, `stop()`, `close()`, `getSnapshot()`,
`setLanguage("de" | "en" | "af")`, and add/remove `Observer`. Observer callbacks
run on main. `No5UiClient.Snapshot` contains `language`, `revision`, `connected`
and `media`; media has bounded title/artist, playback, opaque source generation,
original observation epoch and its corresponding boot-monotonic time. Consumers
use `snapshot.connected && snapshot.media.isFresh(SystemClock.elapsedRealtime())`.

Control durably confirms the common language before publishing it. Clients cache
only confirmed selections; `UiLanguage.current(Context)` initializes the cache,
and `UiLanguage.current()` is a memory-only renderer lookup. Neither changes the
Android system locale nor recreates an Activity. Rendering helpers and dictionary
are maintained alongside these sources by the localization owner.

The signature permission, actual UID, exactly one package for that UID, Android
user and approved single-signer identity are verified before request payloads.
The production signer pin also accepts its Android-verified signing lineage.
Debug peers require both applications to be debuggable and signature-matched.
Callback UID and service identity are verified before callback payloads. Messages
are at most 16 KiB, with 16 live callback slots and four per UID. Binder death
retires subscriptions; client notifications retain only the latest pending state.
An independent local deadline notifies observers when the original observation
expires, including a short title with no animation and a silent producer. This
notification uses a new snapshot object, preserving producer revision and times.
The deadline runs on main independently of the UI-IPC worker. It compares the
original revision, source generation and observation before delivery, always
re-reads the latest canonical snapshot, and never writes canonical source state
from the timer. A stalled Binder call therefore cannot keep media visually fresh.

## Existing audio owner hook

The existing Control audio transport calls
`UiStateBroker.get(context).acceptAudioSnapshot(acceptedWholeAudioJSONObject)`
after accepting a validated full audio state, and `audioUnavailable()` on source
or transport loss. This hook performs no network request or audio action. It
projects only media and audio-source quality, preserves the upstream observation
time and rejects superseded revisions and retired sources. UI reads and duplicate
audio receipts cannot renew freshness. No artwork URLs, tokens, credentials,
request IDs or source/session identifiers cross the UI binder.

For asynchronous transports, allocate `long ticket = broker.beginAudioSource()`
before starting/replacing a source and capture it in that transport's callbacks.
Call `acceptAudioSnapshot(json, ticket)` and `audioUnavailable(ticket)`. Beginning
a new source immediately retires old tickets and queues withdrawal of previous
metadata. Old tickets cannot
publish packets or mark the replacement source unavailable. The broker allocates
these tickets so a newly created audio-owner object's counter may safely restart;
no JSON field controls the generation. The original overload is for the existing
serial owner after its own response admission, not an alternative callback path.
Audio hooks touch only one bounded pending-observation slot. JSON projection and
UI callbacks run on the existing UI publisher worker, so audio actor/input locks
never wait for language persistence, UI JSON parsing or callback delivery.

The Android library is included once in each independent Gradle build. Standalone
Control javac builds additionally include `no-apps/ui-state/src/main/java/**/*.java`;
the Control transport/build owner maintains that source list. The additive
manifest service and permission are in Control's existing manifest.

## Focused evidence

`python3 no-apps/ui-state/tools/test_host.py` compiles all shared Java and both
new Control classes against the real API-29 Android stubs with Java 8 source and
target, then runs thirteen admission, bounds, source-generation and freshness tests.
Its generated receipt is under `ui-state/build/foundation-host/RESULT.json`.
It neither installs nor starts anything. Binder-device, signer/APK and physical
UI acceptance remain separate integration checks; this host result does not
claim them.

`src/robolectricTest/java/com/no5/ui/UiStateWireTest.java` contains nine additional
real-Parcel parser tests for the existing HELM API-29 host runtime. Beside it,
`No5UiClientDeadlineTest` deliberately stalls the IPC worker while advancing only
the main deadline, checking a single stale notification with unchanged producer
identity and time. They are a separate Gradle test source set so the pure host
runner needs no Robolectric.

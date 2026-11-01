# NO5 native domain

Pure Kotlin/JVM. No Android, WebView, JavaScript runtime, network client or coroutines dependency.

## Source boundary used by the app

- `No5SourceSession` groups one `SourceIdentity` and the HELM, AUDIO and SONAR ports. `SourceSessionFactory.create(config, identity)` is the concrete adapter composition boundary. `DefaultSourceSessionFactory` injects one clock into local mocks or selects explicitly unavailable Pi placeholders.
- `SourceCoordinator` loads `SourceConfig`, creates fresh session IDs and applies validated configuration changes. It creates a candidate, saves, retires the old session and exposes the replacement. Creation/save failure keeps the old session; a retirement failure after persistence activates the replacement with a warning. Close is idempotent; the standard aggregate attempts all three ports even when one fails. Final disposal errors become `lifecycleWarning`.
- `SourceFeed<T>` exposes a current value plus immediately initialized subscriptions. `MutableSourceFeed<T>` serializes emissions, queues reentrant changes and makes close/unsubscribe prevent later delivery. It does not choose an Android dispatcher.
- `HelmSource` exposes read-only `HelmState` snapshots and a freshness policy. Optional `HelmDemoControls` owns simulator updates. `MockHelmSource` wraps the fixture repository; `UnavailablePiHelmSource` cannot publish demo telemetry or expose demo controls. The app reevaluates freshness every second, including when no new snapshot arrives.
- `AudioSourcePort` exposes state, outcomes, freshness, availability and typed submit/tick/close. `MockAudioSource` owns clock-driven local playback and command completion; optional `AudioDemoControls` is separate. Outcomes bind PENDING/VERIFIED/FAILED/DROPPED/REJECTED to source session and request. `UnavailableAudioSource` rejects commands.
- `SonarSourcePort` exposes status, capabilities and frames with identity/generation plus typed start/stop requests/outcomes. Acquisition status precedes frames. `DeterministicMockSonarSource` owns samples and timestamps; `SonarDemoControls` only adds optional fixtures. `UnavailablePiSonarSource` remains disconnected with no capabilities/frames. An ACK is adapter admission, not evidence of physical acquisition.
- `SourceSettingsStore` persists only app source mode and endpoint. `SourceConfigValidator` normalizes HTTP(S) hosts/URLs and rejects credentials, invalid ports, query/fragment and unsafe characters, without DNS/network access. Android persistence and native draft/apply/cancel behavior live in `app/`; no server setting is written.

See [SOURCE_INTERFACES.md](../docs/SOURCE_INTERFACES.md) for lifecycle, threading, deterministic injection and the exact future-adapter factory point, and [SOURCE_SETTINGS.md](../docs/SOURCE_SETTINGS.md) for persistence failure and corrupt-record behavior. PI selection never establishes connectivity; HELM/AUDIO/SONAR health and capabilities remain independent.

## Domain models behind the sources

- `DemoHelmRepository.state`, `update(HelmInput)`, `tick(seconds)`, `reset()` drive explicit local simulation. `tick` respects `SetRunning`. `SetTelemetryAge` simulates quality transitions. The vessel clock starts from the source fixture (14:23), not the host clock. All demo values remain labeled by `quality.demo`.
- `AudioModel.initial()` is unavailable/locked. `fixture(AudioFixture.DEMO)` enables a local simulation only. `startWrite` creates a single pending transaction. `finishWrite` requires that exact request ID; `expireWrite` rolls it back. `offline` discards affected pending writes without replay. `meters` validates exactly five outputs; labels require verified routing. Only CamillaDSP Master can be the volume authority.
- `SonarModel` owns a 2048-ping ring, each with exactly 1024 unsigned 16-bit amplitudes. JVM `ShortArray` stores the bits; `sample()` returns 0..65535. The binary factory accepts exactly 2048 little-endian bytes. Acquisition, display gain and freeze are independent. Freeze copies the displayed ring; live acquisition and latest A-Scope continue. Timing calibration, detector capability, confidence and freshness all gate physical depth.
- `QualityEvaluator` evaluates shared freshness at >5 seconds stale and >30 seconds lost. The default audio policy evaluates source, DSP and meters independently, becoming stale at >2.5 seconds and lost at >30 seconds. Explicitly marked demo quality is preserved as simulation. Source/timestamp absence is invalid for live telemetry. Valid zero is retained; invalid/lost/unavailable numeric values are hidden by `TelemetryValue.displayValue`.

Demo HELM RPM range is 0..7000, matching the selected instrument scale. This is not a claim about the installed engine's operating limits. Shared protocol validation retains its broader 0..15000 envelope until a real adapter supplies vessel-specific bounds. The remaining demo ranges are simulation guards, not certified vessel configuration.

## Gateway boundary: draft only

`GatewayProtocol` version 1 uses the distinct namespace `no5-native-gateway-draft`. This is a typed integration draft, not a claim of wire compatibility with the HTML `no5-screen` bridge. No serializer, authentication implementation, socket endpoint, discovery process or backend client is supplied.

`LiveGateway` is the unimplemented transport boundary. A real implementation must authenticate the adapter and validate sender/session identity before handing envelopes to `GatewaySession`. This local tracker requires a capability report, source, protocol version, session ID, request ID, timestamps and explicit units. `prepare` produces an envelope for an external adapter; it does not send anything. There is no queue or automatic retry/replay. Capability withdrawal, disconnect and session replacement discard affected pending writes. Acknowledgement `ACCEPTED` is receipt only; `VERIFIED` must represent backend read-back. A timeout or mismatched/late acknowledgment never verifies a newer command.

Demo fader limits must never be copied into a live configuration without real vessel validation. Live fader writes require negotiated per-channel bounds/steps, DSP/output availability, command capability and the single CamillaDSP Master volume authority. Live preset writes require the exact gateway-validated preset. No propulsion, helm, transmit-power or hardware sonar control command exists in this contract.

`GatewayHelmTelemetry` rejects unit mismatches and demo values. A real adapter still needs to validate connection ownership, payload size, schema, monotonicity, source health and real sensor ranges before publication. High-rate sonar stays binary and separate from control payloads. Rendering a frozen history must not be described as live history restoration after process death.

## Tests

JUnit 4 tests cover fixture outcomes from `no5_screens/audio/model.test.cjs`, `sonar/model.test.cjs` and shared quality rules. Sonar checks include exact original JavaScript fixture amplitudes and calibrated depth. Source-feed/session, HELM/audio/sonar port and endpoint/settings tests add deterministic clock, close, stale-reply, capability and persistence-boundary coverage. Test files describe intended checks; executed results are recorded exclusively in [verification/STATUS.md](../verification/STATUS.md). They do not certify live hardware, adapter security or audio output.

Run from the project root: `./gradlew :domain:test`.

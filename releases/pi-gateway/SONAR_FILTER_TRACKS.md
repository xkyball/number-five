# Sonard filter commands and confirmed tracks

The existing Sonard 0.3.5/0.3.6 API exposes filter mode only. No TX command or
acquisition start/stop is introduced. The HELM role uses its existing
`sonar.read` and `sonar.command` scopes; the app API remains No5 Gateway V1.

- `GET /v1/sonar/filter`: sonar.read, 200 with version1/kind sonar-filter,
  serverSessionId, generation, mode, effectiveMode and active. An unavailable
  or unsupported source returns503.
- `PUT /v1/sonar/filter`: sonar.command, maximum4096 bytes. Exactly version1,
  serverSessionId, generation, clientId, positive requestId and mode
  (`off`, `auto`, `on`). Returns202 and a numeric commandId immediately.
- `GET /v1/sonar/commands/{id}`: sonar.read and the same authenticated owner;
  200 with the retained command result, or410 when unavailable. It never
  retries a write. Cross-owner reads cannot reveal another app's result.

A 64-slot pending ring replaces unstarted absolute mode goals from the same
producer. The last256 terminal results remain readable; a non-evicting bounded
producer watermark prevents replay after result eviction. ACCEPTED/RUNNING
are admission/execution states; APPLIED requires the host200 policy mode to
match the requested mode. Lost replies and source changes after a started
write become UNKNOWN/COMPLETION_UNKNOWN and are never sent again. Authorization
and exact source generation are checked before execution. The host receives
only `PUT /api/v1/detection/filter` with `{mode: ...}`.

Enhanced ping Presentation includes at most eight confirmed tracks. Each
record contains trackId, bin, confidence, classification, classificationSource
and detector. A genuine time reference and finite stream range are required;
physical depth is converted using the actual resampled stream geometry.
`trackTotal` and `trackOmitted` explicitly describe the bounded selection.
Tracks are sorted by displayed range and ID; no pauschal0.75 threshold is added
for confirmed rule tracks. A class is heuristic rules_v1 evidence, not a
calibrated fish/material identification. The existing4096-byte ping metadata
limit and unchanged raw samples remain binding.

Source/time/ping matching occurs before projection. Unverified timing emits
no physical track positions. HELM's overlay starts disabled and freezes the
track evidence with its own displayed ping; live acquisition continues.

Focused checks: tests/test_sonar_commands.py and test_sonar_presentation.py,
with existing test_audio_commands.py to preserve async AUDIO behavior.
Deployment uses the existing hash-pinned rebuild packet and scoped
activate_audio_rings.py recipe. No registry, TLS, route, private audio setting
or unrelated unit is replaced.

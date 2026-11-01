# No5 Helm v1

The canonical wire schema is `helm-v1.schema.json`. Both simulation and a conforming real backend expose this contract. The app has no simulation-specific path.

## Snapshot

`GET /no5/helm/v1/snapshot` returns `{schemaVersion, serverId, revision, generatedAt, state}`. `state` contains ownship, AIS targets, routes, waypoints and `activeRouteId`. Revisions are nonnegative integers. `serverId` identifies the backend epoch and changes when a restart invalidates revision continuity. Time is RFC3339 UTC.

Geographic positions use decimal WGS84 degrees. Heading and COG use radians; SOG uses metres per second; depth uses metres. The UI converts SOG to knots and heading to degrees. IDs are stable within the backend state; routes contain ordered references to waypoint IDs. These are read-only Android projections.

## Stream and replay

`WS /no5/helm/v1/stream?afterRevision=<snapshot revision>&serverId=<snapshot epoch>` starts with `hello`. The backend replays every contiguous delta after that revision before sending new deltas. Snapshot acquisition, replay selection and subscription registration must be atomic against the backend's revision producer, preventing the HTTP/WS opening race.

A delta includes `type=delta`, `schemaVersion=1`, `serverId`, `baseRevision`, `revision`, `generatedAt` and optional replacement fields `ownship`, `ais`, `routes`, `waypoints`. Arrays replace their entire projection; an empty array clears it. Omitted fields remain unchanged. Each accepted delta must have `baseRevision == localRevision` and `revision == baseRevision + 1`. A stale duplicate from the same epoch is ignored.

On a gap or epoch change, stop applying deltas, enter SYNCING, fetch a fresh snapshot and atomically replace the projection. Reopen the stream from the new revision. If replay history is unavailable, the backend sends `resyncRequired`. There is no partial update on a gap. A snapshot from the same epoch may not roll the revision backward.

Heartbeat events contain `type=heartbeat`, `schemaVersion`, `serverId`, `revision`. They must arrive within six seconds during an idle stream. A stream failure, timeout or unavailable snapshot enters DEGRADED and preserves the last known projection. Reconnect retries use capped exponential delay. Explicit close enters DISCONNECTED.

States: DISCONNECTED → CONNECTING → SYNCING → LIVE. Gaps trigger SYNCING. Backend loss triggers DEGRADED, followed by a new synchronization attempt.

## Simulator controls

The simulator adds an operator endpoint, `POST /operator/v1/scenario`, separate from Helm v1. The Android client never calls it. `simctl.py` provides pause, resume, disconnect with duration, revision gap, server reset and ownship depth/speed changes. It is an operator testing interface, not an alternate product API.

The fixture state contains ownship position, heading, COG/SOG, depth, three AIS targets, one route and three waypoints. The server validates every emitted Helm v1 message against the canonical schema. HTTP/WS tests verify replay, gaps, disconnect/reconnect and epoch replacement.

The delivered contract has not been tested against a running No5 Raspberry Pi backend. A real bridge must map Signal K values/resources into this exact schema and satisfy the same replay/epoch rules.

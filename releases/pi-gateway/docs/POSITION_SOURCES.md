# Optional Signal K position sources in HELM V1

`GET /v1/helm` continues to use the existing `map.read` permission. No new
endpoint, token scope, network client or Signal K write is introduced.

The administrator may explicitly configure `helm.position_pointer` for a
complete Signal K `navigation.position` leaf and `helm.gnss_pointer` for its
GNSS subtree. Both pointers address the **same existing HELM snapshot**. An
empty RFC6901 pointer selects that snapshot's root. No pointer, endpoint or
sensor identity is inferred. Without `position_pointer` the payload is unchanged.

## Additive wire block

An opted-in HELM response adds `positionSources`:

| Field | Contract |
| --- | --- |
| `version` | `1`, independent of the unchanged HELM V1 version |
| `sourceKind`, `simulated` | `REAL`, `false`: provenance of this explicitly configured Signal K input, independent of other simulated telemetry fields |
| `primarySourceId` | Exact primary `$source`, or `null` if unavailable |
| `total` | Number of distinct, syntactically accepted source references, including sources whose position is invalid |
| `omitted` | `total - sources.length` |
| `freshness` | `liveForMs: 5000`, `lostAfterMs: 30000` |
| `sources` | At most 16 candidates; primary first, remaining references in stable lexical order |
| `combination` | Disabled by default; optional result: [POSITION_COMBINATION.md](POSITION_COMBINATION.md) |

Each candidate contains `sourceId`, `sourceRef`, `label`, `status`,
`observedAtEpochMs`, `position` and `gnss`. IDs and labels currently retain
the exact source reference, limited to 200 printable, nonblank characters.
No sentence/PGN suffix is removed and no friendly device label is invented.

`position` is one object with `latitudeDegrees`, `longitudeDegrees` and
optional `altitudeMetres` (`null` when not available). Latitude and longitude
come from one source observation, never independent fields or different
receivers. Invalid, unavailable, future or lost positions are `null`.
Stale positions retain their original coordinates with explicit `STALE` status.

The status enum is `LIVE`, `STALE`, `LOST`, `INVALID`, `UNAVAILABLE`. A live
position needs a complete finite coordinate pair and its original timestamp
within the last five seconds. Future observations are invalid. Observation
times remain original even for diagnosis of an invalid or lost observation.
The existing top-level publication timestamp may belong to a different sensor;
it neither expires nor freshens independently timed candidates.

`gnss` may contain `type`, `methodQuality`, `integrity`, `satellites`,
`horizontalDilution` and `positionDilution`. Each is a separate observation
containing `value`, `sourceRef`, `observedAtEpochMs` and the same status enum.
Only an exactly matching source is attached. Each field keeps its own time.
Unknown/invalid values are not replaced with defaults. DOP is dimensionless,
not an accuracy estimate in metres. A simultaneous fresh `no GPS`, `Error`,
`Manual input`, `Simulator mode` or `Unsafe` indication invalidates the matching
position; an older quality observation is not silently treated as simultaneous.

The primary leaf and `values[the same exact source reference]` are two views of
one source, counted once; the primary leaf is authoritative. Other references
stay distinct. Contradictory inner `$source` attributes invalidate their
observation. Duplicate JSON keys remain rejected by the existing strict reader.
Missing source references are not fabricated from the global adapter name.

The complete extension is bounded to 24,576 bytes using the actual standard
JSON encoding, including Unicode escaping. Extra candidates count as omitted.
The complete HELM payload retains its 65,536-byte limit. Existing `values`,
`fieldQuality`, `units` and envelope semantics stay unchanged; specifically,
new keys are **not** added to the legacy strict `units` object.

## Consumer responsibilities

Selection is MAP-local: automatic or a concrete source. The selected source and
an app-owned selection generation must survive asynchronous camera results;
the outer `serverSessionId` remains the gateway-process boundary. Persisted
selection does not turn a stored fix into fresh data. No global Signal K
priorities are changed.

No per-source heading, COG or SOG is currently supplied by this extension.
Consumers must not attach legacy motion fields to another manually selected
receiver or use zero as an unknown heading. Optional combination is computed by the Gateway under explicit participant and plausibility gates; see [POSITION_COMBINATION.md](POSITION_COMBINATION.md). No common antenna reference or uncertainty estimate is asserted.

## Primary references

- [Signal K v1.7.0 position and multiple-value schema](https://raw.githubusercontent.com/SignalK/specification/v1.7.0/schemas/definitions.json)
- [Signal K v1.7.0 GNSS fields](https://raw.githubusercontent.com/SignalK/specification/v1.7.0/schemas/groups/navigation.json)
- [Multiple sources and their independent timestamps](https://signalk.org/specification/1.7.0/doc/data_model_multiple_values.html)
- [Source references](https://signalk.org/specification/1.7.0/doc/sources.html)
- [Timestamp and missing-data semantics](https://signalk.org/specification/1.7.0/doc/data_model.html)

Signal K permits servers to supply timestamps omitted by a producer. An
original Signal K timestamp alone therefore does not prove an independent
GNSS measurement clock suitable for fusion.

Tests use explicitly synthetic source references and coordinates, not vessel
data. No runtime installation or configuration activation is part of this change.

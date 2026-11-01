# GPS combination in HELM V1

The existing read-only `GET /v1/helm` position catalogue retains version 1 and
its single-source candidates. Explicit additional `[helm]` configuration:

```toml
position_pointer = "/position" # reviewed navigation snapshot, example only
gnss_pointer = "/gnss"
combination_sources = ["synthetic.usb.GP", "synthetic.android.GP"]
combination_verified_fix_clock_sources = ["synthetic.usb.GP", "synthetic.android.GP"]
combination_max_age_ms = 5000
combination_fix_quality_skew_ms = 1000
combination_max_time_skew_ms = 1000
combination_max_separation_metres = 30.0
```

Commission one exact reference per independent physical receiver. An empty list
disables combination; aliases are never inferred as independent receivers.
Other sources stay visible without voting. No priority or Signal K data changes.
Only receivers whose original fix-quality clock has been independently checked
belong in `combination_verified_fix_clock_sources`, a subset of participants.
It defaults to empty (fail closed). Fresh-looking retagged quality is insufficient;
an unverified receiver is excluded with `UNVERIFIED_FIX_CLOCK` while its individual
catalogue entry remains visible.

`positionSources.combination.available` advertises enabled support independently
of fix validity. Enabled results contain `version: 1`,
`method: EQUAL_WEIGHT_SPHERICAL_V1`, `mode: CONSENSUS | SINGLE_SOURCE | NONE`,
`status`, `reason`, nullable horizontal `position`, `observedAtEpochMs`,
`contributors`, `excluded`, `timeSkewMs` and `separationMetres`. Contributors
retain exact IDs/original position times; exclusions carry IDs/reasons. Result
time is the oldest contribution. HTTP/poll time never renews it; altitude is null.

Eligibility: complete LIVE position aged 0..5000 ms, matching LIVE genuine GNSS
fix quality within 1000 ms of position, and no newer unsafe integrity evidence.
Missing/invalid/future/simulated fix evidence is not defaulted. Newer no-fix
quality blocks an older position. Single-source catalogue semantics are retained.
Commissioning verifies the original producer clock against GPS time.
The age and quality association gates can be tightened through configuration;
their hard upper limits remain 5000 ms and 1000 ms respectively. Both position
and quality must meet the configured age. Inter-source skew and distance have
separate configurable gates (defaults 1000 ms and 30 m).

Multiple contributors must agree pairwise within the configured distance/time
limits, inclusively. Their unit-sphere Cartesian vectors are averaged and
normalized, handling the dateline correctly. One contributor passes through
its exact horizontal coordinates with `SINGLE_SOURCE`. Conflicting or temporally
incompatible contributors produce no position. Omitted catalogue members cannot
silently cause a fallback. Bounds remain 16 candidates, 24576 catalogue bytes
and 65536 HELM bytes, including Unicode escaping.

Reasons: `OK`, `SINGLE_SOURCE`, `SOURCE_DISAGREEMENT`, `TIME_MISMATCH`,
`NO_USABLE_SOURCE`, `SOURCE_CATALOG_TRUNCATED`; disabled:
`COMBINATION_NOT_CONFIGURED`. Neither spread nor thresholds are accuracy bounds.
DOP is not converted into metres. Shared GNSS errors, unmeasured antenna offsets
and motion within the allowed time window are not modelled. Initial antennas
are assumed to be only a few metres apart. No accuracy improvement is claimed.

MAP consumes the result without computing fusion. Contributor/mode/status changes
cancel old camera work. Original fix age, transport/server fences and explicit
follow intent remain enforced. Single-source/automatic selection and older
Gateway compatibility remain supported. Older clients ignore the additive result.

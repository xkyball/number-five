# Decoder analysis without chart publication

Two additive commands support controlled follow-up work. Neither command runs
an original decoder, writes a chart, changes country authoring, or constitutes
approval for a physical a97 test. Existing optimizer kernels remain unchanged.

## Consecutive identical fill vertices

```sh
no-chart-converter analyze-fill-duplicates --source OWN_PLAIN_CHART.rx5 \
  --expect-sha256 SOURCE_SHA256 --tile LEVEL X Y --output NEW_ANALYSIS.json
```

Repeat `--tile` for additional exact existing source nodes. This is an explicit
source selection, with no parent fallback or automatic country-wide search.
The source, features and all four source arenas are SHA256-bound in the report.
Only supported closed Root-U32 Kind3 parts with pure `AC(...)` programmes in
both known drawing contexts can be proposed. Consecutive identical XY values
are compared as exact integers. The first and last source vertex ordinals stay
in the proposal. Other geometry, sounding roles, programmes and unsupported
parts are retained. A doubtful part retains its entire owner.

The result includes proposed retained vertex ordinals and element hashes. It
contains no replacement chart or approval. Native containment, degenerate
segment and anchor behavior require independent admission before any future
materializer. Equal fill appearance alone is insufficient for that admission.
The original symbol centroid fallback uses a duplicate-weighted F32 mean;
removing duplicate vertices can change it. Native query identity is unproven.
The CLI explicitly records both limits and `operativeApplyAllowed: false`.
There is no new real-country reduction count or hardware timing measurement in
this package release.

The optional `--anchor-policy preserve_native_polygon0` refines the analysis.
It classifies actual native polygon membership from ordered source elements in
the supported all-closed Root1 subset, including masks and holes. An arbitrary
first index entry is not generally sufficient to identify Polygon0. Open or
unresolved grouping keeps the complete owner unchanged.

For a resolved owner the policy retains Polygon0's exact source input, order
and flags. Later polygon proposals also retain every span that overlaps a known
protected Polygon0 span within that source payload. The report distinguishes
protected inputs, alias exclusions and remaining later-polygon candidates.
The CLI analyzes each selected source payload independently; cross-source alias
review and native null-segment/query equivalence remain open. This mode also
writes no chart. The default `analyze_all` preserves the earlier analysis.

## Pinned decoder profile reports

```sh
no-chart-converter compare-decoder-profiles \
  --baseline BASELINE.json --candidate CANDIDATE.json \
  --expect-baseline-report-sha256 BASELINE_REPORT_SHA256 \
  --expect-candidate-report-sha256 CANDIDATE_REPORT_SHA256 \
  --expect-baseline-chart-sha256 BASELINE_CHART_SHA256 \
  --expect-candidate-chart-sha256 CANDIDATE_CHART_SHA256 \
  --output NEW_COMPARISON.json
```

This compares stored report counters and their explicit driver, library,
request, fresh-state and read-only/no-stub provenance. Chart/library filenames
inside reports are references; this command never opens them. These are
validated declarations, not authentication of an original execution or proof
of semantic retention. Each report must describe the same single target case
and complete fresh geometry/data phases; incompatible reports are refused.
The CLI requires
the same driver by default. It does not expose a driver compatibility override.
The Python API documents a separately bound compatibility receipt for callers
that have independently reviewed such a driver pair.

Counter increases are reported as regressions. GEO, DATA, XY/BBox and XYZ work
remain separate; counter changes do not imply physical loading-time changes.
Invented controls require explicit `--allow-synthetic` and remain identified as
synthetic. All outputs must be fresh and use private file permissions.

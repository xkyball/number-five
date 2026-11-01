# Released selection and resident geometry

P4a separates the persistently released source set from the native resident cache.
`ChartSelection` still names cells from one immutable package. Import quotas remain
unchanged. A resident release can select the package's complete supported catalog;
the `ChartResidentBudget.cells`/bytes limits only geometry admitted by P3. The reader's
20,000-descriptor guard matches the existing JNI transport guard, and is not a
measured Lumina capacity.

## App contract

```kotlin
val snapshot = library.snapshot() // Caller uses an IO context.
val bound = library.residentSelection(snapshot.active!!, snapshot.activeRelease)
val nativeCatalog = ChartResidentAdapter.catalog(bound.planning, bound.pack,
    bound.dataDirectory, residentBudget)
// Configure the explicit resident engine and retain a planner for bound.planning.
// On a user activation, only after successful engine configuration:
library.activateResident(bound)
```

`residentSelection` runs on `Dispatchers.IO` under the library mutex, returns frozen
descriptors, and reads package manifests plus optional index sidecars. It verifies
selected source/chain descriptors, guarded paths and the latest installed NOAA
cancellation metadata. It does not open, hash or parse S57 chart contents. Neither it
nor `paths(selection)` belongs in the camera loop. Retain the binding/planner across
camera updates. Native per-cell admission verifies the full ordered chain's real
bytes before and after ingestion, using P1 source identity and its own prepared ABI.

Pass `snapshot.activeRelease` on restart. A replaced package manifest must not
silently change a saved source key. The binding is checked again before publication;
a changed source or planning generation fails without replacing the selection.
Missing, invalid, duplicate or unsupported metadata remains explicitly unknown.
Valid v1 coordinate extents still provide conservative bounds. No package-wide
S57 rescan or guessed scale is started. `ensureCellMetadata` remains an explicit
single requested-cell recovery API; the app determines its bounded scheduling.

Each metadata read has a byte limit: manifest 8 MiB, optional sidecar 64 MiB, active
selection 4 MiB. Oversized mandatory metadata fails; oversized/invalid optional
sidecars become unknown. IO cancellation is checked while reading and before
publication. These are descriptor IO limits, not a total process-memory guarantee.

## Persistence, migration and downgrade

`active.json` is the sole authoritative selection. Resident publication writes:

```json
{"version":2,"packageId":"…","cells":[{"name":"US5BOSCC","sourceKey":"…"}]}
```

The complete ordered selection is stored, independent of residency. It is replaced
by the existing fsync + atomic rename mechanism. A failed rename/write preserves
the previous active file. Before the first transition from the old unversioned v1
selection, its exact bytes are stored separately in `active-v1-preserved.json`.
This is a recovery backup, never a second active selection. Package manifests,
chart bytes and existing indexes are not rewritten by activation.

Reading a v1 selection does not migrate or publish it. A v2 selection deliberately
fails the old strict v1 decoder, so an old application cannot automatically restore
a 100-cell subset. The new library's legacy `paths`/`activate` methods retain their
100-cell eager-renderer limit; `activate` refuses to overwrite a v2 release.
Unknown/newer or corrupt active formats block both writers and remain untouched.
An absent `snapshot.active` therefore does not authorize migration or replacement.

There is no automatic downgrade/export. Before an explicitly requested downgrade,
preserve the complete v2 file and choose a legacy-compatible selection or restore
the preserved v1 file while the app is stopped. The guard in this new library cannot
control an older binary's writer if a user explicitly creates a new selection there.

## Verification boundaries

`ChartResidentSelectionTest` checks 150/400 synthetic source descriptors with real
P1 coverage-schema metadata and a two-cell resident transport budget, restart,
source replacement, stale planning metadata, v1 backup, strict downgrade behavior,
unknown versions, malformed chains, cancellation, atomic-write failure, NOAA
cancellation and immutable aliases. The fake cardinality sources are not 400 real
NOAA files and do not validate GPU/device memory. A separate genuine Boston
five-file descriptor verifies the frozen P1 key while its data directory is absent,
proving that catalog binding requires no chart-byte access. Existing real import,
integrity, backfill and metadata tests remain applicable.

Renderer scheduling, full-catalog navigation bounds, native mixed-scale clipping,
resident admission memory/temperature on Lumina, deployment and UI acceptance are
outside this library package.

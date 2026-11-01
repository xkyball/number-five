# no-chart-converter

Private source repository for the chart-cache conversion toolchain. Python 3.11+;
the installed CLI runs independently of the historical working directory.

Reviewed own sources and original hashes are retained in `SOURCE_PROVENANCE.json`.
Personal path constants are normalized in the rollback snapshot. Charts, private
inputs, OEM libraries, firmware, APKs, keys and runtime records are excluded.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
.venv/bin/no-chart-converter --help
.venv/bin/python -m pytest -q
```

Available commands:

- `cache-fill --source CACHE.zip --land ne_10m_land-5.1.1.zip --output NEW.zip`
  adds public Natural Earth background cells to an unchanged Pi vector-cache
  export. The globally aligned 10° grid covers the original extent rounded
  outwards. `--expect-sha256` pins the input; existing outputs are never replaced.
  Optional bathymetry, ETOPO land contours and elevation points use the existing
  MAP v2 reader; `fetch-terrain` downloads only a bounded regional height grid.
  `--view-aspect 2000 1070` fills an exact Mercator reference frame with complete
  background cells; the existing app's start view remains manually controlled.
  See [the composite cache contract](docs/CACHE_FILL.md).
- `decode --source CACHE.zip --catalogs S57_CSV_DIRECTORY --output NEW_IR_DIRECTORY`
  validates the actual `no5-pi-map-cache-v1` ZIP and retains geometry, attributes,
  topology and provenance in `no5-chart-ir/1`. Provide `--expect-sha256` to bind an
  externally supplied whole-package digest. All declared cells are the default.
- `export-country --source CACHE.zip --catalogs S57_CSV_DIRECTORY
  --config PRIVATE_ADAPTER_CONFIG.json --output NEW_GENERATION` builds all fifteen
  inputs from the fresh retained IR, compiles the supported presentation and
  geometry, and runs the actual country composer. `--prepare` stops after prepared
  input validation. Reusable presentation resources/rules and optional public S52
  compilation are explicit inputs. Unsupported semantics produce a stage report.
  See [fresh cache conversion](docs/CACHE_COUNTRY.md).
- `country --config PRIVATE_CONFIG.json` runs the complete existing V15 country
  composer from explicitly pinned prepared inputs, including scale selection,
  polygon/line preparation, point/sounding programmes, metadata localization and
  persisted readback. `--prepare` records planning without writing a country RX5.
  See [the input contract](docs/COUNTRY_INPUTS.md).
- `inspect COUNTRY.rx5` validates our plain RX5 blocks, native tree and typed
  geometry/programme references.
- `region --source COUNTRY.rx5 --store EXISTING_OUTPUT_PARENT/store --bbox W S E N
  --name NAME` builds a persistent spatial index and exports an exact complete-
  owner region. Native selection/retirement states through level 21 and complete
  queries/programmes/depth bytes are retained and inversely checked. Geometry
  can overhang the box; it is not clipped. `--estimate` materializes the same
  selected bytes in memory without publishing the chart.
- `area-picker --source COUNTRY.rx5 --store EXISTING_OUTPUT_PARENT/store` serves
  the existing browser area-selection tool on `127.0.0.1:8767`. It uses the same
  region exporter, prepares its index, estimates selected areas and downloads new
  exports. It never opens a browser. External basemap tiles are disabled unless
  `--tile-url` and the appropriate attribution are supplied explicitly.
- `optimize-masks --source REGION.rx5 --output NEW.rx5 --report NEW.json` applies
  V22 convex MASK8 preparation with exhaustive projection certificates for
  original source levels 13–15 and destinations through 21. Other owners remain
  intact. This appends active arenas; it does not promise a smaller file.
- `plan-locality --source CHART.rx5 --tile 15 X Y --output CANDIDATE.json`
  prepares exact candidate plans for explicitly selected existing L15 leaves.
  `apply-locality --source CHART.rx5 --allowlist REVIEWED.json
  --expect-allowlist-sha256 SHA256 --output NEW.rx5 --report NEW.json` applies
  only independently reviewed source/kernel/plan/evidence pins. Planning never
  approves itself, and country conversion does not activate this optimization
  automatically. See [the locality contract](docs/LOCALITY.md).
- `analyze-fill-duplicates --source CHART.rx5 --expect-sha256 SHA256
  --tile LEVEL X Y --output NEW_ANALYSIS.json` reports conservative proposals
  for consecutive identical XY vertices in supported solid fill parts. It
  writes no chart and does not approve native query/anchor behavior. Optional
  `--anchor-policy preserve_native_polygon0` retains the first decoded polygon's
  exact source inputs in the supported closed-Root1 subset and protects known
  overlapping aliases within that source payload.
- `compare-decoder-profiles --baseline BASELINE.json --candidate CANDIDATE.json
  ... --output NEW_COMPARISON.json` compares two explicitly hash-bound stored
  decoder reports, including their chart identities and measurement contracts.
  It runs no decoder. See [decoder analysis](docs/DECODER_ANALYSIS.md) for the
  complete arguments and the separation from physical device timing.
- `prune --source CHART.rx5 --allowlist REVIEWED.json
  --expect-allowlist-sha256 SHA256 --output NEW.rx5 [--title NAME]` binds the
  current V23 policy to an exact reviewed allowlist, rechecks each external
  visual/admission/query certificate pin, requires query identity and applies
  only approved leaf references. The optional menu title is appended separately;
  the complete source prefix is inversely checked. Certificate mathematics and
  native/device acceptance remain external evidence. The advanced `--decisions`
  alternative accepts complete typed caller approvals and reports explicitly
  that it has not rechecked the external proof files.

`decode` retains vector IR, `export-country` generates prepared inputs from that
fresh IR and runs country authoring, and `country` also accepts independently
prepared pinned manifests. The adapter supports the documented geometry and
presentation contracts; it refuses unsupported source visibility, unresolved
required boundaries or missing glyph/programme bindings. It does not load old
research runners or configuration-selected Python code. [Pipeline boundaries](docs/PIPELINE.md)
state what runs and which private inputs it needs.

The additive [geometry load planner](docs/LOAD_PLANNING.md) provides a Python
API for authenticated, metadata-only package budgets with unchanged supplied
tile selections. It is separate from country authoring and the browser tool.

The optional [own POSIX host source](docs/HOST_RUNTIME.md) provides a separate
`no5_*` pthread mutex component for explicitly compiled target-platform consumers.
It introduces no automatic native build or library loading.

The [manual Area query probes](docs/ORIGINAL_QUERY_PROBES.md) provide source-only
synthetic component experiments with separately supplied, reviewed runtime inputs.
They are separate from the installed package and never run automatically.

The [own Navionics header writer](docs/NAVIONICS_WRITER.md) reproduces a 166-byte
synthetic file accepted by the original directory reader. Its bounded raw-header
API and CLI include a wire readback. A separate fixed Point40 preset reproduces
one raw point accepted by the original reader, with optional typed metadata;
a fixed three-point Line32 preset is also accepted through its NoScale reader.
These finite presets make no general chart-writing or rendering claim.

Optional interoperability sources under `tools/navionics_native_probe/` add a
geographic cache-IR adapter, a strict Mac SD metadata encoder and an explicit
own-data encrypted-block diagnostic. The cache adapter currently selects whole
coastline and pontoon lines plus individual sounding samples, with explicit coordinate,
attribute and units policies. The block diagnostic has completed two byte-exact
roundtrips through a separately supplied Original provider. The optional
[container producer](tools/navionics_native_probe/container_producer/README.md)
and [admission probe](tools/navionics_native_probe/own_admission/README.md) also
completed genuine admission and geometry readback for a self-authored 462-byte
container and a 527-byte real-cache subset, each with an encrypted map header,
a line and a sounding. These finite tests use an explicitly registered
synthetic key; automatic removable-media loading,
chart rendering and a usable Navionics chart remain unproven. These tools are
separate from the installed CLI and perform no automatic native startup.

The optional [private-input producer](tools/navionics_native_probe/private_producer/README.md)
keeps media-derived key material in process memory and exports only the completed
own container and scalar checks. Its public request/collector tools never read
the private input; the executor supplies the bound read-only snapshot.

The opt-in [Atlas writer](src/no_chart_converter/navionics/ATLAS_README.md)
streams multiple supplied local maps into one Plain file with a contiguous
header group. The [whole-cell accounting adapter](tools/navionics_native_probe/geographic/SCALING.md)
records every supplied source feature, geometry and attribute outcome, and
keeps unsupported features and conversion losses explicit. Accounting a whole
cell does not make its emitted supported subset a complete chart.

The [sequential cache-export runner](FULL_CACHE_ATLAS.md) processes one cell in
a fresh subprocess, writes its Generic Atlas files and loss accounting, then
releases its temporary IR only after an independent saved-evidence consumer
acknowledges that exact generation. Explicit Generic mode uses all 106 qualified
classes and 148 geometry routes. Completed-cell resume validates the request,
source pins, QA and cleanup together; it performs no native or device actions.

The separate [typed attribute emitter](docs/NAVIONICS_TYPED_ATTRIBUTES.md)
adds finite DEPCNT/SLCONS E/M attribute recipes and stable physical presence
groups. It leaves the legacy and Atlas emitters unchanged; native attribute
readback, scaled-map admission and nautical portrayal remain separate checks.

The optional [normal-loader readback adapter](tools/navionics_native_probe/normal_readback/README.md)
observes bounded geometry reads after an actual successful normal loader call.
Its saved-event validator accepts explicit map/layer/feature expectations; the
published controls use invented data. It reuses the existing qualified reader
and keeps native admission and lease retirement as separate checks.

RX5 source-arena compaction retains complete typed geometry and query data.
Source-local fill-part removal has a narrower renderer domain: normal nearest
object queries can visit neighboring source tiles. Its local component results
therefore do not authorize replacing a general-purpose chart. A sidecar report
does not impose a query guard on the Original renderer.

The optional `rx5.no_ink_children` API instead clones complete absolute-coordinate
sources and empties only qualified invisible AC draw instructions. Canonical
geometry, query/category contexts and parent fallbacks remain present. It
recomputes the source-window and projection certificates and does not activate
itself in country conversion; native child selection, queries and raster parity
remain separate acceptance checks.

The opt-in `rx5.exact_level_children` variant confines an omitted fill to one
qualified level and requires four real next-level children restoring the
complete incoming scene. It recomputes the exact-level certificate, verifies
the actual restore subtree and full source-prefix inverse, and leaves deeper
zoom available. It does not activate itself in country conversion.

The opt-in `rx5.source_slab_children` materializer extends a qualified blank
source over one additional level using four routing nodes and sixteen complete
restore grandchildren. Its `rx5.bounded_noink_slab` policy recomputes the
two-level projection proof and verifies every actual routing/restore node.
It retains the complete incoming geometry and query data; native source
selection, image/query parity and timing remain separate acceptance checks.

The separate [raw Plain FeatureBatch API](docs/NAVIONICS_BATCH.md) provides
typed point/line/area inputs, multiple features and parts, a bound two-pass
offset plan and sequential sink output. Its default scope is one logical block
and empty attributes; an explicit bounded crosspage budget is a source-bound
generalization with separate finite reader history, not general native or rendering qualification.

Inputs, outputs, catalogs and any separately owned native providers stay outside
Git. No helper, OEM library, licensed chart, FPR/key or device action is included.
Historical authorization snapshots remain historical. The package imports and
synthetic tests do not contact a helper or original renderer.

`source_snapshot/` preserves the historical source layout for rollback and
reference; it is not used as executable code by the installed CLI. The annotated
tag `converter-source-baseline-20261016` predates functional portability changes.
Frozen original working sources remain untouched.

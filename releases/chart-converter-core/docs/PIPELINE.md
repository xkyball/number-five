# Pipeline boundaries

| Stage | Portable active implementation | Private runtime inputs |
| --- | --- | --- |
| Pi cache ZIP → vector IR | `cache_decoder.py`, original archive checks and geometry decoder | `no5-pi-map-cache-v1` ZIP and object/attribute CSV catalogs |
| Fresh vector IR → all prepared source/presentation manifests | `cache_country` geometry, metadata and presentation compiler; optional explicit public S52 provider | Reusable pinned presentation resources/rules or public lookup XML and provider; unsupported semantics refuse |
| Complete scale-aware country composition | Static relative `country_runtime` modules; real V15 ownership, clipping, scale selection, serialization and metadata readback | Explicit pins described in `COUNTRY_INPUTS.md` |
| Country → region | `rx5/region.py` and `metadata.py`; CLI and loopback browser picker | Existing own plain country RX5; explicit output store |
| V22 geometry preparation | `rx5/mask_merge.py`, `arena.py`, `optimize.py` | Existing own plain RX5 |
| V23 approved leaf pruning | Unchanged pure `rx5/pruning.py`; strict `approvals.py` product policy | Exact source/allowlist hash and pinned independent visual/admission/query certificates |
| Original native/device acceptance | Separately owned external runtime and physical a97 | Not bundled or performed automatically |

The prepared country manifests are generated intermediate inputs; the standalone
country entrypoint also accepts explicitly supplied preparations. The port retains original geometry, source selection, rendering
programmes, query attributes and source-scale gates. A synthetic one-cell build
contains invented area/line/sounding owners and runs through real country and
region serialization; it is not original-library or a97 acceptance.

The region exporter retains all selection levels and empty retirement guards.
It copies complete intersecting owners, so geometry may extend outside the
requested box. One explicit source/store is configured per CLI process; the
browser picker serializes index/export jobs for that source. V22 uses the same
convex merge and exhaustive projection checks as the current product, without
claiming that a host timing predicts physical a97 load time.

V23 validates the supplied external evidence identity and exact chart mutation.
It does not turn candidate lists or arbitrary similar pixels into approval. Its
product wrapper rejects nautical/query differences even where the lower-level
core has a more general explicitly accepted-report interface.

There is no automatic download, DRM operation, card mount/write, APK deployment,
service restart or preview. The browser picker starts only on explicit invocation,
binds loopback, opens no browser and uses no external basemap by default. Outputs
are explicit new files/directories. Charts, native binaries, private configuration
and runtime receipts belong outside Git.

The baseline tag `converter-source-baseline-20261016` predates functional portability
changes. Original hashes and relative source origins are in `SOURCE_PROVENANCE.json`;
active module origins/adaptations are in `PORT_PROVENANCE.json` and
`country_runtime/SOURCE_PROVENANCE_COUNTRY.json`.

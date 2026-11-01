# Prepared country inputs

`no-chart-converter country --config PRIVATE_CONFIG.json` accepts
`no5-country-runtime-config/1`. The existing V15 composer is packaged as static
Python modules; configuration describes data and never supplies executable code.
The `export-country` adapter generates these inputs from a fresh cache; its
reusable resource and supported-semantics contract is in [CACHE_COUNTRY.md](CACHE_COUNTRY.md).

The top-level object contains `format`, a fresh `outputDirectory`, and `inputs`.
Each input value is an exact `{ "path": "...", "bytes": N, "sha256": "..." }` pin.
Relative paths resolve beside the configuration file. Embedded pins inside
prepared manifests are checked when read. Optional `pathRemaps` entries with
`from` and `to` relocate data paths recorded by earlier private manifests without
altering or weakening their byte/hash checks.

| Required input | Contents |
| --- | --- |
| `composition` | Source cell order, dictionary/document maps and composition metadata |
| `groundCatalog` | Original compiled render/feature stream bindings |
| `groundGeometry` | Prepared ground geometry streams and owner bindings |
| `pointInputs` | Point and signed-depth sounding streams, owners and query frames |
| `coverageFixture` | Source native scales and actual covered/excluded polygons |
| `fillAvailability` | Exact fill geometries bound to ground inputs |
| `containerInputs` | Presentation resource and attribute-map bindings |
| `detailInputs` | Prepared line/area details with source presentation identity |
| `remainingDetailInputs` | Additional source details using the same owner contract |
| `sourceRegistry` | Effective source-style registry |
| `sourcePresentationIndex` | Original saved presentation/common resource binding |
| `caOwnerProgramBindings` | Source owner programme bindings used by point/area activation |
| `presentationParent` | Exact plain RX5 parent supplying compatible metadata/resources |
| `pixelModelAuthorizations` | Explicit permitted precision-model cases; no implicit fallback |
| `pixelModelCallInputs` | Exact authorized call inputs for those precision cases |

`expectedSourceCounts` may bind `sourceCells`, `groundOwners`, `pointOwners` and
`detailOwners`; full source joins are checked independently. `title` and
`editionDate` describe the output. `--prepare` verifies inputs, source joins and
planning and emits a receipt without producing a country chart. A full build
writes fresh `raw/` and finalized `country/` generations plus an input receipt.
An existing output directory is refused.

The same command can restart only the country stage from a verified prepared
generation. Retain its exact fifteen input pins, verify their cache/IR/catalog
and presentation-profile provenance, and create a new configuration with a
fresh output directory. The runtime verifies every top-level input and checks
nested data pins when read; its receipt binds the new configuration. Preparation
does not run again, and the earlier generation remains an explicit dependency.

Coverage fixtures retain the complete original projected walks for source
metadata and point ownership, including retraced zero-area boundary edges.
Area masks interpret separate even/odd faces without changing those walks.
Fresh point programmes may explicitly contain empty drawing contexts; their
owner, query, profile, context entry and SHA bindings remain mandatory.

The Kind3 fill continuation uses `own-root-u32-grid1-valid-output-fill/1`:
GEOS `valid_output` on the one-unit Root grid. Its receipts report actual
added/lost area and topology changes. Serialized geometry is checked through
`own-root-u32-grid1-serialized-role-roundtrip/1`. This contract supplies no
authoring-pixel budget, whole-pixel identity or zero-leak guarantee. Kind2 line
continuation remains separately scoped; source queries and sounding XYZ retain
their existing preservation contract.
A clipped fill that becomes genuinely empty under this quantizer emits no
native fill record. Its explicit disposition is
`EMPTY_DECLARED_ROOT_GRID_FILL_ORIGINAL_SOURCE_RETAINED`, with the source
identity, positive floating reference and measured loss retained in receipts.
This differs from source-winner exclusion and adds no arbitrary owner skip.

Detail-mask construction memoizes repeated ordered higher-source unions inside
each immutable source snapshot. Its key retains the complete ordered domains
and omission states, including exact domain WKB. The original binary-union
order and fresh per-owner scope/refusal checks remain in place. This reduces
repeated compiler work; it does not change chart selection or claim faster
rendering on a plotter.

Diagnostic coverage-overlap areas apply the exact empty-set identity before
GEOS: an intersection with an empty operand has area zero. Nonempty operands
still use the original GEOS operation and propagate its errors. This avoids a
mixed-geometry reoverlay failure without changing masks, source ownership or
the required final serialized-geometry validation.

For a small runnable example using invented data only:

```python
from pathlib import Path
from no_chart_converter.country_runtime import build_country
from no_chart_converter.country_runtime.synthetic import create_synthetic_config

config = create_synthetic_config(Path("new-synthetic-example"))
result = build_country(config)
print(result["chart"])
```

This example exercises the actual area, line and sounding authoring chain. It
does not contain licensed chart data or certify an original renderer/device.
Private real prepared inputs and original-reader providers are never bundled.

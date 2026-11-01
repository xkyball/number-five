The own Atlas writer combines supplied local `LayeredBatchSpec` maps into one
Plain file. It plans every absolute pointer before output and writes through a
sink in chunks no larger than 64 KiB. It does not decide whether the supplied
maps cover a complete source cell.

```python
from no_chart_converter.navionics.atlas import atlas_from_spec, plan_atlas, write_atlas
from no_chart_converter.navionics.batch import plan_sha256

atlas = atlas_from_spec(public_spec)
plan = plan_atlas(atlas, max_output_bytes=64 * 1024 * 1024)
with open(fresh_output_path, "xb") as sink:
    receipt = write_atlas(atlas, sink, expected_plan_sha256=plan_sha256(plan),
                          max_output_bytes=64 * 1024 * 1024)
```

The JSON root is exactly `{"format":"no5-own-plain-atlas/1","maps":[...]}`.
Each map uses the existing strict `no5-own-plain-layered-feature-batch/1` schema.
Dates must agree and source IDs must be unique across all supplied maps. The
1..16-map and 64-MiB bounds are our software scope, not native format limits.
The compatible default remains 4096 bytes. Existing layered plans and bytes
for budgets through 8192 are unchanged; a larger budget adds an explicit
pending-native qualification to the layered plan.

A single Type16 metadata record declares the union of local raw bounds and the
final file extent. Its Type4 directory points at consecutive 55-byte Kind17 map
headers. Each map then has its original lower bank/layer/feature lists with
rebased absolute pointers. One-map Atlas bytes equal the existing layered
writer's bytes. A potential encrypted container must encrypt only this contiguous
Kind17 header group. Native raw `*_from_file` lower-payload readers bypass the
header overlay; those bank/layer/feature/vector bytes must remain Plain in this
profile. No encryption is performed by this module.

Positive short writes retry. Invalid progress or IO exceptions leave a partial
sink and produce no complete receipt. The API does not seek, close, flush,
perform native calls, register keys or establish a persisted-file snapshot.
Callers bind immutable source specs, use fresh outputs, and verify persisted
bytes separately. The input geometry/spec and offset plan remain in memory;
the output stream is buffered to at most 64 KiB, not assembled in full.

Finite historical normal admission and geometry proofs cover smaller own files.
They do not qualify all multiple-map files, large grids, scaled files, attributes,
area holes, source-cell completeness or MFD portrayal. Attributes remain empty.
Each source feature, sample, part and omitted attribute must be accounted by the
external geographic adapter ledger. The current raw Area24 closure rule does
not infer an exterior/hole relationship or nautical area semantics.

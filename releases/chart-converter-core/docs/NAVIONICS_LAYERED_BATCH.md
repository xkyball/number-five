# Own typed Plain layers and depth points

The additive `no_chart_converter.navionics.layered_batch` API plans and streams
multiple typed layers. The existing `navionics.batch` API, schema, default plan
digests and byte streams are unchanged. This extension uses source-bound native
dictionary and scalar-codec facts; no new authored file has native acceptance
merely because these software checks pass.

`DepthPoint44(source_id, xy, mantissa, decimal_scale=0)` is the narrow SOUNDG
class 136 record: signed source-I32 XY, flags0, empty attributes, and an explicit
signed14-bit depth mantissa plus two scale bits. Scale 0 divides by10; scales 1,
2 and 3 multiply by1,10 and100. The emitted LE16 word is
`(decimal_scale << 14) | (mantissa & 0x3fff)`. Mantissas must be -8192..8191;
scales must be 0..3. A mantissa 50/scale 0 has source-predicted F32 value5.0.
No float-to-word rounding or inferred invalid-value sentinel is added.

`PlainLayer(layer_id, features, layer_range=(0,100000))` requires a nonempty
feature sequence with matching low-two-bit geometry type: point 0, line 1,
area 2. It accepts the existing Point40/Line32/Area24 objects as well as
DepthPoint44. The depth point is restricted to layer 140, whose record-tag
unit bits 8..10 stay zero. The native scalar path maps this to private depth
unit 2 (meters). This differs from the public NavDepthUnit enum, where 1 names
meters; the API exposes neither enum as a user-selectable unit. No vertical
datum or rendered depth label is established.

The actual native dictionary maps COALNE to class 29/base 60; its line layer is
61. It maps SOUNDG to class 136/base 140; its point layer is140. These native
codes must not be substituted with external S57 object numbers. This source
routing fact does not qualify a new renderer or a navigable chart.

`LayeredBatchSpec(header, layers, date_text_1='20261017', date_text_2='20261017')`
uses the existing `RawMapHeader`, with a zero cursor placeholder. Bounds/grid
are validated by the unchanged RAW guard. Source IDs must be unique across
all layers; unique layer IDs are an own input rule, not a claimed file-format
limit. Geographic conversion is outside this API: Point40/44 take source-I32
XY and vectors take local U16 XY. N1024 and southern-hemisphere coordinates
remain source/math proposals until their actual original readback is checked.

Use `plan_layered_batch(spec, max_output_bytes=4096)` and canonical
`plan_sha256(plan)`, then
`write_layered_batch(spec, sink, expected_plan_sha256=digest, max_output_bytes=4096)`.
The explicit own budget is 1..8192 bytes. It is SHA-bound and is not a native
format size limit or general crosspage qualification. The two-pass plan binds
full ordered geometry, depth word, layer routing and every pointer. Part count
and attribute length retain the coupled1/2/4-byte width calculation, with
attributes always empty. Original class/width/coordinate acceptance for old
finite files does not qualify every new layered input.

All Layer18 records occur sequentially, followed by one plain layer-end.
Each layer points to its own feature list and has its own selected feature-end.
The layer body's second U32 stays 0 as in the accepted own fixtures; it is not
reinterpreted as a universally reserved field. The layer-tag units stay0 and
are unrelated to `RawMapHeader.flags`. The metadata/file-size, header, bank,
layer, feature and direct low30 stream pointers are computed from the final
layout, with no inserted page headers or padding.

The caller supplies a fresh sink at byte0. Positive short writes are retried;
invalid write counts or exceptions fail and may leave partial output. The API
does not seek, flush, close, roll back, execute native code or authenticate a
later persisted file. Native qualification fields remain explicitly untested.

The strict additive JSON format is `no5-own-plain-layered-feature-batch/1`,
with root fields `format`, `header`, `layers`, `date_text_1`, `date_text_2`.
Each layer has exactly `layer_id`, `layer_range`, `features`. Existing raw
feature rows retain their previous fields. A kind 44 row has exactly `kind`,
`source_id`, `xy`, `mantissa`, `decimal_scale`; class 136 is fixed. There are no
attribute, arbitrary-unit, flag, private-mode or opaque-field escape hatches.

The manual CLI commands are `plan-navionics-layered-batch` and
`write-navionics-layered-batch`. Both require `--spec` and
`--expect-spec-sha256`; writing additionally requires `--expect-plan-sha256`
and a fresh `--output`. Optional `--max-output-bytes` is passed to both steps.
The existing no-follow regular-spec snapshot, duplicate-key rejection,
pre-write checks, no-clobber output and persisted full SHA readback apply.

The intended smallest combined source test is a COALNE line and one 5 m depth
point in two layers. Its geographic inputs are invented, not a surveyed
coastline. Exact coordinate math and negative-Y rounding must be checked
separately from depth units, native layer/feature ends, normal lifecycle and
any portrayal. This source extension makes no LoadXF2, normal high-level
collection, authorization, datum, full-cartography, display or timing claim.

# Own raw Plain FeatureBatch

The Python API in `no_chart_converter.navionics.batch` plans and emits multiple
Point40, Line32 and Area24 features and vector parts in supplied order. It
generalizes our finite own lower-reader inputs. This general interface has no
new original-reader acceptance, full collection/LoadXF2 admission or rendering
result. It loads no native library and includes no OEM or purchased payloads.

Use frozen `Point40(source_id, class_code, xy)`, `Line32(source_id, class_code,
parts)` and `Area24(source_id, class_code, parts)` objects in a
`BatchSpec(header, features, layer_id=60, layer_range=(0,100000),
date_text_1='20261017', date_text_2='20261017')`. The header is a `RawMapHeader`
whose `trailing_value` must be zero: the writer computes that layer pointer.
Lists are snapshotted into immutable tuples. Source labels are nonempty strings
of at most 128 characters, unique per batch. They are external ledger labels,
not native feature IDs or attributes.

Point40 takes signed 32-bit raw source X/Y inside the supplied header bounds.
Vectors take supplied unsigned 16-bit local X/Y inside the supplied raw grid.
Line parts have at least two stored points; Area parts have at least four,
including an explicit closing point, and three distinct vertices. Every part
is retained in order, including repeated vertices and aliases in the input.
No hole, orientation, ring grouping, reversal or shared-pointer semantics are
assigned to separate parts. Classes are raw U8 declarations; flags and attribute
length are always zero. Semantic attributes and depth units are unsupported.
The Area24 profile retains the previously tested own zero tail without claiming
that this byte is universally required by the format.

The plain metadata record is 258 bytes, with the own zero descriptor at File144.
Header and optional Bank52 carrier use the same supplied raw bounds/grid. The
RAW validator checks integer values, ordered bounds, the observed inclusive
header envelopes, its own grid domain 2..65536, signed fixed-point arithmetic
and a conservative rectangular aspect intermediate. Passing these checks is
**not** native Header/LOD/Scale qualification, including at N=65536. No
world-to-raw conversion or coordinate error bound is provided. The bank prolog
does not establish a local transform by itself.

`plan_plain_batch(spec)` returns JSON data with every record/part offset,
length, field width, point count and ordered coordinate SHA256. Absolute points
are included directly. Thus a changed interior vertex changes the plan binding
even when counts and bounds stay equal. `plan_sha256(plan)` binds the complete
plan. The writer recomputes it independently before its first sink write.

Length planning evaluates each complete candidate width W=1,2,4. The envelope
length, attribute length and vector part count share W. In this empty-attribute
profile Point40 payload size is `10+W`, Line32 is `10+2W+4P`, and Area24 adds
its retained one-byte tail. For example P=60 fits W1 with 252 bytes, while P=61
requires a rebuilt W2 payload of 258 bytes. Bank envelope width is planned
independently and shifts all direct part pointers. Pointers are U32 with the
upper two mode bits zero; direct stream offsets must fit low30.

The default interface refuses a complete extent above **4096 bytes**, before
any sink write. This is its compatible first-logical-block boundary, not a
Navionics format size limit. An explicit `max_output_bytes` argument (CLI
`--max-output-bytes`) can choose an own software budget from 1 through 8192.
Values above4096 are a source-bound contiguous plain-file generalization.
General native qualification remains open; the finite result below is separate.
The observed plain no-overlay cache path seeks at `4096*k` and reads up to
4100 overlapping bytes: the extra four bytes are read overlap, not a disk
header or footer. Offsets and declared file length remain contiguous byte
positions, without padding, a per-page size factor or reads past actual EOF.
This source fact does not qualify other encryption/overlay or high-level paths.

The default4096 plan dictionary and SHA remain exactly compatible with0.2.21;
its `FIRST_LOGICAL_BLOCK_ONLY` profile binds that implicit budget. Every
nondefault budget is an explicit `outputBudgetBytes` plan field and therefore
changes the plan SHA, even if emitted geometry/bytes stay the same. Budgets
above4096 additionally report
`SOURCE_BOUND_CONTIGUOUS_PLAIN_GENERIC_NATIVE_UNQUALIFIED`. Pass the same explicit
budget to both planning and writing; a hash from a different budget refuses
before the first write. Budgets outside1..8192 and over-budget output refuse.
The scalar `plan_payload_width(base_bytes, sized_fields=...)` exposes the width
fixed point for U8/U16/U32 software tests without allocating large files.

```python
plan = plan_plain_batch(spec)
binding = plan_sha256(plan)
with open(new_output_path, 'xb') as sink:
    report = write_plain_batch(spec, sink, expected_plan_sha256=binding)
```

The caller owns a fresh sink starting at byte zero. The writer calls only
`write(bytes)`: no seeking, flushing, closing, whole-file concatenation or
device operation. It retries positive short writes. None, bool, zero, negative
or excessive write counts fail; genuine sink exceptions propagate. A failure
after output starts can leave partial bytes and returns no success receipt.
The caller owns cleanup. `emittedStreamSHA256` hashes only the emitted stream;
a persisted-file hash/readback is a separate caller responsibility.

The plan's `sourceId`, `layerOffset` and feature `recordOffset`, bound to that
emitted stream hash, form the source ledger. Each part has a separate
`streamOffset`. This is not the original feature's opaque member cursor and
does not create a private native UID.

The CLI takes an exact-key JSON specification with format
`no5-own-plain-feature-batch/1`, the six `RawMapHeader` fields in `header`, a
`features` list and explicit `layer_id`, `layer_range`, `date_text_1` and
`date_text_2`. A feature row has exactly `kind`, `source_id`, `class_code` and
either `xy` (40) or `parts` (32/24). Unknown keys such as flags, attributes,
holes or authorization options refuse. The pinned input must be a regular
nonsymlink file of at most 64 KiB. Duplicate JSON keys refuse.

```sh
no-chart-converter plan-navionics-batch --spec ./batch.json \
  --expect-spec-sha256 ACTUAL_SPEC_SHA256
no-chart-converter write-navionics-batch --spec ./batch.json \
  --expect-spec-sha256 ACTUAL_SPEC_SHA256 --expect-plan-sha256 RETURNED_PLAN_SHA256 \
  --output ./new-own-batch.bin
```

Only for a separately reviewed source-level two-page proof, append
`--max-output-bytes 8192` to **both** commands and use the resulting plan SHA.
This opt-in changes no source spec, key, descriptor, vendor flag or native mode.

An unmodified original ARMHF low-level reader has now read one exact 4153-byte
own Line32/class29 file successfully. It contained 750 supplied U16 points; a
Y word spanned File4095..4096. The reader returned 12 chunks and 761 raw point
occurrences, including 11 genuine state-5 carry boundaries, then real feature
and layer ends with normal cache/OSS/process cleanup. The input SHA256 is
`162817871b40a945a4b84ea83ed8165c9f1b3cbe4d1f74ad4c6a7686ae70036c`;
the saved original-run report SHA256 is
`a36560e5abe206ecc3bbe45a1db649c50013b2183783b1fc0b2807d41ff93440`.
This finite history is also bound in the packaged batch provenance.

It establishes that exact U16 crossing and file, without generalizing to all
8-KiB files, every length/field width, U32 boundary reads, attributes, rendering,
high-level LoadXF2 or normal collection admission. The newer generic source
qualifier changes the nondefault budget's plan hash from its earlier pending
label while retaining identical emitted bytes. Default0.2.21 plan hashes remain
unchanged. Every emission still reports `nativeReaderExecuted: false`: the
writer itself does not execute the separate original reader.

Planning prints JSON without opening an output. Writing validates both bindings
before creating a new output with exclusive creation, streams and fsyncs it,
then verifies its complete persisted size/hash. Existing files and symlinks
are refused. No directories, previews, native runs, device copies or runtime
configurations are created. Both commands retain the general native-acceptance
and physical-packing limits above.

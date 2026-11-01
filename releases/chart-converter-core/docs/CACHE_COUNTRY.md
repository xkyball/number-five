# Fresh cache to country

The adapter builds all fifteen prepared-country inputs from a newly decoded
`no5-pi-map-cache-v1` export. It does not reuse historical cell/owner manifests.
Every original cell ID, feature ID, attribute, source scale and raw geometry stays
in the retained IR; new target UIDs use the selected IR collection's cell order.
Filtering a different collection can therefore change the target UID namespace.

CSV catalogs must have unambiguous numeric codes. Duplicate codes are refused. A
separately reviewed projection may exclude ambiguous aliases only when its input
pins, excluded rows and applicability to the selected source codes are recorded;
there is no silent first-wins catalog rule.

```sh
no-chart-converter export-country --source CACHE.zip --catalogs S57_CSV_DIRECTORY \
  --config PRIVATE_ADAPTER_CONFIG.json --output NEW_GENERATION
```

The configuration is `no5-cache-country-config/1` with a byte/hash-pinned
`presentationProfile`. Optional `title` and `editionDate` name the output.
`--cells ID1,ID2` selects a diagnostic source subset; `--prepare` generates and
validates prepared inputs without writing the country chart.

The export contains geometry and attributes, but not its original renderer's
complete presentation. A reusable `no5-cache-country-presentation/1` profile
therefore supplies pinned resource blocks and generic class/primitive/attribute
lookup rules, or a pinned public S52 lookup XML plus an explicit compiler provider.
No rule may substitute an old chart's per-owner style assignments. Unknown or
unclosed programmes are refused rather than silently replaced by empty styles.

A profile declares `nativeScaminLevelU32` and `nativeScaminMultiplierU32` explicitly.
They are target compiler parameters, not a conversion of source SCAMIN denominators.
The original source SCAMIN stays in the query and controls the existing own V15
scale policy. Actual source SCAMAX is currently refused because this writer has
no contracted implementation of its visibility rule.

Generic rules use a class acronym, raw IR primitive (`0` point, `1` line, `2`
area), source lookup table/RCID, priority, category, typed attribute predicates
and an instruction. Paper and simplified instruction packets stay separate.
A single target owner has one ordering/visibility state. A profile can explicitly
declare `contextPaintPolicy: "OWN_MAX_PRIORITY_MOST_VISIBLE_CATEGORY/1"`: the
compiler uses the higher numerical context priority and the most visible category
(`DISPLAYBASE`, then `STANDARD`, then `OTHER`). The original per-context lookup
selections and programme bytes remain unchanged, and every owner records its
source states, applied target state and policy. This does not claim identical
source paint order when priorities differ. Without the policy, differing states
are refused. The policy creates no duplicate owners. Resource references
must resolve to actual pinned definitions and, when requested, atlas entries.
Closed light-sector recipes bind actual glyph resources; unknown combinations
remain a concrete refusal. See the packaged optional provider's
[native build notes](../src/no_chart_converter/cache_country/native/README.md).

Geometry uses the existing Root-Q Mercator codec. Actual source coverage and
exclusions are retained; a bounding box does not stand in for coverage or fill.
Source triangle primitives supply mask fills, while real source topology supplies
boundaries and line parts. Kind-3 alone never grants fill availability. Exact
source-null strip/fan connector triangles remain empty. A primitive collapsed by
the declared Root rounding contributes no fill and receives a quantization event;
an orientation change retains its original vertex order and exterior-mask role.
An entirely empty owner, unsupported seams and unresolved required boundaries are
refused. Self-intersecting coverage uses explicitly recorded even/odd faces only
when noding creates exact integer Root vertices; no tolerance or buffer repair is
applied, and unhandled subgrid intersections remain a refusal.
An area with boundary, symbol or text instructions keeps its source triangle fill
in the primary record. A separate kind-2 companion carries the authentic topology
boundary and only its LS/LC instructions. Original source IDs and primary UIDs
remain intact; the companion reserves low-word bit 31 and declares its primary
relationship, identical query attributes and distinct programme/resource binding.
Receipts distinguish source features from authored representation records. Area
symbols/text use an explicitly declared F64 anchor: the arithmetic mean of the
first exterior ring's Root-Q vertices, excluding the closing duplicate. This own
stable policy does not claim bit parity with a renderer's view-dependent fallback.
Signed soundings use decimetres with nearest/ties-even quantization; receipts
retain the original scalar, unit/datum state and measured quantization difference.
An inferred metre unit or unknown vertical datum is not upgraded to verified data.

Fresh decoding creates `ir/`; an explicit resume references the retained IR in
place. Output also contains validated plaintext `source-cells/` for the optional
provider, `prepared/`, the country generation and an adapter receipt. Unsupported
input retains its IR and an `ADAPTER_REFUSAL.json` naming the failing stage.
A successful own wire/geometry readback does not certify physical a97 portrayal.

## External filesystems and temporary decoding

The decoder requires an actual atomic no-clobber directory publication primitive.
A tiny filesystem probe runs before heavy decoding. Some external filesystems,
including the measured macOS ExFAT setup, do not support that primitive.

For those outputs provide a **fresh**, explicitly selected supported-filesystem
workspace in the adapter configuration:

```json
{
  "format": "no5-cache-country-config/1",
  "presentationProfile": {"path": "PROFILE.json", "bytes": 123, "sha256": "SUPPLY_ACTUAL_SHA256"},
  "decodeWorkDirectory": "/private/tmp/NEW_DECODE_WORKSPACE",
  "maxDecodeWorkBytes": 1073741824
}
```

The sample pin is intentionally not valid; use the actual profile's length/hash.

The Python resource bootstrap can also normalize legacy bitmap offsets before
writing the profile. Install `no-chart-converter[bitmap]` and pass an explicit
`public_bitmap_png_pin` to `bootstrap_public_s52_profile`, alongside the target
parent and public XML pins. The optional Pillow adapter decodes static PNGs to
straight RGBA8 without resizing, compositing or colour management. Animated and
16-bit images are refused instead of silently reducing their data. Without this
argument, the bootstrap preserves its existing atlas table and does not import
Pillow.
The normalization path accepts UTF-8 XML only and refuses DTD/entity declarations.

Only scale-1 rows with the same symbol name, dimensions, every RGBA byte and one
unambiguous matching public XML bitmap pivot receive offsets `(-pivotX, -pivotY)`.
The rectangle, scale, atlas pixels, palette and definition resources are retained.
An unmatched or ambiguous row remains lexically unchanged and is reported as
unproven; names or image dimensions alone cannot approve a correction. Malformed
rectangles, duplicate table names and invalid explicit resource pins refuse the
normalization before resource serialization. The pure
`cache_country.bitmap_pivots.normalize_bitmap_pivot_rows` API accepts decoded
`RGBAImage` values and needs no image library.

The profile and bootstrap receipt pin `bitmap-pivot-receipt.json`, which binds the
source/result table, source parent, public XML/PNG and full decoded RGBA images.
It lists changed rows, preserved unproven rows and already-exact offsets. This is
a resource correction; the report makes no native-renderer or physical-device
acceptance claim.
The byte budget covers decoded cells and decoder manifests/receipts before each
write. Its default is 1 GiB, an internal resource budget with no a97 significance.
The completed IR is copied to the external generation, every file and descriptor
is read back by size/SHA, and a separate `IR_DELIVERY_RECEIPT.json` marks completion.
This delivery explicitly makes **no atomic external-directory publication claim**.
Only then is the owned temporary workspace removed. Failed delivery keeps its
bounded generation and a three-hour workspace lease for diagnosis; its location
is identified in the failure report and may need explicit cleanup after recovery.
No prepared input refers to the removed decode workspace.

## Resume from a completed retained IR generation

After a later presentation or geometry refusal, avoid repeating decoding and IR
delivery by explicitly selecting the completed manifest and its SHA256:

```sh
no-chart-converter export-country --source CACHE.zip --catalogs S57_CSV_DIRECTORY \
  --config PRIVATE_ADAPTER_CONFIG.json --output ANOTHER_FRESH_GENERATION \
  --expect-sha256 EXACT_CACHE_SHA256 \
  --retained-ir-manifest PREVIOUS_GENERATION/ir/ir-manifest.json \
  --expect-ir-sha256 EXACT_IR_MANIFEST_SHA256
```

The adapter verifies the source ZIP, retained manifest and completed decoder
receipt, unchanged catalog hashes, complete cell descriptor join, and every cell
size/hash before preparation. The retained selection/order stays fixed; `--cells`
cannot be combined with this mode. No historical prepared owner, metadata or
presentation state is accepted. All new prepared inputs and the country remain
fresh. The IR is referenced in place with zero IR-copy bytes, so its retained
generation must remain available; the new receipt records that dependency.

## Plain vector container

Fresh country parents and synthetic fixtures use the shared 240-byte plain
vector header initializer. It writes every unsigned layout field, including
the 1-GiB file-segment descriptor, one segment, version 8, minimum version 5 and vector
flag 4. The finalizer checks this contract before writing and during persisted
readback. This writer supports one physical file up to its segment extent;
it does not generate segmented sidecar files. The descriptor is not a device
or storage-card capacity limit.

The retained reader reads exactly the declared HeaderSize into an internally
256-byte, zero-filled buffer. Declaring zero would read no header; declaring
240 preserves the actual first-block offset 240. Its seek path divides by
FileSegmentSize at unsigned64 offset 40. Header validation is an own host check;
it does not establish physical-plotter rendering acceptance.

## Original sounding admission

After metadata localization, the writer materializes exact adjacent Kind4/9
sounding twins in every active vector profile. Each pair retains the same UID,
XYZ12/index references, signed depth values, programmes, queries and other record
bytes. Only the kind byte differs. The retained version8 reader's witnessed gate
uses Kind9 for source equal to destination and Kind4 for different source and
destination. This closes the missing equal-zoom admission without adding primary
objects or recomputing geometry. Full rendering and all query/UI deduplication
are separate acceptance scopes.

The occurrence inventory keeps the primary source role in `kind` and explicitly
records `nativeOccurrenceKinds`, `maskLevelsByKind` and `recordsByKind`. Kind9 is
decoded as its native XYZ12 role. Arbitrary duplicate UIDs, conflicting pairs
and orphan Kind9 records are refused. Region export retains both identical-bounds
occurrences and their shared index array. Already complete pairs are idempotent.

An existing own single-file plain version8 chart can use the same step:

```sh
no-chart-converter materialize-soundings --source OWN_SOURCE.rx5 \
  --expect-sha256 SOURCE_SHA256 --output NEW_CHART.rx5 --report NEW_RECEIPT.json
```

This rebinds container pointers while preserving all coordinate, index, render,
additional, source and resource payload bytes. It does not call a helper or
rebuild the country preparation/profiles. Existing files are never overwritten.

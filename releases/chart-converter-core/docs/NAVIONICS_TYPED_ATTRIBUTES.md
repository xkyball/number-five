# Opt-in typed E/M and physical presence groups

This staged pure module changes no converter source, default, CLI or legacy
plan. Root may port `typed_batch.py` to `navionics/typed_batch.py`; the existing
Atlas adapter needs explicit type dispatch and must not treat this as the
legacy `LayeredBatchSpec`.

The finite native translations are DEPCNT class42/line37 and SLCONS
class131/line137/area138. VALDCO138 is signed I32 at factor10 with an explicitly
declared native-unit2 premise. This premise is not a source datum/unit discovery.
CATSLC62 and WATLEV151 are U8 E values, limited to the independently paired
source/native meanings. No source-code arithmetic or unknown sentinels occur.

`EnumE(native_code_u16, enum_u8)` and
`ScaledM(native_code_u16, value: Decimal|int, declared_native_units)` feed
`AttributedFeature(legacy_Line32_or_Area24, attributes)`.
`ScaledM` refuses floats, nonfinite values, nonexact tenths, invalid units and
I32 overflow. Its bounded Decimal coefficient/exponent validation is an own
scalar tool budget, not a format limit. All typed attributes are explicit;
unsupported fields/codes/values and duplicates refuse.

`group_typed_features(features, *, layer_range=(0,100000))` forms contiguous
runs by native geometry/class/complete attribute presence. A/B/A stays A/B/A;
features are never globally reordered. `PresenceLayer` refuses heterogeneous
presence. Duplicate physical layer IDs are allowed only in this additive API.
The original GetLayer enumerates physical records and resets masks per layer;
GetSpecificLayer takes an explicit record offset. Both facts are source-bound.

`TypedBatchSpec(header, layers, dates...)`, `plan_typed_batch`,
`write_typed_batch`, `typed_batch_from_spec`, and private `_chunks` mirror the
existing layered plan structure. All absolute offsets, `sourceId`,
`layerOrdinal`, bank part hashes and direct U16 streams remain explicit.
`plan_sha256` binds the complete plan before the first sink write. Positive
short writes retry IO; errors can leave partial caller-owned output.

The default output tool budget is4096; an explicit budget up to64MiB can stage
larger inputs. This is not a native-format capacity or an acceptance claim.
Writer owns Atlas/private streaming; this module makes no atlas changes.
Mixed Point40/DepthPoint44 are intentionally outside this first typed subset.

All subsets of the supported class presence positions, including empty and
CATSLC-only, have an explicit wire representation. Empty means declared absent,
never an unknown enum or substitute for a present source value. The actual
source population has WATLEV on every SLCONS; an upstream adapter must retain
that value and separately account for all unsupported source fields. This API
claims no full-source attribute completeness. Depth's first readback checker
covers the three actual nonempty recipes42:[138],131:[151]/[62,151], not the
optional CATSLC-only emitter recipe.

OWN_SMOKE.nv2 and its JSON spec/plan are entirely invented. They contain five
features in physical IDs137,137,137,37,138 with attribute payloads
02 /0604 /03 /32000000 /0402. No purchased geometry, OEM bodies or runtime is
included or executed. Native traversal/attribute values, portrayal, geographic
meaning and normal loader acceptance remain untested by this work.


The additive depth-area profile supports native DEPARE class41 and DRGARE
class46, both in Area38. DRVAL1/DRVAL2 are ordered M82/M83 at dictionary
positions1/2 with factor10 and explicitly declared native-unit2. Different
presence sets remain separate physical groups; absent values are never
invented. Existing DEPCNT/SLCONS plans and bytes remain unchanged.
The profile's source/wire contract does not establish a source datum, native
portrayal or complete-cell acceptance.

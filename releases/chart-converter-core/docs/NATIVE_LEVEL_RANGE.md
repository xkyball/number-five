# Optional native feature level range

`no_chart_converter.navionics.batch.NativeLevelRange(min_level, max_level)`
is an explicit native-format range. Both values must be integers from 0 through 15,
with `min_level <= max_level`; booleans and floats refuse. It is independent of
S57 SCAMIN/SCAMAX scale denominators.

Pass it as the optional last `native_level_range` field of `Point40`, `Line32`,
`Area24`, or `DepthPoint44`. An `AttributedFeature` uses its geometry's range.
The existing batch/layered/typed planners and Atlas dispatch use it directly.

```python
from no_chart_converter.navionics.batch import Line32, NativeLevelRange

line = Line32('own-line', 29, (((0, 0), (3, 2)),),
              native_level_range=NativeLevelRange(0, 15))
```

In the existing strict JSON feature row, the optional field is:

```json
"native_level_range": {"min_level": 0, "max_level": 15}
```

It works at the same feature-row position in raw, layered, typed and nested
Atlas map documents. Unknown range fields, denominator aliases and invalid
values refuse before writing. The outer document schemas are unchanged.

Absent or `null` retains the previous `0x00` byte and the complete legacy plan
and SHA. An explicit range adds the per-feature plan annotation
`nativeLevelRange: {minLevel, maxLevel, packedByte}` and emits
`(min_level << 4) | max_level` at the existing feature range byte. The record
extent, geometry, attributes and pointer layout do not change. Explicit 0/0
keeps the wire identical but binds the caller's explicit intent into the plan
SHA. Atlas propagates the existing local plan annotation without a codec fork.

The source contract is `NATIVE_SCALE_AND_LIGHT_RECIPES.json`, SHA256
`ec6233210e15e2b62f9d5e15cd4bb67895bae21ca852d1d363b975e301f90c4c`.
Normal native flags7 applies this range; flags4 geometry readbacks bypass it.
Range 0/15 covers the representable native chartStep 0..15 under the documented
conditions. It does not establish a scale-denominator conversion, behavior for
larger chart steps, visibility, navigation accuracy or portrayal.

The published finite 588-byte typed and 8-map native inputs remain unchanged.
No new native execution accompanies this opt-in. A future portrayal check needs
the genuine normal view state with nonzero chart step; an artificial private
map-state write or a forced step 0 check is not a substitute.

Portable source tests are `tests/test_navionics_native_level_range.py`. All
fixtures are invented; baseline digests bind the complete pre-opt-in plans and
wire. These tests do not initialize or execute the native runtime.

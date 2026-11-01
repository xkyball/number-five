# Own cleartext header-directory and finite point writer

`write-navionics-directory` writes our own synthetic logical record stream.
The built-in case reproduces the 166-byte file read successfully through the
unmodified original file-registration and map-directory APIs on an ARMHF
process. That execution returned two 48-byte decoded headers and completed
free, retire and cache uninitialization normally. It did not read geometry or
render a chart. The installed tool itself never executes that runtime.
This lower-level directory result also does not establish MFD collection
discovery or the outer `EChartInfo2` / `xf2_ReadChartHeader` metadata contract.

```sh
no-chart-converter write-navionics-directory \
  --synthetic-two-header --output ./own-header-directory.bin
```

The output SHA256 is
`dc5c48b1db5f4a01cdd8c266bc8cf293dbf161ae36a7f149f2ba0ca14d05ab89`.
The command prints a JSON receipt with the persisted file hash, raw header
readback and `nativeReaderExecuted: false`. Existing files and symlinks are
refused. It creates no directories, opens no preview and loads no native library.

An explicit raw-header specification is also supported:

```json
{
  "format": "no5-synthetic-nav-header-directory/1",
  "headers": [
    {
      "flags": 4660,
      "raw_bounds": [100000, 100000, 200000, 200000],
      "raw_grid": [4, 4],
      "raw_levels": [8, 20],
      "trailing_value": 324508639
    }
  ]
}
```

```sh
no-chart-converter write-navionics-directory --spec ./headers.json \
  --expect-spec-sha256 ACTUAL_SHA256 --output ./own-custom-headers.bin
```

The hash binds the same bytes parsed as JSON. Specs must be regular files no
larger than 64 KiB; duplicate keys, extra fields and noninteger wire values are
refused. The supported tool scope is one to sixteen headers, retained in supplied
order including duplicates. This bound is not a file-format limit. Acceptance
of arbitrary custom values by the original reader is untested by this command.

The Python API in `no_chart_converter.navionics.writer` provides frozen
`RawMapHeader(flags, raw_bounds, raw_grid, raw_levels, trailing_value)` values,
`encode_map_header`, `build_header_directory`, `directory_from_spec`,
`synthetic_two_header_directory` and `read_header_directory`. Bounds are four
signed 32-bit values; grid/level pairs and the trailing word are unsigned 32-bit
values; flags are unsigned 16-bit. Lists are snapshotted as immutable tuples.
The separate `encode_record` / `decode_record` API supports the observed one,
two and four-byte little-endian payload lengths and opaque payloads.

Canonical directory output is Type7 with the own ASCII marker, Type4 with
absolute byte positions, complete 52-byte Type17 header payloads, then Type0
with the `ffffffff` stop payload. Directory/header tags retain flag `8000`.
The canonical reader checks contiguous pointer targets, record kinds, widths,
reserved zero gaps, stop and exact end of stream. It is an own wire validator,
not an independent implementation of the complete original format.

No CRS, geographic units, valid chart bounds, zoom calibration, depth or style
meaning is assigned to the raw values. The built-in trailing words are arbitrary
header-test markers. They are not usable geometry/layer pointers. Original
decoded level values differ from these raw level inputs; this writer preserves
the input fields rather than inferring a geographic level rule.

The word at header payload offset 48 is used by the original `GetLayer(first=1)`
path as its first record cursor. A future geometry writer must point it at a
real layer record. The arbitrary values in these header-only probes must never
be passed off as complete geometry files.

No OEM library, purchased chart, protection key, identity input or native runner
is bundled. Full collection semantics, general geometry writing, rendering and
physical plotter acceptance remain separate work. The original two-header acceptance and
own structured-source provenance are pinned in the packaged
`resources/NAVIONICS_WRITER_PROVENANCE.json`.

## One fixed Point40 test preset

```sh
no-chart-converter write-navionics-point-fixture --output ./own-point.bin
```

This separate command emits exactly 150 own bytes: one header, one Type18 layer,
and one Type40 point, class 126, flags zero, no attributes, raw X=125000 and
Y=175000. Its SHA256 is
`6767cff834bf266d7b6414840e860ebe1872acc020c2dc43fcb14df5b4738c89`.
The unmodified original ARMHF reader read that exact input through normal
registration, directory, layer and feature APIs, converted the point and
returned normal feature/layer ends, then freed and uninitialized normally.
This is acceptance of that finite raw test preset. No geographic CRS, style,
renderer output or complete MFD collection discovery is asserted.

The feature-end Type0 record retains flag `8000`. An earlier own variant with
that flag missing did read the point but skipped the end record and ran to EOF.
The layer-end Type0 record remains unselected. The writer and strict own preset
readback preserve this distinction.

```sh
no-chart-converter write-navionics-point-fixture --prefix type16 \
  --output ./own-point-with-metadata.bin
```

The optional Type16 prefix creates a 246-byte fixture with minimum 130-byte
metadata payload. Its two-byte length is explicit even though 130 also fits in
one byte: this puts the format constant `081273ab` at file offset 4, as the
original presence reader requires. The record starts `50 80 82 00`. The final
file length is stored at file offset 11. Directory, header, layer and point
positions are recomputed from the final layout; the header's first-layer cursor
is 196 and the layer's first-feature cursor is 225. Other unknown metadata fields
are own zeros, not vendor defaults. Both date strings are the explicit own
calendar value `20261017`. After the 0.2.18 source checkpoint, this exact 246-byte
file also passed the original presence/168-byte information reader, directory,
layer and point APIs, both end markers and normal cleanup. Independent reviews
checked all 43 metadata fields. The earlier frozen provenance remains an honest
record of its then-pending status; the later result is bound additively in
`resources/NAVIONICS_PLAIN_METADATA_PROVENANCE.json`.

The pure `no_chart_converter.navionics.point` API provides
`build_absolute_point_fixture(prefix='label' | 'type16' | 'type16-plain')` and
`read_absolute_point_fixture(bytes)`. The reader accepts only these fixed own
layouts, verifies the metadata/declared extent, all record pointers, fixed raw
values and both terminators, and returns typed wire values. It is not an
arbitrary feature decoder. `no_chart_converter.navionics.metadata` also exposes
the independently authored `encode_metadata_record` raw-field API; meaningful
metadata values beyond these finite observations remain the caller's obligation.

Both CLI variants write a fresh file and print a persisted-byte receipt. They
execute no original code. Original readback evidence, source origins and the
separate metadata-candidate boundary are pinned in
`resources/NAVIONICS_POINT_PROVENANCE.json`.

## Extended own plain declaration

```sh
no-chart-converter write-navionics-point-fixture --prefix type16-plain \
  --output ./own-plain-point.bin
```

This new profile emits 370 bytes. Its first Type16 record has a 254-byte payload
and occupies 258 bytes, followed by the directory at 258, header at 265, layer
at 320 and point at 349. The header points to that layer and the layer points
to that point; the complete 370-byte extent is declared at file offset 11.

The original `GetEncryptInfo` reads an unsigned 16-bit word at fixed file offset
144. In the accepted shorter fixture, that position is already outside its
metadata record. The extended profile reserves it within metadata and explicitly
stores our own zero word, which declares the three observed bit components
`[0, 0, 0]`. All added reserve bytes are own zeros. No reference file's descriptor,
keys, device identity, permission or success flag is copied or invented.

`encode_plain_metadata_record` provides this separately named raw-field encoder;
the original minimum-size encoder and both previously accepted output files
remain unchanged. The wire reader accepts only the fixed zero descriptor and
fixed preset. Its byte controls establish the word/reserve contents; the explicit
source store and original fixed-offset contract establish its field binding.
A zero store cannot be distinguished from another zero store within an all-zero
reserve by output bytes alone.

After the 0.2.19 source checkpoint, this exact 370-byte file passed the original
`GetEncryptInfo` reader with `[0,0,0]`, metadata, directory, point, both ends and
normal cleanup. That later finite result is bound in
`resources/NAVIONICS_PLAIN_METADATA_ACTUAL_ADDENDUM.json`; the frozen source-era
pending status is preserved. It does not assert original loader admission.
The original `LoadXF2` plain branch also requires a native object mode
established by its normal caller; a file's plain declaration does not set that
mode. The writer changes no original runtime state. Full collection discovery,
normal loader admission and rendering remain separate acceptance steps.

## One fixed three-point NoScale line

```sh
no-chart-converter write-navionics-line-fixture --output ./own-line.bin
```

This separate preset reproduces 197 own bytes with SHA256
`2c87a6a11d703bc0a5b5f83aae010acfd04e4e21fe243c7ebf6e37e4208916fb`.
It contains one Type32 line, class 29, flags zero, no attributes, one part and
the raw unsigned-16-bit coordinate sequence `(0,0), (1,2), (3,1)`. Its stored
raw bounding box is `[0,0,3,2]`. The original reader returned exactly those six
NoScale scalar values, then normal feature/layer ends and cleanup. That separate
original run does not establish world coordinates, scale, style or rendering.

The header, Bank52 carrier, layer and feature start at byte positions 45, 100,
142 and 171. The line's direct low-30-bit stream pointer is 127, with both high
mode bits zero. The stream stores its first raw pair, two `88` absolute-pair
commands and a zero terminator. The selected feature-end record is at 190;
the layer-end record at 164 remains plain. The own Bank52 prolog is retained
to reproduce the exact input. Its presence is not a proof that NoScale needs
it or that a bank/world-scale transformation has been established.

The pure `no_chart_converter.navionics.line` API provides
`build_three_point_line_fixture()` and `read_three_point_line_fixture(bytes)`.
The strict reader checks only this fixed layout, derives its raw point sequence
from the stream and reports typed pointers, bounds, part count and record spans.
It refuses other classes, flags, attributes, pointer modes, coordinate commands
or output points. These are the own preset's integrity rules, not a claim that
the complete original format rejects every other value.

The builder writes no geographic conversion, metadata-prefix combination,
multiple-feature collection, area, hole, depth or portrayal program. It executes
no native code, and the CLI reports that explicitly. Its own source, exact
accepted input and separate original NoScale result are pinned in
`resources/NAVIONICS_LINE_PROVENANCE.json`.

# Geometry load planning

`no_chart_converter.rx5.load_planning` adds read-only package accounting. It
accepts an existing open `region.Reader`; its caller retains the reader and
closes it normally. Authenticate that held file descriptor before indexing:

```python
from no_chart_converter.rx5.load_planning import (
    authenticate_reader_source, build_load_index, estimate_selected_requests,
)

binding = authenticate_reader_source(reader, expected_sha256=source_sha256)
index = build_load_index(reader, source_binding=binding)
report = estimate_selected_requests(
    index, selected_requests, source_binding=binding, byte_budget=8 * 1024 * 1024,
)
```

The authentication reads the full file to hash it without decoding it. The
binding includes size, UUID, device/inode/mtime and authenticated `ctimeNs`;
an earlier region index's four-field stat alone is insufficient. Subsequent
indexing reads block headers and prefixes only. Use an authenticated binding
for the current unchanged file generation on every planning call.

Each request is `(destination_tile, supplied_source_tile_or_None)`, with tiles
expressed as `(level, x, y)`. The module preserves the supplied selection,
including coarse sources, no source and explicit empty replacements. It does
not choose a source or infer hydrographic coverage/native scale from a node.

Reports separate repeated per-request geometry cost, distinct source-package
cost and the exact physical range union across shared index/coordinate streams.
Payload and block-header-inclusive sizes are separate. The byte budget applies
to `freshGeometryPackageBytes`; exceeding it reports an overrun and retains
every request. These are advertised geometry package sizes, not measured IO,
cache hits, hardware time or a complete metadata/resource download budget.

Country conversion and the browser tool do not activate this planner.

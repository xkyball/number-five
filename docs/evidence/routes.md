# Number Five — routes: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Karten und Routenplanung

Keep a local chart renderer while adapting established nautical portrayal and navigation standards. Separate chart display, offline cache, routes, AIS and backend authority.

### Recorded results

- MAP adapts original OpenCPN S52 GLES drawing code, pinned commit452a60d963b876bddd2f8e5eefe1c814d6e4e39c, alongside retained CPU regression path. No production ochartsng dependency.
  - Record: `no-apps/map/docs/SOURCE_PROVENANCE.json`
  - SHA-256: `3b89239f59dc0029f554f33f8608c327f1036e449d42abd43dfb0c892429d633`
- MAP106 scoped physical acceptance:19 styles and terrain on both devices; dynamic HTTP503 route errors shown correctly in English. Successful remote route save/delete NOT RUN because route provider returned503.
  - Record: `no-apps/map/verification/releases/map106-route-feedback-20261025/RESULT.json` · 2026-10-25
  - SHA-256: `3177b02f27e77af84c57988672877e5ef2c15c9753c127df3b7c677e2aa4d4ac`
- Current MAP source is110/0.5.42;512MiB resident-geometry budget candidate has host regressions PASS, physical tests NOT RUN, world underlay OFF.
  - Record: `no-apps/map/verification/releases/map110-memory-budget-20261026/CANDIDATE.json` · 2026-10-26
  - SHA-256: `cc7d66eabb600b79c0b6cad60dd42580072ab14fb3bab6fbdec6d49b2af69a74`
- Route backend uses Signal K resource endpoints and route schemas via own Gateway. UI/source presence does not erase current provider failure.
  - Record: `no-pi/projects/no5-pi-gateway/src/no5_pi_gateway/map_routes.py`
  - SHA-256: `01192e37bb7c033dce7e0f12870336e8fa6129722c18b54acbd310fcf00a7b45`

### Limits

- Current full M001–M120 functional retention audit is NOT RUN overall; public proof must name specific demonstrated behavior.
- No blanket arbitrary vector-format conversion or nautical portrayal equivalence established.
- Official encrypted-chart licensing/device acceptance stays separate from own integration publication.

### Credits

- OpenCPN
- GDAL/OGR
- S52/S57 standards
- NOAA historical Boston ENC test data
- Signal K
- Natural Earth / ETOPO where used; preserve exact dataset license


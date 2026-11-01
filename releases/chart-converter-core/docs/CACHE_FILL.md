# Offline background cells

`cache-fill` produces `no5-map-cache-v2`, comprising the complete unchanged
`no5-pi-map-cache-v1` payload plus independent public background cells. It never
uses a shop, device fingerprint, helper or original encrypted source. `pyshp`
reads the public polygons; Shapely/GEOS clips and triangulates them.

```sh
no-chart-converter cache-fill --source South-Africa-No5-MAP.zip \
  --land ne_10m_land-5.1.1.zip --output South-Africa-No5-MAP-filled.zip \
  --expect-sha256 ORIGINAL_ZIP_SHA256
```

Download [Natural Earth land, 1:10 million](https://www.naturalearthdata.com/downloads/10m-physical-vectors/10m-land/)
from `https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip`.
The command requires version **5.1.1**, archive SHA-256
`e547d749445eaa0964aba76738090ec88f5e63c4585122170f98c67a7ea922dc`.
[Natural Earth terms](https://www.naturalearthdata.com/about/terms-of-use/)
place the geometries in the public domain. The output retains attribution.

The grid origin is 0° latitude/longitude, with default 10° cells. South Africa
expands to longitude 0–40°, latitude −50–−10°: 16 background cells plus the
unchanged 56 nautical cells. Display dimensions do not reduce vector precision.
Dateline-wrapping and polar grids outside ±85° are rejected explicitly.

## Package and consumer contract

- The original `no5-pi-map-cache.json` and every nautical member remain byte
  identical. Their checksums, original provenance and cell identities survive.
- `no5-map-cache-v2.json` binds that original manifest hash, source archive,
  public-domain terms, complete grid bounds, and background cell checksums.
- Every `NO5BG_…` cell has `cells/NAME.senc` (OSENC 201) and
  `provenance/NAME.json`, provider `natural-earth-background-v1`. Public cells
  have **no** helper status, license host, product authorization or invented
  soundings, navigation marks, depths or survey attributes.
- SEAARE water, LNDARE land and COALNE coast retain polygon holes and islands.
  Coast derives before clipping; a tile cut must never become a coastline.
  Native polygon triangles are prepared offline, preserving polygon area.
- The matching MAP importer validates both manifests and preserves the two
  origins in one selectable collection. It must retain old v1 import support
  and leave the current selection unchanged after importing.
- Consumers must explicitly mark background sources. Nautical coverage wins
  **before scale sorting**, including at equal scales; background fills only
  remaining pixels. Original exclusions keep their native even-odd semantics.
  Never crop the public cells by guessed nautical bounding rectangles.
- SEAARE has no measured depth: only the explicitly marked background provider
  receives the neutral water fill. Original nautical S52 rules stay unchanged.
- Import a regenerated combined package as a new collection. The current
  incremental nautical update merge deliberately rejects combined collections
  before publication; it must not silently drop or relabel their background.

Archive writes are deterministic and published atomically only to a new output
path after CRC and complete original-byte readback checks. Larger private inputs,
build products and native render evidence belong on the designated work drive,
not in this repository. Device validation remains distinct from host/Pi tests.


## Optional real marine background (existing MAP v2 reader)

Add `--bathymetry ne_10m_bathymetry_all.zip` to include Natural Earth **4.1.0**
[SRTM Plus-derived bathymetry](https://www.naturalearthdata.com/downloads/10m-physical-vectors/10m-bathymetry/).
Pinned download: `https://naciscdn.org/naturalearth/10m/physical/ne_10m_bathymetry_all.zip`,
SHA-256 `9c5ea31402e5eb322a49f56136bfdc2732ab4e60c85720a4f83606f08088e5c2`.
It uses the same public-domain terms. Land remains version 5.1.1.

The converter interprets the source's nested depth thresholds at 0, 200, 1000,
2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000 and 10000 metres. It writes
non-overlapping DEPARE depth bands with DRVAL1/DRVAL2 and actual DEPCNT
isobaths with VALDCO. The deepest band has no invented upper depth. Contours
are derived before clipping; tile edges are never added as depth contours.
Deepest actual source coverage wins where generalized threshold outlines do
not nest perfectly. Land is removed from all marine geometries. Water without
source depth coverage remains neutral SEAARE; no point soundings are invented.

For compatibility with the already released strict v2 reader, the manifest's
existing primary land source fields stay intact. Every public cell provenance
additionally declares `bathymetry` with its separate archive hash, version,
source URL, source basis and depth levels; it never represents those depths
as land-only data or as licensed o-charts survey data. All public payloads and
complete provenance are bound by the manifest's existing hashes. No app update
or new release is needed for **MAP104's existing no5-map-cache-v2 importer**.
Earlier MAP builds without that importer are not claimed to support the bundle.
The existing renderer chooses S52 colours and contour styling; distinct deep
bands can share the same water colour. This is coarse reference bathymetry.

This option changes only the 16 public cells. Every original nautical payload
and its manifest remain byte-identical, and existing nautical coverage retains
priority. Import the output as a new collection; activation remains explicit.

## Optional land contours and named elevation points

The existing MAP104 S52 reader already understands LNDELV line/point features
with ELEVAT. No app changes, app release or device import is part of this export.
Install the converter's optional `terrain` dependencies, then fetch only the
required regional subset of [NOAA ETOPO 2022](https://doi.org/10.25921/fd45-gt74):

```sh
no-chart-converter fetch-terrain --bounds 0 -50 40 -10 \
  --output etopo2022-region-60s.npz
no-chart-converter cache-fill --source South-Africa-No5-MAP.zip \
  --land ne_10m_land-5.1.1.zip --bathymetry ne_10m_bathymetry_all.zip \
  --terrain-grid etopo2022-region-60s.npz --terrain-sha256 REGIONAL_SUBSET_SHA256 \
  --elevation-points ne_10m_geography_regions_elevation_points.zip \
  --expect-sha256 ORIGINAL_ZIP_SHA256 --output South-Africa-No5-MAP-terrain.zip
```

The fetch uses the official ETOPO 2022 v1 **60 arc-second bed-elevation**
OPeNDAP source, with a two-sample margin. It downloads the regional array,
not the global grid. The receipt records exact source indices and SHA-256.
Only WGS84 horizontal coordinates and EGM2008 heights in metres, positive up,
are accepted. NOAA distributes ETOPO under
[CC0](https://www.ncei.noaa.gov/access/metadata/landing-page/bin/iso?id=gov.noaa.ngdc.mgg.dem%3Aetopo_2022).
The grid is coarse (roughly 1–2 km here); it is background geography, not a
survey or detailed hiking map.

ContourPy's serial linear interpolation creates 250 m contours from actual
grid values. They are clipped against Natural Earth land before grid splitting;
holes stay empty and tile cuts never become extra contour lines. Missing
samples do not bridge contours. LNDELV lines retain their actual ELEVAT:
1000 m contours have SCAMIN 80 million, other 500 m contours 6 million,
and the remaining 250 m contours 1.5 million. The existing renderer determines
line colour and final scale visibility. No shaded or coloured relief is added.

The optional Natural Earth **5.0.0** elevation-point archive is pinned to
SHA-256 `c7465b5df43045c3b639ab26ce61128273c2391b597074077a55f06fe63e1c99`:
`https://naciscdn.org/naturalearth/10m/physical/ne_10m_geography_regions_elevation_points.zip`.
Actual positive source elevations, names and positions are preserved as
LNDELV points. Half-open tile ownership prevents duplicated boundary points.
The existing S52 point symbol/height label is used; no peaks or soundings are
invented.

Each public cell's extensible provenance binds separate `topography` and
`elevationPoints` source/version/license metadata; the strict v2 manifest schema
stays unchanged. Background terrain appears only where background cells own
the display. An original nautical cell's land is not replaced by this terrain:
all 56 original cells remain byte-identical and keep their existing priority.

## Reference display aspect (ZIP only, existing importer)

`cache-fill --view-aspect 2000 1070` constructs a viewing rectangle in the
renderer’s Mercator projection. It expands the shorter projected dimension,
never crops the original coverage and never changes vector resolution. Latitude
and longitude spans cannot be compared directly as pixel dimensions. The camera
latitude is the inverse of the projected midpoint, not the latitude average.

For the South Africa 0–40°E, 50–10°S grid, the exact reference frame is
**24.725907925°W–64.725907925°E, 50–10°S**. Its projected aspect is 2000/1070,
centred at **32.146701400°S, 20°E**, with 0.000200929348495 pixels per projected
metre at the reference size. A 2000×1070 viewport at that camera must have no
uncovered pixels; the native reader/renderer verifies all four frame corners.

The existing v2 importer requires complete globally aligned 10° cells.
Consequently the ZIP supplies **40** background cells across 30°W–70°E,
50–10°S, plus the unchanged 56 nautical cells (96 total). These complete edge
cells extend beyond the exact viewing frame. The package's outer grid bounds
must not be reported as an exact 2000:1070 rectangle. `displayFrame` in every
public cell's extensible provenance records the precise reference bounds and
camera; no new manifest fields or app changes are introduced.

The existing app does not automatically consume this provenance camera. Its
whole-catalog start view also adds padding. The user therefore sets the view
manually for now; this ZIP alone does not change that start-view behaviour.
Land, real bathymetry and coarse terrain cover the complete enlarged grid.
Fetching the enlarged ETOPO subset is bounded to 20 million samples/5000 square
degrees and the same 128 MiB expanded NPZ ceiling.

Equal-class, equal-attribute line parts within one tile/contour level share one
OSENC feature with multiple disconnected edge references. Edge-table bytes,
coordinates, heights and depths are preserved exactly; no simplification or
connector geometry is introduced. This reduces duplicate native feature objects
so the wider frame can fit the existing renderer's resident-memory reservation.
For this 40-cell background, the feature count falls from 21442 to 5555, with
identical native pixels in all seven tested views, including the full frame.

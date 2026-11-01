# Source and dependency notices

This private repository retains the project's own converter implementations and
their source-hash provenance. Existing source comments and attribution are kept.
No license is granted here for chart content, vendor runtimes or third-party
products; none of those inputs are distributed in this repository.

The SENC decoder records the existing MAP/OpenCPN spherical-Mercator convention.
The RX5 algorithms are this project's own host implementations. Native/OEM
libraries are optional external runtime inputs and are never copied into Git.

Shapely and its GEOS dependency, setuptools and optional test/native packages are
installed through their normal package distributions with their own license
notices. S57 CSV catalogs are supplied externally, preserving their source notices.
The repository does not bundle or relicense those catalogs or dependencies.

The optional S52 bridge source builds against an explicitly selected external
No5/OpenCPN source tree. The upstream source, runtime libraries, assets and their
license notices remain with that dependency; this repository ships the bridge
source, not those sources, binaries or reusable chart presentation assets.

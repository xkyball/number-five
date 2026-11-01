# Number Five — hardware: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Sensoren und Elektronik

Read the original trim sender with an Arduino and recreate the required sender behavior for the Yamaha trim gauge, while retaining fail behavior and source-level traceability.

### Recorded results

- Yamaha trim r3 Oct31: direct build123d/OCCT source,244 valid bodies,17 print-part sets,three STEP views; firmware host tests and AVR builds, copper/net/KiCad and mechanical checks run.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-10-31
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`
- r3 still records two failed SPICE reports and unsuitable LM358 manufacturer model; analog stability and5ohm target, actual fault timing, relay temperature margin and Yamaha operation remain OPEN.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-10-31
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`
- Sources, firmware,exports,SVG and short connection/check report delivered with advisory FAIL/OPEN; physical conversion, actual cuts and Yamaha operating acceptance not claimed.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-10-31
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`

### Limits

- Do not describe digital ERC or passive partial simulation as a complete working electronic system.
- Do not include household NO-Gate, ET600 or resistor scanner as boat modules.
- Existing material plans and possession are not assembly evidence.

### Credits

- Arduino / AVR tools
- KiCad
- ngspice
- build123d / OCCT

## Gehäuse und Einbau

Turn software and loose modules into mountable, serviceable assemblies: board location, fasteners, connectors, cable paths, lids, tools and printed parts must agree. Physical build is now the major unfinished work.

### Recorded results

- VenteriorV15-2 uses direct build123d0.13/OCCT, not a current Fusion-native build.17 unique print bodies, three assembly views, source-bound exports and SVG/copper checks exist; real fit/active electronics and missing materials remain open.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-11-01
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`
- Pi box PI-2L-5 has31 print bodies/15 types and saved geometry/toolpath checks. Actual print failed three carriers; Oct31 functional audit design_status=FAIL; digital_delivery_complete=false.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-10-31
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`
- Yamaha r3 exports and package exist but real assembly/boat mounting remain untested. Historical Fusion/native module results must retain their earlier source-specific scope.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-10-31
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`

### Limits

- Public narrative should include Fusion/cloud evolution and later direct build123d path, rather than presenting all active designs as generated in Fusion.
- A valid STL or passed slicer is not a successful print, real fit or operating qualification.
- No complete finished boat installation established.

### Credits

- build123d / OCCT
- Fusion for historical scoped designs
- KiCad / ngspice
- 3D slicer exact product per exported package


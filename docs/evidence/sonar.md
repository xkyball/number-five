# Number Five — sonar: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Eigenes Sonar

A specific Venterior fishfinder became an acquisition platform: capture analog echo on Pico, retain raw samples, control own transmit timing, render an echogram/A-scope and expose derived depth through a data boundary.

### Recorded results

- October18 finite real run:392 own starts,392 physical markers,392 raw links,372 usable pings; all associated. Pico firmware0.7.13,197kHz,4 periods,1000ns high time,2s lease. Sonard0.4.22.
  - Record: `no-pi/projects/no5-rebuild/evidence/sonard-continuity-0422-20261018.json` · 2026-10-18
  - SHA-256: `9d205635a63eef133308abe361ffe16a8f4c33d43627ba90c32e886dc670793b`
- 900.487s emulator display observation recorded usable5s rate minimum0.2Hz, maximum19.6Hz, mean14.1955801Hz. HELM121, actual live direct/Gateway frame matches13569, no payload mismatches; one2.486s Gateway pause and four stale frames. overall_pass=false. This supports an observed peak in this chain, not sustained20Hz boat performance.
  - Record: `no-pi/projects/no5-rebuild/evidence/sonard-continuity-0422-20261018.json` · 2026-10-18
  - SHA-256: `9d205635a63eef133308abe361ffe16a8f4c33d43627ba90c32e886dc670793b`
- Byte comparison covered512 original pings with2048/8192/16384 samples and unchanged wire fields/raw sample bytes. It is a bounded data-integrity check.
  - Record: `no-pi/projects/no5-rebuild/evidence/sonard-continuity-0422-20261018.json` · 2026-10-18
  - SHA-256: `9d205635a63eef133308abe361ffe16a8f4c33d43627ba90c32e886dc670793b`
- Current normal host does not require a raw archive. Optional test recorder has independent bounded buffers/budgets; recorder failure is separate from live sonar. PTY acceleration/replay results are explicitly not USB/TX/endurance qualification.
  - Record: `no-pi/projects/no5-sonard-openecho/README.md`
  - SHA-256: `6be0aa053c0c4e5e2914ebfd3090443b0828828abf36f10c79e3743809291adf`
- VenteriorV15-2 build package exists as of Nov1; nominal digital geometry/electrical subsets pass, materials missing and active electrical/real operation checks remain open.
  - Record: `no-pi/projects/no5-rebuild/tools/fusion-enclosures/evidence/project-progress-current.json` · 2026-11-01
  - SHA-256: `be92dec56130f8723c03e10ef7668e66d907fb3bbddee2debf93b7e1e88e029f`

### Limits

- Only the inspected Venterior path is evidenced; no compatibility with arbitrary cheap fishfinders.
- Peak usable rate is not identical to transmit rate, raw publication rate or sustained valid depth rate.
- No on-water calibration, full6h end-to-end acceptance or complete analog fault qualification inferred.
- Do not reinterpret synthetic20Hz GPU tests as physical acquisition throughput.

### Credits

- Raspberry Pi Pico SDK
- NumPy
- Own Sonard/OpenEcho sources; preserve origin intake and new publication license separately
- Venterior inspected hardware attribution


## Additional source reviewed by the site author

`no-pi/projects/no5-rebuild/PICO_DEPTH_RATE_20261016.md`

SHA-256: `86e7b9b9dc94ed17dbf398da9212d72f1831c8299ed920f304a52cce85cc167b`

The 16 October 600-second trial documented 8,807 own starts/physical markers/raw correlations, 8,502 usable LOCKED-depth pings, rolling 5-second usable rates 6.4–19.6 Hz and a 14.17 Hz run mean. It compared against a retained empirical reference 1.25175 m while the LCD showed 1.1 m; this is not absolute depth calibration. Later failed continuity criteria remain separate.

# Number Five — anc: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Aktive Geräuschreduzierung

Investigate cancelling dominant motor tones around a seat. Start with measured reference/secondary paths and a bounded regulator; do not promise silence across the boat.

### Recorded results

- Project proposes a Pico2 controller, measured microphone reference, error microphones and a separate audio output mixed with music. The first active phase is planning, not a confirmed build.
  - Record: `no-pi/projects/no5-active-noise-control/README.md` · 2026-10-19
  - SHA-256: `aa85d9d8b3ea319da6f8e189015cc1e7ef480b674899d27673c979a774a8bbc8`
- South African sourcing research Oct19 found original ADC chain incomplete at checked retailers; a PDM-microphone alternative was locally listed but requires new synchronized firmware. Listed coreR572.70 including VAT excluded shipping, installation, wind protection and integration.
  - Record: `no-pi/projects/no5-active-noise-control/SOURCING_SOUTH_AFRICA_20261019.md` · 2026-10-19
  - SHA-256: `9354aa71ce49dea45d40233e4a6f20516176181c97b7d685f8754b8502996533`

### Limits

- No measured boat noise reduction or completed regulator.
- Stock/prices are Oct19 observations, not current purchasing availability.
- Theoretical sub1ms electronics target and proposed6dB/3dB criteria are goals, not results.

### Credits

- Raspberry Pi
- Arm microphone-library-for-pico
- malacalypse/rp2040_i2s_example GPL3
- TI converter documentation
- FxLMS prior work


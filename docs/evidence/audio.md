# Number Five — audio: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Audio und Spotify

Music plays on the Pi. Android controls it over the LAN; the receiver owns true playback events and CamillaDSP owns system volume, channel routing and equalization.

### Recorded results

- Separate Rust receiver uses pinned librespot0.8.0, native Connect control and true one-second position events. Gateway confirms commands against fresh readback; browser/app clocks are not fabricated.
  - Record: `no-pi/projects/no5-spotify-connect/README.md`
  - SHA-256: `866fe8b7057ca287b2bd18f51779284f6d6bc3c42a985e6dd3b64b4b443a8388`
- October9 user confirmed selecting a song from search starts playback, using actual HELM61 and the active lab receiver. This was not a physical-Pi change or measured latency test.
  - Record: `no-pi/projects/no5-spotify-connect/evidence/music-selection-20261009/manual-acceptance.json` · 2026-10-09
  - SHA-256: `a6f8b3be49901a94b724b0717792f19b91a896bcdd9c20ac33c64210e064c4b0`
- CamillaDSP extension has16 peaking slots, fixed reserve, measured post-EQ samples, speaker alignment and bounded side reflections. Source/lease checks distinguish prepared targets from processed applied state.
  - Record: `no-pi/projects/no5-camilladsp/README.md`
  - SHA-256: `68efb4ab5217c38bd953db77226f6092d441f7134ffae6a0b3bb7c1aac45d93e`
- October20 physical Pi preset-library workflow PASS in targeted scope: create/save/reselect via native HELM132, exact original preset restoration and catalog survival across Gateway restart.
  - Record: `no-pi/projects/no5-rebuild/evidence/sound-preset-library-pi-20261020.json` · 2026-10-20
  - SHA-256: `25dcb2fa6d4bff90774b186c12ae44e716501c9918e63e90e99c7a41cc3f5a5b`
- October21 Pi EQ CPU matrix PASS includes16-point configurations and edit cases at5Hz requested cap. These are bounded CPU/edit-latency measurements, not acoustic-quality or whole-system stability certification.
  - Record: `no-pi/projects/no5-camilladsp/verification/cpu-eq-optimized-pi-20261021.json` · 2026-10-21
  - SHA-256: `7ecf0dded4a3074039dd6cb7a7c3d3f57608cfc92094bd86e17376f0720fc365`

### Limits

- Describe immersive4.1 as stereo-derived ambience; do not claim true source multichannel surround.
- A digital headroom budget does not prove absence of analog clipping or good boat acoustics.
- Receiver Cargo.toml already declares MIT; Camilla overlay is MPL2.0 and upstream dual licensed GPL3-only OR MPL2.0. Preserve those grants.

### Credits

- librespot
- Raspotify (original receiver path)
- CamillaDSP / Henrik Enquist
- ALSA


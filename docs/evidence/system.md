# Number Five — system: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Bordsystem und Daten

BBN Marine OS supplied the starting platform. Signal K remains the shared boat-data layer, AvNav an existing navigation component; No5 added explicit service boundaries and a shared gateway rather than treating the OS bundle as one integrated application.

### Recorded results

- Pi gateway exposes HELM, AUDIO and SONAR contracts; reads configured Signal K timestamps; combines CamillaDSP and a separate Spotify receiver, and refuses missing upstream data rather than inventing readings.
  - Record: `no-pi/projects/no5-pi-gateway/README.md`
  - SHA-256: `7f13f58bbebf8a80aaa69f2af8f28f2fbbbca23abbf1aa87d130f2b94c4a1166`
- MarineOS lab uses official BBN-FULL image corresponding to observed 2025-05-18 build; October 8 lab readback covered bridge, desktop, Gateway, GUI and six DSP outputs. It is a lab result, not an on-water acceptance.
  - Record: `no-pi/projects/no5-marineos-lab/README.md` · 2026-10-08
  - SHA-256: `c0063b6394131c14cfa940568b31c13221c70cb253aa5aac9655e58aefeeaac3`
- October 16 sound runtime capture succeeded, but completeSystemRestoreReady=false. Missing base restore inputs include vessel hardware profile, account cache and private identities; source availability alone does not prove full bare-metal restore.
  - Record: `no-pi/projects/no5-rebuild/evidence/sound-final-capture-pi-actual-20261016.json` · 2026-10-16
  - SHA-256: `4e914202783fd2dca91aaff2d70a3a8f08b300ed6d341350eacabf885edea929`
- GPS source diagnostics show three actual sources (USB gpsd and two Luminas) and an existing combined position. A separate read-only browser view was live-verified.
  - Record: `no-pi/projects/no5-rebuild/evidence/gps-live-map-first-install-20261023.json` · 2026-10-23
  - SHA-256: `4e1802bdc41008ea84c8a3140b58911bf92f7583dc78a94d31adda3b233c2233`

### Limits

- Home Assistant migration was not evidenced; present it as a considered next system base.
- Source timestamps and shared data do not establish display phase synchronization across devices.
- Do not infer active engine/steering/autopilot control from HELM telemetry.

### Credits

- BBN Marine OS / bareboat-necessities
- AvNav
- Signal K
- gpsd
- Debian / Raspberry Pi


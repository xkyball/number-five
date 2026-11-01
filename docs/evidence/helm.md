# Number Five — helm: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## HELM – Instrumente am Steuerstand

Build a readable instrument panel around data already available through the marine stack; separate measured source values, their freshness and visual interpolation.

### Recorded results

- HELM is native Kotlin/Compose, package com.no5.helm, Android 10/API29 minimum. Source build metadata is versionCode206, 0.8.134-sonar-start-depth-controls. This is source identity, not proof that206 is installed everywhere.
  - Record: `no-apps/helm/app/build.gradle.kts`
  - SHA-256: `cb0ef415a9a6bb346c62cc0ecaa2d5cd16bf726a3012520b0b46682695dcb057`
- HELM, AUDIO, SONAR and SETTINGS are native; network contracts are external. Instrument smoothing does not smooth source times, nulls or fault status. Original simulator is a separate process.
  - Record: `no-apps/helm/README.md`
  - SHA-256: `501b759c0229082a8efd910b0ef10d84cce8838008a67d97d6cfff052969708e`
- Sonar start-depth controls retain unavailable depths as empty and do not alter Signal K readings.
  - Record: `no-apps/helm/releases.json`
  - SHA-256: `d18abaabbc2d610bce6644805a60d4028db651a4d67b89e4b5b2e4444d2c5119`

### Limits

- No native iOS app or completed iOS migration evidenced. A port can reuse network service boundaries, but native presentation must be ported.
- Existing artwork notice records source provenance, not an independent license grant; preserve notices and use only authorized own imagery.

### Credits

- Jetpack Compose / Kotlin / Android
- David Li waves (MIT, adapted Stockham FFT and packing only)
- Barlow Condensed (SIL OFL 1.1)


## Additional source reviewed by the site author

`no-apps/helm/verification/releases/helm206-start-depth-controls/physical.json`

SHA-256: `5d274de33ca6804177fc8feae6da7a8c7f0f0797d1572a3706c333210585fdbe`

# Number Five — displays: evidence notes

Reviewed 1 November 2026. These are source-backed notes from saved records, not a new runtime or on-water test. Internal machine paths and runtime identities are omitted.

## Displays und Bedienung

Affordable large Android displays required work on root integration, shared header, window composition, focus, physical knobs, GPS, recovery and updates; the hardware integration proved more involved than installing APKs.

### Recorded results

- Lumina Control contains own root/focus/header/window and diagnostics sources with explicit dependencies on no-apps; OEM dumps, firmware and APKs are excluded from its source repository.
  - Record: `no-android/projects/lumina-control/README.md`
  - SHA-256: `17a0858994e6e1fe4c84d594ca00e55e62682c5f7aa4268524fd2dad416bbac4`
- MAP106 installed content verified on both physical Luminas. Scoped style/terrain and object-query proofs exist.
  - Record: `no-apps/map/verification/releases/map106-route-feedback-20261025/RESULT.json` · 2026-10-25
  - SHA-256: `3177b02f27e77af84c57988672877e5ef2c15c9753c127df3b7c677e2aa4d4ac`
- GPS5 reboot and original-packet transmission passed on physical Lumina; transport age remains a separate constraint.
  - Record: `no-pi/projects/no5-rebuild/evidence/lumina-gps-latency-20261018.json` · 2026-10-18
  - SHA-256: `d3d0e8043f4e0f79fca57018c4af4e411945d8b443826311e66da603b9a159f7`
- Control36 selected-profile WiFi recovery passed bounded disconnect cases; accepted=false overall because manual-off return/full lifecycle and Sonar acceptance remain partial.
  - Record: `no-pi/projects/no5-rebuild/evidence/lumina-network-recovery-20261027.json` · 2026-10-27
  - SHA-256: `28bf1bc3cffcbb883b0e46abecc50953cd8a7a52fc099e6081a20478f200fc24`
- After user WLAN changes, six short readings on October30 improved measured RSSI to median-74/-81dBm. No kernel errors/drops observed in that17s sample; not a long Sonar stability test.
  - Record: `no-pi/projects/no5-rebuild/evidence/lumina-wlan-after-reconfiguration-20261030.json` · 2026-10-30
  - SHA-256: `a90f3d6153633d4cc9d8faa57a610583a9188f4b89eb4ed598d7fce227eda255`

### Limits

- No calibrated display-brightness/cost comparison or manufacturer-support correspondence was verified in this pass. User experience can be labeled as such.
- No measured cross-screen synchronization bound found. Distinguish common data from frame synchronization.
- Do not generalize OEM-specific root and button behavior to arbitrary Android devices.

### Credits

- Android / Kotlin
- Lumina device-specific integration; manufacturer attribution must use exact device documentation


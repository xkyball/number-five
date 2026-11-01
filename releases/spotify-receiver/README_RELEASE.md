# spotify-receiver — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Complete current Rust Spotify Connect receiver,local control/state/media/EQ-volume code,Cargo.lock,own librespot patches,vendored librespot-connect source and public deployment templates.

## Use

Rust 1.85+ and a C toolchain; Linux ALSA build additionally needs pkg-config and libasound2-dev.

```sh
cargo test --locked
cargo build --locked --release --features alsa
```

The first command does not require ALSA playback. The second is for a matching Linux/Pi build host. Read DEPLOYMENT.md and the service template before adapting paths; never start competing receivers on one audio route. The optional librespot-core patch-preparation helper remains explicit and is not activated automatically.

Upstream: https://github.com/librespot-org/librespot/tree/v0.8.0 . Vendored connect LICENSE and NO5_PATCH.md are retained.

## Limits

- Spotify accounts/content are not included; live Spotify/ALSA behavior is not verified by source packaging.
- Cargo downloads pinned public dependencies on first clean build unless you provide a Cargo cache.
- Existing MIT licenses remain; other crate licenses apply to their own crates.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

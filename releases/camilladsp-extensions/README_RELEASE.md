# CamillaDSP extensions — Number Five source snapshot

Prepared 1 November 2026. Own Rust extension and EQ core retain MPL-2.0. Upstream CamillaDSP 4.1.3 commit 05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74 is pinned by download URL and archive SHA256 in upstream.json. Cargo.lock and eq-core/Cargo.lock retain dependency versions and checksums. The current own code is bound by per-file manifest hashes to its source repository commit.

## Included scope

no5_sound.rs, complete shared eq-core, the own Rust parity baseline, finite integration/preparation script and deterministic file/loopback probe scripts. No service is installed or controlled automatically. Historical verification, actual PCM recordings, private installed routing/profile state and compiled/vendor archives are omitted. The standalone kernel contains the required dependency closure of its own code. The full sound module builds against the explicitly pinned upstream tree.

## Build and test

Use current stable Rust with Cargo (edition 2024; preparation used Cargo 1.97.0), Python 3.12+ for tarfile's data-filter support, and the native build prerequisites from the pinned upstream CamillaDSP documentation. Build on the desired platform, independently of production audio:

```sh
cargo test --locked -j 2 --manifest-path eq-core/Cargo.toml -- --test-threads=1
python3 tools/prepare.py
cargo test --locked -j 2 --manifest-path .build/camilladsp-05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74/Cargo.toml --lib no5_sound -- --test-threads=1
cargo build --locked -j 2 --release --manifest-path .build/camilladsp-05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74/Cargo.toml --bin camilladsp
```

prepare.py fetches only the pinned upstream archive if absent, checks its SHA256, restores pristine integration files before patching, then adds the own module/kernel and exact Cargo lock. It writes only ignored .build/ in this copy. Cargo downloads its normal pinned dependencies when absent. Their licenses and Camilla's system requirements still apply. No upstream/vendor binaries are shipped.

The explicit optional PCM probes use aiohttp; install requirements-probes.txt in an isolated Python environment. They produce deterministic temporary PCM/files and use a loopback WebSocket on a selected isolated executable; do not point them at a production audio service. The original README shows the command arguments. Their numerical checks are digital tests, not acoustic measurements.

## Limits

This release does not reproduce a full boat audio installation. Gateway, Spotify receiver, Android interface, ALSA/device routing and private presets are separate inputs. Local source/kernel tests do not establish live Pi performance, current installed executable identity, real speaker level or clipping at the DAC. Historical reports are intentionally excluded rather than presented as new proof. No device, VM, Pi or GUI was accessed for this package.

See LICENSES.md and licenses/ for original license texts and attribution.

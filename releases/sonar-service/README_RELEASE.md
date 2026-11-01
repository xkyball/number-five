# sonar-service — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Current0.4.31 own Sonard/OpenEcho extension sources plus 20 original own M1 runtime members,the 6 hash-bound preserved own deltas,and a portable release-only build adapter. Original M1 Pico transport firmware source is included separately; current0.7.x TX firmware is not included.

## Use

Python 3.11+. The source-only wheel builder uses the Python standard library and preserves original source checks:

```sh
python3 -B build_release.py --output dist
python3 -m venv .venv
.venv/bin/pip install dist/no5_sonard_openecho-0.4.31-py3-none-any.whl
.venv/bin/no5-sonard-openecho --help
```

The wheel declares NumPy,SciPy,pandas,FilterPy,trackpy,FastAPI,Uvicorn and WebSockets dependencies; optional fast-cobs uses cobs1.2.2. It contains no Pico firmware image and does not start a live port. Do not run archived device/replay tests without separately supplying their original inputs.

firmware/m1-pico-transport is the historical original0.5-era transport source,not the later0.7.x own-transmit implementation. It requires the public Raspberry Pi Pico SDK supplied separately through PICO_SDK_PATH.

## Limits

- This is host source; current own-transmit Pico0.7.x firmware and board calibration/hardware profiles remain outside this package.
- Recorded-data/device-specific tests need recordings or running hardware and are not a clean-clone acceptance claim.
- No raw captures,archive state or real device identities are shipped.
- The original build script is preserved; use build_release.py to bind portable local source inputs.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

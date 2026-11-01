# gps-live-map — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Complete small read-only Python/Leaflet application,public installer/service,synthetic tests. Requires a compatible No5 Gateway position-source endpoint.

## Use

Python 3.11+ and Node.js for model tests. Runtime uses only the Python standard library; Leaflet1.9.4 is bundled under BSD-2-Clause.

```sh
python3 -B -m unittest discover -s tests -p 'test_*.py'
node --test tests/model.test.mjs
```

A real install requires an existing No5 Gateway and a local private credential. Read the original README.md first; do not run install.py on an unrelated host. Optional browser test requires Playwright and Chromium and runs headless.

## Limits

- Runtime accounts,Gateway secrets and real positions are not included.
- No on-water acceptance implied; release checks are reported separately.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

# venterior-v15-design — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Current V15-2 parametric CAD source and circuit-placement input,plus own electronics/wiring/check source for inspection. Portable adapter can reconstruct the exact nominal model using build123d.

## Use

Use Python 3.12 with the public dependency versions in dependencies/cad.lock.txt. No CAD desktop application is required.

```sh
python3.12 -m venv .venv
.venv/bin/pip install -r dependencies/cad.lock.txt
.venv/bin/python -B generate_cad.py
```

This writes new generated/STEP assemblies and individual nominal print models locally. It does not send a print or call Fusion. The original full project scripts are supplied for inspection; some electrical/check scripts require central helpers not bundled here. Private workshop/inventory integration is deliberately absent. The minimal parts input preserves the actual current 61 part definitions while excluding unrelated stock data.

## Limits

- Current missing materials,real purchased-part measurements,active electrical behavior and on-boat fit remain OPEN.
- This source package omits private inventory export,manufacturer PDFs/photos and all historical test evidence.
- Release adapter exports nominal geometry only; it does not perform the original full electrical/mechanical/slicing acceptance.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

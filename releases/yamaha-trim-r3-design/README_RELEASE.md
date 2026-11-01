# yamaha-trim-r3-design — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Current parametric CAD,design/net contract,AVR firmware and host-test sources. Four own nominal purchased-reference BReps are retained as required frozen geometry inputs; no manufacturer PDF/model is copied.

## Use

Python 3.12 and the public packages pinned in requirements-cad.txt. The CAD source uses build123d and bd_warehouse; the latter was installed with --no-deps in the original environment.

```sh
python3.12 -m venv .venv
.venv/bin/pip install build123d==0.13.0 cadquery-ocp-novtk==8.0.1.1.0 numpy==2.5.4
.venv/bin/pip install --no-deps bd_warehouse==0.4.0
.venv/bin/python -B src/contract.py
.venv/bin/python -B src/cad.py
sh firmware/tests/run.sh
```

First contract.py builds own stripboard/routing data; cad.py uses it to write new delivery/assemblies and print geometry. These commands are offline and do not print or flash. Arduino firmware compilation additionally needs Arduino CLI and the Arduino AVR core; physical clone/bootloader is not assumed. Full QA commands may need central tools and vendor analog models,so use the included source and known limitations instead of claiming complete QA.

## Limits

- Analog stability,5ohm output target,real fault timing,thermal margin and Yamaha operating acceptance remain OPEN; two current SPICE reference reports fail.
- Full QA references central native-tools and manufacturer SPICE inputs not bundled. The portable CAD path does not establish full electrical acceptance.
- No print,upload,inventory booking or on-boat action is part of this archive.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

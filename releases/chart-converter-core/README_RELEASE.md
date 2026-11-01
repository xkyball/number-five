# Chart converter core — Number Five source snapshot

Prepared 1 November 2026 from no-chart-converter 0.2.23. Per-file source hashes and the clean repository commit are in manifest.json. The original private source location does not restrict this reviewed own-source release.

## Included scope

Complete current portable Python runtime, package resources, own POSIX host/S52 bridge source, packaging metadata and self-contained invented-data tests. Historical hash-provenance files are preserved; historical source_snapshot is not an executable dependency of the installed core and is omitted. Optional native/OEM probe tool trees are omitted, with their tests listed in excluded-tests.json. No private chart/runtime input is supplied.

## Use

Python 3.11+; the preparation environment was Python 3.14.6. Run in this extracted directory:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-release.txt
.venv/bin/python -m pip install --no-deps -e .
.venv/bin/python -B run_release_tests.py
.venv/bin/no-chart-converter --help
```

requirements-release.txt pins the observed core/test distributions. pyproject.toml retains the original setuptools>=68 build contract; build tooling is not locked or bundled. Optional bitmap/terrain inputs use the separate requirements-optional-observed.txt pins. Native/OEM extras are not required for the core tests and are intentionally not installed by the test recipe. Package installation does not contact a chart service or start a native process.

The command help and original README describe explicit input/output contracts. Supply only inputs you are entitled to use and preserve their notices. The decoder needs external S57 CSV catalogs; country authoring needs explicit presentation resources and configurations. Input defaults do not include personal working paths.

## Actual limits

RX5 and Navionics are active interoperability work. Core tests demonstrate finite synthetic behavior, not completed chart rendering or safe navigation on a physical plotter. Optional S52 engine tests skip unless a separately built public engine/assets are explicitly selected; the release runner clears those selections. No device, Pi, VM or OEM renderer is started. The source package grants no license to third-party maps or vendor runtimes and makes no blanket legal claim.

See LICENSES.md for software/docs licensing and original NOTICE.md for dependency attribution.

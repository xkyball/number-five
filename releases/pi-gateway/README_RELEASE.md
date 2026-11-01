# pi-gateway — Number Five source snapshot

Prepared 1 November 2026. Base repository commit `c1f42c02dfb73eb67db8929c17f2f9b6478dfcd4` (2026-10-29T16:26:01+02:00); per-file hashes distinguish committed, changed and uncommitted own source. This is a bounded source snapshot, not the entire Number Five system.

## Included scope

Current complete own runtime package,build metadata,public examples,source helpers,synthetic contract fixtures and tests. Current untracked position-combination and sonar-forwarding dependencies are included.

## Use

Python 3.11+. Create an isolated virtual environment and install the exact runtime dependencies:

```sh
python3 -m venv .venv
.venv/bin/pip install -e '.[test]'
.venv/bin/python -B run_release_tests.py
.venv/bin/no5-pi-gateway --help
```

Copy config/no5-pi-gateway.example.toml to your own location; default producer is disabled. Source daemons,private credentials,TLS and hardware configuration must be supplied locally. Pytest-style source tests require pytest and can be run separately with `pytest tests/test_sonar_forwarding.py`;the unittest runner reports them as outside its scope. Core tests use temporary files and loopback fixtures only; they do not exercise a Pi. The 1 peer-path near-field vector test is preserved in source and excluded from the standalone runner; its synthetic peer fixture is bundled under peer-contracts/.

## Limits

- Separate Signal K,CamillaDSP,Spotify receiver,Sonard and HELM apps are not bundled.
- Actual installed private configuration,credential material,real runtime/evidence and hardware profiles are excluded.
- Archived original-failure replay test and external HELM integration need excluded inputs; use the release test runner for self-contained core tests.

Sources are retained unchanged except explicit release-only adapters recorded in manifest.json. Nothing installs, flashes or contacts a device automatically. See LICENSES.md.

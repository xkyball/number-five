# Pi-box R5 — own mechanical source and references

Prepared 1 November 2026. Current R5 Python/model source, current own contact contract, 200 exact frozen inherited own BReps, retained own converter STEP and predecessor tray reference are included. Every reference byte/hash is bound in manifest.json and required-inherited-geometry.json. These are project-owned nominal source models,not OEM CAD. No CAD rebuild was performed to package them.

## Two small source examples

```sh
python3.12 -m venv .venv
.venv/bin/pip install -r requirements-examples.txt
.venv/bin/python -B generate_examples.py
```

## Full nominal inner-stack source path

```sh
.venv/bin/python -B prepare_full_source.py
```

This release-only adapter relocates paths to bundled references,checks their hashes and invokes the unchanged original adapters/export_adapters/build_rest/export_rest sequence. It creates local output/ and delivery/ files;it does not print,flash,call Fusion or book inventory. Original nominal geometry/electrical contracts are unchanged. The full path was statically inspected but not executed during packaging;failures or missing environment support must remain explicit. The two small exact recipes were exported in an offline packaging check.

## Precise scope and known faults

This supplies the current nominal MECHANICAL source chain,including its formerly external own reference inputs. Frozen inherited references are editable BReps,not newly reconstructed parametric predecessors. Full electrical routing/report/slicer tooling still needs public library dependencies and original central helpers;the source-only package is not a full manufacturing pipeline.

The actual 31-part rest-print set had three carrier print failures,and the 31 October functional audit found mechanical/electrical faults. Real fit,fastener strength,flexible wiring,thermal/electrical operation and on-boat mounting remain open. Existing nominal geometry checks do not remove those faults. No ready-to-print or operational acceptance is implied by this source archive.

See LICENSES.md and per-file sources.

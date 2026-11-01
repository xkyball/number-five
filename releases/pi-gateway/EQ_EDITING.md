# EQ editing preview extension

The asynchronous sound endpoint retains its existing16KiB HTTP bound and64-admission/256-outcome rings. Sound capabilities add `eqProjection` and `experimentalEq`; both require confirmed native `no5.eq-editing/1` support. Legacy peers omit these flags and remain regular.

A preset `soundPreview` may include `eqEditing`: schema `no5.eq-editing/1`, boolean `experimental`, optional `previousPoint`, and matching optional `editedPointId`. The full requested model remains bounded to16 points. A point intent projects only that point along its requested path; bulk intent proportionally reduces all positive gains to the canonical native budget. Neither operation changes EQ enablement. The observed applied preset is authoritative, rather than the unclamped request.

The readback `eqEditing` object contains schema, previewProjection=true, maxExperimentalBoostDb=3, experimental, constrained, and normalPreset. Normal mode has null normalPreset. Experimental has a canonical normal fallback of identical identity, geometry, cuts and room with common positive gain scaling. This is runtime metadata, never a saved Preset field.

Experimental is admitted only within an owned preview with a maximum5-second native lease. `soundKeepalive` requires explicit experimental=true to renew an active experiment. Generic keepalives cannot extend it. Save, SaveAs and library mutations are rejected while confirmed experimental is active; direct clients cannot bypass that rule. A normal projected preview ends the mode before ordinary save/discard. The Gateway recovery journal and lease rollback remain regular models. Native authority/generation/revision checks and processed-block verification remain mandatory; unobserved preparation is UNKNOWN.

Proof: tests/test_sound_eq_editing.py plus the canonical Rust no5_sound::tests. No independent Python numerical approximation is used to decide the DSP ceiling.

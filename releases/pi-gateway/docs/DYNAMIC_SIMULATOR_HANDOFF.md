# Dynamic simulator handoff

## Delivered scope

The Pi gateway contains an opt-in, local-only display overlay. It is installed
in release `e88e3f0fee03c3cc80c101cc742004444d00756b`, but it is **disabled
on lysmarine**. It neither writes Signal K, CamillaDSP, the Spotify receiver
or a SONAR producer, and it has no AUDIO simulation path.

The feature is implemented in:

- `src/no5_pi_gateway/voyage.py` — deterministic voyage model;
- `src/no5_pi_gateway/simulation.py` — strict settings, persistence and
  HELM/weather overlays;
- `src/no5_pi_gateway/simulation_ui.html` — loopback operator page;
- `src/no5_pi_gateway/service.py` / `state.py` — exclusive M0/M1 source
  switching.

## Model provenance

`voyage.py` is a port of the existing No5 HELM simulator at
`projects/no5-helm-audio-client/helm/simulator/engine.py`. It preserves the
Figure-Eight route, arc-length traversal, 50 ms integration, phase sequence,
twin-engine/fuel dynamics and geodesic position projection. It is deliberately
labelled as an illustrative display model, not navigation, engine, tank,
weather or acoustic evidence.

The old HELM simulator's `scene_weather.py` is a static scene, so it was not
used as a replacement forecast. The gateway instead derives all nine
simulated weather values deterministically from the voyage clock and position.
This makes a selected weather field change over time while an unselected field
remains real, stale or unavailable.

## Operator API and local UI

The only control surface is loopback-only and requires the existing separate
simulation bearer token:

| Method/path | Purpose |
| --- | --- |
| `GET /admin/simulation` | local HTML control page |
| `GET /admin/v1/simulation` | control state, available sources and dynamic preview |
| `POST /admin/v1/simulation` | strict revision-bound apply request |

Nginx denies `/admin/` on the LAN/TLS listener. The request is an envelope
with `version: 1`, `serverSessionId`, `expectedRevision`, an idempotent
`requestId`, `action: "apply"`, and a complete `settings` object. There is no
Android-visible new gateway API and no runtime upstream URL input.

The local page supports individual HELM/weather checkboxes, voyage run/pause
and time scale, and `INHERIT`/M0/M1 source choice. The full persisted settings
object additionally contains bounded phase, route, speed, acceleration, trim
and starting-fuel fields; the API validates every key exactly. State is stored
atomically in the configured local state file, version 2. Invalid or removed
source configuration makes persisted selection fail closed to defaults.

### Field overlay semantics

HELM may select any of these values independently:

`portRpm`, `starboardRpm`, `portTrim`, `starboardTrim`, `portFuel`,
`starboardFuel`, `heading`, `speed`, `depth`, `waterTemp`, `portVoltage`,
`starboardVoltage`, `houseVoltage`, `portCoolantC`, `starboardCoolantC`,
`portOilBar`, `starboardOilBar`, `portFuelFlowLph`,
`starboardFuelFlowLph`, `totalFuelFlowLph`, `portGear`, `starboardGear`,
`latitudeDegrees`, `longitudeDegrees`, `fuelRemainingLiters`,
`courseOverGroundDegrees`, `tripDistanceNm`, `tripDurationSeconds`,
`fuelUsedLiters`, `efficiencyNmPerLiter`.

Latitude and longitude must be selected together. A selected value receives a
fresh simulated quality timestamp. An unselected value is copied untouched
from the real HELM projection and therefore remains real, stale or unavailable
as applicable. As V1 has no per-field source provenance, any mixed HELM
envelope is intentionally marked `sourceKind: SIMULATED` and includes
`simulation` metadata; `fieldSources` in the control state records the
per-field selection.

Weather may independently select `airTemperatureC`, `windSpeedKn`,
`windDirectionDegrees`, `relativeHumidityPercent`, `pressureHpa`, latitude,
longitude, `cloudCoverPercent` and `visibilityMeters` (again, position is a
pair). Non-selected real forecast values are retained rather than fabricated.

AUDIO is not present in settings. It stays a CamillaDSP/receiver-derived real
or unavailable input.

## SONAR M0/M1 semantics

The gateway does **not** generate a local waveform, binary frame, range scale
or bottom value. It can select one exclusive, already deployed upstream daemon:

| Mode | Meaning | Admission |
| --- | --- | --- |
| `INHERIT` | Existing configured producer | no source change |
| `M0` | Existing M0 simulator daemon | only if a fixed M0 base URL is provisioned, or source is already M0; upstream status must report `source: synthetic`; published as `SIMULATED` |
| `M1` | Physical Pico daemon | only if a fixed M1 base URL is provisioned, or source is already M1; upstream status must report `source: pico`; published as `REAL` raw transport |

The optional fixed bases live only in service TOML:

```toml
[simulation]
sonar_m0_upstream_base_url = "http://127.0.0.1:8090"
sonar_m1_upstream_base_url = "http://127.0.0.1:8091"
```

They are not mutable through the UI/API. A change stops/cancels the old
read-only `SonardUpstream`, resets the local source generation and starts one
new upstream reader. Frames from two sources cannot interleave. Command paths
remain readback/rejection-only as before.

On the deployed Pi both entries are commented out and simulation is disabled,
so M0/M1 cannot be selected accidentally.

### Observed lysmarine producer state (read-only, 2026-10-07)

`no5-sonard.service` is the active M1 daemon. The configured and observed
local endpoint is `http://127.0.0.1:8787`; its `/api/v1/status` reports
milestone `M1`, `source: "pico"`, `state: "WAIT_PICO"`, no connected Pico
device and zero pings. This is correct unavailable evidence, not a gateway
fault or a synthetic fallback.

`no5-sonard-m0.service` is installed but disabled/inactive. Its unit invokes
the same daemon binary on the **same port 8787** and is therefore an
alternative producer, not a concurrent stream. The local simulation UI never
starts/stops either unit. A future M0 run must first make the daemon switch
exclusive at the service layer, then provision the fixed M0 gateway base. Do
not start both units together: their port declarations conflict. No unit or
simulation setting was changed while collecting this evidence.

## Verification

`make test` passed with **64 tests**, with 6 expected optional skips in the
local environment. Simulation coverage includes:

- dynamic selected HELM values while unselected real/stale/missing values are
  preserved;
- missing real values stay missing when not selected;
- field-mixed dynamic weather rather than a static scene;
- M0/M1 fixed-daemon selection and rejection of an unprovisioned mode;
- persisted settings, coordinate-pair validation and idempotent commands;
- no AUDIO entry in simulation settings.

The release was deployed as
`/opt/no5-pi-gateway/releases/0.1.0-e88e3f0`. The installed
`simulation.py`, `service.py` and `voyage.py` match the commit byte-for-byte:

| file | SHA-256 |
| --- | --- |
| `simulation.py` | `3dedb893998f0e50890a1376b9e49596cc1632060a9002ea10a0eab258134017` |
| `service.py` | `4871bc87acedc50f1c9d21bf0de2fe9a4195226bf5efbe35a7b506c11894432a` |
| `voyage.py` | `a6b35e1ca0373e2a394026724436d63bb1d41ed992d267a1c52c0ec4c9b45f79` |

## Deployment wording correction

The first planned deployment target was audio-focused commit `ef396a5`; its
isolated test gate failed and **it was not deployed**. After the readback test
correction, the successful release target became `e88e3f0`, which includes
both the audio fix and this simulator work. Therefore describing the actual
`e88e3f0` deployment as "audio-only" was incorrect. The active release and
the file hashes above are the authoritative evidence.

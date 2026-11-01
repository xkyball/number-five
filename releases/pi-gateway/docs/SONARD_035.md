# Sonard 0.3.5 adapter

The existing `m1` producer slot supports `no5-sonard-openecho` 0.3.5 without a
second Android API. Actual package identity comes from status `host_package`,
not the inherited top-level M1 version or health/OpenAPI version.

The binary frame parser preserves the exact 2048 display-sample bytes. A NaN
range is permitted as unverified geometry; infinities/nonpositive finite ranges
remain malformed. For an enhanced host, a reconnect-scoped queue holds at most
32 frames. A single worker reads the existing host endpoint
`GET /api/v1/detection/latest` with a 100 ms request bound and 256 KiB response
bound. It attaches analysis only if Detection and Presentation share the
frame's exact ping ID and UTC nanosecond time. An older frame skipped by the
latest-analysis endpoint is released with unknown canonical bottom; an
otherwise unmatched frame waits 500 ms plus at most one request. Stop/reconnect
cancels the worker and discards the old queue.

The bounded Android V1 projection contains:

| Field | Meaning |
| --- | --- |
| `geometry` | Actual display range, host/empirical/unverified TX reference, original display flags, separate ADC period if same-ping analysis exists |
| `presentation` | Same-ping quality/reason, effective filter and translated range/freeze recommendation; null if unavailable |
| `bottom` | Canonical selected bottom mapped by physical depth into the actual display range |
| `legacyBottom` | Independent header detector, never a substitute for missing enhanced analysis |
| `hostPackageVersion` | Actual Sonard package identity |

Display spacing is range/1023. The 0.3.5 full-window `display_bin` and range
recommendation are translated using their physical-depth ratio, so a nonzero
TX index does not shift the selected depth into the wrong display bin.
Confirmed track sources accept their heuristic confidence below 0.75; raw
selection retains its own 0.75 rule. The selected track must be confirmed in
the same-ping Presentation. No fixed 32-track limit is imposed: 32 bounds raw
pending frames, and the HTTP byte bound handles oversized analysis safely.

Bit 7 identifies empirical fallback. It is not independent absolute TX
calibration. Missing/ambiguous TX or NaN range yields no meter axis. The
producer's raw/archive samples and original flags remain untouched.

This adapter adds no host or Gateway acquisition writes. `canStart/canStop`
remain false. Display gain/range/freeze do not change TX/ADC settings. The host
filter/context/label API remains a separate contract; no speculative HTTP
frequency, power, ping-rate, general CFAR or recording command is generated.

Host tests exercise actual aiohttp HTTP/WS, delayed/failed analysis, strict
identity matching, low-confidence confirmed tracks, nonzero-TX geometry and
unchanged sample bytes. Emulator fixtures are synthetic and test the real
Gateway adapter, Android WS/decoder/source, controller and native UI. They do
not substitute for a running 0.3.5 Pico or physical Lumina acceptance.

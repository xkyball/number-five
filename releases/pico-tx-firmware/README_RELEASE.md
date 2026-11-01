# OpenEcho Pico TX source

Prepared 1 November 2026. Actual CMake/session source contains default finite-transmit0.7.0 and NO5_CLIP_DIAGNOSTIC=ON0.7.13-reference-median (HELLO minor7/patch13 and expanded capabilities). The old README banner0.7.0 was stale.

GPIO16 is marker input,GPIO26 ADC0;transmit uses explicit policy/profile/lease and GPIO22,the diagnostic profile also GPIO18. Source availability does not certify analog circuitry,current limits or boat operation.

## Offline host tests

Python 3.11+,pytest and cc. Tests compile C policies against own SDK stubs and simulate PIO;they do not open USB or flash.

```sh
python3 -m venv .venv
.venv/bin/pip install pytest
.venv/bin/python -m pytest firmware/tests -q
```

The sole source adaptation replaces the private parser path with the bundled exact own parser in sonar-parser/. Predecessor session.c exists only for source-compatibility tests. The separate sonar-service package contains the full current host.

## Native build (not performed during packaging)

Supply public Raspberry Pi Pico SDK and ARM GCC/CMake explicitly. Original source does not pin the SDK revision;record your chosen commit when reproducing. No auto-download.

```sh
cmake -S firmware -B build -DPICO_SDK_PATH=/absolute/path/to/pico-sdk -DNO5_CLIP_DIAGNOSTIC=ON
cmake --build build --parallel 2
```

Omit the option for default 0.7.0. This does not flash. Electrical profiles,full oscilloscope app,real continuous-run acceptance and measured board calibration are separate.

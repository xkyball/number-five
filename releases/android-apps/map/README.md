# No 5 native Android Helm — foundation development source

Current ARM64 test build: **0.4.20-window-profiler**. The five native screens
share one persistent GLES renderer. Chart styles and renderer diagnostics are in
Settings. Minimum Android runtime: **Android 10 / API 29**.
See [native screen integration](docs/NATIVE_SCREENS_ANDROID10.md).
The latest targeted header/rail candidate composes the GLES chart through one
TextureView in the app window. Lumina UI acceptance remains pending; see
[composition changes and device check](docs/LUMINA_SHELL_SURFACE.md).
Fullscreen is reapplied on Android 10 window focus and resume; see
[status-bar lifecycle repair](docs/IMMERSIVE_LIFECYCLE.md).

**Version 0.4.2-surface-timing: Android uses the original OpenCPN S52 GLES branch to draw chart geometry directly into an EGL framebuffer.** Pan, pinch and rotation remain touch-driven. Four adjacent NOAA Boston harbour cells are included. The CPU renderer remains for regression comparison. Host GLES evidence uses Mesa llvmpipe; physical-device speed and the independent upstream reference-image comparison remain pending.

Open this directory in Android Studio with JDK 17. Install SDK 37.0, build tools 36.0.0, NDK 29.0.14206865 and CMake 3.31.6. The minimum runtime is Android 10. `com.no5.map` is the application ID; the internal Kotlin namespace remains `com.no5.helm` for source compatibility. Source inputs and reproducible port edits are included; no AVNav/ochartsng fork is required.

Build the production APK:

```bash
clear
./gradlew :app:assembleDebug :ui-shell:testDebugUnitTest :helm-model:testDebugUnitTest
```

Build a separate emulator APK:

```bash
clear
./gradlew :app:assembleDebug -Pno5.abis=x86_64
```

Run the backend simulator (Python 3.11+):

```bash
clear
python3 -m venv simulator/.venv
simulator/.venv/bin/pip install -r simulator/requirements.txt
simulator/.venv/bin/python simulator/server.py --host 0.0.0.0 --port 8765
```

Android emulator connects to `http://10.0.2.2:8765` by default. On the physical Android display, use Settings → Karte / Offline → Diagnose · Helm-Verbindung to enter the simulator computer or Pi's LAN address. The same client/schema is used for every endpoint. A conforming boat-side service must implement `docs/BACKEND_CONTRACT.md`; this execution did not deploy one onto the boat.

Operator scenario examples, from another terminal:

```bash
clear
python3 simulator/simctl.py gap
python3 simulator/simctl.py disconnect --seconds 8
python3 simulator/simctl.py ownship --depth 11 --speed 0
```

Native verification (Linux, CMake 3.22+, C++17 compiler, EGL and GLESv2 runtime libraries; Mesa supports the offscreen checks):

```bash
clear
python3 scripts/verify_provenance.py
python3 scripts/regenerate_opencpn_port.py --check
cmake -S chart-engine -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native -j 4
ctest --test-dir build/native --output-on-failure
simulator/.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
```

The default is a real Boston harbour S57 dataset from NOAA, downloaded 2026-10-03. Four adjacent cells at 1:12,000 include their sequential updates and ancillary notes. Chart display and gestures do not require the boat backend. One finger pans; two fingers spread/pinch to zoom and rotate. Normal touches never query chart objects. Choose Object info, then tap a chart object for one query; navigation does not trigger it. Pending viewport updates coalesce to the latest requested view rather than queuing every input sample. See `docs/LOCAL_SMOKE.md` for the short device check and compiled JNI/GLES Android instrumentation test. The tiny GDAL regression fixture remains available with the `chart=fixture` Activity intent extra. Actual chart synchronization and official o-charts device acceptance remain later work. See `docs/ARCHITECTURE.md`, `docs/CHART_ENGINE.md`, `docs/SOURCE_PROVENANCE.json`, `docs/TEST_PLAN.md` and the execution report for the exact implemented boundary.

The direct GLES target does not upload a CPU chart framebuffer. Its single GPU color attachment represents the continuous view; atlas symbols, cached text strings and pattern textures are retained OpenCPN portrayal resources. See `docs/GPU_PORT_STATUS.md` for the port and its current verification boundary. To run only the retained CPU regressions, configure with `-DNO5_DIRECT_GLES=OFF`.

## Renderer audit build 0.4.1

The **Renderer** button reports the actual EGL/GL identity and successful frame
submissions, distinguishing known software/translated drivers from unverified
hardware candidates. See [GPU audit and chart styling](docs/GPU_AUDIT.md) for
source paths, fresh test evidence and the required physical-device check.
Deliver ARM64 only: `./gradlew -Pno5.abis=arm64-v8a :app:assembleDebug`.

## Phase 0: Surface sizing and wall-time diagnostics

Chart viewport and FBO now follow the actual Surface dimensions at the two
2000-pixel target sizes; larger surfaces use a bounded, uniform aspect fit.
The Renderer dialog separates render/submit wall time, swap wait and present
interval, and explicitly marks GPU execution time as unmeasured. See
[Phase 0 validation and Boston baseline](docs/PHASE0_SURFACE_TIMING.md).

Renderer capture controls and measurement limits: [docs/RENDERER_CAPTURE.md](docs/RENDERER_CAPTURE.md).

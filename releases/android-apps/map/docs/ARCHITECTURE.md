# No 5 native Android Helm foundation

The Android shell, typed Helm client and simulator are implemented. The native chart engine now retains the complete OpenCPN S52 source with CPU and GLES branches, original S57/OSENC 201 geometry operations and original chart-stack selection. **The foundation acceptance gate remains open because Android device smoke and wider chart/reference validation are still pending.**

Compose hosts a ChartSurfaceView. That same native View owns the full Android MotionEvent sequence from DOWN to UP/CANCEL, tracks stable pointer IDs, processes historical samples and applies the complete first movement. There is no separate transparent Compose touch layer. Object information is an explicit one-shot mode: ordinary navigation taps are silent, and a drag or multi-finger gesture never queries objects. The native render worker owns JNI/EGL; pending viewport commands coalesce to the latest view before each frame. The No5 C++ facade drives S57 → SENC → original S52 GLES portrayal into one continuous GPU color attachment matching the Surface at the supported target sizes (2000×1116 or 2000×1200), with a bounded uniform aspect fit for larger Surfaces. A final GPU draw presents it to the Android Surface. No complete CPU chart image is allocated or uploaded in the Android path. The CPU target remains for regression comparison. No visible OpenCPN/wx surface, wx application shell, raster tile path, AVNav or ochartsng runtime is linked.

| Module | Responsibility |
|---|---|
| app | Activity, asset installation, endpoint configuration |
| ui-shell | Surface, pan/pinch/rotation/reset, palettes, connection banner, feature panel |
| chart-api | Kotlin contract with no OpenCPN types |
| chart-engine | JNI, original OpenCPN S52 GLES/S57/SENC/selection, EGL framebuffer, CPU regression target |
| helm-model | Immutable typed projection and revision reducer |
| helm-client | HTTP snapshot, WS replay, resync and reconnect |
| simulator | Same Helm v1 contract and separate operator CLI |
| tests | Native CPU/GLES fixture checks, Android MotionEvent + Compose/AndroidView JVM checks, Kotlin reducers, HTTP/WS integration |

The backend owns navigation state. Backend loss retains the last projection and enters DEGRADED. A delta revision gap enters SYNCING and triggers a fresh snapshot with atomic state replacement. Simulator and boat endpoints use the same wire schema and client; no simulator-specific product branch or on-device navigation authority exists.

OpenCPN pin: `452a60d963b876bddd2f8e5eefe1c814d6e4e39c`. Original and adapted-source provenance are included. The initial partial S52 slice is replaced. Utility wx call slots are implemented through No5 value/file/memory-DC types, without wxWidgets runtime. See CHART_ENGINE.md for exact source boundaries and port repairs.

Android minimum API 33 follows the user's Android 13 requirement and tightens the blueprint's older API 24 floor. Target SDK is 36; compile SDK 37; production ABI arm64-v8a. A separate x86_64 APK supports emulator testing. Build pins: AGP 9.4.0, Gradle 9.6.0, NDK 29.0.14206865, JDK 17 and CMake 3.31.6. Application ID is `com.no5.helm`.

The first chart selection chooses the most detailed center-covering cell to set the portrayal scale. Adjacent opened cells at that scale render together in one continuous frame. Full OpenCPN quilting of overlapping different-scale cells and complete database/cache indexing are not part of this adapted slice. Geometry caches rebuild on open; persistent cache reuse and chart sync remain subsequent work. Font/platform raster equivalence and broader conditional-symbology behavior require reference-image validation. Official o-charts and real Pi/Signal K deployment remain separate integration steps; neither was deployed in this execution.

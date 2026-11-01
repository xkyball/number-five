# Number Five — Android source snapshot

Prepared 1 November 2026 from no-apps base commit `18b5209cac152a4efed0f783bfc963ffefaa57e4`,including current own changed/uncommitted source with per-file hashes. Main app source metadata:HELM 206,MAP 110,Launcher 31. No APK,private signing material,OEM library,runtime database,recording or geographic chart file is included.

## Included

Native HELM/MAP/Launcher application sources;required shared UI/header/control/gateway/release modules;own current Sonar JNI code;MPL shared EQ Rust/JNI source;OpenCPN renderer slice and original third-party notices;active fonts/art/shaders;official Gradle wrapper jars. The exact own Lumina protocol source required by the build is bundled under gateway-config/pinned/ and checked against gateway-config/lumina-wire.json. This is not the complete Lumina root runtime.

## Source checks

```sh
python3 -B verify_source_bundle.py
```

This validates all bundled source hashes,the exact wire pin,the shared-EQ source/helper pins,and all OpenCPN/third-party source entries. Charts are deliberately absent;the original map/scripts/verify_provenance.py additionally demands NOAA assets and therefore is not the source-only release check.

## Build prerequisites and commands

Use JDK 17 for HELM/Launcher and the JDK supported by MAP’s pinned Android Gradle Plugin9.4.0 (the original environment uses the project wrapper). Install SDK 35 and build-tools35.0.0 for HELM/Launcher,SDK 37 for MAP,NDK 29.0.14206865 and CMake 3.31.6. Shared EQ additionally requires the exact rustc version declared in shared-eq/MANIFEST.json,the aarch64-linux-android Rust target and the pinned public Cargo dependencies already cached because its source-bound helper builds offline. Do not loosen pins silently. Gradle public dependencies can be downloaded normally. Set ANDROID_HOME or your own local.properties;none is shipped.

Release metadata deliberately requires a Git working tree. For a downloaded ZIP,initialize Git in this directory and commit the snapshot using your own configured identity before building. A Git clone of the eventual source repository already has this.

```sh
git init
git add .
git commit -m "Import Number Five source snapshot"
cd helm
./gradlew --max-workers=2 -Pno5LuminaWireInput=../gateway-config/pinned/KnobLeaseProtocol.java :app:assembleDebug
cd ../map
./gradlew --max-workers=2 -Pno5LuminaWireInput=../gateway-config/pinned/KnobLeaseProtocol.java :app:assembleDebug
cd ../launcher
./gradlew --max-workers=2 -Pno5LuminaWireInput=../gateway-config/pinned/KnobLeaseProtocol.java assembleDebug
```

These instructions build locally and do not install,flash or open an emulator. No Android rebuild was performed during packaging. Android app startup without supplied charts is not a demonstration of chart rendering. Tests that need geographic/recorded fixtures,real services,OEM/root integration or actual device credentials remain outside this source-only check;their source may be retained for inspection. Existing production installations use another signing identity,so do not treat a locally signed debug build as an in-place production update.

## Limits

The complete boat system,full Lumina root/controller implementation,source chart datasets and physical installation are separate components. Current functional limits in the project research still apply;source packaging is not full app acceptance.

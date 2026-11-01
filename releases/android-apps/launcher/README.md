# No5 Launcher

Eigenständige Android-App mit unveränderter Paket-ID `com.no5.home`. Der
Quellstand stammt aus `xkyball/no5` bei
`c7c4e451042bd9c5ca53210762ae256544147934` und wurde aus dem historischen
Gesamtprojekt nach oben verschoben.

Der Launcher enthält seine Navigationserklärung lokal, damit `./gradlew
testDebugUnitTest assembleDebug` ohne Abhängigkeit auf den alten No5-Repo-Root
ausgeführt werden kann. Er startet ausschließlich überprüfte Komponenten und
ändert keine OEM-Home-Einstellung, keine Signatur und keine Systemkonfiguration.

Die vorhandenen Rendernachweise und Home-Dokumentation liegen in
`verification/` und `docs/`. Sie sind keine Geräteabnahme.

Die Revision `0.3.1-clearance` verwendet die gemeinsame 2000×1200-Bühne,
130-px-Header, horizontale Icon-Footerleiste und geschützte Knopfecken.
2000 px auf 237 mm Displaybreite ergeben 43 px Sicherheitsabstand.
HELM/MAP, Alle Apps, Einstellungen, Wartung und Tag/Rotnacht bleiben native
Steuerelemente. Details: [HOME_CLEARANCE.md](docs/HOME_CLEARANCE.md).

Der lokale Ausgangsnachweis für `0.3.0-clearance`/Build 4 (APK SHA-256
`c28d7d76d3844b059737d080675a4edaed471bbdf0a7500424357dfdd0a6d789`)
ist bytegenau erhalten. Die integrierte Auslieferung erhöht nur die Version
und bewahrt Geometrie, Layout und Navigation.

36 erfolgreiche Tests auf Android 10/15 sowie Build und Debug-Lint
(keine Fehler, sechs Warnungen). API 33 prüft zusätzlich reale Touchbedienung
und unveränderte Android-Home-Zuordnung. Keine Installation auf dem Lumina.

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
python3 verification/verify_emulator.py
```

APK: `build/outputs/apk/debug/No5Launcher-debug.apk`. Der Emulatorprüfer ist
auf `emulator-5554` begrenzt und benötigt Pillow und ADB.

## Alle Apps · 0.3.2-app-grid / Build 6

Die sekundäre App-Ansicht verwendet ein natives Raster mit fünf Spalten,
Android-Appicons und ausschließlich dem Anzeigenamen unter dem Icon.
Namen umbrechen auf höchstens zwei Zeilen und werden danach ellipsiert.
Die Kachel ist vollständig bedienbar; Scrollpositionen bleiben bei
Tag/Rotnacht, App-Rückkehr und Activity-Neuerstellung erhalten.
Details, Basis und lokale Abnahme: [APP_GRID.md](docs/APP_GRID.md).

Die zusätzlich freigegebene Knob-Korrektur vergrößert ausschließlich die
schwarze Innenfüllung um 3 mm im Radius (25,316 px bei 237 mm / 2000 px).
Außenkontur, 43-px-Sicherheitsfläche und Homescreen-Anordnung bleiben erhalten.

```sh
./gradlew --offline --no-daemon testDebugUnitTest assembleDebug lintDebug
python3 verification/build_grid_fixture.py
python3 verification/verify_app_grid_emulator.py --serial emulator-5556 \
  --baseline-apk /path/to/verified-build-5.apk \
  --fixture-apk build/app-grid-fixture/fixture.apk
```

Die neue Prüfung akzeptiert ausschließlich lokale Emulator-Seriennummern.
Die QA-Testfixture und ihr Zustand sind getrennt von Produkt-Apps und werden
nur im dafür gestarteten Emulator installiert. Physische Knobeingaben sind
weiterhin nicht integriert.

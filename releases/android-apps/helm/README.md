<!-- Ocean weather stage: source version 0.7.4 / Build 14. Stage1 Ocean Preview remains 0.7.1-stage1 / Build 11. -->
# NO5 Native Android 0.7.4

Native Kotlin/Compose auf **2000 × 1200**. HELM, AUDIO, SONAR und SETTINGS sind nativ; DASHBOARD bleibt offen. Die App bezieht alle Telemetrie über den konfigurierten externen No5-Gateway. Der beiliegende Python-Simulator und ein künftiger echter Pi implementieren **dieselbe V1-Schnittstelle**. Herkunft und Fähigkeiten bleiben ausdrücklich erkennbar.

## Start

1. Projekt vollständig entpacken und im Terminal nach `no5_android/` wechseln.
2. `python3 -m simulator` starten. Python 3.10+, Standardbibliothek, keine Zusatzpakete für den Server.
3. APK 0.7.0 im Standard-Android-Emulator desselben Rechners installieren/aktualisieren.
4. In SETTINGS oben **APP 0.7.4 · BUILD 14** prüfen. **Pi endpoint** auf `http://10.0.2.2:8080` setzen, **Save settings** drücken.
5. HELM, AUDIO oder SONAR öffnen. Der Simulator liefert die Fahrt und die fiktive Musikbibliothek. Die Audioanzeige simuliert Zustand und Bedienung; sie spielt keine Musikdateien ab.
6. Auf dem Serverrechner `http://127.0.0.1:8080/` öffnen. **START** beginnt die Fahrt aus READY. **Reset** kehrt zu READY zurück; Pause, Zeitfaktor, Trim und getrennte Fehler sind dort steuerbar.

Für ein echtes Android-Gerät im privaten LAN den Server ausdrücklich mit `python3 -m simulator --host 0.0.0.0 --port 8080` starten und seine tatsächliche LAN-IP eintragen. Es erfolgt keine automatische Firewalländerung oder öffentliche Freigabe. Kein Pi wurde installiert oder produktive Hardware verbunden.

## Instrumente und Einheiten

HELM besitzt getrennte Rohdaten und eine durch Displayframes getaktete Darstellung. Der Gateway liefert nominal alle 50 ms einen HELM-Sample; die Darstellung verbindet zulässige Bewegungswerte innerhalb höchstens 100 ms mit dem nächsten Ziel. Quelle, Zeit, Nullwerte, kritische Motorwerte und Ausfälle werden nicht geglättet. Kompass und Positionswinkel berücksichtigen den kurzen Weg über 0°/360° beziehungsweise die Datumsgrenze.

Das zentrale Instrument zeigt ausschließlich SOG, aktuelle Tiefe und zwei Minuten Tiefenverlauf. Der Verlauf enthält echte Quellen-Samples mit Lücken bei Ausfall, Reset oder Quellenwechsel; er ist kein SONAR. Die Geschwindigkeitsskala ist eine Anzeigeskala und keine Sicherheitsgrenze. Zahlen über der Skala bleiben sichtbar.

SETTINGS speichert Geschwindigkeit (`kn`, `km/h`, `mi/h`), zugehörige Streckeneinheit, Verbrauch (`L/h`, `km/L`, `NM/L`, `L/100km`) und Tiefe (`m`, `ft`). Rohdaten bleiben `kn`, `m`, `L/h`. Nicht definierte Quotienten und veraltete Eingangswerte bleiben unbekannt; es entstehen keine unendlichen Werte. Reine Einheitenänderungen verbinden den Gateway nicht neu und löschen den Verlauf nicht. Entwurfsänderungen gelten erst nach Save; Cancel/Back verwirft sie.

Es gibt keinen lokalen Demo-Modus und keine internen Demo-Steuerungen in der App. Wiederverwendbare deterministische Quellen bleiben für ausdrücklich injizierte Tests enthalten. Bestehende Demo-Einstellungen werden auf den externen Gateway umgestellt; Adresse und Wetterentscheidung bleiben erhalten. Beschädigte Einstellungen deaktivieren Verbindungen bis zum bewussten Speichern.

## Audio und Wetter

AUDIO erhält drei fiktive Titel mit eigenen geometrischen Covers vom Gateway. Transport, Seek, Shuffle/Repeat, Fader, Mute und das freigegebene Preset verwenden korrelierte Befehle und Bestätigungen. Der Master bleibt die einzige Lautstärkeinstanz. Fehlende Fähigkeiten, veraltete Daten und Offlinezustände sperren Befehle; keine Wiederholung alter Writes nach Wiederverbindung. Physisches Lautsprecherrouting bleibt unbestätigt.

Wetter bleibt eine eigene Quelle mit eigenen Ausfällen und Aktualisierungsregeln. **Gateway weather**, **Open-Meteo** und **Weather off** sind unabhängig wählbar. Nach erteilter Nutzerfreigabe sind Erststart/V1-Migration für Open-Meteo aktiviert; explizit gespeicherte Entscheidungen bleiben erhalten. Übertragen werden nur gültige Bootsquellenkoordinaten, keine Telefonortung. Freigabe in SETTINGS widerrufen und speichern. Wettermodelle sind keine Bordsensormessung.

SONAR empfängt den gemeinsamen V1-WebSocket-Stream vom Simulator oder einem kompatiblen echten Gateway:1024 uint16-Bins proPing, begrenzte2048-Ping-Historie, eigene Kalibrierung/Generation und bestätigte HTTP-Aufnahmebefehle. Der Simulator erzeugt deklarierte synthetische Echos aus seiner Fahrt-Tiefe und Reflektoren; echte Hardwaredaten werden dadurch nicht behauptet. Freeze hält nur die Ansicht an, die Erfassung läuft weiter. Advanced öffnet die kompakte untere Einstellleiste. Ohne verbundenen SONAR-Adapter bleibt die Aufnahme unavailable.

## Bauen und prüfen

Android Studio: JDK 17, SDK Platform 35, Build Tools 35.0.0. Projektordner öffnen, Gradle synchronisieren, `app` starten. Toolchain-Pins sind enthalten; `local.properties` und private Schlüssel nicht.

```sh
./gradlew :domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s simulator/tests -v
python3 tools/validate_gateway_contract.py
python3 tools/validate_audio_contract.py
python3 tools/validate_sonar_contract.py
./gradlew :app:connectedDebugAndroidTest
```

Der tatsächlich ausgeführte Prüfstand steht ausschließlich in [verification/STATUS.md](verification/STATUS.md). Host-Transportmessung, JVM-Tests und kompilierte Instrumentation ersetzen keine Android-Pixel-/Touch-/FPS-Abnahme. Die ursprüngliche Lieferung verwendete den bekannten 0.3/0.4-Debug-Schlüssel. **Cloud-APKs verwenden einen anderen Debug-Schlüssel**; Fingerprint und konkrete APK stehen im aktuellen Prüfbericht. Nur bei einem tatsächlichen Signaturkonflikt muss mit dem bisherigen eigenen Schlüssel gebaut oder neu installiert werden; eine Deinstallation löscht App-Einstellungen.

## Übergabe

Antigravity im entpackten `no5_android/` öffnen: **Lies ANTIGRAVITY_START.md und führe die darin beschriebenen Schritte aus.**

- [Gateway-Vertrag](docs/GATEWAY_API.md), [Schema](contracts/no5-gateway-v1.schema.json), [Audio-Schema](contracts/no5-audio-v1.schema.json)
- [Zeitverhalten](docs/TEMPORAL_PIPELINE.md), [Simulator](docs/SIMULATOR.md)
- [Quellen](docs/SOURCE_INTERFACES.md), [SETTINGS](docs/SOURCE_SETTINGS.md), [Wetter](docs/WEATHER_SOURCES.md)
- [Abnahme](docs/ACCEPTANCE.md), [Prüfstand](verification/STATUS.md)

Ein echter Pi muss den beigefügten Vertrag erfüllen. Vorhandene SignalK-/CamillaDSP-/Hardwareprotokolle sind damit nicht automatisch angebunden. Szenario-Steuerungen unter `/v1/simulation` sind separate Testwerkzeuge; HELM bleibt lesend. Kein Motor-, Lenkungs- oder Autopilot-Schreibvertrag.

Minimum ab 0.7.19: Android 10 (API 29). compileSdk und targetSdk bleiben 35. GLES3 bleibt der primäre Renderer; AGSL ist ausschließlich auf API33+ als zusätzlicher Rückfall verfügbar. Software-Rendering, Shaderfehler, reduzierte Bewegung und Ressourcenengpässe behalten einen statischen Bild-Fallback.

## Hintergrund und Anzeige ab 0.6

SETTINGS → HELM BACKGROUND → Hintergrundbild / Animation / Kein Hintergrund → **Save background**. **Cancel background** verwirft den Entwurf. Die Wahl wird getrennt von Pi und Wetter gespeichert; NIGHT blendet alle Landschaft aus. Animation benötigt Hardware-Canvas und fällt bei reduzierter Bewegung, Shaderfehlern oder Ressourcenengpässen auf das Bild zurück. Details und tatsächliche Prüfgrenzen: [OCEAN_IMPLEMENTATION.md](docs/OCEAN_IMPLEMENTATION.md).

RPM verwendet feste Ziffernzellen und Baselines; AUDIO eine zusammenhängende Blende; die unteren HELM-Inhalte gemeinsame vertikale Gruppen. Native Hostbilder stehen unter verification/renders/. Geräte-/GPU-/Touch-/FPS-Abnahme bleibt gesondert.

## Ocean-Diagnose

Der neue begrenzte GLES3-Meshpfad ist in [OCEAN_GLES.md](docs/OCEAN_GLES.md) dokumentiert; AGSL bleibt sichere Rückfallbasis. Die erste Etappe besitzt noch festes dekoratives Tageslicht, keine neue Wetter-/UTC-Lichtkopplung.

Volle Qualität ist der Standard. SETTINGS → HELM BACKGROUND → Animation → Save background, dann zurück zuHELM. Bei einem Wechsel zu reduzierter Qualität/Bild bitte No5Ocean-Logs ansehen. Sie melden gemessene **App-Fensterframezeiten**, keine isolierten GPU- oder Shaderzeiten.0.7.0 protokolliert den Wechselgrund und Messwerte sowie konkrete Shaderfehler.

Mit dem Android-Studio-Standardspeicherort unter macOS:

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" logcat -d -v threadtime 'No5Ocean:V' 'AndroidRuntime:E' '*:S'
```

Bei einem anderen SDK-Speicherort dessen `platform-tools/adb` verwenden. Paket: `com.no5.helm`; SETTINGS muss APP0.7.0 · BUILD10 zeigen. Keine Deinstallation oder Emulator-Datenlöschung erforderlich.

Zusätzliche Mindest-API-Hostmatrix: `./gradlew -Pno5OceanApi33=true :app:testDebugUnitTest --tests 'com.no5.helm.ocean.*'`. Sie benötigt die fest versionierte Robolectric-Android13-Testlaufzeit vonMavenCentral. Im ausgelieferten Prüflauf war deren Download nicht verfügbar; API35-Hosttests und Android33-kompatibler Build sind getrennt dokumentiert. Ein Hostlauf belegt keine GPU-Pixel oder Geräte-FPS.

## Simulator0.7: Start und Steuerung

Der Server beginnt in **READY**. Im Browser auf demselben Rechner `http://127.0.0.1:8080/` öffnen und **START** drücken; erst dann beginnt die Fahrt. HELM-/AUDIO-/SONAR-Dienste sind unabhängig von der Fahrt aktiv. Pause hält die Fahrt, Stop beendet sie, Reset führt zurück zuREADY. **Edit settings** bearbeitet einen Entwurf; erst bestätigtes **Apply** verändert den Simulator. Mehrere Browser müssen Versionskonflikte explizit auflösen.

Der Simulator liefert echte Open-Meteo-Modellvorhersagen für seine feste fiktive Testposition, mit eigener Herkunft, Modellzeit und Abrufzeit. `python3 -m simulator --weather-off` startet ohne diese externen Anfragen. Die App kann weiterhin direkt Open-Meteo oder **Gateway weather** wählen; bereits gespeicherte Freigaben bleiben erhalten. Wetterverlust erzeugt keinen synthetischen Ersatz.

Die standalone Simulator-ZIP enthält denselben `simulator/`-Stand wie dieses Projekt. Python3.10+, Standardbibliothek. Sie ist ein Entwicklungsserver, keine Installation auf demPi. Details: [Simulatorsteuerung](docs/SIMULATOR_CONTROL_API.md), [SONAR-Vertrag](docs/SONAR_GATEWAY_V1.md).

360° video test (0.7.20): [local-file setup, asset status and decoder limits](docs/VIDEO360_TEST.md). No licensed footage is bundled.

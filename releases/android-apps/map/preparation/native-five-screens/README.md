# No5 – isolierte Vorbereitung der fünf nativen Screens

**Die Kotlin-Screens werden seit MAP 0.4.11 in der aktiven App verwendet.**
Der separate Harness bleibt eine ausdrücklich simulierte Vorschau. Die ursprünglichen
Vorbereitungs- und Handoff-Dokumente beschreiben den archivierten Ausgangsstand;
den aktuellen Integrationsvertrag dokumentiert
[NATIVE_SCREENS_ANDROID10](../../docs/NATIVE_SCREENS_ANDROID10.md).

Dieses eigenständige Gradle-Projekt baut `:ui` (native Kotlin/Compose-Bausteine) und `:harness`
(separate, ausdrücklich simulierte Vorschau-App). Das Root-Projekt verwendet dasselbe
UI-Verzeichnis als `:native-screens`; der Produktionshost liegt in `ui-shell`.
Es gibt keine WebView, keinen Gateway-Schreibpfad und keine Autopilotfunktion.

## Bauen und prüfen

Vom Repository-Root mit vorhandenem Android SDK 37 / JDK 17:

```sh
./gradlew -p preparation/native-five-screens :ui:testDebugUnitTest :harness:testDebugUnitTest :harness:assembleDebug
```

Cloud-Umgebung dieser Vorbereitung: zuvor `source /workspace/.no5-setup/env.sh`.
Das ist eine Umgebungsabhängigkeit dieser Cloud, keine Voraussetzung für einen anderen Consumer.
Die Gradle-Version kommt vom bestehenden Wrapper; Produktions-Buildsettings werden nicht geändert.

Das optionale APK heißt `harness/build/outputs/apk/debug/harness-debug.apk`,
Paket `com.no5.preparation.harness`, Label **No5 UI · SIMULATION**.
Android 13+, ARM64-Ziel; die Compose-App enthält derzeit keine eigene native Bibliothek.
Keine Internet-, Standort- oder Hintergrunddienstberechtigung. Der Prozessspeicher ist flüchtig.

## Komponenten

* `ui/.../No5Shell.kt`: kompakter Header, eine linke Navigationsleiste mit fünf Zielen,
  einklappbare Detailpanels, kontextuelle Kartenoverlays und Dialoge;
  ein stabiler injizierter Renderer-Slot. Kein Footer.
* `No5Header.kt`: tatsächlicher nativer HELM-Header aus `xkyball/no5`, 0.7.18 Build28:
  Wortmarke mit STELLENBOSCH/Claim, Quellenblock, Originalicons und DAY/NIGHT-Pille.
* `AdapterContracts.kt`: WGS84, lokale Viewportpixel, Frameversion, Projektion und lesender Backendvertrag.
* `Routes.kt`: Entwurf, WGS84-Eingabe, Sortieren/Entfernen/Rückgängig, Verwerfen,
  versionierte Speicheranforderung, Review mit bewusstem Einstieg, expliziter Start und Stopp.
* `Ais.kt`: Suche, Filter, lokale Beobachtung, Details, letzter Standort, Alter/Qualität/CPA getrennt.
* `LayersSettings.kt`: bestätigte Capabilities und Einstellungen, Quellen-/Offline-/Diagnose-Unterseiten.
* `No5Theme.kt`: Barlow, dunkle Schrift/Orange am Tag und gedimmte rote UI in der Nacht.
* `harness/...`: ausschließlich hier synthetische Testpositionen und Testkartenformen.

States werden per Parameter übergeben; Composables senden Ereignisse. `FrozenList` kopiert
eingehende Listen und bietet keine Mutationsschnittstelle; Elemente müssen ebenfalls immutable sein.
Die UI berechnet keine zweite Kartenprojektion. Kartenpaletten und nautische Bedeutungsfarben
sind Aufgabe des Rendereradapters; die UI-Themenfarbe ist kein Karten-Rotfilter.

## Aktuelles Layout

Die später ausdrücklich freigegebene Seitenleiste ersetzt die ursprüngliche Footer-Vorgabe.
Im nativen 2000×1200-Harness bei xhdpi: Header 112px (56dp), Leiste 128px (64dp),
Renderer-Slot 1872×1088px. Die Leiste zeigt Icons und kurze Namen; Accessibility verwendet
die vollständigen Screen-Namen. Der aktive Eintrag erhält einen farbigen Streifen.
Touchziele der Leiste und der kompakten Header-/Panelaktionen sind mindestens 48dp.
Der Header übernimmt HELM-Farben, Schriftbaselines und horizontale Schriftstreckung.
Seine Schalterhälften messen sichtbar 55.5×25.5dp; die transparenten Touchflächen
55.5×48dp. MOB liegt separat unter den fünf Zielen in der Seitenleiste.
Der Header zeigt keinen zusätzlichen mittigen PLOTTER-Titel. Die originale HELM-Bildoption
nutzt das unveränderte Ocean-Bild und dessen Quellcrop/Alpha-Maske innerhalb des originalen
Medienclips. Das ist kein eingefrorener Screenshot der animierten Build28-Szene.

ROUTEN, AIS, EBENEN und EINSTELLUNGEN öffnen Panels unmittelbar neben der Leiste.
`ShellState.panelExpanded` und `ShellEvent.TogglePanel` steuern das Einklappen.
Erneuter Druck auf den aktiven Navigationseintrag öffnet sein Panel wieder.
Einklappen behält Entwurf, Auswahl, gespeicherten Scrollzustand und Kamerazustand.
Es ändert weder Slotgröße noch Renderer-Lifecycle. Ein geänderter Routenentwurf bleibt
bei einem Screenwechsel durch den vorhandenen Verwerfen-Dialog geschützt.

`NavigationHud.active` ist nur bei tatsächlich aktiver Navigation wahr. Ohne Route kein
HUD. Im offenen Panel steht der bestätigte aktive Leg im Panelkopf; nach Einklappen sind
die Werte im Kartenoverlay sichtbar. Tag/Rotnacht bleibt im Header, MOB in der Seitenleiste.
Warnungen stehen in geöffneten Panels unter deren Kopf, sonst im Kartenoverlay.
Die frühere vollbreite HUD-/Statuszeile entfällt.

Native Bilder und historische Vergleichsansichten: [previews/](previews/).
Nach den Gradle-Prüfungen erzeugt `python3 preparation/native-five-screens/tools/collect_evidence.py`
die kopierten nativen PNGs, Vergleichstafeln und Hash-/XML-Belege neu. Der Collector benötigt
Pillow ausschließlich als Hostwerkzeug und liest die historische Referenz aus dem Git-Commit.

## Referenz und Herkunft

Für den Header ist ausschließlich der ausdrücklich angeforderte tatsächliche HELM-Stand
`5c6436c8314f65fb91154d40dff8c787ac5caeb4` maßgeblich. `drawHeaderFallback()` ist dort
der aktive HELM-Pfad; `drawSharedHeader()` der anderen Seiten ist keine Vorlage.
Quellcode, Scene-JSONs, Schriftdateien und tatsächlich gerenderte Build28-Pixel wurden
vor der Änderung abgeglichen. Quellen und Hashes: [evidence/helm-header-source-comparison.json](evidence/helm-header-source-comparison.json).
Die Header-Assets sind ausgewählte unveränderte JSON-Knoten/Clippfade sowie das Originalbild.
Die Schriftdateien haben dieselben SHA-256-Hashes wie im HELM-Repository.

Referenzen: `NO5_PLOTTER_OVERLAYS.html`, `NO5_PLOTTER_PROTOTYPE.zip` und
`NO5_MAP_ANDROID_UMSETZUNGSPLAN.docx`, jeweils Library-Version 1.
Die drei Library-Referenzen wurden aufgelöst. Die vorgeschriebene lokale Materialisierung
scheiterte bei allen mit `library file transfer failed: download failed`.
Kein Ersatzpfad für diese Library-Downloads wurde verwendet. Der Plan wurde vollständig
über den erlaubten Library-Lesezugriff gelesen.

Der Nutzer stellte danach zusätzlich `NO5_PLOTTER_PROTOTYPE (1).zip` direkt bereit.
Dieses ZIP wurde lokal entpackt, Quellen/Lesbarkeit geprüft und seine PNG-Pixel angesehen:
KARTE Tag/Rotnacht, ROUTEN/Entwurf, AIS, EBENEN und EINSTELLUNGEN.
Die PNGs sind laut Quelldokumentation SVG-Renderausgaben, keine Browser-Screenshots.
Die vorhandenen Kartenstilstudien des Repositorys wurden nicht als Freigabe übernommen.
Die Barlow-Schriften stammen aus dem hochgeladenen ZIP; Lizenz unter `licenses/`.

## Übergabe

API-/Adaptervertrag und Integrationsschritte: [INTEGRATION.md](INTEGRATION.md).
Tatsächlicher Prüfstand und Grenzen: [VALIDATION.md](VALIDATION.md).
Alle Änderungen bleiben unter `preparation/native-five-screens/`.
Übernahme auf `master` koordiniert der Parent; kein konkurrierender Master-Push.
Produktive Einbindung erst nach ausdrücklicher Renderingfreigabe.

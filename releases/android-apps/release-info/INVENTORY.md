# Vollständiges App-Inventar

Aktiver Katalog nach der freigegebenen Archivierung vom 2026-10-09; ursprüngliche
Erfassung am 2026-10-07 aus Application-Builds und Android-Manifesten.

| App | Projekt/Modul | Installierbares Paket | Versionsfläche |
|---|---|---|---|
| HELM | helm/app | com.no5.helm | SETTINGS-Zeile; Owner-Hunk |
| MAP | map/app | com.no5.map | vorhandene Informationsfläche; Owner-Hunk |
| Launcher | launcher | com.no5.home | bestehende Status-/Versionszeile |
| Systemwindow + NarrowTestIme | systemwindow-ime-test | com.no5.systemwindowtest | Test-App-Kopfzeile; IME gehört derselben APK |
| Outline V1 | map-outline-probe/app | com.no5.map.outlineprobe | Probe-Kontrollzeile |
| Outline V2 | map-outline-probe-v2/app | com.no5.map.outlineprobe.v2 | Probe-Kontrollzeile; im Benchmark gesperrt |
| Fullrenderer A/B | map-fullrender-probe/app | com.no5.map.fullrenderprobe | Probe-Kontrollzeile; im Benchmark gesperrt |
| Renderer baseline | renderer-baseline/app | com.no5.map.rendererbaseline | Informationsfläche |
| Native UI harness | map/preparation/native-five-screens/harness | com.no5.preparation.harness | Simulationsversionszeile |

**9 aktive APKs**, davon acht eigenständige Top-Level-App-Projekte und ein
verschachtelter Harness. Kein zweites APK für die IME.

Knob-Diagnose, Knob-Messung, Header-POC/Fixture, Emulator-Header-Vorschau und
Native-Screen-Vorschau sind seit 9. Oktober archiviert. 153 Quelldateien liegen
im dauerhaften Tag `archive/legacy-apps-20261009`; 61 historische Belege,
Releasehistorien und Assets bleiben unverändert unter
[project-archives](../project-archives/legacy-apps-20261009/README.md).
Die Vorschau-Apps waren nie in die Release-Bridge integriert; daraus wird
keine nachträgliche Release- oder Geräteabnahme abgeleitet. Der aktive
`map/preparation/native-five-screens`-Harness bleibt erhalten.

Der Layoutviewer (`com.no5.layoutreview`) ist seit 8. Oktober 2026 archiviert.
Sein Katalog und die vollständigen Quellen liegen am zentral dokumentierten
Archiv-Tag; `apps.json` führt ihn unter `archivedProjects`, nicht unter den
aktiv gebauten Apps. Siehe [Projektarchive](../tools/PROJECT_ARCHIVES.md).

`helm/domain`, MAP-/Baseline-Chart-, Client-, Modell-, UI-Module,
`map/preparation/native-five-screens/ui` und `native-button-check` sind Libraries.
Der archivierte `header`-Stand ist ein Host-/DEX-/C-POC mit eigener Test-APK, keine SystemUI-App.
Header-Direktpayload, PID-Gate, Loader und ptrace-Probe sind Helper-Artefakte,
keine installierbaren APKs. Simulatoren, Python-Analyzer/CLI und native Hosttests
sind Host-Werkzeuge mit ihren bestehenden separaten Einstiegspunkten.
Historische Preview-Paketnamen und reine Deliveries sind keine zusätzlichen
aktuellen Apps; historische Nachweise werden unverändert erhalten.

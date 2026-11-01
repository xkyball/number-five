# Gateway/Auth-Zwischenstand für die Master-Integration

Der Nutzer hat am 10. Oktober 2026 das Committen, Pushen und die Übergabe an
den bestehenden Integrationschat beauftragt. Dieser Checkpoint enthält nur
Gateway/Auth-, CA-, Home-UI- und zugehörige Versions-/Test-/Dokumentationsänderungen.
Er ist gegen den Owner-Ausgangsstand `2bb9cd2738887cee4cae08043f9e53d420cd1926`
geprüft. Master-Merge und neue kombinierte Abnahme gehören zum Integrationschat.

## Verhalten und Modulgrenzen

- `gateway-config`: originbezogener abgeleiteter Passwortzugang parallel zum
  alten Key-Datei-Mechanismus; gepinnter Download und automatische Reparatur einer
  fehlenden/unlesbaren/ungültigen öffentlichen CA.
- `launcher`: ein gemeinsamer verdeckter Key-/Passwortdialog mit Dateiimport,
  Provider/Adressprüfung mit dem gewählten Zugang; unabhängiger CA-Status;
  manueller CA-Import nur unter Erweitert; MAP-Settings-Design, Knob-Abstand und
  ausreichende native Touch-Flächen.
- `helm/app`: gemeinsame Security-Profiltests und Eigentümer-Versionsmetadaten.
  `map`: ausschließlich Eigentümer-Versionsmetadaten. Keine neuen Rendererquellen.
- Fremde Änderungen an `tools/apk_handoff.py` und generierte Render-/Timinglogs
  sind ausgeschlossen. Keine Secrets, persönlichen Passwort-Prüfdatensätze,
  Produktions-Keydateien, Keystores oder APKs.

Der Serverteil liegt im separaten `no-pi`-Checkpoint; seine nginx-Erweiterung
ist Voraussetzung für `X-No5-Gateway-Password` und `/auth/v1/{ca,parameters,check}`.
Nur der Nutzer kennt sein Passwort. Ein schwaches Passwort bleibt trotz
langsamer gesalzener Ableitung offline erratbar; dies ist TLS-Authentisierung,
kein PAKE.

## Geprüfte Tests und Builds

| Teil | Ergebnis |
| --- | --- |
| Home16, final nach MAP-Design-/Dialog-Höhenkorrektur | 142 Tests, 0 Fehler/Skips; Build und Lint bestanden, 31 Warnungen |
| HELM59 mit gemeinsamer Security-Erweiterung | 458 Tests, 0 Fehler/Skips; Build/Lint und Instrumentation-Kompilation bestanden, 107 Lintwarnungen |
| Domain | 170 Tests unverändert, UP-TO-DATE im dokumentierten Lauf |
| MAP55 / MAP-Client | 21 / 4 Tests, 0 Fehler/Skips; Build/Lint und Instrumentation-Kompilation bestanden, 9 Lintwarnungen |
| Simulator | 78 Tests bestanden |

Aus dem Repository mit dem vorhandenen JDK/SDK und ohne Downloads:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 -B tools/shared_gradle.py launcher --offline testDebugUnitTest assembleDebug lintDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 -B tools/shared_gradle.py helm --offline :domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home python3 -B tools/shared_gradle.py map --offline :helm-client:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
python3 -B -m unittest discover -s simulator/tests -v
```

Die unabhängigen Android-Einstiege wegen des gemeinsamen Runtime-Locks
nacheinander ausführen. Die Home-Prüfung ist der neueste Lauf; die HELM/MAP-
Prüfungen stammen aus dem unveränderten CA-Recovery-Stand vor dem Home-UI-Fix.

Auf dem physischen Lumina K706, Nutzer 0, sind via Center installiert und per
Version/Hash bestätigt: Home16 `0.4.8-gateway-map-style`, HELM59
`0.7.49-ca-recovery`, MAP55 `0.4.50-ca-recovery`. Native Tag-/Rotnachtprüfung,
Zugangsdialog, Erweitert-Importbereich, Mindesttouchgröße und Knob-Abstand
bestanden. Sichtbarer Status: „CA vorhanden und geprüft“ und
„Adresse erreichbar · automatisch gefunden“. Der finale Home-APK-Hash ist
`cdf6453a09eda0730641c99f3a014fee6eb2e31bbac10ecaa588d4dc2782be58`.
Der Center-Startjob ist wegen unbekanntem OEM-Fokus `partial`; Installation,
Version/Hash, native Oberfläche und sichtbar/resumed Home sind separat bestätigt.

Vollständige lokale Belege stehen außerhalb dieses Git-Repositories unter
`/Users/chris/projects/max/gateway-panel-20261010/{checks.json,STATUS.json,README.md}`,
`/Users/chris/projects/max/gateway-panel-20261010/lumina-center/home16/` sowie
`/Users/chris/projects/max/ca-recovery-20261010/`. Diese Laufzeitlogs/-bilder
werden nicht als Produktquellen veröffentlicht.

## Integrationshinweise und offene Grenzen

Der veröffentlichte gemeinsame Master `30301abdf0f8534ba01b4c769e47eeef43b7bd87`
enthält inzwischen HELM62/Spotify und die Renderer-Integration. Die hier
getesteten Owner-Metadaten HELM59/MAP55 dürfen aktuelle Master-Releases weder
zurücksetzen noch deren Historie überschreiben. Security-/Home-Quellen gezielt
integrieren, Kataloghistorien vereinen und Buildnummern erst auf dem kombinierten
Stand vergeben. Die gemeinsame `release-info/apps.json` und Release-Kataloge
benötigen daher eine bewusste Zusammenführung. Kein kompletter Owner-Branch-Merge
mit ungeprüfter Überschreibung des Masterstands.

Native CA-Erstdownload/Keystore-Wiederherstellung und die persönliche
Passwortanmeldung bleiben offen. Für die UI-Abnahme wurde kein Credential
eingegeben und keine gültige CA gelöscht. Emulatoren bleiben am Labor,
Lumina am echten Pi. Dieser Checkpoint enthält keinen neuen Geräte-/Pi-Auftrag
und löst keine CI aus (`[skip ci]`).

# Browserproxy auf dem physischen Pi

Stand 10. Oktober 2026: **Auf dem echten Pi aktiv und über HTTPS geprüft.**
Root-Preflight, Installation, Simulator-/CamillaGUI-Zugang und Erhaltungsprüfungen
bestanden. Der Browserproxy verwendet weiterhin das gepinnte Audio-Release
`0.1.0-cf6e792-audio`; kein neues Gateway-Paket wurde aktiviert.

Der Nutzer hat sudo zusätzlich ausdrücklich passwortfrei beauftragt. Die
spätere Regel in `/etc/sudoers:56` lautet nun
`user ALL=(ALL:ALL) NOPASSWD: ALL`. Sie hatte zuvor die bereits eingebundene
no5-codex-Regel überlagert. Syntaxprüfung und Root-Aufrufe mit ignoriertem
Passwort-Zeitstempel bestanden sowohl ohne TTY als auch mit TTY. Die
Originaldatei liegt rootprivat unter
`/var/backups/no5-sudo-passwordless-20261010T090659Z/sudoers.before`.
SSH-Identität und Zugangsdaten blieben erhalten.

## Übernommene Laborfunktionen

| Funktion | Aktive Pi-Adresse | Zugang |
| --- | --- | --- |
| HELM-/AUDIO-/SONAR-App-API | `https://lysmarine.local:9443` | unveränderter `X-No5-Gateway-Key` |
| Simulatorsteuerung | `https://lysmarine.local/simulator/` | derselbe rohe Gateway-Key wie im Labor |
| Vorhandene CamillaGUI | `https://lysmarine.local/camilladsp/` | Gateway-Key beim Login, danach 15-Minuten-Cookie |

Die Simulatorseite verwendet den exakt gepinnten Editor des aktiven
Audio-Releases `0.1.0-cf6e792-audio`. nginx liefert HTML, CSS und JavaScript
getrennt mit CSP und ohne gespeicherten Browser-Key aus. Nur die beiden
Methoden GET/POST auf `/simulator/api` werden nach Key-Prüfung an die lokale
Kontroll-API weitergegeben; POST verlangt die exakte HTTPS-Origin.
Der vorhandene Simulation-Bearer bleibt in einem rootprivaten Include.
Die direkten `/admin/`- und `/internal/`-Pfade bleiben auf beiden TLS-Ports gesperrt.

Der Simulator behält seine aktuelle Auswahl, Zustandsdatei und lokale
SONAR-Endpunkte. Er überlagert nur ausdrücklich ausgewählte HELM-/Wetterfelder;
die bestehende SIMULATED-Kennzeichnung bleibt erhalten. Die Installation
aktiviert keine zusätzliche Auswahl und schickt keine Hardwarebefehle.

CamillaGUI war beim lesenden Ausgangscheck inaktiv und ist jetzt gestartet.
Die Migration verwendet genau dieses vorhandene Binary und dessen
Pi-Konfiguration. Ein Drop-in wählt eine Kopie mit Loopback-Bindung;
DSP-Pfade und die beiden deaktivierten Konfigurationshooks bleiben erhalten.
Der Backendstart ist daher ein separater Bestandteil dieser Browsermigration.
Die nginx-Regeln übernehmen die im Labor geprüften Redirects und die
Korrektur rootrelativer JavaScript-API-Pfade.

Der Netzabgleich bindet Port 443 nur an Loopback und die globalen Adressen
der ausdrücklich erkannten eingebauten Pi-NICs. Er arbeitet beim Boot,
bei NetworkManager-Ereignissen und über einen 30-Sekunden-Timer.
Bei unveränderter Adressliste erfolgt kein Reload. Bei nginx-/TLS-Fehlern
stellt er den früheren Listener-Include wieder her. DNS-Identität, CA,
Serverschlüssel und Zertifikat werden durch diesen Helfer nicht verändert.
Automatische Zertifikatserneuerung ist noch kein Bestandteil dieses Helfers.

## Gezielte Installation und Abnahme

`tools/migrate_pi_browser_proxy.py` prüft standardmäßig nur die reale
Pi-Identität, das gepinnte Gateway-Release, die ursprüngliche Key-Map,
laufende Audio-Dienste und den aktuellen DSP-Zustand. Für eine Installation
benötigt es die benachbarten `tools/`- und `deploy/`-Dateien dieses Projekts.
Erst `--apply` installiert die Transaktion.

Vor der Aktivierung ist die Nutzerwirkung zu nennen: nginx wird neu geladen;
der Gateway wird nur bei geänderter TOML/Environment neu gestartet, wodurch
App-Verbindungen und bestehende Gateway-Sitzungen kurz ausfallen können.
CamillaGUI wird gestartet bzw. bei bereits laufendem Backend neu gestartet.
CamillaDSP, Spotify-Receiver, Signal K, Avahi und SONAR werden nicht neu gestartet.
Es gibt keine Testtöne, Wiedergabe-, Fader- oder Presetbefehle.

Die Transaktion erhält Audio-TOML, Presetliste einschließlich
`preset_no5_front_stereo_fullrange.yml`, DSP-Konfiguration und Fader.
Vorhandene Environment-Werte bleiben identisch. Nur gegebenenfalls fehlende
interne Simulation-/GUI-Sitzungsgeheimnisse werden lokal auf dem Pi erzeugt.
Der App-Key wird weder ersetzt noch auf den Mac exportiert.

Bei dieser Aktivierung wurde nur der fehlende interne CamillaGUI-Sitzungsschlüssel
ergänzt. Der Simulation-Token war bereits vorhanden. Main stand bei **−6 dB,
unmuted**; alle Aux-Fader standen bei 0 dB, unmuted. Genau dieser vom Betreiber
inzwischen verwendete Zustand blieb erhalten. Der ältere stumme Audio-Abnahmestand
vom 9. Oktober wurde nicht wieder aufgezwungen. Es wurde keine Simulatorauswahl
aktiviert: HELM-/Wetter-Overlays bleiben leer, SONAR bleibt auf `INHERIT`.

Vor Änderungen wird ein rootprivater Snapshot unter
`/var/backups/no5-browser-proxy-<UTC-Zeit>/` angelegt. Ein Fehler bei
Validierung/Start oder den eingebauten Erhaltungsprüfungen stellt Dateien
und betroffene Dienstzustände wieder her. Der Snapshot enthält einen
eigenständigen Rückbauhelfer. Nach einer erfolgreichen Installation lautet
die spätere Rückbauform:

```sh
sudo /usr/bin/python3 /var/backups/no5-browser-proxy-<UTC-Zeit>/migrate_pi_browser_proxy.py \
  --rollback /var/backups/no5-browser-proxy-<UTC-Zeit>
```

Der Rückbau verweigert spätere fremde Dateiänderungen; normale automatisch
erzeugte DHCP-Listener werden gesondert validiert. Die Audio-Migrationsreserve
vom 9. Oktober bleibt unangetastet. Vor ihrem Gebrauch nach dieser Migration
zuerst den Browserproxy zurückbauen, damit ihre Schutzprüfungen nicht an den
zusätzlich freigegebenen Gateway-Einstellungen scheitern.

Der tatsächliche Snapshot ist
`/var/backups/no5-browser-proxy-20261010T090807Z`. Ein lesender Rückbaucheck mit
`--check-rollback` prüfte alle 22 geänderten Dateien und die privaten
Wiederherstellungsdateien erfolgreich. Der Rückbau wurde nicht ausgeführt.
Die temporären öffentlichen Prüfscripte und das Pi-Staging wurden danach
entfernt. Rückbauhelfer und Live-Verifier bleiben in der rootprivaten Reserve.

`tools/verify_pi_browser_proxy.py --backup <Snapshotpfad>` prüft danach
striktes TLS, Assets, echten Key-Zugang, abgewiesene Origin-/Payload-Requests,
den originalen CamillaGUI-Lesezugang und Logout sowie den unveränderten
Simulator-/Audiozustand. Es schickt keinen gültigen Simulator- oder DSP-Befehl.
Bedienungsabnahme im Browser und in HELM bleibt davon getrennt.

## Bereits ausgeführte Prüfungen

Am 10. Oktober 2026:

- Fünf lokale Migrationsprüfungen bestanden: Audio-/Preset-Erhalt,
  TOML-Grenzen, explizite NIC-Auswahl, öffentliche Browserassets und
  Zurückweisung mehrdeutiger Environment-Dateien.
- Python- und Simulator-JavaScript-Syntax bestanden.
- Echtes Pi-nginx mit künstlichen Zertifikaten/Zugangsdaten auf separaten
  Unix-Sockets: 18 Zugriffsfälle bestanden. Kein produktiver Daemon oder
  privates Produktionsmaterial wurde verwendet.
- Gesamte vorbereitete Gateway-Suite mit vorhandener Pi-venv: 93 Prüfungen,
  89 bestanden, 4 optionale Client-/Schema-Prüfungen übersprungen.
  Der erste Mac-Lauf hatte wegen fehlendem `aiohttp` zwei Importfehler;
  auf dem Pi wurden keine Pakete installiert.
- Produktiver Root-Preflight und Installation bestanden; nginx neu geladen,
  Gateway einmal neu gestartet, CamillaGUI gestartet. Keine zusätzlichen
  Neustarts der sechs geschützten Audio-/SONAR-/Signal-K-/Avahi-Dienste.
- Live-Prüfung über die tatsächliche LAN-Adresse mit striktem TLS:
  Simulatorassets, echter Key-Zugang, Origin-/Methoden-/Payload-Schutz,
  interne Pfadsperren, App-API auf 9443 sowie Original-CamillaGUI und Logout
  bestanden. Simulatorauswahl, Revision, DSP-Fader, DSP-Konfiguration,
  CA/Serverschlüssel, Key-Map und zusätzliches Front-Preset unverändert.
- Vom Mac über mDNS und striktes Pi-CA-/Hostnamen-TLS: Simulator-HTML/JS/CSS
  und CamillaGUI-Login auf Port 443 bestanden. Es wurde nur die öffentliche
  CA-Datei übertragen; kein Gateway-Key und kein globaler Truststore geändert.
- Der gehärtete Netzabgleich als echter systemd-Dienst lief erfolgreich;
  aktiver Timer und idempotenter Wiederholungslauf ohne nginx-Reload bestätigt.
- Rückbaureserve lesend geprüft. Tatsächlicher Rückbau, echter DHCP-Wechsel,
  Pi-Neustart und Android-/Browserbedienung: **NOT RUN**.

Die aktive Erweiterung für Key/Passwort und CA-Download sowie die noch offene persönliche Passwortaktivierung sind separat in
[PASSWORD_AND_CA_BOOTSTRAP.md](PASSWORD_AND_CA_BOOTSTRAP.md) beschrieben.

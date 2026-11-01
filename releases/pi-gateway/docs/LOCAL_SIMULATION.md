# Lokale Simulations-Overlays

Diese Funktion ergänzt den produktiven Pi-Gatewaydienst um eine **opt-in**
Testschicht. Sie ist kein zweiter Dienst, kein Ersatz für Signal K, CamillaDSP,
Raspotify/librespot oder `no5-sonard` und führt keine Schreiboperation zu
diesen Komponenten aus.

Der Dienst startet weiterhin produktiv und ehrlich: Ohne aktivierte
Simulation bleiben HELM, Wetter und SONAR unverändert auf ihren echten bzw.
unverfügbaren Quellen.

## Was die lokale Oberfläche steuert

| Bereich | Auswahl | App-Provenienz |
| --- | --- | --- |
| HELM | einzelne dynamische Felder aus dem bestehenden Figure-Eight-Fahrtmodell | Der gesamte HELM-Payload wird `SIMULATED`, sobald mindestens ein Feld ersetzt wird. Nicht ausgewählte Felder werden aus dem echten Snapshot übernommen oder bleiben unbekannt/stale. |
| Wetter | einzelne dynamische Wetterfelder aus demselben Fahrtmodell | Gemischter Payload, als `SIMULATED` markiert. Nicht gewählte reale Werte bleiben erhalten oder unavailable. |
| SONAR | `INHERIT`, M0 oder M1 | M0/M1 sind ausschließlich explizit konfigurierte vorhandene Daemons. Der Gateway erzeugt keine SONAR-Samples und schreibt niemals an einen Producer. |
| AUDIO | keine Simulation | CamillaDSP/Raspotify bleiben ausschließlich echte Quellen; diese Oberfläche kann keinen Fader, Mute oder Transport steuern. |

Der gegenwärtige Gateway-V1-Appvertrag hat keine Herkunft pro HELM-Feld.
Deshalb wäre ein Payload mit einzelnen `REAL` und einzelnen `SIMULATED`
Werten gegenüber der App unehrlich. Ein HELM-Overlay kennzeichnet daher
bewusst den **gesamten** Payload als simuliert. Die Oberfläche führt die
Auswahl pro Feld separat auf.

## Sicherheitsmodell

Die separat vom Nutzer gewünschte Browsermigration vom 10. Oktober ergänzt
einen gezielten `/simulator/api`-Proxy auf Port 443. Ihr Aktivierungsstand
steht in [PI_BROWSER_PROXY.md](PI_BROWSER_PROXY.md). Die folgenden direkten
Loopback-/Tunnelwege und die Sperre der rohen `/admin/`-Pfade bleiben erhalten.

* Die Funktion ist standardmäßig deaktiviert.
* Die Kontroll-API bindet nur an den Gateway-Loopback und verlangt einen
  eigenen Bearer-Token. Der Token wird niemals in TOML, im Browser-Speicher,
  Logs oder einem App-Build gespeichert.
* `deploy/nginx/no5-gateway.conf` sperrt `/admin/` ausdrücklich auf beiden
  TLS-Ports. Nur der separat installierte Browserproxy erlaubt den vorhandenen
  Gateway-Key an seinem gezielten `/simulator/api`-Endpunkt; der interne
  Bearer bleibt auf dem Pi.
* Steuerkommandos sind streng schema-, sessions- und revisionsgebunden;
  dieselbe Request-ID ist nur mit identischem Inhalt wiederholbar.
* Die Auswahl wird atomar als Modus/Präferenz in einer lokalen 0600-Datei
  gespeichert. Eine beschädigte Datei wird ignoriert und aktiviert keine
  Simulation.

## Aktivierung auf lysmarine

Zuerst muss das neue Paket/Release inklusive der Nginx-Konfiguration nach dem
normalen versionierten Releaseablauf installiert sein. Vor dem Aktivieren die
Nginx-Konfiguration prüfen und laden, damit `/admin/` auf dem LAN gesperrt
bleibt:

```sh
sudo nginx -t
sudo systemctl reload nginx
```

Dann nur die folgenden lokalen Einstellungen ergänzen. Der Wert des Tokens ist
ein neues Geheimnis; ihn nicht in die Shell-History, TOML oder ein Ticket
kopieren.

```toml
# /etc/no5-pi-gateway/config.toml
[simulation]
enabled = true
state_file = "/var/lib/no5-pi-gateway/simulation-state.json"
token_env = "NO5_GATEWAY_SIMULATION_TOKEN"
source_name = "no5-pi-gateway-simulator"
# nur die tatsächlich installierten lokalen Daemon-Endpunkte eintragen
# sonar_m0_upstream_base_url = "http://127.0.0.1:8090"
# sonar_m1_upstream_base_url = "http://127.0.0.1:8091"
```

Der Administrator erzeugt einmalig einen ausreichend zufälligen Wert, trägt
ihn als `NO5_GATEWAY_SIMULATION_TOKEN=…` in
`/etc/no5-pi-gateway/environment` ein und setzt die Datei auf `root:root`,
Modus `0600`. Anschließend:

```sh
sudo systemctl restart no5-pi-gateway
sudo systemctl status no5-pi-gateway --no-pager
```

Der Systemd-Dienst darf bereits in
`/var/lib/no5-pi-gateway` schreiben; kein weiteres Freigeben eines
Gerätepfads, keine Gruppe und kein sudo für die Weboberfläche sind nötig.

## Oberfläche öffnen

Auf dem Pi selbst ist die Adresse:

```text
http://127.0.0.1:8789/admin/simulation
```

Von einem Verwaltungsrechner erfolgt der Zugriff nur über einen SSH-Tunnel:

```sh
ssh -L 8789:127.0.0.1:8789 user@lysmarine.local
```

Danach `http://127.0.0.1:8789/admin/simulation` im lokalen Browser öffnen und
den separaten Simulation-Token eingeben. Er bleibt nur bis zum Schließen oder
Neuladen dieses Tabs im Speicher. Die Kontrolle ruft lokal
`GET/POST /admin/v1/simulation` auf; diese Pfade sind kein Android- oder
öffentliches Gateway-API.

## Funktionsabnahme

Nach jeder Auswahl die App-Herkunft prüfen:

1. HELM: mindestens ein ausgewähltes Feld liefert `sourceKind=SIMULATED`,
   `simulated=true` und eine dynamische Fahrt-Metadatenstruktur. Nicht
   ausgewählte, ungemappte Felder bleiben `UNAVAILABLE`, stale oder real.
2. Wetter: ein ausgewähltes Feld liefert dynamische Überlagerungswerte; nicht
   ausgewählte reale Open-Meteo-/Signal-K-Werte bleiben unverändert.
3. SONAR: M0 oder M1 erst auswählen, wenn der entsprechende lokale Daemon in
   TOML provisioniert ist. Der WebSocket darf ausschließlich Frames des
   gewählten Daemons zeigen; kein lokaler synthetischer Datenstrom existiert.
4. AUDIO: sicherstellen, dass die Fader/Mute-Anzeige und CamillaDSP unverändert
   bleiben. Diese Testoberfläche besitzt absichtlich keine AUDIO-Aktion.

## Deaktivierung und Rollback

Zum Beenden der Tests in der Oberfläche alle HELM-/Wetter-Overlays löschen und
SONAR auf `INHERIT` zurückstellen. Für einen harten, auch nach
Neustart wirksamen Stopp `enabled = false` setzen und nur den Gateway neu
starten:

```sh
sudo systemctl restart no5-pi-gateway
```

Die Auswahl-Datei kann als Diagnose erhalten bleiben; bei deaktivierter
Funktion wird sie nicht angewendet. Für einen kompletten Release-Rollback den
vorherigen `/opt/no5-pi-gateway/current`-Symlink gemäß
`docs/DEPLOYMENT.md` wählen und ausschließlich `no5-pi-gateway` neu starten.
Signal K, CamillaDSP, Raspotify und `no5-sonard` bleiben dabei unangetastet.

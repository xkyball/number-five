# Begrenzter temporärer Sonar-Quellentest

`tools/temporary_sonar_path.py` ist ein lokaler Bibliotheksbaustein für den
bereits ausgeführten, begrenzten Testweg. Er wird **nicht** in den Gateway
importiert, installiert, beim Boot gestartet oder als Rebuild-Dienst aktiviert.
Er enthält keine Zieladresse, SSH-Profile, Dienstnamen oder privaten Imports.
Der produktive Gateway und seine Quellenverträge werden dadurch nicht geändert.

Der frühere private Orchestrator verband zusätzlich SSH, Tunnel und
Dienstneustart. Dieser öffentliche Extrakt ersetzt seine privaten
Transportkonstanten bewusst nicht: Er macht ausschließlich die beiden
Testmutationen und deren Rücknahme öffentlich prüfbar. Die Hosttests sind kein
Nachweis, dass dieser neue Baustein bereits auf dem physischen Pi lief.

## Explizite Eingaben und lokale Grenze

`Parameters` verlangt für jeden Test einen frischen, privaten Journalpfad und:

| Parameter | Bedeutung |
|---|---|
| `config` | Normalisierter absoluter Pfad der vorhandenen Gateway-TOML-Datei |
| `runtime` | Absoluter Interpreterpfad des freigegebenen Laufzeit-Pins; exakt `sys.executable` |
| `target` | Erwarteter lokaler `socket.gethostname()`-Wert |
| `old_url`, `test_url` | Verschiedene `http://127.0.0.1:PORT`-Origins, ohne Pfad oder Zugangsdaten |
| `private_journal` | Neuer absoluter Pfad in einem vom Operator geschützten Verzeichnis |
| `old_mode`, `test_mode` | Ausschließlich das explizite Paar `M0` und `INHERIT` |

Jede öffentliche Operation prüft Linux, UID 0, Zielhostname und Interpreter.
Die Bibliothek führt keine entfernten Befehle aus. Keine Eingabe wird aus einer
Umgebungsvariable oder einem eingebauten Geräteprofil erraten. Die vorhandene
Konfiguration muss `source.producer = "m1"` besitzen.

Der Aufrufer übergibt außerdem `LoopbackControl(origin, read_bearer)` mit einem
expliziten lokalen Admin-Origin. `read_bearer` liefert die bereits autorisiert
gelesene Berechtigung ausschließlich im Arbeitsspeicher. Die Bibliothek liest
keine Prozessumgebung oder private Datei dafür. Bearer gehören weder in
Argumente, Journale, Standardausgabe noch Logdateien. HTTP-Proxies und Redirects
sind ausgeschaltet; Anfragen sind auf fünf Sekunden, Bodies auf 4096 Byte und
Antworten auf 128 KiB begrenzt. Ein HTTP-Fehler gibt nur einen stabilen Code aus.

## Prüfsequenz

Vor Beginn fremde Zugriffe abstimmen und den aktuellen öffentlichen
Gateway-/Runtime-Pin erfassen. Private Ist-Zustände bleiben im privaten Journal
des Operators. Diese Schrittfolge ist ein Bedienvertrag, keine automatisch
ausführende Installationsanweisung:

1. Den eigenen temporären Tunnel separat aufbauen und seine Identität prüfen
   (siehe unten). Die Test-URL darf ausschließlich dessen Pi-Loopbacklistener
   bezeichnen. Der Helper startet weder Sonard noch einen Sender.
2. `begin(parameters, control)` prüft den aktuellen Config- und M0-/INHERIT-Stand
   und legt ein neues Journal mit `0600` an. Ein bestehendes Journal wird niemals
   überschrieben. Es enthält Parameter, Hashes und Auftragsmetadaten, keine
   vollständige TOML, Einstellungsdokumente oder Zugangsdaten.
3. `change_url(parameters)` ersetzt ausschließlich
   `[source].upstream_base_url`. Byteformat, Kommentare und alle anderen
   semantischen Werte bleiben erhalten. Der ursprüngliche SHA-256, frische
   Dateiidentität und Metadaten müssen unmittelbar vor der atomaren Ersetzung
   noch passen. Modus und Besitzer bleiben erhalten.
4. Einen notwendigen Gateway-Neustart führt ausschließlich der bereits
   autorisierte externe Operator aus. Danach aktuelle Prozess-/Runtimeidentität
   und den tatsächlich geladenen Config-Pfad erneut prüfen. Der Helper kann
   weder einen Neustart auslösen noch bestätigen, welche Konfiguration ein
   laufender Prozess geladen hat.
5. `change_mode(parameters, control)` liest die aktuelle Serversitzung,
   Revision und alle anderen Einstellungen neu. Er kopiert genau diesen Stand,
   ändert ausschließlich `settings.sonar.mode` und sendet einmal den vorhandenen
   revisionsgebundenen `apply`-Auftrag mit eindeutiger Request-ID. Die
   unveränderten Nicht-Sonar-Einstellungen müssen im bestätigten Ergebnis
   übereinstimmen. Neuere fremde Änderungen nach diesem Ergebnis werden als
   solche erfasst und bleiben erhalten.
6. Den eigentlichen Quellentest separat ausführen: geprüfte HTTPS-Identität,
   tatsächliche Gatewaygeneration und Producer, ursprüngliche Pingzeiten,
   Metadaten/Framezuordnung und sichtbare App-Reaktion binden. Keine Rohdaten oder
   privaten Gerätewerte in den öffentlichen Beleg übernehmen.
7. `change_mode(parameters, control, restore=True)` nimmt nur den noch eigenen
   Testmodus zurück. Wieder aktuelle Revision und Nicht-Sonar-Einstellungen
   verwenden; niemals den anfänglichen Gesamtstand zurückschreiben.
8. `change_url(parameters, restore=True)` nimmt nur die weiterhin eigene
   Test-URL zurück. Andere zwischenzeitlich geänderte TOML-Werte bleiben erhalten.
   Der Modusrückbau muss zuvor bestätigt sein oder es darf noch überhaupt kein
   Modusauftrag begonnen worden sein. Danach den nötigen externen Neustart und
   die tatsächliche Rückkehr des ursprünglichen Pfads gesondert belegen.
9. Nur den eigenen Tunnel beenden. Den öffentlichen Ergebnisstatus mit den
   Quellen-/Runtimehashes ablegen; das private Journal verbleibt außerhalb Git.

Die vier Bibliothekseinstiege bilden absichtlich keinen allgemeinen Remote- oder
Service-CLI. Ein lokaler autorisierter Aufrufer konstruiert die expliziten
`Parameters` und übergibt die bereits vorhandene Berechtigungsfunktion. Der
gewählte Runtime-Pin benötigt Python 3.11 oder neuer wegen `tomllib`.

## Separater, eigener SSH-Tunnel

Für den ausgeführten Mac-Sonard-/Pi-Gateway-Test liegt Sonard auf Mac-Loopback;
der eigene SSH-Reverse-Tunnel veröffentlicht ihn ausschließlich auf einem
ausdrücklich gewählten Pi-Loopbackport. Der schematische Aufruf lautet:

```text
ssh -N -T -o ExitOnForwardFailure=yes \
    -R 127.0.0.1:<TEST_PORT>:127.0.0.1:<MAC_SONARD_PORT> <AUTHORIZED_SSH_PROFILE>
```

Diese Platzhalter sind keine eingebauten Helperwerte. Hostschlüsselprüfung
bleibt aktiv. Zielprofil, Ports, Eigentümer-PID/Prozessstart und eventuell ein
eigener Control-Socket werden vor Verwendung erfasst und separat privat
gebunden. Ein fehlgeschlagener Portbind ist ein Abbruchgrund. Keine fremden
SSH-Verbindungen, Listener, Hosts, Aufnahmeprozesse oder vorhandenen Tunnel
beenden. Beim Rückbau die eigene Prozess-/Socketidentität erneut prüfen und
ausschließlich diesen Teilnehmer schließen. Kein `pkill`, kein pauschales
Portfreiräumen und keine wiederverwendeten fremden Control-Sockets.

Tunnelaufbau, Konfigurationsneustarts und physische Abnahme sind ausdrücklich
**nicht** Bestandteil der synthetischen Helpertests oder automatisch
installierter Rebuild-Inputs.

## Konflikte und unklarer Abschluss

- Jede Operation hält das private Journal unter exklusiver Dateisperre. Es ist
  ein begrenztes, synchronisiertes JSONL-Protokoll; ein unvollständiger letzter
  Datensatz, Zielwechsel oder kaputtes Schema verhindert die Wiederaufnahme.
- Der URL-Schreibpfad verwendet eine eigene zufällige `O_EXCL`-Stagingdatei,
  frisches SHA-/Inode-/Metadaten-Readback, `fsync` und atomare Ersetzung. Nur eine
  nachweislich noch eigene Staging-Inode darf entfernt werden. Dateien und
  sämtliche übergeordneten Pfade werden ohne Symlinkverfolgung geöffnet.
- Das ist ein koordiniertes, dateibasierendes Compare-and-Replace mit
  zusätzlicher advisory Sperre, kein Kernel-CAS gegenüber einem unkooperativen
  root-Schreiber im letzten Moment zwischen Readback und Rename. Solche
  parallelen Installations-/Config-Schreiber müssen vorher abgestimmt bleiben.
- Mode-CAS wird vom vorhandenen Gateway anhand Sitzung und Revision atomar
  entschieden. Bei Timeout, verlorenem ACK, widersprüchlichem Ergebnis oder
  geänderter Sitzung lautet der Journalstand `COMPLETION_UNKNOWN`. Kein zweiter
  Versuch und keine blinde Rücknahme desselben Auftrags.
- Bereits gespeicherte `APPLY_INTENT`/`RESTORE_INTENT`-Zustände werden nach einem
  Prozessverlust nicht automatisch fortgesetzt. Zuerst tatsächliche Datei,
  Sitzung, Revision und Betrieb getrennt abgleichen. Der Extrakt bietet dafür
  bewusst keinen automatischen Eigentums-Reclaim.
- Ein eigener Wert, den ein fremder Schreiber später auf exakt denselben Wert
  setzt, lässt sich allein aus dem Wert nicht unterscheiden. Deshalb gelten die
  zusätzliche Testbesitz-/Zugriffsabstimmung und der frische Readback weiterhin.
- Keine Journal-, Config- oder Verzeichnisbereinigung bei fremden Dateien,
  Symlinks, falschem Besitzer oder beschädigtem Zustand. Unklarheit bleibt ein
  konkreter Abnahmeblocker.

## Gezielter öffentlicher Hostnachweis

```text
PYTHONPATH=src:tests <EXISTING_TEST_RUNTIME>/bin/python \
    -m unittest -v test_temporary_sonar_path
```

Die Tests benutzen ausschließlich temporäre synthetische Dateien und den echten
`SimulationController` als lokalen CAS-Teilnehmer. Nur der lokale
Linux-/UID-/Zielguard wird für diese Dateifixtures ersetzt und daneben gezielt
negativ geprüft. Sie öffnen keine SSH-Verbindung, keinen Dienst und keinen
Audiopfad. Der gesonderte Beleg
`verification/TEMPORARY_SONAR_PATH_HOST_20261016.json` bindet die tatsächlichen
Quellen und Ergebnisse. Physischer Einsatz dieses neuen Extrakts bleibt darin
`NOT RUN`; historische Live-Ergebnisse werden nicht nachträglich als seine
Ausführung ausgegeben.

Die Rebuild-Abgrenzung ist ein Dokumentationsverweis auf diesen Quellvertrag,
kein Installations-/Bootrezept für den temporären Testteilnehmer.

# Gemeinsamer Zugang mit Key oder Passwort

## Nachtrag: Passwortzugang im Browser geprüft

Am 10. Oktober wurden die nginx-Zugänge für Camilla und Simulator ausdrücklich
nachgeprüft. Beide gemeinsamen Eingaben verwenden lokale WebCrypto-Ableitung.
Die veraltete `minlength="16"`-Angabe in Camillas Login-HTML ist entfernt;
das Formular hatte die native HTML-Validierung bereits übersprungen.
Simulator „Neu laden“ verwendet nun den im Arbeitsspeicher vorhandenen
abgeleiteten Zugang, nachdem das Eingabefeld bei der Anmeldung geleert wurde.
Eine neue explizite Eingabe ersetzt diesen Zugang, ohne Passwortzeichen zu trimmen.

`tools/refresh_password_browser.py` aktualisiert nur die schon erweiterten
öffentlichen Browserdateien, standardmäßig als lesender Plan. Mit `--apply`
sichert es die tatsächlich geänderten Dateien privat, prüft nginx-Syntax und
den Erhalt von Auth-/Gateway-/Audiozustand. Keine Passwortbereitstellung,
kein Auth-/Gateway-/Audio-Neustart und kein nginx-Reload. Bei einem späteren
Rückbau der ursprünglichen Passwortproxy-Migration zuerst die zwei Dateien
aus dieser Nachtragsreserve zurücknehmen, damit deren Datei-Schutzprüfungen passen.

Bestanden: 19 isolierte echte nginx-Fälle, 31 echte Labor-TLS-/API-Fälle mit
einem kurzen künstlichen Passwort einschließlich Simulator und Camilla-Sitzung,
GUI-Weiterleitung und GUI-Seite. Danach Auth-Dateien exakt wiederhergestellt,
22 bestehende Key-Fälle erneut bestanden. Echter Pi: 22 Key-/TLS-/HTTP-/WS-
Fälle bestanden, persönlicher Prüfdatensatz erhalten. Zusätzlich 32 Playwright-
Prüfungen der tatsächlich ausgelieferten öffentlichen Pi-/Labor-Dateien gegen
einen lokalen künstlichen Backendvertrag; keine Produktivzugänge im Browser.
Persönliche Passwortanmeldung auf dem Pi bleibt vom Nutzer auszuführen.

Nachweis: `/Users/chris/projects/max/password-browser-20261010/README.md`.
Pi-private Browserreserve: `/var/backups/no5-password-browser-20261010T115736Z`.
Laborreserve: `/var/backups/no5-password-browser-20261010T115729Z`.

Stand: 10. Oktober 2026. Die nginx-Erweiterung ist auf dem echten Pi und im
Labor aktiv. Der vom Nutzer gelieferte persönliche Prüfdatensatz ist auf dem
echten Pi installiert; im Labor bleibt er unveröffentlicht.
Die drei Android-Apps sind gebaut und an DeploymentLounge übergeben;
ihre neue native Geräteabnahme ist noch nicht ausgeführt.

## Bedienung und einmaliger Prüfwert

Home besitzt am bisherigen Key-Zugang genau einen Dialog „Gateway-Zugang“:
ein Eingabefeld für Gateway-Key oder Passwort, „Verbinden“ und „Key-Datei laden“.
Es gibt kein zusätzliches dauerhaftes Passwortfeld, keine Passwort-Einrichtung,
keinen Passwortwechsel und keinen Registrierungsendpunkt in der App.
Die vorhandenen NO5-GATEWAY-KEY-V1-Dateien bleiben unverändert verwendbar.

Der Nutzer erzeugt seinen Prüfwert selbst in einem interaktiven lokalen Terminal:

```sh
python3 /Users/chris/projects/max/no-pi/projects/no5-pi-gateway/tools/create_password_verifier.py
```

Das Tool liest die Passphrase verdeckt zweimal, erzeugt zwei unabhängige
zufällige 32-Byte-Salts und gibt ausschließlich einen kleinen JSON-Datensatz
`NO5-PASSWORD-VERIFIER-V1` aus. Nur diesen Datensatz an Codex übergeben.
Es gibt keine Passwortargumente, Netzverbindung, Telemetrie oder Klartextdatei.
Der Datensatz enthält keinen direkt verwendbaren Anmelde-Prehash.
Er wird ausschließlich über den bestehenden administrativen Pi-Zugang
mit `tools/install_password_verifier.py` installiert. Das Tool erstellt zuerst
eine Pi-private Sicherung und startet nur den separaten Passwort-Prüfdienst neu.
Der vorhandene Gateway-Key wird weder ersetzt noch widerrufen. Der neue öffentliche Client-Salt macht zuvor gespeicherte abgeleitete Zugänge ungültig; bereits eingegebene Keys müssen dann erneut eingegeben werden. Importierte alte Key-Dateien bleiben gültig.

Die Passwortwahl hat keine Mindestlänge oder Zeichenklassen-Vorgabe. Das Tool
liest das frei gewählte Passwort zweimal verdeckt und gibt den Prüfdatensatz
nur bei Übereinstimmung aus. Leere Eingaben werden abgewiesen; die technische
Obergrenze von 512 Zeichen entspricht den bestehenden App-/Browser-Clients.
Hashing macht
ein schwaches oder bereits bekanntes Passwort nicht unerratbar: Wer den
Prüfdatensatz kennt, kann Kandidaten offline ausprobieren. Die langsame,
gesalzene Ableitung erhöht die Kosten.

## Ableitung und Transport

App und Browser leiten jede eingegebene Zeichenfolge lokal als
PBKDF2-HMAC-SHA256 mit 600.000 Iterationen und dem öffentlichen Client-Salt ab.
Das Ergebnis sind 32 Bytes, übertragen als 64 hexadezimale Zeichen im Header
`X-No5-Gateway-Password` über streng geprüftes HTTPS/WSS. So muss die Oberfläche
nicht erraten, ob eine Eingabe ein Key oder ein Passwort ist. Eine eingegebene
Passphrase wird auch dann nicht als Klartext-Key gesendet, wenn sie wie ein Key
aussieht. Importierte Dateien benutzen weiterhin `X-No5-Gateway-Key`.

Der Pi speichert für das Passwort eine zweite, unabhängig gesalzene
PBKDF2-Ableitung dieses Client-Prehashs. Der gespeicherte Prüfwert selbst wird
als Login abgewiesen. Klartextpasswort und wiederverwendbarer Client-Prehash
stehen nicht im Pi-Prüfdatensatz. Für eingegebene alte Keys existiert zusätzlich
ein SHA-256-Prüfwert ihrer lokalen Ableitung. Die vorhandene Key-Konfiguration
bleibt wegen des parallelen alten Mechanismus bestehen.

Der übertragene Client-Prehash ist selbst eine wiederverwendbare Berechtigung.
Dies ist Passwortauthentisierung über TLS, kein PAKE oder frischer
Challenge-Response-Austausch. Die App speichert ausschließlich diesen Prehash
mit AES-256-GCM im Android Keystore, an die genaue Origin gebunden und getrennt
vom alten Key-Store. Eingabearrays werden nach Gebrauch gelöscht; das Eingabefeld
hat keine Zustands- oder Autofill-Speicherung. Ein fehlgeschlagener Passwortversuch
wechselt nicht still zum alten Key. Ein bewusster Dateiimport wählt den Key erneut;
der getrennt gespeicherte abgeleitete Zugang bleibt erhalten. Import-Rollback und
Readback erhalten den vorher ausgewählten Zugang.

Die Iterationszahl entspricht der dokumentierten PBKDF2-HMAC-SHA256-Empfehlung
im [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
ASCII- und Unicode-Ableitungen wurden gegen unabhängige Python-Testvektoren geprüft.

## nginx und bestehende Dienste

`no5-password-auth.service` läuft als eigener nicht anmeldbarer Systemnutzer
`no5auth` ausschließlich auf `127.0.0.1:8790`. Seine Daten sind privat, die
HTTP-Schnittstelle enthält keinerlei Passwortregistrierung oder Schreibroute.
Unbekannte Ableitungen sind auf fünf Versuche pro IP und zwanzig insgesamt pro
Minute begrenzt; höchstens zwei teure Prüfungen laufen gleichzeitig. Bestätigte
Ableitungen werden nur im Arbeitsspeicher für 15 Minuten gecacht.

nginx verwendet `auth_request` für die App-API, Simulator-API und die neue
Camilla-Anmeldung. Erst nach erfolgreicher Prüfung liefert eine Root-private
Include-Datei den bestehenden Key an das unveränderte Loopback-Gateway.
Der Client-Prehash wird vorher entfernt. Die zusätzliche AUDIO-Prüfung im
Gateway, HTTP, SONAR- und Fader-WebSockets funktionieren so weiter.
Der Simulator behält seinen privaten internen Bearer. CamillaGUI benutzt
weiterhin den bisherigen Cookie-Prüfer; Browser-Eingaben verwenden nun dieselbe
lokale Ableitung und `/camilladsp/password-session`.

Aktivierung: bestehende nginx-Konfiguration erweitert, Syntax geprüft und
nginx neu geladen. Kein Gateway-Release-Wechsel, keine Paketinstallation,
kein Audio-Daemon-Neustart und keine Audio-Bedienbefehle.
CA, Server- und CA-Schlüssel, alter Key, Audio-Presets und Gateway-Konfiguration
wurden gegen ihren vorherigen Stand geprüft und erhalten.

Anonyme GET-Endpunkte: `/auth/v1/ca` und `/auth/v1/parameters`.
`/auth/v1/check` bestätigt eine Berechtigung mit 204 oder weist sie mit 401 ab.
`/auth/v1/password` bleibt immer 404, auch mit gültigem Key.

## Öffentliche CA automatisch laden

Bei fehlender oder beschädigter lokal gespeicherter CA benutzen die Apps `/auth/v1/ca` ohne
Credential. Der Bootstrap prüft zuerst eine bekannte Root-CA-Kennung, dann die
normale PKIX-Zertifikatskette und den logischen Hostnamen. Größe ist auf 64 KiB
begrenzt; Weiterleitungen sind abgeschaltet. Es gibt keinen Trust-all-Client,
keinen öffentlichen HTTP-Listener und keine System-Truststore-Änderung.
Eine gültige vorhandene CA bleibt erhalten. Unlesbare oder ungültige lokale
Daten werden automatisch durch den vollständig geprüften Download ersetzt.
Fehlschläge löschen den bestehenden Eintrag nicht; Home versucht den Abruf bei
der nächsten Verbindungsprüfung erneut. Der manuelle öffentliche CA-Import
bleibt für andere Origins oder einen bewussten CA-Wechsel verfügbar.

Die beiden Kennungen wurden über die bestehenden vertrauenswürdigen
administrativen Verbindungen bezogen und gelten ausschließlich für
`https://lysmarine.local:9443` und `https://no5-marineos-lab.local:9443`.
Eine andere Origin benötigt eine eigene bekannte Kennung oder manuellen CA-Import.
Die Apps weichen nicht zwischen echtem Pi und Labor aus.

nginx sendet dafür Blattzertifikat und öffentliche Root-CA gemeinsam aus der
zusätzlichen Datei `/etc/no5-gateway-proxy/tls/server-with-ca.crt`.
Die ursprünglichen Zertifikats- und Schlüsseldateien bleiben erhalten.
Der vorhandene Labor-Netzabgleich aktualisiert diese zusätzliche Kette bei
seiner regulären Blattzertifikats-Erneuerung. Auf dem echten Pi rotiert der
Netzabgleich keine Zertifikate. Bei einer späteren manuellen Blatt-Erneuerung
muss auch die zusammengesetzte Kette neu erstellt werden; ein CA-Wechsel braucht
zusätzlich eine passende neue bekannte Kennung oder den bewussten App-Import.

## Ausgeführte Prüfungen und Grenzen

- PASS: vom Nutzer gelieferter Prüfdatensatz auf dem physischen Pi installiert,
  Parameterschnittstelle bestätigt den neuen Datensatz, 17 bestehende Key-/
  HTTP-/WS-Prüfungen und Ablehnung des gespeicherten Prüfwerts als Login.
  Nur der Prüfdienst neu gestartet; Gateway und Audio-Daemons erhalten.
  Kein Klartext oder direkt verwendbarer Client-Prehash erhalten.
- PASS: 17 synthetische Prüfungen mit echtem nginx und echtem Passwort-Prüfer,
  getrennte Unix-Sockets und künstlicher Upstream, ohne Produktivzugriff.
- PASS: Labor mit temporärem offline erzeugtem künstlichem Passwort-Prüfwert:
  21 echte TLS-/API-Prüfungen einschließlich HTTP, SONAR/Fader-WebSockets,
  parallelem Key und Ablehnung des gespeicherten Prüfwerts. Anschließend exakt
  die vorherigen Auth-Dateien wiederhergestellt und 17 Key-Prüfungen bestanden.
- PASS: echter Pi, 17 lesende TLS-/API-Prüfungen mit importiertem und eingegebenem
  bestehendem Key, Simulator und Camilla-Anmeldung. Geschützte Dienste, Audio-
  und Credential-Dateien vor/nach der Aktivierung und Prüfung erhalten.
- PASS: tatsächliche öffentliche CA beider Hosts mit der kompilierten produktiven
  Kotlin-TrustManager-Klasse auf Host-JDK17 heruntergeladen; falsche Kennung und
  falscher Hostname abgewiesen. Dies ist kein nativer Android-TLS-Nachweis.
- PASS: Gateway-Suite auf vorhandener Pi-venv, 103 Tests, null Fehler;
  vier optionale HELM-Quell-/JSON-Schema-Tests wegen fehlender Extras übersprungen.
- PASS: Home132, HELM457, MAP21 und MAP-Client4 JVM-Tests; Domain170 unverändert
  UP-TO-DATE. Android-Build/Lint aller drei Apps und Instrumentation-Kompilierung
  für HELM/MAP. Simulator78 Tests bestanden.
- NOT RUN: persönliche Passwortanmeldung, neuer Android-Dateipicker/Keystore/
  erster CA-Download auf einem Gerät, native UI-/Touch-/Lifecycle-Abnahme,
  tatsächlicher Pi-Neustart und echter Zertifikatswechsel.

Rohbelege und exakte APK-Hashes:
`/Users/chris/projects/max/password-gateway-20261010/`.
Pi-private Prüfdatensatz-Reserve: `/var/backups/no5-password-verifier-20261010T102129Z`.
Pi-private Proxy-Rückbaureserve: `/var/backups/no5-password-proxy-20261010T095749Z`.
Labor: `/var/backups/no5-password-proxy-20261010T094732Z`; separate Ergänzung
zur Kettenerneuerung: `/var/backups/no5-lab-chain-refresh-20261010T100217Z`.
Rückbau prüft alle betroffenen Dateien vor dem Stoppen des Prüfdienstes und
restauriert nur diese nginx-/Browser-Erweiterung. Der persönliche Prüfwert bleibt
bei einem späteren Rückbau in seiner privaten Datei erhalten. Keine Git-
Veröffentlichung, keine CI und keine direkte Android-Installation ausgeführt.

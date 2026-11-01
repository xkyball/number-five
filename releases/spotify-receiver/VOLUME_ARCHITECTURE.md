# Gemeinsame Lautstärke und flüssige Fader · Recherche 8. Oktober 2026

Empfehlung: CamillaDSP bleibt die einzige Stelle, die die Systemlautstärke am
Audiosignal verändert. Der Spotify-Connect-Regler, HELM und ein späterer Knopf
geben ihre Lautstärkewünsche an denselben lokalen Koordinator. Der Receiver
verwendet dafür einen eigenen librespot-Mixer ohne zusätzliche PCM-Absenkung.
Spotify zeigt die bestätigte Systemlautstärke als seinen Gerätepegel an.
Die beauftragte Implementierung liegt jetzt im Receiver, Gateway und HELM56.
14 Receiver-Hosttests (ein Unix-Test auf macOS übersprungen), 82 Gatewaytests
und 515 App-/170 Domaintests bestehen. Die Laboraktivierung ist noch offen;
der native Linux-Build ist unvollständig; jede Labor-Nutzung ist inzwischen
durch ausdrückliche dringende Nutzeranweisung gesperrt. Eigene Zugriffe sind
beendet, ohne die VM oder fremde Dienste herunterzufahren. Die folgende Bestandsbeschreibung
beschreibt den vor der Änderung untersuchten Zustand.

## Belegter Ausgangszustand

- No5 verwendet librespot **0.8.0**, ausdrücklich den `softvol`-Mixer und dessen
  `get_soft_volume()` im Player. Spotify verändert damit bereits die PCM-Amplitude.
- Der Gateway verändert zusätzlich CamillaDSP **4.1.3** Main in dB. Beide
  Absenkungen wirken hintereinander; der GesamtdB-Wert ist ihre Summe.
- HELM sendet den Faderwert erst beim Loslassen. Der Adapter wartet bis zum
  nächsten HTTP-Poll, sendet einmal und sperrt während des ACK alle Writes.
- Camilla-Lesen und jedes Schreiben öffnen derzeit eine neue lokale
  WebSocket-Verbindung. Nach jedem Write wird ein vollständiger DSP-Snapshot
  mit mehreren Requests gelesen. Das ist kein kontinuierlicher Faderkanal.
- Die Android-Ansicht zeichnet einen gemeinsamen Canvas. Alle Bedienelemente
  wechseln während `pending` sichtbar in den gesperrten Stil. Neuzeichnen allein
  beweist keinen sichtbaren Flash; dieses globale Umfärben ist im Code belegt.

## Vergleich der vorgeschlagenen Wege

| Variante | Wirkung | Urteil |
| --- | --- | --- |
| Camilla Main fest auf 0 dB, HELM steuert Spotify | Eine Regelstelle für Spotify; Connect-Rückmeldung über `Spirc::set_volume` möglich | Funktioniert nur für Spotify. Andere Eingänge und unabhängige Gesamtlautstärke fehlen; kein beliebiger positiver Camilla-Gain als „max“ |
| Periodischer Job setzt Spotify auf 100 % | Telefonänderungen wirken kurz und werden danach zurückgesetzt | Verwerfen: zwei gegeneinander arbeitende Regler, Pegelsprünge, zusätzlicher Pollbedarf |
| Spotify PCM ohne eigene Absenkung; Connect-Regler steuert Camilla, Camilla meldet zurück | Eine Regelstelle für alle Eingänge; Telefon, HELM und Knopf teilen einen bestätigten Pegel | Empfohlen; eigenes Mixer-/Koordinatorprotokoll und Rückkopplungsschutz nötig |

`Mixer` besitzt `volume()`, `set_volume(u16)` und `get_soft_volume()`. Die
Default-Implementierung des letzten Aufrufs verwendet `NoOpVolume` mit
Amplitudenfaktor **1.0**. Ein eigener Mixer kann deshalb die angeforderte
Connect-Lautstärke an den Koordinator liefern, ohne die Samples ein zweites Mal
zu skalieren. No5 darf dafür nicht einfach den vorhandenen SoftMixer auf 100 %
zurücksetzen: der sichtbare Connect-Regler soll den gewünschten Pegel behalten.

Die Gegenrichtung ist ebenfalls vorhanden: `Spirc::set_volume` publiziert eine
Lautstärkeänderung über Connect, ruft dabei aber selbst wieder `Mixer::set_volume`
auf. Ein simples beidseitiges Weiterreichen erzeugt deshalb eine Schleife.
Der Koordinator braucht monotone Änderungsnummern, Herkunft und Gleichwertprüfung:
bestätigte Echo-Werte werden nicht erneut an Camilla geschrieben. Alle lokalen
Controller werden seriell verarbeitet; pro Ziel existiert nur der neueste noch
nicht gesendete Wert. Ein Cache-/Sitzungswechsel darf keine alte Lautstärke wieder
einspielen. `Spirc::set_volume` wirkt nur für das aktive Connect-Gerät.

## Fadertransport

HELM → vorhandener authentifizierter Gateway über **WSS** → dauerhaftes lokales
Camilla-WebSocket. Hostname, HOME-Auflösung, API-Key-Mechanismus und gültige
Zertifikatsprüfung bleiben erhalten. Ein schmaler Gateway-Vertrag erlaubt nur
freigegebene Fader-/Mute-Ziele und deren Readback; Camilla `SetConfig`, `Stop`,
`Exit` oder frei gewählte Zieladressen sind kein Teil dieses Kanals.

Beim Ziehen soll HELM den Griff unmittelbar lokal darstellen und während der
Geste fortlaufend den neuesten Zielwert senden. Ausgangspunkt für die Messung:
20–30 Updates/s, höchstens ein Write in Bearbeitung plus ein ersetzbarer nächster
Wert, eindeutige Sitzungs-/Sequenznummern, begrenzte Lebensdauer. Der Release-Wert
wird einmal bestätigt. Verbindungsabbruch verwirft unbestätigte Zwischenwerte;
Reconnect liest zuerst den aktuellen DSP-Pegel und spielt keine Geste nach.
Die endgültige Frequenz wird am tatsächlichen Camilla-Rate-Limit gemessen.

Camilla4.1.3 bietet `SetVolume`, `SetFaderVolume`, `GetVolume`, `GetFaders` und
eigene weiche Lautstärkerampen. `volume_ramp_time` für Main ist konfigurierbar;
der dokumentierte Default ist400 ms. Eine offen gehaltene Verbindung verkürzt
Handshake-/Abfragewege, beseitigt aber keine konfigurierte Rampendauer. Im
geprüften Protokoll ist keine Fader-Push-Subscription dokumentiert. Der Gateway
muss bestätigte Set-Werte sofort und externe Änderungen durch schlanke Reads
an seine WSS-Abonnenten verteilen; `SetUpdateInterval` ist keine solche Subscription.

## Pegelmodell und Abnahme

- Ein gemeinsam definierter, invertierbarer Prozent↔dB-Verlauf gilt für Telefon
  und HELM. Prozent sind kein dB-Wert; die Kurve darf nicht still pro Eingang
  variieren. Null braucht einen klaren Mute-Zustand, getrennt vom letzten Pegel.
- Vor Umstellung muss der bereits wirksame Gesamtpegel übernommen werden.
  Beispiel: Spotify −20 dB plus Camilla −10 dB wird zu Camilla −30 dB, bevor
  Spotify-PCM auf Faktor1 wechselt. Sonst würde die Umstellung20 dB lauter.
- Gerätegrenzen kommen weiterhin aus dem bestätigten Camilla-Katalog. Knopf-
  Geometrie/Absolut- oder Relativwerte und physische Audioabnahme sind noch offen.
- Zu messen: Finger/Knopf→Set-ACK, bestätigter Endpegel, Connect-Anzeige,
  gleichzeitige Telefon-/HELM-Änderungen, Mute/Unmute, Pause, Quellenwechsel,
  Reconnect und Gateway-/Receiver-Ausfall. Lokale WebSocket-RTT ist keine Messung
  der Spotify-Rückmeldung und kein Nachweis hörbarer Latenz.
- Layout: Text bekommt Abstand zu Rahmen; Faderzustand liegt außerhalb der
  Mute-/Plus-/Minusflächen. Statische Flächen/Artwork werden getrennt von
  veränderlichen Reglern aufgezeichnet. Nur das betroffene Element zeigt Busy;
  unbekannte oder verlorene Quellen bleiben tatsächlich gesperrt.

Die Recherche änderte keine DSP-Rampen, Gain-, Routing- oder Receiver-Einstellungen
und eröffnete keinen neuen Netzwerkzugang. Ein neues Gateway-WSS-Write-Ende und
die Lautstärkeumstellung werden vor der Laboraktivierung konkret zur Freigabe
gemeldet; lokale Implementierung und Tests können vorher erfolgen.

Lesende Labormessung (`GetFaders`, zehn Abfragen je Modus, unveränderte Pegel):
auf dem bestehenden Socket Median **0,220 ms**, Maximum **0,300 ms**; einschließlich
neuem Socket Median **0,923 ms**, Maximum **2,438 ms**. Das zeigt, dass der lokale
DSP-Kontrollweg schnell antwortet. Die 250-ms-App-Polltaktung, ACK-Abfolge,
Netzstrecke und DSP-Rampe werden dadurch nicht mitgemessen. Es erfolgte kein Write.
Beleg: `evidence/volume-websocket-read-latency-20261008.json`.

Primärquellen (Versionen passend zum Labor/Receiver):

- [CamillaDSP4.1.3 WebSocket-Befehle](https://github.com/HEnquist/camilladsp/blob/v4.1.3/websocket.md)
- [CamillaDSP4.1.3 Lautstärke/Rampen](https://github.com/HEnquist/camilladsp/blob/v4.1.3/README.md#volume-control)
- [librespot0.8.0 Mixer und NoOpVolume](https://github.com/librespot-org/librespot/blob/v0.8.0/playback/src/mixer/mod.rs)
- [librespot0.8.0 SoftMixer](https://github.com/librespot-org/librespot/blob/v0.8.0/playback/src/mixer/softmixer.rs)
- [librespot0.8.0 Spirc-Lautstärke und Rückmeldung](https://github.com/librespot-org/librespot/blob/v0.8.0/connect/src/spirc.rs)
- [Öffentlicher Spirc-Handle](https://docs.rs/librespot-connect/0.8.0/librespot_connect/struct.Spirc.html#method.set_volume)

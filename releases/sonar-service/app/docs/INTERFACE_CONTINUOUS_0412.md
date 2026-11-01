# Sonard 0.4.12: ausdrücklich gestarteter Dauerbetrieb

Der bestehende Vertrag aus `INTERFACE_DEPTH_RATE_0411.md` bleibt erhalten.
Bildframes, Roh-PINGs, Quellenwahl, 12-Bit-Vollskala 4095,
`no5.depth-rate-control/1` und die getrennten gleitenden 5-s-Raten ändern sich
nicht. Keine HELM-Bedienung oder Gateway-Installation ist Bestandteil dieser
Erweiterung. Verbraucher müssen die neue Paketversion gezielt freigeben.

## Start und Ende

Nach einem erfolgreichen kurzen physischen Test in derselben USB-Session:

```http
PUT /api/v1/transmit
Content-Type: application/json

{"mode":"periodic","duration_s":0,"rate_policy":"depth_auto"}
```

Dies bewaffnet nur. Genau ein neuer ausdrücklicher
`POST /api/v1/transmit/fire` mit `{"request_id":"UUID"}` startet den Lauf.
`duration_s:0` bedeutet ausdrücklich keine feste Laufzeitgrenze. Es ist weder
ein Standardwert noch ein automatischer Neustart. `2`, `30` und `600` behalten
ihre endlichen Testgrenzen. Negative Werte, Boolean-Werte und andere Dauern
werden abgewiesen. Die Politik darf weiterhin nur unbewaffnet wechseln.

`POST /api/v1/transmit/abort` beendet die Ausgabe. Der erneuerbare
Zwei-Sekunden-Lease, tatsächliche Originalmarker, Empfangsbereitschaft,
vollständige Empfangsfenster und die bisherigen Fehlerabbrüche bleiben
zwingend. STOP, USB-Verlust, Leaseablauf, Marker-/Erfassungsfehler oder ein
Hostabbruch führen zum Ruhepegel. Nach Reset und Wiederverbindung ist ein
erneuter ausdrücklicher Start erforderlich. Sonard muss für die Steuerfreigabe
und Erfassung weiterlaufen; die Oberfläche darf geschlossen werden.

Die Grenze wird direkt im Pico entfernt. Es gibt keine wiederholten
600-s-Starts und keine automatische Wiederbewaffnung. Der einzelne PIO-Burst
bleibt endlich: vier Perioden bei 197 kHz, 1000 ns HIGH.

## Status

Additiv zu den bestehenden Feldern:

```json
{
  "continuous_supported": true,
  "continuous": true,
  "policy": {"mode":"periodic","duration_s":0,"rate_policy":"depth_auto"},
  "schedule": {"hard_max_duration_s":null,"lease_s":2},
  "run": {"state":"RUNNING","duration_s":0,"elapsed_s":700.0}
}
```

Das Beispiel ist ein gekürztes Schema, kein Messnachweis. `continuous` bedeutet
die ausdrücklich konfigurierte Laufzeitpolitik; aktiv ist die Ausgabe nur bei
passendem `mode` und `run.state`. `elapsed_s` zählt über 600 hinaus.
`run.verified` bleibt während eines offenen Laufs kein abgeschlossener
Abnahmebeleg. Die aktuelle Qualität und brauchbare Rate stammen weiterhin
aus den tatsächlichen Pings. Ein unerwartetes Geräte-DONE im Dauerbetrieb wird
als Fehler behandelt, nicht als erfolgreicher Abschluss oder Neustart.

## Firmware und Profil

Pico 0.7.12 meldet zusätzlich Capability-Bit 13: `0xC0003FEE` insgesamt.
`TX_CLIP_CONFIG` 0x46 behält seine Paketgröße. Nur im bestehenden
Vierperioden-/27-ms-Interleaved-Profil bedeutet dessen bisheriges Dauerfeld
`burst_count:0` jetzt den ausdrücklichen Dauerbetrieb. Alte Firmware oder alte
Profile unterstützen diese Wahl nicht. Die normale endliche Burstdiagnose
bleibt unverändert.

`deployment/clip-depth-qualified-0712.json` bindet die unveränderten bisherigen
physischen Echo-/R17-/Ratenbelege und exakt Firmware 0.7.12. Zeitplan,
elektrische Burstform und freigegebene Grenze bleiben unverändert. Der Pico
akzeptiert im Dauerbetrieb keine adaptive Untergrenze unter 24.300 µs und keine
Quote über acht eigene Bursts je Originalzyklus. Experimentalprofile und der
R17-Diagnosemodus dürfen keinen Dauerbetrieb starten. Für `fixed_qualified`
bleiben die sieben bisherigen 27-ms-Slots verfügbar.

Die internen Startzähler sind 64 Bit breit; die früheren endlichen
Startzahlgrenzen gelten weiter für endliche Läufe. Sie beenden keinen
ausdrücklich gestarteten Dauerlauf. Die simulierte Prüfung geht in beiden
Zeitplanvarianten über 600 s hinaus und prüft weiterhin Lease, fehlende
physische Marker/Rohzuordnung, STOP, USB-Verlust und Reset.

# Sonard 0.4.15: USB-Liveness

Die Geometrie und der Published-Ping-Vertrag aus `INTERFACE_STRIPES_0414.md`
bleiben unverändert. Drei synthetische vollständige M1-Frames (volle Abdeckung,
qualifiziertes Bildfenster und unbestätigter Zeitbezug) sind bytegleich mit 0.4.14.
Pico-Firmware, Burstform, Detektion, Roharchiv und Sendepolitik ändern sich nicht.

## Transport

Der Host liest weiterhin exklusiv denselben nichtblockierenden seriellen FD.
Zusätzlich zur Bereitschaftsmeldung leert er denselben Empfangspfad auf macOS
alle 10 ms, auf anderen POSIX-Systemen alle 100 ms. Es gibt keinen zweiten Leser
und keine Ersatz-Pings. Drei Sekunden ohne gültiges Paket lösen einen
Verbindungsfehler aus. Eine Wiederbindung startet unbewaffnet; eine Sendung wird
nicht automatisch wieder aufgenommen. STOP, Lease und Firmwaregrenzen gelten
unverändert.

`GET /api/v1/status` ergänzt unter `pico.transport_liveness` den Zustand,
`last_byte_age_s`, `last_packet_age_s`, `read_poll_interval_s`,
`packet_silence_limit_s` und `poll_read_bytes`. Die gezählten Bytes durchlaufen
denselben Wire-Audit und CRC-/Frameparser. Sie verändern keine ADC-Werte.

`rates.last_raw_arrival_age_s` zeigt das Alter des letzten tatsächlich archivierten
Rohpings. Nach mehr als drei Sekunden wird `rates.ping_hz` auf null gesetzt;
`rates.last_observed_ping_hz` hält dann den letzten historischen Messwert bereit.
Die getrennten eigenen/bestätigten/brauchbaren 5-s-Raten bleiben unverändert.

## Reproduzierbare Prüfungen

`tests/test_transport_liveness.py` entfernt im PTY absichtlich die
Bereitschaftsmeldung. Der periodische Leseweg muss die geteilte Wire-Nachricht
unverändert einsammeln. Ein stiller Gegenpart muss genau einen Disconnect und
keine Ausgabe/Neubewaffnung erzeugen. Ein veralteter Rohping darf keine aktuelle
positive Rate vortäuschen. Der Clip-Testgegenpart sendet wie die reale Firmware
Statuspakete auch während seiner Wartephasen.

Der im 0.4.14-Lauf aufgetretene Stillstand ist dokumentiert, nicht als bestandener
Dauerlauf gewertet. Eine kontrollierte Wiederbindung desselben USB-Endpunkts
stellte den Empfang ohne Firmwarewechsel wieder her. Die genaue Ursache im
Betriebssystem bzw. Gerät ist damit nicht abschließend bewiesen.

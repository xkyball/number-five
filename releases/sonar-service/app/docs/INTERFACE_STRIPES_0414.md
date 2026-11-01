# Sonard 0.4.14: Suchaufnahmen, Bildgeometrie und Abstandsmuster

Pico bleibt auf 0.7.12. PING/STREAM und der 40-Byte-M1-Header bleiben binär
kompatibel. Die vier Perioden bei 197 kHz/1000 ns HIGH und sämtliche
qualifizierten elektrischen Grenzen bleiben erhalten. Rohprofile, Detektion
und die physische Tiefenautomatik werden nicht durch Bildfilter verändert.

## Ursache und Korrektur

0.4.13 bildet für 1024 Bildbins bei 2048 Samples jeweils das Maximum aus zwei,
bei einer 16384-Suchaufnahme aus sechzehn Werten. Die Maxima begünstigen in
langen Aufnahmen Rauschspitzen. Zusätzlich konnte die globale Begrenzung der
geschätzten Basislinie einen niedrigen Fernpegel auf den Nahbereich übertragen.

`local_adc_background_v2` begrenzt die Basislinie nur in ihrer lokalen
Nachbarschaft aus drei 256-µs-Referenzzellen. Kein zeitliches Hintergrundbild
wird abgezogen; stationäre Ziele werden nicht durch einen Historienmedian
entfernt. Echoblöcke behalten den bisherigen Schutz.

Bei aktueller, bestätigter eigener Tiefe zeigt eine zusätzliche Suchaufnahme
das gleiche normale Tiefenfenster wie ihre kurzen Nachbarn. Der vollständige
Suchping bleibt unverändert archiviert und wird vollständig detektiert.
Unbekannte, widersprüchliche oder veraltete Tiefe erhält volle Bildabdeckung.
Native und unbestätigte Aufnahmen werden nicht auf ein eigenes Tiefenfenster
beschnitten. Das ist eine reale Begrenzung der dargestellten Abdeckung:
HELM kann ausgeblendete Fernbereiche nicht allein durch Zoom-out zurückholen.

## Politik und gleicher Ping

`GET/PUT /api/v1/sonar/noise-reduction` ergänzt optional `stripe_mode`:

- `auto` (Standard) oder `on`: lokale Basislinie und qualifiziertes Bildfenster.
- `off`: bisherige v1-Bereinigung und vollständige Aufnahmegeometrie. Das ist
  auch der explizite Zugang zur vollständigen Bildabdeckung.

`mode: off/auto/on` bleibt die getrennte Rauschunterdrückung. Ein Aufruf nur
mit `mode` erhält `stripe_mode`; ein Aufruf nur mit `stripe_mode` erhält
`mode`. Änderungen erhöhen die Bildgeneration und verlangen ein Löschen der
alten Bildhistorie; identische Aufrufe bleiben idempotent. Ein automatischer
Fensterwechsel erzeugt keine neue Sourceepoch oder Auswahlgeneration.

Published-Zeile, abgeleitete Aufnahme und Präsentation enthalten für ihren
exakten Ping `display_window` mit Schema `no5.display-window/1`:

| Feld | Bedeutung |
| --- | --- |
| `ping_id` | Identisch zu Published-Zeile, UTC, Session und Segment |
| `capture_sample_count` | Gesamte echte ADC-Aufnahme; weiterhin gleich Published-`sample_count` |
| `display_start_sample` | ADC-Index des darstellbaren TX-Nullbezugs |
| `display_end_sample_exclusive` | Exklusive obere ADC-Grenze des Bildes |
| `display_sample_count` | End minus Start; keine erfundenen Samples |
| `capture_range_m` | Erfassungsreichweite ab TX-Nullbezug, ohne Vortrigger |
| `display_range_m` | Tatsächliche Bildreichweite, exakt als Header-f32 dargestellt |
| `reason` | `full_capture`, `qualified_depth_window` oder `capture_matches_depth_window` |
| `raw_samples_modified` | Immer `false` |
| `detector_uses_full_capture` | Immer `true` |
| `tx_reference_verified` | Bestätigter physischer Nullbezug dieses Pings |

Header-`displayed_max_depth_m`, `presentation.bottom.display_bin` und
`recommendations.range_end_bin` verwenden gemeinsam diese Bildbasis. Physische
Tiefe, Rohindizes, Tracks und Klassifikation werden nicht auf das Bildfenster
umgedeutet. Die bestehenden TX-Flags bestimmen weiterhin, ob überhaupt ein
physisch bestätigter Entfernungsbezug vorliegt; unbekannt bleibt unbekannt.
Bei unbestätigtem TX sind beide Reichweiten `null`, Grund ist
`unverified_time_reference`, Start ist 0 und die Bildzahl entspricht der
vollen Rohzahl. Dies beschreibt exakt die bestehende M1-Ersatzdarstellung:
Originalpayload ab Index0, physische Headerreichweite NaN. In JSON wird NaN
als `null` beschrieben, niemals als erfundene positive Reichweite.

## Begrenzter Versuch mit erkennbaren Abständen

`PUT /api/v1/transmit` ergänzt `interval_pattern: off/native_cycle_code_v1`.
Änderungen sind nur unbewaffnet möglich. Das Experiment ist ausschließlich
mit qualifiziertem schnellen `depth_auto`, ECHO_AD und `duration_s: 2/30`
zulässig. Kein kontinuierlicher Codebetrieb und keine schnellere Freigabe
werden daraus abgeleitet.

Das Muster `[1,1,1,0,0,1,0]` wird durch den tatsächlich beobachteten
Originalzyklus gezählt: 0 fordert den qualifizierten Mindestabstand 24300 µs,
1 fordert 27000 µs. Erfassung und Reserven haben immer Vorrang. Die Firmware
prüft weiterhin Abstand, echte Originalmarker, Empfangsbereitschaft, sichere
Übernahme und Lease. Verspätete Slots werden ausgelassen, niemals nachgeholt.
Es werden ausschließlich bestehende additive 0x47-Pläne verwendet.

`interval_signature` im Sendestatus nennt Muster, letzten angeforderten
Index/Symbol und Revision. Angewandte Fenster und TX-/Rohzuordnung tragen die
zugehörige `interval_signature` im unveränderlichen Derived-Archiv. Nur die
physischen GP16-Marker bestätigen einen wirklich eingehaltenen Abstand.
Das Archiv unterscheidet angefordert, angewandt und tatsächlich beobachtet.
Eine durch ein langes Empfangsfenster verdeckte Variation gilt nicht als
erkanntes Symbol. `acoustic_code_confirmed` und
`multipath_rejection_qualified` bleiben bis zu einem eigenen Nachweis `false`.

Die Abstandsmuster sind Diagnose, keine nachgewiesene Klassifikation von
Fisch/Grund/Mehrweg. Eine Reflexion desselben Pings trägt ebenfalls dessen
Signatur. Keine automatisierte Echo-Löschung wird damit aktiviert.

## Prüfung und Grenzen

`compare_stripe_recording.py` vergleicht beide Darstellungen mit identischen
archivierten ADC-Profilen und protokolliert ihren unveränderten SHA-256.
`make_stripe_contract_fixture.py` erzeugt ausdrücklich synthetische
Published-/Header-/Präsentationsfälle für Gatewaytests, keinen Live-Nachweis.
`verify_noise_archive.py` reproduziert sowohl alte v1-Metadaten als auch die
neuen deklarierten Fenster aus dem unveränderten Wire-/Roharchiv.

Die bekannten Wannenrückläufer können auf Peaklage und Kontrast geprüft
werden. Ungelabelte Wannenprofile beweisen keine vollständige Erhaltung aller
unbekannten schwachen biologischen Ziele. Verbleibende Rückläufer werden
deshalb nicht pauschal als Störung entfernt.

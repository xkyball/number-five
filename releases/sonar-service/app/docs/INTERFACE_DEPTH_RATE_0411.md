# Sonard 0.4.11 / Pico 0.7.11: adaptive Pingrate

Additiver Vertrag. Die ADC-Vollskala bleibt 12 Bit/4095; Echogramm und
PING/STREAM behalten ihre bisherigen Paketformate. Quellenwahl und
`display_selection` bleiben unverändert. Keine implizite Bewaffnung durch
Quellenwahl, Neuverbindung, AUTO-Anzeige oder Empfang dieser Telemetrie.

## Host

`PUT /api/v1/transmit` akzeptiert im neuen Profil zusätzlich
`rate_policy: "depth_auto" | "fixed_qualified"`; Standard ist `depth_auto`.
`mode` bleibt `off`/`periodic`, `duration_s` ist 2, 30 oder 600.
Politikänderungen erfordern unbewaffnete Ausgabe; Konflikt 409, ungültige
Werte 422. FIRE mit eindeutiger UUID bleibt separat; ABORT unverändert.
Alte Profile behalten ihre bisherigen Fähigkeiten und Politik.

`GET /api/v1/transmit` ergänzt `rate_control` (Schema
`no5.depth-rate-control/1`) und `ping_rates`. Das übergeordnete Schema ist
`no5.depth-rate-status/1`; bestehende Zustands- und Zählerfelder bleiben.

`rate_control` enthält `rate_policy`, `depth_m`, `depth_age_s`, `sample_count`,
`receive_range_m`, `sample_period_ns`, `minimum_spacing_us`,
`qualified_min_spacing_us`, `max_own_per_native`, `planned_hz`, `limiting_reason`, `reason`,
`range_reserve_limited`, `experimental_trial`, `trial_spacing_us`,
`pipeline_reserve_us`, `periodic_full_search`, `geometry_errors` und
`applied_window`. Letzteres ist null oder die letzte tatsächlich vom Pico
gemeldete Fensterwahl: `revision`, `sample_count`, `next_min_spacing_us`,
`device_us`, `native_cycle`. Eine Plananforderung allein bestätigt keine
Anwendung. `planned_hz` ist eine konservative Planung bei 380-ms-Originalzyklus,
keine gemessene oder garantierte Rate. Historische `schedule`-Konstanten
beschreiben beim adaptiven Profil ausschließlich die Baseline; für die
adaptive Planung `rate_control` verwenden.

`ping_rates` enthält das beobachtete Fenster mit `window_s: 5`,
`start_device_us`, `end_device_us`, `observed_s`, `complete`, `time_basis`
und `rates`. Zeitbasis sind physische Geräteereignisse; das aktuelle
Fensterende wird anhand der Host-Monotonzeit extrapoliert. `rates.own`,
`.physical`, `.raw`, `.usable` enthalten jeweils `count`, `hz`,
`rolling_1s_min`, `max_gap_s`. Die Werte betreffen ausschließlich eigene
Pings. Bei weniger als einer vollständigen Sekunde bleibt das Minimum null;
ohne Ereignisse sind die Ratenobjekte leer. Teilfenster sind als solche
kenntlich. `usable` verlangt aktuelle eigene LOCKED-Tiefe mit Confidence≥0,75;
es ist kein unabhängiger Nachweis einer richtigen Tiefe. Das Laufmittel
`run.usable_hz` bleibt ein separates historisches Feld.

Die spätere direkte Nutzeranweisung vom 16. Oktober hebt die frühere feste
10-Hz-Mindestvorgabe auf. Maßgeblich ist die höchste brauchbare Rate für die
aktuelle Tiefe, gemessen als `rates.usable.count / observed_s` im gleitenden
5-s-Fenster (`rates.usable.hz`). Teilfenster anhand `complete` kenntlich machen.
Einsekundenminima und größte Pinglücke bleiben Diagnosewerte; größere Tiefe
oder vollständige Suche dürfen die Rate reduzieren. Ein kurzzeitiger
Einzelintervall-Kehrwert ist kein Nachweis einer nachhaltig möglichen Rate.

Gründe sind u.a. `depth_stale`, `unconfirmed_depth`, `stabilizing`,
`range_reserve_limited`, `depth_locked`, `qualified_spacing`, `receive_window`.
`depth_held_fresh` bedeutet: Ein einzelner Ping liefert keinen bestätigten
Kandidaten, die letzte echte eigene Tiefe ist aber noch jünger als 0,9 s.
Das bestehende Fenster bleibt dann erhalten; es wird weder verkürzt noch
der Tiefenzeitstempel erneuert. Dieser Ping zählt nicht als brauchbar.
Ein widersprüchlicher oder fehlerhafter Wert sowie veraltete Tiefe erzwingen
weiter das volle Fenster. Nach einer echten veralteten Phase sind erneut
fünf LOCKED-Pings und eine Sekunde Stabilität für eine Verkleinerung nötig.
Fehlende Werte und unbekannte additive Gründe dürfen keinen kompatiblen
Echogrammstrom verwerfen. Keine TX-Steuerbedienung durch diesen Vertrag
ableiten; die App kann die Politik zunächst ausschließlich lesend zeigen.

## Pico

HELLO 0.7.11, Capability `0xC0001FEE` (zusätzlich Bit12).
Additiver Befehl `TX_DEPTH_CONFIG=0x47`, Little Endian, sieben uint32:
`request, revision, count, spacing_us, floor_us, valid_us, max_own_per_native`.
Bestehendes TX_ACK bleibt unverändert. ACK bestätigt Annahme; Anwendung
erfolgt erst außerhalb einer laufenden/noch nicht emittierten Erfassung.

Revisionen müssen strikt wachsen. Samplezahl 2048..16384 in 256er-Schritten;
Abstand mindestens Samplezahl×2µs+3000µs Pipelinebudget und Hardware-Untergrenze.
Absolute Versuchsuntergrenze 6000µs; TTL 100000..1000000µs.
Eine neue Untergrenze oder Zyklusquote im bewaffneten Lauf wird abgewiesen.
Die Zyklusquote bleibt ohne neue physische Freigabe bei sieben; der lokale
experimentelle Testmodus darf explizit höchstens64planen. TTL-Ablauf
erzwingt vollständige Suchfenster und mindestens 27ms bzw. das längere
Empfangsbudget. Er verlängert weder die TX-Lease noch den 600-s-Hardstop.

TX_EVENT9 meldet vor jeder eigenen Ausgabe das wirksame Fenster im
bestehenden 40-Byte-Layout: `reason=revision`, `id=count`,
`output_start_us=Prüfzeit`, `marker_us=Abstand nach diesem Burst`,
`ping_id=Originalzykluszähler`. Es ist kein Sendemarker. START/MARKER/PING_LINK
bleiben die physischen Zuordnungen. Das JSONL-Archiv verknüpft Fensterrevision,
TX-ID, physischen Marker und Roh-Ping. Jeder Roh-Ping enthält seine tatsächliche
Samplezahl; ein langes Suchfenster wird niemals als kurzes Paket ausgegeben.

## Qualifikation

Normalbetrieb verwendet ausschließlich das hashgebundene qualifizierte Profil.
Der Abschlussstand vom 16. Oktober ist `deployment/clip-depth-qualified-0711.json`:
24.300µs und höchstens acht eigene Bursts je Originalzyklus, gebunden an den
physischen 600-s-Beleg `evidence/depth-rate-physical-20261016.json`.
Die unveränderte Baseline `clip-depth-0711.json` bleibt bei27.000µs/7;
`fixed_qualified` nutzt weiterhin das geprüfte alte Sieben-Slot-Profil.
`--depth-rate-trial-spacing-us` ist ein lokaler ausdrücklicher Testmodus;
er kann keine qualifizierte Grenze verändern. Ein 600-s-Kandidat verlangt
zusätzlich `--depth-rate-trial-evidence` mit bestandenen kurzen/30-s-, R17-
und A/B/A-Nachweisen. Ein niedrigerer Normalgrenzwert erfordert einen
vollständigen hashgebundenen physischen 600-s-Abnahmebeleg. Simulationen und
ACKs ersetzen diese Nachweise nicht. Frequenz197kHz,1000nsHIGH,4Perioden,
GP18/22-Verdrahtung, GP16 als Eingang und Scope-STREAM bleiben bestehen.

Der lokale Testhelfer akzeptiert alternativ zum frischen `--view-proof` ein
ausdrückliches `--headless`. `--api-base` erlaubt einen isolierten lokalen
Testport. Der Beobachtungsmodus wird mit der echten Host-Sitzung archiviert;
Originalmarker-, ARM/FIRE-, Laufzeit- und Abbruchprüfungen bleiben gleich.
Die Nutzerfreigabe vom16.Oktober erlaubt solche Tests; Mitsicht wird ergänzt,
sobald der gemeinsam verwendete Emulator verfügbar ist.

Die lokale Formprüfung kann eine aktuelle schwache Rückkehr ergänzend durch
denselben8µs-Gaussianfilter wie die vorher gemessene Referenz prüfen.
`template_fit_preprocessing` benennt `median_v1` oder `median_gaussian_v1`;
letzteres trägt einen `_smoothed_v1`-Fensternamen. Es gelten weiter aktueller
Peak, sechs Standardfehler, Formkorrelation≥0,8 und zusätzlich höchstens3cm
Abstand zur bestehenden Tracktiefe. Glättungskorrelation geht in die
Unsicherheit ein. Die Rohdaten bleiben unverändert; ein fehlender Rückläufer
wird nicht aus dem Track ergänzt.

## Originalreferenz und Verlustgrund

`transmit.original_reference` hat Schema `no5.original-reference-observation/1`.
`latest` ist null oder die letzte echte Firmwarebeobachtung; `latched_fault`
ist ein seit USB-Bindung aufbewahrter Fehlerbeleg. Neue qualifizierte Marker
ersetzen `latest`, löschen aber den historischen Fehlerbeleg nicht.
`arrival_age_s` und `observation_fresh` kennzeichnen die Empfangsfrische
(430ms); sie diagnostizieren keinen neuen Fehler aus ausbleibenden Paketen.

Die Beobachtung enthält `firmware_event:8`, ursprüngliches `firmware_detail`,
`observation_reason`, `observed_device_us`, `observed_utc_ns`, `utc_basis`,
`observation_kind`, `native_last_device_us`, `native_period_us`, `native_stable`,
`slot`, `meaning`, `received_host_utc_ns`. Nur eine akzeptierte USB-Zeitabbildung
liefert `observed_utc_ns` (`utc_basis: accepted_usb_time_sync`); andernfalls
bleiben beide null. Empfangszeit ist kein Ersatz für die Beobachtungszeit.

Details1/2/3/4 bedeuten ungültiges Originalintervall, Periode außerhalb Profil,
unvollständiger vorheriger Zyklus beziehungsweise Referenzfrist abgelaufen.
Detail4 beobachtet eine `timeout_check`, keinen neuen Marker. Details5/6
bezeichnen außerphasige Markerquarantäne und Wiederkehr einer echten passenden
Originalflanke. Neu: Detail11 ist eine vom Pico tatsächlich phasengeprüfte
Originalflanke und wird auch im passiven Betrieb gemeldet. START-Zeit und
fehlende Zähler werden hierfür nicht umgedeutet.

`recovery_rule`, `latched_until` und `tx_mode` unterscheiden Wiederkehr des
Markers von Sendefreigabe: Quarantäne kann nur bei echter passender Original-
flanke und laufender Lease enden; ein echter Referenzfehler verlangt weiter
ein ausdrückliches neues ARM/FIRE. USB-Bindung löscht den historischen Latch.
Tests: `test_original_reference_preserves_detail_observed_time_and_latched_fault`.

Für den gezielten R17-Erholungstest erlaubt der lokale Testmodus zusätzlich
`--depth-rate-trial-probe-source R17_a`: ausschließlich2s, normale kurze
Fenster2048Samples plus unveränderte lange Suchproben. Dies simuliert die
Sendefolge des Flachwasserprofils ohne vorgetäuschte R17-Tiefe.
`capture_input_source` bezeichnet den Messabgriff; der Akquisitionskontext
archiviert ihn. Tiefenableitung wird für R17 deaktiviert. Ein vollständig
zugeordneter R17-Lauf ist kein Echonachweis (`echo_qualification:false`) und
erfüllt weder die kurze Echo-Freigabe noch eine30-/600-s-Freigabe.

Die kompakte Peakprüfung archiviert `compact_reference_windows:4`: Sie
verwendet das zusammenhängende Viertel mit dem niedrigsten Median im
Rauschreferenzbereich. So erhöht ein naher Rückläufer seine eigene Schwelle
nicht. `1` bezeichnet die vorherige vollständige Referenz für Replays.
Prominenz, physische Breite und zeitliche Trackbestätigung bleiben erhalten.

Die Qualifikationswerkzeuge unterscheiden Tiefengenauigkeit und Stabilität.
`verify_depth_rate_archive.py --empirical-reference BELEG` verwendet nur einen
vor dem Prüflauf aufgezeichneten physischen Rückläufer als feste Referenz.
Der tatsächliche LCD-Wert bleibt Pflicht; seine Übereinstimmung wird separat
als `lcd_agreeing_fraction` und `lcd_qualification_passed` ausgegeben. Ein
bestandener Stabilitätstest ist damit keine bestandene LCD-Kalibrierung.
Am 16. Oktober blieb der Geber auf ausdrücklichen Nutzerwunsch bei LCD 1,1 m;
der unabhängige Rückläufer lag bei 1,25175 m. Diese Referenz verändert weder
die Live-Tiefenauswahl noch Rohdaten oder die Tiefenautomatik.

`analyze_depth_echo_motion.py` dokumentiert den Abstandsvergleich mit den
tatsächlichen LCD-Positionen. Eine Rückkehr von A=1,0 m über B=0,8 m auf
A′=1,1 m wird ausdrücklich als A/B/A′ mit LCD-korrigierter Schließabweichung
ausgewertet, niemals als Rückkehr an exakt dieselbe Position bezeichnet.
Der frühere strenge Vergleich bleibt als eigener fehlgeschlagener Beleg
erhalten. Beide Auswertungen sind reine Offline-Prüfungen.

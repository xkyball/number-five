# Sonard 0.4.13: automatische Rauschunterdrückung

Pico bleibt auf 0.7.12. Keine Änderung an ADC, Erfassungsfenstern, Detektion,
Tiefenauswahl, Klassifikation, Sendeparametern oder Roharchivformat.
NumPy/SciPy sind vorhandene Laufzeitabhängigkeiten; keine neue Bibliothek.

## Datenweg

1. Original-PING unverändert archivieren und mit der bisherigen Detektion auswerten.
2. `robust_adc_background_v1` erzeugt eine eigene Anzeige-Kopie.
3. Erst danach auf die bisherigen 1024 Echogramm-Bins verdichten.

Die robuste Schätzung verwendet zeitlich 256 µs breite Referenzzellen,
Median und MAD (1,4826), ohne TX-Blankingbereich und ohne aktuelle Echoblöcke
einschließlich 50 µs Rand. SciPy glättet ausschließlich die grobe Rauschschätzung;
die Roh-Echoform wird weder median-gefiltert noch zeitlich gemittelt.
Der interpolierte Rauschboden wird abgezogen. Außerhalb geschützter Blöcke
und kurzer kohärenter Strukturen folgt eine weiche Schwelle von zwei Sigma.
Es werden keine Pings ergänzt und keine alten Echoprofile subtrahiert.
Stationäre detektierte Ziele bleiben damit erhalten.

Die Ausgabe ist ADC-Kontrast nach Hintergrundabzug, keine kalibrierte
akustische Leistung und kein Sv/dB-Wert. Schwache, noch nicht erkannte
Strukturen können abgeschwächt werden. Unbeschriftete Badewannendaten
belegen keine Fisch-Erkennungsrate. Für genaue Auswertung bleiben die
ursprünglichen ADC-Werte verfügbar.

## Politik und Status

`GET /api/v1/sonar/noise-reduction` liefert:

```json
{
  "schema": "no5.sonard-noise-reduction-status/1",
  "policy": {
    "mode": "auto",
    "revision": 1,
    "algorithm": "robust_adc_background_v1",
    "scope": "echogram_only",
    "reference_cell_us": 256,
    "threshold_sigma": 2.0,
    "raw_samples_modified": false,
    "detection_input": "original_adc",
    "transmit_input": "original_detection"
  },
  "latest": null,
  "display_generation": 1
}
```

`PUT /api/v1/sonar/noise-reduction` akzeptiert ausschließlich
`{"mode":"off"}`, `{"mode":"auto"}` oder `{"mode":"on"}`.
Unbekannte Felder oder Typen ergeben 422. Default ist `auto`.
`off` publiziert exakt das bisherige Echogramm einschließlich Amplituden.
`auto` bereinigt bei belastbarer Referenz; `on` aktiviert die Bereinigung
auch bei sehr geringem Hintergrund. Beide fallen bei weniger als 64
Referenzsamples oder ungeprüftem Zeitbezug auf Originalwerte zurück.
Der konkrete Grund steht immer im Status.

Änderungen sind atomar unter `<storage-root>/noise-reduction.json` gespeichert
und gelten ab dem nächsten vollständig verarbeiteten Ping. Neustarts verwenden
den gespeicherten Modus, sonst `auto`. CLI `--noise-reduction-mode` überschreibt
die gespeicherte Politik beim Start; spätere API-Änderungen werden gespeichert.
Ein unveränderter Modus erhöht keine Revision. Tatsächliche Änderungen erhöhen
die Anzeige-Generation: im Auswahlstatus heißt dieses bestehende Feld
`display_selection.generation`, im Publish-Ring `display_generation`.
Das separate `selection_generation` bleibt bei Rauschänderungen unverändert.
PUT enthält bei einer tatsächlichen Modusänderung
`clear_client_history: true`. Clients sollen keine Roh- und bereinigte Historie
unmarkiert mischen. Bis zum nächsten ausgewählten Ping ist `latest: null`.

`latest` enthält `schema: no5.sonard-noise-reduction/1`, `ping_id`,
`acquisition_source`, `sample_count`, `sample_period_ns`, Politik/Revision,
`active`, `reason`, `baseline_adc`, `noise_sigma_adc`, `reference_samples`,
`suppressed_fraction` (Anteil auf null gesetzter Nach-TX-Samples),
`protected_fraction`, `output` und `calibrated_acoustic_power: false`.
Ein inaktiver Filter hat `output: original_adc`; aktiv
`background_reduced_adc_codes`. Fehlende Schätzwerte sind null.
Gründe: `disabled`, `unverified_time_reference`, `insufficient_noise_reference`,
`no_measurable_background`, `automatic_background_estimate`, `enabled`.
Bei Auswahl eigener Pings bezieht sich `latest` ausschließlich auf diesen
Anzeigestrom; versteckte native Pings ersetzen ihn nicht.

## Bestehende Schnittstellen

Dasselbe Statusobjekt steht additiv in `GET /api/v1/status` unter
`noise_reduction`, `acquisition.amplitude.noise_reduction` und in
`GET /api/v1/sonar/presentation` unter `noise_reduction`.
Die bisherigen Detektorfilter `GET/PUT /api/v1/detection/filter` bleiben
unabhängig. Sie steuern keine Rauschbereinigung des Echogramms.

WebSocket `/ws/v1/echogram`: unverändertes Paketformat, gleicher Header,
Zeitbezug, Flags, Tiefe, Konfidenz, Bins und 12-Bit-Vollskala 4095.
Es gibt keine Verstärkung oder Normalisierung auf das Pingmaximum.
`acquisition.amplitude.display_transform` ist bei aktivem Filter
`background_reduction_then_max_pool_or_interpolate`, sonst der bisherige Wert
`max_pool_or_interpolate_without_amplitude_scaling`. Einheit bleibt ADC-Code;
die Transformation muss als abgeleiteter Kontrast erkennbar bleiben.

Jeder Ping erhält im vorhandenen `derived/.../detections.jsonl` ein additives
`noise_reduction` mit tatsächlich verwendeter Revision und Entscheidung.
Die gespeicherten Originalwerte und die versionierte Algorithmusquelle
erlauben eine erneute Berechnung; bestehende Raw-Zarr/WAL/Wire-Dateien werden
durch Modusänderungen nicht bearbeitet.

## Veröffentlichte Quellenzuordnung

`GET /api/v1/sonar/published-pings?after_ping_id=N` liefert einen begrenzten
Ring der letzten 512 tatsächlich veröffentlichten Frames. Die Antwort hat
`schema: no5.sonard-published-pings/1`, `capacity`, `session_id`, `segment_id`,
`oldest_ping_id`, `latest_ping_id`, `truncated` und `pings`.
Pro Abruf kommen höchstens 64 Einträge (`limit: 1..64`, Default 64), mit
`next_after_ping_id` als Folgcursor und `has_more`. Ohne Cursor oder mit 0
beginnt der Abruf am ältesten vorhandenen Eintrag. Bei einem älteren
Cursor als dem vollen Ring wird `truncated: true` gemeldet.

Jeder Eintrag enthält `ping_id`, `time_utc_ns` (exakt der binäre Frame-Zeitstempel),
`session_id`, `segment_id`, `actual_source`, `source_evidence`,
`valid_tx_reference`, `selection_generation`, `display_generation`, `sample_count`, `sample_period_ns`,
`noise_revision` und `noise_active`. Eigene Pings erhalten ausschließlich nach
physischem TX-Ledger/GP16-zu-Raw-Match `actual_source: own` und
`source_evidence: physical_own_marker_raw_match`. `native` / `qualified_native_marker_raw_match` erfordert einen zusätzlich
zeitgleichen qualifizierten Originalmarker. Andernfalls sind `actual_source: null`
und `source_evidence: unconfirmed` gesetzt. Dies sind die drei festen Evidence-
Werte. Versteckte Frames fehlen im Publish-Ring. `selection_generation` zählt
nur tatsächliche all/own/native-Wechsel (auch own→native→own); eine Rausch-
änderung erhöht ausschließlich die Anzeige-Generation (`display_generation`
im Ring = `display_selection.generation` im Status), nie `selection_generation`.
Die Zuordnung wird vor der Auslieferung gespeichert und nicht aus einer
später gepollten Analyse oder nur der Display-Auswahl hergeleitet.

Gateway/Clients können damit verspätete Metadaten zu bereits empfangenen Frames
zuordnen. Session, Segment, Ping-ID und Zeit müssen passen; bei neuem Segment
den Cursor zurücksetzen. Anzeige-Generation und Rauschrevision für die
Historiengrenzen berücksichtigen. Bei jedem neuen USB-Segment leert Sonard
den Ring, bevor Frames unter dieser Segmentkennung veröffentlicht werden.
Header und Einträge gehören dadurch immer demselben aktuellen Segment an.
Keine Quelle aus einem ähnlichen Peak oder
aus der bloßen Auswahl `own` erfinden. Legacy-WebSocket bleibt rein binär.

## Prüfung und Betrieb

`tests/test_noise_reduction.py` prüft wechselnden Rauschboden, stationäre und
bewegte schwache Echos, Peakgeometrie, Abtastperioden, Pretrigger, Ausfallgründe,
API/Neustartpersistenz und bytegleiche Roharchive. Ein identischer synthetischer
Input ergibt in off/auto/on identische Detektion und Tiefenentscheidungen;
nur Amplituden der Anzeige unterscheiden sich.

`tools/verify_noise_recording.py` liest eine bestehende Replay-ZIP ausschließlich
und vergleicht die gesamten Original-Samplehashes vor/nach der Bereinigung.
Die 60-s-Aufnahme enthält 1118 Pings mit 2048/8192/16384 Samples. Die gemessene
Bereinigungszeit ist keine neue Pi-Gesamtlastfreigabe. Der frühere Pi-Test mit
MicroSD-Synchronisierung bleibt eine getrennte bekannte Begrenzung.

Der Host unterdrückt im normalen Logmodus die wiederholten trackpy-INFO-Zeilen
für die historischen Frames jedes Pings; `--log-level debug` erhält sie.
Leasefehler nennen jetzt den Ausnahmetyp. Leasezeit, Timeout und sichere
Abschaltung bleiben unverändert; keine automatische Wiederbewaffnung.

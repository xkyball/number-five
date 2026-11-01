# Sonard 0.4.17: konservative Impulstrennung im Bildpfad

Der freizugebende Host ergänzt `repeated_rc_impulse_v1` und
`local_adc_background_v3`. Er verändert ausschließlich abgeleitete ADC-
Bildwerte. Vier Perioden/197 kHz, Pico 0.7.12, physische GP16-Zuordnung,
Roharchiv, Detektion, Tiefenauswahl und tiefenabhängiger Scheduler bleiben
erhalten. Keine zusätzliche Bibliothek, kein Firmwareupdate.

## Additive API

`GET/PUT /api/v1/sonar/noise-reduction` akzeptiert zusätzlich
`impulse_mode: off | auto | on`. Fehlende PUT-Felder bleiben unverändert.
Null, Boolean und unbekannte Werte werden mit 422 abgelehnt. Änderungen
erhöhen `revision` und `display_generation`; idempotente PUTs tun dies nicht.
Die Auswahl wird atomar gespeichert. Standard ist `auto`.

- `impulse_mode: off`: bisheriger v2-Bildpfad, gleicher Stripe-Modus/Crop.
- `auto` und `on`: gleiche konservative Nachweispflicht; `on` erzwingt keine
  Löschung ohne passendes Muster.
- `mode: off`: sämtliche Bereinigung aus. `stripe_mode: off`: historischer
  v1-Pfad und vollständiges Anzeigefenster, keine Impulstrennung.

`policy.algorithm` ist bei aktivierter Impulspolitik
`local_adc_background_v3`, bei Impuls-off `local_adc_background_v2`, bei
Stripe-off weiterhin `robust_adc_background_v1`.
`latest.impulse_reduction` ergänzt `active`, `reason` und bei Prüfung
`algorithm`, `events`, `modified_samples`, `maximum_subtraction_adc`,
`detected_echo_samples_unchanged`, `temporal_replacement`. Bei aktiver
Trennung kommen `background_reestimated`, `corrected_baseline_adc` und
`corrected_noise_sigma_adc` hinzu. ADC-Codes sind keine kalibrierte Leistung.
`maximum_subtraction_adc`/`modified_samples` beschreiben die abgetrennte
Komponente vor erneuter Hintergrundschätzung, nicht eine allgemeine
Differenzmessung zwischen fertigen Bildern.

`noise_active` bezeichnet weiterhin die gesamte tatsächlich angewandte
Bereinigung. Impuls-off oder Auto ohne Treffer können deshalb weiterhin
`noise_active=true` besitzen. Der generische Transformstring bleibt
`background_reduction_then_max_pool_or_interpolate`; Vollskala 4095,
12 signifikante Bits in uint16 LE. Keine neue Amplitudennormierung.

## Unveränderte Lesersemantik

M1-Paketformat und N5EC/1 bleiben unverändert, einschließlich ihrer exakten
Feldmengen, Grenzen 4096/2088/6200 Byte, IDs, UTC, Quellen, Generationen,
Sequenzen und Fenstergeometrie. Neue Impulsfelder gehören ausschließlich
in Noise-Policy/-Diagnose, nicht in PublishedPing oder Atomic-Metadaten.
Bei passender Störung ändern sich M1-Amplituden ausdrücklich; die Header
und deren Zuordnung bleiben erhalten. Ohne Signatur bleiben M1 und
PublishedPing für die drei bisherigen Referenzfälle byte-/wertgleich.

Öffentliche synthetische Leserfälle: `tests/fixtures/impulse-interface-0417.json`.
Sie enthalten identische Raw-A/B-Daten, v2/off, v3/auto/on, Auto ohne Muster,
native Quelle, andere Sampleperiode, Clipping, isoliertes Ziel und ein
geschütztes schwaches Ziel, jeweils mit M1, Atomic-Kontext und Noisestatus.
Das ist Softwareevidenz, keine elektrische Abnahme.

## Nachweis und Grenzen

Nur bestätigte eigene 2-µs-Aufnahmen werden untersucht. Mindestens sechs
passende steile Anstiege mit gemeinsamer RC-Form über mindestens 300 Samples
und wiederkehrenden Abständen sind nötig. Das empirische Abklingen beträgt
0,78 je 2 µs; Quelle des elektrischen/acoustischen Störanteils ist damit
nicht eindeutig identifiziert. Gesättigte Profile und unklare/isolierte
Muster bleiben unverändert. Detektorblöcke samt 50-µs-Rand behalten exakt
die bisherigen Bildwerte. Die Hintergrundschätzung wird außerhalb dieser
Schutzbereiche nach der Trennung erneuert, damit ein erhöhter MAD eines
Impulszugs nicht ein darunterliegendes schwaches Ziel weiter unterdrückt.

Es gibt keine zeitliche Spaltenersetzung, Zukunftspings, zusätzliche
Bildlatenz oder Subtraktion stationärer Ziele. Unbeschriftete Wannenprofile
beweisen keine Fischklassifikation und keine universelle Verlustfreiheit:
Ein ungeschütztes echtes Echo, das exakt die wiederholte Störform besitzt,
bleibt grundsätzlich mehrdeutig. Deshalb werden zweifelhafte Muster erhalten.

Die Referenzidee lokaler Impuls-/Transientenprüfung ist bei
[Echopype](https://echopype.readthedocs.io/en/stable/api/echopype.clean.mask_impulse_noise.html)
dokumentiert. Hier wird keine kalibrierte Sv-Formel und kein ungeprüfter
Ozeanparameter übernommen. Die Implementierung verwendet vorhandenes
NumPy/SciPy und ein separat getestetes ADC-Komponentenmodell.

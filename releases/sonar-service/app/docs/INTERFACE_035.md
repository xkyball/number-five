# Erweiterte Sonard Schnittstelle 0.3.5

Ergänzung0.4.10: Der aktuelle additive TX-/Detektorvertrag steht in
[CLIP_ENDURANCE_API.md](../CLIP_ENDURANCE_API.md). Die Paketversion steht
weiter unter `host_package.version`;0.4.9/0.4.10 behalten4095 als ADC-Vollskala.
Die neuen Template-/Markerfelder ändern weder Rohsamples noch Paketgeometrie.

Dieses Dokument beschreibt die vorhandenen HTTP- und WebSocket-Schnittstellen
von `no5-sonard-openecho` 0.3.5. Fachliche Analyse, Darstellungsempfehlung und
unveränderte Rohaufnahme sind getrennte Daten. Es definiert keine Oberfläche
und keine zusätzlichen Gateway-Endpunkte.

Die HTTP-Feldnamen sind **snake_case**. Die Paketidentität steht unter
`GET /api/v1/status → host_package.version`. Die geerbte top-level
`status.version`, `/healthz.version` und OpenAPI-`info.version` können noch
die M1-Version bezeichnen. Die [OpenAPI-Datei](sonard-0.3.5.openapi.json)
stammt aus dem 0.3.5-Wheel; sie enthält die HTTP-Routen, aber keinen
WebSocket-Vertrag und keine vollständigen Typen der dynamischen Antworten.

## HTTP Endpunkte

| Methode und Pfad | Vorhandene Funktion |
|---|---|
| `GET /api/v1/status` | Laufzeit, Paketidentität, Aufnahme, Analyse, Zähler und Sitzung |
| `GET /api/v1/sonar/presentation` | kanonische Auswahl für den letzten analysierten Ping |
| `GET /api/v1/detection/latest` | letzte Blöcke, Tracks, Auswahl, Presentation und Policy |
| `GET /api/v1/detection/tracks` | letzte Tracks und Liste der Regelklassen |
| `GET /api/v1/detection/filter` | Policy, wirksame Auswahl und letzte Detektion |
| `PUT /api/v1/detection/filter` | Filtermodus off, auto oder on ändern |
| `GET /api/v1/acquisition/context` | aktueller Aufnahme-Kontext |
| `PATCH /api/v1/acquisition/context` | Aufnahme-Kontext teilweise aktualisieren |
| `GET /api/v1/labels` | manuelle Labels der aktuellen Host-Sitzung |
| `POST /api/v1/labels` | unveränderliches manuelles Label anhängen |
| `GET /healthz` | Hostprozess antwortet; kein Nachweis eines Pings |
| `GET /readyz` | `{ready, state}`; ready kann auch bei WAIT_PICO oder IDLE true sein |

Die neuen Schreibpfade melden ungültige Körper oder Werte mit HTTP 422.
Fehler stehen in `detail`; FastAPI-Bodyfehler können dort eine Liste liefern.
Es gibt keinen HTTP-Befehl für aktive TX-Frequenz, Leistung, Pingrate,
RX-Blanking oder allgemeine CFAR-Parameter. Aufnahme-Start/Stop gehört in
diesem Host zum Prozess-Lifecycle und ist kein zusätzlicher HTTP-Endpunkt.
Authentifizierung, revisionsgebundene ACKs und ein Gateway-V1-Protokoll sind
keine Funktionen dieser Host-API.

## Kanonisches Analyseergebnis

`GET /api/v1/sonar/presentation` antwortet unmittelbar mit:

| Feld | Typ und Bedeutung |
|---|---|
| `schema` | `no5.sonard-presentation/1` |
| `ping_id` | Ping-ID; vor einer Analyse null |
| `quality` | WAITING, SEARCHING, LOCKED, UNSTABLE oder UNVERIFIED_TX |
| `reason` | lesbarer Grund; kann null sein |
| `bottom` | ausgewähltes Boden-/Tiefenkandidatenobjekt oder null |
| `tracks` | bestätigte Tracks aus der letzten Detektion |
| `filter` | wirksame Filterauswahl, siehe unten |
| `recommendations` | Range-/Freeze-Empfehlung, keine Änderung der Rohaufnahme |

Ein vorhandenes `bottom` enthält:

| Feld | Typ und Bedeutung |
|---|---|
| `source` | `m1_raw`, `cfar_track_rules_v1` oder `direct_envelope_track_rules_v1` |
| `display_bin` | Fließkommaposition innerhalb 0 bis 1023; Geometriehinweis unten beachten |
| `depth_m` | Tiefe in Metern relativ zum verwendeten TX-/Geberbezug |
| `confidence` | heuristische Bewertung innerhalb 0 bis 1 |
| `strength` | 0 bis 1; Rohquelle verwendet Confidence, Trackquelle den Regel-Score |
| `track_id` | Track-ID oder null bei Rohquelle |
| `classification` | Regelklasse; unknown bei Rohquelle |
| `classification_source` | rules_v1 bei Trackquelle; null bei Rohquelle |

Die Quelle `direct_envelope_track_rules_v1` bezeichnet einen zeitlich
bestätigten breiten Direktrückläufer. Sie ergänzt CFAR, wenn ein breites Echo
die lokalen Referenzfenster füllt. Sie ist kein ungeprüfter Einzelpeak.

Die Zustände bedeuten:

- **WAITING:** noch kein Analyseergebnis; bottom null, ping_id kann null sein.
- **SEARCHING:** zeitlich gültiger Ping, aber keine ausgewählte Tiefe.
- **LOCKED:** Sonard veröffentlicht ein bottom-Objekt. Für Trackquellen
  erzwingt dieser Zustand keine Mindest-Confidence von 0,75.
- **UNSTABLE:** nach einem vorherigen bottom fehlt eine neue Auswahl.
  bottom ist null und freeze true. Bei weiter fehlender Auswahl kann der
  unmittelbar folgende Ping bereits SEARCHING sein.
- **UNVERIFIED_TX:** kein freigegebener Zeitbezug für diesen Ping.

`recommendations` enthält `range: FOLLOW_BOTTOM|HOLD` und `freeze: boolean`.
Bei einem Analyseergebnis ist `range_end_bin` ein Wert von 32 bis 1023
oder null. Im initialen WAITING-Objekt kann dieses Feld fehlen. Die Empfehlung
steuert keine Aufnahmeparameter und bedeutet kein Stoppen des Producers.

## Letzte Detektion und Tracks

`GET /api/v1/detection/latest` liefert
`{detection, presentation, policy}`. `detection` ist vor einem Ergebnis null.
Danach besitzt es das Schema `no5.sonard-detection/2` und diese Felder:

`valid_tx`, `ping_id`, `time_utc_ns`, `sample_period_ns`,
`tx_zero_sample_index`, `blocks`, `tracks`, `selected`, `state`, `reason`.

`state` ist derzeit waiting über Statusprojektion beziehungsweise
`not_applicable`, `off`, `evaluating` oder `active` im DetectionResult.
Die fachliche Hauptauswahl ist Presentation; der alte Rohdetektor unter
`status.bottom` ist ein unabhängiger Befund.

Jeder Block enthält `start_sample`, `end_sample`, `peak_sample`, `depth_m`,
`width_m`, `energy`, `peak_amplitude`, `local_noise`, `snr_db`, `prominence`
und `detector`. Samplepositionen beziehen sich auf das ADC-Fenster;
`end_sample` ist die ausschließende Abschnittsgrenze. `detector` ist cfar oder
direct_envelope. Amplituden/Energie sind Signalmerkmale in ADC-Zählwerten,
keine kalibrierte akustische Leistung.

Jeder Track enthält:

`track_id`, `depth_m`, `velocity_mps`, `hits`, `age_pings`, `misses`,
`confidence`, `state`, `kind`, `classification`, `classification_source`,
`rule_score`, `evidence`, `detector`.

`state` ist tentative, confirmed oder stale. confirmed verlangt derzeit
mindestens drei Hits und keinen aktuellen Miss. `kind` ist echo_structure.
`evidence` ist eine Liste der verwendeten Merkmale/Regelgründe.
`GET /api/v1/detection/tracks` antwortet mit `{tracks, rule_classes}` und
enthält auch tentative/stale Tracks; Presentation enthält nur confirmed.
Die HTTP-Antworten besitzen keine fest implementierte 32-Track-Grenze.

Regelklassen sind `bottom_like`, `fish_like`, `weed_like`, `bubble_like`,
`multipath_like` und `unknown`; `classification_source` ist rules_v1.
`rule_score` bewertet die Klassifikationsregel. Track-Confidence besteht
derzeit aus 50 Prozent Persistenz, 30 Prozent SNR und 20 Prozent Kontinuität.
Keiner dieser Werte ist eine kalibrierte Wahrscheinlichkeit für Fischart
oder Bodenmaterial. Track-IDs ohne Sitzung/Ping sind keine dauerhaften
globalen Objektkennungen.

## Filtermodus und Policy

`GET /api/v1/detection/filter` liefert `{policy, selection, latest}`.
`selection` und Presentation-`filter` enthalten:

`requested_mode`, `effective_mode`, `active`, `reason`, `candidate`.

`candidate` ist ein vollständiges Trackobjekt oder null. Wirksame Modi:

| Wunsch | Wirksamer Modus und Auswahl |
|---|---|
| off | off; keine kanonische bottom-Auswahl |
| on | on; Trackkandidat, falls vorhanden; sonst active false |
| auto | auto_raw bei glaubwürdigem Rohgrund oder solange kein Track hilft; auto_filter beim Track-Fallback |

auto bevorzugt einen Rohgrund mit Confidence mindestens 0,75 und schaltet
ansonsten auf einen bestätigten Nicht-Mehrweg-Track um. Der Trackpfad wählt
den frühesten bestätigten Nicht-Mehrweg-Track, nicht zwingend den lautesten
oder einen bereits als bottom_like klassifizierten Track.

Schreiben erfolgt ausschließlich mit einem mode-Feld:

```http
PUT /api/v1/detection/filter
Content-Type: application/json

{"mode":"auto"}
```

Erfolg: HTTP 200 mit `{policy}`. Zusätzliche Felder und andere Modi ergeben
422. Die Auswahl wird sofort neu berechnet; die letzte Detection kann noch
vom Ping vor dem Moduswechsel stammen. Nachfolgende Pings verwenden den
neuen Modus. off deaktiviert deren Detektionspipeline; Roh-Pings bleiben
archiviert. Ein Moduswechsel setzt den bestehenden Tracker nicht vollständig
zurück und ist kein Reconnect.

Policy-Felder und Standardwerte sind:

| Feld | Standard |
|---|---:|
| mode | auto |
| blanking_m | 0,50 |
| training_cells / guard_cells | 32 / 8 |
| cfar_scale | 4,5 |
| min_block_cells / max_block_cells | 4 / 640 |
| association_gate_m | 0,18 |
| max_track_misses / min_track_hits | 3 / 3 |
| track_window_pings | 48 |
| rule_version | rules_v1 |

Diese Felder sind lesbar und pro abgeleitetem Ping archiviert. Die aktuelle
HTTP-API schreibt nur mode, nicht die übrigen Parameter.

## Aufnahme Kontext

GET liefert `{context}` mit `mount`, `gps`, `course_deg`, `speed_mps`, `water`.
PATCH akzeptiert eine Teilmenge dieser Felder. Ausgelassene Felder bleiben
erhalten, null setzt ein Feld zurück. mount/gps/water sind Objekte oder null;
course_deg/speed_mps sind Zahlen oder null. Die internen Objektfelder sind
derzeit nicht durch ein festes Host-Schema eingeschränkt.

```json
{
  "mount": {"draft_m": 0.4},
  "gps": {"lat": -33.9, "lon": 18.4},
  "course_deg": 12.0,
  "speed_mps": 1.8,
  "water": {"temperature_c": 16.0}
}
```

Das Beispiel ist ein zulässiger Körper, kein gemessener Bootsdatensatz.
Erfolg: HTTP 200 mit `{context}`. Unbekannte Top-level-Felder oder falsche
grobe Typen ergeben 422. Der Host prüft weder Koordinatenbereiche noch alle
Wasser-/Montageeinheiten; solche Werte müssen vom Aufrufer korrekt geliefert
werden. Kontext wird je Ping im Derived-Archiv und als aktueller Sitzungsstand
gespeichert. PATCH verändert weder Rohsamples noch die laufende ADC-Konfiguration.

## Manuelle Labels

POST hängt ein Label an die aktuelle Host-Sitzung an:

```json
{"track_id":7,"ping_id":481,"label":"fish_like","note":"manuell geprüft","reviewer":"operator"}
```

Erforderlich sind positive track_id, nichtnegative ping_id und eine der sechs
Regelklassen. note/reviewer sind optional Text oder null. Andere Felder oder
Klassen ergeben 422. Erfolg: HTTP 201 mit `{label}`. Das gespeicherte Objekt
enthält zusätzlich `schema: no5.sonard-label/1` und `created_utc_ns`.

GET liefert `{labels}`. Es gibt kein Edit/Delete und keine automatische
Deduplizierung oder Request-ID. Eine Wiederholung von POST kann ein weiteres
Label erzeugen. Der Host prüft aktuell Format und Werte, nicht die tatsächliche
Existenz des angegebenen Ping/Tracks. Ein Aufrufer muss die richtige Sitzung
und gültige Referenzen sichern. Ein weiterer Eintrag kann eine Korrektur
dokumentieren; der Dataset-Export verwendet das zuletzt gelesene Label je
Ping-/Track-Paar innerhalb der Sitzung.

## Status und Zeitbezug

Status enthält weiterhin `state`, `session`, `pico`, `clock`, `rates`,
`bottom`, `acquisition`, `accepted_config`, `device_status`, `detection`,
`connection_history`, `shutdown` und `host_package`.

Neu beziehungsweise erweitert sind insbesondere:

- `detection.policy`, `state`, `reason`, `selection`, `candidate`,
  `presentation`, `confirmed_tracks`, `archive`.
- `acquisition.tx_marker_requested`, `tx_reference_basis`,
  `empirical_trigger_fallback`, `last_tx_reference_valid`,
  `clipped_pings_total` und `context`.
- `session.archived_pings_total` zusätzlich zum bisherigen Archivzähler.
- `accepted_config.requested` und `accepted_config.ack` für tatsächlich
  quittierte Aufnahmegeometrie; device_status kann bei fehlender Beobachtung null sein.

`rates.ping_hz` ist die gemessene Pingrate, nicht ADC-Samplingrate oder
Ultraschallträgerfrequenz. Zeitwerte des Pings sind UTC-Nanosekunden;
`sample_period_ns` ist der ADC-Abstand. Host-/USB-Empfang ist kein akustischer
Sendezeitpunkt. Der derzeit explizite empirische Bezug heißt
`empirical_marker_aba_20261012`; sonst unverified. Empirische Freigabe ist
kein Beweis einer unabhängig kalibrierten absoluten Laufzeit.

Presentation besitzt keinen eigenen Beobachtungszeitstempel. Seine Ping-ID
muss mit der zeitlich zugeordneten Aufnahme abgeglichen werden. HTTP-Lesen
frischt einen alten Ping nicht auf. Die Rohframe-Veröffentlichung erfolgt
vor Fertigstellung der Detektion; kurzzeitig kann der letzte HTTP-Analyse-Ping
hinter dem zuletzt empfangenen Frame liegen. Eine alte Analyse darf deshalb
nicht unter einer neuen Ping-ID verwendet werden.

## Binärer Echogramm Stream

`WS /ws/v1/echogram` sendet Binärframes ohne Gateway-Subprotocol, ohne
anfängliches Text-state und ohne JSON-Header. Headerformat:

| Byteoffset | Typ | Inhalt |
|---:|---|---|
| 0 / 1 / 2 | u8 / u8 / u16 | Version 1, Typ 1, Headerlänge 40 |
| 4 | u64 | ping_id |
| 12 | i64 | time_utc_ns |
| 20 | f32 | displayed_max_depth_m |
| 24 | f32 | legacy bottom_depth_m; NaN bei unbekannt |
| 28 | f32 | legacy bottom_confidence |
| 32 | u32 | Flags |
| 36 / 38 | u16 / u16 | bin_count, reserviert |
| 40 | bin_count × u16 | little-endian Displayamplituden |

Der verwendete Standard hat 1024 Displaybins, also 2088 Byte pro vollständigem
Host-Frame. Das ist ein resampeltes Anzeigeprofil, nicht das volle Roh-Ping-
Fenster. Rohsamples und ursprüngliche Flags bleiben im Archiv erhalten.
Bei gültigem TX-Bezug beginnt Resampling am TX-Nullindex; die dargestellte
Maximalreichweite bezieht sich auf das verbleibende Fenster.

Im Anzeige-Header bezeichnet Bit 0 einen freigegebenen TX-Nullbezug, Bit 1
Clipping und Bit 5 einen mehrdeutigen Trigger. Beim ausdrücklich aktivierten
empirischen Trigger-Fallback setzt der Host für den Displayframe Bit 0 und
Bit 7 und entfernt Bit 5. Die ursprünglichen Roh-Ping-Flags werden dabei
nicht überschrieben. Bit 7 kennzeichnet somit den empirischen Fallback,
keine zusätzliche Messung eines absoluten Sendezeitpunkts.

Meter pro Displaybin sind `displayed_max_depth_m / (bin_count - 1)`.
10.000 ADC-Samples mit 4 µs ergeben bei 1500 m/s ohne Pretrigger 30 m;
1024 Displaybins haben dann etwa 0,0293255 m Abstand. ADC-Abstand und
Displaybin-Abstand sind unterschiedliche Größen.

**Geometriegrenze in 0.3.5:** Presentation berechnet `display_bin` aus der
vollen Samplezahl, der binäre Stream dagegen aus dem Fenster nach TX-Null.
Bei einem TX-Index ungleich null muss ein Verbraucher die physische `depth_m`
und die Range-Empfehlung in die tatsächliche Stream-Geometrie übertragen.
Bei TX-Index null stimmen beide Grundlagen überein. Ungültiger TX-Bezug kann
eine NaN-Reichweite liefern; daraus darf keine Meterachse berechnet werden.
Der Header-Rohgrund ist keine Kopie der erweiterten Presentation-Auswahl.

## Archiv und Versionskennungen

| Daten | Schema |
|---|---|
| Roh-Ping | no5.raw-ping/1.0 |
| Detection | no5.sonard-detection/2 |
| Presentation | no5.sonard-presentation/1 |
| Derived-Pingzeile | no5.sonard-derived/3 |
| manuelles Label | no5.sonard-label/1 |

Derived-Zeilen liegen unter
`sessions/<id>/derived/<segment>/detections.jsonl` und enthalten zusätzlich
policy, legacy_bottom, selection, presentation und acquisition_context.
Manuelle Labels liegen unter `sessions/<id>/derived/labels.jsonl`.
Roh-Zarr-Segmente liegen unter `sessions/<id>/raw/<segment>.zarr`.
Filtermodus und manuelle Labels überschreiben die Roh-Pings nicht.

Die Beschreibung und OpenAPI wurden gegen das vorhandene 0.3.5-Wheel und
seinen Quellstand geprüft. Dafür wurden weder ein Server noch ein Pico-Client
gestartet. Quelle: `__main__.py` für HTTP-Routen, `service.py` für Antworten,
`detection.py` für Merkmale/Tracks und der erhaltene M1-Host `protocol.py` und
`service.py` für den Binärstream.

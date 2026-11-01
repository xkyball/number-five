# Sonard 0.4.16: atomarer Echogramm-Kontext, Wirevertrag v1

Status: verbindlicher Quellvertrag; keine Installation oder Live-Abnahme aus
diesem Dokument ableiten. Sonard 0.4.15 und sein geprüftes Wheel bleiben erhalten.

## Lesepfad und Grenzen

`/ws/v1/echogram-context` ist ausschließlich lesend. Jede WebSocket-Nachricht
ist genau ein vollständiger Binary-Envelope. Kein separates JSON-/Binary-Paar,
keine Aufnahme-/TX-Befehle und kein zusätzlicher Produzent. Client-Datennachrichten
führen wie beim bisherigen Lesepfad zum Schließen mit Code 1003. Ein stiller
Stream muss Disconnects weiterhin erkennen.

Der bisherige `/ws/v1/echogram`, sein 40-Byte-M1-Header und alle M1-Payloadbytes
bleiben unverändert. Bestehende HTTP-Antwortschemata, ADC, DSP, TX und Roharchive
werden nicht umgedeutet. Der neue Pfad transportiert dieselbe bereits gewählte
Anzeigequelle; er wählt weder selbst eine Quelle noch stuft er Evidenz hoch.

## Binary-Envelope

Little Endian, Prefix `struct.Struct('<4sHHII')`, exakt 16 Bytes:

| Offset | Typ | Wert / Bedeutung |
|---:|---|---|
| 0 | 4 Bytes | ASCII `N5EC` (`4e354543`) |
| 4 | uint16 LE | Version `1` |
| 6 | uint16 LE | Flags `0`; andere Werte zurückweisen |
| 8 | uint32 LE | Anzahl UTF-8-Metadatenbytes, `1..4096` |
| 12 | uint32 LE | Anzahl Original-M1-Bytes, `42..2088` |
| 16 | Bytes | JSON-Metadaten, genau die angegebene Bytezahl |
| danach | Bytes | vollständige unveränderte M1-Binary |

Nachrichtengröße exakt `16 + metadata_len + m1_len`, maximal **6200 Bytes**.
Keine Padding-/Trailerbytes. Die v1-M1-Binary hat Version 1, Message-Type 1,
Headerlänge 40, Reserved 0 und `1..1024` uint16-Amplituden. Ihre Länge beträgt
exakt `40 + 2 * bin_count`. Dies ist die explizite Grenze des neuen Pfads; der
Legacy-Pfad erhält dadurch keine neue Einschränkung. M1-Flags, Amplituden und
Floatbits werden weder neu quantisiert noch umgeschrieben.

Ungültige Magic, Version, Flags, Längen, UTF-8, JSON, doppelte Objektschlüssel,
NaN/Infinity in JSON, unerwartete Felder oder widersprüchliche Identitäten
sind Fehler; niemals aus einem ungültigen Envelope auf M1 raten. Der alte
M1-Pfad ist kein impliziter Fallback innerhalb einer Envelope-Nachricht.

## JSON-Schema `no5.sonard-echogram-context/1`

Das oberste Objekt enthält ausschließlich diese vier Felder:

```json
{
  "schema": "no5.sonard-echogram-context/1",
  "selected_source": "own",
  "publication_sequence": "1",
  "published_ping": {
    "ping_id": "123",
    "time_utc_ns": "1792170000000000123",
    "session_id": "11111111-1111-4111-8111-111111111111",
    "segment_id": "22222222-2222-4222-8222-222222222222",
    "actual_source": "own",
    "source_evidence": "physical_own_marker_raw_match",
    "valid_tx_reference": true,
    "display_generation": 1,
    "selection_generation": 1,
    "sample_count": 2048,
    "sample_period_ns": 2000,
    "noise_revision": 1,
    "noise_active": true,
    "display_window": {
      "schema": "no5.display-window/1",
      "ping_id": "123",
      "capture_sample_count": 2048,
      "display_sample_count": 2048,
      "display_start_sample": 0,
      "display_end_sample_exclusive": 2048,
      "capture_range_m": 3.072,
      "display_range_m": 3.072000026702881,
      "tx_reference_verified": true,
      "reason": "full_capture",
      "raw_samples_modified": false,
      "detector_uses_full_capture": true
    }
  }
}
```

Das Beispiel beschreibt das Schema; verbindliche Binary-/Hash-Beispiele stehen
in `tests/fixtures/echogram-context-0416.json`.

`published_ping` enthält **alle** bisherigen per-Ping-Felder des HTTP-Belegs.
Nur die unten genannten 64-Bit-Werte erhalten im neuen JSON eine exakte
Stringdarstellung. Der bestehende HTTP-Beleg bleibt unverändert.

### Zahlen und Identitäten

| Feld | Wiretyp und Wertebereich |
|---|---|
| `publication_sequence` | kanonischer Dezimalstring, uint64 `1..2^64-1` |
| `published_ping.ping_id`, `display_window.ping_id` | kanonischer Dezimalstring, uint64 `0..2^64-1` |
| `time_utc_ns` | kanonischer Dezimalstring, int64 `-2^63..2^63-1` |
| `display_generation`, `selection_generation`, `noise_revision` | JSON-Integer `1..2^32-1` |
| `sample_period_ns` | JSON-Integer `1..2^32-1` |
| `sample_count`, `capture_sample_count`, `display_sample_count` | JSON-Integer `1..16384` |
| `display_start_sample`, `display_end_sample_exclusive` | JSON-Integer `0..16384` mit konsistenter Fenstergeometrie |
| `capture_range_m`, `display_range_m` | endliche positive JSON-Zahl oder `null`, entsprechend dem bestätigten Zeitbezug |

Dezimalstrings haben keine Pluszeichen, Leerzeichen, führenden Nullen,
Exponenten oder `-0`; `0` ist ausschließlich bei Ping-ID/UTCns zulässig.
Numerische JSON-Ersatzwerte für Stringfelder werden zurückgewiesen. Booleans
sind keine Integer. Session und Segment sind kanonische kleingeschriebene
UUID-Strings. Ping-ID und UTCns müssen exakt dem eingebetteten M1-Header
entsprechen. Die Fenster-Ping-ID muss ebenfalls identisch sein.

`selected_source` ist `all`, `own` oder `native`. `actual_source` ist `own`,
`native` oder `null`. Dessen Evidenz bleibt genau:

| actual_source | source_evidence |
|---|---|
| `own` | `physical_own_marker_raw_match` |
| `native` | `qualified_native_marker_raw_match` |
| `null` | `unconfirmed` |

Ein unbekannter Ursprung bleibt `null`. Ein Output-ACK allein bestätigt
weiterhin keine physische Sendung. Bekannte tatsächliche Quellen müssen zur
gewählten Auswahl passen; `all` erlaubt beide. Bestätigtes `native` setzt einen
gültigen TX-Bezug voraus. Die beiden TX-Referenzbooleans und M1-Headerflag Bit 0
stimmen überein; `noise_active` ist ebenfalls strikt boolesch. Bei unbestätigtem
Zeitbezug sind beide Reichweiten `null`, die vollständige Index-0-Abdeckung
bleibt erhalten und die unbekannte M1-Reichweite bleibt NaN in der **Binary**.
Bei bestätigtem Bezug entspricht `display_range_m` exakt dem gelesenen
Float32-Wert im M1-Header. Ein Ende vor `capture_sample_count` (gekürztes
Bildfenster) ist ausschließlich bei `actual_source: own` zulässig; bestätigtes
Native und unbekannte Quellen erhalten keine solche Verkürzung. Ein bereits
vorhandener Pretrigger-Start wird dadurch nicht mit einem Crop verwechselt.
`reason` ist ein nichtleerer UTF-8-Text bis 128 Bytes.
`raw_samples_modified` bleibt `false`, `detector_uses_full_capture` bleibt `true`.

### Sequenz, Veröffentlichung und langsame Leser

`publication_sequence` startet pro neuem Segment bei 1 und steigt für jeden
wirklich gewählten/veröffentlichten Ping um eins, unabhängig von verbundenen
Kontextlesern. Versteckte Pings erhöhen sie nicht. Quellen-/Filterwechsel setzen
sie nicht zurück; die vorhandenen Generationen kennzeichnen diese Wechsel.
Ein neuer Session-/Segmentkontext darf Ping-IDs und Sequenzen wiederverwenden.
Die Identität ist daher immer Session + Segment + Sequenz/Ping, niemals Ping-ID
allein. Überschreiten der uint64-Grenze erzeugt keinen Wraparound.

Ein zweiter `LatestFrameHub(queue_size=4)` verteilt ausschließlich vollständige,
vor dem Enqueue eingefrorene Envelope-Bytes. Kein nachträgliches Nachschlagen in
einem veränderlichen Latest-Status. Bei einem langsamen Leser wird ein alter
kompletter Envelope verworfen; niemals nur Bild oder Beleg. Sequenzlücken
werden nicht mit erfundenen Messungen gefüllt. Die Socket-Sendearbeit läuft im
Lesertask; ein langsamer Leser bremst weder Erfassung noch den Legacy-Hub.

Eine fehlerhafte/nicht unterstützte Kontextkodierung darf die bestehende
Legacy-Veröffentlichung oder Erfassung nicht abbrechen. Sie lässt eine explizit
diagnostizierbare Kontextlücke; die interne Fehlerzählung und Sequenz zeigen
diesen Fehler, ohne Quelle oder Messwerte zu ersetzen.

## Gezielte Abnahme

- Legacy-Binary und vorhandene Published-HTTP-Felder unverändert, einschließlich
  unbestätigter Referenz und voller/verkürzter Bildfenster.
- Kontext bereits vor einem künstlich wartenden Legacy-Publish eingefroren;
  spätere Status-, Quellen- und Segmentwechsel können ihn nicht umetikettieren.
- Roundtrips mit uint64/int64-Grenzen, UTF-8 und allen Quellenzuständen;
  fehlerhafte Versionen, Flags, Größen, JSON und Identitäten zurückweisen.
- Langsame Queue verliert ganze Paare; Idle-Disconnect und Client-Datenframe
  schließen den zusätzlichen Lesetask ohne Erfassungs-/TX-Effekt.
- Erst nach separaten Quellfreigaben ein kurzer gemeinsamer Dual-Reader-Lauf.
  Keine Installation, kein Hostneustart und kein neuer Hardware-Dauerlauf aus
  der Offlineprüfung ableiten. Zusätzliche HTTP-Roundtrips entfallen; allgemeine
  Netzwerkverzögerung und bestehende Frischegrenzen gelten weiter.

# Spotify-Funktionen für No5 — Recherche vom 9. Oktober 2026

## Aktueller Stand nach Implementierung und aktiver Sessionprobe

Der frühere Forschungsstand unten ist historisch: Cover, beobachtete Queue
und begrenztes Browse für Playlists/Inhalt/Lieblingssongs/Suche wurden auf
`d9e8992` implementiert, auf Linux gebaut/getestet und im virtuellen Labor
aktiviert; Nachweise auf Master `702fe26`. Anschließend liefern echte lesende
Abfragen mit aktiver Spotify-Sitzung alle genannten Seiten mit HTTP200.
**Tracktitel/Interpreten fehlen noch in den Browse-Songseiten; gezielte
Song-/Queue-Auswahl fehlt im No5-Schreibvertrag.**

Die vollständige aktuelle Einordnung mit allen für No5 geprüften Kategorien,
den tatsächlichen Grenzen von `Spirc.load` und dem nutzergewünschten
No5-Layout ist [SPOTIFY_CAPABILITIES_20261009.md](SPOTIFY_CAPABILITIES_20261009.md).
Die [sanitisierten echten Lese-Nachweise](evidence/media-evaluation-20261009/README.md)
belegen kein Android-UI-/Songauswahl-PASS und keine vollständige Bibliothek.
Es gab in diesem Forschungs-/Designcheckpoint keine weiteren Runtimeänderungen.

## Historischer erster Recherchecheckpoint — vor dieser Implementierung

Cover benötigen keinen zusätzlichen Login. Die bereits verbundene librespot-Sitzung bietet außerdem technische Ansatzpunkte für Playlistdaten, Lieblingssongs und eine einfache Suche. Diese internen Schnittstellen sind keine offiziell unterstützte Spotify Web API. Für eine dokumentierte, berechtigungsgebundene Bibliotheksintegration wäre eine separate Spotify-Freigabe für No5 erforderlich.

**Status:** Quellcode und aktuelle Primärdokumentation geprüft. Keine neuen Spotify-Funktionen implementiert, keine Kontoabfragen für Bibliothek oder Suche ausgeführt und keine OAuth-Anmeldung angelegt. Die unten genannten Möglichkeiten sind keine Laufzeitabnahme.

## Was die vorhandene Connect-Sitzung hergibt

| Funktion | Ohne zusätzliche Benutzeranmeldung | No5 heute / notwendige Ergänzung |
| --- | --- | --- |
| Cover des laufenden Titels | `AudioItem.covers` liefert Bild-URLs und Größen beim Titelwechsel. [librespot-Metadaten](https://github.com/librespot-org/librespot/blob/v0.8.0/metadata/src/audio/item.rs) | No5 verwirft die Cover derzeit; Transportvertrag und echter Bildloader fehlen. |
| Als Nächstes / Warteschlange | Der Connect-Zustand enthält vorherige und nächste Titel, Kontext und Queue-Revision. Er hält höchstens 10 vorherige und 80 nächste Titel. [Connect-Zustand](https://github.com/librespot-org/librespot/blob/v0.8.0/connect/src/state.rs) | Einen begrenzten, beobachteten Ausschnitt aus dem Connect-Aktor exportieren. Keine vollständige Playlist daraus ableiten. |
| Playlistinhalt und Playlistliste | `get_playlist` und `get_rootlist` verwenden die vorhandene Sitzung. [SpClient](https://github.com/librespot-org/librespot/blob/v0.8.0/core/src/spclient.rs) | Lesenden Receiver-Aufruf mit Seitenabruf, Fehlern und Sitzungsbindung ergänzen. Zugang im Labor noch nicht geprüft. |
| Lieblingssongs | `get_context` unterstützt den Collection-Kontext des angemeldeten Nutzers. [SpClient](https://github.com/librespot-org/librespot/blob/v0.8.0/core/src/spclient.rs) | Technischer Ansatz für diesen Bibliotheksteil. Vollständiger Zugriff auf gespeicherte Alben, Shows usw. ist dadurch nicht belegt. |
| Suche | `get_context` unterstützt einen Suchkontext; dessen Ergebnisse hängen laut Quellcode stark von der Anfrage ab. [SpClient](https://github.com/librespot-org/librespot/blob/v0.8.0/core/src/spclient.rs) | Zuerst Ergebnistypen, Seiten und Fehler mit der echten Sitzung prüfen. Eine vollständige Katalogsuche ist damit noch nicht belegt. |
| Titel oder Kontext auswählen | `Spirc.load` lädt einen Kontext auf dem aktiven Receiver und lässt die bestehende Queue bestehen. [Spirc](https://github.com/librespot-org/librespot/blob/v0.8.0/connect/src/spirc.rs) | Neuer Befehl mit bestätigtem Ergebnis nötig; derzeit kein entsprechender No5-Aufruf. |

Play/Pause, Next/Previous, Seek, Shuffle und Repeat besitzt unser Receiver bereits. Der lokale Vertrag fasst Kontext- und Titelwiederholung aktuell als ein Repeat-Signal zusammen; librespot bietet auch einen eigenen Titelwiederholungsaufruf. Das würde eine gezielte Vertragserweiterung erlauben. Für Queue-Einfügen oder -Umsortieren ist im öffentlichen `Spirc`-Handle kein entsprechender No5-fertiger Aufruf vorhanden. Interne Connect-Nachrichten allein sind noch kein belastbarer App-Vertrag.

Die laufende Queue bezeichnet die Wiedergabereihenfolge einschließlich Shuffle, eingefügter Titel und gegebenenfalls Autoplay. Die zugrunde liegende Playlist ist eine separate Sammlung. Ein Kontext kann außerdem ein Album, Suchergebnis oder die Lieblingssongs sein. In der Oberfläche deshalb zuerst **„Als Nächstes“**, daneben den tatsächlich bekannten Kontext; „Playlist“ nur bei bestätigtem Playlistkontext.

## Konkreter Weg zu echten Covern

Im Receiver übernimmt `src/main.rs::player_event` aus `TrackChanged` heute ID, Titel, Künstler, Album und Dauer, aber keine Cover. `src/state.rs` enthält keine Bildfelder. Der Gateway-Adapter setzt `artworkId` auf `None`. In HELM lässt `AudioV1Decoder` für dieses Feld nur den kleinen prozeduralen `AudioArtworkCatalog` zu; `AudioNativeScene` hat noch keinen echten Bitmaploader. Eine URL in `artworkId` würde deshalb keinen Spotify-Cover anzeigen.

Vorgeschlagene Umsetzung:

1. Beim echten Titelwechsel einen eigenen, versionierten Bilddescriptor aus den gelieferten Metadaten übernehmen. Eine tatsächlich vorhandene, passende Bildgröße wählen; keine CDN-Adresse aus vermuteten IDs erzeugen. Fehlendes Bild bleibt fehlend.
2. Im Gateway einen eng begrenzten Bildabruf vorsehen: nur zugelassene Spotify-CDN-Adressen über HTTPS, begrenzte Redirects, Zeit, Bytes und Bilddimensionen; keine weitergegebenen Zugangsdaten. Der Abruf läuft außerhalb des DSP- und Befehlsaktors. Ein begrenzter Speicher-Cache verhindert wiederholte Downloads.
3. Das Bild über den bestehenden Gateway mit unverändertem HTTPS-Hostnamen und gültiger Zertifikatsprüfung anbieten. Dafür ist weder ein neuer offener Dienst noch eine geänderte Trust-Konfiguration nötig. Den Metadatenvertrag kompatibel ergänzen, statt das bisherige Katalogfeld umzudeuten.
4. HELM lädt und dekodiert im Hintergrund. Ergebnisse an Receiver-Sitzung und Titel binden, verspätete alte Bilder verwerfen und nur die betroffene Bildfläche aktualisieren. Fader-Ereignisse lösen weder Downloads noch einen kompletten Neuaufbau aus.
5. Originalbild proportional anzeigen, mit dem vorgesehenen kleinen Eckenradius; Beschriftung und Spotify-Verweis außerhalb des Bildes. Im Nachtmodus bei Bedarf das Cover ausblenden, statt es rot einzufärben oder mit Text zu überdecken. Spotify verlangt unveränderte Bilder, Attribution und einen Rückverweis. [Designvorgaben](https://developer.spotify.com/documentation/design)

Das ist der kleinste erste Ausbau. Abnahme: echter Titelwechsel und passendes Bild; schneller Wechsel ohne altes Cover; fehlendes Bild, Netzwerkfehler und übergroße Antwort ohne Audioaussetzer; Nachtmodus und Android-10-Darstellung; unveränderte TLS-Prüfung.

## Was eine zusätzliche Spotify-Freigabe eröffnet

Die Übergabe an einen Connect-Receiver ersetzt nicht den OAuth-Zustimmungsprozess für eine eigene No5-Developer-App. Spotify bindet Web-API-Zugriff an diese App und ihre genehmigten Berechtigungen. [Autorisierung](https://developer.spotify.com/documentation/web-api/concepts/authorization)

| Funktion | Offizieller API-Weg | Berechtigung / relevante Grenze |
| --- | --- | --- |
| Aktueller Titel und Queue | `GET /me/player/queue` | Dokumentiert sind `user-read-currently-playing` und `user-read-playback-state`. Ergebnis ist die Queue des autorisierten Kontos; vor Anzeige die tatsächliche No5-Wiedergabe zuordnen. [Queue](https://developer.spotify.com/documentation/web-api/reference/get-queue) |
| Eigene und gefolgte Playlists | `GET /me/playlists` | `playlist-read-private`, für kollaborative Inhalte gegebenenfalls `playlist-read-collaborative`. [Playlistliste](https://developer.spotify.com/documentation/web-api/reference/get-a-list-of-current-users-playlists) |
| Playlisttitel | `GET /playlists/{id}/items` | Aktuelle Dokumentation beschränkt auf Eigentümer oder Mitwirkende; andernfalls 403. Das bloße Folgen einer Playlist genügt nicht. [Playlistinhalt](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items) |
| Bibliothek lesen | Gespeicherte Titel, Alben, Shows, Episoden und Hörbücher über die jeweiligen Endpunkte | `user-library-read`; gefolgte Künstler separat mit `user-follow-read`. [Gespeicherte Titel](https://developer.spotify.com/documentation/web-api/reference/get-users-saved-tracks), [Scopes](https://developer.spotify.com/documentation/web-api/concepts/scopes) |
| Katalogsuche | `GET /search` | Titel, Alben, Künstler, Playlists, Shows, Episoden und Hörbücher; aktuelle Referenz maximal 10 Ergebnisse je Typ pro Seite, standardmäßig 5. [Suche](https://developer.spotify.com/documentation/web-api/reference/search) |
| Ausgewählten Titel/Album/Playlist starten | `PUT /me/player/play` | Premium und `user-modify-playback-state`. Explizit den No5-Receiver adressieren. [Wiedergabe](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback) |
| Titel zur Queue hinzufügen | `POST /me/player/queue` | Premium und `user-modify-playback-state`; Reihenfolge zusammen mit anderen Player-Aufrufen ist nicht garantiert. [Queue ergänzen](https://developer.spotify.com/documentation/web-api/reference/add-to-queue) |
| Speichern, Entfernen, Playlistpflege | Generische Bibliotheksendpunkte und Playlist-Endpunkte | Nur auf Wunsch Schreibrechte, z. B. `user-library-modify`, `playlist-modify-private` oder `playlist-modify-public`. [Migration und Bibliotheksendpunkte](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide) |

Weitere passende Möglichkeiten wären zuletzt gehörte Titel und persönliche Top-Titel, mit `user-read-recently-played` beziehungsweise `user-top-read`. Ein zweiter Audio-Player in HELM ist dafür nicht erforderlich. Playlistbearbeitung bedeutet keine freie Bearbeitung der Playback-Queue; für deren Entfernen oder Umsortieren ist hier kein offizieller Endpunkt nachgewiesen.

**Sonderfall Suche ohne Benutzerlogin:** Die offizielle Katalogsuche kann auch mit einem App-Token aus dem Client-Credentials-Verfahren verwendet werden. Dafür braucht No5 trotzdem eine registrierte Developer-App und serverseitig gespeicherte App-Zugangsdaten; diese gehören nicht in eine APK. Dieser Weg erlaubt keine persönliche Bibliothek oder Player-Steuerung. Bei App-only-Suche ist ein Markt anzugeben. [Client Credentials](https://developer.spotify.com/documentation/web-api/tutorials/client-credentials-flow), [Suchparameter](https://developer.spotify.com/documentation/web-api/reference/search)

## Zentrale Konfiguration und bestehendes Concurrency-Modell

Ein optionaler Spotify-Anschluss gehört in HOME. Bei einer nativen Anmeldung eignet sich Authorization Code mit PKCE, ohne Client-Secret in der App. Ein erneuerbarer Grant kann zentral verwaltet werden; HELM und MAP benötigen dann keine eigenen Anmeldungsschritte. Für mehrere Personen ist die Bindung an das autorisierte Spotify-Konto ausdrücklich sichtbar zu machen. Ein Wechsel des Connect-Kontos darf nicht still die Bibliothek eines anderen Nutzers anzeigen. [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)

Lesende Browse-/Bildarbeit bleibt vom Audioaktor getrennt, mit begrenzten parallelen Anfragen und Abbruch alter Suchanfragen. Neue Wiedergabebefehle gehen durch denselben kontrollierten No5-Befehlspfad. Ein API-Erfolg bestätigt noch keine beobachtete Wiedergabe; ein ungewisser Schreibausgang darf nicht automatisch wiederholt werden. Insbesondere Queue-Einfügen wäre sonst doppelt möglich. Die Main-Lautstärke bleibt unter der bestehenden Camilla-Autorität; zusätzliche Browse-Funktionen führen keinen zweiten Lautstärkeregler ein.

## Grenzen der offiziellen API im Oktober 2026

Neue Developer-Apps beginnen im Development Mode. Der Eigentümer benötigt Premium; bis zu fünf freigeschaltete Spotify-Nutzer können eine solche App verwenden. Das ist eine Nutzer-, keine Gerätezahl. Mehrere No5-Displays desselben Kontos benötigen dadurch nicht automatisch mehrere Nutzerplätze. Eine vorhandene höhere Nutzerzahl kann Bestandsschutz haben. Extended Quota ist nicht vorausgesetzt. [Quota-Modi](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), [Migrationsleitfaden](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)

Die Juli-Änderung erlaubt inzwischen bis zu 25 Client-IDs je Developer; das ältere Februar-Limit von einer ID ist überholt. Development-Quotas werden je Developer-Konto geteilt. `429` kann eine ausgeschöpfte Quota melden; Bibliothek und Suche müssen das anzeigen und Anfragen begrenzen. [Änderung Juli 2026](https://developer.spotify.com/documentation/web-api/references/changes/july-2026)

Developer-App, Quota-Modus und reale Feature-Zugänglichkeit dieses Kontos wurden nicht abgefragt. Ein extra OAuth-Login ist also keine Garantie für jede beliebige Playlist. Die bestehenden privaten Connect-Schnittstellen sind ebenfalls nicht als dauerhaft stabile Spotify-API zugesichert.

## Empfohlene Reihenfolge und offene Nachweise

1. Cover und lesendes „Als Nächstes“ auf Basis der vorhandenen Sitzung ergänzen.
2. Playlistliste, Playlistinhalt, Lieblingssongs und Suchkontext mit kontrollierten, lesenden Laborproben verifizieren. Erst bestätigte Funktionen anbieten; keinen vollständigen Bibliothekszugriff versprechen.
3. Bei Bedarf eine optionale HOME-Freigabe für offizielle persönliche Bibliothek und Katalogsuche ergänzen; Berechtigungen nach tatsächlichem Funktionsumfang auswählen.
4. Auswahl, Queue-Einfügen und Bibliotheksschreibzugriff separat mit Bestätigungs- und Fehlerverträgen umsetzen.

Die Arbeit an Concurrency und Audioaussetzern ist unabhängig davon gesichert: [Concurrency-Nachweise](evidence/concurrency-20261009/validation.json), [Labor-Audioabnahme](../no5-marineos-lab/evidence/audio-continuity-20261009/README.md). Der Benutzer bestätigte wieder flüssige Musik. Android-Spotify-UI-Abnahme und ein kontrollierter wechselseitiger Lautstärketest Telefon/HELM stehen weiterhin aus. Das dafür gebaute Instrumentierungs-APK liegt ausschließlich im DeploymentLounge-Eingang; Lounge wird hier weder neu gestartet noch umgangen.

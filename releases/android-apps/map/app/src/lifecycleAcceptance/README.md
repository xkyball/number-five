# D1: isolierte Lifecycle-Abnahme

Implementierter Testhost, noch keine Geräteabnahme. Er verwendet den Produktions-Controller, die Produktionsbibliothek und `NativeChartEngine` mit echter JNI-/GLES-Eigentümerschaft. Eine eigene App-ID, Activity und Bibliothekswurzel verhindern, dass ein Testimport oder die globale Cancellation-Prüfung den Nutzerbestand erreicht.

Die ursprünglichen Lifecycle-Fälle verwenden ausschließlich einen Offline-Testbestand. Die aktuelle Havel-Auswahl, ihre Kamera/Einstellungen und beide alten Florida-Bestände samt Sicherungen in `com.no5.map` bleiben außerhalb des Testhosts. Der abgelehnte 70-Dateien-Restore und zusätzliche Bereinigung bleiben geschlossen. Kein Test startet `MainActivity`, liest den Home-Zugang oder importiert eine Nutzerdatei. Die zusätzliche Routenabnahme darf ausschließlich auf dem Emulator einen ausdrücklich konfigurierten Host-Testgateway verwenden (siehe unten).

## Root-Gradlevertrag

Root besitzt `map/app/build.gradle.kts`. D1 verändert keine Produktions- oder Gradledatei.

| Feld | Verbindlicher Wert |
|---|---|
| Build type | `lifecycleAcceptance`, `initWith(debug)`, Library-Fallback `debug` |
| Application-ID | `com.no5.map.lifecycleacceptance` über `applicationIdSuffix = ".lifecycleacceptance"` |
| Instrumentation-ID | AGP-eigene Test-ID dieser Variante; Target muss obige App-ID sein |
| Opt-in | `-Pno5.lifecycleAcceptance=true` |
| Instrumentation source | ausschließlich `src/androidTestLifecycleAcceptance`; gemeinsames `src/androidTest` und generierter Keyboardfixture ausgeschlossen |
| `testBuildType` | nur im Opt-in `lifecycleAcceptance` |
| Release-info | `apply` nur im Opt-in überspringen; gemeinsame Release-info-Datei unverändert |
| Normales Debug | keine Source-, Launcher-, Test-, Release-Info- oder Versionsänderung durch den Opt-in |
| Aktuelle zentrale Freigabe | Compile Instrumentation / JNI / Manifest; keine APK-Freigabe, Installation oder Geräteausführung |

Zentral erwartete Compile-Aufgabe: `:app:compileLifecycleAcceptanceAndroidTestKotlin` mit dem Opt-in. Die Aufgabe nennt eine erwartete AGP-Variante, keinen durch D1 ausgeführten oder bestandenen Build. Root muss zusätzlich das zusammengeführte App- und Testmanifest sowie die tatsächliche Instrumentation-Ziel-ID prüfen. Der App-Merger muss `MainActivity`, `AboutActivity`, `PackageReplacedReceiver`, `ReleaseProvider`, Home/Gateway-Queries, die privilegierten Home/System- und Keyboardberechtigungen entfernen. `INTERNET` ist für die opt-in Routenabnahme im App- und Testmanifest vorhanden; ohne deren Fixtureparameter wird kein Netzwerkclient erstellt. Der einzige Launcher ist `LifecycleAcceptanceActivity`; der Fixtureprovider ist nicht exportiert und vergibt keine URI-Grants.

## Daten und Wurzeln

Jeder reguläre Test wählt eine neue UUID. Ein externer Prozessabbruch muss dieselbe ausdrücklich angegebene UUID beim zweiten Lauf verwenden. Zulässig sind nur UUIDs aus Kleinbuchstaben/Hex und Bindestrichen. Es gibt keinen Intentparameter für einen Pfad, Paketnamen, Provider, Download oder eine beliebige URI.

`LifecycleAcceptanceContext` verlangt zur Laufzeit die feste App-ID und den Build type. Dateien und Cache liegen unter `files/lifecycle-acceptance-runs/<UUID>/`; Controllerwurzel ist dort `files/chart-library-v1`. Einstellungen bekommen ein Präfix pro Testlauf. `ISOLATED_TEST_RUN.json` bindet App-ID, UUID, kanonischen Root und den SHA-256 von `pins.json`. Ein vorhandener unpassender Marker stoppt den Lauf. Die unveränderten Original-ZIPs werden ausschließlich im privaten Verzeichnis dieser separaten App aus Assets erzeugt; jede Assetdatei wird vor Benutzung mit Größe und Hash geprüft. Der Provider akzeptiert nur fünf feste, lesende Fixture-URIs und keine Dateipfade.

| Fixture | Amtliche Originalbytes / Zweck |
|---|---|
| NOAA Seed | `US5BOSCC.000` bis `.002` (Edition 5, Update 2), `US5BOSDD.000` (Edition 5, Update 0) |
| NOAA Update | Original `US5BOSCC.003` und `.004`; Update-only und eine unveränderte zweite Zelle |
| Negative Gap | nur Original `.004`; die fehlende `.003` muss abgewiesen werden |
| WSV Base | Original `1W7BRW02.000`, Edition 8 / Update 0, separates Profil `wsv-inland-s57` |
| Slow Seed | dieselben Seedbytes; nur Provider-Pipe mit gekennzeichneter künstlicher IO-Verzögerung für den Abbruch beim Einlesen |

Die sieben S57-Assets haben zusammen 2.377.142 Bytes. Die `.s57`-Endung im APK-Assetnamen erhält die Bytes und vermeidet das bestehende Git-Ignore für `.000`; erst beim Repackaging wird der originale ENC-Dateiname verwendet. Herkunft/Einzelhashes stehen in [pins.json](assets/lifecycle-fixtures/pins.json). NOAA stammt unverändert aus MAP60 `0014763fa00badcfb97e5949f882815bd0f97a0a`, Pfad `map/chart-engine/src/main/assets/charts/noaa/`, mit dessen [PROVENANCE.json](assets/lifecycle-fixtures/PROVENANCE.json) und NOAA-Bedingungen. WSV stammt aus dem bereits amtlich gepinnten ZIP `WW82_Untere_Havel_2026-07-14.zip`, SHA-256 `f034b0b399257b0d08e5ee038ec23e85df75d5404d306f8b806a839657dce740`, Eintrag `Untere-Havel-Wasserstrasse_0-69_2026-07-14/ENC_ROOT/1W7BRW02.000`. WSV bekommt keine NOAA-Bedingungen. ZIP-Verpackung und der zusätzliche Herkunftsnachweis sind testgeneriert; DSID, Producer, Namen, Geometrie und Updatebytes sind unverändert.

Kein künstlicher EDTN0-Datensatz wird als Publisher-Abkündigung ausgegeben. Das Cancellation-Receipt-Verhalten bleibt deshalb durch die gezielten Hostregressionen gedeckt, nicht als neu durchgeführte Geräteabnahme markiert.

## Reguläre Instrumentationfälle

`ChartLifecycleAcceptanceTest` startet nur die eigene Activity mit einer neuen Test-UUID. Die Suite enthält sieben reguläre Fälle:

1. Partiales NOAA-Update: geprüfte vollständige Ersatzkette aktivieren, dauerhafte Auswahl übernehmen, zweite Zelle byteidentisch halten, Kamera/Präsentationswahl erhalten, danach ausschließlich echt freigegebene alte Testpakete löschen.
2. Fehlende Updatekette: Fehler melden; aktive Auswahl, dauerhafte Dateien, native Pins und vorhandene Paketbytes bleiben identisch.
3. Eine Zelle und anschließend die letzte deaktivieren: Dateien bleiben erhalten, leerer Bestand ist ausdrücklich persistent, Activity-Recreation reaktiviert keine Zelle.
4. Inaktives WSV-Testpaket löschen: aktives NOAA und dessen Bytes/Pins erhalten; anschließend letztes aktives NOAA-Testpaket löschen und leer nach Recreation bleiben.
5. Import während tatsächlich blockierter URI-Lesephase abbrechen: `CANCELLING` bleibt busy, bis der Abbruchpfad fertig ist; keine neue Paketfreigabe, alte Pins/Dateien unverändert, keine Stagingreste.
6. K1 beobachten und mit demselben Token fortsetzen: echtes natives Ziel schon aktiv, alte dauerhafte Auswahl noch unverändert, alte Paketbytes noch vorhanden.
7. K2 beobachten und fortsetzen: neues Ziel dauerhaft veröffentlicht, Cleanup ausstehend, echte native Freigabe erfolgreich, alte Paketbytes noch vor der Dateilöschung vorhanden.

Jede aktivierte Szene wartet auf eine bestätigte Frame-Generation der aktuellen Source/Content-Generation und vollständige erforderliche Geometrie ohne laufenden Loader. Das ist ein Quellen-/Frame-Protokollbeleg, keine nautische Pixelvollständigkeit oder physische Displaylatenz. Die Tests greifen für Lifecycleaktionen gezielt auf den echten Controller zu. System-OpenDocument, der UI-Löschdialog einschließlich „Abbrechen“ und sichtbare Darstellung bleiben eigene manuelle UI-Abnahmeschritte. Activity-Recreation ist ausdrücklich kein Prozesskill.

## Autoritativer Prozesskill: zwei Phasen je K1/K2

`LifecycleAcceptanceEngine` delegiert an die echte Engine. Er verändert keinen Rückgabewert, keine Statusgeneration und keinen Release-ACK. Die einzige künstliche Funktion ist ein höchstens 30 Sekunden dauerndes Anhalten der Rücklieferung. Ein Timeout löst einen Fehler aus; er wird niemals als Commitbestätigung behandelt.

| Punkt | Genau angehaltene reale Grenze | Erwartung vor Kill | Erwartung nach echtem Kill und Neustart |
|---|---|---|---|
| K1 | erfolgreiche `activateResidentCatalog(activation)` ist abgeschlossen; Ergebnis noch nicht beim Controller angekommen | Native CC Update 4 / DD 0, Library weiter CC Update 2 / DD 0, alter Payload vollständig | alte dauerhafte Auswahl/Quellpins CC 2 / DD 0 laden; ungepublizierter geprüfter Import darf erhalten bleiben, wird nicht automatisch aktiv |
| K2 | erfolgreiche `releaseRetiredChartSources(expectedCatalogGeneration)` ist abgeschlossen; Ergebnis noch nicht vor `finishCleanup` angekommen | neue Library CC 4 / DD 0, Pending Cleanup, echter Release-ACK, alter Payload noch vollständig | neue dauerhafte Auswahl/Quellpins CC 4 / DD 0 laden; erneute reale Freigabe und Pending Cleanup fertig; alte ausgeschlossene Testpakete entfernt |

Vor Sichtbarkeit der Barriere schreibt der Wrapper einen per `fd.sync` und atomarem Rename veröffentlichten JSON-Nachweis. Er enthält App-ID/Root/UUID, PID/UID, Operation-ID/Token, echte Native-Ergebnisse, echte vollständige Native-Sourcepins und Source/Catalog-Generationen, alte/current Library-Digests, dauerhaften Zustand, alle Library-Dateihashes, Kamera, Präsentationswahl sowie Source/Content/Surface/Frame-Tags. Beim nächsten `prepare()` wird ein eigener neuer Nachweis geschrieben. Keine Libraryidentität wird aus dem residenten Teilbestand geraten. Die Arm-Prüfung verlangt das erwartete originale Updateziel, passenden Quellcommit und an beiden Grenzen die erwartete Veröffentlichung.

Nur nach separater Geräte-/Installationsfreigabe durch Root:

1. Einen neuen UUID-Run und den gewünschten Punkt wählen; Build/Installhash, Target-App-ID, vorhandene Nutzerdateien und Lounge-Slot zuvor durch Root pinnen. Nur die Methode `com.no5.helm.acceptance.ChartLifecycleAcceptanceTest#processKillRecipe` laufen lassen. Instrumentation-Argumente: `lifecyclePhase=arm-k1` (oder `arm-k2`) und `lifecycleRunId=<UUID>`. Arm verlangt einen frischen, leeren Testroot und legt darin die Seedbasis an.
2. Auf den fsyncten JSON-Nachweis mit passender UUID/PID und `phase=SOURCE_COMMITTED_BEFORE_SELECTION` beziehungsweise `SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES` warten. Dann innerhalb der 30-Sekunden-Barriere ausschließlich den Prozess der App `com.no5.map.lifecycleacceptance` über den autorisierten Gerätepfad hart stoppen. Kein Activity-Close, kein UI-Continue, kein `clear data`, keine Bibliotheksdatei ändern. Das Ende der Arm-Instrumentation ist bei diesem bewusst ausgelösten Prozesskill kein regulärer Test-PASS.
3. Dieselbe einzelne Methode mit `lifecyclePhase=verify-k1` beziehungsweise `verify-k2` und exakt derselben UUID starten. Die Activity initialisiert die echte Engine und ruft normales Controller-`prepare()` ausschließlich über den eigenen Testcontext auf. Verify verlangt eine neue PID, prüft dauerhafte Auswahl, echte Sourcepins/Dateihashes, den aktuellen Frame und die obigen K1/K2-Erwartungen. K2 verlangt abgeschlossene Freigabe/Cleanup; K1 verlangt unveränderten dauerhaften Digest und alte freigegebene Pins.
4. Arm-/Prepare-/Verify-JSON, Runnerresultat und tatsächlichen Stop-/Relaunchbeleg außerhalb der Test-App sichern. Root prüft die Nutzerbaseline unverändert. Jede andere PID/App-ID, ein unpassender Marker, fehlender ACK oder ein zu spät ausgeführter Kill macht den Versuch ungültig. Die zwei Punkte brauchen zwei getrennte neue UUIDs. Testwurzeln bleiben bis zur expliziten Befundsicherung erhalten.

Die Barriere ist bewusst begrenzt, damit ein vergessenes Weiter-/Kill-Signal keinen dauerhaften Produktionsjob blockiert. `@After` setzt lediglich eine normale Beobachtungsbarriere fort und schließt die eigene Activity; es löscht weder Runwurzeln noch alte Nutzerdaten. Power-loss/Disk-Full und Pin-/Loader-bedingtes negatives Release-ACK sind hier nicht injiziert. Insbesondere fehlende öffentliche Native-Pininjektion wird nicht durch einen Fake-ACK als Gerätebeleg ersetzt.

## Offene Freigaben / Ergebnisgrenzen

Quellen und Fixturepins sind geliefert. Zentrale Varianten-/Manifestprüfung, Kotlin-/Instrumentation-/JNI-Kompilation, spätere APK-Identität, Installation, isolierte reale Geräteausführung, Prozesskillabnahme und UI-Sichtprüfung sind getrennte noch ausstehende Schritte. Die Variante ist nicht MAP61 und ersetzt keine aktuelle MAP-App. D1 führt keinen eigenen Build, Test, Gerätezugriff, Commit oder Git-Push aus. Kein Source-PASS darf als Lumina-PASS berichtet werden.

## Zusätzliche Routen-, Overlay- und S52-Abnahme

`RoutesOverlaysFiltersEmulatorTest` prüft die Produktions-UI und JNI mit einem unveränderten o-charts-Paket im privaten Cache der Test-App. `routeFixtureCa` wird nur auf `ranchu`/`goldfish` akzeptiert und muss unter diesem Cache liegen. Standardmäßig bindet der Client fest an `no5-marineos-lab.local:19443` über `10.0.2.2`; synthetischer Key und Test-CA gehören zum isolierten Host-Testgateway mit einem separaten realen Signal-K-Ressourcenanbieter.

Für die reguläre Lab-VM kann zusätzlich `routeFixtureAccess` eine kleine private JSON-Datei unter demselben Test-App-Cache benennen. Ihr Schema ist `{"address":"<aktuelle Lab-Adresse>","port":9443,"keyBase64":"<privater Lab-Key>"}`; Hostname und Port bleiben fest auf `no5-marineos-lab.local:9443` beschränkt. Dazu wird die öffentliche Lab-CA als DER übergeben. Der Runner erzeugt eine frische synthetische Route, prüft und löscht diese und sichert nur den öffentlichen Prüfbericht und die Origin. Zugangsdaten werden weder in Quellen/APKs noch in Prüfarbeleg-Archiven gespeichert; beide privaten Cache-Fixtures sind nach der Prüfung zu entfernen. Es gibt keinen Zugriff auf Home-Anmeldung oder Lumina. Die Internet-Berechtigung allein aktiviert keinen Client.

Die Nutzerfreigabe für die Emulator-/Lab-Prüfung deckt diese Ausführungen ab. Der Ergebnisbeleg nennt die tatsächlich verwendete Origin. Ein Lab-Ergebnis bestätigt keine Installation des Gateway-Service auf dem produktiven Pi.

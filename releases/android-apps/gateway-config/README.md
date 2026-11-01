# Gateway-Zugang pro Gerät in Home

Home ist die einzige produktive Oberfläche für HTTPS-Gatewayname, Port, Auflösung, origin-gebundenen Zugang über Key-Datei, eingegebenen Key oder Passwort und öffentliche Gateway-CA. HELM und MAP benutzen dieselbe Konfiguration. App-interne Einstellungen für Wetterfreigabe, Einheiten und Kartenbestand bleiben unabhängig.

## Gerätezuordnung

Emulatoren sind anhand der Android-Emulator-Merkmale fest dem virtuellen Pi `no5-marineos-lab.local` zugeordnet. Auf physischen Geräten wird dieser Lab-Hostname abgewiesen. Der tatsächliche Name des echten Pi wird auf dem Gerät ausdrücklich eingerichtet; ein Produktionsname wird nicht erfunden. Discovery sucht ausschließlich die konfigurierte HTTPS-Dienstinstanz und den konfigurierten Port. Kein Ausweichen zwischen Labor und Produktion und kein automatischer Hostwechsel.

Android-10-NSD liefert eine numerische Adresse. Der Gateway besitzt die bekannte DNS-SD-Konvention, dass der Dienstinstanzname dem Hostnamen ohne `.local` entspricht. Der Dienstinstanzname wird nicht als nachgewiesener SRV-Hostname ausgegeben. Mit importierter CA bestätigt die HTTPS-Prüfung zusätzlich den unveränderten logischen Hostnamen. Eine manuelle Adresse wird ausdrücklich als MANUAL gespeichert und ist kein mDNS-Nachweis.

## Prüfung und Wiederherstellung

Beim Öffnen von Home und alle 60 Sekunden im Vordergrund wird zuerst mDNS versucht. Scheitert es, wird die vorhandene Adresse geprüft. Nach zwei Online-Fehlern wird sie gesperrt und Home fordert eine erneute Auflösung an. Ohne Netzwerk bleibt sie erhalten. Neue manuelle Adressen werden erst nach einer erfolgreichen Prüfung gespeichert. Ohne importierten Zugang prüft Home zunächst TCP-Erreichbarkeit; mit einem gültigen Home-Profil wird `/v1/status` über HTTPS mit Zertifikats- und Hostnamenprüfung gelesen und mit dem strengen No5-V1-Statusdecoder validiert. Eine bestätigte Gateway-Verbindung setzt keine verfügbaren HELM-Sensoren voraus; deren HTTP-/Datenstatus prüfen HELM und MAP getrennt. TCP-Erreichbarkeit allein wird nicht als TLS- oder Datenabnahme bezeichnet.

Es gibt keinen Hintergrunddienst, keinen Alarm und keine neue Multicast-/Firewallregel. Während Home pausiert, laufen keine periodischen Home-Prüfungen. Beide Clients reagieren auf Konfigurationsänderungen und prüfen sie bei Rückkehr; ausgefallene Daten bleiben unavailable. Die periodische Prüfung erfolgt erneut beim nächsten Home-Aufruf.

## Gemeinsame Sicherheit — für den normalen Emulator freigegeben

Home bekommt INTERNET und ACCESS_NETWORK_STATE sowie einen eigenen ContentProvider. Der Provider verlangt `com.no5.home.permission.GATEWAY_CONFIGURATION` mit Schutzstufe signature, zusätzlich die exakten Clientpakete `com.no5.helm`/`com.no5.map` und einen passenden Signaturvergleich zum tatsächlichen Aufrufer-UID. Beide Clients prüfen Paket und Home-Signatur ebenfalls. Geteilte UIDs mit zusätzlichen Paketen werden abgewiesen. Metadatenabfragen enthalten weder Schlüssel noch Zertifikatkörper. Der gesonderte Profilaufruf liefert origin-gebundenes Material ausschließlich für die aktive, nutzbare Konfiguration an diese beiden Clients; keine Client-Schreiboperationen.

Der gemeinsame Dialog „Gateway-Zugang“ verarbeitet Key-Datei, eingegebenen Key oder Passwort. Eingegebene Werte werden lokal abgeleitet; die öffentliche CA wird bei fehlendem oder beschädigtem lokalem Bestand für die beiden bekannten Pi-/Labor-Origins automatisch über gepinntes TLS geladen. Der manuelle Dateiimport bleibt erhalten. Details und aktuelle Grenzen: [PASSWORD_ACCESS.md](PASSWORD_ACCESS.md). Bestehende HELM-Dateien werden nicht durch Tools gelesen, kopiert oder automatisch migriert. Speicherung bleibt AES-256-GCM/Android Keystore mit Origin als AAD, Backup ist deaktiviert. Importe benutzen unverändert die vorhandenen bounded Importer mit Readback/Rollback. Keine System-CA, keine deaktivierte Hostnamenprüfung, keine IP-URL und keine Credential-URL. URL, SNI und Header-Origin bleiben identisch; nur die Socketadresse wird aufgelöst. Profile und temporäre Bytearrays werden nach Gebrauch gelöscht. Kein Token/Key/CA-Körper in UI, Logs oder Exporten.

HELM behält seine zentralen Source-Sessions und alle bisherigen Gateway-V1-Adapter. Konfigurations- und Materialänderungen tauschen die Session aus; reine Check-Zeitstempel tun das nicht. Bei fehlender, beschädigter oder unvollständiger Home-Konfiguration wird kein alter app-lokaler Endpunkt aktiviert.

MAP liest `/v1/helm` über denselben Home-Zugang. Der alte `/no5/helm/v1/snapshot`-/Stream-Vertrag bleibt nur als bestehende Test-/Bibliotheksschnittstelle. Die produktive MAP-Activity akzeptiert keinen eigenen Verbindungsendpunkt mehr. Die Konvertierung benutzt den bestehenden strengen Decoder und die Beobachtungszeiten jedes Sensorfeldes; fehlende Werte werden nicht zu Null, alte Positionen werden verborgen. AIS/Routen werden von No5-Gateway V1 nicht geliefert und erscheinen ausdrücklich nicht verfügbar.

## Builds und Abnahme

Die drei unabhängigen Gradle-Projekte bleiben unabhängig. `gateway-config/android.gradle` bindet gemeinsame Quellen ein; unveränderte Security- und Decoder-Dateien wurden zum Teilen verschoben. AGP9 benötigt für MAP zusätzlich seine Kotlin-Source-API. Bestehende Signierung wird nicht geändert. APKs gehen ausschließlich in `~/DeploymentLounge/incoming/`; keine direkte Installation durch Codex.

Der Nutzer hat den genau beschriebenen Zugriff des Home-Providers am 2026-10-08 für die Abnahme im normalen AVD HeaderFixture_API29_ARM64 ausdrücklich freigegeben. Andere Geräte sind nicht Bestandteil dieser Laufzeitfreigabe. Für die Abnahme sind die drei zueinander passenden APKs über den vorgesehenen Deployment-Prozess bereitzustellen und Zugang einmal in Home zu importieren. Die Geräteabnahme muss Adresse/MANUAL-versus-MDNS, gültiges HTTPS, tatsächliche HELM-/MAP-Daten, Schlüssel-/CA-Wechsel, Fehler/Wiederkehr und Tag/Nacht prüfen. Diese Quelländerung ist kein neuer Emulator-mDNS-PASS. Der frühere Transportversuch und seine Wiederherstellung bleiben unter `helm/docs/MARINEOS_API29_MDNS.md` dokumentiert.

Für den ausdrücklich eingegebenen, zur Geräteumgebung passenden HTTPS-Origin können Schlüssel/CA auch vor einer bestätigten Adresse oder im Zustand NEEDS_INPUT importiert werden. So können ein widerrufener Schlüssel oder ein Zertifikatswechsel repariert werden, ohne die Prüfung zuvor mit dem alten ungültigen Zugang bestehen zu müssen. Datenabrufe bleiben bis zur erfolgreichen erneuten Prüfung gesperrt.

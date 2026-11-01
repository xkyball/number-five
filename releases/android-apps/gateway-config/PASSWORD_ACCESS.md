# Ein Gateway-Zugang für Datei, Key und Passwort

Stand: 10. Oktober 2026. Home13 `0.4.5-ca-recovery`, HELM59
`0.7.49-ca-recovery`, MAP55 `0.4.50-ca-recovery`.

Der bestehende Key-Zugang heißt „Gateway-Zugang“ und öffnet genau einen nativen
Dialog mit einem verdeckten Eingabefeld „Gateway-Key oder Passwort“.
„Verbinden“ prüft den eingegebenen Key oder das Passwort nach lokaler Ableitung.
„Key-Datei laden“ öffnet den bestehenden Dateiimport. Es gibt kein zusätzliches
Passwortfeld und keinen Einrichtungs- oder Registrierungsablauf in der App.
Die beiden vorhandenen Felder für Origin und Verbindungsadresse bleiben erhalten.

Alle eingegebenen Werte werden lokal mit PBKDF2-HMAC-SHA256/600.000 Iterationen
abgeleitet. Der Prehash wird erst nach strikter TLS-Prüfung übertragen und
nach erfolgreicher Bestätigung originbezogen mit Android Keystore/AES-GCM
in einem separaten Store gespeichert. Der Klartext wird nicht gespeichert
oder an Pi/Codex übertragen. Die gemeinsame Home-Konfiguration liefert das
gewählte Profil über den bisherigen signaturgeschützten Provider an HELM/MAP.
HTTPS und beide WebSocket-Pfade benutzen denselben originbezogenen Header.
Importierte NO5-GATEWAY-KEY-V1-Dateien behalten den bisherigen Key-Header.
Die beiden gespeicherten Arten werden bewusst gewechselt; fehlerhafte Zugänge
führen nicht zu einem automatischen Rückfall. Readback und Import-Rollback
bewahren den vorher gewählten Zugang und getrennte bereits gespeicherte Werte.

Der Nutzer erzeugt seinen Passwort-Prüfdatensatz ausschließlich selbst mit
`no-pi/projects/no5-pi-gateway/tools/create_password_verifier.py` und übergibt
nur dessen JSON für die einmalige administrative Pi-Konfiguration.
Der Pi speichert eine separat gesalzene zweite Ableitung; sie ist selbst kein
Login. Der Client-Prehash ist dagegen eine wiederverwendbare Berechtigung über
TLS. Die Passwortwahl liegt beim Nutzer, ohne Mindestlänge oder Zeichenklassen-
Vorgabe. Das lokale Generator-Tool fragt zweimal verdeckt und weist verschiedene
Eingaben ab. Schwache Passwörter können aus Prüfdatensätzen offline geraten werden.

Eine fehlende oder beschädigte lokale CA wird für die zwei bekannten Pi-/Labor-Origins automatisch
über gepinntes TLS aus `/auth/v1/ca` bezogen. PKIX-Kette und Hostname werden
weiterhin geprüft; unbekannte Origins brauchen manuelle CA-Bereitstellung.
Eine gültige vorhandene CA bleibt erhalten. Eine unlesbare oder ungültige lokale
Kopie wird erst nach vollständig geprüftem Download ersetzt. Scheitert der
Download oder die Prüfung, bleibt der Eintrag erhalten und die Verbindung
gesperrt; die nächste Home-Verbindungsprüfung versucht den Abruf erneut.
Keine globalen CA-, Backup-, Signatur- oder Gerätezuordnungsänderungen.

Ausgeführte hostseitige Prüfungen: Home132, HELM458, MAP21/MAP-Client4 Tests;
Domain170 unverändert UP-TO-DATE. Alle Builds und Lints bestanden, HELM/MAP-
Instrumentation kompiliert. ASCII/Unicode-KDF, originbezogene HTTP/WSS-Header,
Pin-/Kettenablehnung, bestehende/beschädigte CA, einzelner Dialog/Fileimport
und Import-Rollback sind abgedeckt. Der kompilierte TrustManager hat zusätzlich
beide echten öffentlichen CAs auf Host-JDK17 geladen und falsche Pins sowie
Hostnamen abgewiesen. Der vollständige produktive Bootstrap hat zusätzlich
fehlgeschlagene Entschlüsselung, ungültige Zertifikatsbytes und Lesefehler
automatisch repariert; falsche CAs verändern den lokalen Eintrag nicht.
Aktueller [Wiederherstellungsnachweis](../../ca-recovery-20261010/README.md). Labor-Passwort/Key und echter Pi-Key bestehen die live
HTTP/WSS-Prüfungen.

Keine neue native Geräteabnahme: Es war ausschließlich das physische Lumina
per ADB verbunden. Emulatoren verwenden nur das Labor; Lumina nur den echten
Pi. Keine direkte APK-Installation durchgeführt. Die drei finalen Main-APKs und
beide Test-APKs wurden über den bestehenden Weg an DeploymentLounge übergeben.
Die finalen SHA256-Werte in `/Users/chris/projects/max/ca-recovery-20261010/apks.json`
sind maßgeblich; vorherige Entwürfe mit gleicher Buildnummer sind überholt.
Der vom Nutzer gelieferte persönliche Prüfdatensatz ist auf dem echten Pi
installiert. Paralleler alter Key und Ablehnung des gespeicherten Prüfwerts als
Login sind bestätigt; die persönliche Anmeldung mit App-Eingabe bleibt offen.

Serverdetails, Aktivierung, Sicherungen und Grenzen:
[Pi-Dokumentation](../../no-pi/projects/no5-pi-gateway/docs/PASSWORD_AND_CA_BOOTSTRAP.md).
[Belege](../../password-gateway-20261010/README.md).

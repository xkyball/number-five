# Installierte Version und „Was ist neu“

Stand 2026-10-07. Jedes Top-Level-Projekt bleibt eigenständig. `android.gradle` registriert pro Variant
Java-Quellen und erzeugte Assets über die offizielle Sources-/Variant-API. Es
fügt Java-Quellen und erzeugte JSON-Assets in genau die aufrufende App ein; es
gibt keinen Root-Build, keine zusätzlichen Laufzeit-Abhängigkeiten und keine
automatische Installation. Die 12 aktiven APKs stehen in `apps.json` und `INVENTORY.md`.
Archivierte Apps stehen ausschließlich unter `archivedProjects`; ihre Quellen
und Kataloge bleiben am dort gepinnten GitHub-Stand wiederherstellbar.

## Integrationsvertrag für Produkt-Owner

`ReleaseInfo.versionLine(context)` ist die knappe sichtbare Versionszeile mit
Version, Android-Code und Source-Commit. Ein expliziter „Was ist neu“-Button
öffnet `AboutActivity.open(activity)`. `ReleaseInfo.details(context)` liefert
denselben Text für eine eigene native About-Fläche. Die Activity ist privat.
Die Kurzliste zeigt höchstens fünf deduplizierte Änderungen; weitere sind ausdrücklich aufklappbar.
Es gibt keine automatischen Dialoge, Benachrichtigungen, Overlays oder Timer.
Benchmark-UIs deaktivieren den About-Button zusammen mit ihren Bedienelementen.
HELM-/MAP-UI-Hunks werden separat an die parallel arbeitenden Owner geliefert.

## Semantik und Beleggrenzen

„Vorher installiert“ bedeutet die unmittelbar vor **dieser Installation auf
diesem Gerät und für diesen Android-Nutzer** installierte APK, unabhängig von
App-Starts. Bei direktem Wechsel Release1→Release4 werden die belegten Änderungen
von Release2/3/4 kumulativ nach Änderungs-ID dedupliziert. Bei Installation
1→2→4 ohne App-Start ist 2 der Vorgänger und das Intervall 3/4.

Der Android-[Update-Broadcast](https://developer.android.com/reference/android/content/Intent#ACTION_MY_PACKAGE_REPLACED)
enthält keine Vorgängerdaten. Er kann bei
[gestoppten Paketen ausbleiben](https://developer.android.com/about/versions/android-3.1#launchcontrols).
`PackageInfo.firstInstallTime`/`lastUpdateTime` belegen Zeitpunkte, keine frühere
Version. Ein zuletzt beobachteter Stand wird deshalb **niemals** zum belegten
Installationsvorgänger hochgestuft. Broadcast und App-Aufruf erfassen den aktuellen
Stand und markieren den Vorgänger unbekannt, bis ein verifiziertes Deployreceipt
vorliegt. Selbst eine scheinbar lückenlose Broadcastfolge ist keine Garantie.

Ein bestehender Altstand wird beim Erstrollout vor Installation als tatsächliche
APK gelesen. Exakte historische APK-Hashes dürfen ihn einer Release zuordnen;
VersionCode/Name allein dürfen das nicht. Ohne Hashbeleg bleibt seine Release-ID
unbekannt, obwohl Version/Code/APK-Hash belegt sein können. Die UI nennt dann
keinen relativen Changelog. Fehlende historische Notizen werden kenntlich gemacht.
Kein rückwirkend konstruiertes Installationsjournal und keine erfundenen Änderungen.

## Deployment und Datenschutz

`tools/release_deploy.py` ist ein expliziter ADB-Deployment-Einstieg. Ein Dry-Run
ist rein lokal. Ein tatsächliches Deploy benötigt einen ausdrücklich gewählten
Serial, Android-Nutzer und `--deploy`; es führt keine Starts oder Benchmarks aus.
Vor/nach wird die tatsächlich installierte APK geprüft. Nur nach erfolgreicher
Installation plus Readback werden Receipt und Journal geschrieben. Das Journal
ist geräte-/nutzer-/paketspezifisch. Fehlgeschlagene Installationen bewegen keine
Baseline. Installationen außerhalb dieses Wegs bleiben konservativ unbekannt.

Der Provider hat `android.permission.DUMP` als Manifestschutz. Zusätzlich prüft
**jeder** `call` Binder-Caller exakt auf UID2000 (ADB shell) oder UID0 (root).
Normale Apps, system UID und selbst die App-UID werden abgewiesen. Ausschließlich
`snapshot` und `record-installation` sind implementiert; Query/Insert/Update/Delete
sind gesperrt. Es ist keine allgemeine Datei- oder Präferenzschnittstelle.
Das bestehende ADB-Vertrauensmodell wird genutzt; kein neues Uses-Permission und
kein Zugriff auf Gateway-Schlüssel, Endpunkte, Tokens, fremde Daten oder Signierkeys.
Ein Receipt prüft APK-SHA256, Paket, Code/Name/Build-ID, Nutzer und Installationstime
gegen die Laufzeit sowie eine zufällige lokale Challenge. Wiederholtes identisches
Receipt ist idempotent; andere Receipts derselben Installation werden abgewiesen.

Der Zustand liegt ausschließlich im privaten `noBackupFilesDir` als `AtomicFile`.
Reinstall/Datenreset erzeugen eine neue Challenge; Belege anderer Geräte, Nutzer
oder Installationsepochen sind nicht übertragbar. Ohne externen Installbeleg sind
Erstinstallation und Datenreset bei gleichen PM-Zeitpunkten nicht unterscheidbar;
die UI sagt unbekannt. Ein belegtes Fresh-Deploy hat ausdrücklich keinen Vorgänger.

## Release- und Build-Identität

Jede App besitzt `releases.json` mit stabilen IDs und streng steigender Sequence.
Historische Namen/Codes stammen aus Git oder vorhandenen Release-Belegen und
behaupten keine damalige Installation. Ein Quellen-Belegcommit ist nicht automatisch der
Commit einer damaligen APK: `artifactSourceCommit` wird nur mit explizitem APK-Beleg
zugeordnet, andernfalls bleibt die frühere APK-Quelle unbekannt. Der nächste Release wird angehängt,
niemals umnummeriert. Änderungs-IDs sind stabil; Wiederholungen werden dedupliziert.
Der Bridge-Build verweigert einen Widerspruch zwischen Android-Version und Katalog.
Source-Commit, Dirty-Status und ein SHA256 über Commit plus relevante Quellen
bilden den Build-Fingerabdruck. Branchbuilds gleicher Version bleiben unterscheidbar.
Downgrade und anderer Build derselben Release werden ausdrücklich bezeichnet.

## Prüfung

Tests: `python3 -m unittest discover -s release-info/tests -v` und im Launcher
`testDebugUnitTest` für die gemeinsamen Java-/Android-Tests (API29 und API35).
`tools/release_metadata.py --check --project helm` prüft isoliert nur HELM; fehlende andere
Owner-Projekte sind dafür keine Build-Abhängigkeit. `tools/release_metadata.py --check` prüft alle App-Kataloge und Integrationshunks.
Tatsächlich ausgeführte Builds/Tests stehen in `verification/STATUS.md`.
Keine APK-Installation, kein Gerätetest und keine Benchmark-Unterbrechung gehören
zum aktuellen Implementierungsauftrag.

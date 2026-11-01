# Native Knopfclient-Basis, Kandidat Protocol 2

Diese gemeinsamen Android-Quellen sind für die isolierte Kandidatenkopie.
Sie enthalten keine OEM-Eingabequelle, Audiofunktion, Standardaktion oder
Vordergrund-Erkennung. API29 reicht aus. Weder AIDL noch Broadcasts oder Netzwerk
werden verwendet. Der aktuelle MAP54/R0-Kartenrenderer wird hier nicht verändert.

Root verdrahtet in HOME, MAP und HELM die Main-Quellen dieses Ordners als
zusätzlichen SourceSet; die Robolectric/JUnit-Tests gehören nur in einen
koordinierten Testeinstieg. Es gibt absichtlich keinen neuen Gradle-Einstieg und
keine Manifeständerung durch diesen Teilauftrag.

## Öffentliche Schnittstellen

```kotlin
val client = SystemControlClient(activity) { envelope: KnobEnvelope ->
    // Genau eine gewählte Appaktion, ODER die ausdrückliche Weitergabe an Audio.
    ClientDisposition.HANDLED // REFUSED und AUDIO_DEFAULT sind die anderen Antworten
}
client.onResume()
client.onWindowFocusChanged(hasFocus)
client.onPause()
client.updateBindingsEpoch(nextPositiveMonotonicEpoch) // VOR App-/Screenbelegung ändern
client.close()
// Ein Health-/Lifecycle-Owner kann nach einem fehlgeschlagenen Bind gezielt retryConnection() auslösen.

val host = ControlBinderHost(helmServiceContext, controllerSession = freshSession)
// Service.onBind gibt host.binder zurück. Keine zusätzliche IPC-Fokustransaktion.
host.observeForeground(ObservedForeground(packageName, epoch, resumed, userId, displayId = 0))
val ready: ReadyClient? = host.findReadyClient(packageName, observedOSforegroundEpoch)
host.dispatchAsync(envelope) { result: ClientDisposition ->
    // Callback auf Main: Root prüft den Originalscope nochmals und führt nur AUDIO_DEFAULT aus.
}
// host.dispatch(envelope) ist die begrenzte synchrone Variante für Hintergrundaufrufer.
host.close()
```

`KnobEnvelope` enthält ausschließlich `protocol=2`, `controllerSession`,
`sequence`, `foregroundEpoch`, `clientEpoch`, `bindingsEpoch`, `input`,
`issuedAtUptimeMs` und `expiresAtUptimeMs`. Ausgabe- und Ablaufzeit verwenden
`SystemClock.uptimeMillis()`; das gültige Zeitfenster beträgt höchstens250ms.
Default ist Ausgabe jetzt und Ablauf250ms später. Host prüft vor der Queue,
vor Binder-Input und nach der Antwort; Client vor dem Main-Post und unmittelbar
vor seiner Appaktion. Unplausible, abgelaufene und zu lange Zeitfenster werden verweigert.
Die acht Inputnamen entsprechen dem bestehenden Java-Router. Wirecodes sind
explizit 1..8. `ReadyClient` liefert Package/UID und die passenden Session-/
Epochenwerte; `clientEpoch` wird bei jeder Registration vom Host neu vergeben.
Alle Sequenzen und Bindings-Epochen sind positiv und monoton pro Registration;
eine Session muss bei einem neuen Controller-Lebenslauf neu entstehen.

## Service-/Manifestvertrag, von Root umzusetzen

- HELM deklariert `com.no5.helm.permission.SYSTEM_CONTROL` mit Protectionlevel
  `signature`. HOME/MAP/HELM verwenden diese Permission.
- Expliziter Service: `ComponentName("com.no5.helm",
  "com.no5.helm.control.HelmSourceService")`, enabled/exported, geschützt durch
  genau diese Permission. Kein implicit Bind, kein Discovery-Broadcast.
- Alle drei Kandidaten brauchen dieselbe freigegebene APK-Signatur. Debug-
  Keystores unabhängiger Rechner sind kein nachgewiesener Signaturvertrag.
- HELM-Service implementiert Lifecycle/Foreground-Servicebedarf separat und
  schließt den Host bei seinem Ende. Ein offener Binderclient allein garantiert
  keine dauerhafte HELM-Audio-Lebensdauer.

Hosttransaktionen erfassen und prüfen `Binder.getCallingUid()` auf dem
eingehenden Binderthread. Die UID muss zum Hostuser und genau EINEM Package
aus HOME/MAP/HELM gehören, die Permission besitzen und mit HELM signaturgleich
sein. Ein behaupteter Packagename kann die UID nicht ersetzen. Shared-UIDs mit
mehreren Packages werden verweigert. Der Host benötigt keine
`clearCallingIdentity()`-Operation.

Der Client prüft Servicekomponente, Permissiondeklaration inklusive
Signature-Level, gleiche Signatur/User und den eindeutigen HELM-UID-Besitzer.
Jeder Callback wird vor dem Lesen seiner Nutzdaten gegen diese erwartete
HELM-UID geprüft. Register/State/Deregister enthalten nur begrenzte Primitive
und einen Callbackbinder; Sessionstrings sind maximal80, Packagenamen maximal128
Zeichen und jedes Parcel maximal2048Bytes. Höchstens drei Packageregistrationen.
Fehlt die Dienstkomponente oder die Permissiondeklaration im PackageManager,
verwandelt die Identitätsprüfung dessen `NameNotFoundException` gezielt in eine
verweigerte `SecurityException`. Das gilt vor dem Bind und bei der erneuten
Prüfung in `onServiceConnected`; eine zwischenzeitlich verschwundene Installation
wird vor der Registration ungebunden. Es gibt keinen Catch-all für `Throwable`.

## Gültigkeit und genau eine Entscheidung

App-resumed/window-focused sind ausschließlich zusätzliche Admission-Gates.
Sie sind KEIN Beleg für den OS-Vordergrund. Root liefert den autoritativen
Vordergrund in-process an `observeForeground`, erhöht dessen Epoch bei jeder
Scopeänderung und serialisiert diese Beobachtungen mit Router/Dispatch und der
Ausführung einer gültigen `AUDIO_DEFAULT`-Antwort. Ohne passenden beobachteten
Package-/User-/Display0-/Resumed-Scope ist kein Appclient bereit.

Hostdispatch verbraucht die Sequenz VOR dem Callback und erlaubt nur eine
laufende Zustellung pro Registration. Nach dem Callback prüft er erneut
dieselbe Registration, Session, Foreground- und Bindings-Epoch. Root darf nur
das dann gültige `AUDIO_DEFAULT` genau einmal an seinen AudioSink geben;
`HANDLED` führt keine zusätzliche Audioaktion aus. `REFUSED`, Timeout,
Callbackfehler oder Binderdeath lösen KEINEN anderen Owner/Fallback aus.
HELM-Audiostandard bei echtem Fremd-/Systemvordergrund ist ein anderer,
autoritativer Root-Serviceweg; kein zuletzt aktiver Appclient.

Der Client bindet nur resumed+focused, deregistriert/unbindet bei Pause oder
Fokusverlust und verwirft alte Servicegenerations-/Session-/Client-/Bindings-/
Foreground-/Sequenzcallbacks. Service- und Callbackdeath sind verknüpft.
Admission-Revisionen verhindern, dass eine verspätete Ready-Nachricht eine
neuere Sperre überschreibt. Screen-/Belegungswechsel machen bestehende Queues
vor der State-Transaktion ungültig.

Consumer laufen auf dem Main-Looper. Ein Hintergrund-Bindercallback wartet
höchstens250ms; lokal auf Main wird direkt konsumiert. Timeout markiert ein
noch nicht gestartetes Job als cancelled. Wenn der Consumer bereits läuft,
darf er einmal enden; sein verspätetes Ergebnis wird verweigert. Ein Timeout
ist deshalb KEIN Beleg dafür, dass keinerlei Appaktion stattgefunden hat.
Die Sequenz ist auch für abgebrochene Jobs bereits verbraucht. Keine erneute
Zustellung/Fallback daraus ableiten.

Host-Statepublish und `dispatchAsync` blockieren Main nicht durch synchrone
Callback-Binderaufrufe. Alle Hosts eines Prozesses teilen genau EINEN
Daemon-IPC-Worker mit höchstens drei Warteschlangenplätzen. Statepublish
fasst neuere Revisionen zusammen; eine volle Queue verweigert Zustellung.
Der Async-Deadline-Ticker entwertet den Pending-Dispatch, entfernt noch nicht
gestartete Workerjobs und liefert genau eine Antwort auf Main. `close()`
beendet offene Dispatches mit `REFUSED`; verspätete Antworten gelten nicht.
Hostdispatch sendet vor Input die aktuelle Admission erneut, falls eine frühere
Statepublication keinen Queueplatz bekommen hatte.

Ein laufender synchroner Binderaufruf lässt sich dadurch nicht zuverlässig
abbrechen. Er kann diesen EINEN Prozessworker dauerhaft belegen; es werden
keine Ersatzworker aufgebaut. Weitere Inputs laufen aus oder werden verweigert.
Auch ein bereits laufender Main-Consumer kann Main selbst blockieren: Die
250ms sind eine Zustell-/Gültigkeitsgrenze, keine Garantie für die Antwortlatenz
eines blockierten Main-Loopers. Sein spätes Ergebnis führt zu keiner Audioaktion.
Service und Activity im selben HELM-Prozess müssen `dispatchAsync` verwenden,
damit ihr gemeinsamer Main den Appcallback konsumieren kann.

## Prüfungen und Grenzen

Die API29-Robolectric-Tests prüfen u.a. UID/Shared-UID/User/Signature-Gates,
Primitive/Version/Größengrenzen, Appfokus gegenüber OS-Wahrheit, veraltete
Scopes/Bindings/Registrations, Replay, Death, Disposition nach Scopewechsel,
Bind-Lifecycle, fremde Callback-UID, alte Admission, gecancelte Queues und
bereits gestartete Consume-Timeouts.
Hinzu kommen ein blockierter State-Binder, Queueüberlauf, Close mit offenen
Dispatches, Ablehnung vor/nach Main-Queue-Ablauf sowie der tatsächliche
`SystemControlClient` im lokalen HELM-Main mit je genau einer gültigen
`HANDLED`-/`AUDIO_DEFAULT`-Antwort über `dispatchAsync`.
Eine weitere PackageManager-Testklasse prüft auf API29/35 fehlenden Dienst,
fehlende Permission, deren Verschwinden nach dem Bind sowie gültige Installation
mit explizitem Retry. Sie verwendet echte PackageManager-Lookups auf installierte
Robolectric-Paketfixtures und den öffentlichen Clientkonstruktor.

Die Testkonstruktoren und simulierten Binder-Identitäten sind `internal`.
Solche Hosttests beweisen die Policy und lokalen Binder-/Looperpfade, keine
echte Kernel-UID-Propagation, Multi-Prozess-/Service-Lebensdauer, OEM-Eingabe,
physische Latenz oder Audio-/Systemoberflächenabnahme. Build-/Testergebnis wird
erst nach dem koordinierten Kandidatenbuild ergänzt; keine Hardwareaktion.

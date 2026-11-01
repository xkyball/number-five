# MAP-Routen über Signal K

Der vorhandene Gateway-Service bietet `GET /v1/map/routes`,
`PUT /v1/map/routes/{uuid}` und `DELETE /v1/map/routes/{uuid}`.
Alle verlangen denselben `X-No5-Gateway-Key`
wie AUDIO und die TLS-Frontdoor. Home bleibt die Zugangskonfiguration der App.
Es gibt keinen zweiten Android-Zugang zu Signal K.

Im vorhandenen Gateway-TOML wird der konkrete Ressourcenanbieter ausdrücklich
konfiguriert; ohne diesen Abschnitt bleibt die Funktion verfügbar gemeldet als
nicht konfiguriert:

```toml
[map_routes]
enabled = true
resources_url = "http://127.0.0.1:3000/signalk/v2/api/resources/routes"
# Nur falls Signal K Schreibauthentifizierung benötigt:
# token_file = "/etc/no5-gateway-proxy/signalk-routes-token"
```

Signal K benötigt einen aktivierten Ressourcenanbieter für Routen und einen
gegebenenfalls schreibberechtigten lokalen Token. Diese Änderung installiert
oder aktiviert keinen Anbieter und verändert keinen laufenden Dienst.

GET liefert `{routes: {uuid: {route, token}}, unavailableIds: []}`.
Nicht darstellbare Originalgeometrien werden als IDs gemeldet, statt eine
Route durch Auslassen von Punkten zu verkürzen. PUT nimmt genau
`{expectedToken: null|sha256, route: <vollständige Signal-K-Ressource>}` an.
UUID, GeoJSON-Reihenfolge `[longitude, latitude]`, Renderergrenzen und
Größenlimits werden geprüft. Der Gateway prüft den gelesenen Basisstand,
serialisiert eigene Schreibzugriffe und bestätigt erst einen passenden
Signal-K-Readback. Ein wiederholter identischer Payload ist idempotent.
DELETE verlangt genau `{expectedToken: sha256}`, prüft ebenfalls den Basisstand
und bestätigt erst, wenn der Ressourcenanbieter die UUID als entfernt meldet.
Signal K bietet hierfür keine garantierte Transaktion gegen gleichzeitige
Schreibzugriffe anderer Clients; der Readback erkennt eine abweichende Antwort.

Routenführung, Autopilot, Telemetrie und allgemeine URL-Proxies sind nicht Teil
dieses Pfads. Lokal speichert MAP Entwürfe und die letzte bestätigte Offline-Kopie.
Fehlende Rechte, Konflikte und Providerfehler erhalten den bearbeitbaren Entwurf.

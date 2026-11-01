# GPS-Livekarte

Kleine, rein lesende Browseransicht auf dem physischen Pi:
`http://lysmarine.local:8798/`. Keine Anmeldung im lokalen WLAN-Netz.
Alle Quellen aus dem vorhandenen Gateway und dessen Kombination werden angezeigt.
Keine Neuberechnung, Rückeinspeisung oder Änderung der GPS-Konfiguration.

## Betrieb

- Python 3.11+, ausschließlich Standardbibliothek; Leaflet 1.9.4 lokal beigelegt
  (Lizenz in `static/vendor/LICENSE`). Internet nur für OpenStreetMap-Kacheln.
- Genau ein gemeinsamer Gateway-Abruf pro Sekunde, unabhängig von Browseranzahl.
  Originalmesszeiten bestimmen Alter und Spuren; keine Speicherung von GPS-Daten.
- Webzugriff nur auf der aktuellen privaten IPv4-Adresse von `wlan0` und aus
  deren lokalem Subnetz. Kein Router-Portforwarding einrichten. Nach einem
  Wechsel der Pi-IP den eigenen Dienst neu starten.
- `no5-gps-map.service` startet automatisch, als dynamischer unprivilegierter
  Benutzer. Schlüssel über systemd-Credentials; nie im Browser oder Repository.
  Bei Rotation des Gateway-Schlüssels den privaten Schlüssel gezielt erneuern
  und nur diesen Dienst neu starten.
- `/api/positions` gibt ausschließlich Quellen-/Positions-/Zeit-/Kombinationsdaten
  zurück. Kein allgemeiner Gateway-Proxy, keine Schreibmethoden.
- Die letzten 60 Sekunden Spuren liegen pro Browser im Arbeitsspeicher. Neustart
  der Gateway-Sitzung oder Neuladen der Seite beginnt eine neue Historie.
- Maximalzoom 24, OSM-Kacheln bis Zoom 19, darüber Vergrößerung; sichtbare
  Quellenangabe, regulärer Browser-Cache und Referrer gemäß OSM-Kachelregeln.
- Die kleine Umgebungskarte zeigt Straßen unabhängig vom engen Metermaßstab.
  Häufige Positionsaktualisierungen verwenden keine Karten-Überblendung.
- Der Simulationsstatus stammt aus dem GPS-Katalog, unabhängig von anderen
  simulierten HELM-Werten.

## Wiederaufbau und Rücknahme

Den gesamten Ordner ohne `__pycache__`/`*.pyc` in ein eigenes temporäres
Verzeichnis auf den Pi übertragen. Dort ausführen:

```sh
sudo -n python3 -B install.py --interface wlan0
```

Der Installer prüft Pi-Identität, privaten Netzwerkanschluss und freien Port.
Er übernimmt den vorhandenen Gateway-Schlüssel ausschließlich Pi-lokal über
dessen strikten Parser. Unveränderliche Versionen liegen unter
`/opt/no5-gps-map/releases/`, `current` verweist auf die aktive Version.
Private Einstellungen und eng begrenzte Rücknahmebelege liegen unter
`/etc/no5-gps-map/`. Andere Dienste werden nicht neu gestartet.
Fehlgeschlagene Inbetriebnahme stellt die vorherige Version wieder her;
bei einer Erstinstallation wird der neue Dienst deaktiviert.

Öffentliche Prüfung ohne Koordinatenprotokoll:

```sh
systemctl is-active no5-gps-map
systemctl is-enabled no5-gps-map
python3 - <<'PY'
import json, urllib.request
with urllib.request.urlopen('http://lysmarine.local:8798/api/positions',timeout=3) as r:
    d=json.load(r)
assert d['upstreamAvailable']
print({'sources':len(d['sources']),'combination':d['combination']['status']})
PY
```

Gezielte Abschaltung: `sudo systemctl disable --now no5-gps-map.service`.
Vorversion aus dem letzten privaten Installationsbeleg über `current`
wiederherstellen, zugehörige `previousUnit` übernehmen, `daemon-reload` und nur
`no5-gps-map` neu starten. Versionsordner und Belege erhalten.

## Unsichtbare Tests

```sh
python3 -B -m unittest discover -s tests -p 'test_*.py'
node --test tests/model.test.mjs
node tests/browser.cjs
```

Der Browsertest benötigt Playwright und einen Headless-Chromium; bei Bedarf
`NODE_PATH` und `CHROMIUM_EXECUTABLE` auf vorhandene Installationen setzen.
Er startet ausschließlich eine Loopback-Testquelle mit erfundenen Koordinaten,
blockiert externe Kacheln und beendet seine eigenen Prozesse. Kein Appfenster.

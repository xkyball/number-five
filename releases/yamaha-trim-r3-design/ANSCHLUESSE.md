# R3 · kurze Anschluss- und Befestigungsübersicht

Alle Steckerpins werden nach `delivery/svg/05-pins.svg` und der Draufsicht zugeordnet; am vorhandenen Stecker vor Bestromung vergleichen. Die Kennungen stehen auch im Gesamtverkabelungsplan.

| Anschluss | Pins | Aufgabe |
|---|---|---|
| J1 · 2-polig | 1 Geber-Signal, 2 Masse | Passiver Originalgeber, keine externe Speisespannung |
| J2 · 3-polig | 1 rosa Yamaha-Leitung, 2 frei, 3 Yamaha-Masse/schwarz | Anzeigeanschluss; rosa wird vom Relais geöffnet |
| J3 · 4-polig | 1 Bordnetz+ über quellennah1A-Sicherung, 2/3 frei, 4 Masse | Versorgung |
| J5 · 4-polig | 1 Wandler IN+, 2 IN−, 3 OUT+, 4 OUT− | Vier angelötete Wandlerleitungen enden lösbar auf der Platine |
| JP1 · Steckbrücke | Wandler5V an Elektronik5V | Für USB-Service entfernen |

Das vorhandene FBR211SBD012-M ist im Plan als K1 bezeichnet. Pins1/12 sind der gemeinsame Kontakt zur rosa Yamaha-Leitung; Pin8 ist der stromlos offene Kontakt zur nachbildenden Elektronik; Pin7 bleibt unbeschaltet. Pins5/6 sind die Spule. Zeichnung und Durchgangsmessung am vorhandenen Teil vergleichen; das Herstellerbild ist eine Unteransicht. Zwei Transistorwege schalten die Spule: Arduino-Freigabe und regelmäßige Lebenszeichen. Bei Programmstillstand fällt die Lebenszeichenschaltung ab. Ein verschweißter Kontakt bleibt eine Grenze.

26×M3x8: Platine4, Nano2, DAC2, Wandler4, Steckerhalter8, Stecker-Spannschrauben4, USB-Verschluss2. 6×M3x10 für die drei Kabelsättel; 4×M3x12 für die Haube von unten. Direktverschraubung in gedruckte Löcher; keine losen Muttern/Einsätze. Tatsächliche Kopfform und Halt am Druck prüfen. Äußere Montagelaschen haben Schraublöcher und Kabelbinderschlitze; Schrauben zum unbekannten Untergrund sind kein erfundener Bestandsposten.

Stecker lösen: jeweils zwei Halteschrauben zum festen Träger entfernen, den codierten Halter mit Stecker10mm nach hinten ziehen und dann abheben. Die Spannschraube im Halter bleibt dabei angezogen. Haube vorher abnehmen; Leiterführungen mit Serviceweg und offenen Wandler-Padmaßen stehen in `delivery/harness.json`. Kabel-/Drahtlängen sind Planwerte; keine Zuschnitte wurden gebucht.

135×65mm-Platine aus145×65mm zuschneiden. Acht Bohrungen und kupferfreie8×8mm-Bereiche nach SVG. Lackdrahtbrücken bleiben vollständig oben; Koordinaten, sechs Höhenlagen, Endanschlüsse und Längen in `delivery/jumpers.csv`. Besetzte Pins erhalten obere Lötlaschen; keine zweite Ader gewaltsam ins Loch stecken. Freie Lötenden dürfen keine Nachbarstreifen berühren. Die 2mm-Steckerabstandhalter benötigen ausreichend lange Stifte des vorhandenen Steckers.

USB-Service: J2/J3 abziehen, JP1 entfernen, erst dann USB verbinden. Normalbetrieb ohne USB und mit JP1. Wandler vor Anschluss der Elektronik auf gemessene5V einstellen. Gleichzeitiges USB/Bordnetz-CSV ist am konkreten Nano noch zu prüfen und wird nicht als rückspeisefrei zugesichert.

Seriell115200Baud: `help`, `config`, `status`, `v5 <gemessene mV>`, `ecu <Leerlauf-mV> <Quellwiderstand-Ohm>`, `cal low`, `cal high`, `range <Ohm> <Ohm>`, optional `point <normalisierter Messwert> <Ohm>`, `save`, `arm`, `disarm`, `log 1`. R2-Werte werden nicht übernommen. `run 0` ist Standard; `run 1` nur ausdrücklich für gewählten Kaltstart speichern. Nach Fehler bleibt eine bewusste Wiederfreigabe erforderlich. Reale Bootloader-/Unterspannungsreaktion bleibt zu prüfen.

Gemeinsame Masse bedeutet auch: Eine einzelne unterbrochene Masseleitung kann durch einen anderen Rückleiter verdeckt werden. Das Programm kann nicht jede Masseunterbrechung sicher erkennen. Ein physisch festhängender DAC-Ausgang wird erst über die Ausgangsabweichung überwacht; vor dem Relaisschließen sind Ladefolge und Sollwertvorgabe geprüft, keine vollständige analoge Einzelfehlersicherheit. Fehlerspeisung und echte Abschaltzeit müssen am Prüfstand gemessen werden.

Platzbedarf: Körper188×106×48mm; einschließlich Montagelaschen und Kabelverschraubungen etwa210×129.2×48mm. Zusätzlichen Raum für äußere Kabelbögen, Schraubendreher und Wartung vorsehen. Der konkrete freie Raum unter der Motorhaube ist noch nicht vermessen. Das vorhandene Relais ist nicht hermetisch dicht; Temperatur sowie Feuchte/Kondensation am Einbauort bleiben zu prüfen.

import type { Lang } from './projects';
export type Copy = {de:string;en:string};


export const storyChapters=[
  {
    "id": "refit",
    "title": {
      "de": "Eigentlich wollten wir nur das Boot modernisieren.",
      "en": "We only meant to modernise the boat."
    },
    "date": {
      "de": "Ausgangspunkt · mehrere Arbeiten liefen parallel",
      "en": "Starting point · work developed in parallel"
    },
    "image": "helm-day",
    "project": "system",
    "steps": [
      {
        "de": "Das Vorhaben: Auf Number Five sollten große Instrumente, Karten, eine gute Musikanlage und ein Sonar zusammenpassen. Die Technik sollte sich nach dem Boot richten: nach dem Platz am Steuerstand, dem zweiten Bedienplatz und dem verfügbaren Budget.",
        "en": "The intention: Number Five needed large instruments, charts, a good sound system and sonar that worked together. The technology should fit the boat: its helm, a second control position and the available budget."
      },
      {
        "de": "Das Hindernis: Eine Sammlung passender Programme ist noch kein zusammenhängendes Bordsystem. Jedes zusätzliche Gerät brachte eigene Startabläufe, Einstellungen und Vorstellungen davon mit, wer gerade den gültigen Zustand kennt. Beim Einbau kommen Versorgung, Anschlüsse und Wartungszugang dazu.",
        "en": "The obstacle: A collection of suitable programs is not yet a coherent boat system. Every added device brought its own startup sequence, settings and assumptions about who held the current state. Installation adds power, connections and maintenance access."
      },
      {
        "de": "Die Versuche: Wir begannen mit BBN Marine OS, AvNav und vorhandener Bootstechnik. Statt eine fertige Produktspezifikation abzuarbeiten, begegneten wir immer neuen Abhängigkeiten: Für eine bessere Anzeige brauchten wir Daten; für verlässliche Bedienung bestätigte Befehle; für ein zusätzliches Display gemeinsame Zustände.",
        "en": "The attempts: We started with BBN Marine OS, AvNav and existing boat equipment. Rather than executing a finished specification, we kept finding dependencies: better displays needed data, reliable controls needed acknowledged commands, and another display needed shared state."
      },
      {
        "de": "Die Entscheidung: Das Projekt wird nach Aufgaben aufgeteilt. Daten erfassen, Musik abspielen und Sonar auswerten gehören in eigenständige Dienste. Anzeigen und Bedienung dürfen davon getrennt auf den Geräten entstehen.",
        "en": "The decision: Divide the project by responsibility. Acquiring data, playing music and processing sonar belong in independent services. Displays and controls can then develop separately on the devices."
      },
      {
        "de": "Der Gewinn: Ein Teil lässt sich verändern, ohne jedes andere Teil neu zu bauen. Ein Instrumentendisplay muss nicht gleichzeitig Musikserver sein; ein neuer Bedienplatz braucht keine zweite Musikanlage. Genau darin liegt der Anfang des Baukastens.",
        "en": "The gain: One part can change without rebuilding every other part. An instrument display does not also need to be the music server; another control position does not need another sound system. That is where the building kit begins."
      },
      {
        "de": "So bauen wir weiter: Wiederholbare Installation und Geräteprofile machen die Bausteine auf weiteren Booten nutzbar. Jeder neue Aufbau kann das gemeinsame Rezept verbessern.",
        "en": "How we build further: Repeatable installation and device profiles bring the components to more boats. Every new build can improve the shared recipe."
      }
    ]
  },
  {
    "id": "integration",
    "title": {
      "de": "Daten verfügbar heißt noch lange nicht gut bedienbar.",
      "en": "Available data does not automatically mean good controls."
    },
    "date": {
      "de": "Systemintegration · BBN, AvNav und Signal K",
      "en": "System integration · BBN, AvNav and Signal K"
    },
    "image": "helm-night",
    "project": "helm",
    "steps": [
      {
        "de": "Das Vorhaben: Vorhandene Standards nutzen. Signal K macht Bootsdaten über gemeinsame Schnittstellen zugänglich; NMEA verbindet vorhandene Geber und Geräte. BBN bündelt wichtige Grundlagen, AvNav brachte Navigation und Integrationsmöglichkeiten mit. Diese Arbeit mussten wir nicht neu erfinden.",
        "en": "The intention: Use existing standards. Signal K exposes boat data through common interfaces; NMEA connects existing sensors and equipment. BBN bundles important foundations, while AvNav provided navigation and integration options. We did not have to invent that work again."
      },
      {
        "de": "Das Hindernis: Für unseren Aufbau blieben Lücken zwischen Daten, Gerätestart und einer zusammenhängenden Touch-Bedienung. Ein empfangener Wert erklärt noch nicht, ob er frisch ist. Ein gedrückter Knopf beweist noch nicht, dass der Dienst den Befehl ausgeführt hat.",
        "en": "The obstacle: Our setup still had gaps between data, device startup and coherent touch controls. Receiving a value does not tell you whether it is fresh. Pressing a button does not prove that the service executed the command."
      },
      {
        "de": "Die Versuche: Wir haben die vorhandenen Dienste verbunden, Zustände verfolgt und eigene Ansichten aufgebaut. Dabei musste klar werden, welcher Dienst für welchen Wert verantwortlich ist, wie ein Ausfall sichtbar wird und welche Bedienfunktion das angeschlossene System tatsächlich unterstützt.",
        "en": "The attempts: We connected existing services, traced state and built our own views. That required identifying the authority for each value, making failures visible and checking which controls the connected system actually supported."
      },
      {
        "de": "Die Entscheidung: Ein eigener Gateway vermittelt Daten, Fähigkeiten und bestätigte Zustände. HTTP und WebSocket bleiben die Transportwege im lokalen Netz. Die heutige Darstellung läuft dagegen in nativen Android-Apps. Andere Projekte gehen ebenfalls moderne Wege: KIP beschreibt seine Bedienung ausdrücklich als Touch-orientiert.",
        "en": "The decision: Our gateway exposes data, capabilities and acknowledged state. HTTP and WebSocket remain the transport mechanisms on the local network. Today’s presentation runs in native Android apps. Other projects also take modern approaches: KIP explicitly describes touch-oriented controls."
      },
      {
        "de": "Der Gewinn: Anzeigen können dieselbe Grundlage verwenden, ohne dieselbe Oberfläche sein zu müssen. Fehlende Daten werden nicht als Nullwert versteckt. Bedienung kann zwischen angefordertem und bestätigtem Zustand unterscheiden.",
        "en": "The gain: Displays can use the same foundation without having to share the same interface. Missing data is not hidden as a zero value. Controls can distinguish a requested state from a confirmed state."
      },
      {
        "de": "So bauen wir weiter: Neue Funktionen an denselben Datenwegen ergänzen und Home Assistant als zusätzliche Systembasis erschließen. Dienste und native Oberflächen bleiben dabei getrennt weiterentwickelbar.",
        "en": "How we build further: Add functions through the same data paths and explore Home Assistant as another system base. Services and native interfaces can continue to evolve independently."
      }
    ]
  },
  {
    "id": "devices",
    "title": {
      "de": "Das günstige Display wurde zum eigenen Entwicklungsprojekt.",
      "en": "The inexpensive display became a project of its own."
    },
    "date": {
      "de": "Zwei Luminas · unterschiedliche Firmware",
      "en": "Two Luminas · different firmware"
    },
    "image": "map-routes-native",
    "project": "displays",
    "steps": [
      {
        "de": "Das Vorhaben: Ein großes, bezahlbares Android-Display als frei gestaltbarer Bedienplatz. Die 75 Euro für ein Display sind meine Preisangabe. Interessant wird das Gerät durch das, was lokal darauf laufen kann – nicht allein durch seine Bildschirmdiagonale.",
        "en": "The intention: An affordable, large Android display as a control position we could design ourselves. The €75 display price is my own purchase report. What makes it interesting is what can run locally on it, beyond its screen size."
      },
      {
        "de": "Das Hindernis: Das angezeigte Android 14 war auf den untersuchten Geräten tatsächlich Android 10 mit API 29. Zwei äußerlich ähnliche K706-Geräte hatten unterschiedliche OEM-Firmware. Startverhalten, Tasten und Drehknöpfe konnten deshalb nicht einfach als identisch behandelt werden.",
        "en": "The obstacle: The advertised Android 14 was actually Android 10 with API 29 on the devices examined. Two apparently similar K706 units had different OEM firmware. Startup, buttons and knobs could not simply be treated as identical."
      },
      {
        "de": "Die Versuche: Wir verglichen die reale Hardware, Betriebssystemangaben und Bedienereignisse. GPS-Aufbereitung, Wiederaufnahme und die Zuordnung physischer Regler wurden eigene Arbeitsbereiche. Fehlende Herstellerunterlagen mussten durch nachvollziehbare Untersuchung ersetzt werden.",
        "en": "The attempts: We compared actual hardware, operating-system properties and control events. GPS handling, resume behaviour and mapping physical controls became separate areas of work. Missing manufacturer documentation had to be replaced with traceable investigation."
      },
      {
        "de": "Die Entscheidung: Geräteprofile statt einer pauschalen Annahme über „das Lumina“. Die Apps berücksichtigen die tatsächliche Plattform. Schnittstellen und Bedienelemente werden gezielt angebunden, statt von einem vollständigen Herstellersupport auszugehen.",
        "en": "The decision: Device profiles rather than a blanket assumption about “the Lumina”. The apps target the actual platform. Interfaces and controls are connected deliberately, rather than relying on complete manufacturer support."
      },
      {
        "de": "Der Gewinn: Günstige Geräte werden als ernsthafte Bedienplätze nutzbar. Gestaltung und Datenzugriff sind unsere Entscheidungen. Ein leistungsfähigeres Gerät kann denselben Ansatz verwenden; wie stark es beschleunigt, muss jeweils gemessen werden.",
        "en": "The gain: Inexpensive devices become useful control positions. Presentation and data access are our choices. A more powerful device can use the same approach; the actual performance gain must be measured for each device."
      },
      {
        "de": "So bauen wir weiter: Einbauposition, Helligkeit, Wärmeabfuhr und Schutz gegen Feuchtigkeit auf das eigene Boot abstimmen. Weitere Geräteprofile machen neue Displays anschlussfähig. Die 75 Euro gehören zum Display; Einbau und Versorgung haben ihre eigenen Kosten.",
        "en": "How we build further: Match mounting position, brightness, cooling and moisture protection to the boat. Further device profiles bring new displays into the system. The €75 is for the display; installation and power have their own costs."
      }
    ]
  },
  {
    "id": "charts",
    "title": {
      "de": "Karten mussten auf dem Gerät funktionieren.",
      "en": "Charts needed to work on the device."
    },
    "date": {
      "de": "Von frühen Studien zu MAP 110 · Oktober 2026",
      "en": "From early studies to MAP 110 · October 2026"
    },
    "image": "map-graphite",
    "project": "maps",
    "steps": [
      {
        "de": "Das Vorhaben: Karten direkt mit den Fingern bedienen und die Darstellung selbst gestalten. Frühe Boston-Studien mit NOAA-Daten zeigten mögliche Stile. Eine hübsche Einzelansicht beantwortete aber noch nicht, wie Laden, Drehen und Wiederaufnahme auf einem günstigen Gerät funktionieren.",
        "en": "The intention: Control charts directly with our fingers and shape the presentation ourselves. Early Boston studies using NOAA data explored visual styles. An attractive still image did not yet answer how loading, rotation and resume would work on inexpensive hardware."
      },
      {
        "de": "Das Hindernis: Kartengeometrie braucht Speicher und einen klaren Lebenszyklus. Ein Renderer kann eine Szene korrekt zeichnen und trotzdem beim Zurückkehren, beim Laden oder unter Speicherdruck scheitern. Auch ein diagnostischer Zellüberblick ist noch keine sauber lesbare Navigationsansicht.",
        "en": "The obstacle: Chart geometry needs memory and a clear lifecycle. A renderer can draw one scene correctly yet fail on return, while loading or under memory pressure. A diagnostic overview of chart cells is not yet a readable navigation view."
      },
      {
        "de": "Die Versuche: Wir verglichen Darstellungswege und bauten lokales natives Rendering, Gesten und Arbeitsseiten auf. Auf beiden physischen Luminas wurden im Stand MAP 110 am 26. Oktober 96 Zellen, mehrere Drehwinkel und ein normaler Neustart geprüft. Die Geometriegrenze lag in diesem Versuch bei 512 MiB.",
        "en": "The attempts: We compared rendering approaches and developed local native rendering, gestures and workspaces. With MAP 110 on 26 October, both physical Luminas were checked with 96 cells, multiple rotation angles and a normal restart. The geometry budget in that trial was 512 MiB."
      },
      {
        "de": "Die Entscheidung: Auf dem Gerät zeichnen und Arbeitsseiten für Routen, Ebenen und Objektinformationen als echte App-Bedienung behandeln. Die vorhandenen Open-Source-Grundlagen bleiben benannt. Leistungsdiagnostik und Bildschirmaufnahme werden getrennt ausgewertet.",
        "en": "The decision: Render on the device and treat route, layer and object-information workspaces as real app controls. Existing open-source foundations remain credited. Performance diagnostics and screen recordings are evaluated separately."
      },
      {
        "de": "Der Gewinn: Die Karte ist Teil unserer eigenen Bedienumgebung. Gestaltung und Geräteauswahl entwickeln sich unabhängig von einem einzelnen Plotterhersteller. Die Konverter liefern außerdem Decoder und Schreibbausteine für eigene Wege zu Lighthouse/RX5 und Navionics.",
        "en": "The gain: The chart is part of our own control environment. Presentation and device choice evolve independently of a single plotter supplier. The converters also provide decoders and writer components for our own routes to Lighthouse/RX5 and Navionics."
      },
      {
        "de": "So bauen wir weiter: Den beim Löschen und Zurückkehren aufgetretenen Ladefehler beheben und Objektabfrage und Kartendarstellung verbessern. Die aktuelle Vorführung nutzt das NOAA-Boston-Testpaket. Datenstand und Aufnahmezeit stehen bei den Bildern; frühere Stilbilder erzählen die Entwicklung.",
        "en": "How we build further: Fix the loading error seen after deleting data and returning, and improve object queries and chart portrayal. The current demonstration uses the NOAA Boston test package. Dataset revision and capture time accompany the images; earlier style studies tell the development story."
      }
    ]
  },
  {
    "id": "services",
    "title": {
      "de": "Musik und Sonar machten die gemeinsame Grundlage wertvoll.",
      "en": "Music and sonar made the shared foundation valuable."
    },
    "date": {
      "de": "Eigenständige Dienste · gemeinsame Bedienung",
      "en": "Independent services · shared controls"
    },
    "image": "sonar-physical-b205",
    "project": "audio",
    "steps": [
      {
        "de": "Das Vorhaben: Musik soll weiterlaufen, wenn sich ein Display ändert. Echos sollen dort sichtbar werden, wo man sie braucht. Beide Aufgaben verlangen eine zuverlässige Quelle und mehrere mögliche Bedien- oder Anzeigeorte.",
        "en": "The intention: Music should keep playing when a display changes. Echoes should be visible wherever they are needed. Both tasks need a reliable source and several possible control or viewing positions."
      },
      {
        "de": "Das Hindernis: Eine schicke Musiktaste kennt noch keinen echten Wiedergabezustand. Ein empfangenes Sonarpaket ist noch kein brauchbarer Tiefenwert. Beim Fishfinder waren die relevanten Signale zunächst im Gerät verborgen; bei Audio mussten Wiedergabe, Klangverarbeitung und Kanalzuordnung zusammenpassen.",
        "en": "The obstacle: An attractive music button does not know the real playback state. A received sonar packet is not yet a usable depth measurement. Relevant fishfinder signals were initially hidden inside the device; audio required playback, sound processing and channel routing to work together."
      },
      {
        "de": "Die Versuche: Für Audio entstanden eigenständige Pi-Dienste mit Spotify Connect, Klangverarbeitung und einer eigenen Oberfläche. Beim untersuchten Venterior erfassen wir Signale mit einem Pico, steuern Pings und werten Echos aus. Die native Sonaransicht zeigt reale Daten des Versuchsaufbaus.",
        "en": "The attempts: Audio gained independent Pi services with Spotify Connect, sound processing and a custom interface. On the Venterior examined, a Pico acquires signals while we control pings and process echoes. The native sonar view displays real data from the test setup."
      },
      {
        "de": "Die Entscheidung: Der zuständige Dienst führt den gültigen Zustand; ein Client sendet einen Auftrag und zeigt die Rückmeldung. Nicht verfügbare Funktionen bleiben erkennbar. Gemeinsamer Zustand ist dabei etwas anderes als bildgenaue oder verzögerungsfreie Synchronisation.",
        "en": "The decision: The responsible service holds authoritative state; a client sends a request and displays the response. Unavailable functions remain visible as such. Shared state is different from frame-accurate or zero-delay synchronisation."
      },
      {
        "de": "Der Gewinn: Eine Bedienung lässt sich ergänzen, ohne die eigentliche Musikwiedergabe zu duplizieren. Dieselben Sonardaten können zur Darstellung und zur Weitergabe der Tiefe dienen. Im Versuch vom 16. Oktober wurden im besten Fünf-Sekunden-Fenster 19,6 brauchbare Messungen pro Sekunde erreicht; über 600 Sekunden lag der Mittelwert bei 14,17.",
        "en": "The gain: Another control can be added without duplicating music playback. The same sonar data can serve the display and depth output. In the 16 October trial, the best five-second window reached 19.6 usable measurements per second; the 600-second average was 14.17."
      },
      {
        "de": "So bauen wir weiter: Die Venterior-Erfassung mit Tiefen- und Temperaturkalibrierung sowie dem Einbau an Bord verbinden. Der Versuch hängt von Gerät, Aufbau und Tiefe ab. Die Audiovorführung zeigt den gemeinsamen Lautstärkewert an beiden Luminas und im DSP: −39 → −40 → −39 dB. Reaktionszeiten können wir darauf aufbauend getrennt messen und verbessern.",
        "en": "How we build further: Connect Venterior acquisition with depth and temperature calibration and installation aboard. The experiment depends on device, setup and depth. The audio demonstration shows the shared volume value on both Luminas and the DSP: −39 → −40 → −39 dB. Response times can be measured and improved separately on that foundation."
      }
    ]
  },
  {
    "id": "building-kit",
    "title": {
      "de": "Aus Einzelreparaturen wird ein Baukasten.",
      "en": "Individual fixes become a building kit."
    },
    "date": {
      "de": "Weiterbauen · Software und Werkbank",
      "en": "Building further · software and workbench"
    },
    "image": "enclosure-exploded",
    "project": "build",
    "steps": [
      {
        "de": "Das Vorhaben: Die nächste Erweiterung soll nicht wieder bei null anfangen. Ein zusätzlicher Sensor soll vorhandene Datenwege nutzen, ein weiterer Bedienplatz vorhandene Dienste, ein neuer Dienst eine klar beschriebene Schnittstelle.",
        "en": "The intention: The next addition should not have to start from scratch. Another sensor should use existing data paths, another control position should use existing services, and a new service should expose a clear interface."
      },
      {
        "de": "Das Hindernis: Wiederverwendbare Software allein baut kein Boot um. Versorgung, Löten, genaue Anschlussmaße, Gehäuse, Kabelwege und Werkzeugzugang müssen zusammenpassen. In Südafrika wird schon die Beschaffung konkreter Varianten zum eigenen Problem.",
        "en": "The obstacle: Reusable software alone does not refit a boat. Power, soldering, exact connection dimensions, enclosures, cable routes and tool access must fit together. In South Africa, even sourcing specific variants becomes a project of its own."
      },
      {
        "de": "Die Versuche: Neben den Apps entstanden Wiederherstellungsrezepte, Elektronikaufbauten und parametrische Gehäuse. Aus Konstruktionen werden Druckteile und aus Druckteilen Einbauten. Jede dieser Erfahrungen verbessert die gemeinsame Grundlage.",
        "en": "The attempts: Alongside the apps came recovery recipes, electronics assemblies and parametric enclosures. Designs become printed parts and printed parts become installations. Each experience improves the shared foundation."
      },
      {
        "de": "Die Entscheidung: Eigene veröffentlichbare Software, Hardwarequellen und Unterlagen werden mit nachvollziehbarer Herkunft geteilt. KI-Unterstützung ist ausdrücklich Teil der Entwicklung. Änderungen müssen trotzdem verstanden und passend geprüft werden.",
        "en": "The decision: Share our publishable software, hardware sources and documentation with traceable origins. AI assistance is an explicit part of development. Changes still need to be understood and appropriately checked."
      },
      {
        "de": "Der Gewinn: Mitarbeit kann an der Werkbank beginnen. Eine bessere Halterung, ein lokal lieferbares Teil oder ein sauberer Einbau ist genauso wertvoll wie Code. Wer Fertigung oder Installation anbieten möchte, kann darauf ein eigenes Angebot aufbauen – im Rahmen der jeweiligen Lizenzen.",
        "en": "The gain: Contributions can begin at a workbench. A better mount, a locally available component or a well-executed installation matters as much as code. People offering fabrication or installation can build their own services on these foundations, under the respective licences."
      },
      {
        "de": "So bauen wir weiter: Erfahrungen aus Druck, Montage und Betrieb zurück in die gemeinsamen Quellen bringen. Funkregler, Fernsehen, Funkintegration und mehrere Boote sind weitere Ausbauwege. Jeder kann mit dem Baustein anfangen, der am eigenen Boot einen Unterschied macht.",
        "en": "How we build further: Feed experience from printing, installation and operation back into the shared sources. Wireless controls, television, radio integration and multiple boats are further paths. Anyone can start with the component that makes a difference aboard their boat."
      }
    ]
  }
];

export const projectInsights:Record<string,{gain:Copy;decision:Copy;images:string[];chapter:string}>={
  "system": {
    "gain": {
      "de": "Ein neuer Bedienplatz muss nicht noch einmal GPS, Musik und Sonar bereitstellen. Er kann die vorhandenen Dienste gezielt nutzen.",
      "en": "Another control position does not have to provide GPS, music and sonar all over again. It can use the existing services."
    },
    "decision": {
      "de": "Wir trennen Betriebssystem, Dienste und Bediengeräte. Der eigene Gateway beschreibt Zustände und Fähigkeiten; Wiederherstellungsrezepte sollen den installierten Aufbau nachvollziehbar machen. BBN und AvNav bleiben wichtige Ausgangspunkte.",
      "en": "We separate operating system, services and control devices. Our gateway describes state and capabilities; recovery recipes aim to make the installed setup reproducible. BBN and AvNav remain important starting points."
    },
    "images": [
      "map-routes-native",
      "sonar-physical-b205"
    ],
    "chapter": "integration"
  },
  "helm": {
    "gain": {
      "de": "Instrumente passen sich dem Bedienplatz an: einheitliche Ansichten, Tag und Nacht und ein sichtbarer Unterschied zwischen frischen, fehlenden und alten Daten.",
      "en": "Instruments adapt to the control position: consistent layouts, day and night modes, and a visible distinction between fresh, missing and stale data."
    },
    "decision": {
      "de": "Bootsdaten werden über die bestehenden Standards und den Gateway gelesen. Die native Oberfläche kümmert sich um Darstellung und Bedienung. Die aktuellen Geräteaufnahmen zeigen HELM 206; ihre Boots-Kopfzeile enthält ausdrücklich simulierte beziehungsweise fehlende Werte. Frühere Layout-Prüfbilder stehen gesondert im historischen Abschnitt.",
      "en": "Boat data is read through existing standards and the gateway. The native interface handles presentation and controls. Current device captures show HELM 206; their boat-data header explicitly contains simulated or missing values. Earlier layout fixtures appear separately in the historical section."
    },
    "images": [
      "helm-night",
      "helm-stale-state"
    ],
    "chapter": "integration"
  },
  "audio": {
    "gain": {
      "de": "Die Musik ist nicht an ein einzelnes Display gebunden. Ein Bedienplatz kann wechseln, während Wiedergabe und Klangverarbeitung im eigenständigen Pi-Modul bleiben.",
      "en": "Music is not tied to one display. A control position can change while playback and sound processing remain in the independent Pi module."
    },
    "decision": {
      "de": "Wiedergabe, Equalizer und Kanalzuordnung bekommen eine gemeinsame Bedienlogik. Ein angeforderter Zustand ist erst nach Rückmeldung bestätigt. Die aktuellen Gerätebilder zeigen echte Preset-, Equalizer-, Raum- und Kanaldaten des Pi. Titel-/Wiedergabestatus und Klangsteuerung sind getrennt: Bei der Aufnahme fehlte der Titelstatus, während der DSP lief.",
      "en": "Playback, equalizer and channel routing receive consistent controls. A requested state is only confirmed after a response. Current device captures show real Pi preset, equalizer, room and channel data. Track/playback state and sound controls are separate: track state was missing during capture while the DSP was running."
    },
    "images": [
      "audio-eq-expanded",
      "audio-eq-night",
      "audio-room-layout",
      "audio-zones-setup",
      "audio-presets",
      "audio-night"
    ],
    "chapter": "services"
  },
  "displays": {
    "gain": {
      "de": "Ein günstiges Display wird zu einem gestaltbaren Bedienplatz. Entscheidend sind echte Plattformdaten und passende Profile, nicht das Android-Versprechen auf der Verpackung.",
      "en": "An inexpensive display becomes a control position we can shape. Actual platform properties and suitable profiles matter more than the Android claim on the packaging."
    },
    "decision": {
      "de": "Die untersuchten K706-Luminas laufen mit Android 10/API 29 trotz Android-14-Anzeige. Unterschiedliche OEM-Firmware erhält getrennte Profile. So lässt sich die Integration eines weiteren Displays gezielt übernehmen und anpassen.",
      "en": "The examined K706 Luminas run Android 10/API 29 despite displaying Android 14. Different OEM firmware receives separate profiles. This lets us reuse and adapt the integration for another display deliberately."
    },
    "images": [
      "map-routes-native",
      "map-layers-native",
      "helm-night"
    ],
    "chapter": "devices"
  },
  "maps": {
    "gain": {
      "de": "Karte, Routen und Objektinformationen lassen sich in der eigenen nativen Bedienumgebung verbinden. Die Darstellung nutzt das Gerät lokal; gemeinsame Datenwege bleiben im Netz.",
      "en": "Charts, routes and object information can share our native control environment. Rendering happens locally on the device; shared data paths remain on the network."
    },
    "decision": {
      "de": "Die physischen MAP-110-Versuche vom 26. Oktober gingen deutlich über die frühen Stilbilder hinaus: 96 Zellen, Drehung und Neustart wurden geprüft. Das Laden nach Löschen und Zurückkehren scheiterte weiterhin. Die aktuellen Bilder zeigen MAP 110 nach dem normalen Import des vorhandenen NOAA-Boston-Testpakets am 1. November. Frühere NOAA-Stilbilder sind ausschließlich als Entwicklungsgeschichte eingeordnet.",
      "en": "Physical MAP 110 trials on 26 October went beyond the early style images: 96 cells, rotation and restart were checked. Loading after deleting data and returning still failed. Current images show MAP 110 after normal import of the existing NOAA Boston test package on 1 November. Earlier NOAA style studies are presented solely as development history."
    },
    "images": [
      "map-layers-native",
      "map-library-native",
      "map-cream",
      "map-night-history",
      "map-symbols-history"
    ],
    "chapter": "charts"
  },
  "plotters": {
    "gain": {
      "de": "Eigene Kartenarbeit kann auch für vorhandene Geräte relevant werden. Dafür braucht jeder Zielplotter sein konkretes Format oder eine tatsächlich unterstützte Schnittstelle.",
      "en": "Our chart work can also become useful for existing equipment. Each target plotter needs its specific format or an interface it actually supports."
    },
    "decision": {
      "de": "RX5/Lighthouse und Navionics sind getrennte Konvertierungswege. Vorhandene Writer und Beispiele lösen konkrete Formatbausteine. Weitere Darstellungsdetails, Zielgeräte und passende Eingangsdaten bilden die nächsten Ausbauschritte.",
      "en": "RX5/Lighthouse and Navionics are separate conversion paths. Existing writers and examples solve specific format components. Further portrayal details, target devices and suitable input datasets form the next steps."
    },
    "images": [
      "map-cream",
      "map-graphite"
    ],
    "chapter": "charts"
  },
  "sonar": {
    "gain": {
      "de": "Die Rohsignale des untersuchten günstigen Fishfinders werden zu sichtbaren Echos und weitergebbaren Tiefenwerten. Darstellung und Erfassung sind getrennt weiterentwickelbar.",
      "en": "Raw signals from the inexpensive fishfinder examined become visible echoes and shareable depth values. Display and acquisition can develop independently."
    },
    "decision": {
      "de": "Eigene Pingsteuerung, Pico-Erfassung und Auswertung erschließen den Venterior. Der dokumentierte Versuch erreichte maximal 19,6 brauchbare Messungen/s im Fünf-Sekunden-Fenster. Temperatur ergänzen wir über einen eigenen Mess- und Kalibrierungsweg.",
      "en": "Our ping control, Pico acquisition and processing open up the Venterior. The documented trial reached a maximum of 19.6 usable measurements/s over a five-second window. Temperature is an extension with its own measurement and calibration path."
    },
    "images": [
      "sonar-range",
      "sonar-scope-history",
      "sonar-scope-points-history",
      "sonar-night-history",
      "sonar-board",
      "sonar-perfboard-history",
      "sonar-temperature-research",
      "sonar-wiring"
    ],
    "chapter": "services"
  },
  "electronics": {
    "gain": {
      "de": "Vorhandene Bootstechnik muss nicht komplett ersetzt werden. Ein gezielter Adapter kann einen vorhandenen Geber oder eine Anzeige in die neue Aufgabenteilung einbeziehen.",
      "en": "Existing boat equipment does not all have to be replaced. A specific adapter can bring an existing sensor or gauge into the new architecture."
    },
    "decision": {
      "de": "Beim Yamaha-Trimmsensor gehören Originalgeber, Erfassung und Nachbildung für die vorhandene Anzeige zusammen. Stromversorgung, Schutz, Anschlüsse und Einbau werden gemeinsam gestaltet. Der CAD-Entwurf zeigt die räumliche Anordnung für den nächsten Aufbau.",
      "en": "The Yamaha trim project connects the original sensor, acquisition and emulation for the existing gauge. Power, protection, connections and installation are designed together. The CAD design shows the layout for the next assembly."
    },
    "images": [
      "yamaha-enclosure",
      "sonar-wiring"
    ],
    "chapter": "building-kit"
  },
  "build": {
    "gain": {
      "de": "Aus Quellen werden erst durch passende Gehäuse, Leitungen und Befestigungen nachbaubare Baugruppen. Hier können Menschen mit Werkstatt- und Einbauerfahrung besonders viel beitragen.",
      "en": "Sources become reproducible assemblies through suitable enclosures, wiring and mounting. People with workshop and installation experience can make a particularly valuable contribution here."
    },
    "decision": {
      "de": "Parametrische Konstruktion verbindet Elektronik, Außenanschlüsse, Befestigung und Wartungswege. Die Druckvorbereitung vom 31. Oktober zeigt konkrete Teile. Digitale Geometrieprüfung, tatsächlicher Druck, Passprobe und Betrieb an Bord werden getrennt dokumentiert.",
      "en": "Parametric design connects electronics, external connections, mounting and maintenance paths. Print preparation from 31 October shows specific parts. Digital geometry checks, actual printing, fit checks and operation aboard are documented separately."
    },
    "images": [
      "print-preparation",
      "yamaha-enclosure",
      "enclosure-exploded"
    ],
    "chapter": "building-kit"
  }
};

export const extensionPaths=[
  {
    "n": "01",
    "title": {
      "de": "Einen Sensor ergänzen",
      "en": "Add a sensor"
    },
    "example": {
      "de": "Zum Beispiel einen weiteren Tank- oder Temperaturgeber.",
      "en": "For example, another tank or temperature sensor."
    },
    "body": {
      "de": "Zuerst Messbereich, Versorgung und Anschluss klären. Eine passende Erfassung übersetzt den Wert in einen beschriebenen Datenpfad, etwa in Signal K. Einheit, Zeitstempel und Ausfallzustand gehören dazu. Eine vorhandene Anzeige kann den Wert nutzen, wenn sie diesen Datenpfad unterstützt.",
      "en": "First establish range, power and connections. Suitable acquisition translates the measurement into a described data path, for example in Signal K. Units, timestamps and failure state belong to it. An existing display can use the value if it supports that path."
    },
    "link": "electronics"
  },
  {
    "n": "02",
    "title": {
      "de": "Einen Bedienplatz ergänzen",
      "en": "Add a control position"
    },
    "example": {
      "de": "Ein zweites Display oder ein eigener Lautstärkeregler.",
      "en": "A second display or a dedicated volume control."
    },
    "body": {
      "de": "Ein Client verbindet sich mit den vorhandenen Diensten und liest deren Zustand. Schreibende Befehle brauchen Berechtigung und Bestätigung. Bei einem zusätzlichen Funkknopf kommen Funkhardware, Verbindungsausfall und Energieversorgung hinzu. Die Grundlage ist vorhanden; der konkrete Regler muss noch angebunden und geprüft werden.",
      "en": "A client connects to existing services and reads their state. Commands need permission and acknowledgement. A wireless button additionally needs radio hardware, loss-of-connection handling and power. The foundation exists; the specific control still needs connecting and testing."
    },
    "link": "displays"
  },
  {
    "n": "03",
    "title": {
      "de": "Einen Dienst ergänzen",
      "en": "Add a service"
    },
    "example": {
      "de": "Eine weitere Medienquelle oder eine neue Auswertung.",
      "en": "Another media source or a new analysis service."
    },
    "body": {
      "de": "Der Dienst erhält eine klar beschriebene Aufgabe, Daten und Rückmeldungen. Ein Adapter kann seine Fähigkeiten dem Gateway zugänglich machen. Oberfläche und Dienst entwickeln sich getrennt. Fernsehen, Funkintegration oder mehrere Boote brauchen jeweils eigene Schnittstellen und ein passendes Sicherheits- und Betriebsmodell.",
      "en": "Give the service a clear responsibility, data and responses. An adapter can expose its capabilities through the gateway. Interface and service develop separately. Television, radio integration or multiple boats each need their own interfaces and an appropriate security and operating model."
    },
    "link": "system"
  }
];

export const costRows=[
  [
    {
      "de": "Display",
      "en": "Display"
    },
    {
      "de": "Ein Lumina/K706; 75 € laut eigener Kaufangabe. Zweites Gerät separat rechnen.",
      "en": "One Lumina/K706; €75 according to my own purchase report. Count a second device separately."
    },
    {
      "de": "75 € · Nutzerangabe",
      "en": "€75 · owner report"
    }
  ],
  [
    {
      "de": "Pi und Speicher",
      "en": "Pi and storage"
    },
    {
      "de": "Raspberry Pi 5, 8 GB; Kühlgehäuse und 64-GB-microSD sind im Bestand dokumentiert.",
      "en": "Raspberry Pi 5, 8 GB; cooling enclosure and 64 GB microSD are documented in the inventory."
    },
    {
      "de": "Kaufpreise nicht erfasst",
      "en": "Purchase prices not recorded"
    }
  ],
  [
    {
      "de": "Versorgung",
      "en": "Power"
    },
    {
      "de": "DC-Wandler, Sicherungen, Leitungen und die Versorgung der jeweiligen Geräte.",
      "en": "DC converters, fuses, wiring and power for the chosen devices."
    },
    {
      "de": "Aufbauabhängig · offen",
      "en": "Setup-dependent · open"
    }
  ],
  [
    {
      "de": "Audio",
      "en": "Audio"
    },
    {
      "de": "USB-5.1-Soundkarte dokumentiert; Verstärker, Lautsprecher und Einmessung hängen vom Boot ab.",
      "en": "USB 5.1 sound card documented; amplifier, speakers and acoustic setup depend on the boat."
    },
    {
      "de": "Kein belegter Paketpreis",
      "en": "No evidenced package price"
    }
  ],
  [
    {
      "de": "Sonar und Sensoren",
      "en": "Sonar and sensors"
    },
    {
      "de": "Untersuchter Fishfinder, Pico, Adapter und Sensoren nach gewünschter Ausstattung.",
      "en": "Examined fishfinder, Pico, adapters and sensors for the chosen configuration."
    },
    {
      "de": "Kaufpreise nicht erfasst",
      "en": "Purchase prices not recorded"
    }
  ],
  [
    {
      "de": "Gehäuse und Fertigung",
      "en": "Enclosures and fabrication"
    },
    {
      "de": "Druckmaterial, Schrauben, Platinen, Verbinder sowie Druck- und Lötarbeit getrennt rechnen.",
      "en": "Count print material, screws, boards, connectors, printing and soldering separately."
    },
    {
      "de": "Material + Fertigung offen",
      "en": "Material + fabrication open"
    }
  ],
  [
    {
      "de": "Montage und Versand",
      "en": "Installation and shipping"
    },
    {
      "de": "Einbau, Kabelwege, lokale Beschaffung, Versand und mögliche Importkosten.",
      "en": "Installation, cable routing, local sourcing, shipping and possible import costs."
    },
    {
      "de": "Land- und bootsabhängig",
      "en": "Country- and boat-dependent"
    }
  ]
];

export const text=(lang:Lang,value:Copy)=>value[lang];

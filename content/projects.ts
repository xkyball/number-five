export type Lang = 'de' | 'en';
export type Local = { de: string; en: string };
export const t = (lang: Lang, de: string, en: string) => lang === 'de' ? de : en;
export const local = (de: string, en: string): Local => ({ de, en });
export type Project = {
  slug: string; number: string; title: Local; deck: Local; status: Local; image: string;
  problem: Local; journey: Local[]; results: Local[]; needs: Local[]; open: Local[]; help: Local;
  credits: { name: string; url: string }[]; sourceIds: string[];
};

export const projects: Project[] = [
  {
    "slug": "system",
    "number": "01",
    "title": {
      "de": "Bordsystem & Daten",
      "en": "Boat system & data"
    },
    "deck": {
      "de": "Aus vielen guten Einzelteilen ein Bordsystem machen, das zusammenarbeitet.",
      "en": "Making a boat system out of good individual components."
    },
    "status": {
      "de": "Gemeinsame Dienste · dokumentierte Wiederherstellung",
      "en": "Shared services · documented recovery"
    },
    "image": "dashboard-current",
    "problem": {
      "de": "Am Anfang stand BBN Marine OS: ein Raspberry Pi mit vielen vorhandenen Marine-Diensten. Dazu AvNav, Signal K und der Wunsch, alles an einem übersichtlichen Steuerstand zu bedienen. Die einzelnen Programme waren vorhanden. Ihre Zustände, Bedienung und Anschlüsse zu einem stimmigen Ganzen zu verbinden, wurde zur eigentlichen Aufgabe.",
      "en": "We started with BBN Marine OS: a Raspberry Pi with existing marine services, alongside AvNav and Signal K. The goal was a coherent helm. The individual applications existed; connecting their states, controls and interfaces became the real work."
    },
    "journey": [
      {
        "de": "Marine OS und die vorhandenen Standards lieferten den Einstieg. Statt für jeden Bildschirm direkt mit jedem Dienst zu sprechen, haben wir einen gemeinsamen Gateway als Vermittler aufgebaut.",
        "en": "Marine OS and existing standards gave us a starting point. Rather than have every screen talk directly to every service, we built a shared gateway."
      },
      {
        "de": "Bootsdaten, Musik, Karten und Sonar haben unterschiedliche Zeit- und Ausfallbedingungen. Deshalb bleiben die Dienste getrennt, während die Apps gemeinsame Zugänge und verständliche Zustände verwenden.",
        "en": "Boat data, music, charts and sonar have different timing and failure conditions. The services remain separate, while the apps share connection handling and understandable states."
      },
      {
        "de": "Ein Labor auf dem Mac macht einen Teil der Pi-Software ohne das Boot zugänglich. Gepinnte Quellen und Wiederherstellungsrezepte halten fest, was auf Pi und Labor installiert ist. Die Rezepte werden mit jedem weiteren Aufbau ergänzt.",
        "en": "A Mac-based lab makes part of the Pi software accessible away from the boat. Pinned sources and restore recipes record what is installed on the Pi and in the lab. The recipes grow with each further build."
      }
    ],
    "results": [
      {
        "de": "Ein gemeinsamer Datenweg für HELM, Audio, Sonar und Kartenrouten ist implementiert.",
        "en": "A shared data interface for HELM, audio, sonar and chart routes is implemented."
      },
      {
        "de": "Die Musikwiedergabe arbeitet als eigener Dienst auf dem Pi. Das Display ist die Bedienung, nicht die Musikquelle.",
        "en": "Music playback runs as a separate Pi service. The display controls it; it does not generate the audio."
      },
      {
        "de": "Netzwerkwechsel, Gerätezugänge und Wiederherstellung sind eigene dokumentierte Aufgaben statt versteckter Annahmen.",
        "en": "Network changes, device access and recovery are documented tasks rather than hidden assumptions."
      }
    ],
    "needs": [
      {
        "de": "Raspberry Pi und lokale Netzwerkverbindung",
        "en": "Raspberry Pi and a local network"
      },
      {
        "de": "Signal K und passende Adapter für die vorhandenen Bootsdaten",
        "en": "Signal K and adapters for the boat’s existing data"
      },
      {
        "de": "Linux-Grundkenntnisse und eine getrennte Konfiguration eigener Zugänge",
        "en": "Basic Linux skills and separate configuration of personal credentials"
      }
    ],
    "open": [
      {
        "de": "Den Neuaufbau aus einer leeren Installation vereinfachen und Erfahrungen aus dem gemeinsamen Betrieb an Bord in die Wiederherstellungsrezepte übernehmen.",
        "en": "Simplify rebuilding from a blank installation and feed experience from integrated operation aboard into the recovery recipes."
      },
      {
        "de": "Home Assistant als weitere Systembasis anbinden. Die Trennung zwischen Diensten und Oberflächen gibt dieser Erweiterung einen klaren Ansatzpunkt.",
        "en": "Connect Home Assistant as another system base. Separating services and interfaces gives that extension a clear starting point."
      }
    ],
    "help": {
      "de": "Hilf beim reproduzierbaren Aufbau, bei Netzwerkfehlern und beim Anschluss weiterer Bootsdaten.",
      "en": "Help with reproducible installation, network recovery and additional boat-data interfaces."
    },
    "credits": [
      {
        "name": "BBN Marine OS",
        "url": "https://github.com/bareboat-necessities/lysmarine_gen"
      },
      {
        "name": "Signal K",
        "url": "https://signalk.org/"
      },
      {
        "name": "AvNav",
        "url": "https://www.wellenvogel.net/software/avnav/docs/"
      }
    ],
    "sourceIds": [
      "system",
      "routes"
    ]
  },
  {
    "slug": "helm",
    "number": "02",
    "title": {
      "de": "HELM · der Steuerstand",
      "en": "HELM · the helm display"
    },
    "deck": {
      "de": "Die wichtigen Werte auf einen Blick. Eine Oberfläche, die zum Boot passt.",
      "en": "The important values at a glance. An interface made for the boat."
    },
    "status": {
      "de": "Native Android-App · auf Lumina ausgeliefert",
      "en": "Native Android app · delivered to Lumina"
    },
    "image": "helm-current-day",
    "problem": {
      "de": "Geschwindigkeit, Tiefe und Motorwerte sollen sich ohne Umwege ablesen lassen. Ein Browser voller unabhängiger Werkzeuge ergibt noch keinen gut bedienbaren Steuerstand. Wir wollten eine zusammenhängende Oberfläche mit großen Werten, klarer Hierarchie und einer brauchbaren Nachtansicht.",
      "en": "Speed, depth and engine data should be easy to read. A browser full of independent tools is not yet a usable helm. We wanted a coherent interface with large values, clear hierarchy and a useful night view."
    },
    "journey": [
      {
        "de": "Aus den ersten Layouts wurde eine native Android-App mit getrennten Daten- und Darstellungswegen. Die Anzeige kennt fehlende und veraltete Werte, statt sie stillschweigend durch plausible Zahlen zu ersetzen.",
        "en": "Early layouts became a native Android application with separate data and display paths. It recognises missing and stale values instead of quietly replacing them with plausible numbers."
      },
      {
        "de": "Tag- und Rotnachtansichten, Einheiten, gemeinsame Kopfzeile und die Bedienung auf den Luminas wurden über mehrere Stände zusammengeführt.",
        "en": "Day and red-night views, units, the shared header and Lumina interaction were brought together across several revisions."
      },
      {
        "de": "Die Sonaranzeige ist Teil derselben Bedienwelt. Entwicklungstests verwenden ausdrücklich gekennzeichnete Simulatoren; sie ersetzen keine Bootsdaten.",
        "en": "The sonar view belongs to the same interface. Development uses explicitly identified simulators; these do not stand in for boat measurements."
      }
    ],
    "results": [
      {
        "de": "HELM 206 ist im Quellen- und Auslieferungsstand vom 31. Oktober 2026 auf beiden Luminas dokumentiert.",
        "en": "The source and delivery records dated 31 October 2026 document HELM 206 on both Luminas."
      },
      {
        "de": "Instrumente, Audio und Sonar verwenden eine gemeinsame native Bedienoberfläche.",
        "en": "Instruments, audio and sonar share a native interface."
      },
      {
        "de": "Datenherkunft und Ausfälle bleiben im Datenmodell unterscheidbar.",
        "en": "Data origin and failures remain distinguishable in the data model."
      }
    ],
    "needs": [
      {
        "de": "Android 10 oder neuer; die geprüften Geräte sind die beiden Luminas",
        "en": "Android 10 or later; the tested devices are the two Luminas"
      },
      {
        "de": "Ein kompatibler Gateway und echte Datenquellen für den späteren Einsatz",
        "en": "A compatible gateway and real sources for deployment"
      },
      {
        "de": "Für eigene Änderungen: Kotlin, Android-SDK und die dokumentierte Buildumgebung",
        "en": "For modifications: Kotlin, the Android SDK and documented build tools"
      }
    ],
    "open": [
      {
        "de": "Mit Erfahrungen unter Fahrt, Sonne und Nässe Ablesbarkeit, Bedienung und Einbau weiter verbessern.",
        "en": "Use experience underway, in sunlight and in wet conditions to improve readability, controls and installation."
      },
      {
        "de": "Eine iOS-Oberfläche als weiteren Client entwickeln. Die heutigen Bildschirmoberflächen sind nativ für Android.",
        "en": "Develop an iOS interface as another client. Today’s screen interfaces are native Android."
      }
    ],
    "help": {
      "de": "Gesucht sind Rückmeldungen zur Ablesbarkeit und Bedienung unter echten Bordbedingungen.",
      "en": "We would like feedback on readability and controls under real conditions aboard."
    },
    "credits": [
      {
        "name": "Android / Kotlin",
        "url": "https://developer.android.com/"
      },
      {
        "name": "Signal K",
        "url": "https://signalk.org/"
      }
    ],
    "sourceIds": [
      "helm"
    ]
  },
  {
    "slug": "audio",
    "number": "03",
    "title": {
      "de": "Audio & Spotify",
      "en": "Audio & Spotify"
    },
    "deck": {
      "de": "Die Musik spielt auf dem Pi. Am Steuerstand bleibt die Bedienung einfach.",
      "en": "The Pi plays the music. The helm keeps the controls simple."
    },
    "status": {
      "de": "Pi-Wiedergabe · gemeinsame native Klangsteuerung",
      "en": "Pi playback · shared native sound controls"
    },
    "image": "audio-current-day",
    "problem": {
      "de": "Ein Spotify-Fenster auf einem großen Display war nicht das Ziel. Die Musikanlage sollte unabhängig von der Anzeige laufen und sich mit großen Bedienelementen, Drehknöpfen und gemeinsamem Lautstärkezustand steuern lassen. Dazu kamen mehrere Lautsprecherkanäle und eine vernünftige Klangverarbeitung.",
      "en": "The goal was not simply a Spotify window on a large display. Music should run independently of the screen, with large controls, physical knobs and one consistent volume state. Multiple speaker channels and useful sound processing were part of the task."
    },
    "journey": [
      {
        "de": "Wir haben die Wiedergabe vom Display getrennt: Ein Spotify-Connect-Empfänger liefert Musik an die digitale Klangverarbeitung (DSP) mit CamillaDSP auf dem Pi. Die Android-Oberfläche erhält Zustand und sendet Bedienbefehle über den Gateway.",
        "en": "We separated playback from the display: a Spotify Connect receiver feeds digital sound processing (DSP) through CamillaDSP on the Pi. The Android interface receives state and sends commands through the gateway."
      },
      {
        "de": "Besonders aufwendig waren Zustandsabgleich, Lautstärke, Wiederverbindung und der Übergang zwischen Spotify und eigener Bedienung. Ein einziger gemeinsamer Master verhindert widersprüchliche Lautstärkesteller.",
        "en": "State synchronisation, volume, reconnects and handover between Spotify and our controls required substantial work. One shared master avoids competing volume controls."
      },
      {
        "de": "Klangprogramme, Filter und Kanalaufteilung werden getrennt von der Wiedergabe behandelt. Entwicklungsbilder können Beispielmusik zeigen; sie sind keine Aussage über eine abgeschlossene persönliche Spotify-Bibliotheksintegration.",
        "en": "Sound presets, filters and channel routing are kept separate from playback. Development views may show example tracks; they do not establish a finished personal Spotify-library integration."
      }
    ],
    "results": [
      {
        "de": "Hörbare Spotify-Wiedergabe über Empfänger und CamillaDSP ist im Labor dokumentiert; auf dem Pi ist der Migrations- und Stabilitätsstand separat festgehalten.",
        "en": "Audible Spotify playback through the receiver and CamillaDSP is documented in the lab; Pi migration and stability have separate records."
      },
      {
        "de": "Ein DSP-Bus für Front, Rear und Subwoofer ist umgesetzt. Virtueller Kanalnachweis und tatsächliche Lautsprecherinstallation bleiben getrennt.",
        "en": "A DSP bus for front, rear and subwoofer channels is implemented. Virtual channel checks and actual speaker installation remain separate."
      },
      {
        "de": "Am 20. Oktober wurden Klangprofile am physischen Pi über HELM gespeichert und wiedergewählt; der Katalog blieb nach einem Gateway-Neustart erhalten.",
        "en": "On 20 October, sound presets were saved and reselected through HELM on the physical Pi; the catalog survived a gateway restart."
      }
    ],
    "needs": [
      {
        "de": "Pi mit geeignetem Audioausgang, Endstufe und Lautsprechern",
        "en": "Pi with a suitable audio output, amplifier and speakers"
      },
      {
        "de": "Spotify-Zugang entsprechend den Bedingungen der verwendeten Dienste",
        "en": "Spotify access subject to the terms of the services in use"
      },
      {
        "de": "CamillaDSP und eine zum eigenen Aufbau passende Kanalbelegung",
        "en": "CamillaDSP and channel routing matched to your installation"
      }
    ],
    "open": [
      {
        "de": "Bibliothek, Suche und weitere Medienquellen über dieselbe native Bedienung erschließen. Beim dokumentierten Aufnahmestand war die Klangsteuerung verbunden; der Titelstatus fehlte.",
        "en": "Bring library, search and additional media sources into the same native controls. In the recorded revision, sound controls were connected while track state was unavailable."
      },
      {
        "de": "Klangprofile mit den Lautsprechern und der Versorgung des jeweiligen Bootes abstimmen und Erfahrungen aus dem Betrieb zurückspielen.",
        "en": "Tune sound profiles for each boat’s speakers and power supply and feed operating experience back into the system."
      }
    ],
    "help": {
      "de": "Hilf bei praxistauglicher Audiohardware, zuverlässiger Wiedergabe und der Einmessung an Bord.",
      "en": "Help with practical audio hardware, reliable playback and calibration aboard."
    },
    "credits": [
      {
        "name": "CamillaDSP",
        "url": "https://github.com/HEnquist/camilladsp"
      },
      {
        "name": "librespot",
        "url": "https://github.com/librespot-org/librespot"
      }
    ],
    "sourceIds": [
      "audio"
    ]
  },
  {
    "slug": "displays",
    "number": "04",
    "title": {
      "de": "Displays & Bedienung",
      "en": "Displays & controls"
    },
    "deck": {
      "de": "Groß, bezahlbar und bedienbar: Was wir aus Android-Displays herausholen.",
      "en": "Large, affordable and usable: what we can get out of Android displays."
    },
    "status": {
      "de": "Zwei Luminas · gerätespezifische Integration",
      "en": "Two Luminas · device-specific integration"
    },
    "image": "launcher-current",
    "problem": {
      "de": "Ein großes, helles und bezahlbares Display klingt nach einer einfachen Einkaufsliste. Im Boot werden daraus Fragen nach Helligkeit, Stromversorgung, Anschlüssen, mehreren Anzeigen und Bedienung mit echten Tasten. Dazu kamen Herstellergrenzen und wenig Unterstützung bei den benötigten Anpassungen.",
      "en": "A large, bright and affordable display sounds like a shopping list. On a boat it becomes a question of brightness, power, connections, multiple screens and physical controls. Device restrictions and limited manufacturer support added to the work."
    },
    "journey": [
      {
        "de": "Die Luminas wurden geöffnet, ihre Tasten- und Drehknopfwege untersucht und die Android-Oberfläche schrittweise angepasst.",
        "en": "We opened the Luminas, investigated their buttons and rotary controls, and adapted the Android interface in stages."
      },
      {
        "de": "Launcher, gemeinsame Kopfzeile, Fensterfokus und Bildschirmflächen mussten zusammenpassen. Ein Geräteknopf darf nicht bei jedem Appwechsel eine andere, unklare Wirkung haben.",
        "en": "The launcher, shared header, window focus and usable screen area had to work together. A physical knob should not acquire an unclear new meaning after each app switch."
      },
      {
        "de": "Mehrere Anzeigen sollen denselben bestätigten Dienstzustand zeigen. Die dafür entwickelten Zeit- und Zustandsmodelle sind nicht gleichbedeutend mit einer vollständigen Messung der Synchronität aller Anzeigen.",
        "en": "Multiple displays should show the same confirmed service state. The timing and state models we built are not equivalent to a complete measurement of synchronisation across every display."
      }
    ],
    "results": [
      {
        "de": "App-Auslieferungen und gezielte Bedienprüfungen sind für zwei reale Luminas dokumentiert.",
        "en": "App deliveries and targeted interaction checks are documented for two physical Luminas."
      },
      {
        "de": "Gerätespezifische Werkzeuge für Kopfzeile, Fokus, Fenster und Diagnose sind als eigener Quellbestand erhalten.",
        "en": "Device-specific header, focus, window and diagnostic tools are preserved as a separate source collection."
      },
      {
        "de": "Hardwarefotos und Entwicklungsaufnahmen zeigen den tatsächlichen Erkundungsweg.",
        "en": "Hardware photographs and development captures show the actual investigation."
      }
    ],
    "needs": [
      {
        "de": "Ein konkret unterstütztes Android-Gerät; gerätespezifische Eingriffe sind nicht allgemein übertragbar",
        "en": "A specifically supported Android device; device-level modifications are not universally transferable"
      },
      {
        "de": "Passende 12-V-Versorgung, Befestigung und Schutz am Einbauort",
        "en": "Suitable 12 V power, mounting and protection at the installation site"
      },
      {
        "de": "Für tiefe Anpassungen: Android-/Elektronikkenntnisse",
        "en": "For low-level modifications: Android and electronics skills"
      }
    ],
    "open": [
      {
        "de": "Displayposition, Sonnenschutz, Wärmeabfuhr und Schutz gegen Feuchtigkeit auf das jeweilige Boot abstimmen.",
        "en": "Match display position, shade, cooling and moisture protection to the boat."
      },
      {
        "de": "Weitere Displayprofile und physische Regler ergänzen; Reaktionszeiten am jeweiligen Gerät erfassen und gezielt verbessern.",
        "en": "Add more display profiles and physical controls; record response times on each device and improve them deliberately."
      }
    ],
    "help": {
      "de": "Teile Erfahrungen mit anderen Displays, Lieferanten, Helligkeit und robusten Bedienelementen.",
      "en": "Share experience with other displays, suppliers, brightness and robust physical controls."
    },
    "credits": [
      {
        "name": "Android Open Source Project",
        "url": "https://source.android.com/"
      }
    ],
    "sourceIds": [
      "displays"
    ]
  },
  {
    "slug": "maps",
    "number": "05",
    "title": {
      "de": "Karten & Routen",
      "en": "Charts & routes"
    },
    "deck": {
      "de": "Eine eigene Kartenoberfläche auf bewährten Grundlagen. Mit Routen, die zum System gehören.",
      "en": "Our own chart interface on established foundations. With routes that belong to the system."
    },
    "status": {
      "de": "Native Karten · Routenentwürfe und Arbeitsseiten",
      "en": "Native charts · route drafts and workspaces"
    },
    "image": "map-current-overview",
    "problem": {
      "de": "Wir wollten Karten direkt in der eigenen Android-Bedienwelt darstellen: flüssig verschieben und drehen, Objekte abfragen, Ebenen wählen und Routen bearbeiten. Eine eingebettete Fremdoberfläche gab uns dafür nicht die gewünschte Kontrolle.",
      "en": "We wanted charts inside our own Android interface: pan and rotate, inspect objects, select layers and edit routes. An embedded external interface did not provide the control we wanted."
    },
    "journey": [
      {
        "de": "Nach den ersten Integrationsversuchen wurde der Kartenweg eigenständig aufgebaut. Der Renderer verwendet portierte OpenCPN-/S52-Grundlagen und ist keine vollständig neu erfundene Seekartenbibliothek.",
        "en": "After the first integration attempts, we built a separate chart path. The renderer uses ported OpenCPN/S52 foundations; it is not a chart library invented from scratch."
      },
      {
        "de": "GPU-Darstellung, Touchbedienung, Speicherbedarf und zusammenhängende Kartenausschnitte wurden getrennt untersucht. Tests mit echten Geräten waren wichtig, weil ein schneller Hosttest noch keine schnelle Lumina ist.",
        "en": "GPU rendering, touch interaction, memory use and continuous coverage were investigated separately. Physical-device tests mattered because a fast host test does not establish a fast Lumina."
      },
      {
        "de": "Für Routen verwenden wir Signal K statt eines neuen isolierten Datenformats. Lokale Entwürfe ermöglichen Bearbeitung ohne Verbindung. Der Gateway vermittelt die Standardschnittstelle.",
        "en": "Routes use Signal K rather than a new isolated format. Local drafts support editing while disconnected. The gateway mediates the standard interface."
      }
    ],
    "results": [
      {
        "de": "MAP 110 ist für beide Luminas dokumentiert. Im geprüften Paket wurden 96 Karten- und Hintergrundzellen gleichzeitig gehalten; gezielte Drehprüfungen bestanden.",
        "en": "MAP 110 is documented on both Luminas. The tested package retained 96 chart and background cells concurrently; targeted rotation checks passed."
      },
      {
        "de": "Kartenstile, Ebenen und Routenbedienung sind implementiert. Die aktuelle Objektabfrage meldete NO_CONFIRMED_PORTRAYAL statt Objektattributen; diese konkrete Grenze ist im Gerätebild sichtbar.",
        "en": "Chart styles, layers and route interaction are implemented. The current object query returned NO_CONFIRMED_PORTRAYAL rather than object attributes; the device capture shows this specific limit."
      },
      {
        "de": "Die Routenintegration besitzt einen Standardweg über Signal K und lokale Entwürfe.",
        "en": "Route integration has a standard path through Signal K and local drafts."
      }
    ],
    "needs": [
      {
        "de": "Android-Gerät mit passender Grafikunterstützung",
        "en": "Android device with suitable graphics support"
      },
      {
        "de": "Kartendaten, deren Nutzungsrechte den jeweiligen Einsatz erlauben",
        "en": "Chart data licensed for the intended use"
      },
      {
        "de": "Für gemeinsame Routen: Gateway und erreichbarer Signal-K-Ressourcenanbieter",
        "en": "For shared routes: gateway and a reachable Signal K resources provider"
      }
    ],
    "open": [
      {
        "de": "Den Ladeweg nach der Paketverwaltung verbessern: Der Versuch vom 26. Oktober meldete dort einen Fehler. Objektabfrage und nautische Darstellung anhand konkreter Kartenausschnitte weiterentwickeln.",
        "en": "Improve loading after package management: the 26 October trial recorded an error there. Extend object queries and nautical portrayal using specific chart areas."
      },
      {
        "de": "Das gemeinsame Speichern und Löschen von Routen über den Ressourcenanbieter weiter anbinden. Ein früherer Aufruf lieferte HTTP 503; die aktuelle Aufnahme zeigt eine leere Routenliste.",
        "en": "Continue integrating shared route save and delete with the resources provider. An earlier call returned HTTP 503; the current capture shows an empty route list."
      }
    ],
    "help": {
      "de": "Gesucht sind reproduzierbare Kartentests, offene Kartendaten und Hilfe beim zuverlässigen gemeinsamen Routenbestand.",
      "en": "We welcome reproducible chart tests, open chart data and help with reliable shared route storage."
    },
    "credits": [
      {
        "name": "OpenCPN",
        "url": "https://opencpn.org/"
      },
      {
        "name": "Signal K",
        "url": "https://signalk.org/"
      },
      {
        "name": "NOAA ENC",
        "url": "https://nauticalcharts.noaa.gov/charts/noaa-enc.html"
      }
    ],
    "sourceIds": [
      "maps",
      "routes"
    ]
  },
  {
    "slug": "plotters",
    "number": "06",
    "title": {
      "de": "Karten für vorhandene Plotter",
      "en": "Charts for existing plotters"
    },
    "deck": {
      "de": "Eigene Vektordaten in andere Kartenformate überführen. Der Weg ist geöffnet, die Arbeit geht weiter.",
      "en": "Converting our own vector data into other chart formats. A path is open; the work continues."
    },
    "status": {
      "de": "Kartenkonvertierung · Decoder und Schreibbausteine",
      "en": "Chart conversion · decoders and writer components"
    },
    "image": "map-current-detail",
    "problem": {
      "de": "Muss eine eigene Kartenquelle auf die eigene App beschränkt bleiben? Wir haben untersucht, ob sich zulässig verwendbare Vektordaten auch für vorhandene Plotter und deren Kartenwege aufbereiten lassen.",
      "en": "Must a chart source be limited to our own app? We investigated whether legally usable vector data can also be prepared for existing plotters and their chart paths."
    },
    "journey": [
      {
        "de": "Ein eigenes Zwischenformat hält Geometrie, Attribute und Herkunft zusammen. Von dort werden die unterstützten Zielstrukturen erzeugt und unabhängig zurückgelesen.",
        "en": "Our intermediate representation keeps geometry, attributes and provenance together. Supported target structures are generated from it and independently read back."
      },
      {
        "de": "Für Lighthouse/RX5 entstanden Konvertierungs-, Ausschnitt- und Prüfwerkzeuge. Für Navionics wurden eigene kleine Container, Geometrien und später umfangreichere Adapter untersucht.",
        "en": "For Lighthouse/RX5 we developed conversion, regional export and inspection tools. For Navionics we investigated self-authored small containers, geometries and later broader adapters."
      },
      {
        "de": "Jeder Schritt trennt erzeugte Datei, Annahme durch einen Leser, tatsächliche Darstellung und Einsatz auf einem Plotter. Diese Nachweise sind verschieden und werden nicht gegeneinander ausgetauscht.",
        "en": "Each step distinguishes file generation, reader acceptance, actual portrayal and use on a plotter. Those are different kinds of evidence and are not interchangeable."
      }
    ],
    "results": [
      {
        "de": "Die Konvertierung besitzt eigene Quellwerkzeuge, eine dokumentierte Zwischenrepräsentation und Prüfungen unterstützter Zielstrukturen.",
        "en": "The conversion work has its own source tools, a documented intermediate representation and checks for supported target structures."
      },
      {
        "de": "Für bestimmte selbst erzeugte Navionics-Testcontainer sind echte Leserannahme und Geometrie-Rücklesen dokumentiert.",
        "en": "Genuine reader admission and geometry readback are documented for specific self-authored Navionics test containers."
      },
      {
        "de": "Das ist ein technischer Ausgangspunkt für weitere Kartenadapter, mit eigenen Decodern und Schreibwegen.",
        "en": "These decoders and writer components provide a technical foundation for further adapters."
      }
    ],
    "needs": [
      {
        "de": "Eigene oder passend lizenzierte Vektordaten und deren Herkunftsnachweis",
        "en": "Own or appropriately licensed vector data with provenance"
      },
      {
        "de": "Python-Toolchain und genaue Kenntnis der unterstützten Objekt- und Darstellungswege",
        "en": "Python tools and a clear understanding of supported feature and portrayal paths"
      },
      {
        "de": "Gesonderte, rechtmäßig zugängliche Zielumgebung für Geräteprüfungen",
        "en": "A separate, lawfully accessible target environment for device testing"
      }
    ],
    "open": [
      {
        "de": "Die vorhandenen Lighthouse/RX5- und Navionics-Bausteine um weitere Formatdetails ergänzen und mit den jeweiligen Zielgeräten zusammenführen. So wächst aus gelösten Konvertierungsschritten der passende Weg für einen konkreten Plotter.",
        "en": "Extend the existing Lighthouse/RX5 and Navionics components with further format details and connect them to the relevant target devices. Solved conversion steps can then grow into a suitable workflow for a specific plotter."
      },
      {
        "de": "Datenrechte, Zielsoftwarebedingungen und technische Voraussetzungen gehören zum jeweiligen Anwendungsfall. Fremde Karten, Schlüssel oder proprietäre Bibliotheken werden nicht als eigene Quellen verteilt.",
        "en": "Data rights, target-software terms and technical prerequisites belong to each use case. Third-party charts, keys and proprietary libraries are not distributed as our own sources."
      }
    ],
    "help": {
      "de": "Hilf mit offenen Testdaten, nachvollziehbaren Formatbeschreibungen und konkreten Geräteversuchen.",
      "en": "Help with open test data, reproducible format descriptions and specific device experiments."
    },
    "credits": [
      {
        "name": "OpenCPN",
        "url": "https://opencpn.org/"
      },
      {
        "name": "IHO Standards",
        "url": "https://iho.int/en/standards-and-specifications"
      }
    ],
    "sourceIds": [
      "plotters"
    ]
  },
  {
    "slug": "sonar",
    "number": "07",
    "title": {
      "de": "Unser eigenes Sonar",
      "en": "Our own sonar"
    },
    "deck": {
      "de": "Vom günstigen Handgerät zur eigenen Echoerfassung und Anzeige.",
      "en": "From an inexpensive handheld device to our own echo capture and display."
    },
    "status": {
      "de": "Venterior und Pico · reale Echos in HELM",
      "en": "Venterior and Pico · real echoes in HELM"
    },
    "image": "sonar-current-scope",
    "problem": {
      "de": "Das untersuchte Venterior-Handgerät konnte Tiefe anzeigen. Wir wollten an die Echos selbst: häufiger messen, Rohdaten behalten und sie in einer eigenen Oberfläche darstellen. Dafür mussten Elektronik, Zeitbezug und Auswertung gemeinsam funktionieren.",
      "en": "The Venterior handheld could display depth. We wanted the echoes themselves: more frequent measurements, retained raw data and our own display. Electronics, timing and processing had to work together."
    },
    "journey": [
      {
        "de": "Ein Pico erfasst Signale aus dem vorhandenen Gerät. Eigene Sendungen, physische Marker und Rohdaten werden einander zugeordnet. Der Sonard-Dienst verarbeitet die Messungen und gibt sie über den Gateway an die Anzeige weiter.",
        "en": "A Pico captures signals from the existing device. Own transmissions, physical markers and raw samples are correlated. Sonard processes the measurements and passes them through the gateway to the display."
      },
      {
        "de": "Die nutzbare Pingrate hängt von der Tiefe und dem nötigen Empfangsfenster ab. Wir haben die Rate schrittweise erhöht, statt nur die Bildschirmwiederholung schneller zu stellen.",
        "en": "Usable ping rate depends on depth and the required receive window. We raised the acquisition rate in stages, rather than merely increasing display refresh."
      },
      {
        "de": "Die Rohdaten und fehlgeschlagenen Versuche bleiben Teil der Entwicklung. Filter, Tiefenverfolgung und Anzeige wurden unabhängig voneinander weiterentwickelt.",
        "en": "Raw data and failed trials remain part of the development record. Filtering, depth tracking and display were developed separately."
      }
    ],
    "results": [
      {
        "de": "Der dokumentierte 600-Sekunden-Versuch vom 16. Oktober 2026 enthält 8.807 eigene Starts mit physischer Marker- und Rohdatenzuordnung.",
        "en": "The documented 600-second trial on 16 October 2026 contains 8,807 own starts with physical marker and raw-data correlation."
      },
      {
        "de": "Im gleitenden Fünfsekundenfenster wurden 6,4 bis 19,6 brauchbare Messungen pro Sekunde gemessen; das Laufmittel betrug 14,17. Das ist keine feste 20-Hz-Zusage.",
        "en": "Rolling five-second windows measured 6.4 to 19.6 usable measurements per second; the run average was 14.17. This is not a constant 20 Hz promise."
      },
      {
        "de": "HELM zeigte während dieses Versuchs die echte erfasste Historie. Die spätere Tiefen- und Darstellungsintegration ist separat dokumentiert.",
        "en": "HELM displayed the genuinely acquired history during that trial. Later depth and display integration has separate records."
      }
    ],
    "needs": [
      {
        "de": "Das konkret untersuchte Venterior-Modell, Pico und passende Erfassungselektronik",
        "en": "The specifically investigated Venterior model, Pico and suitable acquisition electronics"
      },
      {
        "de": "Lötwerkzeug, Messmittel und die zum Aufbau gehörenden Anschlussdaten",
        "en": "Soldering tools, measurement equipment and the wiring data for the build"
      },
      {
        "de": "Pi, Sonard und passende Sonar-/Gateway-Versionen",
        "en": "Pi, Sonard and matching sonar/gateway versions"
      }
    ],
    "open": [
      {
        "de": "Die Tiefenkalibrierung am konkreten Aufbau verfeinern. Im dokumentierten Vergleich zeigte das LCD 1,1 m; die gesonderte empirische Referenz betrug 1,25175 m.",
        "en": "Refine depth calibration for the actual setup. In the documented comparison the LCD read 1.1 m; the separate empirical reference was 1.25175 m."
      },
      {
        "de": "Temperatur, Bordversorgung und akustischen Einbau ergänzen. Langzeitprotokolle enthalten auch fehlgeschlagene Kriterien und liefern konkrete Ansatzpunkte für die nächsten Verbesserungen.",
        "en": "Add temperature, boat power and acoustic installation. Endurance logs also contain failed criteria and provide concrete starting points for the next improvements."
      }
    ],
    "help": {
      "de": "Wir suchen Hilfe bei störungsarmer Elektronik, reproduzierbaren Messungen und einem robusten Einbau ins Boot.",
      "en": "We welcome help with low-noise electronics, reproducible measurements and robust installation aboard."
    },
    "credits": [
      {
        "name": "Raspberry Pi Pico SDK",
        "url": "https://github.com/raspberrypi/pico-sdk"
      },
      {
        "name": "Signal K",
        "url": "https://signalk.org/"
      }
    ],
    "sourceIds": [
      "sonar"
    ]
  },
  {
    "slug": "electronics",
    "number": "08",
    "title": {
      "de": "Sensoren & Elektronik",
      "en": "Sensors & electronics"
    },
    "deck": {
      "de": "Vorhandene Bootstechnik anbinden, statt jedes Gerät auszutauschen.",
      "en": "Connecting existing boat equipment instead of replacing everything."
    },
    "status": {
      "de": "Sensoradapter · Schaltungen und Aufbauquellen",
      "en": "Sensor adapters · circuit and assembly sources"
    },
    "image": "helm-current-day",
    "problem": {
      "de": "Ein neuer Bildschirm löst nicht automatisch die Schnittstellenprobleme alter Sensoren. Beim Yamaha-Trimmsignal liest ein Arduino den Originalgeber; die Adapterelektronik muss das passende Signal für die vorhandene Yamaha-Anzeige nachbilden. Für Sonar und Pi kommt eine verlässliche Versorgung hinzu.",
      "en": "A new screen does not automatically solve the interfaces of older sensors. An Arduino reads the original Yamaha trim sender; the adapter electronics must reproduce the signal expected by the existing Yamaha gauge. Sonar and Pi also need reliable power."
    },
    "journey": [
      {
        "de": "Signalwege werden vom tatsächlichen Anschluss bis zum Ziel beschrieben. Logische Schaltung, räumlicher Aufbau und Kabeldaten erfüllen unterschiedliche Aufgaben und verwenden denselben Anschlussstand.",
        "en": "Signal paths are described from the actual connector to their destination. Circuit diagrams, physical layouts and wire data serve different purposes while sharing the same connections."
      },
      {
        "de": "Die vorhandenen Teile und ihre konkreten Anschlussvarianten bestimmen den Aufbau. Ein gezeichnetes Kaufmodul ersetzt weder sein Datenblatt noch eine reale Messung.",
        "en": "Actual available parts and their connector variants determine the build. A drawn module does not replace its data sheet or a real measurement."
      },
      {
        "de": "Digitale Prüfungen und gegebenenfalls Simulationen untersuchen bestimmte Teilnetze. Ein bestandener Softwaretest ist noch kein elektrischer Funktionstest des fertigen Aufbaus.",
        "en": "Digital checks and, where suitable, simulations examine particular subnetworks. A passing software test is not yet an electrical functional test of the physical assembly."
      }
    ],
    "results": [
      {
        "de": "Für den Yamaha-Trimmsensor liegen Schaltungs-, Teile- und Gehäusequellen vor.",
        "en": "Circuit, part and enclosure sources exist for the Yamaha trim adapter."
      },
      {
        "de": "Für den Sonaraufbau sind Anschluss- und Versorgungsentwürfe mit räumlichen Verdrahtungsdaten erhalten.",
        "en": "The sonar build has connection and power designs with physical wiring data."
      },
      {
        "de": "Unbestätigte Werte und echte elektrische Prüflücken werden im jeweiligen Stand benannt.",
        "en": "Unconfirmed values and real electrical test gaps are identified in each revision."
      }
    ],
    "needs": [
      {
        "de": "Die exakte Sensor-/Anzeigevariante und bestätigte Anschlussbelegung",
        "en": "The exact sender/gauge variant and confirmed pinout"
      },
      {
        "de": "Geeignete vorhandene Bauteile, Lötwerkzeug und Messmittel",
        "en": "Suitable available components, soldering tools and measurement equipment"
      },
      {
        "de": "Ein abgesicherter Prüfaufbau vor dem Anschluss an das Boot",
        "en": "A protected test setup before connection to the boat"
      }
    ],
    "open": [
      {
        "de": "Analoge Funktionsmessungen, Versorgungsspitzen und die tatsächlichen Pin- und Artikelmaße in die jeweiligen Baugruppen zurückführen.",
        "en": "Feed analogue measurements, supply transients and actual pin and part dimensions back into the individual assemblies."
      },
      {
        "de": "Für weitere Geber und Anzeigen passende Adapter entwickeln und Anschlussdaten sowie Betriebserfahrungen gemeinsam dokumentieren.",
        "en": "Develop adapters for additional sensors and gauges and document connections and operating experience together."
      }
    ],
    "help": {
      "de": "Hilf mit Messungen, klaren Anschlussplänen und Bauteilalternativen, die auch lokal erhältlich sind.",
      "en": "Help with measurements, clear wiring plans and component alternatives that can be bought locally."
    },
    "credits": [
      {
        "name": "KiCad",
        "url": "https://www.kicad.org/"
      },
      {
        "name": "ngspice",
        "url": "https://ngspice.sourceforge.io/"
      }
    ],
    "sourceIds": [
      "hardware"
    ]
  },
  {
    "slug": "build",
    "number": "09",
    "title": {
      "de": "Gehäuse & Einbau",
      "en": "Enclosures & installation"
    },
    "deck": {
      "de": "Der schwierige Teil ist oft das Anfassen: drucken, löten, verkabeln und montieren.",
      "en": "Often the hard part is physical: printing, soldering, wiring and mounting."
    },
    "status": {
      "de": "Parametrische Gehäuse · digitale Baupakete",
      "en": "Parametric enclosures · digital build packages"
    },
    "image": "print-preparation",
    "problem": {
      "de": "Software kann man schnell teilen. Eine Platine muss trotzdem ins Gehäuse passen, ein Kabel durch die Wand und eine Schraube mit dem Werkzeug erreichbar sein. Genau hier entscheidet sich, ob andere das Projekt tatsächlich nachbauen können.",
      "en": "Software is easy to share. A circuit still has to fit its enclosure, a cable has to pass through a wall and a screw must be reachable. This is where a project becomes genuinely reproducible for other builders."
    },
    "journey": [
      {
        "de": "Die Konstruktion umfasst ganze Baugruppen: Kaufteile, Halterungen, Deckel, Außenbefestigung und innere Leitungen. Drucklage und spätere Einbaulage werden unterschieden.",
        "en": "Design covers complete assemblies: bought-in parts, supports, lids, external mounting and internal wires. Print orientation is distinguished from installation orientation."
      },
      {
        "de": "Der CAD-Weg hat sich über das Projekt verändert, von früheren SCAD-/Fusion-Arbeiten zu aktuellen direkt erzeugten build123d-/OCCT-Baupaketen. Historische und heutige Quellenstände werden nicht vermischt.",
        "en": "The CAD approach evolved from earlier SCAD/Fusion work to current directly generated build123d/OCCT packages. Historical and current source revisions are not conflated."
      },
      {
        "de": "Pi-Box, Venterior-Aufbau und Trimmsensor benötigen unterschiedliche Lösungen. Fehlgeschlagene Drucke und Montageprüfungen liefern konkrete Änderungen, statt nur neue schöne Renderbilder.",
        "en": "The Pi box, Venterior assembly and trim sensor need different solutions. Failed prints and assembly checks drive actual changes rather than merely new attractive renders."
      }
    ],
    "results": [
      {
        "de": "Versionierte Konstruktionen, druckbare Einzelteile und Verdrahtungsunterlagen sind für mehrere Baugruppen vorhanden.",
        "en": "Versioned designs, individual print parts and wiring records exist for multiple assemblies."
      },
      {
        "de": "Der aktuelle Venterior-Stand enthält geschlossene Druckkörper und vollständige SVG-Verdrahtung; reale Betriebsprüfungen bleiben separat.",
        "en": "The current Venterior revision includes closed print bodies and complete SVG wiring; physical operating checks remain separate."
      },
      {
        "de": "Die Pi-Box hat digitale Geometrieprüfungen und dokumentierte reale Druckprobleme. Beides gehört zum veröffentlichten Entwicklungsstand.",
        "en": "The Pi box has digital geometry checks and documented physical print problems. Both belong to the published development state."
      }
    ],
    "needs": [
      {
        "de": "Passender 3D-Drucker oder lokaler Fertigungsdienst",
        "en": "Suitable 3D printer or local fabrication service"
      },
      {
        "de": "Tatsächliche Kaufteile mit überprüften Maßen",
        "en": "Actual components with verified dimensions"
      },
      {
        "de": "Löt- und Montagewerkzeug, Kabel, Befestigung und Zeit für Passprüfungen",
        "en": "Soldering and assembly tools, wires, mounting hardware and time for fit checks"
      }
    ],
    "open": [
      {
        "de": "Druck, Passprobe und Einbau am gewählten Ort nutzen, um Halter, Wärmeabfuhr und Schutz gegen Feuchtigkeit zu verbessern.",
        "en": "Use printing, fit checks and installation at the chosen location to improve mounts, cooling and moisture protection."
      },
      {
        "de": "Aus digitalen Quellen unterschiedliche Fertigungswege entwickeln: selbst drucken und montieren, Bauteile lokal fertigen lassen oder komplette Baugruppen und Einbau anbieten.",
        "en": "Develop different fabrication routes from the digital sources: print and assemble yourself, commission local parts or offer complete assemblies and installation."
      }
    ],
    "help": {
      "de": "Gesucht sind Mitbauer, Druck- und Lötservices sowie Lieferanten für bezahlbare, lokal verfügbare Teile – besonders auch in Südafrika.",
      "en": "We are looking for builders, printing and soldering services, and suppliers of affordable local parts—including in South Africa."
    },
    "credits": [
      {
        "name": "build123d",
        "url": "https://build123d.readthedocs.io/"
      },
      {
        "name": "Open CASCADE",
        "url": "https://dev.opencascade.org/"
      },
      {
        "name": "KiCad",
        "url": "https://www.kicad.org/"
      }
    ],
    "sourceIds": [
      "hardware"
    ]
  }
];

export const research = {
  title: local('Motorgeräusche aktiv reduzieren', 'Actively reducing engine noise'),
  text: local('Ein weiterer Forschungszweig untersucht Gegenschall für tiefe Motorordnungen an einem Sitzplatz. Hardware und Rechenbudget sind geplant; Firmware, tatsächliche Laufzeit und akustische Wirkung sind noch zu messen. Der Ansatz konzentriert sich auf einen Sitzplatz und tiefe Motorordnungen.', 'A further research branch investigates anti-noise for low engine orders at one seating position. Hardware and computation budgets are planned; firmware, actual latency and acoustic effect still need measurement. The approach focuses on one seating position and low engine orders.'),
};

export const milestones = [
  { date: local('Der Ausgangspunkt', 'The starting point'), title: local('Vorhandene Marine-Software verbinden', 'Connecting existing marine software'), text: local('BBN Marine OS, AvNav und Signal K bilden den Einstieg. Der Wunsch nach einer gemeinsamen Bedienung bringt die Integrationsgrenzen ans Licht.', 'BBN Marine OS, AvNav and Signal K provide the starting point. The wish for one coherent interface exposes integration gaps.') },
  { date: local('8. Oktober 2026', '8 October 2026'), title: local('Ein Labor neben dem Boot', 'A lab away from the boat'), text: local('Das Marine-OS-Labor dokumentiert Gateway, DSP und hörbare Spotify-Wiedergabe. Hardware und tatsächliche Bordbedingungen bleiben ein eigener Prüfbereich.', 'The Marine OS lab documents the gateway, DSP and audible Spotify playback. Hardware and conditions aboard remain a separate test domain.') },
  { date: local('11.–12. Oktober 2026', '11–12 October 2026'), title: local('Die Geschichte wird größer', 'The story grows'), text: local('Die erste Community-Vorbereitung wird zum längeren Bericht mit Hardwarefotos und Bedienbeispielen. Daraus entsteht die Idee einer eigenen, fortschreibbaren Projektseite.', 'The first community drafts grow into a longer report with hardware photos and interaction examples. This becomes the basis for an evolving project website.') },
  { date: local('16. Oktober 2026', '16 October 2026'), title: local('Eigene Echos, gemessene Pingrate', 'Own echoes, measured ping rate'), text: local('Ein 600-Sekunden-Versuch ordnet eigene Starts, physische Marker und Rohdaten zu. Die höchste brauchbare Rate im Fünfsekundenfenster beträgt 19,6 Hz.', 'A 600-second trial correlates own starts, physical markers and raw data. Peak usable rate in a five-second window is 19.6 Hz.') },
  { date: local('25.–26. Oktober 2026', '25–26 October 2026'), title: local('Karten auf zwei Geräten', 'Charts on two devices'), text: local('Kartenbedienung und Speicherverhalten werden auf beiden Luminas geprüft. Offene Punkte bei Routenanbieter und Laderückkehr werden im Stand festgehalten.', 'Chart interaction and memory behaviour are checked on both Luminas. Route-provider and loading-return issues remain recorded.') },
  { date: local('31. Oktober – 1. November 2026', '31 October–1 November 2026'), title: local('Vom Bildschirm zur Werkbank', 'From screen to workbench'), text: local('Neue HELM-Stände und überarbeitete Gehäuse sind dokumentiert. Jetzt zählen Passung, Verdrahtung und der tatsächliche Einbau. Die Teilprojekte laufen dabei weiterhin parallel.', 'New HELM revisions and revised enclosures are documented. Fit, wiring and real installation matter next. The individual projects continue in parallel.') },
];

export const sourceLinks = [
  { id: 'system', title: 'Marine OS lab & recovery', path: 'docs/evidence/system.md' },
  { id: 'helm', title: 'HELM delivery records', path: 'docs/evidence/helm.md' },
  { id: 'audio', title: 'Audio / Spotify / DSP', path: 'docs/evidence/audio.md' },
  { id: 'displays', title: 'Lumina controls & display integration', path: 'docs/evidence/displays.md' },
  { id: 'maps', title: 'MAP physical-device checks', path: 'docs/evidence/maps.md' },
  { id: 'routes', title: 'Signal K route integration', path: 'docs/evidence/routes.md' },
  { id: 'plotters', title: 'Chart conversion boundaries', path: 'docs/evidence/plotters.md' },
  { id: 'sonar', title: 'Physical sonar trial', path: 'docs/evidence/sonar.md' },
  { id: 'hardware', title: 'Enclosures & electrical checks', path: 'docs/evidence/hardware.md' },
];
export const repository = 'https://github.com/xkyball/number-five';
export const siteOrigin = 'https://no5.christophhoffmann.net';

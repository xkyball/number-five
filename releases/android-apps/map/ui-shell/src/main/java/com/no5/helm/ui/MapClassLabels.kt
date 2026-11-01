package com.no5.helm.ui

/** Localized UI names; S57 identifiers and the source catalog remain unchanged. */
internal object MapClassLabels {
 private val labels = """
ADMARE|Verwaltungsgebiet|Administratiewe gebied
AIRARE|Flughafen/Flugplatz|Lughawe/vliegveld
ACHBRT|Ankerliegeplatz|Ankerligplek
ACHPNT|Anker|Anker
ACHARE|Ankergebiet|Ankergebied
BCNCAR|Kardinale Bake|Kardinale baken
BCNISD|Einzelgefahrenbake|Enkelgevaarbaken
BCNLAT|Laterale Bake|Laterale baken
BCNSAW|Fahrwassermittenbake|Veilige-waterbaken
BCNSPP|Sonderbake|Spesialedoelbaken
BERTHS|Liegeplatz|Ligplek
BRIDGE|Brücke|Brug
BUISGL|Einzelgebäude|Enkelgebou
BUIREL|Religiöses Gebäude|Godsdienstige gebou
BUAARE|Bebautes Gebiet|Beboude gebied
BOYCAR|Kardinale Tonne|Kardinale boei
BOYINB|Anlagentonne|Installasieboei
BOYISD|Einzelgefahrentonne|Enkelgevaarboei
BOYLAT|Laterale Tonne|Laterale boei
BOYSAW|Fahrwassermittentonne|Veilige-waterboei
BOYSPP|Sondertonne|Spesialedoelboei
CBLARE|Kabelgebiet|Kabelgebied
CBLOHD|Freileitung|Oorhoofse kabel
CBLSUB|Unterwasserkabel|Onderwaterkabel
CANALS|Kanal|Kanaal
CANBNK|Kanalböschung|Kanaaloewer
CTSARE|Umschlaggebiet|Vragoorlaaigebied
CAUSWY|Dammweg|Damweg
CTNARE|Vorsichtsgebiet|Waarskuwingsgebied
CHNWIR|Kette/Drahtseil|Ketting/draadkabel
CHKPNT|Kontrollstelle|Kontrolepunt
CGUSTA|Küstenwachstation|Kuswagstasie
COALNE|Küstenlinie|Kuslyn
CONZNE|Anschlusszone|Aangrensende sone
COSARE|Festlandsockelgebiet|Kontinentale-plaatgebied
CTRPNT|Festpunkt|Beheerpunt
CONVYR|Förderanlage|Vervoerband
CRANES|Kran|Hyskraan
CURENT|Nichtgezeitenströmung|Nie-getystroming
CUSZNE|Zollgebiet|Doeanegebied
DAMCON|Staudamm|Damwal
DAYMAR|Tagesmarke|Dagmerk
DWRTCL|Tiefwasserroute: Mittellinie|Diepwaterroete: middellyn
DWRTPT|Tiefwasserroute: Abschnitt|Diepwaterroete: deel
DEPARE|Tiefengebiet|Dieptegebied
DEPCNT|Tiefenlinie|Dieptelyn
DISMAR|Entfernungsmarke|Afstandsmerk
DOCARE|Dockgebiet|Dokgebied
DRGARE|Ausgebaggertes Gebiet|Gebaggerde gebied
DRYDOC|Trockendock|Droogdok
DMPGRD|Schüttstelle|Stortgebied
DYKCON|Deich|Dyk
EXEZNE|Ausschließliche Wirtschaftszone|Eksklusiewe ekonomiese sone
FAIRWY|Fahrwasser|Vaarwater
FNCLNE|Zaun/Mauer|Heining/muur
FERYRT|Fährroute|Veerbootroete
FSHZNE|Fischereizone|Visserysone
FSHFAC|Fischereianlage|Visserygerief
FSHGRD|Fischereigebiet|Visvanggebied
FLODOC|Schwimmdock|Drywende dok
FOGSIG|Nebelsignal|Missein
FORSTC|Befestigungsanlage|Vestingwerk
FRPARE|Freihafengebiet|Vryhawegebied
GATCON|Tor|Hek
GRIDRN|Trockenfallgestell|Droogvalrooster
HRBARE|Hafengebiet|Hawegebied
HRBFAC|Hafenanlage|Hawegerief
HULKES|Schiffshulk|Skeepsromp
ICEARE|Eisgebiet|Ysgebied
ICNARE|Verbrennungsgebiet|Verbrandingsgebied
ISTZNE|Küstenverkehrszone|Kusverkeersone
LAKARE|See|Meer
LAKSHR|Seeufer|Meeroewer
LNDARE|Landfläche|Landgebied
LNDELV|Landhöhe|Landhoogte
LNDRGN|Landregion|Landstreek
LNDMRK|Landmarke|Landmerk
LIGHTS|Lichter|Ligte
_EXTGN|Erloschenes Licht|Uitgedoofde lig
LITFLT|Feuerfloß|Ligvlot
LITVES|Feuerschiff|Ligskip
LOCMAG|Örtliche Magnetstörung|Plaaslike magnetiese afwyking
LOKBSN|Schleusenkammer|Sluiskolk
LOGPON|Holzlagerbecken|Houtdam
MAGVAR|Missweisung|Magnetiese variasie
MARCUL|Aquakultur|Mariene kweekplaas
MIPARE|Militärisches Übungsgebiet|Militêre oefengebied
MONUMT|Denkmal|Monument
MORFAC|Festmachanlage|Vasmeergerief
NAVLNE|Navigationslinie|Navigasielyn
OBSTRN|Hindernis|Hindernis
OFSPLF|Offshoreplattform|Buitelandse platform
OSPARE|Offshorefördergebiet|Buitelandse produksiegebied
OILBAR|Ölsperre|Olieversperring
PILPNT|Pfahl|Paal
PILBOP|Lotsenübernahmestelle|Loodsoorklimplek
PIPARE|Rohrleitungsgebiet|Pyplyngebied
PIPOHD|Oberirdische Rohrleitung|Oorhoofse pyplyn
PIPSOL|Unterwasser-/Landrohrleitung|Onderwater-/landpyplyn
PONTON|Ponton|Ponton
PRCARE|Besonderes Vorsichtsgebiet|Spesiale voorsorggebied
PRDARE|Produktions-/Lagergebiet|Produksie-/bergingsgebied
PYLONS|Pfeiler/Brückenstütze|Pyloon/brugsteun
RADLNE|Radarlinie|Radarlyn
RADRNG|Radarentfernung|Radarafstand
RADRFL|Radarreflektor|Radarreflektor
RADSTA|Radarstation|Radarstasie
RTPBCN|Radarantwortbake|Radarantwoordbaken
RDOCAL|Funkmeldestelle|Radioaanmeldpunt
RDOSTA|Funkstation|Radiostasie
RAILWY|Eisenbahn|Spoorlyn
RAPIDS|Stromschnellen|Stroomversnellings
RCRTCL|Empfohlene Route: Mittellinie|Aanbevole roete: middellyn
RECTRC|Empfohlener Kurs|Aanbevole vaarkoers
RCTLPT|Empfohlener Verkehrsweg|Aanbevole verkeersbaandeel
RSCSTA|Rettungsstation|Reddingstasie
RESARE|Beschränktes Gebiet|Beperkte gebied
RETRFL|Rückstrahler|Retroreflektor
RIVERS|Fluss|Rivier
RIVBNK|Flussufer|Rivieroewer
ROADWY|Straße|Pad
RUNWAY|Start-/Landebahn|Aanloopbaan
SNDWAV|Sandwellen|Sandgolwe
SEAARE|Seegebiet/Gewässername|Seegebied/watergebiednaam
SPLARE|Wasserfluggebiet|Watervliegtuiglandingsgebied
SBDARE|Meeresbodengebiet|Seebodemgebied
SLCONS|Uferbauwerk|Oewerkonstruksie
SISTAT|Verkehrssignalstation|Verkeerseinstasie
SISTAW|Warnsignalstation|Waarskuwingseinstasie
SILTNK|Silo/Tank|Silo/tenk
SLOTOP|Böschungsoberkante|Hellingbovelyn
SLOGRD|Böschung|Skuins terrein
SMCFAC|Sportbootanlage|Kleinvaartuiggerief
SOUNDG|Tiefenzahl|Dieptesyfer
SPRING|Quelle|Fontein
SQUARE|Platz|Plein
STSLNE|Gerade Küstenmeerbasislinie|Reguit territoriale basislyn
SUBTLN|U-Boot-Transitweg|Duikbootdeurvaartbaan
SWPARE|Abgesuchtes Gebiet|Geveegde gebied
TESARE|Küstenmeer|Territoriale seegebied
_TEXTO|Text|Teks
TS_PRH|Gezeitenstrom: harmonische Vorhersage|Getystroom: harmoniese voorspelling
TS_PNH|Gezeitenstrom: nichtharmonische Vorhersage|Getystroom: nie-harmoniese voorspelling
TS_PAD|Gezeitenstrom: Tabellendaten|Getystroom: tabeldata
TS_TIS|Gezeitenstrom: Zeitreihe|Getystroom: tydreeks
T_HMON|Gezeiten: harmonische Vorhersage|Gety: harmoniese voorspelling
T_NHMN|Gezeiten: nichtharmonische Vorhersage|Gety: nie-harmoniese voorspelling
T_TIMS|Gezeitenstrom: Zeitreihe|Getystroom: tydreeks
TIDEWY|Gezeitenrinne|Getygeul
TOPMAR|Toppzeichen|Topmerk
TOWERS|Turm|Toring
TSELNE|Verkehrstrennlinie|Verkeerskeidingslyn
TSSBND|Verkehrstrenngebiet: Grenze|Verkeerskeidingstelsel: grens
TSSCRS|Verkehrstrenngebiet: Kreuzung|Verkeerskeidingstelsel: kruising
TSSLPT|Verkehrstrenngebiet: Fahrspur|Verkeerskeidingstelsel: baan
TSSRON|Verkehrstrenngebiet: Kreisverkehr|Verkeerskeidingstelsel: rotonde
TSEZNE|Verkehrstrennzone|Verkeerskeidingsone
TUNNEL|Tunnel|Tonnel
TWRTPT|Gegenverkehrsroute|Tweerigtingroetedeel
UWTROC|Unterwasser-/überspülter Fels|Onderwater-/oorspoelde rots
UNSARE|Nicht vermessenes Gebiet|Onopgemete gebied
VEGATN|Vegetation|Plantegroei
WATTUR|Wasserturbulenz|Waterturbulensie
WATFAL|Wasserfall|Waterval
WEDKLP|Seegras/Seetang|Seewier/kelp
WRECKS|Wracks|Wrakke
ZEMCNT|Nullmeterlinie|Nulmeterlyn
TS_FEB|Gezeitenstrom: Flut/Ebbe|Getystroom: vloed/eb
NEWOBJ|Neues Objekt|Nuwe objek
M_ACCY|Datengenauigkeit|Datapresisie
M_CSCL|Erfassungsmaßstab|Datasamestellingskaal
M_COVR|Abdeckung|Dekking
M_HDAT|Horizontales Bezugssystem|Horisontale datum
M_HOPA|Horizontale Versatzparameter|Horisontale datumverskuiwing
M_NPUB|Nautische Veröffentlichungen|Nautiese publikasies
M_NSYS|Seezeichensystem|Navigasiemerkstelsel
M_PROD|Herstellungsinformationen|Produksie-inligting
M_QUAL|Datenqualität|Datagehalte
M_SDAT|Tiefenbezug|Dieptedatum
M_SREL|Vermessungszuverlässigkeit|Opnamebetroubaarheid
M_UNIT|Dateneinheiten|Datamaateenhede
M_VDAT|Höhenbezug|Vertikale datum
C_AGGR|Zusammenfassung|Saamvoeging
C_ASSO|Verknüpfung|Assosiasie
C_STAC|Übereinander/untereinander|Bo-op/onder mekaar
${'$'}AREAS|Kartografische Fläche|Kartografiese gebied
${'$'}LINES|Kartografische Linie|Kartografiese lyn
${'$'}CSYMB|Kartografisches Symbol|Kartografiese simbool
${'$'}COMPS|Kompass|Kompas
${'$'}TEXTS|Text|Teks
NOTMRK|Hinweiszeichen|Kennisgewingmerk
WTWAXS|Wasserstraßenachse|Waterwegas
WTWPRF|Wasserstraßenprofil|Waterwegprofiel
BRGARE|Brückengebiet|Bruggebied
BUNSTA|Bunkerstation|Brandstofstasie
COMARE|Kommunikationsgebiet|Kommunikasiegebied
HRBBSN|Hafenbecken|Hawekom
LOKARE|Schleusengebiet|Sluisgebied
LKBSPT|Schleusenkammerteil|Sluiskolkdeel
PRTARE|Hafenbereich|Hawegebied
BCNWTW|Wasserstraßenbake|Waterwegbaken
BOYWTW|Wasserstraßentonne|Waterwegboei
REFDMP|Mülldeponie|Vullisstort
RTPLPT|Routenplanungspunkt|Roetebeplanningspunt
TERMNL|Terminal|Terminaal
TRNBSN|Wendebecken|Draaikom
WTWARE|Wasserstraßengebiet|Waterweggebied
WTWGAG|Wasserstraßenpegel|Waterwegpeil
TISDGE|Allgemeiner Zeitplan|Algemene tydskedule
VEHTRF|Fahrzeugübergang|Voertuigoordrag
EXCNST|Besonderes Schifffahrtsbauwerk|Uitsonderlike navigasiestruktuur
LG_SDM|Zulässige Schiffsmaße|Toegelate skeepsafmetings
LG_VSP|Zulässige Schiffsgeschwindigkeit|Toegelate vaartuigspoed
ANNOTA|Anmerkung|Aantekening
RESTRC|Allgemeine Grenze|Algemene grens
TRFLNE|Verkehrslinie|Verkeerslyn
GENNAV|Allgemeines Seezeichen|Algemene navigasiemerk
""".trimIndent().lineSequence().associate { row->val p=row.split('|');p[0] to (p[1] to p[2]) }
 fun contains(code:String)=code.uppercase(java.util.Locale.ROOT) in labels
 fun title(code:String,sourceName:String):String {
  val names=labels[code.uppercase(java.util.Locale.ROOT)]
  return when(com.no5.ui.UiLanguage.current()){"en"->sourceName;"af"->names?.second;else->names?.first}
   ?:m1("Unbekannte Klasse","Unknown class","Onbekende klas")+" · "+code
 }
}

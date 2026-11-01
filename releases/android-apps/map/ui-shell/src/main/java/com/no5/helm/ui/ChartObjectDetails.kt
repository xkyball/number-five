package com.no5.helm.ui

import com.no5.ui.No5Text
import android.content.res.AssetManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.helm.chart.*
import com.no5.helm.library.*
import com.no5.preparation.ui.No5CompactAction

/** The local S57 registry decodes enum/list values; unknown codes remain visible. */
internal class ChartObjectDictionary(attributes: String, expected: String) {
    private data class Attribute(val code: String, val label: String, val type: String)
    private val attributes = attributes.lineSequence().drop(1).filter { it.isNotBlank() }.map(::csvRow)
        .filter { it.size >= 4 }.associate { it[2] to Attribute(it[0], it[1], it[3]) }
    private val expected = expected.lineSequence().drop(1).filter { it.isNotBlank() }.map(::csvRow)
        .filter { it.size >= 3 }.associate { (it[0] to it[1]) to it[2] }
    fun label(key: String) = labels[key]?.let { No5Text.ui(it) } ?: attributes[key]?.label ?: key
    fun value(key: String, raw: String): String {
        val attribute = attributes[key] ?: return raw
        if (attribute.type !in setOf("E", "L")) return raw
        return raw.split(',').joinToString(" · ") { part ->
            val code = part.trim()
            val decoded = translations[key to code] ?: expected[attribute.code to code]
            if (decoded == null) No5Text.ui("Unbekannter Code %s", code) else "${translations[key to code]?.let { No5Text.ui(it) } ?: decoded} ($code)"
        }
    }
    companion object {
        fun from(assets: AssetManager) = ChartObjectDictionary(
            assets.open("s57attributes.csv").bufferedReader().use { it.readText() },
            assets.open("s57expectedinput.csv").bufferedReader().use { it.readText() })
        private val labels = mapOf("OBJNAM" to "Name", "NOBJNM" to "Lokaler Name", "BOYSHP" to "Tonnenform",
            "CATCAM" to "Kardinalzeichen", "CATLAM" to "Lateralzeichen", "CATSPM" to "Zweck",
            "COLOUR" to "Farben", "COLPAT" to "Farbmuster", "CATWRK" to "Wrackart", "VALSOU" to "Gemeldete Tiefe",
            "QUASOU" to "Qualität der Tiefenangabe", "WATLEV" to "Lage zum Wasserspiegel", "CATREA" to "Gebietsart",
            "RESTRN" to "Beschränkungen", "INFORM" to "Hinweis", "NINFOM" to "Lokaler Hinweis",
            "DATSTA" to "Gültig ab", "DATEND" to "Gültig bis", "PERSTA" to "Saisonbeginn", "PEREND" to "Saisonende",
            "STATUS" to "Status", "TXTDSC" to "Zusatztext", "NTXTDS" to "Lokaler Zusatztext", "DUNITS" to "Tiefeneinheit")
        private val translations = mapOf(
            ("BOYSHP" to "1") to "Kegelförmig",
            ("BOYSHP" to "2") to "Zylindrisch",
            ("BOYSHP" to "3") to "Kugelförmig",
            ("BOYSHP" to "4") to "Säulentonne",
            ("BOYSHP" to "5") to "Spierentonne",
            ("BOYSHP" to "6") to "Fasstonne",
            ("BOYSHP" to "7") to "Großtonne",
            ("BOYSHP" to "8") to "Eistonne",
            ("CATLAM" to "1") to "Backbordzeichen",
            ("CATLAM" to "2") to "Steuerbordzeichen",
            ("CATLAM" to "3") to "Bevorzugtes Fahrwasser nach Steuerbord",
            ("CATLAM" to "4") to "Bevorzugtes Fahrwasser nach Backbord",
            ("CATWRK" to "1") to "Als ungefährlich kartiert",
            ("CATWRK" to "2") to "Gefährliches Wrack",
            ("CATWRK" to "3") to "Verstreute Wrackreste",
            ("CATWRK" to "4") to "Mast oder Masten sichtbar",
            ("CATWRK" to "5") to "Rumpf oder Aufbau teilweise sichtbar",
            ("COLPAT" to "1") to "Waagerechte Streifen",
            ("COLPAT" to "2") to "Senkrechte Streifen",
            ("COLPAT" to "3") to "Diagonale Streifen",
            ("COLPAT" to "4") to "Kariert",
            ("COLPAT" to "5") to "Streifenrichtung unbekannt",
            ("COLPAT" to "6") to "Randstreifen",
            ("DUNITS" to "1") to "Meter",
            ("DUNITS" to "2") to "Faden und Fuß",
            ("DUNITS" to "3") to "Fuß",
            ("DUNITS" to "4") to "Faden und Bruchteile",
            ("QUASOU" to "1") to "Tiefe bekannt",
            ("QUASOU" to "2") to "Tiefe unbekannt",
            ("QUASOU" to "3") to "Zweifelhafte Lotung",
            ("QUASOU" to "4") to "Unzuverlässige Lotung",
            ("QUASOU" to "5") to "Bei diesem Wert kein Grund gefunden",
            ("QUASOU" to "6") to "Geringste Tiefe bekannt",
            ("QUASOU" to "7") to "Geringste Tiefe unbekannt; kartierte Durchfahrtstiefe frei",
            ("QUASOU" to "8") to "Gemeldeter Wert, nicht vermessen",
            ("QUASOU" to "9") to "Gemeldeter Wert, nicht bestätigt",
            ("QUASOU" to "10") to "Unterhaltene Tiefe",
            ("QUASOU" to "11") to "Nicht regelmäßig unterhalten",
            ("RESTRN" to "1") to "Ankern verboten",
            ("RESTRN" to "2") to "Ankern eingeschränkt",
            ("RESTRN" to "3") to "Fischen verboten",
            ("RESTRN" to "4") to "Fischen eingeschränkt",
            ("RESTRN" to "5") to "Schleppnetzfischen verboten",
            ("RESTRN" to "6") to "Schleppnetzfischen eingeschränkt",
            ("RESTRN" to "7") to "Einfahrt verboten",
            ("RESTRN" to "8") to "Einfahrt eingeschränkt",
            ("RESTRN" to "9") to "Baggern verboten",
            ("RESTRN" to "10") to "Baggern eingeschränkt",
            ("RESTRN" to "11") to "Tauchen verboten",
            ("RESTRN" to "12") to "Tauchen eingeschränkt",
            ("RESTRN" to "13") to "Sog und Wellenschlag vermeiden",
            ("RESTRN" to "14") to "Zu meidendes Gebiet",
            ("RESTRN" to "15") to "Bauarbeiten verboten",
            ("RESTRN" to "16") to "Einleitungen verboten",
            // S-57 3.1 Appendix A, chapter 2: the bundled legacy registry stops at 16.
            ("RESTRN" to "17") to "Einleitungen eingeschränkt",
            ("RESTRN" to "18") to "Industrielle oder mineralische Erkundung und Erschließung verboten",
            ("RESTRN" to "19") to "Industrielle oder mineralische Erkundung und Erschließung eingeschränkt",
            ("RESTRN" to "20") to "Bohrungen verboten",
            ("RESTRN" to "21") to "Bohrungen eingeschränkt",
            ("RESTRN" to "22") to "Entfernen historischer Artefakte verboten",
            ("RESTRN" to "23") to "Umladen von Fracht verboten",
            ("RESTRN" to "24") to "Schleifen von Gegenständen über den Grund verboten",
            ("RESTRN" to "25") to "Anhalten verboten",
            ("RESTRN" to "26") to "Anlanden verboten",
            ("RESTRN" to "27") to "Geschwindigkeit beschränkt",
            ("WATLEV" to "1") to "Bei Hochwasser teilweise unter Wasser",
            ("WATLEV" to "2") to "Immer trocken",
            ("WATLEV" to "3") to "Immer unter Wasser",
            ("WATLEV" to "4") to "Fällt trocken und wird überflutet",
            ("WATLEV" to "5") to "Auf Höhe des Wasserspiegels",
            ("WATLEV" to "6") to "Überflutungsgefährdet",
            ("WATLEV" to "7") to "Schwimmend",
            ("CATCAM" to "1") to "Nord", ("CATCAM" to "2") to "Ost", ("CATCAM" to "3") to "Süd", ("CATCAM" to "4") to "West",
            ("COLOUR" to "1") to "Weiß", ("COLOUR" to "2") to "Schwarz", ("COLOUR" to "3") to "Rot", ("COLOUR" to "4") to "Grün",
            ("COLOUR" to "5") to "Blau", ("COLOUR" to "6") to "Gelb",
            ("CATREA" to "4") to "Naturschutzgebiet", ("CATREA" to "5") to "Vogelschutzgebiet", ("CATREA" to "6") to "Wildschutzgebiet",
            ("CATREA" to "7") to "Robbenschutzgebiet", ("CATREA" to "22") to "Fischschutzgebiet", ("CATREA" to "23") to "Ökologisches Reservat",
            ("CATREA" to "27") to "Ökologisch empfindliches Seegebiet (ESSA)", ("CATREA" to "28") to "Besonders empfindliches Seegebiet (PSSA)")
    }
}
internal fun csvRow(line: String): List<String> {
    val fields = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) { val c = line[i]
        if (c == '"') { if (quoted && i + 1 < line.length && line[i + 1] == '"') { field.append('"'); i++ } else quoted = !quoted }
        else if (c == ',' && !quoted) { fields += field.toString(); field.clear() } else field.append(c)
        i++
    }
    fields += field.toString(); return fields
}
internal fun objectKind(obj: ChartObject): String = when (obj.objectClass) {
    "BOYCAR" -> No5Text.ui("Kardinaltonne"); "BOYLAT" -> No5Text.ui("Lateraltonne"); "BOYISD" -> No5Text.ui("Einzelgefahrtonne")
    "BOYSAW" -> No5Text.ui("Fahrwassermittentonne"); "BOYSPP" -> No5Text.ui("Sondertonne"); "BOYINB" -> No5Text.ui("Installationsboje")
    "WRECKS" -> No5Text.ui("Wrack")
    "RESARE" -> if (obj.attributes["CATREA"].orEmpty().split(',').any { it.trim() in setOf("4", "5", "6", "7", "22", "23", "27", "28") }) No5Text.ui("Schutzgebiet") else No5Text.ui("Beschränktes Gebiet")
    "MIPARE" -> No5Text.ui("Militärisches Übungsgebiet"); "CTNARE" -> No5Text.ui("Vorsichtsgebiet")
    "LIGHTS" -> No5Text.ui("Leuchtfeuer"); "TOPMAR" -> No5Text.ui("Topzeichen"); "FOGSIG" -> No5Text.ui("Nebelsignal"); "RTPBCN" -> No5Text.ui("Radarantwortbake")
    else -> obj.objectClass
}
internal fun objectTitle(obj: ChartObject) = obj.attributes["OBJNAM"]?.takeIf { it.isNotBlank() } ?: objectKind(obj)
internal fun objectSource(obj: ChartObject, library: ChartLibrarySnapshot?): String {
    val pack = library?.packages?.find { it.id == library.active?.packageId }
    val cell = pack?.cells?.find { it.name == obj.cellName && ChartMetadataContract.sourceKey(it, pack.piCache) == obj.reference.sourceKey }
    if (cell == null) return No5Text.ui("Kartenzelle %s · Herkunft im aktiven Bestand nicht zugeordnet", obj.cellName)
    val provenance = pack.piCache
    val provider = when {
        provenance != null -> "o-charts · ${provenance.product}"
        cell.provider == S57Profiles.WSV -> "WSV · Inland-ENC"
        cell.provider == "noaa-s57" -> "NOAA · S-57"
        else -> cell.provider
    }
    return pack.label+"\n"+cell.name+" · "+No5Text.ui("Edition %s · Update %s · %s",cell.edition,cell.update,cell.issueDate)+"\n"+provider
}

@Composable internal fun ChartObjectPanel(selected: ChartObject?, hits: List<ChartObject>, total: Int,
    notice: String?, busy: Boolean, highlight: ChartObjectHighlight, dictionary: ChartObjectDictionary,
    library: ChartLibrarySnapshot?, texts: List<ChartObjectText>, hasMore: Boolean, onChoose: (ChartObject) -> Unit,
    onMore: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,workspace:Boolean=false,night:Boolean=false,noticeRaw:Boolean=false) {
    if(workspace){
        Box(modifier.fillMaxSize()) {
            ChartObjectWorkspace(selected,hits,total,notice,busy,highlight,dictionary,library,texts,hasMore,onChoose,onMore,onClose,night,noticeRaw)
        }
        return
    }
    var raw by remember(selected?.reference) { mutableStateOf(false) }
    val scroll = key(selected?.reference, hits.firstOrNull()?.reference) { rememberScrollState() }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (selected == null) com.no5.ui.No5Text.ui("Objekte am Kartenpunkt") else objectTitle(selected), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            No5CompactAction(No5Text.ui("Schließen"), onClose, Modifier.testTag("object-close"))
        }
        key(selected?.reference,hits.firstOrNull()?.reference){
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(com.no5.ui.No5Text.ui("Kartenobjekte werden geprüft …")) }
            notice?.let { Text(it, Modifier.testTag("object-notice")) }
            if (selected == null) {
                if (total > 1) Text(No5Text.ui("%d Treffer · Objekt auswählen", total), Modifier.testTag("object-hit-count"))
                hits.forEach { obj -> Surface(tonalElevation = 1.dp) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(objectTitle(obj)); Text("${objectKind(obj)} · ${obj.cellName}", style = MaterialTheme.typography.bodySmall)
                        No5CompactAction("Auswählen", { onChoose(obj) }, Modifier.testTag("object-pick-${obj.reference.sourceKey}-${obj.reference.featureIndex}"), !busy)
                    }
                } }
                if (hasMore) No5CompactAction("Weitere Treffer", onMore, Modifier.testTag("object-more"), !busy)
            } else SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${objectKind(selected)} · ${selected.objectClass}", Modifier.testTag("object-kind"))
                if (!highlight.visible) Text(com.no5.ui.No5Text.ui("Im aktuellen Ausschnitt oder Kartenstil nicht sichtbar."))
                Text("${selected.position.latitude}, ${selected.position.longitude}", style = MaterialTheme.typography.bodySmall)
                if (selected.objectClass == "WRECKS" && selected.attributes["VALSOU"] == null) Text(com.no5.ui.No5Text.ui("Tiefe unbekannt: keine Tiefenangabe in dieser Quelle."))
                if (selected.attributes["VALSOU"] != null && selected.attributes["DUNITS"] == null) Text(com.no5.ui.No5Text.ui("Tiefeneinheit in dieser Quelle nicht angegeben; Wert ohne Umrechnung."))
                val primary = listOf("OBJNAM", "NOBJNM", "BOYSHP", "CATCAM", "CATLAM", "CATSPM", "COLOUR", "COLPAT", "CATWRK", "VALSOU", "DUNITS", "QUASOU", "WATLEV", "CATREA", "RESTRN", "INFORM", "NINFOM", "STATUS", "DATSTA", "DATEND", "PERSTA", "PEREND")
                primary.forEach { key -> selected.attributes[key]?.let { value -> Column {
                    Text(dictionary.label(key), style = MaterialTheme.typography.labelLarge)
                    Text(dictionary.value(key, value), Modifier.testTag("object-attribute-$key"))
                } } }
                listOf("TXTDSC", "NTXTDS").forEach { key -> selected.attributes[key]?.let { name ->
                    val attachment = texts.find { it.name == name }
                    Text("${dictionary.label(key)}: $name", style = MaterialTheme.typography.labelLarge)
                    Text(attachment?.text ?: attachment?.message ?: com.no5.ui.No5Text.ui("Zusatztext wird aus der Kartenquelle gelesen …"), Modifier.testTag("object-attachment-$key"))
                } }
                Text(com.no5.ui.No5Text.ui("Kartenquelle"), style = MaterialTheme.typography.labelLarge)
                Text(objectSource(selected, library), Modifier.testTag("object-source"), style = MaterialTheme.typography.bodySmall)
                Text(com.no5.ui.No5Text.ui("Leuchtfeuer und Topzeichen werden als eigene Objekte angezeigt, sofern die Quelle keine gesicherte Zuordnung liefert."), style = MaterialTheme.typography.bodySmall)
                No5CompactAction(if (raw) "Rohattribute ausblenden" else No5Text.ui("Alle %d Rohattribute", selected.attributes.size), { raw = !raw }, Modifier.testTag("object-raw-toggle"))
                if (raw) selected.attributes.toSortedMap().forEach { (key, value) -> Text("$key: $value", Modifier.testTag("object-raw-$key"), style = MaterialTheme.typography.bodySmall) }
            } }
        }
        }
    }
}


/** Real chart data in the approved compact object navigation. No query or mutable renderer state lives here. */
@Composable private fun ChartObjectWorkspace(selected:ChartObject?,hits:List<ChartObject>,total:Int,notice:String?,busy:Boolean,
    highlight:ChartObjectHighlight,dictionary:ChartObjectDictionary,library:ChartLibrarySnapshot?,texts:List<ChartObjectText>,hasMore:Boolean,
    onChoose:(ChartObject)->Unit,onMore:()->Unit,onClose:()->Unit,night:Boolean,noticeRaw:Boolean){
    val language=com.no5.ui.LocalNo5Language.current
    var page by remember(selected?.reference,hits.firstOrNull()?.reference){mutableStateOf("menu")}
    var attachmentName by remember(selected?.reference){mutableStateOf<String?>(null)}
    fun back(){page=when(page){"menu"->if(selected!=null&&hits.isNotEmpty())"hits"else{onClose();"menu"};"hits"->{onClose();"menu"};"raw"->"attributes";"document"->"attachments";else->"menu"}}
    androidx.activity.compose.BackHandler{back()}
    val message=notice?.let{if(noticeRaw)it else No5Text.ui(it)}
    val rows=when{
        selected==null||page=="hits"->hits.map{obj->com.no5.header.artwork.No5WorkspaceView.Row(objectTitle(obj),"${objectKind(obj)} · ${obj.cellName}",
            rawLabel=true,rawDetail=true,enabled=!busy,key="object-pick-${obj.reference.sourceKey}-${obj.reference.featureIndex}",action={
                // Reselecting the same hit does not change its immutable reference,
                // so remember(selected.reference) alone cannot leave the hit page.
                if (obj.reference == selected?.reference) page="menu"
                onChoose(obj)
            })}+
            if(hasMore)listOf(com.no5.header.artwork.No5WorkspaceView.Row("Weitere Treffer",enabled=!busy,key="object-more",action=onMore))else emptyList()
        page=="menu"->listOf(
            com.no5.header.artwork.No5WorkspaceView.Row(m1("Objektdaten","Object data","Objekdata"),value=selected.attributes.size.toString(),rawValue=true,key="object-attributes",action={page="attributes"}),
            com.no5.header.artwork.No5WorkspaceView.Row(m1("Zusatztexte","Additional text","Bykomende teks"),enabled=listOf("TXTDSC","NTXTDS").any{it in selected.attributes},key="object-attachments",action={page="attachments"}),
            com.no5.header.artwork.No5WorkspaceView.Row(m1("Herkunft","Source","Herkoms"),value=selected.cellName,rawValue=true,key="object-provenance",action={page="source"}))
        page=="attributes"->listOf(com.no5.header.artwork.No5WorkspaceView.Row(No5Text.ui("Alle %d Rohattribute",selected.attributes.size),rawLabel=true,key="object-raw-toggle",action={page="raw"}))
        page=="attachments"->listOf("TXTDSC","NTXTDS").mapNotNull{key->selected.attributes[key]?.let{name->com.no5.header.artwork.No5WorkspaceView.Row(dictionary.label(key),name,rawLabel=true,rawDetail=true,key="object-document-$key",action={attachmentName=name;page="document"})}}
        else->emptyList()
    }
    val facts=when(page){
        "attributes"->selected?.let{obj->listOfNotNull(
            "${objectKind(obj)} · ${obj.objectClass}","${obj.position.latitude}, ${obj.position.longitude}",
            if(!highlight.visible)No5Text.ui("Im aktuellen Ausschnitt oder Kartenstil nicht sichtbar.")else null,
            if(obj.objectClass=="WRECKS"&&obj.attributes["VALSOU"]==null)No5Text.ui("Tiefe unbekannt: keine Tiefenangabe in dieser Quelle.")else null,
            if(obj.attributes["VALSOU"]!=null&&obj.attributes["DUNITS"]==null)No5Text.ui("Tiefeneinheit in dieser Quelle nicht angegeben; Wert ohne Umrechnung.")else null).joinToString("\n")+"\n\n"+
                listOf("OBJNAM","NOBJNM","BOYSHP","CATCAM","CATLAM","CATSPM","COLOUR","COLPAT","CATWRK","VALSOU","DUNITS","QUASOU","WATLEV","CATREA","RESTRN","INFORM","NINFOM","STATUS","DATSTA","DATEND","PERSTA","PEREND")
                .mapNotNull{key->obj.attributes[key]?.let{value->dictionary.label(key)+"\n"+dictionary.value(key,value)}}.joinToString("\n\n")}
        "raw"->selected?.attributes?.toSortedMap()?.map{(key,value)->"$key: $value"}?.joinToString("\n")
        "source"->selected?.let{objectSource(it,library)+"\n\n"+No5Text.ui("Leuchtfeuer und Topzeichen werden als eigene Objekte angezeigt, sofern die Quelle keine gesicherte Zuordnung liefert.")}
        "document"->texts.find{it.name==attachmentName}?.let{it.text?:No5Text.ui(it.message)}?:No5Text.ui("Zusatztext wird aus der Kartenquelle gelesen …")
        else->null
    }
    val title=when(page){"attributes"->"Objektattribute";"raw"->"Rohattribute";"source"->"Kartenquelle";"document"->"Objektdokument";else->"Objektinformationen"}
    if(selected==null||page=="hits"){
        BoxWithConstraints(Modifier.fillMaxSize()){
            val scale=maxWidth.value/2000f
            Box(Modifier.offset(y=(130*scale).dp).fillMaxWidth().height((808*scale).dp)){
                MapPagedWorkspace(m1("Kartenobjekte","Chart objects","Kaartobjekte"),message.orEmpty(),rows.ifEmpty{listOf(com.no5.header.artwork.No5WorkspaceView.Row(m1(if(busy)"Abfrage läuft …"else"Kartenobjekt antippen",if(busy)"Query running…"else"Tap a chart object",if(busy)"Navraag loop…"else"Tik op 'n kaartobjek"),enabled=false,key="object-query-status"))},night,::back)
            }
        }
        return
    }
    com.no5.ui.No5DetailsDialog(title,night,::back,
        body=listOfNotNull(selected?.let(::objectTitle)?.takeIf{page=="menu"},message,if(busy)No5Text.ui("Kartenobjekte werden geprüft …")else null,if(selected==null&&total>1)No5Text.ui("%d Treffer · Objekt auswählen",total)else null).takeIf{it.isNotEmpty()}?.joinToString("\n"),
        rows=rows,compactClose=page=="menu",tag="object-info-panel",bodyKey=selected?.reference to page,preferredHeight=if(page=="menu"&&selected!=null)620f else null,
        actions=listOf(com.no5.header.artwork.No5WorkspaceView.Action("Schließen",key="object-close",action=::back)),content=facts?.let{value->{SelectionContainer{Text(value,Modifier.testTag("object-facts"))}}})
}

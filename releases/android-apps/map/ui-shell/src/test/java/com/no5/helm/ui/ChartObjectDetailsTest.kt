package com.no5.helm.ui
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class ChartObjectDetailsTest {
    private val assets = File("../chart-engine/src/main/assets")
    private fun dictionary() = ChartObjectDictionary(File(assets,"s57attributes.csv").readText(), File(assets,"s57expectedinput.csv").readText())
    private fun obj(name: String, attrs: Map<String,String>) = ChartObject(ChartObjectReference("key", 1, 7), "1", name,"AREA","cell",GeoProjection(1.0,2.0),0.0,attrs)
    @Test fun commaListsRetainEveryCodeIncludingUnknownAndSupplementValues() {
        val d = dictionary(); val colour = d.value("COLOUR", "3,4,99")
        assertTrue(colour.contains("Rot (3)"));assertTrue(colour.contains("Grün (4)"));assertTrue(colour.contains("Unbekannter Code 99"))
        val restriction = d.value("RESTRN", "1,2,7,8")
        assertTrue(restriction.contains("Ankern verboten (1)")); assertTrue(restriction.contains("Ankern eingeschränkt (2)"))
        assertTrue(restriction.contains("Einfahrt verboten (7)")); assertTrue(restriction.contains("Einfahrt eingeschränkt (8)"))
        val area = d.value("CATREA", "4,27,28")
        assertTrue(area.contains("Naturschutzgebiet"));assertTrue(area.contains("ESSA"));assertTrue(area.contains("PSSA"))
        assertEquals("3.1415926535897931", d.value("VALSOU","3.1415926535897931"))
        assertEquals("unmapped, raw", d.value("ZZZZZZ","unmapped, raw"))
    }
    @Test fun protectedClassificationDoesNotTurnOtherAreaTypesIntoNatureReserves() {
        assertEquals("Schutzgebiet",objectKind(obj("RESARE",mapOf("CATREA" to "1,28"))))
        assertEquals("Beschränktes Gebiet",objectKind(obj("RESARE",mapOf("CATREA" to "1"))))
        assertEquals("Militärisches Übungsgebiet",objectKind(obj("MIPARE",emptyMap())))
        assertEquals("Vorsichtsgebiet",objectKind(obj("CTNARE",emptyMap())))
    }
    @Test fun restrictionsBeyondLegacyRegistryKeepTheirStandardMeaningAndRawCode() {
        val d = dictionary()
        assertEquals("Einleitungen eingeschränkt (17) · Anlanden verboten (26)", d.value("RESTRN", "17,26"))
        val extended = d.value("RESTRN", (17..27).joinToString(","))
        assertFalse(extended.contains("Unbekannter Code"))
        assertTrue(extended.contains("Bohrungen verboten (20)"))
        assertTrue(extended.contains("Bohrungen eingeschränkt (21)"))
        assertEquals("Schwimmend (7)", d.value("WATLEV", "7"))
    }
    @Test fun csvRegistryKeepsQuotedCommasAndEscapedQuotes() {
        assertEquals(listOf("1","a, b", "c\"d"),csvRow("1,\"a, b\",\"c\"\"d\""))
    }
}

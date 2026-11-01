package com.no5.helm.ui

import com.no5.helm.chart.ChartPresentation
import com.no5.helm.chart.ChartStyles
import com.no5.helm.chart.ChartSymbolSet
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

class ChartStylePreviewPaletteTest {
    @Test fun everySelectableStyleHasAnExactPackagedPaletteAndSourceHash() {
        assertEquals(ChartStyles.all.map { it.id }.toSet(), ChartStylePreviewPalettes.all.keys)
        val assets = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { parent -> listOf(File(parent, "chart-engine/src/main/assets"), File(parent, "map/chart-engine/src/main/assets")) }
            .flatten().firstOrNull { File(it, "presentation/manifest.json").isFile }
        assertNotNull("Packaged presentation sources must be available for the palette contract", assets)
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        ChartStyles.all.forEach { style ->
            val palette = ChartStylePreviewPalettes.require(style.id)
            val file = File(assets, palette.sourceAsset)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("Source changed for ${style.id}; refresh its swatch", palette.sourceSha256, digest)
            val root = factory.newDocumentBuilder().parse(file).documentElement
            assertEquals(style.id, root.getAttribute("style"))
            val colors = root.getElementsByTagName("color")
            val values = (0 until colors.length).associate { index ->
                val element = colors.item(index) as org.w3c.dom.Element
                element.getAttribute("name") to (0xff000000.toInt() or
                    (element.getAttribute("r").toInt() shl 16) or
                    (element.getAttribute("g").toInt() shl 8) or element.getAttribute("b").toInt())
            }
            val expected = mapOf("DEPDW" to palette.deep, "DEPMD" to palette.middle,
                "DEPMS" to palette.shallow, "DEPVS" to palette.veryShallow, "LANDA" to palette.land,
                "CSTLN" to palette.coast, "DEPCN" to palette.contour, "CHBLK" to palette.ink)
            expected.forEach { (name, color) -> assertEquals("${style.id}: $name", color, values[name]) }
            assertEquals(root.getAttribute("coastWidth").toFloat(), palette.coastWidth, 0f)
        }
    }

    @Test fun selectingS01RestoresRequiredSymbolsAndDropsUnsupportedRelief() {
        val candidate = ChartPresentation("prism", ChartSymbolSet.NO5, true)
            .withStyle(ChartStyles.require("lagoon-sculpt"))
        candidate.validate()
        assertEquals(ChartSymbolSet.R04, candidate.symbols)
        assertFalse(candidate.landRelief)
    }

    @Test fun compatibleStyleRetainsSymbolAndReliefChoices() {
        val candidate = ChartPresentation("prism", ChartSymbolSet.BIG, true)
            .withStyle(ChartStyles.require("paper"))
        candidate.validate()
        assertEquals(ChartSymbolSet.BIG, candidate.symbols)
        assertTrue(candidate.landRelief)
    }
}

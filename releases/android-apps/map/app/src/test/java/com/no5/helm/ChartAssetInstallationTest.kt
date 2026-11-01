package com.no5.helm

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartAssetInstallationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun warmStartDoesNotEnumerateOrOverwriteResourcesOrImportedData() {
        val directory = temp.newFolder()
        val imported = File(directory, "charts/user-chart.senc").apply { parentFile.mkdirs(); writeText("user") }
        ChartAssetInstallation.install(directory, "apk1", { if (it.isEmpty()) listOf("style.xml", "charts") else emptyList() }, { "first".toByteArray() })
        File(directory, "style.xml").writeText("installed")
        ChartAssetInstallation.install(directory, "apk1", { error("Warm start must not scan assets") }, { error("Warm start must not read assets") })
        assertEquals("installed", File(directory, "style.xml").readText())
        assertEquals("user", imported.readText())
    }
    @Test fun failedUpdateIsRetriedAndOnlyChangedPackagedResourcesAreReplaced() {
        val directory = temp.newFolder()
        val list: (String) -> List<String> = { if (it.isEmpty()) listOf("one.xml", "two.csv") else emptyList() }
        ChartAssetInstallation.install(directory, "apk1", list, { it.toByteArray() })
        val original = File(directory, "two.csv").readBytes()
        assertThrows(IllegalStateException::class.java) {
            ChartAssetInstallation.install(directory, "apk2", list) { if (it == "two.csv") error("read failure") else "updated".toByteArray() }
        }
        assertArrayEquals(original, File(directory, "two.csv").readBytes())
        var reads = 0
        ChartAssetInstallation.install(directory, "apk2", list) { reads++; "updated".toByteArray() }
        assertEquals(2, reads)
        assertEquals("updated", File(directory, "two.csv").readText())
    }
}

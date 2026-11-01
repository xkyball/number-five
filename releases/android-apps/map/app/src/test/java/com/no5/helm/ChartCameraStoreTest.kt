package com.no5.helm

import com.no5.helm.chart.ChartViewport
import com.no5.helm.library.ChartSelection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartCameraStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun exactSelectionRestoresCameraButForeignOrChangedSelectionDoesNot() {
        val directory = temp.newFolder()
        val selection = ChartSelection("package-one", listOf("B", "A"))
        val view = ChartViewport(latitude = -33.9, longitude = 18.4, metresToPixels = .24, rotationRadians = .15)
        ChartCameraStore(directory).save(selection, view)
        val restarted = ChartCameraStore(directory)
        assertEquals(view, restarted.load(selection.copy(cells = listOf("A", "B"))))
        assertNull(restarted.load(selection.copy(packageId = "package-two")))
        assertNull(restarted.load(selection.copy(cells = listOf("A"))))
    }
    @Test fun invalidCameraCannotOverwritePreviousValidRecord() {
        val store = ChartCameraStore(temp.newFolder())
        val selection = ChartSelection("one", listOf("cell")); val view = ChartViewport()
        store.save(selection, view)
        assertThrows(IllegalArgumentException::class.java) { store.save(selection, view.copy(metresToPixels = Double.NaN)) }
        assertEquals(view, store.load(selection))
    }
}

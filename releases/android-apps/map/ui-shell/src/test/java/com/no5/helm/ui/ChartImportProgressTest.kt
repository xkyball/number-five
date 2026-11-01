package com.no5.helm.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.library.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
class ChartImportProgressTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val progress = mutableStateOf(ImportProgress())
    private val busy = mutableStateOf(true)
    private var cancels = 0
    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().windowFocusChanged(true)
        activity.get().setContent { MaterialTheme { Column { ChartImportProgress(progress.value, busy.value) { cancels++ } } } }
        focusHostInputWindow(activity.get(), compose)
    }
    @After fun cleanup() { activity.pause().stop().destroy() }
    private fun change(value: ImportProgress, running: Boolean = true) {
        compose.runOnIdle { progress.value = value; busy.value = running }
    }
    @Test fun unknownSourceTotalIsIndeterminateWithActualBytesAndWorkingCancel() {
        change(ImportProgress(ImportPhase.READING, currentFile = "large.zip", processedBytes = 65536))
        compose.onNodeWithText("Einlesen").assertIsDisplayed()
        compose.onNodeWithText("Datei: large.zip").assertIsDisplayed()
        compose.onNodeWithText("64,0 KiB · Gesamtgröße unbekannt verarbeitet · diese Phase").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("chart-import-cancel").performClick(); assertEquals(1, cancels)
    }
    @Test fun knownExtractionTotalUsesOnlyPhaseByteFractionAndShowsCatalogPath() {
        change(ImportProgress(ImportPhase.EXTRACTING, currentFile = "ENC_ROOT/catalog.031", processedFiles = 31, totalFiles = 90,
            processedBytes = 1024, totalBytes = 4096, currentFileBytes = 512, currentFileTotalBytes = 2048))
        compose.onNodeWithText("31 / 90 Dateien verarbeitet").assertIsDisplayed()
        compose.onNodeWithText("Datei: ENC_ROOT/catalog.031").assertIsDisplayed()
        compose.onNode(SemanticsMatcher("phase fraction") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 0.25f
        }).assertExists()
        compose.onNodeWithText("Gesamtfortschritt unbekannt", substring = true).assertIsDisplayed()
    }
    @Test fun nativePhaseIsIndeterminateEvenWhenCellCountIsKnownAndUiCanCancel() {
        change(ImportProgress(ImportPhase.INDEXING, currentFile = "US5BOSCC.000", processedFiles = 0, totalFiles = 1500))
        compose.onNodeWithText("Indexieren").assertIsDisplayed()
        compose.onNodeWithText("0 / 1500 Zellen abgeschlossen").assertIsDisplayed()
        compose.onNodeWithText("Zelle 1 / 1500 wird geprüft und indexiert").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("chart-import-cancel").performClick(); assertEquals(1, cancels)
    }
    @Test fun cancellationWaitAndTerminalStateAreDistinctAndDoNotOfferDuplicateCancel() {
        change(ImportProgress(ImportPhase.INDEXING, activity = ImportActivity.CANCELLING))
        compose.onNodeWithText("Abbruch angefordert").assertIsDisplayed()
        compose.onNodeWithText("Warte auf die laufende Datei/Operation", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chart-import-cancel").assertDoesNotExist()
        change(progress.value.copy(activity = ImportActivity.CANCELLED), false)
        compose.onNodeWithText("Import abgebrochen").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
    @Test fun errorRetainsPhaseCurrentFileAndMessageWithoutActiveSpinner() {
        change(ImportProgress(ImportPhase.CHECKING, currentFile = "US5BOSCC.000", activity = ImportActivity.FAILED, error = "DSID fehlt"), false)
        compose.onNodeWithText("Import fehlgeschlagen · Prüfen").assertIsDisplayed()
        compose.onNodeWithText("Datei: US5BOSCC.000").assertIsDisplayed()
        compose.onNodeWithTag("chart-import-error").assertTextEquals("DSID fehlt")
        compose.onNodeWithTag("chart-import-cancel").assertDoesNotExist()
    }
    @Test fun savingDisablesCancellationAndSuccessfulImportHasNoSpinner() {
        change(ImportProgress(ImportPhase.SAVING))
        compose.onNodeWithTag("chart-import-cancel").assertDoesNotExist()
        change(progress.value.copy(activity = ImportActivity.COMPLETED), false)
        compose.onNodeWithText("Import abgeschlossen").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }
}

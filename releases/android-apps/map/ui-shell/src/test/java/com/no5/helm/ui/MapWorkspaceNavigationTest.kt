package com.no5.helm.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.header.artwork.No5WorkspaceDiagnostics
import com.no5.header.artwork.No5WorkspaceTextFit
import com.no5.helm.library.*
import com.no5.preparation.ui.*
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapWorkspaceNavigationTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var host:ActivityController<ComponentActivity>
    private val shell=mutableStateOf(ShellState(destination=Destination.SETTINGS,
        chart=ChartUiState(status=DataStatus(Availability.READY,"EXPLICIT_UI_FIXTURE"))))
    private val epoch=mutableStateOf(0)
    private val measurement=mutableStateOf(false)
    private var positionOpens=0;private var styleOpens=0;private var rendererOpens=0;private var gatewayOpens=0
    private val stored=ChartPackage("11111111-1111-1111-1111-111111111111","OBSERVED_PACKAGE_NAME",1,
        listOf(ChartCell("A",1,0,"A.000","20261010",emptyMap(),selectionExtent=ChartSelectionExtent(42.2,42.3,-71.8,-71.7))))
    private val actions=object:ChartLibraryActions {
        override val state=MutableStateFlow(ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored),ChartSelection(stored.id,listOf("A")))))
        override val supportsResidentSelection=true
        override val supportsLibraryManagement=true
        val imports=mutableListOf<Uri>();var cancels=0;var mutations=0
        override fun importPackage(uri:Uri){
            imports+=uri
            if(!measurement.value)state.value=state.value.copy(busy=true,importProgress=ImportProgress(phaseStartedNanos=100L))
        }
        override fun activate(selection:ChartSelection){mutations++}
        override fun cancelImport(){cancels++;state.value=state.value.copy(importProgress=state.value.importProgress?.copy(activity=ImportActivity.CANCELLING))}
        override fun deactivate(selection:ChartSelection){mutations++}
        override fun deletePackage(packageId:String){mutations++}
        override fun updatePackage(packageId:String,uri:Uri){mutations++}
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun root()=host.get().window.decorView
    private fun native(tag:String)=all(root()).singleOrNull{it.tag==tag}
        ?:error("Missing native $tag; current targets: ${all(root()).mapNotNull{it.tag}.filter{it.toString().startsWith("no5-")}}")
    private fun tap(tag:String){ui.runOnIdle{native(tag).also{assertTrue("Disabled native action: $tag",it.isEnabled)}.performClick()};ui.waitForIdle()}
    private fun launch(font:Float=1f){
        RuntimeEnvironment.setFontScale(font)
        host=Robolectric.buildActivity(ComponentActivity::class.java)
        host.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        host.setup().visible().windowFocusChanged(true)
        host.get().setContent{key(epoch.value){No5Shell(shell.value,{event->
            if(event==ShellEvent.TogglePanel)shell.value=shell.value.copy(panelExpanded=!shell.value.panelExpanded)
        },workspacePanels=true,rendererSlot={Box(it)},panelContent={_,modifier->
            MapSettingsWorkspace(SettingsState(),{},shell.value.redNight,{
                shell.value=shell.value.copy(panelExpanded=false)
            },{positionOpens++},{styleOpens++},{rendererOpens++},{gatewayOpens++},null,
                library={back->ChartLibraryPanel(actions,measurement.value,workspace=true,onBack=back)},
                layers={back->MapLayersWorkspace(LayersState(),{},shell.value.redNight,back,Modifier.fillMaxSize(),s52Supported=false){}},
                displayContent={},diagnosticsContent={},modifier=modifier)
        })}}
        ui.waitForIdle()
    }
    @After fun finish(){
        No5WorkspaceDiagnostics.observe=null
        if(::host.isInitialized)host.pause().stop().destroy()
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun sortedNativeSettingsReachRealTargetsAndNeverReduceTheRendererArea(){
        launch()
        val before=ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(2000f,before.width,0f);assertEquals(1070f,before.height,0f)
        tap("no5-row-map-settings-position");assertEquals(1,positionOpens)
        tap("no5-row-map-settings-appearance")
        tap("no5-row-map-appearance-style");assertEquals(1,styleOpens)
        tap("no5-workspace-back")
        tap("no5-row-map-settings-diagnostics")
        tap("no5-row-map-diagnostics-renderer");assertEquals(1,rendererOpens)
        tap("no5-row-map-diagnostics-gateway");assertEquals(1,gatewayOpens)
        ui.runOnIdle{assertFalse(native("no5-row-map-diagnostics-info").isEnabled)}
        tap("no5-workspace-back")
        tap("no5-row-map-settings-library")
        tap("no5-row-chart-library-packages")
        tap("no5-row-chart-package-select-${stored.id}")
        focusHostInputWindow(host.get(),ui)
        ui.runOnIdle{assertNotNull(native("no5-field-chart-region-filter"))}
        tap("no5-action-chart-region-close")
        focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("map-library-packages").assertIsDisplayed()
        tap("no5-workspace-back")
        ui.onNodeWithTag("map-library-library").assertIsDisplayed()
        tap("no5-workspace-back")
        ui.onNodeWithTag("map-settings-menu").assertIsDisplayed()
        assertEquals(before,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle{shell.value=shell.value.copy(destination=Destination.CHART,panelExpanded=false)}
        assertEquals(before,ui.onNodeWithTag("renderer-slot").fetchSemanticsNode().boundsInRoot)
        assertEquals(0,actions.mutations);assertTrue(actions.imports.isEmpty())
    }
    @Test fun actualDocumentPickerAndObservedImportDoNotReuseARefusedOldOperation(){
        launch()
        ui.runOnIdle{actions.state.value=actions.state.value.copy(importProgress=ImportProgress(activity=ImportActivity.COMPLETED,phaseStartedNanos=1L))}
        tap("no5-row-map-settings-library");tap("no5-row-chart-library-import")
        tap("no5-action-chart-package-import")
        val refused=shadowOf(host.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,refused.intent.action)
        ui.runOnIdle{
            measurement.value=true
            host.get().activityResultRegistry.dispatchResult(refused.requestCode,Activity.RESULT_OK,Intent().setData(Uri.parse("content://explicit.test/refused.zip")))
        }
        ui.onNodeWithTag("map-import-observed").assertDoesNotExist()
        ui.runOnIdle{assertFalse(native("no5-action-chart-package-import").isEnabled);measurement.value=false}
        tap("no5-action-chart-package-import")
        val accepted=shadowOf(host.get()).nextStartedActivityForResult
        ui.runOnIdle{host.get().activityResultRegistry.dispatchResult(accepted.requestCode,Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://explicit.test/accepted.zip")))}
        ui.onNodeWithTag("map-import-observed").assertIsDisplayed()
        val original=actions.state.value.snapshot
        ui.runOnIdle{actions.state.value=actions.state.value.copy(importSourceLabel="my observed archive.zip",importProgress=ImportProgress(phase=ImportPhase.INDEXING,
            currentFile="OBSERVED001.000",processedFiles=2,totalFiles=7,phaseStartedNanos=2L))}
        ui.onNodeWithTag("chart-import-source").assertTextEquals("my observed archive.zip")
        ui.onNodeWithTag("chart-import-file").assertTextEquals("OBSERVED001.000")
        ui.onNodeWithTag("no5-action-chart-import-cancel").assertIsEnabled().performClick()
        assertEquals(1,actions.cancels)
        ui.onNodeWithTag("no5-action-chart-import-cancel").assertDoesNotExist()
        ui.onNodeWithTag("chart-import-phase").assertTextEquals(No5Text.ui("import.state.cancelling"))
        ui.runOnIdle{actions.state.value=actions.state.value.copy(busy=false,importProgress=actions.state.value.importProgress?.copy(activity=ImportActivity.CANCELLED))}
        ui.onNodeWithTag("chart-import-phase").assertTextEquals(No5Text.ui("Import abgebrochen"))
        assertEquals(original,actions.state.value.snapshot);assertEquals(0,actions.mutations)
    }
    @Test fun nativeSelectionUsesLocalDraftAndSafeDecisionBeforeClosing(){
        launch();tap("no5-row-map-settings-library");tap("no5-row-chart-library-packages")
        tap("no5-row-chart-package-select-${stored.id}")
        tap("no5-row-chart-zone-42:-72")
        tap("no5-row-chart-cell-A")
        ui.runOnIdle{assertFalse(native("no5-row-chart-cell-A").isSelected)}
        tap("no5-action-chart-region-return")
        ui.runOnIdle{assertFalse(native("no5-action-chart-package-activate").isEnabled)}
        tap("no5-action-chart-region-close");focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("unsaved-changes-dialog").assertIsDisplayed()
        ui.onNodeWithTag("unsaved-changes-save").assertIsNotEnabled()
        ui.onNodeWithTag("unsaved-changes-continue").performClick();focusHostInputWindow(host.get(),ui)
        tap("no5-row-chart-zone-42:-72")
        ui.runOnIdle{assertFalse(native("no5-row-chart-cell-A").isSelected)}
        tap("no5-action-chart-region-return");tap("no5-action-chart-region-close");focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("unsaved-changes-discard").performClick();focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("map-library-packages").assertIsDisplayed();assertEquals(0,actions.mutations)
        assertEquals(listOf("A"),actions.state.value.snapshot.active!!.cells)
    }
    @Test fun nativeTermsAndManagementRetainModelTruthAndRequireDeleteConfirmation(){
        launch();tap("no5-row-map-settings-library");tap("no5-row-chart-library-packages")
        tap("no5-row-chart-package-select-${stored.id}");tap("no5-row-chart-region-terms")
        focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("chart-terms").assertIsDisplayed()
        ui.onNodeWithTag("chart-noaa-terms").performClick()
        ui.onNodeWithTag("chart-noaa-terms").assertIsSelected()
        ui.onNodeWithTag("chart-noaa-terms").performClick()
        ui.onNodeWithTag("chart-noaa-terms").assertIsNotSelected()
        ui.onNodeWithTag("chart-terms-close").performClick();focusHostInputWindow(host.get(),ui)
        tap("no5-action-chart-region-close")
        tap("no5-row-chart-package-manage-${stored.id}")
        tap("no5-row-chart-package-delete");focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("chart-package-delete-dialog").assertIsDisplayed();assertEquals(0,actions.mutations)
        ui.onNodeWithTag("chart-package-delete-cancel").performClick();focusHostInputWindow(host.get(),ui)
        assertEquals(0,actions.mutations)
        tap("no5-row-chart-package-delete");focusHostInputWindow(host.get(),ui)
        ui.onNodeWithTag("chart-package-delete-confirm").performClick();focusHostInputWindow(host.get(),ui)
        assertEquals(1,actions.mutations)
        // The request alone never fabricates an empty or inactive package.
        assertEquals(listOf("A"),actions.state.value.snapshot.active!!.cells)
        ui.runOnIdle{assertTrue(native("no5-row-chart-package-deactivate").isEnabled)
            actions.state.value=actions.state.value.copy(snapshot=ChartLibrarySnapshot())}
        ui.runOnIdle{assertFalse(native("no5-row-chart-package-deactivate").isEnabled);assertFalse(native("no5-row-chart-package-update").isEnabled)}
        tap("no5-action-chart-management-close")
        ui.onNodeWithTag("map-library-packages").assertIsDisplayed()
    }
    @Test fun nativeMenuTextAndActionsFitAtNormalFont()=textMatrix(1f)
    @Test fun nativeMenuTextAndActionsFitAtLargeFont()=textMatrix(1.25f)
    private fun textMatrix(font:Float){
        launch(font)
        val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
        for(language in listOf("de","en","af"))for(night in listOf(false,true)){
            ui.runOnIdle{UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);shell.value=shell.value.copy(redNight=night);epoch.value++}
            for(page in listOf("settings","library","import")){
                if(page=="library")tap("no5-row-map-settings-library")
                if(page=="import")tap("no5-row-chart-library-import")
                ui.runOnIdle{
                    fits.clear();val view=root()
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                    val folder=File(System.getProperty("no5.renderOutputDir"),"map-workspaces-api${RuntimeEnvironment.getApiLevel()}").apply{mkdirs()}
                    File(folder,"$page-$language-$night-$font.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                    assertTrue(fits.isNotEmpty());fits.filterNot{it.dynamic}.forEach{fit->
                        assertEquals(font,fit.fontScale,0f);assertEquals(fit.text,fit.drawn)
                        assertTrue("Native fixed label clipped: $fit",fit.width<=fit.available+.01f)
                    }
                    all(view).filter{it.tag?.toString()?.startsWith("no5-row-")==true||it.tag?.toString()?.startsWith("no5-action-")==true}.forEach{v->
                        assertTrue("Native target height at xhdpi",v.height>=96);assertTrue(v.width>=96)
                    }
                }
            }
        }
    }
}

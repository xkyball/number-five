package com.no5.helm.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.chart.ChartPresentation
import com.no5.helm.chart.PresentationProfiles
import com.no5.helm.library.*
import com.no5.helm.model.*
import com.no5.preparation.ui.*
import com.no5.ui.No5LocaleScope
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each case renders the actual production body, never copied body strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapDialogBodiesLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>
    private var originalFont = 1f
    private var originalLanguage = "de"

    private enum class Body {
        COORDINATE, COORDINATE_CLOSE, ROUTE_CLOSE, ROUTE_REVIEW, ROUTE_STOP,
        MOB, MESSAGE, APP_SWITCHER, GPS, GPS_LEGACY,
        STYLE_DAY, STYLE_NIGHT, STYLE_GRAY, STYLE_CLOSE,
        REGION, REGION_ACTIVE, REGION_CLOSE, MANAGEMENT, MANAGEMENT_ACTIVE, DELETE_CONFIRMATION, DELETE_ACTIVE,
    }

    @After fun close() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), originalLanguage)
        RuntimeEnvironment.setFontScale(originalFont)
    }

    @Test fun actualProductionBodiesFitAllLanguagesAndDayNightAtNormalFont() = matrix(1f)
    @Test fun actualProductionBodiesFitAllLanguagesAndDayNightAtLargeFont() = matrix(1.25f)

    private fun matrix(fontScale: Float) {
        originalFont = RuntimeEnvironment.getFontScale()
        originalLanguage = UiLanguage.current(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.setFontScale(fontScale)
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        val language = mutableStateOf("de")
        val night = mutableStateOf(false)
        val body = mutableStateOf(Body.COORDINATE)
        val epoch = mutableStateOf(0)
        val points = FrozenList.copyOf((0..14).map { RoutePoint(42.0+it*.001, -71.0, "point$it", "SYNTHETIC waypoint$it") })
        val route = RouteDocument("synthetic-route", "SYNTHETIC route preserved", points, revision = 2147483647L)
        val coordinate = CoordinateEntry(latitude = "42.001", longitude = "-71.002", name = "SYNTHETIC waypoint",
            error = "Speichern fehlgeschlagen: HTTP_503 · synthetic retained draft")
        val coordinateState = mutableStateOf(RoutesState(coordinate=coordinate))
        val profiles = PresentationProfiles()
        val pack = ChartPackage("11111111-1111-1111-1111-111111111111", "NOAA · SYNTHETIC fixture", 1,
            (0..23).map { i -> ChartCell("C%03d".format(i), 1, 0, "C%03d.000".format(i), "20261018", emptyMap(), false,
                ChartSelectionExtent(42.0+i/8*.2,42.1+i/8*.2,-71.8,-71.7)) })
        val actions = object : ChartLibraryActions {
            override val state = MutableStateFlow(ChartLibraryUiState(ChartLibrarySnapshot(listOf(pack))))
            override val supportsLibraryManagement = true
            override fun importPackage(uri: Uri) = Unit
            override fun activate(selection: ChartSelection) = Unit
            override fun cancelImport() = Unit
        }
        val gpsSource = PositionCandidate("synthetic.gps.1", "SYNTHETIC GPS source", "synthetic", PositionStatus.LIVE, 1792310000000L, GeoPoint(42.0,-71.0))
        activity.get().setContent {
            No5LocaleScope(language.value) {
                key(epoch.value) {
                    No5ControlTheme(night.value) {
                        when (body.value) {
                            Body.COORDINATE -> RoutesDialogs(coordinateState.value, { coordinateState.value=reduceRoutes(coordinateState.value,it) })
                            Body.COORDINATE_CLOSE -> RoutesDialogs(RoutesState(coordinate=coordinate,coordinateCloseDialog=true), {})
                            Body.ROUTE_CLOSE -> RoutesDialogs(RoutesState(draft=route,dirty=true,discardDialog=true,
                                message="Speichern fehlgeschlagen: HTTP_503 · "+"synthetic retained draft. ".repeat(100)), {})
                            Body.ROUTE_REVIEW -> RoutesDialogs(RoutesState(review=route,reviewEntryLeg=0,freshFix=true,mode=RouteNavigationMode.MANUAL_GUIDANCE), {})
                            Body.ROUTE_STOP -> RoutesDialogs(RoutesState(active=route,stopDialog=true), {})
                            Body.MOB -> No5Shell(ShellState(redNight=night.value,mobDialog=true,simulated=false,chart=ChartUiState(ownFixValid=true)),{},rendererSlot={Box(it)})
                            Body.MESSAGE -> No5Shell(ShellState(redNight=night.value,message="SYNTHETIC diagnostic body · "+"retained model data. ".repeat(150)),{},rendererSlot={Box(it)})
                            Body.APP_SWITCHER -> No5AppSwitcher(night.value,{})
                            Body.GPS -> MapPositionDialog(MapPositionState(sources=PositionSources(primarySourceId=gpsSource.sourceId,total=1,sources=listOf(gpsSource),combinationAvailable=true,combinationReason="SOURCE_DISAGREEMENT")),{}, {_,_->})
                            Body.GPS_LEGACY -> MapPositionDialog(MapPositionState(legacy=true),{}, {_,_->})
                            Body.STYLE_DAY, Body.STYLE_CLOSE -> ChartStyleSettings(profiles.day,profiles,false,{}, {})
                            Body.STYLE_NIGHT -> ChartStyleSettings(profiles.night,profiles,false,{}, {})
                            Body.STYLE_GRAY -> ChartStyleSettings(profiles.gray,profiles,false,{}, {})
                            Body.REGION, Body.REGION_ACTIVE, Body.REGION_CLOSE, Body.MANAGEMENT, Body.MANAGEMENT_ACTIVE, Body.DELETE_CONFIRMATION, Body.DELETE_ACTIVE -> No5Panel("Karten") { ChartLibraryPanel(actions,false) }
                        }
                    }
                }
            }
        }
        for (lang in listOf("de","en","af")) for (isNight in listOf(false,true)) for (selected in Body.entries) {
            compose.runOnIdle {
                UiLanguage.acceptConfirmed(activity.get(),lang)
                language.value=lang;night.value=isNight;body.value=selected;coordinateState.value=RoutesState(coordinate=coordinate);epoch.value++
                actions.state.value=ChartLibraryUiState(ChartLibrarySnapshot(listOf(pack),
                    if(selected in listOf(Body.REGION_ACTIVE,Body.MANAGEMENT_ACTIVE,Body.DELETE_ACTIVE))ChartSelection(pack.id,pack.cells.map{it.name}) else null))
            }
            focusHostInputWindow(activity.get(),compose)
            fun translated(source:String)=No5Text.text(lang,source)
            fun tap(tag:String) {
                focusHostInputWindow(activity.get(),compose)
                val node = compose.onNodeWithTag(tag)
                val scrollParent = generateSequence(node.fetchSemanticsNode().parent) { it.parent }.any {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
                }
                if (scrollParent) node.performScrollTo()
                node.assertIsDisplayed().performClick()
                focusHostInputWindow(activity.get(),compose)
            }
            when(selected) {
                Body.STYLE_CLOSE -> {
                    compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-prism"))
                    compose.onNodeWithTag("chart-style-prism").performClick()
                    tap("chart-style-close")
                }
                Body.REGION, Body.REGION_ACTIVE, Body.REGION_CLOSE -> {
                    tap("chart-package-select-${pack.id}")
                    if(selected==Body.REGION_CLOSE) { tap("chart-select-none");tap("chart-region-close") }
                }
                Body.MANAGEMENT, Body.MANAGEMENT_ACTIVE, Body.DELETE_CONFIRMATION, Body.DELETE_ACTIVE -> {
                    tap("chart-package-manage-${pack.id}")
                    if(selected in listOf(Body.DELETE_CONFIRMATION,Body.DELETE_ACTIVE))tap("chart-package-delete")
                }
                else -> Unit
            }
            val (title,buttons)=when(selected) {
                Body.COORDINATE -> translated("Wegpunkt · WGS84 Dezimalgrad") to listOf(translated("Übernehmen"),translated("Abbrechen"))
                Body.COORDINATE_CLOSE,Body.ROUTE_CLOSE,Body.STYLE_CLOSE,Body.REGION_CLOSE -> translated("Änderungen speichern?") to listOf(translated("Speichern und schließen"),translated("Verwerfen"),translated("Weiter bearbeiten"))
                Body.ROUTE_REVIEW -> translated("Routenführung prüfen") to listOf(translated("Routenführung starten"),translated("Zurück"))
                Body.ROUTE_STOP -> translated("Routenführung beenden?") to listOf(translated("Führung beenden"),translated("Zurück"))
                Body.MOB -> No5Text.text(lang,"MOB · %s",translated("NICHT FREIGEGEBEN")) to listOf(translated("TESTMARKIERUNG SETZEN"),translated("ABBRECHEN"))
                Body.MESSAGE -> "NO 5" to listOf(translated("SCHLIESSEN"))
                Body.APP_SWITCHER -> translated("No5 Apps") to listOf(translated("Schließen"))
                Body.GPS,Body.GPS_LEGACY -> translated("GPS-Quelle und Folgen") to listOf(translated("Schließen"))
                Body.STYLE_DAY,Body.STYLE_NIGHT,Body.STYLE_GRAY -> translated("Kartenstil") to listOf(translated("Schließen"),translated("Anwenden"))
                Body.REGION -> No5Text.text(lang,"Kartenbereiche · %s",pack.label) to listOf(translated("Schließen"),translated("Auswahl aktivieren"))
                Body.REGION_ACTIVE -> No5Text.text(lang,"Kartenbereiche · %s",pack.label) to listOf(translated("Schließen"),translated("Auswahl ist aktiv"),translated("Gewählte deaktivieren"))
                Body.MANAGEMENT,Body.MANAGEMENT_ACTIVE -> translated("Kartenbestand verwalten") to listOf(translated("Schließen"),translated("Deaktivieren"),translated("Aktualisieren …"),translated("Paket löschen …"))
                Body.DELETE_CONFIRMATION,Body.DELETE_ACTIVE -> translated("Kartenbestand verwalten") to listOf(translated("Schließen"),translated("Abbrechen"),translated("Löschen bestätigen"))
            }
            MapDialogLayoutProbe(compose,activity.get(),selected.name.lowercase(),lang,fontScale,isNight)
                .verify(title,buttons,expectedTitleSp=if(selected in listOf(Body.STYLE_DAY,Body.STYLE_NIGHT,Body.STYLE_GRAY))20f else 16f)
            if(selected==Body.COORDINATE || selected==Body.REGION) {
                val value = if(selected==Body.COORDINATE) "SYNTHETIC edited waypoint" else "C0"
                compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextReplacement(value)
                val before = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
                compose.runOnIdle { com.no5.keyboard.SpecialKeyboardGeometry.publish(activity.get(),activity.get().packageName,android.graphics.Rect(353,760,1656,1200)) }
                compose.waitForIdle()
                val during = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
                assertEquals(before.left,during.left,0f);assertEquals(before.right,during.right,0f)
                assertEquals(before.bottom-440f,during.bottom,.1f)
                MapDialogLayoutProbe(compose,activity.get(),selected.name.lowercase()+"_keyboard",lang,fontScale,isNight).verify(title,buttons)
                compose.runOnIdle { com.no5.keyboard.SpecialKeyboardGeometry.publish(activity.get(),activity.get().packageName,null) }
                compose.waitForIdle()
                assertEquals(before,compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot)
                compose.onAllNodes(hasSetTextAction()).onFirst().assert(
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(value)))
                if(selected==Body.COORDINATE) assertEquals(value,coordinateState.value.coordinate!!.name)
            }
        }
    }
}

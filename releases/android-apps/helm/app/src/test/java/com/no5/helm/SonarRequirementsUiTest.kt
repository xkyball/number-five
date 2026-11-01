package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native host layout evidence only. Physical QF/OEM acceptance is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-rDE-w1000dp-h600dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarRequirementsUiTest {
    @get:Rule val ui=createComposeRule()
    private var now=1_000_000L
    private val source=ObservedSource()
    private val controller=SonarNativeController(source){now}
    @After fun dispose(){controller.dispose();UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}

    private fun nativeTargets(vararg keys:String){ui.runOnIdle{
        for(key in keys){val target=sonarWorkspaceViews().single{it.tag==key};val bounds=android.graphics.Rect()
            assertTrue("Hidden native $key",target.getGlobalVisibleRect(bounds));assertTrue("$key touch height $bounds",bounds.height()>=48)
            assertTrue("$key exceeds approved workspace body: $bounds",bounds.bottom<=529)
        }
    }}
    @Test fun pictureControlsFitWithoutScrollAndKeepThreeTabsAcrossLanguagesAndNight() {
        source.publish(now)
        var language by mutableStateOf("de");var night by mutableStateOf(false)
        controller.open()
        var capturedView:android.view.View?=null
        ui.setContent { capturedView=LocalView.current;key(language) { Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,night,controller)} } }
        val output=File(System.getProperty("no5.renderOutputDir"),"requirements-ui").apply{mkdirs()}
        for(lang in listOf("de","en","af")) for(isNight in listOf(false,true)) {
            ui.runOnIdle { UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=isNight;controller.open() }
            nativeTargets("no5-row-sonar-menu-image","no5-row-sonar-menu-analysis","no5-row-sonar-menu-diagnostics")
            assertEquals(listOf(SonarDrawerTab.DISPLAY,SonarDrawerTab.ANALYSIS,SonarDrawerTab.DIAGNOSTICS),SonarDrawerTab.entries)
            sonarImagePage(ui)
            nativeTargets("no5-row-sonar-image-auto","no5-row-sonar-range","no5-row-sonar-gain","no5-row-sonar-picture-details")
            ui.runOnIdle {
                val view=checkNotNull(capturedView);val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                try{view.draw(Canvas(image));File(output,"picture-$lang-${if(isNight)"night" else "day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
            }
        }
        ui.runOnIdle{controller.imageAuto(false)}
        sonarWorkspaceTap(ui,"no5-row-sonar-image-auto")
        ui.runOnIdle {assertEquals(SonarImageMode.AUTO,controller.snapshot.display.gainMode);assertEquals(SonarImageMode.AUTO,controller.snapshot.display.gammaMode);assertEquals(SonarImageMode.AUTO,controller.snapshot.display.thresholdMode)}
        sonarWorkspaceTap(ui,"no5-row-sonar-gain")
        ui.onNodeWithTag("Sonar GAIN Manual").performClick()
        ui.runOnIdle {assertEquals(SonarImageMode.MANUAL,controller.snapshot.display.gainMode);assertEquals(SonarImageMode.AUTO,controller.snapshot.display.gammaMode)}
    }

    @Test fun directKnobModeAndSingleMenuKeepSafeTargetsAcrossLanguagesAndNight() {
        source.publish(now)
        var language by mutableStateOf("de");var night by mutableStateOf(false)
        var capturedView:android.view.View?=null
        var expectedMenuLeft=Float.NaN
        ui.setContent {
            capturedView=LocalView.current
            expectedMenuLeft=with(LocalDensity.current){(931f*.5f).dp.roundToPx().toFloat()}
            key(language) { Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,night,controller)} }
        }
        val output=File(System.getProperty("no5.renderOutputDir"),"sonar-mode-ui").apply{mkdirs()}
        for(lang in listOf("de","en","af")) for(isNight in listOf(false,true)) {
            ui.runOnIdle { UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=isNight;controller.close() }
            ui.onNodeWithContentDescription(No5Text.ui("Sonar range controls")).assertDoesNotExist()
            ui.onNodeWithContentDescription(No5Text.ui("Sonar quick controls")).assertDoesNotExist()
            val menu=ui.onNodeWithTag("Sonar menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val mode=ui.onNodeWithTag("Sonar knob mode").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(expectedMenuLeft,menu.left,0f);assertEquals(193f,menu.width,0f)
            assertEquals(48f,menu.height,.01f);assertEquals(48f,mode.height,.01f)
            assertTrue("Menu and mode overlap",menu.right<mode.left)
            for(rect in listOf(menu,mode)) assertTrue("Touch target intersects physical knob",KnobGeometry.safe(rect.left*2,rect.top*2,rect.right*2,rect.bottom*2))
            ui.onNodeWithTag("Sonar menu").performClick()
            ui.onNodeWithTag("sonar-workspace-menu").assertIsDisplayed()
            ui.runOnIdle {controller.close()}
            for(expected in listOf(SonarKnobMode.SONAR,SonarKnobMode.AUDIO)) {
                ui.runOnIdle {if(controller.knobMode!=expected)controller.toggleKnobMode()}
                val modeNode=ui.onNodeWithTag("Sonar knob mode").assertIsDisplayed()
                modeNode.assertContentDescriptionEquals(No5Text.ui("KNÖPFE: %s",No5Text.ui(expected.name)))
                if(expected==SonarKnobMode.AUDIO)modeNode.assertIsSelected() else modeNode.assertIsNotSelected()
                ui.onNodeWithTag("sonar-workspace-menu").assertDoesNotExist()
                ui.runOnIdle {
                    val view=checkNotNull(capturedView)
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(image))
                    File(output,"mode-$lang-${if(isNight)"night" else "day"}-${expected.name.lowercase()}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle()
                }
                modeNode.performClick()
                ui.runOnIdle {assertNotEquals(expected,controller.knobMode);assertNull(controller.drawer)}
            }
        }
    }

    @Test fun mixedGeometryReliefFallsBackToTwoDimensionsWithoutChangingThePreference() {
        source.publish(now)
        controller.relief(true)
        controller.model.setAnalysis(SonarGeometry(80.0,SonarTxReference.HOST_VERIFIED),SonarBottom(),null,false,
            amplitude=SonarAmplitude(4095,12,true))
        controller.model.frame(SonarFrame(ShortArray(1024),Long.MAX_VALUE,now),pingSource=SonarPingSource.OWN)
        controller.refresh()
        assertFalse(controller.model.viewHistoryHasUniformGeometry())
        ui.setContent {Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        ui.onNodeWithTag("Sonar relief geometry fallback").assertDoesNotExist()
        ui.onNodeWithTag("Sonar status").assertIsDisplayed()
        ui.runOnIdle {controller.openStatusDetails()}
        ui.onNodeWithTag("Sonar fact Echo-Relief").performScrollTo().assertExists()
        ui.runOnIdle {assertTrue(controller.reliefEnabled);assertFalse(controller.reliefReady);assertEquals(2,controller.model.ring.count)}
    }

    @Test fun frozenProfileDiagnosticsKeepTheirOwnSamplesScaleAndGeometryWhileLiveDataAdvances() {
        source.publish(now,SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED,adcSamplePeriodUs=4.0),ShortArray(1024){100})
        controller.freeze()
        controller.model.setAnalysis(SonarGeometry(24.576,SonarTxReference.HOST_VERIFIED,adcSamplePeriodUs=2.0),SonarBottom(),null,false,
            amplitude=SonarAmplitude(65535,16,true))
        controller.model.frame(SonarFrame(ShortArray(1024){900},Long.MAX_VALUE,now+100),pingSource=SonarPingSource.OWN)
        controller.refresh();controller.open(SonarDrawerTab.DIAGNOSTICS)
        ui.setContent{Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        sonarWorkspaceTap(ui,"no5-row-sonar-geometry")
        fun fact(label:String,value:String) {
            ui.onNodeWithTag("Sonar fact $label").performScrollTo().assertContentDescriptionEquals("${No5Text.ui(label)}: $value")
        }
        fact("Ping-ID",(Long.MAX_VALUE-1).toString())
        fact("Displayprofil Min / Max","100 / 100");fact("Displayprofil Mittel","100.0")
        fact("Anzeige / Vollskala","1024 Bins / 4095");fact("ADC-Abstand","4.00 µs")
        fact("Anzeigebin-Abstand","${sonarNumber(3.072/1023,5)} m")
        ui.runOnIdle{assertEquals(Long.MAX_VALUE,controller.model.latest!!.pingId);assertEquals(900,controller.model.latest!!.sample(0));assertEquals(65535,controller.model.amplitudeFullScale)}
        sonarWorkspaceCloseSheet(ui);ui.runOnIdle{controller.open(SonarDrawerTab.DISPLAY)}
        sonarSmoothingSheet(ui)
        ui.onNodeWithTag("Sonar interpolation nearest").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("Sonar interpolation linear").performScrollTo().assertIsNotEnabled()
    }

    @Test fun supportedFontScaleKeepsImageControlsAndTouchTargetsInsidePanel() {
        source.publish(now);controller.open()
        ui.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}
            }
        }
        sonarImagePage(ui)
        nativeTargets("no5-row-sonar-image-auto","no5-row-sonar-gain","no5-row-sonar-picture-details")
        sonarWorkspaceTap(ui,"no5-row-sonar-gain")
        for(tag in listOf("Sonar GAIN Auto","Sonar GAIN Manual","Sonar GAIN value")){
            val rect=ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag touch target $rect",rect.height>=48f);assertTrue("$tag exceeds approved window $rect",rect.bottom<=529f)
        }
    }

    @Test fun longPressMenuRequestLeavesTheCurrentLeafAndSheetWithoutChangingThePing(){
        source.publish(now);controller.open(SonarDrawerTab.ANALYSIS)
        val original=controller.model.latest!!.samples.copyOf();val originalId=controller.model.latest!!.pingId
        val actor=SonarControlActor(controller);actor.observe()
        try{
            ui.setContent{Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
            sonarWorkspaceTap(ui,"no5-row-sonar-analysis-context")
            val action=actor.action(com.no5.control.NormalizedKnobInput.RIGHT_LONG_PUSH)
            var id=0L;ui.runOnIdle{id=action.submit(99,android.os.SystemClock.uptimeMillis());assertTrue(id>0)}
            ui.waitForIdle()
            ui.onNodeWithTag("sonar-workspace-menu").assertIsDisplayed()
            ui.onNodeWithTag("sonar-sheet-analysis-context").assertDoesNotExist()
            nativeTargets("no5-row-sonar-menu-image","no5-row-sonar-menu-analysis","no5-row-sonar-menu-diagnostics")
            ui.runOnIdle{
                assertEquals(SonarDrawerTab.DISPLAY,controller.drawer)
                assertEquals(originalId,controller.model.latest!!.pingId);assertArrayEquals(original,controller.model.latest!!.samples)
                val receipt=android.os.Parcel.obtain()
                try{assertTrue(action.writeResult(id,receipt));receipt.setDataPosition(0);assertEquals(1,receipt.readInt());assertEquals(org.lumina.control.commands.v1.CommandRing.State.APPLIED.ordinal,receipt.readInt())}finally{receipt.recycle()}
            }
        }finally{actor.close()}
    }

    @Test fun exactPingOptionIsExplicitSafeAndTranslatedWhileOldInterpolationAndReliefPreferencesStayInactive() {
        source.publish(now);controller.open()
        var language by mutableStateOf("de");var night by mutableStateOf(false)
        var capturedView:android.view.View?=null
        ui.setContent{capturedView=LocalView.current;key(language){Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,night,controller)}}}
        val out=File(System.getProperty("no5.renderOutputDir"),"exact-ping-option").apply{mkdirs()}
        for(lang in listOf("de","en","af"))for(isNight in listOf(false,true)) {
            ui.runOnIdle {
                UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=isNight
                controller.exactPingProfiles(false);controller.interpolation(true);controller.relief(true);controller.open()
            }
            sonarSmoothingSheet(ui)
            val title=mapOf("de" to "Echte Pingprofile","en" to "Actual ping profiles","af" to "Werklike pingprofiele").getValue(lang)
            val option=ui.onNodeWithTag("Sonar exact ping profiles").performScrollTo().assertIsDisplayed().assertContentDescriptionEquals(title).assertIsNotSelected()
            val rect=option.fetchSemanticsNode().boundsInRoot
            assertTrue(rect.height>=48f);assertTrue(rect.left>=172f&&rect.right<=828f);assertTrue(rect.top>=125f&&rect.bottom<=465f)
            option.performClick();option.assertIsSelected()
            ui.onNodeWithTag("Sonar interpolation nearest").performScrollTo().assertIsNotEnabled().assertIsNotSelected()
            ui.onNodeWithTag("Sonar interpolation linear").assertIsNotEnabled().assertIsNotSelected()
            sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-relief")
            ui.onNodeWithTag("Sonar 3D echo relief").performScrollTo().assertIsNotEnabled().assertIsNotSelected()
            ui.onNodeWithTag("Sonar 2D echogram").assertIsSelected()
            ui.runOnIdle {assertTrue(controller.linearInterpolation);assertTrue(controller.reliefEnabled);assertTrue(controller.snapshot.display.exactPingProfiles);controller.freeze()}
            sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-smoothing")
            ui.onNodeWithTag("Sonar exact ping profiles").performScrollTo().assertIsNotEnabled().assertIsSelected()
            ui.runOnIdle {controller.exactPingProfiles(false);assertTrue(controller.snapshot.display.exactPingProfiles);controller.freeze()}
            ui.runOnIdle {
                val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView;val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b));File(out,"$lang-${if(isNight)"night" else "day"}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            }
        }
    }

    @Test fun hostRateDemandRemainsWhileSonarVisibleAndLargeIdentifiersRemainWhole() {
        source.publish(now)
        controller.open(SonarDrawerTab.DIAGNOSTICS)
        source.detail.emit(SonarDiagnostics(source.identity,1,"test-session",Long.MAX_VALUE,now,SonarPingSource.OWN,
            listOf(SonarDiagnosticField("Betriebsdiagnose","physical_markers",Long.MAX_VALUE.toString())),available=true))
        ui.setContent{Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        assertTrue(source.diagnosticsDemand)
        sonarWorkspaceTap(ui,"no5-row-sonar-host")
        ui.onNodeWithContentDescription("${No5Text.ui("Diagnose-Ping")}: ${Long.MAX_VALUE}").assertExists()
        sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-action-sonar-tab-image")
        ui.runOnIdle {assertTrue(source.diagnosticsDemand)}
        sonarWorkspaceTap(ui,"no5-action-sonar-close")
        ui.runOnIdle {assertTrue(source.diagnosticsDemand);assertNull(controller.drawer);controller.screenVisible(false);assertFalse(source.diagnosticsDemand)}
    }

    @Test fun analysisAndDiagnosticsKeepWorstCaseValuesWholeInEveryLanguageAndTheme() {
        val uuid="12345678-1234-5678-9012-123456789012"
        val longReason="Gemessener Originalreferenzzustand – keine abgeleitete Batteriebehauptung. ".repeat(4).take(200)
        val longValue="Vollständiger technischer Diagnosewert: "+"ABCDEFGHIJKLMNOPQRST0123456789".repeat(24)
        val selection=SonarDisplaySelection(SonarPingSource.ALL,2)
        source.sourceState.emit(SonarSourceSelectionState(source.identity,1,true,selection))
        source.current.emit(SonarSourceStatus(source.identity,1,true,true,serverSessionId=uuid,serverGeneration=Long.MAX_VALUE,displaySelection=selection,
            observationReason=SonarObservationReason("ORIGINAL_REFERENCE_LOST","native_reference_lost",null,now,true,true)))
        source.packets.emit(SonarSourceFrame(source.identity,1,SonarFrame(ShortArray(1024){200},Long.MAX_VALUE-1,now),
            bottom=SonarBottom(200.0,.97,.88,SonarBottomSource.CFAR_TRACK_RULES_V1,Long.MAX_VALUE,"fish_like"),
            serverSessionId=uuid,geometry=SonarGeometry(10.0,SonarTxReference.HOST_VERIFIED),hostPackageVersion="0.4.10",
            presentation=SonarPresentation(Long.MAX_VALUE-1,SonarAnalysisQuality.LOCKED,longReason,"auto","auto_raw",true,true,300.0,false,
                listOf(SonarTrack(Long.MAX_VALUE,200.0,.97,"fish_like","rules_v1","direct_envelope"))),
            displaySelection=selection,pingSource=SonarPingSource.OWN))
        controller.freeze()
        source.filters.emit(SonarFilterState(source.identity,available=true,mode=SonarFilterMode.ON,effectiveMode="on",reason="live filter reason"))
        controller.open(SonarDrawerTab.ANALYSIS)
        var language by mutableStateOf("de");var night by mutableStateOf(false)
        var capturedView:android.view.View?=null
        ui.setContent {
            capturedView=LocalView.current
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                key(language){Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,night,controller)}}
            }
        }
        val output=File(System.getProperty("no5.renderOutputDir"),"requirements-ui").apply{mkdirs()}
        fun wholeValue(tag:String,expected:String) {
            val node=ui.onNodeWithTag(tag).performScrollTo().assertTextEquals(expected)
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
            assertEquals(1,layouts.single().lineCount)
            assertFalse("A horizontally readable value must not be ellipsized",layouts.single().isLineEllipsized(0))
            assertFalse("$tag width=${layouts.single().size.width} intrinsic=${layouts.single().multiParagraph.maxIntrinsicWidth} paragraph=${layouts.single().multiParagraph.width} value=$expected",layouts.single().didOverflowWidth)
            assertTrue("Last character must fit inside the measured text width",layouts.single().getBoundingBox(expected.lastIndex).right<=layouts.single().size.width)
            if(expected.length>24) {
                assertTrue(node.fetchSemanticsNode().boundsInRoot.height>=48f)
                val range=node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                if(range!=null && range.maxValue()>0) { node.performTouchInput {swipeLeft()};assertEquals(expected,node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text) }
            }
        }
        for(lang in listOf("de","en","af")) for(isNight in listOf(false,true)) {
            ui.runOnIdle {UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=isNight;controller.open(SonarDrawerTab.ANALYSIS)}
            sonarWorkspaceTap(ui,"no5-row-sonar-analysis-context")
            wholeValue("Sonar fact Ping-ID",(Long.MAX_VALUE-1).toString())
            wholeValue("Sonar fact Pingquelle",No5Text.ui("Eigene")) // Selection is ALL; this ping is OWN.
            wholeValue("Sonar fact Befundfilter · Wunsch/Wirkung","auto / auto_raw")
            wholeValue("Sonar fact Sitzung",uuid)
            sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-filter")
            wholeValue("Sonar fact Live-Filter · Wunsch/Wirkung","ON / on")
            sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-bottom")
            wholeValue("Sonar fact Analysegrund",longReason)
            wholeValue("Sonar fact Track-ID",Long.MAX_VALUE.toString())
            ui.runOnIdle {org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.let{view->val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image));File(output,"analysis-worst-$lang-${if(isNight)"night" else "day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}}
            sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-action-sonar-tab-diagnostics")
            ui.runOnIdle { source.detail.emit(SonarDiagnostics(source.identity,Long.MAX_VALUE,uuid,Long.MAX_VALUE,now,SonarPingSource.OWN,
                listOf(SonarDiagnosticField("Technische Details","Negativer Wert",Long.MIN_VALUE.toString(),"dB"),SonarDiagnosticField("Technische Details","Langer Wert",longValue)),available=true)) }
            sonarWorkspaceTap(ui,"no5-row-sonar-host")
            wholeValue("Sonar fact Diagnose-Ping",Long.MAX_VALUE.toString())
            ui.onNodeWithTag("Sonar details Technische Details").performScrollTo().performClick()
            wholeValue("Sonar fact Negativer Wert","${Long.MIN_VALUE}\u00a0dB")
            wholeValue("Sonar fact Langer Wert",longValue)
            ui.runOnIdle {org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.let{view->val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image));File(output,"diagnostics-worst-$lang-${if(isNight)"night" else "day"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}}
        }
    }

    @Test fun missingBottomKeepsButtonsAndDialogGeometryAndSelectedIntent() {
        source.publish(now);controller.bottomOverlay(true);controller.open(SonarDrawerTab.ANALYSIS)
        ui.setContent {Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        sonarWorkspaceTap(ui,"no5-row-sonar-bottom")
        val before=ui.onNodeWithTag("Sonar current bottom overlay").assertIsSelected().fetchSemanticsNode().boundsInRoot
        val tracksBefore=ui.onNodeWithTag("Sonar confirmed track overlays").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {
            controller.model.setBottom(SonarBottom(),false)
            source.capabilities.emit(SonarSourceCapabilities(source.identity))
            controller.refresh()
        }
        assertEquals(before,ui.onNodeWithTag("Sonar current bottom overlay").assertIsNotEnabled().assertIsSelected().fetchSemanticsNode().boundsInRoot)
        assertEquals(tracksBefore,ui.onNodeWithTag("Sonar confirmed track overlays").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("Sonar fact Analysegrund").assertExists()
        ui.runOnIdle {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {view.draw(Canvas(image));val out=File(System.getProperty("no5.renderOutputDir"),"quiet-depth").apply{mkdirs()};File(out,"bottom-missing.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
        }
    }

    @Test fun rangeSettingsStaySelectableWithoutDepthAndExposeQuietWindow() {
        source.publish(now);controller.open()
        ui.setContent {Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        sonarImagePage(ui);sonarWorkspaceTap(ui,"no5-row-sonar-range")
        val auto=ui.onNodeWithTag("Sonar auto range mode").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {controller.model.setBottom(SonarBottom(),true);controller.refresh()}
        ui.onNodeWithTag("Sonar auto range mode").performClick().assertIsSelected()
        assertEquals(auto,ui.onNodeWithTag("Sonar auto range mode").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("Sonar depth mode SMOOTHED").assertIsSelected()
        ui.onNodeWithTag("Sonar depth mode DIRECT").performScrollTo().performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(SonarDepthMode.DIRECT,controller.exportPresentationState().depthSettings.mode)}
        ui.runOnIdle {
            val view=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {view.draw(Canvas(image));val out=File(System.getProperty("no5.renderOutputDir"),"quiet-depth").apply{mkdirs()};File(out,"range-direct.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
        }
    }

    private class ObservedSource:SonarSourcePort {
        override val identity=SourceIdentity("explicit-test-sonar",SourceMode.PI,"EXPLICIT TEST")
        val current=MutableSourceFeed(SonarSourceStatus(identity))
        override val status:SourceFeed<SonarSourceStatus> get()=current
        override val capabilities=MutableSourceFeed(SonarSourceCapabilities(identity,bottomDetection=true,filterControl=true,sourceSelection=true,diagnostics=true))
        val packets=MutableSourceFeed<SonarSourceFrame?>(null)
        override val frames:SourceFeed<SonarSourceFrame?> get()=packets
        override val sourceState=MutableSourceFeed(SonarSourceSelectionState(identity,1,true,SonarDisplaySelection(SonarPingSource.OWN,1)))
        val filters=MutableSourceFeed(SonarFilterState(identity))
        override val filterState:SourceFeed<SonarFilterState> get()=filters
        val detail=MutableSourceFeed<SonarDiagnostics?>(null)
        override val diagnostics:SourceFeed<SonarDiagnostics?> get()=detail
        var diagnosticsDemand=false
        override fun setDiagnosticsVisible(visible:Boolean){diagnosticsDemand=visible}
        fun publish(time:Long,geometry:SonarGeometry=SonarGeometry(10.0,SonarTxReference.HOST_VERIFIED),raw:ShortArray=ShortArray(1024){(it%256).toShort()}) {
            current.emit(SonarSourceStatus(identity,1,true,true,serverSessionId="test-session",serverGeneration=1,displaySelection=SonarDisplaySelection(SonarPingSource.OWN,1)))
            packets.emit(SonarSourceFrame(identity,1,SonarFrame(raw,Long.MAX_VALUE-1,time),
                bottom=SonarBottom(200.0,.9,.8),serverSessionId="test-session",geometry=geometry,
                hostPackageVersion="0.4.10",displaySelection=SonarDisplaySelection(SonarPingSource.OWN,1),pingSource=SonarPingSource.OWN))
        }
        override fun start(request:SonarAcquisitionRequest)=SonarAcquisitionOutcome.Rejected(identity.sessionId,request.requestId,SonarRejection.UNSUPPORTED,"UNSUPPORTED")
        override fun stop(request:SonarAcquisitionRequest)=start(request)
        override fun tick()=Unit
        override fun close()=Unit
    }
}

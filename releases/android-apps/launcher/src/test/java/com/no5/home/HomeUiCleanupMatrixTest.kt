package com.no5.home

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.no5.navigation.AppNavigation
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

/** Reachable native HOME surfaces. Host Dialog layout does not claim actual IME/window-focus proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], qualifiers="w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUiCleanupMatrixTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
    private fun layout(view:View,width:Int=2000,height:Int=1200){shadowOf(Looper.getMainLooper()).idle();view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)}
    private fun fits(text:TextView){val value=text.text.toString();assertNotNull("No layout for $value",text.layout);assertTrue("Vertical text clipping: $value",text.layout.height<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom);for(line in 0 until text.lineCount){assertTrue("Horizontal text clipping: $value",text.layout.getLineMax(line)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+1f);assertEquals("Fixed label must not be ellipsized: $value",0,text.layout.getEllipsisCount(line))}}
    private fun capture(view:View,name:String){val directory=File(System.getProperty("no5.renderOutputDir"),"home-cleanup-matrix-api${RuntimeEnvironment.getApiLevel()}").apply {mkdirs()};val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);try{view.draw(Canvas(image));File(directory,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}}
    private fun button(view:View,key:String)=all(view).filterIsInstance<Button>().single {it.text.toString()==No5Text.text(context,key)}
    private fun matrix(action:(String,Boolean,Float)->Unit){for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){UiLanguage.acceptConfirmed(context,language);RuntimeEnvironment.setFontScale(font);action(language,night,font)}}
    @After fun reset(){UiLanguage.acceptConfirmed(context,"de");RuntimeEnvironment.setFontScale(1f)}
    @Test fun landingFixedControlsAndGatewayNoticeRemainReadableInEveryCombination(){matrix {language,night,font->
        Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().visible().windowFocusChanged(true).use {host->
            val activity=host.get();val root=activity.findViewById<ViewGroup>(android.R.id.content)
            activity.updateGatewayNotice("Gateway · Zugang in Lumina Control einrichten",false)
            for(height in listOf(1200,928)){
                layout(root,height=height)
                val controls=all(root).filterIsInstance<Button>().filter {it.isShown}
                assertTrue(controls.size>=5)
                controls.forEach {view->
                    fits(view);val rect=Rect();assertTrue(view.getGlobalVisibleRect(rect));val origin=IntArray(2);root.getLocationOnScreen(origin);rect.offset(-origin[0],-origin[1]);assertTrue("Offscreen action: $rect",Rect(0,0,2000,height).contains(rect));assertTrue(rect.width()>=96&&rect.height()>=96);if(height==1200)assertFalse(HomeStage.intersectsSafety(RectF(rect)))
                }
                capture(root,"landing-$language-${if(night)"night" else "day"}-font$font-h$height")
            }
            val report=HomeActivity::class.java.getDeclaredMethod("report",String::class.java).apply {isAccessible=true}
            for(message in listOf("App nicht installiert oder keine Startaktivität verfügbar.","App nicht mehr verfügbar.","App nicht verfügbar.","Android erlaubt diesen App-Start nicht.","Lumina Control ist nicht verfügbar.")){
                report.invoke(activity,message);layout(root)
                val notice=all(root).filterIsInstance<TextView>().single {it.accessibilityLiveRegion==View.ACCESSIBILITY_LIVE_REGION_POLITE&&it.text.toString()==No5Text.text(activity,message)}
                fits(notice);val bounds=Rect();assertTrue(notice.getGlobalVisibleRect(bounds));val origin=IntArray(2);root.getLocationOnScreen(origin);bounds.offset(-origin[0],-origin[1]);assertFalse(HomeStage.intersectsSafety(RectF(bounds)))
            }
            activity.updateGatewayNotice("Gateway · TLS und Status bestätigt",true);assertEquals(View.GONE,all(root).single {it.tag=="home-gateway-notice"}.visibility)
        }
    }}
    @Test fun emptyAppGridUsesTheActualLocaleCatalog(){matrix {language,night,font->
        Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().use {host->
            val activity=host.get();button(activity.window.decorView,"Alle Apps").performClick();val root=activity.findViewById<ViewGroup>(android.R.id.content);layout(root)
            val list=all(root).filterIsInstance<HomeWorkspaceView>().single()
            assertEquals(No5Text.text(activity,"Alle Apps")+". "+No5Text.text(activity,"Installierte Apps"),list.contentDescription)
            val empty=all(root).single{it.tag=="no5-row-home-apps-empty"}
            assertEquals(No5Text.text(activity,"Keine weiteren startbaren Apps installiert."),empty.contentDescription)
            assertFalse(empty.isClickable)
            capture(root,"apps-empty-$language-${if(night)"night" else "day"}-font$font")
            all(root).single{it.tag=="no5-workspace-back"}.performClick();assertFalse(activity.isFinishing)
        }
    }}
    @Test fun maintenanceAndCentralLanguageKeepConfirmedChoiceAndFixedActions(){matrix {language,night,font->
        Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().visible().windowFocusChanged(true).use {host->
            val activity=host.get();button(activity.window.decorView,"Wartung").performClick()
            val content=activity.findViewById<ViewGroup>(android.R.id.content);layout(content)
            val fits=mutableListOf<HomeTemplateTextFit>();HomeTemplateDiagnostics.observe={fits+=it}
            try {
                capture(content,"setup-$language-$night-$font")
                assertEquals(3,all(content).count{it.tag?.toString()?.startsWith("no5-row-")==true})
                all(content).single{it.tag=="no5-row-Sprache für alle Apps"}.performClick()
                val dialog=HomeActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(activity) as android.app.Dialog
                assertTrue(dialog.isShowing)
                val root=dialog.window!!.decorView;layout(root)
                val pane=all(root).single{it.tag=="home-language-dialog"}
                assertEquals(1120,pane.width);assertEquals(620,pane.height)
                val choices=all(root).filterIsInstance<RadioButton>();assertEquals(3,choices.size)
                assertEquals(language,choices.single{it.isChecked}.tag)
                choices.forEach{assertEquals(104,it.height);assertEquals(No5Text.text(activity,"Sprache für alle Apps")+": "+it.text,it.contentDescription)}
                val target=choices.first{it.tag!=language};target.isFocusableInTouchMode=true;target.requestFocus()
                assertEquals(language,choices.single{it.isChecked}.tag)
                target.performClick();assertEquals("Unacknowledged click is not a broker ACK",language,choices.single{it.isChecked}.tag)
                val observer=HomeActivity::class.java.getDeclaredField("uiObserver").apply{isAccessible=true}.get(activity) as com.no5.ui.No5UiClient.Observer
                observer.onUiError("UI_UNAVAILABLE")
                assertEquals(No5Text.text(activity,"Nicht bestätigt · bisherige Sprache bleibt aktiv"),all(root).filterIsInstance<TextView>().single{it.tag=="home-language-notice"}.text.toString())
                assertEquals(language,choices.single{it.isChecked}.tag)
                val close=all(root).single{it.tag=="home-language-close"}
                val scroll=all(root).filterIsInstance<ScrollView>().single();assertFalse(all(scroll).contains(close))
                assertEquals(96,close.height);assertTrue(close.bottom<=pane.height)
                capture(root,"language-error-$language-$night-$font")
                assertTrue(fits.isNotEmpty());fits.filterNot{it.dynamic}.forEach{fit->
                    assertEquals(fit.text,fit.drawn);assertTrue("Fixed native label clipped: $fit",fit.width<=fit.available+.01f);assertEquals(font,fit.fontScale,0f)
                }
                assertEquals(31f*font,fits.last{it.source==language}.fontPx,0f)
                close.performClick();assertFalse(dialog.isShowing)
                assertEquals(1,all(content).filterIsInstance<HomeWorkspaceView>().size)
                button(content,"Zurück").performClick();assertFalse(activity.isFinishing)
            } finally {HomeTemplateDiagnostics.observe=null}
        }
    }}
    @Test fun longExternalHomeNamesUseRealSubpageAndScrollableBody(){
        val homes=(0 until 20).map{index->ResolveInfo().apply{
            nonLocalizedLabel="External HOME fixture $index "+"long-name-".repeat(24)
            activityInfo=ActivityInfo().apply{packageName="test.externalhome$index";name="$packageName.MainActivity";enabled=true;exported=true;applicationInfo=ApplicationInfo().apply{packageName="test.externalhome$index";enabled=true}}
        }}
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),homes)
        matrix {language,night,font->
            Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().visible().windowFocusChanged(true).use {host->
                val activity=host.get();button(activity.window.decorView,"Wartung").performClick()
                var root=activity.findViewById<ViewGroup>(android.R.id.content);layout(root)
                all(root).single{it.tag=="no5-row-Andere HOME einmal öffnen"}.performClick()
                root=activity.findViewById(android.R.id.content);layout(root)
                val scroll=all(root).filterIsInstance<ScrollView>().single()
                assertTrue(scroll.getChildAt(0).height>scroll.height)
                val external=all(scroll).filter{it.tag?.toString()?.startsWith("no5-row-External HOME fixture")==true}
                assertEquals(20,external.size)
                assertTrue(external.first().contentDescription.toString().contains("long-name-".repeat(24)))
                val back=all(root).single{it.tag=="no5-workspace-back"};assertFalse(all(scroll).contains(back));assertEquals(96,back.height)
                scroll.scrollTo(0,scroll.getChildAt(0).height);layout(root);assertTrue(scroll.scrollY>0)
                capture(root,"homes-long-$language-$night-$font")
                back.performClick();root=activity.findViewById(android.R.id.content);layout(root)
                assertEquals(3,all(root).count{it.tag?.toString()?.startsWith("no5-row-")==true});assertFalse(activity.isFinishing)
                assertNull("Opening the list does not select Android's default HOME",shadowOf(activity).nextStartedActivity)
            }
        }
    }
    @Test fun nativeInfoLongEvidenceKeepsItsCloseActionOutsideTheScrollBody(){matrix {language,night,font->
        Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().use {host->
            val activity=host.get();val fixture="PUBLIC LONG UI FIXTURE "+"x".repeat(10000)
            val dialog=com.no5.releaseinfo.ReleaseInfo.showDetails(activity,fixture,night)
            // D03 is a calibrated full-window layer, not the old platform AlertDialog width.
            val root=dialog.window!!.decorView;layout(root,2000,1200)
            val scroll=all(root).filterIsInstance<ScrollView>().single {it.childCount==1&&it.getChildAt(0) is TextView};val close=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertEquals(No5Text.text(activity,"Schließen"),close.text.toString());fits(close);assertFalse(all(scroll).contains(close));assertTrue(close.height>=96)
            val body=all(scroll).filterIsInstance<TextView>().single();assertTrue(body.text.toString().endsWith(fixture));assertTrue(body.height>scroll.height)
            val content=dialog.findViewById<ViewGroup>(android.R.id.content);(content.parent as ViewGroup).removeView(content);activity.setContentView(content);host.visible().windowFocusChanged(true);layout(content,2000,1200)
            capture(content,"info-long-ATTACHED-CONTENT-FIXTURE-$language-${if(night)"night" else "day"}-font$font")
            close.performClick();shadowOf(Looper.getMainLooper()).idle();assertFalse(dialog.isShowing);assertFalse(activity.isFinishing)
        }
    }}

    @Test fun populatedAppGridPreservesFullExternalNamesAndScrollableTargets(){
        val title="UNTRANSLATED EXTERNAL APP "+"long-name-".repeat(30)
        val apps=(0 until 30).map {index->ResolveInfo().apply {
            nonLocalizedLabel=if(index==0)title else "External instrument $index"
            activityInfo=ActivityInfo().apply {packageName="test.externalapp$index";name="$packageName.MainActivity";enabled=true;exported=true;applicationInfo=ApplicationInfo().apply {packageName="test.externalapp$index";enabled=true}}
        }}
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),apps)
        matrix {language,night,font->
            Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().use {host->
                val activity=host.get();button(activity.window.decorView,"Alle Apps").performClick();val root=activity.findViewById<ViewGroup>(android.R.id.content);layout(root)
                val scroll=all(root).filterIsInstance<ScrollView>().single()
                val rows=all(scroll).filter{it.tag?.toString()?.startsWith("no5-row-home-app-")==true}
                assertEquals(30,rows.size)
                assertTrue(rows.any{it.contentDescription.toString().startsWith(title)})
                rows.forEach{assertEquals(104,it.height);assertEquals(1280,it.width);assertTrue(it.isClickable)}
                scroll.scrollTo(0,25*104);layout(root);assertTrue(scroll.scrollY>0)
                capture(root,"apps-populated-$language-${if(night)"night" else "day"}-font$font")
                all(root).single{it.tag=="no5-workspace-back"}.performClick();assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun typedGatewayUiKeepsCountdownAndUnknownProtocolReasonsWhileRefreshingOpenLocale(){
        val unknown="FUTURE_CODE_%s_HTTP_418_"+"x".repeat(10000)
        matrix {language,night,font->
            val unrecognized=com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.UNAVAILABLE,reason=unknown)
            assertTrue(homeGatewaySessionLabel(context,unrecognized).endsWith(unknown))
            assertEquals("Gateway · nicht verfügbar · $unknown",unrecognized.label) // The legacy contract is untouched.
            Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().use {host->
                val activity=host.get();val monitorField=HomeActivity::class.java.getDeclaredField("gatewayMonitor").apply {isAccessible=true};val monitor=monitorField.get(activity)
                val displayed=monitor.javaClass.getDeclaredField("displayedSessionStatus").apply {isAccessible=true}
                val statuses=listOf(0,1,30).map {seconds->com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.WAITING,retryRemainingSeconds=seconds)}+
                    listOf(com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.UNAVAILABLE,reason="FUTURE_CODE_%s_HTTP_418"),com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.UNAVAILABLE),com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.READY),com.no5.gateway.GatewaySessionStatus(com.no5.gateway.GatewaySessionPhase.CLOSED))
                statuses.forEach {status->
                    displayed.set(monitor,status);activity.refreshLanguageUi();val root=activity.findViewById<ViewGroup>(android.R.id.content);layout(root)
                    val notice=all(root).filterIsInstance<Button>().single {it.tag=="home-gateway-notice"};assertEquals(homeGatewaySessionLabel(activity,status),notice.text.toString());fits(notice)
                    if(status.phase==com.no5.gateway.GatewaySessionPhase.WAITING)assertTrue(notice.text.toString().endsWith("${status.retryRemainingSeconds} s"))
                }
                val waiting=statuses.first();displayed.set(monitor,waiting)
                for(next in listOf("de","en","af")){UiLanguage.acceptConfirmed(context,next);activity.refreshLanguageUi();val root=activity.findViewById<ViewGroup>(android.R.id.content);layout(root);val notice=all(root).filterIsInstance<Button>().single {it.tag=="home-gateway-notice"};assertEquals(homeGatewaySessionLabel(activity,waiting),notice.text.toString());fits(notice)}
                UiLanguage.acceptConfirmed(context,language)
            }
        }
    }

}

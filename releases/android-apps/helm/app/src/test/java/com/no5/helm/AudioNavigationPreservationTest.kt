package com.no5.helm

import android.graphics.*
import com.no5.domain.*
import com.no5.helm.network.AudioV1Decoder
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Pinned pre-change native images; only user-authorized areas are excluded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioNavigationPreservationTest {
    @Test fun closedAudioRemainsPixelIdenticalOutsideButtonsAndExplicitRemovalsInBothThemes() {
        // The immutable original fixture was captured in English. Device locale does not select app language.
        val context=RuntimeEnvironment.getApplication()
        val fixtureLanguage=com.no5.ui.UiLanguage.current(context)
        com.no5.ui.UiLanguage.acceptConfirmed(context, "en")
        val ctl=readOnlyController()
        try {
        val masks=listOf(Rect(514,438,738,490),Rect(750,438,974,490),Rect(517,410,691,461),Rect(723,417,865,452),Rect(52,932,332,1032))
        val output=File(System.getProperty("no5.renderOutputDir"),"spotify-navigation").apply{mkdirs()}
        val records=mutableListOf<String>()
        val outsideChanges=mutableListOf<String>()
        for(night in listOf(false,true)) {
            val name=if(night)"night"else"day"
            val before=BitmapFactory.decodeStream(javaClass.getResourceAsStream("/audio-navigation-control/$name.png"))
            val after=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            val scene=AudioNativeScene(RuntimeEnvironment.getApplication(),night);scene.draw(Canvas(after),ctl)
            var changed=0;var permitted=0
            for(y in 0 until 1200)for(x in 0 until 2000)if(before.getPixel(x,y)!=after.getPixel(x,y)) {
                if(masks.any{it.contains(x,y)})permitted++ else changed++
            }
            File(output,"audio-$name.png").outputStream().use{after.compress(Bitmap.CompressFormat.PNG,100,it)}
            records+="{\"theme\":\"$name\",\"outsideAuthorizedPixels\":$changed,\"changedAuthorizedPixels\":$permitted}"
            if(changed!=0)outsideChanges+="$name:$changed"
            assertTrue("Both buttons/removal should be present",permitted>100)
            val counts=scene.recordingCounts();ctl.openPanel("queue");scene.draw(Canvas(after),ctl)
            for(section in listOf("artwork","zone-0","zone-1","zone-2","master","footer"))assertEquals(counts[section],scene.recordingCounts()[section])
            ctl.closePanel();before.recycle();after.recycle()
        }
        File(output,"preservation.json").writeText("["+records.joinToString()+"]\n")
        assertEquals("Existing AUDIO must remain unchanged outside the explicit user-approved masks",emptyList<String>(),outsideChanges)
        } finally { ctl.dispose();com.no5.ui.UiLanguage.acceptConfirmed(context,fixtureLanguage) }
    }
    /** Current behavior check in addition to the immutable historical golden comparison above. */
    @Test fun closedAudioIsPixelIdenticalAfterBothMusicNavigationPanelsInEveryLanguageAndTheme() {
        val context=RuntimeEnvironment.getApplication()
        val language=com.no5.ui.UiLanguage.current(context)
        try {
            for(tag in listOf("de","en","af")) for(night in listOf(false,true)) {
                com.no5.ui.UiLanguage.acceptConfirmed(context,tag)
                val ctl=readOnlyController()
                val scene=AudioNativeScene(context,night)
                fun draw()=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also {
                    scene.draw(Canvas(it),ctl,nowEpochMs=1770000000000)
                }
                val before=draw()
                val pixels=IntArray(2000*1200).also { before.getPixels(it,0,2000,0,0,2000,1200) }
                try {
                    for(panel in listOf("queue","media")) {
                        ctl.openPanel(panel)
                        assertEquals("The actual navigation must open",panel,ctl.panel)
                        ctl.closePanel()
                        val after=draw()
                        try {
                            val returned=IntArray(2000*1200).also { after.getPixels(it,0,2000,0,0,2000,1200) }
                            assertArrayEquals("Closed $panel changed AUDIO pixels: $tag night=$night",pixels,returned)
                        } finally { after.recycle() }
                    }
                } finally { before.recycle();ctl.dispose() }
            }
        } finally { com.no5.ui.UiLanguage.acceptConfirmed(context,language) }
    }

    private fun readOnlyController(): AudioNativeController {
        val root=sequenceOf(File("."),File(".."),File("../..")).first{File(it,"simulator/tests/fixtures/audio-v1.json").exists()}
        val base=AudioV1Decoder.state(File(root,"simulator/tests/fixtures/audio-v1.json").readText()).state
        val source=object:AudioSourcePort {
            override val identity=SourceIdentity("preservation",SourceMode.PI,"TEST")
            override val state=MutableSourceFeed(base.copy(sessionId=identity.sessionId,media=base.media.copy(trackId="4uLU6hMCjMI75M1A2tKUQC")))
            override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
            override val mediaBrowser=object:AudioMediaPort {
                override val metadata=MutableSourceFeed(AudioMediaMetadata("receiver",1770000000000,true,"spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
                override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
                override val browsing=MutableSourceFeed(AudioBrowseResult())
                override fun browse(query:AudioBrowseQuery)=Unit
                override fun cancelBrowse()=Unit
                override fun close()=Unit
            }
            override fun available(command:AudioCommand)=AudioModel.available(state.value,command)
            override fun submit(command:AudioCommand)=error("Read-only preservation fixture")
            override fun tick()=Unit
            override fun close()=Unit
        }
        return AudioNativeController(Dispatchers.Unconfined,now={1770000000000},automaticTicker=false,automaticVerification=false,source=source)
    }

    @Test fun existingHitGeometryAndNewPlayerOnlyRegionsDoNotOverlapMixer() {
        val g=AudioNativeGeometry
        assertEquals(AudioNativeRect(1735f,378f,116f,388f),g.masterSlider)
        assertEquals(AudioNativeRect(1213f,934f,443f,96f),g.masterMute)
        assertEquals(AudioNativeRect(353f,934f,842f,96f),g.settings)
        assertEquals(AudioNativeRect(34f,648f,956f,96f),g.seek)
        assertEquals(listOf(AudioNativeRect(54f,772f,150f,96f),AudioNativeRect(224f,772f,190f,96f),AudioNativeRect(434f,772f,326f,96f),AudioNativeRect(780f,772f,190f,96f)),g.transport)
        for(slot in 0..2) {assertTrue(g.queueOverlay.right<g.zoneSlider(slot).x);assertTrue(g.queueOverlay.right<g.zoneMute(slot).x)}
        assertTrue(g.nextSongs.right<g.music.x);assertTrue(g.music.right<1008f)
        assertEquals(130f,g.musicPage.y);assertEquals(1058f,g.musicPage.bottom)
    }
}

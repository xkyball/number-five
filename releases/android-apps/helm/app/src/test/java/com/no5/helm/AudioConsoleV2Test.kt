package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import com.no5.domain.*
import com.no5.helm.network.AudioV1Decoder
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioConsoleV2Test {
    private val root get()=sequenceOf(File("."),File(".."),File("../..")).first{File(it,"simulator/tests/fixtures/audio-v1.json").exists()}
    private val fixture get()=AudioV1Decoder.state(File(root,"simulator/tests/fixtures/audio-v1.json").readText()).state
    private class SnapshotSource(snapshot:AudioState):AudioSourcePort {
        override val identity=SourceIdentity("console-render",SourceMode.PI,"TEST GATEWAY")
        override val state=MutableSourceFeed(snapshot.copy(sessionId=identity.sessionId))
        override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
        override fun available(command:AudioCommand)=AudioModel.available(state.value,command)
        override fun submit(command:AudioCommand)=error("Read-only rendering fixture")
        override fun tick()=Unit
        override fun close()=Unit
    }
    private fun controller(state:AudioState)=AudioNativeController(Dispatchers.Unconfined,now={1770000000000L},automaticTicker=false,automaticVerification=false,source=SnapshotSource(state))
    @Test fun nativeConsoleMatchesReferenceStructureAndMasterSurvivesSetup() {
        val context=RuntimeEnvironment.getApplication()
        val out=File(System.getProperty("no5.renderOutputDir"),"audio-v2").apply{mkdirs()}
        for(night in listOf(false,true)) {
            val c=controller(fixture)
            val scene=AudioNativeScene(context,night)
            val pictures=listOf(false,true).map { setup ->
                if(setup)c.openPanel("mixer")
                val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(image);scene.draw(canvas,c)
                HelmScene(context,night).drawChrome(canvas,"AUDIO",renderValues(HelmState.unavailable()),"SIMULATED")
                File(out,"${if(night)"night"else"day"}-${if(setup)"setup"else"console"}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image
            }
            for(y in 142..1040 step 3)for(x in 1660..1998 step 3)assertEquals(pictures[0].getPixel(x,y),pictures[1].getPixel(x,y))
            // Compose draws the one real editor. The native background must
            // not paint a second preset/editor layer beneath it.
            val editor=AudioNativeGeometry.drawer
            for(y in editor.y.toInt() until editor.bottom.toInt() step 3)for(x in editor.x.toInt() until editor.right.toInt() step 3)
                assertEquals("Editor ownership belongs to Compose at $x,$y",pictures[0].getPixel(x,y),pictures[1].getPixel(x,y))
            val mute=AudioNativeGeometry.masterMute
            for(y in mute.y.toInt() until mute.bottom.toInt() step 3)for(x in mute.x.toInt() until mute.right.toInt() step 3)
                assertEquals("Master mute remains visible beside setup",pictures[0].getPixel(x,y),pictures[1].getPixel(x,y))
            assertEquals(4,AudioNativeGeometry.transport.size)
            pictures.forEach{it.recycle()};c.dispose()
        }
    }
    @Test fun metersRequireFreshSamplesAndExplicitTrustedLogicalGroups() {
        val s=fixture;val front=audioGroupMeter(s,AudioChannel.FRONT)!!
        assertEquals(s.dsp.meters!!.channels.take(2).maxOf{it.rmsDb},front.rmsDb,0.0)
        assertNull(audioGroupMeter(s.copy(dsp=s.dsp.copy(meters=s.dsp.meters!!.copy(groups=emptyMap()))),AudioChannel.FRONT))
        assertNull(audioGroupMeter(s.copy(dsp=s.dsp.copy(meters=s.dsp.meters!!.copy(quality=s.dsp.meters!!.quality.copy(status=TelemetryStatus.STALE)))),AudioChannel.FRONT))
        val real=s.dsp.meters!!.copy(quality=s.dsp.meters!!.quality.copy(simulated=false),routingVerified=false)
        assertNull(audioGroupMeter(s.copy(dsp=s.dsp.copy(meters=real)),AudioChannel.FRONT))
        assertNotNull(audioGroupMeter(s.copy(dsp=s.dsp.copy(meters=real.copy(routingVerified=true))),AudioChannel.FRONT))
    }
    @Test fun pendingCommandDoesNotFlashUnchangedMixerControls() {
        val before = fixture
        val pending = before.copy(pending = AudioPending("console-render:1", AudioCommand.Mute(AudioChannel.MASTER, true),
            before.dsp, before.media, 1770000005000L))
        fun render(state: AudioState): Bitmap {
            val controller = controller(state)
            return Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888).also {
                AudioNativeScene(RuntimeEnvironment.getApplication(), false).draw(Canvas(it), controller)
                controller.dispose()
            }
        }
        val idle = render(before)
        val busy = render(pending)
        for (y in 150..925 step 2) for (x in 1020..1990 step 2)
            assertEquals("An ACK wait must not recolor unrelated mixer controls at $x,$y", idle.getPixel(x, y), busy.getPixel(x, y))
        idle.recycle(); busy.recycle()
    }
    @Test fun faderPreviewAndProgressReuseUnchangedArtworkAndControls() {
        val source = SnapshotSource(fixture)
        val controller = AudioNativeController(Dispatchers.Unconfined, now = { 1770000000000L },
            automaticTicker = false, automaticVerification = false, source = source)
        val image = Bitmap.createBitmap(2000, 1200, Bitmap.Config.ARGB_8888)
        val scene = AudioNativeScene(RuntimeEnvironment.getApplication(), false)
        scene.draw(Canvas(image), controller)
        val initial = scene.recordingCounts()
        controller.previewLevel(AudioChannel.MASTER, -12.0)
        scene.draw(Canvas(image), controller)
        val dragged = scene.recordingCounts()
        assertEquals(initial.getValue("master") + 1, dragged.getValue("master"))
        for (section in listOf("frame", "artwork", "player-meta", "player-progress", "zone-0", "zone-1", "zone-2", "footer", "transport-2"))
            assertEquals("Only the changed fader/status should be recorded again: $section", initial[section], dragged[section])
        controller.cancelPreview()
        source.state.emit(source.state.value.copy(media = source.state.value.media.copy(positionMs = 20000)))
        scene.draw(Canvas(image), controller)
        val progressed = scene.recordingCounts()
        assertEquals(dragged.getValue("player-progress") + 1, progressed.getValue("player-progress"))
        for (section in listOf("frame", "artwork", "player-meta", "zone-0", "zone-1", "zone-2", "footer"))
            assertEquals("Progress must reuse unchanged scene content: $section", dragged[section], progressed[section])
        image.recycle(); controller.dispose()
    }
    @Test fun fixedMasterDecimalDoesNotMoveWhenIntegerWidthChanges() {
        fun raster(value:Double):Bitmap {
            val s=fixture;val c=controller(s.copy(dsp=s.dsp.copy(faders=s.dsp.faders+(AudioChannel.MASTER to AudioFader(value)))))
            val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            AudioNativeScene(RuntimeEnvironment.getApplication(),false).draw(Canvas(image),c);c.dispose();return image
        }
        val a=raster(-9.0);val b=raster(-10.0)
        var painted=0
        for(y in 311..333)for(x in 1794..1817) {assertEquals(a.getPixel(x,y),b.getPixel(x,y));if(a.getPixel(x,y)!=a.getPixel(1789,328))painted++}
        assertTrue(painted>10);a.recycle();b.recycle()
    }
}

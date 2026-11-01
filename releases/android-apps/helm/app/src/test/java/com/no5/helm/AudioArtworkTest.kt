package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.no5.domain.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioArtworkTest {
    private val uri="spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private val id="a".repeat(64)
    private val descriptor=AudioArtworkDescriptor(id,"/v1/audio/artwork/$id",2,1)
    private fun png(): ByteArray = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(2,1,Bitmap.Config.ARGB_8888).apply { setPixel(0,0,Color.BLUE);setPixel(1,0,Color.GREEN) }
            .compress(Bitmap.CompressFormat.PNG,100,output)
    }.toByteArray()
    private class Media:AudioMediaPort {
        override val metadata=MutableSourceFeed(AudioMediaMetadata())
        override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
        override val browsing=MutableSourceFeed(AudioBrowseResult())
        override fun browse(query:AudioBrowseQuery)=Unit
        override fun cancelBrowse()=Unit
        override fun close(){metadata.close();artwork.close();browsing.close()}
    }
    private class Source(val media:Media):AudioSourcePort {
        override val identity=SourceIdentity("media-render",SourceMode.PI,"TEST")
        override val mediaBrowser:AudioMediaPort=media
        override val state=MutableSourceFeed(AudioModel.initial().copy(sessionId=identity.sessionId,
            source=AudioSource(connected=true),media=AudioMedia(trackId="4uLU6hMCjMI75M1A2tKUQC")))
        override val outcomes=MutableSourceFeed<AudioCommandOutcome?>(null)
        override fun available(command:AudioCommand)=false
        override fun submit(command:AudioCommand)=error("Read-only")
        override fun tick()=Unit
        override fun close()=media.close()
    }
    @Test fun nativeDecoderRejectsInvalidBytesMismatchedDimensionsAndOversizedInputs() {
        assertNotNull(decodeAudioArtwork(png(),descriptor))
        assertNull(decodeAudioArtwork(png(),descriptor.copy(width=2000)))
        assertNull(decodeAudioArtwork(ByteArray(1_048_577),descriptor))
        assertNull(decodeAudioArtwork(ByteArray(100),descriptor))
    }
    @Test fun realCoverIsUncroppedHiddenAtNightAndRetainedAcrossFaderChanges() {
        val media=Media(); val source=Source(media)
        val ctl=AudioNativeController(Dispatchers.Unconfined,automaticTicker=false,source=source)
        media.metadata.emit(AudioMediaMetadata("receiver",1,true,uri,descriptor))
        media.artwork.emit(AudioArtworkBytes("receiver",uri,id,png()))
        val deadline=System.nanoTime()+2_000_000_000
        while(ctl.currentMediaBitmap==null&&System.nanoTime()<deadline) Thread.sleep(10)
        assertNotNull(ctl.currentMediaBitmap)
        val context=RuntimeEnvironment.getApplication(); val scene=AudioNativeScene(context,false)
        fun frame(scene:AudioNativeScene)=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also {scene.draw(Canvas(it),ctl)}
        val day=frame(scene)
        val evidence=java.io.File(System.getProperty("no5.renderOutputDir"),"spotify-media").apply{mkdirs()}
        java.io.File(evidence,"cover-day.png").outputStream().use{day.compress(Bitmap.CompressFormat.PNG,100,it)}
        // Original 2:1 image fits completely inside the 430x430 box, without crop or tint.
        assertEquals(Color.BLUE,day.getPixel(100,417));assertEquals(Color.GREEN,day.getPixel(430,417))
        val count=scene.recordingCounts().getValue("artwork")
        source.state.emit(source.state.value.copy(revision=1,feedback="Fader ACK"));frame(scene)
        assertEquals(count,scene.recordingCounts().getValue("artwork"))
        val night=frame(AudioNativeScene(context,true)); java.io.File(evidence,"cover-night.png").outputStream().use{night.compress(Bitmap.CompressFormat.PNG,100,it)};assertNotEquals(Color.BLUE,night.getPixel(100,417))
        source.state.emit(source.state.value.copy(revision=2,media=AudioMedia(trackId="5uLU6hMCjMI75M1A2tKUQC")))
        assertNull(ctl.currentMediaBitmap);ctl.dispose()
    }
}

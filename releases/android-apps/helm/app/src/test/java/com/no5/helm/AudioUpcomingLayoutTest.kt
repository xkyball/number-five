package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.no5.domain.*
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioUpcomingLayoutTest {
    private val now=1_000_000L
    private class Media:AudioMediaPort {
        override val metadata=MutableSourceFeed(AudioMediaMetadata())
        override val artwork=MutableSourceFeed<AudioArtworkBytes?>(null)
        override val browsing=MutableSourceFeed(AudioBrowseResult())
        override fun browse(query:AudioBrowseQuery)=Unit
        override fun cancelBrowse()=Unit
        override fun close()=Unit
    }

    @Test fun everyTranslatedQueueHeadingClearsTheButtonsAndAllPreviewInkStaysAboveSeek() {
        val context=RuntimeEnvironment.getApplication()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=context.resources.getFont(R.font.barlow_condensed_semibold)}
        fun ink(value:String,baseline:Float,size:Float):Rect {
            paint.textSize=size
            return Rect().also{paint.getTextBounds(value,0,value.length,it);it.offset(519,(baseline+AudioUpcomingLayout.PLAYER_Y).toInt())}
        }
        for(language in listOf("de","en","af")) {
            for(quality in UpcomingQuality.entries) {
                val heading=ink(No5Text.text(language,AudioUpcoming(emptyList(),quality).label),AudioUpcomingLayout.HEADER_Y,AudioUpcomingLayout.HEADER_SIZE)
                assertTrue("$language $quality heading behind music buttons: $heading",heading.top>=AudioNativeGeometry.nextSongs.bottom+12)
            }
            val lastTitle=ink("2  Über den Wolken · lang waargenome titel",AudioUpcomingLayout.TITLE_Y+AudioUpcomingLayout.ROW_STEP,AudioUpcomingLayout.TITLE_SIZE)
            val lastArtist=ink("Christian Löffler · waargenome kunstenaar",AudioUpcomingLayout.TITLE_Y+AudioUpcomingLayout.ROW_STEP+AudioUpcomingLayout.ARTIST_OFFSET,AudioUpcomingLayout.ARTIST_SIZE)
            val status=ink(No5Text.text(language,"READY · %s",No5Text.text(language,"PAUSE")),AudioUpcomingLayout.STATUS_Y,AudioUpcomingLayout.STATUS_SIZE)
            assertTrue("$language title hits status: $lastTitle $status",lastTitle.bottom<status.top)
            assertTrue("$language artist hits status: $lastArtist $status",lastArtist.bottom<status.top)
            assertTrue("$language status enters seek hit surface: $status",status.bottom<AudioNativeGeometry.seek.y)
        }
    }

    @Test fun observedRowsCannotChangeCurrentTrackButtonsSeekArtworkOrMixerInEitherTheme() {
        val context=RuntimeEnvironment.getApplication()
        val original=UiLanguage.current(context)
        try {
            for(language in listOf("de","en","af"))for(night in listOf(false,true)) {
                UiLanguage.acceptConfirmed(context,language)
                val media=Media()
                media.metadata.emit(AudioMediaMetadata("receiver",now,true,"spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
                val delegate=MockAudioSource(now={now}).also{it.loadFixture(AudioFixture.DEMO)}
                val source=object:AudioSourcePort by delegate {override val mediaBrowser:AudioMediaPort=media}
                val controller=AudioNativeController(Dispatchers.Unconfined,now={now},automaticTicker=false,automaticVerification=false,source=source)
                val scene=AudioNativeScene(context,night)
                fun draw()=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888).also {scene.draw(Canvas(it),controller,nowEpochMs=now)}
                val before=draw()
                media.metadata.emit(AudioMediaMetadata("receiver",now,true,"spotify:track:4uLU6hMCjMI75M1A2tKUQC",queue=
                    AudioObservedQueue("observed",null,now,emptyList(),listOf(
                        AudioQueueItem("first","Über den Wolken · een baie lang waargenome titel","Christian Löffler · waargenome kunstenaar"),
                        AudioQueueItem("second","The Road · externally reordered during the same current title","Thylacine, Parra for Cuva")))))
                val after=draw()
                val regions=listOf(
                    Rect(54,202,484,632), // unchanged artwork
                    Rect(516,200,974,430), // current title/artist/album
                    Rect(516,440,973,489), // both navigation buttons, including their border
                    Rect(34,648,990,744), // existing seek hit surface
                    Rect(54,772,970,868), // transport
                    Rect(1020,150,1999,925), // faders and meters
                )
                for(region in regions) {
                    fun pixels(bitmap:Bitmap)=IntArray(region.width()*region.height()).also {
                        bitmap.getPixels(it,0,region.width(),region.left,region.top,region.width(),region.height())
                    }
                    assertArrayEquals("Preview changed protected pixels $language night=$night $region",pixels(before),pixels(after))
                }
                before.recycle();after.recycle();controller.dispose()
            }
        } finally {UiLanguage.acceptConfirmed(context,original)}
    }
}

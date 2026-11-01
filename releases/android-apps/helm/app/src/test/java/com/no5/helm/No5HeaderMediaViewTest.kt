package com.no5.helm

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.no5.header.artwork.No5HeaderMediaView
import com.no5.navigation.AppNavigation
import com.no5.ui.UiStateContract
import com.no5.ui.UiStateSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Actual attached View/window-focus tests. No gateway, APK or physical Lumina claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class No5HeaderMediaViewTest {
    class Host : Activity() {
        lateinit var media: No5HeaderMediaView
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            media = No5HeaderMediaView(this).apply { defaultFocusHighlightEnabled = false }
            setContentView(FrameLayout(this).apply {
                addView(media, FrameLayout.LayoutParams(232, 104))
            })
        }
    }

    private fun draw(view: View): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(232, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(104, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 232, 104)
        return Bitmap.createBitmap(232, 104, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun headerCreatedBeforeFocusBecomesClickableAndScrollsWithoutAnotherMetadataDelivery() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(false)
            shadowOf(Looper.getMainLooper()).idle()
            val host = controller.get()
            val view = host.media
            shadowOf(host.packageManager).addOrUpdateActivity(ActivityInfo().apply {
                packageName = AppNavigation.HELM; name = "com.no5.helm.MainActivity"
                enabled = true; exported = true
                applicationInfo = ApplicationInfo().apply { packageName = AppNavigation.HELM; enabled = true }
            })
            view.onOpenAudio = { assertNull(AppNavigation.openAudio(host)) }
            val fullTitle = "A long original title that needs scrolling across the narrow marine header"
            view.render(fullTitle, "Original artist · Wiedergabe", fullTitle, night = false,
                active = true, reducedMotion = false)
            assertFalse(view.hasWindowFocus())
            val unfocused = draw(view)

            // No render()/metadata callback occurs between focus gain and this real view action.
            controller.windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.hasWindowFocus())
            assertTrue(view.performClick())
            val intent = shadowOf(host).nextStartedActivity
            assertEquals(AppNavigation.component(AppNavigation.HELM), intent.component)
            assertEquals("AUDIO", intent.getStringExtra(AppNavigation.OPEN_PAGE))
            assertEquals(fullTitle, view.contentDescription)
            val focusedStart = draw(view)
            assertTrue(unfocused.sameAs(focusedStart))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2200))
            val moving = draw(view)
            assertFalse("Overflow must move after the initial reading pause", focusedStart.sameAs(moving))

            controller.windowFocusChanged(false)
            shadowOf(Looper.getMainLooper()).idle()
            val noFocus = draw(view)
            assertTrue("Focus loss must return to a static readable start", focusedStart.sameAs(noFocus))
            controller.windowFocusChanged(true)
            view.render(fullTitle, "Original artist · Wiedergabe", fullTitle, night = false,
                active = true, reducedMotion = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2200))
            val reduced = draw(view)
            assertTrue("Reduced motion must leave the title at its static start", focusedStart.sameAs(reduced))
            view.render(fullTitle, "Original artist · Wiedergabe", fullTitle, night = false,
                active = false, reducedMotion = true)
            assertFalse("A stopped Activity cannot launch AUDIO", view.performClick())
            listOf(unfocused, focusedStart, moving, noFocus, reduced).forEach(Bitmap::recycle)
        }
    }

    @Test fun aFittingTitleStillChangesToStaleAtTheOriginalDeadlineWithoutNewMedia() {
        Robolectric.buildActivity(Host::class.java).setup().use { controller ->
            controller.windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            val received = SystemClock.elapsedRealtime()
            val media = UiStateSnapshot.NowPlaying("Song", "Artist", "PLAYING", 7,
                System.currentTimeMillis(), received, true)
            val snapshot = UiStateSnapshot("de", 12, true, media)
            val view = controller.get().media
            view.render(snapshot, night = false, active = true, reducedMotion = true)
            assertTrue(view.contentDescription.contains("Wiedergabe"))
            val before = draw(view)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(UiStateContract.MEDIA_FRESH_MS))
            // The client's expiry notification has a new object but identical producer data.
            view.render(UiStateSnapshot("de", snapshot.revision, true, media), night = false,
                active = true, reducedMotion = true)
            assertTrue(view.contentDescription.contains("Veraltet"))
            assertFalse(view.contentDescription.contains("Wiedergabe"))
            assertTrue(view.contentDescription.contains("Song"))
            assertEquals(received, media.receivedAtElapsedMs)
            val stale = draw(view)
            assertFalse("Even a non-scrolling title needs an actual expiry redraw", before.sameAs(stale))
            before.recycle(); stale.recycle()
        }
    }
}

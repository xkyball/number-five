package com.no5.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePhotoBackgroundTest {
    @Test fun packagedPhotoMatchesTheSuppliedRepositoryFile() {
        val resources = RuntimeEnvironment.getApplication().resources
        val bytes = resources.openRawResource(R.drawable.home_boat).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("efe2f1bcff06ee20d522d853d8dfbbd449647b2cde57ce6ea4c846b3dbc602db", digest)
        val photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(2556, photo.width)
        assertEquals(1179, photo.height)
        photo.recycle()
    }

    @Test fun actualPhotoRemainsVisibleAndNightMediaHasNoBrightOrNonRedPixels() {
        val photo = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.home_boat)
        for (height in listOf(1200, 928)) for (night in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(2000, height, Bitmap.Config.ARGB_8888)
            HomePhotoBackground(photo, night).apply { setBounds(0, 0, 2000, height); draw(Canvas(bitmap)) }
            val colors = mutableSetOf<Int>()
            for (y in height / 3 until height * 3 / 4 step 17) for (x in 1100 until 1950 step 17) {
                val color = bitmap.getPixel(x, y)
                colors.add(color)
                if (night) {
                    assertEquals(0, Color.green(color))
                    assertEquals(0, Color.blue(color))
                    assertTrue("Night photo too bright", Color.red(color) <= 26)
                }
            }
            assertTrue("Photographed scene is missing", colors.size > if (night) 8 else 100)
            bitmap.recycle()
        }
        photo.recycle()
    }
}

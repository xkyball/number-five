package com.no5.helm.video

import com.no5.helm.ocean.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35])
class VideoPreferencesTest {
    @Test fun videoModeAndDocumentPersistTogetherWithoutChangingOtherModes(){
        val context=RuntimeEnvironment.getApplication()
        val store=BackgroundPreferences(context)
        assertTrue(store.save(BackgroundMode.VIDEO,"content://local/test.mp4"))
        assertEquals(BackgroundMode.VIDEO,BackgroundPreferences(context).load())
        assertEquals("content://local/test.mp4",BackgroundPreferences(context).loadVideoUri())
        assertTrue(store.save(BackgroundMode.IMAGE));assertEquals("content://local/test.mp4",store.loadVideoUri())
        assertFalse(store.save(BackgroundMode.VIDEO,"https://example.com/preview.mp4"));assertEquals(BackgroundMode.IMAGE,store.load())
    }
    @Test fun corruptOrOldPreferencesDoNotCreateAnExternalVideoSource(){
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("no5_background",0).edit().clear().putInt("video_uri",4).commit()
        val store=BackgroundPreferences(context);assertNull(store.loadVideoUri());assertEquals(BackgroundMode.IMAGE,store.load())
    }
}

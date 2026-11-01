package com.no5.helm

import android.content.ComponentName
import com.no5.navigation.AppNavigation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppNavigationIdentityTest {
    @Test fun helmAndMapUseSeparateAndroidPackagesWithStableComponentClasses() {
        assertEquals("com.no5.helm", AppNavigation.HELM)
        assertEquals("com.no5.map", AppNavigation.MAP)
        assertEquals(ComponentName("com.no5.helm", "com.no5.helm.MainActivity"), AppNavigation.component(AppNavigation.HELM))
        assertEquals(ComponentName("com.no5.map", "com.no5.helm.MainActivity"), AppNavigation.component(AppNavigation.MAP))
    }
}

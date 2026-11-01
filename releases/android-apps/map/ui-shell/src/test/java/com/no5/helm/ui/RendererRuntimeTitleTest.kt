package com.no5.helm.ui

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RendererRuntimeTitleTest {
    @Test fun runtimeApiIsReadEvenWhenReleaseStringDisagrees() {
        val previousRelease = Build.VERSION.RELEASE
        try {
            ShadowBuild.setVersionRelease("14")
            assertEquals(29, Build.VERSION.SDK_INT)
            assertEquals("14", Build.VERSION.RELEASE)
            assertEquals("Renderer · ARM64 · Android API 29", rendererDiagnosticRuntimeTitle())
        } finally {
            ShadowBuild.setVersionRelease(previousRelease)
        }
    }

    @Test fun apiIsReportedWithoutInventingAnAndroidReleaseMapping() {
        assertEquals("Renderer · ARM64 · Android API 34", rendererDiagnosticTitleForApi(34))
    }

    @Test fun unavailableApiIsShownAsUnknown() {
        assertEquals("Renderer · ARM64 · Android API —", rendererDiagnosticTitleForApi(0))
    }

    @Test fun invalidApiIsShownAsUnknown() {
        assertEquals("Renderer · ARM64 · Android API —", rendererDiagnosticTitleForApi(-1))
    }
}

package com.no5.helm

import androidx.lifecycle.ViewModelProvider

/** AndroidX caches one Application per process; Robolectric replaces it between fixtures. */
internal fun resetAndroidViewModelApplicationFixture() {
    ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply {
        isAccessible = true
        set(null, null)
    }
}

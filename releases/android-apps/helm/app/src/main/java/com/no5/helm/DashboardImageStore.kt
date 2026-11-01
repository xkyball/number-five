package com.no5.helm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper

/** One bounded decorative bitmap per process, independent of Sonar measurements.
 * No disk IO or PNG decoding is allowed on the UI thread, including first entry.
 * Failed decoding leaves the existing neutral backdrop; it is not retried per page.
 */
internal class DashboardImageStore(private val decode: () -> Bitmap?) {
    private var attempted = false
    private var image: Bitmap? = null
    fun load(): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Dashboard image decoding requires a worker" }
        return synchronized(this) {
            if (!attempted) {
                attempted = true
                image = runCatching { decode() }.getOrNull()?.let {
                    if (it.allocationByteCount.toLong() <= 8L * 1024 * 1024) it
                    else { it.recycle(); null }
                }
            }
            image
        }
    }
}

internal object DashboardArtworkImage {
    @Volatile private var store: DashboardImageStore? = null
    fun load(context: Context): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val owned = store ?: synchronized(this) {
            store ?: DashboardImageStore {
                context.applicationContext.assets.open("helm/ocean.png").use { BitmapFactory.decodeStream(it) }
            }.also { store = it }
        }
        return owned.load()
    }
}

package com.no5.appcontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.no5.header.*
import com.no5.header.android.HeaderAndroidClient

/** Samples the current immutable app model on Main; transport renewal never renews sensor timestamps. */
class ProductHeaderProducer(context: Context, initialPage: HeaderPage) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var resumed = false
    private var focused = false
    private var page = initialPage
    private var source: String? = null
    private var generation = 1L
    private var builder: ((HeaderPublication) -> HeaderSnapshotV1)? = null
    private val client = HeaderAndroidClient(context, initialPage) { if (!closed) publishCurrent() }
    // The existing header's visibility registry is independent of V8 gateway/knob ownership.
    // It advertises no inputs and can never execute a physical event.
    private val visibility = com.no5.control.SystemControlClient(context) { com.no5.control.ClientDisposition.REFUSED }
    private val pulse = object : Runnable {
        override fun run() {
            if (closed || !resumed || !focused) return
            publishCurrent()
            handler.postDelayed(this, 500)
        }
    }
    /** The closure must capture raw immutable values from this page/source, never mutable render state. */
    fun update(page: HeaderPage, sourceSessionId: String?, build: (HeaderPublication) -> HeaderSnapshotV1) {
        main()
        if (closed) return
        if (this.page != page || source != sourceSessionId) {
            check(generation < Long.MAX_VALUE)
            generation++
            this.page = page; source = sourceSessionId
            builder = null
            client.onPageSourceChanged(page, generation, sourceSessionId)
        }
        builder = build
        publishCurrent()
    }
    /** Retire a disconnected/replaced source before a queued UI model can publish it again. */
    fun clear() {
        main(); if (closed) return
        builder = null; source = null
        check(generation < Long.MAX_VALUE); generation++
        client.onPageSourceChanged(page, generation, null)
    }
    fun onResume() {
        main(); if (closed) return
        resumed = true; visibility.onResume(); client.onResume(); restartPulse()
    }
    fun onPause() {
        main(); resumed = false; handler.removeCallbacks(pulse); visibility.onPause(); client.onPause()
    }
    fun onWindowFocusChanged(value: Boolean) {
        main(); if (closed) return
        focused = value; visibility.onWindowFocusChanged(value); client.onWindowFocusChanged(value); restartPulse()
    }
    private fun restartPulse() {
        handler.removeCallbacks(pulse)
        if (resumed && focused && !closed) handler.post(pulse)
    }
    private fun publishCurrent() {
        main()
        if (closed || !resumed || !focused) return
        val build = builder ?: return
        val scope = client.captureScope() ?: return
        if (scope.pageId != page || scope.pageGeneration != generation || scope.sourceSessionId != source) return
        val publication = client.nextPublication(scope) ?: return
        val snapshot = build(publication)
        check(snapshot.pageId == page && snapshot.publication == publication)
        client.publish(scope, snapshot)
    }
    private fun main() { check(Looper.myLooper() === handler.looper) }
    override fun close() {
        main(); if (closed) return
        closed = true; builder = null; handler.removeCallbacks(pulse); visibility.close(); client.close()
    }
}

package com.no5.helm

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Profile work follows visibility, not input focus. A transient onPause/new
 * intent must not tear down the read socket and its ordered RAW history.
 * Recovery admission separately requires RESUMED and a focused live Window.
 */
internal class SonarProfileDemandBinding(
    private val lifecycle: Lifecycle,
    private val setDemand: (Boolean) -> Unit,
) : AutoCloseable {
    private var pageVisible = false
    private var pipelinePrepared = false
    private var admitted = false
    private var delivered: Boolean? = null
    private var closed = false
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
            pipelinePrepared = false
            admitted = false
        }
        update()
    }
    init { lifecycle.addObserver(observer) }
    fun showProfiles(visible: Boolean, prepared: Boolean = true) {
        pageVisible = visible; pipelinePrepared = prepared; update()
    }
    private fun update() {
        val visible = !closed && pageVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!visible) admitted = false
        if (visible && pipelinePrepared) admitted = true
        // Warm a new visible lease before opening its RAW read. Once admitted,
        // a GPU fault/rebuild must not reconnect RX or discard ordered data.
        val wanted = visible && admitted
        if (delivered != wanted) { delivered = wanted; setDemand(wanted) }
    }
    override fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        update()
    }
}

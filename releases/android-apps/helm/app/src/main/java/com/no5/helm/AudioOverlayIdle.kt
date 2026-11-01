package com.no5.helm

/** Only deliberate user activity extends the deadline; producer updates never do. */
internal class AudioOverlayIdle(private val now: () -> Long, private val timeout: Long = 10_000) {
    private var last = now()
    var pointer = false
    var scrolling = false
    var pending = false
    var touchExploration = false
    fun activity() { last = now() }
    fun resetInteraction() { pointer = false; scrolling = false; activity() }
    fun expired(): Boolean {
        if (pointer || scrolling || pending || touchExploration) { activity(); return false }
        return now() - last >= timeout
    }
}

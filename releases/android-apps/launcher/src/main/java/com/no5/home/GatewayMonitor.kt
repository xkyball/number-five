package com.no5.home

import android.os.Handler
import android.os.Looper
import com.no5.gateway.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground checks only; bounded I/O, one worker and stale-callback rejection. */
internal class GatewayMonitor(
    private val store: GatewayConfigurationStore,
    private val checker: GatewayAddressChecker,
    private val result: (GatewayConfiguration?, Boolean, String?) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val policy = GatewayCheckPolicy(checker)
    private var generation = 0
    private var active = false
    private var pending: Future<*>? = null
    private val repeat = object : Runnable {
        override fun run() {
            if (!active) return
            store.hint()?.let { check(it) }
            handler.postDelayed(this, 60_000)
        }
    }

    fun start(delayed: Boolean = false) { if (active) return; active = true; handler.postDelayed(repeat, if (delayed) 60_000 else 0) }
    fun stop() { active = false; generation++; pending?.cancel(true); pending = null; handler.removeCallbacks(repeat) }

    fun check(origin: GatewayOrigin, manual: String? = null) {
        generation++; val attempt = generation
        pending?.cancel(true)
        val previous = store.read()
        val routingBefore = previous?.routingKey
        pending = worker.submit {
            var outcome: GatewayConfiguration? = null
            var error: String? = null
            val online = runCatching { checker.online() }.getOrDefault(false)
            try {
                outcome = if (manual == null) policy.check(origin, previous, System.currentTimeMillis())
                    else policy.manual(origin, manual, System.currentTimeMillis())
                if (manual != null && outcome == null) error = if (!online) GatewayCheckFailure.OFFLINE.message
                    else checker.failure()?.message ?: "Gateway über diese Adresse nicht bestätigt. Bitte Adresse und Zugang prüfen."
                else if (outcome?.usable == false) error = checker.failure()?.message
            } catch (_: Exception) { error = "Die Adresse konnte nicht geprüft werden. Bitte erneut versuchen." }
            handler.post {
                if (attempt != generation || worker.isShutdown || store.read()?.routingKey != routingBefore) return@post
                if (outcome != null && online) {
                    try { store.save(outcome!!) }
                    catch (_: RuntimeException) { result(previous, online, "Die Konfiguration konnte nicht gespeichert werden."); return@post }
                }
                result(outcome ?: previous?.takeIf { it.origin == origin }, online, error)
            }
        }
    }
    override fun close() { stop(); worker.shutdownNow() }
}

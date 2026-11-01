package com.no5.domain

import java.util.UUID

/** A single source selection owns all feature connections and their lifetimes. */
interface No5SourceSession : AutoCloseable {
    val identity: SourceIdentity
    val helm: HelmSource
    val audio: AudioSourcePort
    val sonar: SonarSourcePort
    val weather: WeatherSourcePort
}

fun interface SourceSessionFactory {
    fun create(config: SourceConfig, identity: SourceIdentity): No5SourceSession
}

data class SourceApplyResult(val applied: Boolean, val changed: Boolean = false, val error: String? = null, val warning: String? = null)

/** Save is transactional from the UI's point of view. No connection changes during draft editing. */
class SourceCoordinator(
    private val store: SourceSettingsStore,
    private val factory: SourceSessionFactory,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    var config: SourceConfig = store.load()
        private set
    var session: No5SourceSession = create(config)
        private set
    private var closed = false
    var lifecycleWarning: String? = null
        private set

    private fun create(config: SourceConfig): No5SourceSession = factory.create(config,
        SourceIdentity(sessionId(), config.mode, if (config.mode == SourceMode.DEMO) "LOCAL DEMO" else "RASPBERRY PI"))

    fun apply(requested: SourceConfig): SourceApplyResult {
        if (closed) return SourceApplyResult(false, error = "Source session is closed")
        val validation = SourceConfigValidator.validate(requested)
        val normalized = validation.config ?: return SourceApplyResult(false, error = validation.error)
        if (normalized == config) return SourceApplyResult(true)
        // Display-only edits never reconnect transports, reset history or cancel commands.
        if (normalized.copy(displayUnits = config.displayUnits) == config) {
            try { store.save(normalized) } catch (e: RuntimeException) {
                return SourceApplyResult(false, error = e.message ?: "Cannot save display settings")
            }
            config = normalized
            return SourceApplyResult(true, changed = true)
        }
        val candidate = try { create(normalized) } catch (e: RuntimeException) {
            return SourceApplyResult(false, error = e.message ?: "Cannot create source adapter")
        }
        try { store.save(normalized) } catch (e: RuntimeException) {
            try { candidate.close() } catch (_: RuntimeException) { /* No candidate subscriptions reached the UI. */ }
            return SourceApplyResult(false, error = e.message ?: "Cannot save source settings")
        }
        // Closing invalidates old subscriptions and pending requests before exposing new values.
        val retirementWarning = try { session.close(); null } catch (_: RuntimeException) {
            "Previous adapter reported a disconnect error. New source is active; old UI subscriptions are discarded."
        }
        config = normalized
        session = candidate
        return SourceApplyResult(true, changed = true, warning = retirementWarning)
    }

    /**
     * Explicit diagnostics action. It keeps the already saved configuration unchanged, retires
     * every old poller/WebSocket before the replacement is constructed, and never writes settings.
     * This is intentionally separate from [apply], so a draft cannot cause an active reconnect.
     */
    @Synchronized
    fun reload(): SourceApplyResult {
        if (closed) return SourceApplyResult(false, error = "Source session is closed")
        val previous = session
        val retirementWarning = try { previous.close(); null } catch (_: RuntimeException) {
            "Previous adapter reported a disconnect error while reloading."
        }
        return try {
            val replacement = create(config)
            session = replacement
            SourceApplyResult(true, changed = true, warning = retirementWarning)
        } catch (failure: RuntimeException) {
            // The old session was deliberately retired first, so it cannot leave a hidden polling
            // set behind. Its closed ports remain unavailable until the user retries reload.
            SourceApplyResult(false, error = failure.message ?: "Cannot rebuild source adapter", warning = retirementWarning)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { session.close() } catch (_: RuntimeException) {
                lifecycleWarning = "An adapter reported a disconnect error during final disposal."
            }
        }
    }
}

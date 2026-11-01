package com.no5.helm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.no5.domain.*
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.HelmSourceRegistry
import java.util.UUID

/** Activity facade: production borrows the service's ONE coordinator/session; it never owns it. */
class SourceSessionViewModel private constructor(
    application: Application,
    private val owner: HelmSourceOwner,
    private val ownsIsolatedFixture: Boolean,
) : AndroidViewModel(application) {
    private var leasedGeneration = -1L
    private var lease: No5SourceSession? = null
    constructor(application: Application) : this(application,
        HelmSourceRegistry.requireOwner(application.applicationContext), false)

    /** Explicit isolated test owner, preserving the previous test constructor disposal semantics. */
    constructor(application: Application, store: SourceSettingsStore, factory: SourceSessionFactory,
        sessionId: () -> String = { UUID.randomUUID().toString() }) :
        this(application, HelmSourceOwner(store, factory, sessionId), true)

    val isSourceOwnerAvailable: Boolean get() = !owner.isDisposed
    val homeIdentity get() = owner.homeIdentity
    val gateway get() = owner.luminaGateway
    val config get() = owner.config
    val settingsWarning get() = owner.settingsWarning
    val endpointHistory get() = owner.endpointHistory
    val changes: SourceFeed<Long> get() = owner.changes
    val session: No5SourceSession get() {
        // MainActivity acquires its first lease after the service confirms foreground readiness.
        // Disposal retains that unavailable UI snapshot; never borrow a replacement from a dead owner.
        if (owner.isDisposed) return checkNotNull(lease) { "HELM source service ended before its UI lease was acquired" }
        if (lease == null || leasedGeneration != owner.sessionGeneration) {
            lease?.close()
            lease = owner.borrowSession()
            leasedGeneration = owner.sessionGeneration
        }
        return checkNotNull(lease)
    }
    fun apply(requested: SourceConfig) = owner.apply(requested)
    fun synchronizeHome() = owner.synchronizeHome()
    fun reload() = owner.reload()
    override fun onCleared() {
        try { lease?.close(); lease = null }
        finally { try { if (ownsIsolatedFixture) owner.disposeService() } finally { super.onCleared() } }
    }
}

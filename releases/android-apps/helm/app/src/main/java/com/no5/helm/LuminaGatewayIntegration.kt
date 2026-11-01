package com.no5.helm

import android.content.Context
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.security.GatewaySecurityProfileProvider

/** Common coordinator seam; the historical HOME adapter is retained only for its isolated tests. */
interface DeviceGatewaySettingsStore : SourceSettingsStore, AutoCloseable {
    val warning: String?
    val homeIdentity: String?
    val localSettingsBlocked: Boolean
    val cachedSecurityProfiles: GatewaySecurityProfileProvider?
    val readDiagnostic: HomeGatewayReadDiagnostic?
    val refreshInFlight: Boolean
    fun observeHome(onChange: () -> Unit): SourceSubscription
    fun requestHomeRefresh()
}

class LuminaSourceSettingsStore(context: Context) : DeviceGatewaySettingsStore {
    private val local = AndroidSourceSettingsStore(context)
    val gateway = LuminaGatewayClient(context)
    private val socketRoute = java.util.concurrent.atomic.AtomicReference<GatewaySocketRoute?>()
    private val routeSubscription = gateway.observe { socketRoute.set(gateway.currentSession()?.let { GatewaySocketRoute(it.origin, it.fallbackAddress) }) }
    val endpointResolver = GatewayFallbackEndpointResolver(com.no5.helm.network.AndroidGatewayEndpointResolver(context), socketRoute::get)
    override var warning: String? = null; private set
    override var localSettingsBlocked = false; private set
    override val homeIdentity: String? get() = gateway.status.identity ?: "unavailable"
    override val refreshInFlight get() = false // Binder renewal must not block fail-closed reconciliation.
    override val cachedSecurityProfiles = GatewaySecurityProfileProvider { endpoint ->
        gateway.currentSession()?.takeIf { it.origin.url == endpoint }?.profile()
    }
    override val readDiagnostic: HomeGatewayReadDiagnostic get() = HomeGatewayReadDiagnostic(
        HomeGatewayReadPhase.DELIVERY,
        if (gateway.status.usable) HomeGatewayReadReason.READY else HomeGatewayReadReason.GATEWAY_NOT_PROVISIONED,
        0, 0, 0, 0)
    override fun load(): SourceConfig {
        val saved = local.load()
        localSettingsBlocked = local.warning != null
        val session = gateway.currentSession()
        warning = local.warning
        return saved.copy(mode = SourceMode.PI, endpoint = session?.origin?.url ?: saved.endpoint,
            connectionEnabled = !localSettingsBlocked && session != null)
    }
    override fun save(config: SourceConfig) {
        val endpoint = gateway.currentSession()?.origin?.url
        require(endpoint == null || config.endpoint == endpoint) { "Gateway-Zugang wird in Lumina Control eingerichtet" }
        val saved = local.load()
        local.save(config.copy(endpoint = saved.endpoint, connectionEnabled = true))
        localSettingsBlocked = local.warning != null
    }
    override fun endpointHistory() = emptyList<String>()
    override fun observeHome(onChange: () -> Unit): SourceSubscription {
        val subscription = gateway.observe { onChange() }
        return SourceSubscription { subscription.close() }
    }
    override fun requestHomeRefresh() = gateway.refresh()
    override fun close() { routeSubscription.close(); gateway.close(); endpointResolver.close() }
}

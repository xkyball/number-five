package com.no5.helm

import android.content.Context
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.network.*
import com.no5.helm.security.GatewaySecurityProfileProvider

/** Gateway access is per device; this app retains only weather and display preferences. */
class HomeSourceSettingsStore(
    private val local: SourceSettingsStore,
    private val homeStatus: () -> HomeGatewayStatus,
    private val offer: (String) -> Unit = {},
    private val localWarning: () -> String? = { null },
) : DeviceGatewaySettingsStore {
    private var asyncReader: HomeGatewayStatusReader? = null
    override var warning: String? = null; private set
    override var homeIdentity: String? = null; private set
    override var localSettingsBlocked: Boolean = false; private set
    override fun load(): SourceConfig {
        val saved = local.load()
        warning = localWarning()
        localSettingsBlocked = warning != null
        val status = try { offer(saved.endpoint); homeStatus() }
        catch (_: RuntimeException) {
            homeIdentity = "unavailable"
            warning = warning ?: asyncReader?.diagnostic?.let {
                "Home-Gateway: ${it.phase.name}/${it.reason.name} (${(it.finishedUptimeMs - it.startedUptimeMs).coerceAtLeast(0)} ms)."
            } ?: "Gateway-Konfiguration in Home nicht sicher verfügbar."
            return saved.copy(mode = SourceMode.PI, connectionEnabled = false)
        }
        homeIdentity = status.identity
        val record = status.configuration
        val unavailable = when {
            !status.installed -> "HOME_ABSENT"
            record?.usable != true -> "CONFIGURATION_UNUSABLE"
            !status.keyPresent -> "KEY_MISSING"
            !status.caPresent -> "CA_MISSING"
            else -> null
        }
        if (unavailable != null) warning = warning ?: "Home-Gateway: $unavailable. Zugang in Home prüfen."
        return saved.copy(mode = SourceMode.PI, endpoint = record?.origin?.url ?: saved.endpoint,
            connectionEnabled = !localSettingsBlocked && unavailable == null)
    }
    override fun save(config: SourceConfig) {
        val status = try { homeStatus() } catch (failure: RuntimeException) {
            if (asyncReader == null) throw failure
            // Persisting the fail-closed selection must not require the unavailable provider.
            null
        }
        val current = status?.configuration
        val localConfig = local.load()
        require(current == null || config.endpoint == current.origin.url) { "Gateway address is configured in Home" }
        if (asyncReader != null && current == null) require(config.endpoint == localConfig.endpoint) {
            "Gateway address is configured in Home"
        }
        local.save(config.copy(endpoint = localConfig.endpoint, connectionEnabled = true))
        warning = localWarning()
        localSettingsBlocked = warning != null
    }
    override fun endpointHistory(): List<String> = emptyList()
    override val cachedSecurityProfiles: GatewaySecurityProfileProvider? get() = asyncReader?.cachedProfiles
    override val readDiagnostic: HomeGatewayReadDiagnostic? get() = asyncReader?.diagnostic
    override val refreshInFlight: Boolean get() = asyncReader?.isRefreshing == true
    internal fun attachReader(reader: HomeGatewayStatusReader) { check(asyncReader == null); asyncReader = reader }
    override fun observeHome(onChange: () -> Unit): SourceSubscription {
        val subscription = asyncReader?.observe(onChange) ?: return SourceSubscription { }
        requestHomeRefresh()
        return subscription
    }
    override fun requestHomeRefresh() { asyncReader?.refresh(local.load().endpoint) }
    override fun close() { asyncReader?.close() }
}

internal fun homeSourceStore(context: Context): HomeSourceSettingsStore {
    val local = AndroidSourceSettingsStore(context)
    val reader = HomeGatewayStatusReader(context.applicationContext)
    return HomeSourceSettingsStore(local, reader::cachedStatus, localWarning = { local.warning }).also { it.attachReader(reader) }
}

internal fun homeSecurityProfiles(context: Context) = GatewaySecurityProfileProvider { endpoint ->
    runCatching { HomeGatewayClient(context).load(endpoint) }.getOrNull()
}

/** No app-local discovery or alternate endpoint once Home owns device gateway access. */
internal class HomeGatewayEndpointResolver(context: Context) : GatewayEndpointResolver {
    private val home = HomeGatewayClient(context)
    override fun resolve(url: String, timeoutMs: Int): GatewayEndpointResolution {
        val expected = GatewayOrigin.parse(url) ?: return GatewayEndpointResolution.Failed(GatewayMdnsFailure.HOST_MISMATCH)
        val record = runCatching { home.read() }.getOrNull()
        if (record?.origin != expected || !record.usable) return GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE)
        return gatewayAddress(record.address)?.let { GatewayEndpointResolution.Resolved(listOf(it)) }
            ?: GatewayEndpointResolution.Failed(GatewayMdnsFailure.SERVICE_UNAVAILABLE)
    }
    override fun invalidate() = Unit
}

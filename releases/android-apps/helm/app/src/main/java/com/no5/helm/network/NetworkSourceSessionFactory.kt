package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import java.util.concurrent.atomic.AtomicBoolean

/** A credential renewal may preserve ports only when their security profile confirms continuity. */
interface GatewayCredentialRenewal {
    fun renewGatewayCredentials(): Boolean
}

/** Product composition root: one gateway API, independent feature connections, no local demo. */
class NetworkSourceSessionFactory(
    private val now: () -> Long = System::currentTimeMillis,
    private val transport: (String, PiNetworkPolicy) -> PiHttpTransport = ::UrlConnectionPiTransport,
    private val scheduler: (String) -> PiPollScheduler = ::BackgroundPiPollScheduler,
    private val sonarStream: (String) -> SonarStreamTransport = ::OkHttpSonarStreamTransport,
    private val securityProfiles: GatewaySecurityProfileProvider = GatewaySecurityProfileProvider.NONE,
    private val endpointResolver: GatewayEndpointResolver = GatewayEndpointResolver.NONE,
    private val securedTransport: (String, PiNetworkPolicy, GatewaySecurityProfile) -> PiHttpTransport = { endpoint, policy, profile ->
        UrlConnectionPiTransport(endpoint, policy, profile, endpointResolver)
    },
    private val securedSonarStream: (String, GatewaySecurityProfile) -> SonarStreamTransport = { endpoint, profile ->
        OkHttpSonarStreamTransport(endpoint, profile, endpointResolver)
    },
    private val securedAudioStream: (String, GatewaySecurityProfile) -> AudioFaderStreamTransport = { endpoint, profile ->
        OkHttpAudioFaderStreamTransport(endpoint, profile, endpointResolver)
    },
    private val originProofDispatcher: OriginProofConsumerDispatcher = MainOriginProofConsumerDispatcher(),
) : SourceSessionFactory, AutoCloseable {
    override fun create(config: SourceConfig, identity: SourceIdentity): No5SourceSession {
        require(config.mode == SourceMode.PI && identity.mode == SourceMode.PI) { "Product sessions require a No5 gateway" }
        val validated = SourceConfigValidator.validate(config).config ?: error("Invalid gateway endpoint")
        var helm: HelmSource = UnavailablePiHelmSource(identity)
        var audio: AudioSourcePort = UnavailableAudioSource(identity, "AUDIO · GATEWAY CONNECTION DISABLED")
        var media: AudioMediaPort? = null
        var weather: WeatherSourcePort = UnavailableWeatherSource(identity, "Weather connection disabled")
        var sonar: SonarSourcePort = UnavailablePiSonarSource(identity)
        // Existing loopback simulator endpoints stay explicit HTTP test paths. Any HTTPS endpoint
        // is fail-closed until its independently imported key and CA produce one profile.
        val secureEndpoint = validated.endpoint.startsWith("https://")
        var security: GatewaySecurityProfile? = if (config.connectionEnabled && secureEndpoint)
            securityProfiles.load(validated.endpoint) else null
        try {
            if (config.connectionEnabled && (!secureEndpoint || security != null)) {
                fun selectedTransport(policy: PiNetworkPolicy): PiHttpTransport = security?.let { securedTransport(validated.endpoint, policy, it) }
                    ?: transport(validated.endpoint, policy)
                val connectedHelm = NetworkPiHelmSource(identity, selectedTransport(PiNetworkPolicy.HELM), now, scheduler("helm"))
                helm.close(); helm = connectedHelm
                media = NetworkPiAudioMedia(selectedTransport(PiNetworkPolicy.MEDIA), selectedTransport(PiNetworkPolicy.ARTWORK), now, scheduler("audio-media"), scheduler("audio-artwork"),
                    selectedTransport(PiNetworkPolicy.MEDIA_SELECTION), scheduler("audio-selection"))
                val connectedAudio = NetworkPiAudioSource(identity, selectedTransport(PiNetworkPolicy.AUDIO), now, scheduler("audio"),
                    streamTransport = security?.let { securedAudioStream(validated.endpoint, it) }, mediaBrowser = media,
                    sound = NetworkAudioSound(identity, selectedTransport(PiNetworkPolicy.AUDIO), scheduler("audio-sound"),
                        OkHttpAudioSpectrumTransport(validated.endpoint, security, endpointResolver),
                        commandTransport=security?.let{OkHttpAudioSoundCommandTransport(validated.endpoint,it,endpointResolver)}))
                audio.close(); audio = connectedAudio
                val connectedSonar = NetworkPiSonarSource(identity, selectedTransport(PiNetworkPolicy.SONAR), security?.let { securedSonarStream(validated.endpoint, it) }
                    ?: sonarStream(validated.endpoint), now, scheduler("sonar"), originProofDispatcher=originProofDispatcher,
                    initialProfileDemand=false)
                sonar.close(); sonar = connectedSonar
                val selectedWeather = when (config.weatherProvider) {
                    WeatherProviderMode.SIMULATED -> NetworkPiWeatherSource(identity, selectedTransport(PiNetworkPolicy.WEATHER), now, scheduler("weather"))
                    WeatherProviderMode.OPEN_METEO -> if (config.allowWeatherLocationSharing)
                        OpenMeteoWeatherSource(identity, helm, allowWeatherLocationSharing = true, nowEpochMs = now, scheduler = scheduler("open-meteo"))
                    else UnavailableWeatherSource(identity, "Open-Meteo requires permission to share vessel coordinates")
                    WeatherProviderMode.DISABLED -> UnavailableWeatherSource(identity, "Weather is disabled in SETTINGS")
                }
                weather.close(); weather = selectedWeather
            }
        } catch (failure: RuntimeException) {
            try { helm.close() } catch (_: RuntimeException) { }
            try { audio.close() } catch (_: RuntimeException) { }
            try { media?.close() } catch (_: RuntimeException) { }
            try { weather.close() } catch (_: RuntimeException) { }
            try { sonar.close() } catch (_: RuntimeException) { }
            security?.close()
            throw failure
        }
        return object : No5SourceSession, GatewayCredentialRenewal {
            override val identity = identity
            override val helm = helm
            override val weather = weather
            override val audio = audio
            override val sonar = sonar
            private val closed = AtomicBoolean(false)
            override fun renewGatewayCredentials(): Boolean {
                if (closed.get()) return false
                val active = security ?: return false
                val fresh = securityProfiles.load(validated.endpoint) ?: return false
                return fresh.use {
                    active.renewBearerFrom(it).also{renewed->
                        if(renewed)(audio.sound as? NetworkAudioSound)?.credentialsRenewed()
                    }
                }
            }
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try { helm.close() } finally {
                    try { audio.close() } finally {
                        try { weather.close() } finally {
                            try { sonar.close() } finally { security?.close() }
                        }
                    }
                }
            }
        }
    }

    override fun close() = endpointResolver.close()
}

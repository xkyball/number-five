package com.no5.domain

/** Offline fixture composition for explicit test harnesses; Android injects NetworkSourceSessionFactory. */
class DefaultSourceSessionFactory(private val now: () -> Long = System::currentTimeMillis) : SourceSessionFactory {
    override fun create(config: SourceConfig, identity: SourceIdentity): No5SourceSession {
        val helm = when (config.mode) {
            SourceMode.DEMO -> MockHelmSource(identity, nowEpochMs = now)
            SourceMode.PI -> UnavailablePiHelmSource(identity)
        }
        val audio = when (config.mode) {
            SourceMode.DEMO -> MockAudioSource(identity = identity, now = now)
            SourceMode.PI -> UnavailableAudioSource(identity, "Pi audio adapter is not implemented")
        }
        val sonar = when (config.mode) {
            SourceMode.DEMO -> DeterministicMockSonarSource(identity, now)
            SourceMode.PI -> UnavailablePiSonarSource(identity)
        }
        val weather = when (config.weatherProvider) {
            WeatherProviderMode.SIMULATED -> when (config.mode) {
                SourceMode.DEMO -> MockWeatherSource(identity, now) { helm.snapshots.value }
                SourceMode.PI -> UnavailableWeatherSource(identity)
            }
            WeatherProviderMode.OPEN_METEO -> UnavailableWeatherSource(identity, "Open-Meteo requires the Android weather adapter and location sharing approval")
            WeatherProviderMode.DISABLED -> UnavailableWeatherSource(identity, "Weather is disabled")
        }
        return object : No5SourceSession {
            override val identity = identity
            override val helm: HelmSource = helm
            override val audio: AudioSourcePort = audio
            override val sonar: SonarSourcePort = sonar
            override val weather: WeatherSourcePort = weather
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try { helm.close() } finally { try { audio.close() } finally { try { sonar.close() } finally { weather.close() } } }
            }
        }
    }
}


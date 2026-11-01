package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SourceSettingsTest {
    @Test fun defaultsAreExplicitDemoAndAnEditableExampleAddress() {
        val config = SourceConfig()
        assertEquals(SourceMode.DEMO, config.mode)
        assertEquals("http://raspberrypi.local:8080", config.endpoint)
        assertEquals(config, SourceConfigValidator.validate(config).config)
    }

    @Test fun hostsUrlsIpv4AndIpv6NormalizeWithoutNetworkLookup() {
        val examples = mapOf(
            " RaspberryPi.LOCAL:8080/ " to "http://raspberrypi.local:8080",
            "HTTPS://PI.EXAMPLE:443/api/../v1/" to "https://pi.example/v1",
            "pi.local." to "http://pi.local",
            "192.168.001.010:9000" to "http://192.168.1.10:9000",
            "http://192.168.1.10:80/" to "http://192.168.1.10",
            "[2001:DB8::1]:8080" to "http://[2001:db8::1]:8080",
            "https://[::1]:443" to "https://[::1]",
            "localhost:1" to "http://localhost:1",
            "pi:65535/api" to "http://pi:65535/api",
        )
        for ((input, expected) in examples) {
            val result = SourceConfigValidator.validate(SourceConfig(SourceMode.PI, input))
            assertTrue("$input: ${result.error}", result.isValid)
            assertEquals(expected, result.config?.endpoint)
            assertEquals(SourceMode.PI, result.config?.mode)
            assertEquals(result, SourceConfigValidator.validate(result.config!!))
        }
    }

    @Test fun unsafeOrAmbiguousAddressesFailWithoutRewritingUserIntent() {
        val inputs = listOf(
            "", "  ", "ftp://pi.local", "ws://pi.local:8080", "http:pi.local",
            "http://user%3Asecret@pi.local", "http://user%3Asecret%40pi.local", "http://user:password@pi.local", "http://user@pi.local", "http://@pi.local",
            "http://pi.local?token=value", "http://pi.local?", "http://pi.local#status", "http://pi.local#",
            "http://pi.local:0", "http://pi.local:-1", "http://pi.local:65536", "http://pi.local:",
            "http://pi.local:port", "http://pi.local:999999999999999999",
            "http://192.168.1.999", "http://192.168.1", "http://999.1.1.1", "http://123",
            "http://-pi.local", "http://pi-.local", "http://pi..local", "http://pi_local", "http://.pi.local",
            "http://[:::1]", "http://[fe80::1%25eth0]", "http://::1", "http:///pi.local",
            "http://pi.local/a b", "http://pi.local/\n", "http://pi.local/\\other", "http://pi.local/%00",
            "http://${"a".repeat(64)}.local",
        )
        inputs.forEach { input ->
            val result = SourceConfigValidator.validate(SourceConfig(SourceMode.PI, input))
            assertFalse("Accepted $input", result.isValid)
            assertNull(result.config)
            assertFalse(result.error.isNullOrBlank())
        }
    }

    @Test fun invalidSavedEndpointIsNotAcceptedMerelyBecauseModeIsDemo() {
        val result = SourceConfigValidator.validate(SourceConfig(SourceMode.DEMO, "http://user:secret@pi.local"))
        assertFalse(result.isValid)
    }

    @Test fun endpointHistoryIsCanonicalDeduplicatedAndBoundedToFive() {
        var history = emptyList<String>()
        assertTrue(history.isEmpty())
        (1..6).forEach { index -> history = EndpointHistory.record("HTTP://PI-$index.LOCAL:8080/", history) }
        assertEquals(listOf(
            "http://pi-6.local:8080", "http://pi-5.local:8080", "http://pi-4.local:8080",
            "http://pi-3.local:8080", "http://pi-2.local:8080",
        ), history)
        history = EndpointHistory.record("http://PI-4.local:8080", history)
        assertEquals(listOf(
            "http://pi-4.local:8080", "http://pi-6.local:8080", "http://pi-5.local:8080",
            "http://pi-3.local:8080", "http://pi-2.local:8080",
        ), history)
        assertEquals(listOf("https://safe.local"), EndpointHistory.restored("https://safe.local", listOf("ftp://unsafe.local", "http://user:secret@pi.local")))
    }
}

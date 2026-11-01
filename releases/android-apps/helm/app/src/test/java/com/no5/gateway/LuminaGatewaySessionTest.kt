package com.no5.gateway

import com.no5.helm.security.TEST_CA_DER_B64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.util.Base64

internal const val SYNTHETIC_KEY_ID = "0123456789abcdef0123456789abcdef"
internal val syntheticCaPem = "-----BEGIN CERTIFICATE-----\n" + TEST_CA_DER_B64.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
internal fun syntheticToken(role: GatewayAppRole, origin: String, issued: Long, expiry: Long,
    scopes: String = when(role) { GatewayAppRole.HOME -> "configuration.read"; GatewayAppRole.MAP -> "map.read,map.write"; GatewayAppRole.HELM -> "audio.read,audio.command,sonar.read,sonar.command" }): String {
    val payload = "v=no5-gateway-token-v2\nkid=$SYNTHETIC_KEY_ID\naud=$origin\nrole=${role.name}\nscope=$scopes\niat=$issued\nexp=$expiry\njti=${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })}\n"
    return "N5T2." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(72) { 4 })
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LuminaGatewaySessionTest {
    private val origin = GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!
    private fun session(now: () -> Long, role: GatewayAppRole = GatewayAppRole.HELM,
        ttl: Long = 900_000, scopes: String? = null): LuminaGatewaySession {
        ShadowBuild.setHardware("ranchu")
        val token = if (scopes == null) syntheticToken(role, origin.url, 100_000, 100_000 + ttl)
        else syntheticToken(role, origin.url, 100_000, 100_000 + ttl, scopes)
        return LuminaGatewaySession(origin, syntheticCaPem, token, SYNTHETIC_KEY_ID, 100_000, 100_000 + ttl, role, now)
    }
    @Test fun bearerIsOriginBoundRedactedAndExpiresInEveryBorrowedProfile() {
        var clock = 100_000L
        val session = session({ clock })
        val profile = session.profile()
        val headers = mutableMapOf<String, String>()
        profile.configureHttps(origin.url + "/v1/audio", {}, headers::put)
        assertTrue(headers.getValue("Authorization").startsWith("Bearer N5T2."))
        assertFalse(headers.containsKey("X-No5-Gateway-Key"))
        assertFalse(session.toString().contains("N5T2"))
        assertFalse(profile.toString().contains("N5T2"))
        assertThrows(IllegalArgumentException::class.java) { profile.configureHttps("https://other.local:9443/v1/audio", {}, headers::put) }
        clock = 1_000_000
        assertFalse(session.valid)
        assertThrows(IllegalStateException::class.java) { session.profile() }
        assertThrows(IllegalStateException::class.java) { profile.configureHttps(origin.url + "/v1/audio", {}, headers::put) }
        profile.close(); session.close(); session.close()
    }
    @Test fun wrongTtlAndExpandedScopesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { session({100_000}, ttl = 899_999) }
        assertThrows(IllegalArgumentException::class.java) { session({100_000}, role = GatewayAppRole.MAP, scopes = "map.read,map.write,audio.command") }
        assertThrows(IllegalArgumentException::class.java) { session({100_000}, role = GatewayAppRole.HOME, scopes = "configuration.read,configuration.read") }
    }
    @Test fun fixedPackageRolesCannotBeSelectedByCallerInput() {
        assertEquals(GatewayAppRole.HOME, GatewayAppRole.forPackage("com.no5.home"))
        assertEquals(GatewayAppRole.MAP, GatewayAppRole.forPackage("com.no5.map"))
        assertThrows(SecurityException::class.java) { GatewayAppRole.forPackage("com.no5.other") }
    }

    @Test fun renewalPreservesOnlyTheSameIssuerRoleCaRouteAndLaterExpiry() {
        ShadowBuild.setHardware("ranchu")
        var clock = 100_000L
        fun fresh(issued: Long, role: GatewayAppRole = GatewayAppRole.HELM, route: String? = null,
            keyId: String = SYNTHETIC_KEY_ID): LuminaGatewaySession {
            val token = syntheticToken(role, origin.url, issued, issued+900_000)
                .let { if(keyId==SYNTHETIC_KEY_ID) it else {
                    val parts=it.split('.'); val payload=String(Base64.getUrlDecoder().decode(parts[1]))
                        .replace("kid=$SYNTHETIC_KEY_ID", "kid=$keyId")
                    parts[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())+"."+parts[2]
                } }
            return LuminaGatewaySession(origin, syntheticCaPem, token, keyId, issued, issued+900_000, role, { clock }, route)
        }
        val initial=fresh(clock); val active=initial.profile(); initial.close()
        clock=940_000L
        fresh(clock).use { next -> next.profile().use { assertTrue(active.renewBearerFrom(it)) } }
        val headers=mutableMapOf<String,String>()
        active.configureHttps(origin.url+"/v1/sonar", {}, headers::put)
        assertEquals("Bearer "+syntheticToken(GatewayAppRole.HELM, origin.url, clock, clock+900_000),headers["Authorization"])
        fresh(clock).use { same -> same.profile().use { assertFalse(active.renewBearerFrom(it)) } }
        clock+=1_000
        for (candidate in listOf(fresh(clock,GatewayAppRole.MAP), fresh(clock,route="192.0.2.15"),
                fresh(clock,keyId="fedcba9876543210fedcba9876543210"))) {
            candidate.use { it.profile().use { profile -> assertFalse(active.renewBearerFrom(profile)) } }
        }
        clock=1_840_000L
        assertThrows(IllegalStateException::class.java) { active.configureHttps(origin.url+"/v1/sonar", {}, headers::put) }
        fresh(clock).use { next -> next.profile().use { assertFalse(active.renewBearerFrom(it)) } }
        active.close()
    }
}

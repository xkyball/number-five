package com.no5.helm

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import com.no5.gateway.HomeGatewayContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

/** The namespace differs from applicationId; this fixture reports the actual installed MAP identity. */
class MapGatewayStartupApplication : android.app.Application() {
    override fun getPackageName() = "com.no5.map"
}

/** Android's real missing-provider rejection is absent from the default shadow. */
@Implements(ContentResolver::class)
class StrictGatewayResolverShadow : ShadowContentResolver() {
    companion object {
        var rejection: RuntimeException? = null
        var attempts = 0
        val observers = mutableSetOf<ContentObserver>()
        var unregisters = 0
    }
    @Implementation
    override fun registerContentObserver(uri: Uri, descendants: Boolean, observer: ContentObserver) {
        if (uri != HomeGatewayContract.URI) { super.registerContentObserver(uri, descendants, observer); return }
        attempts++
        rejection?.let { throw it }
        observers.add(observer)
    }
    @Implementation
    override fun unregisterContentObserver(observer: ContentObserver) {
        if (observers.remove(observer)) unregisters++
        else super.unregisterContentObserver(observer)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = MapGatewayStartupApplication::class, sdk = [29, 35], shadows = [StrictGatewayResolverShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityGatewayStartupTest {
    @Before fun setup() {
        StrictGatewayResolverShadow.rejection = SecurityException(
            "Failed to find provider com.no5.home.gateway for user 0; expected to find a valid ContentProvider for this authority")
        StrictGatewayResolverShadow.attempts = 0
        StrictGatewayResolverShadow.observers.clear()
        StrictGatewayResolverShadow.unregisters = 0
    }
    @Test fun missingHomeProviderIsNeverAccessedDuringActualActivityCreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        var created = false
        try {
            controller.create()
            created = true
            assertFalse(controller.get().isFinishing)
            assertEquals(0, StrictGatewayResolverShadow.attempts)
            assertTrue(StrictGatewayResolverShadow.observers.isEmpty())
        } finally { if (created) controller.destroy() }
        assertEquals(0, StrictGatewayResolverShadow.unregisters)
    }
    @Test fun deniedGatewayPermissionDoesNotAbortCreationOrTeardown() {
        StrictGatewayResolverShadow.rejection = SecurityException("Permission denied for gateway observer")
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        var created = false
        try { controller.create(); created = true; assertTrue(StrictGatewayResolverShadow.observers.isEmpty()) }
        finally { if (created) controller.destroy() }
        assertEquals(0, StrictGatewayResolverShadow.unregisters)
    }
    @Test fun returningAndResumingNeverRegistersTheRetiredHomeProvider() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.create()
            StrictGatewayResolverShadow.rejection = null
            controller.start().resume()
            assertEquals(0, StrictGatewayResolverShadow.observers.size)
            val attempts = StrictGatewayResolverShadow.attempts
            controller.pause().resume()
            assertEquals(attempts, StrictGatewayResolverShadow.attempts)
            assertEquals(0, StrictGatewayResolverShadow.observers.size)
        } finally { controller.pause().stop().destroy() }
        assertEquals(0, StrictGatewayResolverShadow.unregisters)
        assertTrue(StrictGatewayResolverShadow.observers.isEmpty())
    }
    @Test fun anInstalledLegacyProviderIsNeverRegisteredOrConsulted() {
        StrictGatewayResolverShadow.rejection = null
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.create().start().resume()
            controller.pause().resume()
            assertEquals(0, StrictGatewayResolverShadow.attempts)
            assertEquals(0, StrictGatewayResolverShadow.observers.size)
            assertEquals(0, StrictGatewayResolverShadow.observers.size)
        } finally { controller.pause().stop().destroy() }
        assertEquals(0, StrictGatewayResolverShadow.unregisters)
    }
}

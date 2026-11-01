package com.no5.helm.control

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import android.os.Handler
import java.io.FileDescriptor
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.HomeSourceSettingsStore
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class HelmSourceServiceTest {
    @Before fun boundedTransportFixture() {
        // Service tests exercise source/Binder/surface wiring. Socket deadlines/peer checks have
        // separate injected-stream tests; never start native I/O against the host from this suite.
        HelmSourceService.rootChannelFactory = { _, connected, packet, disconnected ->
            HelmRootChannel(connected, packet, disconnected, HelmRootChannel.Dependencies(
                connection = { error("Service fixture cannot acquire a native socket") },
                main = object : HelmRootChannel.MainQueue {
                    override fun isMainThread() = Looper.myLooper() === Looper.getMainLooper()
                    override fun post(task: Runnable) = Handler(Looper.getMainLooper()).post(task)
                },
                runtime = object : HelmRootChannel.Runtime {
                    override fun tryExecute(task: Runnable) = false
                    override fun watch(task: Runnable) = HelmRootChannel.Cancellation { }
                }, uptime = { 1L }, closeDescriptor = { _: FileDescriptor -> error("No descriptor in service fixture") }))
        }
    }
    @After fun clearRegistry() {
        HelmSourceRegistry.resetForTest()
        HelmSourceService.resetRootChannelFactoryForTest()
    }

    @Test fun foregroundNotificationStickyLifecycleAndSignatureManifestProtectOneOwner() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
        assertFalse(HelmSourceService.isForegroundReady(app))
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        val service = host.get(); val shadow = shadowOf(service)
        assertSame(owner, service.sourceOwner)
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertEquals(HelmSourceService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(HelmSourceService.CHANNEL_ID, shadow.lastForegroundNotification.channelId)
        assertEquals("No5 HELM", shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("HELM · AUDIO · SONAR", shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(HelmSourceService.foregroundReadiness.value)
        assertTrue(HelmSourceService.isForegroundReady(app))
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
        assertEquals(1, factory.creates)
        val info = app.packageManager.getServiceInfo(ComponentName(app, HelmSourceService::class.java), 0)
        assertTrue(info.exported); assertEquals("com.no5.helm.permission.SYSTEM_CONTROL", info.permission)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        assertEquals(0, shadowOf(app.contentResolver).getContentObservers(HomeGatewayContract.URI).size)
        host.destroy()
        assertEquals(0, shadowOf(app.contentResolver).getContentObservers(HomeGatewayContract.URI).size)
        assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertFalse(HelmSourceService.isForegroundReady(app))
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
    }

    @Test fun gatewaySessionObservationUpdatesWithoutAnyActivityOrHomeProvider() {
        val app = RuntimeEnvironment.getApplication() as Application
        val store = ObservedGatewayFixtureStore()
        val factory = OwnerFixtureFactory()
        val owner = HelmSourceOwner(store, factory)
        HelmSourceRegistry.installForTest(app, owner)
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        val old = owner.borrowAudio(); val generation = owner.sessionGeneration
        store.replaceSession()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(owner.sessionGeneration > generation); assertEquals(2, factory.creates)
        assertFalse(old.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
        assertEquals(0, factory.audioCommands)
        host.destroy(); assertEquals(2, factory.sessionCloses)
    }

    @Test fun repeatedExplicitForegroundStartsAdoptTheSameOwnerAndOneFactory() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        repeat(4) {
            assertTrue(HelmSourceService.startFromForeground(activity.get()))
            assertFalse("Enqueued FGS start is not a completed service", HelmSourceService.isForegroundReady(app))
            assertFalse(HelmSourceService.foregroundStartupFailure.value)
        }
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        assertSame(owner, host.get().sourceOwner)
        repeat(4) {
            assertTrue(HelmSourceService.startFromForeground(activity.get()))
            assertTrue("A warm owner remains ready", HelmSourceService.isForegroundReady(app))
            assertFalse(HelmSourceService.foregroundStartupFailure.value)
        }
        assertEquals(1, factory.creates)
        activity.destroy(); assertEquals(0, factory.sessionCloses)
        host.destroy(); assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
    }

    @Test fun bindWithoutExplicitForegroundBootstrapCannotCreateStandaloneTransport() {
        HelmSourceRegistry.resetForTest()
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        assertNull(host.get().onBind(Intent()))
        assertTrue(shadowOf(host.get()).isStoppedBySelf)
        assertNull(HelmSourceRegistry.peek(RuntimeEnvironment.getApplication()))
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertTrue(HelmSourceService.foregroundStartupFailure.value)
        host.destroy()
    }

    @Test fun startForegroundFailureDisposesOwnerAndLeavesBorrowedPortsUnavailable() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        val loan = owner.borrowAudio()
        val host = Robolectric.buildService(HelmSourceService::class.java)
        shadowOf(host.get()).setThrowInStartForeground(SecurityException("Fixture FGS rejection"))
        host.create()
        assertTrue(owner.isDisposed); assertNull(host.get().onBind(Intent()))
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertTrue(HelmSourceService.foregroundStartupFailure.value)
        assertFalse(HelmSourceService.isForegroundReady(app))
        assertFalse(loan.available(AudioCommand.Transport(TransportAction.PLAY)))
        assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
        host.destroy(); assertEquals(1, factory.factoryCloses)
    }

    @Test fun explicitActivityStartFailureDoesNotCreateAFallbackOwner() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        val host = Robolectric.buildActivity(RejectingActivity::class.java).create()
        assertFalse(HelmSourceService.startFromForeground(host.get()))
        assertEquals(1, factory.creates); assertTrue(owner.isDisposed)
        assertFalse(HelmSourceService.isForegroundReady(app))
        assertTrue(HelmSourceService.foregroundStartupFailure.value)
        assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
        host.destroy()
    }

    @Test fun readinessWaitsForInitializationAfterTheCheapForegroundNotification() {
        val app = RuntimeEnvironment.getApplication() as Application
        val store = ObservedGatewayFixtureStore(); val factory = OwnerFixtureFactory()
        val owner = HelmSourceOwner(store, factory)
        HelmSourceRegistry.installForTest(app, owner)
        val host = Robolectric.buildService(HelmSourceService::class.java)
        var observedInitialization = false
        store.onIdentityRead = {
            observedInitialization = true
            assertEquals(HelmSourceService.NOTIFICATION_ID, shadowOf(host.get()).lastForegroundNotificationId)
            assertEquals("No5 HELM", shadowOf(host.get()).lastForegroundNotification.extras.getString(Notification.EXTRA_TITLE))
            assertFalse("Promotion alone is not successful initialization", HelmSourceService.foregroundReadiness.value)
        }
        host.create()
        assertTrue(observedInitialization)
        assertTrue(HelmSourceService.isForegroundReady(app))
        host.destroy()
    }

    @Test fun initializationFailureAfterForegroundPromotionNeverPublishesReadiness() {
        val app = RuntimeEnvironment.getApplication() as Application
        val store = ObservedGatewayFixtureStore(); val factory = OwnerFixtureFactory()
        val owner = HelmSourceOwner(store, factory)
        HelmSourceRegistry.installForTest(app, owner)
        store.onIdentityRead = { throw IllegalStateException("Fixture post-promotion initialization failure") }
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        assertEquals(HelmSourceService.NOTIFICATION_ID, shadowOf(host.get()).lastForegroundNotificationId)
        assertNotNull(host.get().startupFailure)
        assertTrue(owner.isDisposed)
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertTrue(HelmSourceService.foregroundStartupFailure.value)
        assertFalse(HelmSourceService.isForegroundReady(app))
        assertNull(host.get().onBind(Intent()))
        host.destroy()
        assertEquals(1, factory.sessionCloses); assertEquals(1, factory.factoryCloses)
    }

    @Test fun matchingOwnerRetirementAndLateOldServiceDestroyCannotRetireAReplacement() {
        val app = RuntimeEnvironment.getApplication() as Application
        val firstFactory = OwnerFixtureFactory(); val firstOwner = HelmSourceOwner(OwnerFixtureStore(), firstFactory)
        HelmSourceRegistry.installForTest(app, firstOwner)
        val first = Robolectric.buildService(HelmSourceService::class.java).create()
        assertTrue(HelmSourceService.isForegroundReady(app))
        firstOwner.disposeService()
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertFalse(HelmSourceService.isForegroundReady(app))
        val nextFactory = OwnerFixtureFactory(); val nextOwner = HelmSourceOwner(OwnerFixtureStore(), nextFactory)
        HelmSourceRegistry.installForTest(app, nextOwner)
        assertFalse(HelmSourceService.isForegroundReady(app))
        val next = Robolectric.buildService(HelmSourceService::class.java).create()
        assertTrue(HelmSourceService.isForegroundReady(app))
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
        first.destroy()
        assertSame(nextOwner, HelmSourceRegistry.peek(app))
        assertTrue(HelmSourceService.foregroundReadiness.value)
        assertTrue(HelmSourceService.isForegroundReady(app))
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
        next.destroy()
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertEquals(1, firstFactory.factoryCloses); assertEquals(1, nextFactory.factoryCloses)
    }

    @Test fun staleInitializationFailureCannotOverwriteTheNewPreparedOwner() {
        val app = RuntimeEnvironment.getApplication() as Application
        val store = ObservedGatewayFixtureStore(); val firstFactory = OwnerFixtureFactory()
        val firstOwner = HelmSourceOwner(store, firstFactory)
        val nextFactory = OwnerFixtureFactory(); val nextOwner = HelmSourceOwner(OwnerFixtureStore(), nextFactory)
        HelmSourceRegistry.installForTest(app, firstOwner)
        store.onIdentityRead = {
            store.onIdentityRead = null
            HelmSourceRegistry.installForTest(app, nextOwner)
            throw IllegalStateException("Fixture old initialization completed after owner replacement")
        }
        val old = Robolectric.buildService(HelmSourceService::class.java).create()
        assertNotNull(old.get().startupFailure)
        assertSame(nextOwner, HelmSourceRegistry.peek(app))
        assertFalse(HelmSourceService.foregroundReadiness.value)
        assertFalse("Failure belongs to the retired owner", HelmSourceService.foregroundStartupFailure.value)
        val next = Robolectric.buildService(HelmSourceService::class.java).create()
        assertTrue(HelmSourceService.isForegroundReady(app))
        old.destroy()
        assertTrue(HelmSourceService.isForegroundReady(app))
        assertFalse(HelmSourceService.foregroundStartupFailure.value)
        next.destroy()
        assertEquals(1, firstFactory.factoryCloses); assertEquals(1, nextFactory.factoryCloses)
    }

    @Test fun confirmedLanguageRefreshOnlyNotifiesTheSameReadyServiceWithoutStartingSources() {
        val app = RuntimeEnvironment.getApplication() as Application
        val previousLanguage = com.no5.ui.UiLanguage.current(app)
        val factory = OwnerFixtureFactory(); val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        val manager = app.getSystemService(NotificationManager::class.java)
        HelmSourceService.refreshNotificationLanguage(app)
        assertNull(shadowOf(manager).getNotification(HelmSourceService.NOTIFICATION_ID))
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        try {
            com.no5.ui.UiLanguage.acceptConfirmed(app, "en")
            HelmSourceService.refreshNotificationLanguage(app)
            val notification = shadowOf(manager).getNotification(HelmSourceService.NOTIFICATION_ID)
            assertEquals("No5 HELM", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Shared sources for HELM, AUDIO and SONAR", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("No5 HELM", manager.getNotificationChannel(HelmSourceService.CHANNEL_ID).name.toString())
            assertTrue(HelmSourceService.isForegroundReady(app))
            assertEquals(1, factory.creates)
        } finally {
            host.destroy()
            com.no5.ui.UiLanguage.acceptConfirmed(app, previousLanguage)
        }
        HelmSourceService.refreshNotificationLanguage(app)
        assertNull(shadowOf(manager).getNotification(HelmSourceService.NOTIFICATION_ID))
        assertFalse(HelmSourceService.isForegroundReady(app))
        assertEquals(1, factory.sessionCloses)
    }

    @Test fun distinctAuthenticatedHeaderBinderAndUnattachedNativeSurfaceEndWithService() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = OwnerFixtureFactory()
        HelmSourceRegistry.installForTest(app, HelmSourceOwner(OwnerFixtureStore(), factory))
        val host = Robolectric.buildService(HelmSourceService::class.java).create()
        val service = host.get()
        assertNull(service.startupFailure?.stackTraceToString(), service.startupFailure)
        val knobs = service.onBind(Intent())
        val headers = service.onBind(Intent(com.no5.header.android.HeaderAndroidContract.BIND_ACTION))
        assertNotNull(knobs); assertNotNull(headers); assertNotSame(knobs, headers)
        val view = requireNotNull(service.sharedHeaderView)
        assertFalse(view.isAttachedToWindow)
        assertNull(view.onSystemNavigationAction)
        assertNull(service.headerState()?.snapshot)
        assertEquals(1, factory.creates)
        host.destroy()
        assertNull(service.sharedHeaderView); assertNull(service.headerState())
        assertNull(service.onBind(Intent(com.no5.header.android.HeaderAndroidContract.BIND_ACTION)))
        assertEquals(1, factory.factoryCloses)
    }

    class RejectingActivity : Activity() {
        override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("Fixture FGS start rejection")
    }
}

/** Explicit session feed fixture: no provider, Binder, credentials or transport. */
private class ObservedGatewayFixtureStore : com.no5.helm.DeviceGatewaySettingsStore {
    private val local = OwnerFixtureStore()
    private val observers = mutableSetOf<() -> Unit>()
    private var revision = 1
    var onIdentityRead: (() -> Unit)? = null
    override val homeIdentity get(): String { onIdentityRead?.invoke(); return "fixture-gateway-$revision" }
    override val warning: String? = null
    override val localSettingsBlocked = false
    override val cachedSecurityProfiles = com.no5.helm.security.GatewaySecurityProfileProvider.NONE
    override val readDiagnostic: com.no5.helm.HomeGatewayReadDiagnostic? = null
    override val refreshInFlight = false
    override fun load() = local.load()
    override fun save(config: SourceConfig) = local.save(config)
    override fun observeHome(onChange: () -> Unit): SourceSubscription {
        observers.add(onChange); return SourceSubscription { observers.remove(onChange) }
    }
    override fun requestHomeRefresh() = Unit
    fun replaceSession() { revision++; observers.toList().forEach { it() } }
    override fun close() { observers.clear() }
}

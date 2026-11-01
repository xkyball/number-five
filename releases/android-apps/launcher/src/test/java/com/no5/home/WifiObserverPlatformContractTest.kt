package com.no5.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.no5.ui.WifiObserver
import com.no5.ui.WifiSignal
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import java.time.Duration

/** Actual observer and Android capability/callback API under shadows, not real VPN/radio hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiObserverPlatformContractTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val connectivity get() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val subscriptions = mutableListOf<WifiObserver.Subscription>()

    @Before fun freshProcessObserver() {
        WifiObserver::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        shadowOf(connectivity).clearAllNetworks()
        shadowOf(wifi).setAccessWifiStatePermission(true)
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED)
    }

    @After fun removeEveryVisibleConsumer() {
        subscriptions.reversed().forEach { it.close() }
        assertTrue("No network callbacks retained after last visible consumer", shadowOf(connectivity).networkCallbacks.isEmpty())
        WifiObserver::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        shadowOf(wifi).setAccessWifiStatePermission(true)
    }

    private fun networkInfo(type: Int) = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED)

    private fun capabilities(rssi: Int?, vpn: Boolean = false, alsoWifi: Boolean = false): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(caps)
        shadow.addTransportType(if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_WIFI)
        if (alsoWifi) shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        if (!vpn) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        else shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (rssi != null) {
            val info = ShadowWifiInfo.newInstance()
            shadowOf(info).setRssi(rssi) // No SSID, BSSID, address, scan or location fixture.
            // WifiInfo implements TransportInfo only on the newer API path;
            // API29 must exercise the real observer's WifiManager fallback.
            if (Build.VERSION.SDK_INT >= 31) shadow.setTransportInfo(info)
            shadowOf(wifi).setConnectionInfo(info)
        }
        return caps
    }

    private fun underlyingWifi(rssi: Int): Network = ShadowNetwork.newInstance(101).also {
        shadowOf(connectivity).addNetwork(it, networkInfo(ConnectivityManager.TYPE_WIFI))
        shadowOf(connectivity).setNetworkCapabilities(it, capabilities(rssi))
    }

    private fun defaultVpn(alsoWifi: Boolean = false) {
        shadowOf(connectivity).setActiveNetworkInfo(networkInfo(ConnectivityManager.TYPE_VPN))
        val active = requireNotNull(connectivity.activeNetwork)
        shadowOf(connectivity).setNetworkCapabilities(active, capabilities(null, vpn = true, alsoWifi = alsoWifi))
        assertTrue(connectivity.getNetworkCapabilities(active)!!.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private fun observe(): MutableList<WifiSignal> {
        val values = mutableListOf<WifiSignal>()
        subscriptions += WifiObserver.subscribe(app) { values += it }
        shadowOf(Looper.getMainLooper()).idle()
        return values
    }

    @Test fun defaultVpnDoesNotHideTheActualUnderlyingWifiRssi() {
        underlyingWifi(-71)
        defaultVpn()
        val observed = observe()
        assertEquals(WifiSignal.State.UNKNOWN, observed.first().state)
        assertEquals(WifiSignal.State.CONNECTED, observed.last().state)
        assertEquals(-71, observed.last().rssiDbm)
        assertEquals(WifiSignal.fromObservation(true, true, -71).bars, observed.last().bars)
    }

    @Test fun vpnWifiTransportWithoutARealUnderlyingNetworkDoesNotInventConnection() {
        defaultVpn(alsoWifi = true)
        val stale = ShadowWifiInfo.newInstance()
        shadowOf(stale).setRssi(-40)
        shadowOf(wifi).setConnectionInfo(stale)
        assertEquals(WifiSignal.State.DISCONNECTED, observe().last().state)
    }

    @Test fun disabledRadioAndUnavailableRadioEvidenceRemainDistinct() {
        defaultVpn()
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val values = observe()
        assertEquals(WifiSignal.State.OFF, values.last().state)
        shadowOf(wifi).setAccessWifiStatePermission(false)
        subscriptions.single().refresh()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(WifiSignal.State.UNKNOWN, values.last().state)
    }

    @Test fun connectedNetworkWithUnavailableRssiDoesNotUseSentinelAsSignal() {
        underlyingWifi(-127)
        defaultVpn()
        val actual = observe().last()
        assertEquals(WifiSignal.State.CONNECTED, actual.state)
        assertNull(actual.rssiDbm)
        assertEquals(-1, actual.bars)
    }

    @Test fun callbacksWithdrawLostWifiAndDoNotFollowTheDefaultVpn() {
        val physical = underlyingWifi(-68)
        defaultVpn()
        val values = observe()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        shadowOf(connectivity).removeNetwork(physical)
        callback.onLost(physical)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(WifiSignal.State.DISCONNECTED, values.last().state)
        val restored = underlyingWifi(-88)
        callback.onAvailable(restored)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(-88, values.last().rssiDbm)
    }

    @Test fun visibilitySharesOneCallbackAndStaleEpochCannotChangeResumedState() {
        underlyingWifi(-62)
        defaultVpn()
        val first = observe()
        val second = observe()
        assertEquals(first.last(), second.last())
        val stale = shadowOf(connectivity).networkCallbacks.single()
        subscriptions[0].close()
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
        subscriptions[1].close()
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
        val restored = underlyingWifi(-90)
        val resumed = observe()
        assertEquals(WifiSignal.State.UNKNOWN, resumed.first().state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(-90, resumed.last().rssiDbm)
        stale.onLost(restored)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(-90, resumed.last().rssiDbm)
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
    }
}

package com.no5.control

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Parcel
import android.os.Process
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real PackageManager lookups with installed/uninstalled packages; no injected verifier failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PackageServiceVerifierTest {
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val connections = mutableListOf<ServiceConnection>()
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.no5.home"
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(SystemControlContract.SERVICE, service.component)
            connections += connection
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound++ }
    }

    private class UnusedBinder : Binder() {
        var transactions = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactions++
            return false
        }
    }

    private lateinit var context: BindingContext
    private val helmUid get() = Process.myUid() / SystemControlContract.USER_UID_RANGE *
        SystemControlContract.USER_UID_RANGE + 20002
    private val signature = Signature(byteArrayOf(1, 2, 3, 4))

    private fun productPackage(name: String, uid: Int) = PackageInfo().apply {
        packageName = name
        applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid; enabled = true }
        signatures = arrayOf(signature)
    }

    private fun helmPackage(withPermission: Boolean = true) =
        productPackage(SystemControlContract.HELM_PACKAGE, helmUid).apply {
            val helmApplication = applicationInfo
            services = arrayOf(ServiceInfo().apply {
                packageName = SystemControlContract.HELM_PACKAGE
                name = SystemControlContract.SERVICE.className
                applicationInfo = helmApplication
                enabled = true
                exported = true
                permission = SystemControlContract.PERMISSION
            })
            permissions = if (withPermission) arrayOf(PermissionInfo().apply {
                name = SystemControlContract.PERMISSION
                packageName = SystemControlContract.HELM_PACKAGE
                protectionLevel = PermissionInfo.PROTECTION_SIGNATURE
            }) else emptyArray()
        }

    private fun installHelm(withPermission: Boolean = true) {
        val shadow = Shadows.shadowOf(context.packageManager)
        shadow.installPackage(helmPackage(withPermission))
        shadow.setPackagesForUid(helmUid, SystemControlContract.HELM_PACKAGE)
    }

    @Before fun freshPackageManagerFixture() {
        context = BindingContext()
        val shadow = Shadows.shadowOf(context.packageManager)
        shadow.removePackage(SystemControlContract.HELM_PACKAGE)
        shadow.installPackage(productPackage("com.no5.home", helmUid + 1))
    }

    private fun assertMissingIdentity(lookup: () -> Unit) {
        assertThrows(PackageManager.NameNotFoundException::class.java) { lookup() }
        val denied = assertThrows(SecurityException::class.java) { PackageServiceVerifier(context).verify() }
        assertTrue(denied.cause is PackageManager.NameNotFoundException)
    }

    @Test fun missingServiceRefusesIdentityAndClientFocusWithoutBindingOrCrash() {
        assertMissingIdentity { context.packageManager.getServiceInfo(SystemControlContract.SERVICE, 0) }
        var actions = 0
        SystemControlClient(context) { actions++; ClientDisposition.AUDIO_DEFAULT }.use { client ->
            client.onResume(); client.onWindowFocusChanged(true)
            assertTrue(context.connections.isEmpty())
            assertEquals(0, actions)
        }
    }

    @Test fun missingPermissionRefusesIdentityAndClientFocusWithoutBindingOrCrash() {
        installHelm(withPermission = false)
        assertEquals(SystemControlContract.SERVICE.className,
            context.packageManager.getServiceInfo(SystemControlContract.SERVICE, 0).name)
        assertMissingIdentity { context.packageManager.getPermissionInfo(SystemControlContract.PERMISSION, 0) }
        SystemControlClient(context) { fail("Missing permission must never consume"); ClientDisposition.HANDLED }.use { client ->
            client.onResume(); client.onWindowFocusChanged(true)
            assertTrue(context.connections.isEmpty())
        }
    }

    @Test fun serviceDisappearingAfterBindIsUnboundBeforeRegistration() {
        installHelm()
        assertEquals(helmUid, PackageServiceVerifier(context).verify().uid)
        val service = UnusedBinder()
        SystemControlClient(context) { fail("Disappeared service must never consume"); ClientDisposition.HANDLED }.use { client ->
            client.onResume(); client.onWindowFocusChanged(true)
            assertEquals(1, context.connections.size)
            Shadows.shadowOf(context.packageManager).removeService(SystemControlContract.SERVICE)
            assertMissingIdentity { context.packageManager.getServiceInfo(SystemControlContract.SERVICE, 0) }
            context.connections.last().onServiceConnected(SystemControlContract.SERVICE, service)
            assertEquals(1, context.unbound)
            assertEquals(0, service.transactions)
        }
        assertEquals(1, context.unbound)
    }

    @Test fun permissionDisappearingAfterBindIsUnboundBeforeRegistration() {
        installHelm()
        assertEquals(helmUid, PackageServiceVerifier(context).verify().uid)
        val service = UnusedBinder()
        SystemControlClient(context) { fail("Disappeared permission must never consume"); ClientDisposition.HANDLED }.use { client ->
            client.onResume(); client.onWindowFocusChanged(true)
            assertEquals(1, context.connections.size)
            installHelm(withPermission = false)
            assertMissingIdentity { context.packageManager.getPermissionInfo(SystemControlContract.PERMISSION, 0) }
            context.connections.last().onServiceConnected(SystemControlContract.SERVICE, service)
            assertEquals(1, context.unbound)
            assertEquals(0, service.transactions)
        }
        assertEquals(1, context.unbound)
    }

    @Test fun validInstalledIdentityAllowsExplicitRetryAfterPreviouslyMissingService() {
        SystemControlClient(context) { fail("No callback sent"); ClientDisposition.HANDLED }.use { client ->
            client.onResume(); client.onWindowFocusChanged(true)
            assertTrue(context.connections.isEmpty())
            installHelm()
            assertEquals(helmUid, PackageServiceVerifier(context).verify().uid)
            client.retryConnection()
            assertEquals(1, context.connections.size)
            client.onPause()
            assertEquals(1, context.unbound)
        }
    }
}

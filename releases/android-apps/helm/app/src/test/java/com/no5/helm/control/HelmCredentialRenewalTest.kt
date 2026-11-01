package com.no5.helm.control

import com.no5.domain.*
import com.no5.helm.*
import com.no5.helm.network.GatewayCredentialRenewal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HelmCredentialRenewalTest {
    private class Store : DeviceGatewaySettingsStore, SourceSettingsStore by OwnerFixtureStore() {
        var credentialIdentity="credential-1"
        private var saved=SourceConfig(mode=SourceMode.PI,endpoint="https://no5-marineos-lab.local:9443",
            connectionEnabled=true,weatherProvider=WeatherProviderMode.DISABLED)
        override fun load()=saved
        override fun save(config:SourceConfig){saved=config}
        override val warning:String?=null
        override val homeIdentity get()=credentialIdentity
        override val localSettingsBlocked=false
        override val cachedSecurityProfiles:com.no5.helm.security.GatewaySecurityProfileProvider?=null
        override val readDiagnostic:com.no5.helm.HomeGatewayReadDiagnostic?=null
        override val refreshInFlight=false
        override fun observeHome(onChange:()->Unit)=SourceSubscription{}
        override fun requestHomeRefresh(){}
        override fun close(){}
    }
    @Test fun confirmedRenewalsKeepBorrowedPortsGenerationSubscriptionsAndHistoryOwner() {
        val store=Store(); val delegate=OwnerFixtureFactory(); var renewable=true;var renewals=0
        val factory=SourceSessionFactory { config,identity ->
            val raw=delegate.create(config,identity)
            object:No5SourceSession by raw,GatewayCredentialRenewal {
                override fun renewGatewayCredentials():Boolean {renewals++;return renewable}
            }
        }
        val owner=HelmSourceOwner(store,factory)
        try {
            val borrowed=owner.borrowSession();val sonar=borrowed.sonar;val audio=borrowed.audio
            val generation=owner.sessionGeneration;var revisions=0
            val observation=owner.changes.subscribe{revisions++};val initialRevisions=revisions
            repeat(30) {
                store.credentialIdentity="credential-${it+2}"
                assertTrue(owner.synchronizeHome(requestRefresh=false))
                assertEquals(generation,owner.sessionGeneration)
                assertSame(sonar,borrowed.sonar);assertSame(audio,borrowed.audio)
            }
            assertEquals(30,renewals);assertEquals(1,delegate.creates);assertEquals(0,delegate.sessionCloses)
            assertEquals(initialRevisions,revisions)
            // A changed trust authority, expired profile or failed update must retire the old ports.
            renewable=false;store.credentialIdentity="other-authority"
            assertTrue(owner.synchronizeHome(requestRefresh=false))
            assertTrue(owner.sessionGeneration>generation);assertEquals(2,delegate.creates)
            assertEquals(1,delegate.sessionCloses)
            assertFalse(audio.submit(AudioCommand.Transport(TransportAction.PLAY)).accepted)
            observation.close();borrowed.close()
        } finally {owner.close()}
    }
}

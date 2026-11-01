package com.no5.helm.network

import com.no5.domain.*
import com.no5.gateway.*
import com.no5.helm.security.GatewaySecurityProfile
import com.no5.helm.security.GatewaySecurityProfileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class GatewayCredentialFactoryTest {
    private class Http(val profile:GatewaySecurityProfile,val endpoint:String):PiHttpTransport {
        var closes=0
        override fun get(path:String):String=error("No fixture performs network I/O")
        fun authorization():String {
            var header=""
            profile.configureHttps(endpoint+"/v1/sonar",{},{_,value->header=value})
            return header
        }
        override fun close(){closes++}
    }
    @Test fun realFactoryRenewsSharedProfileWithoutRecreatingAnyPortAndKeepsTrustChangesSeparate() {
        ShadowBuild.setHardware("ranchu")
        val origin=GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!
        var clock=100_000L
        fun fresh(role:GatewayAppRole=GatewayAppRole.HELM)=LuminaGatewaySession(origin,syntheticCaPem,
            syntheticToken(role,origin.url,clock,clock+900_000),SYNTHETIC_KEY_ID,clock,clock+900_000,role,{clock})
        var credential=fresh();val transports=mutableListOf<Http>();var jobs=0;var streamCloses=0
        val factory=NetworkSourceSessionFactory(now={clock},
            securityProfiles=GatewaySecurityProfileProvider{credential.profile()},
            scheduler={PiPollScheduler{_,_->jobs++;SourceSubscription{}}},
            securedTransport={endpoint,_,profile->Http(profile,endpoint).also{transports+=it}},
            securedSonarStream={_,_->object:SonarStreamTransport {
                override fun connect(listener:SonarStreamTransport.Listener)=SourceSubscription{}
                override fun close(){streamCloses++}
            }},securedAudioStream={_,_->object:AudioFaderStreamTransport {
                override fun connect(listener:AudioFaderStreamTransport.Listener)=SourceSubscription{}
                override fun send(value:String)=false
                override fun close(){streamCloses++}
            }})
        val session=factory.create(SourceConfig(mode=SourceMode.PI,endpoint=origin.url,connectionEnabled=true,
            weatherProvider=WeatherProviderMode.DISABLED),SourceIdentity("stable-session",SourceMode.PI,"REAL"))
        val renewal=session as GatewayCredentialRenewal
        val count=transports.size;val scheduled=jobs;val sonar=session.sonar;val audio=session.audio
        try {
            repeat(30) {
                clock+=840_000;credential.close();credential=fresh()
                assertTrue(renewal.renewGatewayCredentials())
                val expected="Bearer "+syntheticToken(GatewayAppRole.HELM,origin.url,clock,clock+900_000)
                assertTrue(transports.all{it.authorization()==expected && it.closes==0})
                assertSame(sonar,session.sonar);assertSame(audio,session.audio)
                assertEquals(count,transports.size);assertEquals(scheduled,jobs);assertEquals(0,streamCloses)
            }
            clock+=1_000;credential.close();credential=fresh(GatewayAppRole.MAP)
            assertFalse(renewal.renewGatewayCredentials())
        } finally {session.close();credential.close();factory.close()}
        assertTrue(transports.all{it.closes==1});assertEquals(2,streamCloses)
        assertFalse(renewal.renewGatewayCredentials())
    }
}

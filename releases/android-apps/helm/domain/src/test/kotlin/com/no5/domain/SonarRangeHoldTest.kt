package com.no5.domain
import org.junit.Assert.*
import org.junit.Test
class SonarRangeHoldTest {
 private class Input {
  var now=1000000L;var id=0L
  val model=SonarModel{now}.also{it.connect("HOST GEOMETRY HOLD");it.acquisition(true);it.updateContext(SonarEpoch("same",1,1),SonarPingSource.OWN)}
  init{ping();model.setDisplay(model.display.copy(rangeMode=SonarRangeMode.AUTO,cropEnd=200.0))}
  fun ping(confirmed:Boolean=true,bin:Double=100.0,dt:Long=50,follow:Boolean=confirmed){
   now+=dt;id++;model.setCalibration(Calibration(false,1.0))
   model.setAnalysis(SonarGeometry(3.072,SonarTxReference.HOST_VERIFIED),if(confirmed)SonarBottom(bin,.99)else SonarBottom(),
    SonarPresentation(id,if(confirmed)SonarAnalysisQuality.LOCKED else SonarAnalysisQuality.UNSTABLE,null,"auto","auto_raw",confirmed,follow,if(confirmed)(bin*1.3).coerceIn(32.0,1023.0)else null,!confirmed),true,"0.4.33",SonarAmplitude(4095,12,true))
   model.frame(SonarFrame(ShortArray(1024),id,now),pingSource=SonarPingSource.OWN)
  }
 }
 @Test fun repeatedShortHoldsPauseButDoNotEraseConfirmedSmallerNeed(){
  val h=Input();repeat(600){h.ping(confirmed=it%10!=9)}
  assertEquals("Unconfirmed intervals cannot count toward30s",200.0,h.model.display.cropEnd,0.0)
  repeat(220){h.ping(confirmed=it%10!=9)}
  assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun hostFollowHoldNeverEarnsConfirmationTime(){
  val h=Input();repeat(400){h.ping()};repeat(200){h.ping(follow=false)}
  repeat(400){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(201){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun sourceContextChangeCannotReusePreviousConfirmationTime(){
  val h=Input();repeat(400){h.ping()};h.model.updateContext(SonarEpoch("new",1,1),SonarPingSource.OWN)
  h.ping();h.model.setDisplay(h.model.display.copy(rangeMode=SonarRangeMode.AUTO,cropEnd=200.0))
  repeat(400){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(201){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun realSourceGapStartsACompleteNewConfirmationWindow(){
  val h=Input();repeat(400){h.ping()};h.ping(confirmed=false,dt=3000)
  repeat(400){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(201){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun contraryConfirmedNeedResetsTheSmallerSeries(){
  val h=Input();repeat(400){h.ping()};h.ping(bin=175.0)
  repeat(400){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(201){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun knownInterruptionWithoutFramesCannotBridgeConfirmationTime(){
  val h=Input();repeat(400){h.ping()}
  h.model.setSourceStale(true);h.now+=1500;h.model.setSourceStale(false)
  repeat(200){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(401){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
 @Test fun staleInputAndFreezeNeverEarnConfirmationTime(){
  val h=Input();repeat(200){h.ping()};h.model.setSourceStale(true)
  repeat(200){h.ping()};h.model.setSourceStale(false)
  repeat(200){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  h.model.setFreeze(true);repeat(200){h.ping()};h.model.setFreeze(false)
  repeat(200){h.ping()};assertEquals(200.0,h.model.display.cropEnd,0.0)
  repeat(401){h.ping()};assertEquals(120.0,h.model.display.cropEnd,.000001)
 }
}

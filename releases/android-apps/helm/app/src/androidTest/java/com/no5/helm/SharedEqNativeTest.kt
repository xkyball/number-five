package com.no5.helm
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
@RunWith(AndroidJUnit4::class)
class SharedEqNativeTest {
 @Test fun byteBridgeKeepsUneditedCoordinatesBitExact(){
  val policy=identity()
  for(delta in 0L until 32L){
   val peer=SoundEqPoint("peer",Double.fromBits(1000.0.toBits()+delta),0.0,10.0)
   val previous=SoundPreset("bridge","Bridge","stereo_4_1",listOf(peer,SoundEqPoint("edited",10000.0,0.0,10.0)),eqEnabled=true)
   val requested=previous.copy(eqPoints=previous.eqPoints.map{if(it.id=="edited")it.copy(gainDb=0.5)else it})
   val actual=NativeEqValidator.project(previous,requested,"edited",false,policy)
   assertEquals(requested,actual);assertEquals(peer.frequencyHz.toBits(),actual.eqPoints.first().frequencyHz.toBits())
  }
 }
 private fun identity():SoundEqValidationPolicy {
  val bytes=SharedEqNative.process("{\"kind\":\"policy\"}".toByteArray())!!;val p=JSONObject(String(bytes,Charsets.UTF_8)).getJSONObject("policy")
  val expected=InstrumentationRegistry.getArguments().getString("eqKernelSha");require(!expected.isNullOrBlank());assertEquals(expected,p.getString("kernelSha256"))
  return SoundEqValidationPolicy(p.getString("schema"),p.getString("kernelSha256"),p.getInt("sampleRateHz"),p.getInt("gridPoints"),p.getInt("minReserveDb"),p.getInt("maxReserveDb"),p.getInt("experimentalBoostDb"))
 }
 @Test fun actualPackagedJniProjectsRegularAndExperimentalLimits(){
  val policy=identity()
  for(reserve in listOf(6.0,12.0,18.0))for(exp in listOf(false,true)){
   val previous=SoundPreset("p","Native JNI","stereo_4_1",listOf(SoundEqPoint("a",1000.0,0.0,2.0)),eqEnabled=true,reserveDb=reserve)
   val requested=previous.copy(eqPoints=previous.eqPoints.map{it.copy(gainDb=21.0)})
   val actual=NativeEqValidator.project(previous,requested,"a",exp,policy);assertTrue(actual.eqEnabled);assertTrue(actual.eqPoints.single().gainDb<=reserve+if(exp)3.0 else 0.0)
   assertEquals(previous.eqPoints.single().frequencyHz.toBits(),actual.eqPoints.single().frequencyHz.toBits());assertEquals(previous.eqPoints.single().q.toBits(),actual.eqPoints.single().q.toBits())
  }
 }
 @Test fun mismatchedPolicyCannotValidate(){val p=SoundPreset("p","Mismatch","stereo_4_1",emptyList());try{NativeEqValidator.project(p,p,null,false,identity().copy(kernelSha256="0".repeat(64)));fail("mismatch accepted")}catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("EQ_VALIDATOR_MISMATCH"))}}
 @Test fun cutsAndNeutralBandsCannotCross(){val policy=identity();for(g in listOf(-6.0,0.0,3.0)){
  val p=SoundPreset("p","Bands","stereo_4_1",listOf(SoundEqPoint("a",500.0,g,5.0),SoundEqPoint("b",1000.0,0.0,5.0)),eqEnabled=true)
  val requested=p.copy(eqPoints=p.eqPoints.map{if(it.id=="a")it.copy(frequencyHz=10000.0)else it});val actual=NativeEqValidator.project(p,requested,"a",false,policy)
  assertTrue(actual.eqPoints[0].highHz<=actual.eqPoints[1].lowHz+1e-9);assertEquals(p.eqPoints[1],actual.eqPoints[1]);assertEquals(g.toBits(),actual.eqPoints[0].gainDb.toBits())
 }}
}

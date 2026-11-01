package com.no5.helm.gpu
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
class SonarGpuSceneUpdatePolicyTest {
 private fun frame()=SonarGpuFrame(SonarRingBuffer(4800),0,1,"own-1",SonarDisplay(historySeconds=240.0),.03,4095,0.0,1023.0,true,false,displayRevision=1)
 @Test fun newDataWaitsForBufferWhileUiOnlyInteractionStillDraws(){
  val p=SonarGpuSceneUpdatePolicy();val f=frame();val health=listOf("healthy");val audio=Any()
  assertTrue(p.immediate(f,DepthUnit.METERS,audio,health))
  val next=f.copy(ringVersion=2);assertFalse(p.immediate(next,DepthUnit.METERS,audio,health))
  assertFalse("duplicate command/status must not repaint the old buffer",p.immediate(next,DepthUnit.METERS,audio,health))
  assertTrue("cursor/audio repaint without a new raw ping",p.immediate(next,DepthUnit.METERS,audio,listOf("cursor changed")))
  assertFalse(p.immediate(next.copy(ringVersion=3),DepthUnit.METERS,audio,listOf("cursor changed")))
 }
 @Test fun warningAndHealthChangesNeverWaitForGpu(){
  val p=SonarGpuSceneUpdatePolicy();val f=frame();p.immediate(f,DepthUnit.METERS,null,listOf("healthy"))
  val late=f.copy(ringVersion=2);assertTrue(p.immediate(late,DepthUnit.METERS,null,listOf("System überlastet · Bild nicht aktuell")))
  assertFalse(p.immediate(late.copy(ringVersion=3),DepthUnit.METERS,null,listOf("System überlastet · Bild nicht aktuell")))
  assertTrue(p.immediate(late.copy(ringVersion=4),DepthUnit.METERS,null,listOf("source stale")))
 }
 @Test fun sourceEpochFreezeControlsNightAndUnitsRemainImmediate(){
  val changes=listOf<(SonarGpuFrame)->SonarGpuFrame>({it.copy(context="own-2")},{it.copy(ringGeneration=1)},{it.copy(display=it.display.copy(freeze=true))},{it.copy(displayRevision=2)},{it.copy(night=true)})
  for(change in changes){val p=SonarGpuSceneUpdatePolicy();val f=frame();p.immediate(f,DepthUnit.METERS,null,emptyList());assertTrue(p.immediate(change(f).copy(ringVersion=2),DepthUnit.METERS,null,emptyList()))}
  val p=SonarGpuSceneUpdatePolicy();val f=frame();p.immediate(f,DepthUnit.METERS,null,emptyList());assertTrue(p.immediate(f.copy(ringVersion=2),DepthUnit.entries.first{it!=DepthUnit.METERS},null,emptyList()))
 }
 @Test fun exactMetadataIdentityAndConsumerNotificationAreBothRequired(){
  val completion=SonarGpuCompletion("own-1",0,1,2000,1200,17,1000,1000000)
  assertTrue(SonarCompositeRecordBinding.matches(completion,completion,true))
  assertFalse(SonarCompositeRecordBinding.matches(completion,completion,false))
  assertFalse("equal values are not the consumed buffer",SonarCompositeRecordBinding.matches(completion,completion.copy(),true))
  assertFalse(SonarCompositeRecordBinding.matches(completion,completion.copy(pingId=16),true))
  assertFalse(SonarCompositeRecordBinding.matches(null,null,true))
 }
}

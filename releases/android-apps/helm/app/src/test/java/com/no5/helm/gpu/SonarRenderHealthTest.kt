package com.no5.helm.gpu
import org.junit.Assert.*
import org.junit.Test
class SonarRenderHealthTest {
 @Test fun hardTwoSecondBoundaryAndActualPresentation(){
  assertFalse(SonarRenderHealth.presentationOverdue(11,10,1,2_000_000_010L))
  assertTrue(SonarRenderHealth.presentationOverdue(11,10,1,2_000_000_011L))
  assertFalse(SonarRenderHealth.presentationOverdue(11,12,1,9_000_000_000L))
  assertTrue(SonarRenderHealth.presentationOverdue(10,0,1,2_000_000_002L))
  assertFalse(SonarRenderHealth.presentationOverdue(0,0,0,9_000_000_000L))
 }
 @Test fun sourceCanContinueWhileOverloadWarningRetainsLastImage(){
  assertNull(SonarRenderHealth.warning(true,false,2_000_000_000L,false,false))
  assertNotNull(SonarRenderHealth.warning(true,false,2_000_000_001L,false,false))
  assertNull("Brief backpressure is not proof of an old picture",SonarRenderHealth.warning(true,true,1L,false,false))
  assertNotNull(SonarRenderHealth.warning(true,false,null,true,false))
  assertNotNull(SonarRenderHealth.warning(true,false,null,false,true))
  assertNull(SonarRenderHealth.warning(false,true,9_000_000_000L,true,true))
 }
}

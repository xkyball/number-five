package com.no5.helm.ui
import org.junit.Assert.*
import org.junit.Test
class MapM1NavigationTest {
 @Test fun pendingExitCannotRunUntilSameTaskExplicitlyCompletes(){
  val gate=MapTaskExitGate();val task=Any();var requests=0;var navigations=0;var closes=0
  gate.register(task){requests++};gate.leave{navigations++};assertEquals(1,requests);assertEquals(0,navigations)
  gate.leave{navigations+=100};assertEquals(1,requests)
  gate.complete(Any()){closes+=100};assertEquals(0,closes);assertEquals(0,navigations)
  gate.complete(task){closes++};assertEquals(1,closes);assertEquals(1,navigations)
 }
 @Test fun stayingCancelsIntentButLeavesTaskRegistered(){
  val gate=MapTaskExitGate();val task=Any();var n=0;var requests=0
  gate.register(task){requests++};gate.leave{n++};gate.cancel(task);gate.complete(task){};assertEquals(0,n)
  gate.register(task){requests++};gate.leave{n+=2};assertEquals(2,requests);gate.complete(task){};assertEquals(2,n)
 }
 @Test fun unmountedTasksCannotResumeNavigationIntoANewerSession(){
  val gate=MapTaskExitGate();val old=Any();val fresh=Any();var n=0
  gate.register(old){};gate.leave{n++};gate.release(old);gate.complete(old){n+=1000};assertEquals(0,n);gate.register(fresh){};gate.leave{n+=10};gate.complete(old){n+=100};assertEquals(0,n);gate.complete(fresh){};assertEquals(10,n)
 }
 @Test fun navigationRequestedDuringCommitResumesOnlyAfterConfirmedSuccess(){
  val gate=MapTaskExitGate();val task=Any();var navigations=0
  gate.register(task){};assertFalse(gate.isPending(task));gate.leave{navigations++}
  assertTrue(gate.isPending(task));assertEquals(0,navigations)
  // Failed confirmation leaves the task and pending request intact.
  assertTrue(gate.isPending(task));gate.complete(task){};assertEquals(1,navigations)
  assertFalse(gate.isPending(task))
 }
}

package com.no5.helm.ui

/** A pending navigation is resumed only by the same mounted task's successful exit. */
internal class MapTaskExitGate {
 private var owner:Any?=null;private var close:(()->Unit)?=null;private var pending:(()->Unit)?=null
 fun register(id:Any,request:()->Unit){check(owner==null||owner==id){"Only one MAP editing task may own navigation"};owner=id;close=request}
 fun release(id:Any){if(owner==id){owner=null;close=null;pending=null}}
 fun leave(action:()->Unit){if(pending!=null)return;val request=close;if(request==null)action()else{pending=action;request()}}
 fun isPending(id:Any)=owner==id&&pending!=null
 fun cancel(id:Any){if(owner==id)pending=null}
 fun complete(id:Any,finish:()->Unit){if(owner!=id)return;val next=pending;pending=null;owner=null;close=null;finish();next?.invoke()}
}
internal val LocalMapTaskExit=androidx.compose.runtime.staticCompositionLocalOf<MapTaskExitGate?>{null}
@androidx.compose.runtime.Composable internal fun RegisterMapTaskExit(owner:Any,request:()->Unit){
 val gate=LocalMapTaskExit.current
 val latest=androidx.compose.runtime.rememberUpdatedState(request)
 androidx.compose.runtime.DisposableEffect(gate,owner){gate?.register(owner){latest.value()};onDispose{gate?.release(owner)}}
}

/** Existing native decision grammar, with the action named for this MAP task. */
@androidx.compose.runtime.Composable internal fun MapTaskDecision(
 saveLabel:String,onSave:()->Unit,onDiscard:()->Unit,onContinue:()->Unit,
 saving:Boolean=false,saveEnabled:Boolean=true,error:String?=null){
 val theme=androidx.compose.material3.MaterialTheme.colorScheme
 com.no5.ui.No5DecisionDialog(
  title=m1("Änderungen behalten?","Keep changes?","Behou veranderings?"),
  body=m1("Ungespeicherte Änderungen gehen beim Verwerfen verloren.","Discarding loses unsaved changes.","Ongestoorde veranderings gaan verlore wanneer dit verwerp word."),
  night=theme.surface.red<.15f,font=androidx.compose.material3.MaterialTheme.typography.bodyLarge.fontFamily?:androidx.compose.ui.text.font.FontFamily.Default,
  onSave=onSave,onDiscard=onDiscard,onContinue=onContinue,busy=saving,canSave=saveEnabled,error=error,
  saveLabel=saveLabel,discardLabel=m1("Verwerfen","Discard","Verwerp"),continueLabel=m1("Bleiben","Stay","Bly"),prefix="unsaved-changes",dialogTag="unsaved-changes-dialog")
}

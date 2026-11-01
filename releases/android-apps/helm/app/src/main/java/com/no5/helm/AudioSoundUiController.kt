package com.no5.helm

import android.content.Context
import android.os.Trace
import androidx.compose.runtime.*
import com.no5.domain.*
import com.no5.helm.network.AudioSoundCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/** Trace slices isolate the live EQ path without file/network work or curve logging. */
internal inline fun <T> traceEqStage(name:String,block:()->T):T {
    val begun=try{Trace.beginSection(name);true}catch(_:RuntimeException){false}
    return try{block()}finally{if(begun)try{Trace.endSection()}catch(_:RuntimeException){}}
}

/** Paint-only projections. Raw source metadata and command decisions stay untouched. */
internal data class SoundObservationInk(val available:Boolean,val writable:Boolean,val reason:String?,
    val headroomDb:Double,val capabilities:SoundCapabilities,val effectiveCalibration:SoundEffectiveCalibration?,
    val sourceGeneration:String,val configGeneration:String)
/** Stable control availability must not subscribe the entire editor to live headroom/calibration. */
internal data class SoundControlInk(val available:Boolean,val writable:Boolean,val reason:String?,
    val capabilities:SoundCapabilities,val sourceGeneration:String,val configGeneration:String)
internal data class SoundSpectrumInk(val sourceGeneration:String,val sampleRateHz:Int,val fftSize:Int,
    val frequenciesHz:List<Double>,val binsDbfs:List<Double>,val peaksDbfs:List<Double>,val overRangeSamples:Long)

/** UI drafts and close decisions only. The source owns work, preview expiry and all confirmations. */
internal class AudioSoundUiController(private val port:AudioSoundPort?,dispatcher:CoroutineDispatcher=Dispatchers.Main.immediate,private val now:()->Long=System::currentTimeMillis,private val validator:EqValidator=NativeEqValidator,private val validationDispatcher:CoroutineDispatcher=Dispatchers.Default,private val geometryProjector:EqGeometryProjector=CanonicalEqGeometryProjector):AutoCloseable {
    var observed by mutableStateOf(port?.state?.value?:AudioSoundState(reason="NO_SOUND_PORT"));private set
    var spectrum by mutableStateOf<AudioSpectrumFrame?>(null);private set
    val observationInk by derivedStateOf(structuralEqualityPolicy()) {observed.let{SoundObservationInk(it.available,it.writable,it.reason,
        it.headroomDb,it.capabilities,it.effectiveCalibration,it.sourceGeneration,it.configGeneration)}}
    val controlInk by derivedStateOf(structuralEqualityPolicy()) {observed.let{SoundControlInk(it.available,it.writable,it.reason,
        it.capabilities,it.sourceGeneration,it.configGeneration)}}
    val spectrumInk by derivedStateOf(structuralEqualityPolicy()) {spectrum?.let{SoundSpectrumInk(it.sourceGeneration,it.sampleRateHz,
        it.fftSize,it.frequenciesHz,it.binsDbfs,it.peaksDbfs,it.overRangeSamples)}}
    var preset by mutableStateOf<SoundPreset?>(null);private set
    var calibration by mutableStateOf<SoundCalibration?>(null);private set
    // Each UI controller may release only the visibility it actually acquired.
    // A fallback AudioNativeController also owns an idle editor controller on this shared port.
    private var ownsVisibility=false
    private var editorVisible=false
    private var catalogVisible=false
    private var closed by mutableStateOf(false)
    private var savedPreset by mutableStateOf<SoundPreset?>(null)
    private var savedCalibration by mutableStateOf<SoundCalibration?>(null)
    private var presetTouched=false;private var calibrationTouched=false
    private var restoredPresetPending by mutableStateOf(false)
    private var restoredCalibrationPending by mutableStateOf(false)
    private var persistedDraftText:String?=null
    private var newOriginal:SoundPreset?=null
    private var newSource:String?=null
    val newPresetDraft get()=newOriginal!=null
    val hasPresetDraft get()=presetTouched && preset!=null
    fun canResumeDraft(entry:SoundPresetEntry)=hasPresetDraft && !newPresetDraft && !busy && entry.id==preset?.id
    var presetNameRequested by mutableStateOf(false);private set
    var presetName by mutableStateOf("");private set
    private var nameContinuation:(()->Unit)?=null
    private var libraryConfirmation:((AudioSoundState)->Boolean)?=null
    private var appliedAwaitingState=false
    private var awaitingFailureRead by mutableStateOf(false)
    private var confirmationTimeout:Job?=null
    private var presetBasis:SoundRevisionBasis?=null;private var calibrationBasis:SoundRevisionBasis?=null
    var experimental by mutableStateOf(false);private set
    var experimentEnding by mutableStateOf(false);private set
    var eqPending by mutableStateOf(false);private set
    // Immediate local intent only. Never used for DSP readback, saves or recovery.
    var eqTarget by mutableStateOf<SoundEqPoint?>(null);private set
    var eqTargetLimited by mutableStateOf(false);private set
    val eqTargetPending by derivedStateOf(structuralEqualityPolicy()) {eqTarget!=null}
    var eqGestureRange by mutableStateOf<Double?>(null);private set
    val eqActuallyEnabled by derivedStateOf(structuralEqualityPolicy()) {observed.available&&observed.preset?.eqEnabled==true}
    fun beginEqGesture(range:Double){if(range.isFinite()&&range>=12.0)eqGestureRange=range}
    fun endEqGesture(){
        val wasDragging=eqGestureRange!=null;eqGestureRange=null
        if(wasDragging){deferredPersistence?.cancel();deferredPersistence=null;persist()}
    }
    private var normalEqDraft:SoundPreset?=null
    private var afterEq:(()->Unit)?=null
    val canSavePreset get()=!experimental&&!experimentEnding&&!eqPending
    val eqPointMaximum get()=(preset?.reserveDb?:12.0)+if(experimental)3.0 else 0.0
    val presetDirty by derivedStateOf(structuralEqualityPolicy()) {eqPending||restoredPresetPending||(preset!=null && preset!=savedPreset)||presetConflict}
    internal data class CoordinateInput(val x:String,val y:String,val z:String) {
        fun parsed():Result<SoundPosition?> = runCatching {
            if(listOf(x,y,z).all(String::isBlank)) null else {
                fun number(text:String)=text.trim().replace(',','.').toDoubleOrNull()?.takeIf(Double::isFinite)
                SoundPosition(requireNotNull(number(x)),requireNotNull(number(y)),if(z.isBlank())null else requireNotNull(number(z)))
            }
        }
        companion object { fun from(value:SoundPosition?)=CoordinateInput(value?.x?.toString().orEmpty(),value?.y?.toString().orEmpty(),value?.z?.toString().orEmpty()) }
    }
    private var coordinateInputs by mutableStateOf<Map<String,CoordinateInput>>(emptyMap())
    var exactTarget by mutableStateOf<String>("FRONT_LEFT");private set
    private val coordinateIds get()=SoundSpeakerId.entries.map{it.name}+"listener"
    fun chooseExactTarget(id:String){if(id in coordinateIds)exactTarget=id}
    fun coordinateInput(id:String):CoordinateInput {
        val pos=if(id=="listener")calibration?.listenerM else calibration?.speakers?.firstOrNull{it.id.name==id}?.positionM
        return coordinateInputs[id]?:CoordinateInput.from(pos)
    }
    fun editCoordinate(id:String,axis:String,text:String) {
        if(!canEdit(SoundScope.CALIBRATION)||id !in coordinateIds||text.length>40||text.any(Char::isISOControl))return
        val old=coordinateInput(id)
        val value=when(axis){"x"->old.copy(x=text);"y"->old.copy(y=text);"z"->old.copy(z=text);else->return}
        coordinateInputs=coordinateInputs+(id to value)
        val parsed=value.parsed();markGeometryInput(id,parsed.isFailure)
        calibrationTouched=true
        if(parsed.isSuccess){
            val current=calibration?:return;val position=parsed.getOrNull()
            changeCalibration(if(id=="listener")current.copy(listenerM=position,geometryEnabled=current.geometryEnabled&&position!=null)
                else current.copy(speakers=current.speakers.map{if(it.id.name==id)it.copy(positionM=position)else it},geometryEnabled=current.geometryEnabled&&position!=null))
        } else persist()
    }
    private var geometryInvalid by mutableStateOf<Set<String>>(emptySet())
    var measurementResetEpoch by mutableStateOf(0);private set
    var measurementDraft by mutableStateOf<SoundMeasurementDraft?>(null);private set
    var measurementError by mutableStateOf<String?>(null);private set
    private var measurementReturn="delay"
    private var measurementBasis:SoundRevisionBasis?=null
    var placingPosition by mutableStateOf<SoundPositionTarget?>(null);private set
    var selectedPosition by mutableStateOf(SoundPositionTarget.LISTENER);private set
    val measurementDirty get()=measurementDraft?.dirty==true
    val hasInvalidGeometry get()=geometryInvalid.isNotEmpty()
    fun markGeometryInput(id:String,invalid:Boolean){geometryInvalid=if(invalid)geometryInvalid+id else geometryInvalid-id}
    val calibrationDirty by derivedStateOf(structuralEqualityPolicy()) {restoredCalibrationPending||(calibration!=null && calibration!=savedCalibration)||hasInvalidGeometry||measurementDirty||calibrationConflict}
    var section by mutableStateOf("eq");private set
    var selectedPoint by mutableStateOf<String?>(null);private set
    var addingPoint by mutableStateOf(false);private set
    fun togglePointPlacement(){addingPoint=!addingPoint}
    fun placeNeutralPoint(frequency:Double){
        val p=preset?:return
        if(p.eqPoints.size>=16 || p.eqPoints.any{kotlin.math.abs(kotlin.math.ln(it.frequencyHz/frequency))<kotlin.math.ln(1.025)})return
        addPoint(frequency,0.0);addingPoint=false
    }
    var selectedSpeaker by mutableStateOf(SoundSpeakerId.FRONT_LEFT);private set
    var calibrationReturn by mutableStateOf("audio");private set
    var spectrumStyle by mutableStateOf("color")
    var closeDecision by mutableStateOf<SoundScope?>(null);private set
    var status by mutableStateOf<String?>(null);private set
    var eqBoundReason by mutableStateOf<String?>(null);private set
    var eqActivationRejected by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var libraryCompletion by mutableStateOf(0L);private set
    private var libraryOperation=false
    private var presetConflict by mutableStateOf(false)
    private var calibrationConflict by mutableStateOf(false)
    val conflicted get()=presetConflict||calibrationConflict
    private val activeScope get()=if(section in setOf("delay","gain","measure","place","positions"))SoundScope.CALIBRATION else SoundScope.PRESET
    private fun conflict(area:SoundScope)=if(area==SoundScope.PRESET)presetConflict else calibrationConflict
    private fun canEdit(area:SoundScope)=!closed && observed.available && observed.writable && !busy && !awaitingFailureRead && !conflict(area) && (observed.preview==null || observed.preview?.id==previewId)
    private var previewId=java.util.UUID.randomUUID().toString()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val subscriptions=mutableListOf<SourceSubscription>()
    private var storage:android.content.SharedPreferences?=null;private var loaded=false
    private var latestPresetPreview=0L;private var latestCalibrationPreview=0L
    private var latestPresetRequested:SoundPreset?=null
    private var lastPointPreview=0L
    private var latestLocallyConstrained=false
    private var latestProjectedIntent:SoundEqEditIntent?=null
    private var latestProjectedBasis:SoundRevisionBasis?=null
    private data class LocalGoal(val token:Long,val previous:SoundPreset,val requested:SoundPreset,val pointId:String?,val experimental:Boolean,val policy:SoundEqValidationPolicy?,val source:String,val config:String,val revision:Long,val explicitActivation:Boolean,val queuedNs:Long=System.nanoTime())
    private data class LocalProjection(val preset:SoundPreset,val constrained:Boolean)
    internal val localEqTimings=EqLocalTimings()
    private val localGoals=Channel<LocalGoal>(Channel.CONFLATED)
    private var localGoal:LocalGoal?=null
    private var latestLocalInput:LocalGoal?=null
    private data class LocalRetryBasis(val source:String,val config:String,val revision:Long,val preview:String?,val experimental:Boolean,val base:SoundPreset,val target:SoundEqPoint)
    private var staleRetryBasis:LocalRetryBasis?=null
    private var validationUnavailable by mutableStateOf(false)
    val eqCurveEditable get()=editable&&!validationUnavailable
    private var localSequence=0L
    private var localEpoch=0L
    private fun invalidateLocalGoals(){if(localGoal!=null){eqPending=false;if(latestPresetPreview<0)latestPresetPreview=0;afterEq=null};validationUnavailable=false;staleRetryBasis=null;localEpoch++;localGoal=null;latestLocalInput=null;while(localGoals.tryReceive().isSuccess){} }
    private fun queueLocal(value:SoundPreset,previousPoint:SoundEqPoint?,explicitActivation:Boolean,forceRevalidation:Boolean=false):Long {
        val base=preset?:return 0
        latestLocalInput?.let{old->if(!forceRevalidation&&eqPending&&old.previous==base&&old.requested==value&&old.pointId==previousPoint?.id&&old.experimental==experimental&&old.policy==observed.eqEditing?.validationPolicy&&old.source==observed.sourceGeneration&&old.config==observed.configGeneration&&old.revision==observed.configurationRevision)return latestPresetPreview}
        val goal=LocalGoal(-(++localSequence),base,value,previousPoint?.id,experimental,observed.eqEditing?.validationPolicy,observed.sourceGeneration,observed.configGeneration,observed.configurationRevision,explicitActivation)
        val moving=previousPoint!=null&&(value.eqPoints.firstOrNull{it.id==previousPoint.id}!=previousPoint||eqPending)
        localGoal=goal;latestLocalInput=goal;latestPresetPreview=goal.token;latestPresetRequested=value;presetTouched=true;eqPending=true
        if(previousPoint==null){eqTarget=null;eqTargetLimited=false}
        if(moving)lastPointPreview=goal.token
        localGoals.trySend(goal);return if(latestPresetPreview!=goal.token)latestPresetPreview else goal.token
    }
    private fun startLocalWorker(){scope.launch {
        for(goal in localGoals){
            val epoch=localEpoch
            val work=withContext(validationDispatcher){val start=System.nanoTime();val result=runCatching {
                val requestedPoint=goal.pointId?.let{id->goal.requested.eqPoints.first{it.id==id}}
                val priorPoint=goal.pointId?.let{id->goal.previous.eqPoints.first{it.id==id}}
                val geometry=if(requestedPoint!=null&&priorPoint!=null)traceEqStage("No5.EQ.GeometryProjection"){
                    geometryProjector.project(priorPoint,requestedPoint,goal.previous.eqPoints)
                }else null
                val bounded=if(geometry!=null&&goal.pointId!=null)goal.requested.copy(eqPoints=goal.requested.eqPoints.map{if(it.id==goal.pointId)geometry.point else it})else goal.requested
                val projected=traceEqStage("No5.EQ.LocalValidation") {
                    stableEqProjection(goal.previous,bounded,validator.project(goal.previous,bounded,goal.pointId,goal.experimental,goal.policy),goal.pointId)
                }
                LocalProjection(projected,geometry?.constrained==true||projected!=goal.requested)
            };Triple(result,start,System.nanoTime())}
            val result=work.first
            localEqTimings.record(EqLocalTiming(goal.token,goal.queuedNs,work.second,work.third))
            if(closed||epoch!=localEpoch||localGoal?.token!=goal.token)continue
            if(!observed.available||goal.source!=observed.sourceGeneration||goal.config!=observed.configGeneration||goal.experimental!=experimental){invalidateLocalGoals();eqPending=false;eqTarget=null;continue}
            if(goal.revision!=observed.configurationRevision){
                val base=observed.preset
                if(!canEdit(SoundScope.PRESET)||base==null||base.id!=goal.previous.id||base.eqPoints.map{it.id}!=goal.previous.eqPoints.map{it.id}){invalidateLocalGoals();eqPending=false;eqTarget=null;continue}
                val requested=if(goal.pointId!=null)base.copy(eqPoints=base.eqPoints.map{p->if(p.id==goal.pointId)goal.requested.eqPoints.first{it.id==p.id}else p})else goal.requested
                queueLocal(requested,goal.pointId?.let{id->base.eqPoints.first{it.id==id}},goal.explicitActivation);continue
            }
            localGoal=null
            if(!canEdit(SoundScope.PRESET)){eqPending=false;eqTarget=null;continue}
            val projection=result.getOrNull()
            if(projection==null){val failure=result.exceptionOrNull();validationUnavailable=failure is LinkageError||failure?.message?.startsWith("EQ_VALIDATOR")==true;eqPending=false;latestPresetPreview=0;eqTarget=null;eqTargetLimited=false;afterEq=null;eqBoundReason="Lokale EQ-Prüfung nicht verfügbar · Klang unverändert";status=eqBoundReason;com.no5.helm.network.soundDiagnostic("LOCAL_VALIDATOR_REJECTED");continue}
            val checked=projection.preset
            val previousPoint=goal.pointId?.let{id->goal.previous.eqPoints.first{it.id==id}}
            val intent=SoundEqEditIntent(goal.experimental,previousPoint)
            val id=port?.submit(AudioSoundCommand.Preview(previewId,SoundScope.PRESET,preset=checked,basis=presetBasis,eqEditing=intent,locallyValidated=true))?:0L
            if(id==0L){eqPending=false;eqTarget=null;afterEq=null;status="Ziel nicht angenommen · keine Änderung bestätigt";continue}
            if(lastPointPreview==goal.token)lastPointPreview=id
            if(repairedActivation==goal.token)repairedActivation=id
            localEqTimings.record(EqLocalTiming(goal.token,goal.queuedNs,work.second,work.third,System.nanoTime()))
            latestLocallyConstrained=projection.constrained||(goal.pointId!=null&&eqTargetLimited)
            latestPresetPreview=id;latestPresetRequested=checked;latestProjectedIntent=intent;latestProjectedBasis=presetBasis
            eqTargetLimited=eqTargetLimited||checked!=goal.requested;status="Vorschau wird übertragen"
        }
    }}
    private var repairedActivation=0L
    private var continuation:(()->Unit)?=null;private var savingId=0L;private var savingScope:SoundScope?=null;private var discarding=false
    init {
        startLocalWorker()
        port?.let { p ->
            subscriptions+=p.state.subscribe { next -> scope.launch {
                val previous=observed
                if(!next.available || previous.configGeneration!=next.configGeneration || previous.sourceGeneration!=next.sourceGeneration){
                    invalidateLocalGoals();endEqGesture();eqTarget=null;eqTargetLimited=false
                }
                if((experimental||experimentEnding) && (!next.available || previous.configGeneration!=next.configGeneration || previous.sourceGeneration!=next.sourceGeneration)){
                    normalEqDraft=previous.eqEditing?.normalPreset?:normalEqDraft
                    experimental=false;experimentEnding=previous.eqEditing?.experimental==true && !next.available
                    port?.setExperimentalAllowed(false);preset=normalEqDraft?:preset;eqPending=false;latestPresetPreview=0;afterEq=null
                    status="Testende noch nicht bestätigt · Verbindung prüfen";persist()
                }
                observed=next
                // A newer queued pointer target must not hide intermediate AUDIO_BLOCK_READBACKs.
                // Keep pending/save ownership tied to the latest command, but paint actual native state.
                if(presetTouched && next.available && previous.configGeneration==next.configGeneration &&
                    previous.sourceGeneration==next.sourceGeneration && next.preview?.id==previewId &&
                    next.eqEditing?.experimental==experimental && next.preset?.id==preset?.id){
                    preset=next.preset
                    normalEqDraft=next.eqEditing?.normalPreset?:preset
                    persist()
                }
                if(experimentEnding && next.available && next.eqEditing?.experimental==false && !eqPending){experimentEnding=false;status="Probehören · noch nicht gespeichert"}
                if(next.available){
                    if(awaitingFailureRead){awaitingFailureRead=false;status=if(presetDirty||calibrationDirty)"Letzten Stand geprüft · Entwurf erhalten"else"Letzten Stand geprüft"}
                    if(preset==null || !presetTouched){preset=next.preset;savedPreset=next.preset;presetBasis=basis(next)}
                    if(calibration==null || !calibrationTouched){calibration=next.calibration;savedCalibration=next.calibration;calibrationBasis=basis(next)}
                    if(previous.sourceGeneration!=next.sourceGeneration)spectrum=null
                    if(previous.preview?.id==previewId && next.preview==null && (presetDirty||calibrationDirty) && !busy)status="Vorschau beendet · lokaler Entwurf erhalten"
                    restore();updateConflicts();completeConfirmedLibrary()
                }else spectrum=null
            } }
            subscriptions+=p.spectrum.subscribe { next -> scope.launch { spectrum=next?.takeIf { it.sourceGeneration==observed.sourceGeneration } } }
            subscriptions+=p.results.subscribe { result ->
                // Admission remains in the bounded source actor. UI ownership is
                // already pending; only terminal outcomes can change it here.
                if(result?.status=="SUPERSEDED"&&result.reason=="LOCAL_VALIDATION_STALE"){
                    scope.launch {
                        val base=observed.preset;val requested=latestPresetRequested;val id=latestProjectedIntent?.previousPoint?.id
                        if(!closed&&result.id==latestPresetPreview&&localGoal==null&&canEdit(SoundScope.PRESET)&&base!=null&&requested!=null&&id!=null&&base.eqPoints.map{it.id}==requested.eqPoints.map{it.id}){
                            val target=eqTarget?.takeIf{it.id==id}?:requested.eqPoints.first{it.id==id}
                            val retry=LocalRetryBasis(observed.sourceGeneration,observed.configGeneration,observed.configurationRevision,observed.preview?.id,experimental,base,target)
                            if(staleRetryBasis==retry){
                                invalidateLocalGoals();validationUnavailable=true;eqPending=false;latestPresetPreview=0;eqTarget=null;eqTargetLimited=false;afterEq=null
                                eqBoundReason="Lokale EQ-Prüfung nicht verfügbar · Klang unverändert";status=eqBoundReason
                                com.no5.helm.network.soundDiagnostic("LOCAL_REVALIDATION_NO_PROGRESS")
                            }else{
                                staleRetryBasis=retry
                                queueLocal(requested.copy(eqPoints=base.eqPoints.map{if(it.id==id)target else it}),base.eqPoints.first{it.id==id},false,true)
                            }
                        }
                    };return@subscribe
                }
                if(result==null || !result.terminal || result.status=="SUPERSEDED")return@subscribe
                scope.launch {
                if(result!=null && result.terminal && result.status=="UNKNOWN" && result.id in setOf(latestPresetPreview,latestCalibrationPreview,savingId))awaitingFailureRead=true
                if(result!=null && result.terminal && result.status=="REJECTED" &&
                    result.id in setOf(latestPresetPreview,latestCalibrationPreview,savingId)){
                    if(result.id==latestPresetPreview && latestPresetRequested?.eqEnabled==true && observed.preset?.eqEnabled!=true)eqActivationRejected=true
                    eqBoundReason=when(result.reason){
                        "EQ_VISIBLE_BANDS_OVERLAP"->"Bandgrenze erreicht · EQ bleibt aktiv"
                        "EQ_POSITIVE_BANDS_OVERLAP"->"Positive Bänder dürfen sich nicht überlappen"
                        "EQ_COMBINED_GAIN_LIMIT"->"Pegelreserve reicht für diese Kurve nicht · Ziel nicht übernommen"
                        else->eqBoundReason
                    }
                }
                if(result!=null && result.terminal && result.id==latestPresetPreview && eqPending){
                    if(result.status=="APPLIED" && observed.available && observed.preview?.id==previewId && observed.eqEditing?.experimental==experimental){
                        staleRetryBasis=null
                        preset=observed.preset;eqPending=false;eqTarget=null;eqTargetLimited=false
                        eqActivationRejected=false
                        normalEqDraft=observed.eqEditing?.normalPreset?:preset
                        if(!experimental)experimentEnding=false
                        eqBoundReason=if(observed.eqEditing?.constrained==true||latestLocallyConstrained)"Grenze erreicht · EQ bleibt aktiv"else if(result.id==repairedActivation)"Bandbreiten angepasst · noch nicht gespeichert"else null
                        persist();val done=afterEq;afterEq=null;done?.invoke()
                    }else if(result.status!="SUPERSEDED"){
                        eqPending=false;eqTarget=null;eqTargetLimited=false;afterEq=null
                        if(experimentEnding){preset=normalEqDraft?:preset;status="Testende noch nicht bestätigt · Verbindung prüfen"}
                        persist()
                    }
                }
                if(result!=null && result.terminal && result.id in setOf(latestPresetPreview,latestCalibrationPreview)){
                    status=when(result.status){"APPLIED"->if(result.id==lastPointPreview)"Ziel übernommen · Klang bestätigt"else "Probehören · noch nicht gespeichert";"UNKNOWN"->"Abschluss unklar · zuerst abgleichen";"SUPERSEDED"->status;else->"Vorschau nicht übernommen · Entwurf erhalten"}
                    if(experimentEnding)status="Testende noch nicht bestätigt · Verbindung prüfen"
                    else if(experimental)status="Testmodus · nur Probehören · Speichern gesperrt"
                    if(result.status !in setOf("APPLIED","SUPERSEDED"))port.refresh()
                }
                if(result?.id==savingId && result.terminal){
                    if(result.status=="APPLIED" && libraryConfirmation!=null){
                        appliedAwaitingState=true;completeConfirmedLibrary()
                        if(appliedAwaitingState){
                            status="Ausgeführt · bestätigten Stand abgleichen";port.refresh()
                            startConfirmationTimeout(5000)
                        }
                        return@launch
                    }
                    busy=false;savingId=0
                    if(result.status=="APPLIED"){
                        val area=savingScope
                        if(area==SoundScope.PRESET){presetTouched=false;restoredPresetPending=false;if(discarding)preset=savedPreset else {if(observed.capabilities.eqProjection)preset=observed.preset?:preset;savedPreset=preset}}
                        else {calibrationTouched=false;restoredCalibrationPending=false;coordinateInputs=emptyMap();geometryInvalid=emptySet();if(discarding)calibration=savedCalibration else savedCalibration=calibration}
                        if(area==SoundScope.PRESET)presetBasis=basis(observed)else calibrationBasis=basis(observed)
                        if(libraryOperation)libraryCompletion++
                        libraryOperation=false;savingScope=null;status=null;updateConflicts();persist();val done=continuation;continuation=null;closeDecision=null;done?.invoke()
                    }else {libraryOperation=false;libraryConfirmation=null;status=if(result.status=="UNKNOWN")"Abschluss unklar · zuerst abgleichen" else "Änderung nicht übernommen";port.refresh()}
                }
                updateConflictStatus()
            } }
        }
    }
    private fun startConfirmationTimeout(milliseconds:Long){
        confirmationTimeout?.cancel();val expected=libraryConfirmation
        confirmationTimeout=scope.launch{delay(milliseconds)
            if(appliedAwaitingState && libraryConfirmation===expected){appliedAwaitingState=false;busy=false;savingId=0;libraryConfirmation=null;libraryOperation=false;continuation=null;status="Abschluss unklar · zuerst abgleichen";awaitingFailureRead=true;port?.refresh()}
        }
    }
    /** An acknowledgement alone cannot open an editor or close a new draft. */
    private fun completeConfirmedLibrary(){
        if(!appliedAwaitingState || libraryConfirmation?.invoke(observed)!=true)return
        appliedAwaitingState=false;confirmationTimeout?.cancel();confirmationTimeout=null
        busy=false;savingId=0;savingScope=null;libraryConfirmation=null;libraryOperation=false
        presetTouched=false;restoredPresetPending=false;preset=observed.preset;savedPreset=preset;presetBasis=basis(observed)
        newOriginal=null;newSource=null;presetNameRequested=false;presetName="";nameContinuation=null
        libraryCompletion++;status=null;closeDecision=null;updateConflicts();persist()
        val done=continuation;continuation=null;done?.invoke()
    }
    private fun basis(s:AudioSoundState)=SoundRevisionBasis(s.configGeneration,s.presetRevision,s.calibrationRevision)
    fun attachStorage(context:Context){if(storage!=null)return;storage=context.applicationContext.getSharedPreferences("no5-sound-drafts-v1",Context.MODE_PRIVATE);restore();updateConflicts()}
    private fun basisMatches(value:SoundRevisionBasis?,area:SoundScope):Boolean {
        if(value==null || value.configGeneration!=observed.configGeneration)return false
        if(area==SoundScope.CALIBRATION)return value.calibrationRevision==observed.calibrationRevision
        val original=newOriginal
        val identity=if(original!=null)newSource==observed.sourceGeneration && observed.preset?.id in setOf(preset?.id,original.id) else preset?.id==observed.preset?.id
        val originalUnchanged=original==null || observed.preset?.id!=original.id || observed.preview?.id==previewId || SoundNativeReadback.preset(observed.preset,original)
        return value.presetRevision==observed.presetRevision && identity && originalUnchanged
    }
    private fun updateConflicts(){
        presetConflict=presetTouched && !basisMatches(presetBasis,SoundScope.PRESET)
        calibrationConflict=(calibrationTouched && !basisMatches(calibrationBasis,SoundScope.CALIBRATION)) ||
            (measurementDirty && !basisMatches(measurementBasis,SoundScope.CALIBRATION))
        updateConflictStatus()
    }
    private fun updateConflictStatus(){
        val messages=setOf("Alter lokaler Entwurf ohne bestätigte Basis · keine Übertragung","Lokaler Entwurf benötigt Abgleich · Verwerfen lädt den bestätigten Stand","Anderer lokaler Entwurf benötigt Abgleich")
        if(conflict(activeScope)){
            val known=if(activeScope==SoundScope.PRESET)presetBasis!=null else calibrationBasis!=null && (!measurementDirty||measurementBasis!=null)
            status=if(known)"Lokaler Entwurf benötigt Abgleich · Verwerfen lädt den bestätigten Stand"else"Alter lokaler Entwurf ohne bestätigte Basis · keine Übertragung"
        }else if(conflicted)status="Anderer lokaler Entwurf benötigt Abgleich"
        else if(status in messages)status=if(presetDirty||calibrationDirty)"Lokaler Entwurf · noch nicht zum Probehören übertragen"else null
    }
    private fun readBasis(value:JSONObject?):SoundRevisionBasis? = runCatching {
        requireNotNull(value)
        val config=value.getString("configGeneration").also{require(it.length in 1..128 && it.all{c->c.code in 33..126})}
        fun revision(key:String):Long {val number=value.get(key) as? Number?:error("Invalid draft revision");val d=number.toDouble();val n=number.toLong();require(d.isFinite() && n in 0..9007199254740991L && d==n.toDouble());return n}
        SoundRevisionBasis(config,revision("presetRevision"),revision("calibrationRevision"))
    }.getOrNull()
    private fun writeBasis(value:SoundRevisionBasis?):Any = value?.let{JSONObject().put("configGeneration",it.configGeneration).put("presetRevision",it.presetRevision).put("calibrationRevision",it.calibrationRevision)}?:JSONObject.NULL
    private fun restore(){if(loaded || storage==null || !observed.available)return;loaded=true
        runCatching{
            val text=storage?.getString("draft",null);persistedDraftText=text
            if(text==null)return
            val o=JSONObject(text);val version=o.getInt("version");require(version in 1..3)
            // Every scope retains its ORIGINAL compare-and-swap basis. Unknown v1 bases
            // survive migration as unknown, never as the currently observed revision.
            if(o.has("preset")){restoredPresetPending=true;preset=AudioSoundCodec.presetDraft(o.getJSONObject("preset"));presetTouched=true;presetBasis=if(version>=2)readBasis(o.optJSONObject("presetBasis"))else null}
            o.optJSONObject("newPreset")?.let { draft ->
                val original=AudioSoundCodec.presetDraft(draft.getJSONObject("original"))
                require(original.id!=preset?.id)
                newOriginal=original;savedPreset=original;newSource=draft.getString("sourceGeneration")
                presetName=draft.optString("name").takeIf{it.length<=100}.orEmpty()
            }
            if(o.has("calibration")){restoredCalibrationPending=true;calibration=AudioSoundCodec.calibration(o.getJSONObject("calibration"));calibrationTouched=true;calibrationBasis=if(version>=2)readBasis(o.optJSONObject("calibrationBasis"))else null}
            if(o.has("measurements")){
                val m=o.getJSONObject("measurements")
                val initial=SoundMeasurementDraft.from(calibration)
                measurementDraft=initial.copy(width=m.optString("width"),length=m.optString("length"))
                measurementBasis=if(version>=2)readBasis(m.optJSONObject("basis"))else null
            }
            o.optJSONObject("coordinateInputs")?.let { inputs ->
                val retained=linkedMapOf<String,CoordinateInput>()
                for(id in coordinateIds) inputs.optJSONObject(id)?.let { value ->
                    val fields=listOf("x","y","z").map{value.getString(it).also{v->require(v.length<=40 && v.none(Char::isISOControl))}}
                    val entry=CoordinateInput(fields[0],fields[1],fields[2]);retained[id]=entry
                    markGeometryInput(id,entry.parsed().isFailure)
                }
                coordinateInputs=retained
            }
            if(presetDirty||calibrationDirty)status="Lokaler Entwurf · noch nicht zum Probehören übertragen"
        }
        // Even a partially readable historical draft must never inherit a fresh basis.
        updateConflicts()
    }
    private var deferredPersistence:Job?=null
    /** Capture immutable model references on UI; encode off the pointer/draw thread. */
    private fun draftEncoder():()->String {
        val recoverable=if(presetDirty)(if(experimental||experimentEnding)observed.eqEditing?.normalPreset?:normalEqDraft else preset)else null
        val pBasis=presetBasis;val cBasis=calibrationBasis
        val original=newOriginal.takeIf{newPresetDraft&&presetDirty};val source=newSource;val name=presetName
        val cal=calibration.takeIf{calibrationDirty};val measurements=measurementDraft.takeIf{measurementDirty};val mBasis=measurementBasis
        val coordinates=coordinateInputs.takeIf{it.isNotEmpty()&&calibrationDirty}
        return {
            val o=JSONObject().put("version",3)
            recoverable?.let{o.put("preset",AudioSoundCodec.preset(it)).put("presetBasis",writeBasis(pBasis))}
            original?.let{o.put("newPreset",JSONObject().put("original",AudioSoundCodec.preset(it)).put("sourceGeneration",source).put("name",name))}
            cal?.let{o.put("calibration",AudioSoundCodec.calibration(it)).put("calibrationBasis",writeBasis(cBasis))}
            measurements?.let{o.put("measurements",JSONObject().put("width",it.width).put("length",it.length).put("basis",writeBasis(mBasis)))}
            coordinates?.let{values->val inputs=JSONObject();values.forEach{(id,value)->inputs.put(id,JSONObject().put("x",value.x).put("y",value.y).put("z",value.z))};o.put("coordinateInputs",inputs)}
            o.toString()
        }
    }
    private fun persist(){val s=storage?:return
        // An idle/retired controller cannot replace a newer controller's draft.
        if(!loaded || s.getString("draft",null)!=persistedDraftText)return
        if(eqGestureRange!=null){
            if(deferredPersistence?.isActive==true)return
            deferredPersistence=scope.launch {
                delay(200)
                val encode=draftEncoder()
                val text=withContext(Dispatchers.Default){encode()}
                if(!closed&&eqGestureRange!=null&&s.getString("draft",null)==persistedDraftText){
                    persistedDraftText=text;s.edit().putString("draft",text).apply()
                }
            }
            return
        }
        deferredPersistence?.cancel();deferredPersistence=null
        persistedDraftText=draftEncoder()();s.edit().putString("draft",persistedDraftText).apply()
    }
    private fun publishVisibility(){
        if(closed)return
        val visible=editorVisible||catalogVisible
        if(visible){ownsVisibility=true;port?.setEditorVisible(true,editorVisible&&section=="eq")}
        else if(ownsVisibility){ownsVisibility=false;port?.setEditorVisible(false,false)}
    }
    fun openPreset(){if(closed)return;section="eq";calibrationReturn="audio";editorVisible=true;publishVisibility();port?.refresh();restore();updateConflicts()}
    fun openSpeakers(fromRoom:Boolean=false){if(closed)return;calibrationReturn=if(fromRoom)"room"else"audio";section="delay";editorVisible=true;publishVisibility();port?.refresh();restore();updateConflicts()}
    fun changeSection(value:String){if(closed)return
        if(value=="measure" && section!="measure"){
            measurementReturn=if(section=="gain")"gain"else"delay"
            if(measurementDraft==null || !measurementDirty){measurementDraft=SoundMeasurementDraft.from(calibration);measurementBasis=basis(observed)}
        }
        placingPosition=null;section=value;addingPoint=false;updateConflicts();if(ownsVisibility)publishVisibility()}
    fun visibility(visible:Boolean){if(closed)return;if(!visible){invalidateLocalGoals();afterEq=null;endEqGesture();eqTarget=null;eqTargetLimited=false;endExperiment()};editorVisible=visible;publishVisibility();if(visible)restore()else spectrum=null}
    /** Menus need fresh capabilities before they can offer the editor. Never starts FFT. */
    fun catalogVisibility(visible:Boolean){if(closed)return;catalogVisible=visible;publishVisibility();if(visible)port?.refresh()}
    val editable by derivedStateOf(structuralEqualityPolicy()) {canEdit(activeScope)}
    private val canUseLibrary get()=canSavePreset && canEdit(SoundScope.PRESET) && observed.capabilities.presetLibrary && preset!=null
    val canCreatePreset get()=canUseLibrary && observed.capabilities.presetManagement && !presetDirty && !calibrationDirty && observed.presetCatalog.size<64
    fun canLoadPreset(value:SoundPresetEntry)=canUseLibrary && !presetDirty && !calibrationDirty && value.routingTemplate==observed.preset?.routingTemplate && observed.presetCatalog.any{it.id==value.id}
    val canManagePresets get()=canUseLibrary && observed.capabilities.presetManagement && !presetDirty && !calibrationDirty
    fun beginNewPreset():Boolean {
        restore();updateConflicts()
        if(!canCreatePreset)return false
        val original=observed.preset?:return false
        newOriginal=original;newSource=observed.sourceGeneration;savedPreset=original;presetBasis=basis(observed)
        preset=original.copy(id="user_"+java.util.UUID.randomUUID().toString().replace("-",""),name="New preset",eqPoints=emptyList(),room=SoundRoom(0.0,.5))
        presetTouched=true;presetName="";selectedPoint=null;addingPoint=false;section="eq"
        persist();preview(SoundScope.PRESET);return true
    }
    fun editPresetName(value:String){if(!busy && value.length<=100 && value.none(Char::isISOControl)){presetName=value;persist()}}
    fun cancelPresetName(){if(!busy){presetNameRequested=false;nameContinuation=null}}
    fun confirmPresetName(){
        if(busy || !presetNameRequested || !SoundPresetName.valid(presetName))return
        preset=preset?.copy(name=presetName.trim());persist()
        val done=nameContinuation
        // Preserve the field and the draft if the preview/save is rejected or uncertain.
        commit(SoundScope.PRESET,false,done,named=true)
    }
    /** Kept as the explicit named-copy command for old stored callers; New never calls it. */
    fun createPreset(name:String):Boolean {
        if(!canUseLibrary || presetDirty || observed.presetCatalog.size>=64 || !SoundPresetName.valid(name))return false
        val revision=observed.presetRevision;val source=observed.sourceGeneration
        return submitLibrary(AudioSoundCommand.SaveAs(previewId,name.trim(),presetBasis),{it.available && it.sourceGeneration==source && it.preset?.name==name.trim() && it.presetRevision==revision+1})
    }
    fun loadPreset(value:SoundPresetEntry,done:(()->Unit)?=null):Boolean {
        if(!canLoadPreset(value))return false
        if(value.id==observed.preset?.id){done?.invoke();return true}
        val revision=observed.presetRevision;val source=observed.sourceGeneration
        return submitLibrary(AudioSoundCommand.LoadPreset(previewId,value.id,presetBasis),
            {it.available && it.sourceGeneration==source && it.preset?.id==value.id && it.presetRevision==revision+1},done)
    }
    fun deletePreset(value:SoundPresetEntry,expected:SoundRevisionBasis?=presetBasis,source:String=observed.sourceGeneration):Boolean {
        if(!canManagePresets || (expected==null || expected.configGeneration!=observed.configGeneration || expected.presetRevision!=observed.presetRevision) || source!=observed.sourceGeneration || observed.presetCatalog.none{it==value})return false
        val revision=observed.presetRevision;val source=observed.sourceGeneration
        return submitLibrary(AudioSoundCommand.DeletePreset(previewId,value.id,expected),
            {it.available && it.sourceGeneration==source && it.presetRevision==revision+1 && it.presetCatalog.none{entry->entry.id==value.id}})
    }
    fun afterDeletion(value:SoundPresetEntry):String? {
        if(value.id!=observed.preset?.id)return observed.preset?.name
        return observed.presetCatalog.filter{it.id!=value.id && it.routingTemplate==value.routingTemplate}.sortedBy{it.id}.firstOrNull()?.name
    }
    private fun submitLibrary(command:AudioSoundCommand,confirmed:(AudioSoundState)->Boolean,done:(()->Unit)?=null):Boolean {
        val id=port?.submit(command)?:0L
        if(id==0L){status="Ziel nicht angenommen · keine Änderung bestätigt";return false}
        savingId=id;savingScope=SoundScope.PRESET;discarding=false;libraryOperation=true;libraryConfirmation=confirmed;continuation=done;busy=true;status="Wird gespeichert"
        return true
    }
    fun choosePoint(id:String){selectedPoint=id}
    fun chooseSpeaker(id:SoundSpeakerId){selectedSpeaker=id}
    fun editMeasurement(width:Boolean,text:String){
        if(!canEdit(SoundScope.CALIBRATION) || text.length>40)return
        val draft=measurementDraft?:return
        measurementDraft=if(width)draft.copy(width=text)else draft.copy(length=text)
        measurementError=null;persist()
    }
    fun cancelMeasurements(){measurementDraft=null;measurementBasis=null;measurementError=null
        if(calibration==savedCalibration && observed.preview?.calibrationDirty!=true)restoredCalibrationPending=false
        updateConflicts()
        changeSection(measurementReturn);persist()}
    fun applyMeasurements(returnToAlignment:Boolean=true):Boolean {
        if(!canEdit(SoundScope.CALIBRATION))return false
        val draft=measurementDraft?:return true
        val value=calibration?:return false
        val dimensions=draft.dimensions()
        if(dimensions==null){measurementError="Beide Abstände zwischen 0,05 und 50 Metern eingeben";return false}
        // Explicit action only: opening a nonrectangular/exact geometry never rewrites it.
        val next=runCatching{SoundMeasuredGeometry.rectangle(value,dimensions.first,dimensions.second)}.getOrElse{measurementError="Abstände überschreiten die bestehende Meterreferenz";return false}
        measurementDraft=null;measurementBasis=null;measurementError=null;geometryInvalid=geometryInvalid.intersect(setOf("listener","SUB"));coordinateInputs=coordinateInputs.filterKeys{it in setOf("listener","SUB")}
        changeCalibration(next)
        if(returnToAlignment)changeSection(measurementReturn)
        return true
    }
    fun beginPositioning(target:SoundPositionTarget){if(!canEdit(SoundScope.CALIBRATION) || SoundMeasuredGeometry.bounds(calibration)==null)return;selectedPosition=target;placingPosition=target}
    fun cancelPositioning(){placingPosition=null}
    fun placePosition(position:SoundPosition){
        if(!canEdit(SoundScope.CALIBRATION))return
        val target=placingPosition?:return
        val value=calibration?:return
        val next=if(target==SoundPositionTarget.LISTENER)value.copy(listenerM=position.copy(z=value.listenerM?.z))
        else value.copy(speakers=value.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(positionM=position.copy(z=it.positionM?.z))else it})
        placingPosition=null;changeCalibration(next)
    }
    fun movePosition(target:SoundPositionTarget,position:SoundPosition){
        if(!canEdit(SoundScope.CALIBRATION))return
        val value=calibration?:return
        val next=if(target==SoundPositionTarget.LISTENER)value.copy(listenerM=position)
        else value.copy(speakers=value.speakers.map{if(it.id==SoundSpeakerId.SUB)it.copy(positionM=position)else it})
        changeCalibration(next)
    }
    fun useGeometry(enabled:Boolean){val value=calibration?:return;if(!enabled||value.completeGeometry)changeCalibration(value.copy(geometryEnabled=enabled))}
    fun useExperimental(enabled:Boolean){
        if(enabled){
            if(!editorVisible||!editable||!observed.capabilities.experimentalEq||eqPending||experimentEnding)return
            val current=preset?:return
            if(!SoundNativeReadback.preset(observed.preset,current)){
                afterEq={if(editorVisible)useExperimental(true)};changePresetProjected(current,null);return
            }
            normalEqDraft=current;experimental=true;port?.setExperimentalAllowed(true)
            changePresetProjected(current,null)
        }else endExperiment()
    }
    private fun endExperiment(){
        if(!experimental&&!experimentEnding)return
        experimental=false;port?.setExperimentalAllowed(false);experimentEnding=true
        normalEqDraft=observed.eqEditing?.normalPreset?:normalEqDraft
        val current=preset?:return
        if(canEdit(SoundScope.PRESET))changePresetProjected(current,null)
        else {preset=normalEqDraft?:preset;eqPending=false;persist();status="Testende noch nicht bestätigt · Verbindung prüfen"}
    }
    private fun changePresetProjected(value:SoundPreset,previousPoint:SoundEqPoint?,explicitActivation:Boolean=false):Long {
        if(!canEdit(SoundScope.PRESET))return 0
        // A restored draft/expired lease is not an activation command. Nor may a
        // motion based on the old display undo an in-flight explicit mode change.
        if(!explicitActivation && (value.eqEnabled && !eqActuallyEnabled && preset?.eqEnabled==true ||
                eqPending && latestPresetRequested?.eqEnabled!=observed.preset?.eqEnabled)){
            afterEq=null;eqActivationRejected=!eqActuallyEnabled;eqBoundReason=if(eqActuallyEnabled)null else "EQ nicht aktiv"
            status="Vorschau nicht übernommen · Entwurf erhalten";return 0
        }
        return queueLocal(value,previousPoint,explicitActivation)
    }
    fun changePreset(value:SoundPreset){
        if(!canEdit(SoundScope.PRESET))return
        if(!SoundEqVisibleBands.compatible(preset?.eqPoints.orEmpty(),value.eqPoints)){
            eqBoundReason="Bandgrenze erreicht · EQ bleibt aktiv";return
        }
        if((value.eqEnabled&&SoundEqBands.overlaps(value.eqPoints)) ||
            !SoundEqBands.compatibleEdit(preset?.eqPoints.orEmpty(),value.eqPoints)){
            eqBoundReason="Positive Bänder dürfen sich nicht überlappen";return
        }
        if(value==preset)return
        if(observed.capabilities.eqProjection){changePresetProjected(value,null);return}
        presetTouched=true;preset=value;persist();preview(SoundScope.PRESET)
    }
    fun setEqEnabled(enabled:Boolean){val p=preset?:return;eqBoundReason=null;eqActivationRejected=false
        if(observed.capabilities.eqProjection){
            val points=if(enabled&&!eqActuallyEnabled&&SoundEqBands.overlaps(p.eqPoints)){
                SoundEqBands.separateLegacyBands(p.eqPoints)?:run{
                    eqActivationRejected=true;eqBoundReason="Positive Bänder dürfen sich nicht überlappen";return
                }
            }else p.eqPoints
            val id=changePresetProjected(p.copy(eqEnabled=enabled,eqPoints=points),null,true)
            repairedActivation=if(points!=p.eqPoints)id else 0L
            return
        }
        changePreset(p.copy(eqEnabled=enabled))
        // A rejected enable target is retained losslessly; retry must actually submit it again.
        if(enabled && p==preset && p.eqEnabled && !SoundEqBands.overlaps(p.eqPoints))preview(SoundScope.PRESET)
    }
    fun setReserve(value:Double){if(!eqCurveEditable)return;val p=preset?:return;if(!value.isFinite())return
        if(eqPending){afterEq={setReserve(value)};return}
        eqBoundReason=null;changePreset(p.copy(reserveDb=value.coerceIn(6.0,18.0)))}
    fun adjustReserve(steps:Int){val p=preset?:return;setReserve(p.reserveDb+steps)}
    fun changeCalibration(value:SoundCalibration){
        if(!canEdit(SoundScope.CALIBRATION))return
        fun position(model:SoundCalibration?,id:String)=if(id=="listener")model?.listenerM else model?.speakers?.firstOrNull{it.id.name==id}?.positionM
        val replaced=coordinateInputs.filter{(id,input)->
            position(calibration,id)!=position(value,id) && (input.parsed().isFailure || input.parsed().getOrNull()!=position(value,id))
        }.keys
        coordinateInputs=coordinateInputs-replaced;geometryInvalid=geometryInvalid-replaced
        calibrationTouched=true;calibration=value;persist();preview(SoundScope.CALIBRATION)
    }
    private fun preview(area:SoundScope):Long {
        if(!canEdit(area))return 0
        if(area==SoundScope.PRESET)eqActivationRejected=false
        if(area==SoundScope.PRESET&&observed.capabilities.eqProjection)return changePresetProjected(requireNotNull(preset),null)
        val command=if(area==SoundScope.PRESET)AudioSoundCommand.Preview(previewId,area,preset=requireNotNull(preset),basis=presetBasis,
            eqEditing=if(observed.capabilities.eqProjection)SoundEqEditIntent(experimental)else null)
        else AudioSoundCommand.Preview(previewId,area,calibration=requireNotNull(calibration),basis=calibrationBasis)
        val id=port?.submit(command)?:0L
        if(area==SoundScope.PRESET){latestPresetPreview=id;latestPresetRequested=preset}else latestCalibrationPreview=id
        status=if(id==0L)"Ziel nicht angenommen · keine Änderung bestätigt" else "Vorschau wird übertragen"
        return id
    }
    fun addPoint(frequency:Double,gain:Double=0.0){if(!eqCurveEditable)return;val p=preset?:return;if(!observed.capabilities.parametricEq || p.eqPoints.size>=16)return
        var point=SoundEqPoint(java.util.UUID.randomUUID().toString(),frequency.coerceIn(20.0,20000.0),gain.coerceIn(-12.0,eqPointMaximum))
        fun fits(q:Double)=SoundEqVisibleBands.compatible(p.eqPoints,p.eqPoints+point.copy(q=q))
        if(!fits(point.q)){
            if(!fits(10.0)){eqBoundReason="Bandgrenze erreicht · EQ bleibt aktiv";return}
            var lo=point.q;var hi=10.0
            repeat(48){val q=(lo+hi)/2;if(fits(q))hi=q else lo=q}
            point=point.copy(q=hi)
        }
        selectedPoint=point.id;changePreset(p.copy(eqPoints=p.eqPoints+point))
    }
    fun updatePoint(point:SoundEqPoint){
        if(!eqCurveEditable||!canEdit(SoundScope.PRESET))return
        val p=preset?:return;val old=p.eqPoints.firstOrNull{it.id==point.id}?:return
        val capped=point.copy(gainDb=point.gainDb.coerceAtMost(eqPointMaximum))
        if(observed.capabilities.eqProjection){
            // The hollow target is only a finger-intent marker. Publish it and
            // queue the raw, gain-capped request immediately; exact visible-band
            // projection and the canonical native safety check both run on the
            // single conflated validation worker before transport admission.
            eqTarget=capped;eqTargetLimited=capped!=point
            val moving=capped!=old||eqPending
            eqBoundReason=null
            val id=changePresetProjected(p.copy(eqPoints=p.eqPoints.map{if(it.id==point.id)capped else it}),old)
            if(id==0L&&moving){eqTarget=null;eqTargetLimited=false}
            return
        }
        val geometry=traceEqStage("No5.EQ.GeometryProjection"){SoundEqVisibleBands.constrain(old,capped,p.eqPoints)}
        val bounded=SoundEqBands.constrain(old,geometry.point,p.eqPoints)
        eqBoundReason=if(geometry.constrained||bounded.constrained)"Bandgrenze erreicht · EQ bleibt aktiv"else null
        val next=p.copy(eqPoints=p.eqPoints.map{if(it.id==point.id)bounded.point else it})
        changePreset(next)
    }
    fun removePoint(){val p=preset?:return;changePreset(p.copy(eqPoints=p.eqPoints.filterNot{it.id==selectedPoint}));selectedPoint=null}
    fun neutral(){eqBoundReason=null;preset?.let{changePreset(it.neutral())}}
    fun requestClose(area:SoundScope,done:()->Unit){if(busy)return
        if(area==SoundScope.PRESET && experimental){afterEq={requestClose(area,done)};endExperiment();return}
        if(area==SoundScope.PRESET && eqPending){afterEq={requestClose(area,done)};return}
        continuation=done;if(if(area==SoundScope.PRESET)presetDirty else calibrationDirty)closeDecision=area else {continuation=null;done()}}
    fun continueEditing(){closeDecision=null;continuation=null}
    fun save(area:SoundScope,done:(()->Unit)?=null){commit(area,false,done)}
    fun discard(){closeDecision?.let{commit(it,true,continuation)}}
    private fun commit(area:SoundScope,discard:Boolean,done:(()->Unit)?,named:Boolean=false){if(busy)return
        val remotePending=eqPending&&latestPresetPreview>0
        if(discard&&area==SoundScope.PRESET){invalidateLocalGoals();eqPending=false;eqTarget=null;eqTargetLimited=false;afterEq=null;latestPresetPreview=0}
        if(!discard&&!canSavePreset){status="Testmodus zuerst beenden";return}
        if(area==SoundScope.PRESET && !discard && observed.capabilities.eqProjection && (!SoundNativeReadback.preset(observed.preset,preset)||observed.preview?.id!=previewId)){
            afterEq={commit(area,false,done,named)};preset?.let{changePresetProjected(it,null)};return
        }
        if(area==SoundScope.PRESET && !discard && newPresetDraft && !named){presetNameRequested=true;nameContinuation=done?:continuation;return}
        if(area==SoundScope.PRESET && discard && newPresetDraft && !observed.available){status="Abschluss unklar · zuerst abgleichen";port?.refresh();return}
        if(area==SoundScope.PRESET && discard && newPresetDraft && !presetConflict && observed.preview?.id!=previewId && observed.preset?.id!=newOriginal?.id){
            // After process loss a previous lease can still be rolling back.
            // No owned lease means no right to send Discard, and no proof that
            // the previous sound has returned. Wait for its actual readback.
            val original=newOriginal;val source=newSource;val revision=observed.presetRevision
            libraryConfirmation={it.available && it.sourceGeneration==source && SoundNativeReadback.preset(it.preset,original) && it.presetRevision==revision}
            appliedAwaitingState=true;busy=true;savingScope=SoundScope.PRESET;discarding=true;continuation=done?:continuation
            status="Vorherigen Klang abgleichen";port?.refresh();startConfirmationTimeout(6500);return
        }
        if(area==SoundScope.CALIBRATION && !discard && measurementDirty && !applyMeasurements(false))return
        if(area==SoundScope.CALIBRATION && !discard && hasInvalidGeometry){status="Maße unvollständig oder ungültig";return}
        if(area==SoundScope.CALIBRATION && discard){geometryInvalid=emptySet();coordinateInputs=emptyMap();measurementDraft=null;measurementBasis=null;measurementError=null;placingPosition=null;measurementResetEpoch++}
        val b=if(area==SoundScope.PRESET)presetBasis else calibrationBasis
        if(discard && !remotePending && (!observed.available || conflict(area) || observed.preview?.id!=previewId)){
            if(area==SoundScope.PRESET){presetTouched=false;restoredPresetPending=false;preset=observed.preset?:savedPreset;savedPreset=preset;presetBasis=basis(observed)}else{calibrationTouched=false;restoredCalibrationPending=false;calibration=observed.calibration?:savedCalibration;savedCalibration=calibration;calibrationBasis=basis(observed)}
            if(area==SoundScope.PRESET){newOriginal=null;newSource=null;presetNameRequested=false;presetName=""}
            status=null;updateConflicts()
            persist();closeDecision=null;continuation=null;done?.invoke();return
        }
        if(!canEdit(area))return
        // Commit this exact local goal. A failed/full preview admission cannot
        // accidentally save an older preview with the same lease ID.
        if(!discard && !(area==SoundScope.PRESET&&observed.capabilities.eqProjection&&observed.preview?.id==previewId&&SoundNativeReadback.preset(observed.preset,preset)) && preview(area)==0L)return
        val command=if(discard)AudioSoundCommand.Discard(previewId,area,b)else AudioSoundCommand.Save(previewId,area,b)
        val id=port?.submit(command)?:0
        if(id==0L){status="Ziel nicht angenommen · keine Änderung bestätigt";return}
        if(area==SoundScope.PRESET && newPresetDraft){
            val target=if(discard)newOriginal else preset;val revision=observed.presetRevision;val source=newSource
            libraryConfirmation={it.available && it.sourceGeneration==source && SoundNativeReadback.preset(it.preset,target) && it.preview?.presetDirty!=true && (it.preview==null || it.preview?.id==previewId) && it.presetRevision==(if(discard)revision else revision+1)}
        }
        savingId=id;savingScope=area;discarding=discard;busy=true;continuation=done?:continuation;status="Wird gespeichert"
    }
    fun saveAndClose(){closeDecision?.let{save(it,continuation)}}
    fun refresh(){if(!closed)port?.refresh()}
    override fun close(){
        if(closed)return
        invalidateLocalGoals();afterEq=null;endEqGesture();eqTarget=null;eqTargetLimited=false;endExperiment();persist();editorVisible=false;catalogVisible=false;publishVisibility();closed=true
        localGoals.close();subscriptions.forEach{it.close()};scope.cancel()
    }
}

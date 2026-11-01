package com.no5.domain

import kotlin.math.*

/** Owned sound data. Never contains Camilla paths, YAML or executable filter descriptions. */
// Immutable pattern: point copies in the gesture's band search must validate
// exactly the same identifier without compiling a pattern on every probe.
private val soundModelIdPattern = Regex("[A-Za-z0-9_-]{1,64}")

data class SoundEqPoint(val id: String, val frequencyHz: Double, val gainDb: Double = 0.0, val q: Double = 1.0, val widthLocked: Boolean = false) {
    init { require(id.matches(soundModelIdPattern)); require(frequencyHz.isFinite() && frequencyHz in 20.0..20000.0); require(gainDb.isFinite() && gainDb in -12.0..21.0); require(q.isFinite() && q in .3..10.0) }
    val widthHz get() = frequencyHz / q
    val lowHz get() = frequencyHz * (sqrt(4*q*q+1)-1)/(2*q)
    val highHz get() = frequencyHz * (sqrt(4*q*q+1)+1)/(2*q)
    fun withBandEdge(edgeHz:Double,low:Boolean):SoundEqPoint {
        if(widthLocked)return this
        require(edgeHz.isFinite())
        val edge=edgeHz.coerceIn(1.0,24000.0)
        val lower=if(low)min(frequencyHz/1.01,edge)else frequencyHz*frequencyHz/max(frequencyHz*1.01,edge)
        val upper=frequencyHz*frequencyHz/lower
        return copy(q=(frequencyHz/(upper-lower)).coerceIn(.3,10.0))
    }
    fun move(frequency: Double, gain: Double): SoundEqPoint {
        val f = frequency.coerceIn(20.0, 20000.0)
        // Lock retains Hz bandwidth. Clamp the frequency itself, not Q, when reaching a Q limit.
        val target = if (widthLocked) f.coerceIn(max(20.0, widthHz * .3), min(20000.0, widthHz * 10)) else f
        return copy(frequencyHz = target, gainDb = gain.coerceIn(-12.0,21.0), q = if(widthLocked) target / widthHz else q)
    }
}
data class SoundRoom(val amount: Double = 0.0, val size: Double = .5) {
    init { require(amount.isFinite() && amount in 0.0..1.0 && size.isFinite() && size in 0.0..1.0) }
}
object SoundPresetName {
    fun valid(value:String)=value.isNotBlank() && value.length<=100 && value.toByteArray(Charsets.UTF_8).size<=128 && value.none(Char::isISOControl)
}
data class SoundPresetEntry(val id:String,val name:String,val routingTemplate:String) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")));require(SoundPresetName.valid(name));require(routingTemplate in setOf("front_stereo_fullrange","stereo_4_1","immersive_4_1")) }
}
data class SoundPreset(val id: String, val name: String, val routingTemplate: String, val eqPoints: List<SoundEqPoint> = emptyList(), val room: SoundRoom = SoundRoom(), val eqEnabled:Boolean=false, val reserveDb:Double=12.0) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))); require(name.isNotBlank() && name.length<=100 && name.toByteArray(Charsets.UTF_8).size<=128 && name.none(Char::isISOControl)); require(routingTemplate in setOf("front_stereo_fullrange","stereo_4_1","immersive_4_1")); require(eqPoints.size<=16 && eqPoints.map{it.id}.distinct().size==eqPoints.size);require(routingTemplate!="front_stereo_fullrange" || room.amount==0.0);require(reserveDb.isFinite()&&reserveDb in 6.0..18.0) }
    fun neutral() = copy(eqPoints=eqPoints.map { it.copy(gainDb=0.0) })
}
/** Validated native sound readback only; all identity/authority/revision checks stay exact. */
object SoundNativeReadback {
    // The installed DSP JSON f64 parser can return one adjacent representable
    // value. Never round the editable/saved model or use a relative epsilon.
    fun number(observed:Double,requested:Double):Boolean = observed.isFinite() && requested.isFinite() &&
        (observed==requested || (observed!=0.0 && requested!=0.0 && Math.nextAfter(requested,observed)==observed))
    fun preset(observed:SoundPreset?,requested:SoundPreset?):Boolean {
        if(observed==null || requested==null)return false
        return observed.id==requested.id && observed.name==requested.name && observed.routingTemplate==requested.routingTemplate &&
            observed.eqEnabled==requested.eqEnabled && number(observed.reserveDb,requested.reserveDb) &&
            number(observed.room.amount,requested.room.amount) && number(observed.room.size,requested.room.size) &&
            observed.eqPoints.size==requested.eqPoints.size && observed.eqPoints.zip(requested.eqPoints).all{(a,b)->
                a.id==b.id && a.widthLocked==b.widthLocked && number(a.frequencyHz,b.frequencyHz) && number(a.gainDb,b.gainDb) && number(a.q,b.q)
            }
    }
}
/** Exactly the currently supported published policy, not a second DSP budget validator. */
data class SoundEqPolicy(val minReserveDb:Double=6.0,val maxReserveDb:Double=18.0,val defaultReserveDb:Double=12.0,val positiveBandsMustNotOverlap:Boolean=true) {
    init { require(minReserveDb==6.0&&maxReserveDb==18.0&&defaultReserveDb==12.0&&positiveBandsMustNotOverlap) }
}
data class SoundEqBandLimit(val point:SoundEqPoint,val constrained:Boolean)
/** Visible-band interaction guard. The native prepare owner alone validates summed filter tails. */
object SoundEqBands {
    fun overlap(a:SoundEqPoint,b:SoundEqPoint):Boolean = a.gainDb>0 && b.gainDb>0 &&
        max(max(20.0,a.lowHz),max(20.0,b.lowHz)) < min(min(20000.0,a.highHz),min(20000.0,b.highHz))-1e-9
    fun firstOverlap(points:List<SoundEqPoint>):Pair<Int,Int>? {
        for(i in points.indices)for(j in i+1 until points.size)if(overlap(points[i],points[j]))return i to j
        return null
    }
    fun overlaps(points:List<SoundEqPoint>):Boolean = firstOverlap(points)!=null
    /** Explicit activation of an old bypassed curve: narrow only colliding unlocked bands.
     * Gains, frequencies, locks and unrelated bands survive; DSP still proves the summed ceiling.
     * Impossible/locked collisions return no candidate, never a partial repair. */
    fun separateLegacyBands(points:List<SoundEqPoint>):List<SoundEqPoint>? {
        val result=points.toMutableList()
        for(i in result.indices)for(j in i+1 until result.size){
            val a=result[i];val b=result[j]
            if(!overlap(a,b))continue
            fun narrowed(p:SoundEqPoint,t:Double)=if(p.widthLocked)p else p.copy(q=p.q+(10.0-p.q)*t)
            if(overlap(narrowed(a,1.0),narrowed(b,1.0)))return null
            var low=0.0;var high=1.0
            repeat(48){val mid=(low+high)/2;if(overlap(narrowed(a,mid),narrowed(b,mid)))low=mid else high=mid}
            // Stay strictly separated through JSON/native f64 round trips.
            val safe=(high+1e-10).coerceAtMost(1.0)
            result[i]=narrowed(a,safe);result[j]=narrowed(b,safe)
        }
        return result.takeUnless(::overlaps)
    }
    private fun valid(point:SoundEqPoint,others:List<SoundEqPoint>)=others.none{overlap(point,it)}
    private fun overlapWidth(a:SoundEqPoint,b:SoundEqPoint):Double=if(a.gainDb<=0||b.gainDb<=0)0.0 else max(0.0,min(min(20000.0,a.highHz),min(20000.0,b.highHz))-max(max(20.0,a.lowHz),max(20.0,b.lowHz)))
    /** Allows untouched bypassed legacy curves, never a newly introduced/widened collision. */
    fun compatibleEdit(previous:List<SoundEqPoint>,requested:List<SoundEqPoint>):Boolean {
        if(previous==requested)return true
        val old=previous.associateBy{it.id}
        for(i in requested.indices)for(j in i+1 until requested.size){
            val a=requested[i];val b=requested[j];val width=overlapWidth(a,b)
            if(width<=1e-9)continue
            val oldA=old[a.id]?:return false;val oldB=old[b.id]?:return false
            val prior=overlapWidth(oldA,oldB)
            if(width>prior+1e-9)return false
            if(width>=prior-1e-9&&(a.gainDb>oldA.gainDb||b.gainDb>oldB.gainDb))return false
        }
        return true
    }
    fun constrain(previous:SoundEqPoint,requested:SoundEqPoint,points:List<SoundEqPoint>):SoundEqBandLimit {
        require(previous.id==requested.id)
        val others=points.filter{it.id!=previous.id}
        if(requested.gainDb<=0)return SoundEqBandLimit(requested,false)
        // A zero/cut point can move freely, but cannot acquire positive gain inside a positive band.
        if(previous.gainDb<=0)return if(valid(requested,others))SoundEqBandLimit(requested,false) else SoundEqBandLimit(requested.copy(gainDb=0.0),true)
        if(!valid(previous,others)){
            // Old bypassed curves remain lossless and can be disentangled or reduced explicitly.
            val improving=compatibleEdit(points,points.map{if(it.id==previous.id)requested else it})
            return SoundEqBandLimit(if(improving)requested else previous,!valid(requested,others))
        }
        fun at(t:Double):SoundEqPoint {
            val f=if(previous.frequencyHz==requested.frequencyHz)previous.frequencyHz else exp(ln(previous.frequencyHz)+(ln(requested.frequencyHz)-ln(previous.frequencyHz))*t)
            val keepHz=previous.widthLocked&&requested.widthLocked&&abs(previous.widthHz-requested.widthHz)<1e-6
            val q=if(keepHz)(f/previous.widthHz).coerceIn(.3,10.0)else exp(ln(previous.q)+(ln(requested.q)-ln(previous.q))*t).coerceIn(.3,10.0)
            return requested.copy(frequencyHz=f.coerceIn(20.0,20000.0),gainDb=previous.gainDb+(requested.gainDb-previous.gainDb)*t,q=q)
        }
        // Check the path as well as its end: a fast gesture cannot tunnel through a narrow neighbour.
        var last=0.0
        for(step in 1..128){val t=step/128.0
            if(!valid(at(t),others)){
                var lo=last;var hi=t
                repeat(40){val middle=(lo+hi)/2;if(valid(at(middle),others))lo=middle else hi=middle}
                return SoundEqBandLimit(at(lo),true)
            }
            last=t
        }
        return SoundEqBandLimit(requested,false)
    }
}
/** Editing geometry for ALL visible bands; separate from the historical positive DSP policy.
 * Frequency order is pinned to the previous state, so even a single huge move cannot tunnel.
 * Old overlap is retained losslessly, but an edit can never enlarge it or reverse its order.
 */
object SoundEqVisibleBands {
    private fun width(a:SoundEqPoint,b:SoundEqPoint)=max(0.0,min(20000.0,min(a.highHz,b.highHz))-max(20.0,max(a.lowHz,b.lowHz)))
    fun compatible(previous:List<SoundEqPoint>,requested:List<SoundEqPoint>):Boolean {
        val old=previous.associateBy{it.id}
        for(i in requested.indices)for(j in i+1 until requested.size){
            val a=requested[i];val b=requested[j];val x=old[a.id];val y=old[b.id]
            val before=if(x!=null&&y!=null)width(x,y)else 0.0
            if(width(a,b)>before+1e-9)return false
            if(x!=null&&y!=null){
                val order=x.frequencyHz.compareTo(y.frequencyHz)
                if(order<0&&a.frequencyHz>=b.frequencyHz || order>0&&a.frequencyHz<=b.frequencyHz)return false
            }
        }
        return true
    }
    fun constrain(previous:SoundEqPoint,requested:SoundEqPoint,points:List<SoundEqPoint>):SoundEqBandLimit {
        require(previous.id==requested.id)
        // Gain is irrelevant to visible geometry. The normal vertical gesture
        // must not run 128 path probes / allocate maps on the UI thread.
        if(previous.frequencyHz==requested.frequencyHz&&previous.q==requested.q)
            return SoundEqBandLimit(requested,false)
        fun at(t:Double):SoundEqPoint {
            if(t==0.0)return previous
            if(t==1.0)return requested
            val f=if(previous.frequencyHz==requested.frequencyHz)previous.frequencyHz else exp(ln(previous.frequencyHz)+(ln(requested.frequencyHz)-ln(previous.frequencyHz))*t)
            val keepHz=previous.widthLocked&&requested.widthLocked&&abs(previous.widthHz-requested.widthHz)<1e-6
            val q=if(previous.q==requested.q)previous.q else if(keepHz)(f/previous.widthHz).coerceIn(.3,10.0)else exp(ln(previous.q)+(ln(requested.q)-ln(previous.q))*t).coerceIn(.3,10.0)
            val g=if(previous.gainDb==requested.gainDb)previous.gainDb else previous.gainDb+(requested.gainDb-previous.gainDb)*t
            return requested.copy(frequencyHz=f,gainDb=g,q=q)
        }
        // The requested frame differs from the gesture's base in exactly one
        // point. The old implementation rebuilt a list and an ID map at every
        // one of up to 128 path probes and 40 binary-search steps. Compare the
        // moving point against each peer directly; untouched pairs are exactly
        // unchanged. Fall back to the general reference path for malformed
        // duplicate-ID inputs instead of silently changing their semantics.
        val oldById=points.associateBy{it.id}
        var movingIdCount=0
        for(point in points)if(point.id==previous.id)movingIdCount++
        val movingIdIsUnique=movingIdCount==1
        fun valid(p:SoundEqPoint):Boolean {
            if(!movingIdIsUnique)return compatible(points,points.map{if(it.id==p.id)p else it})
            val priorMoving=oldById[p.id]?:return false
            for(other in points){
                if(other.id==p.id)continue
                val priorOther=oldById[other.id]
                val before=if(priorOther!=null)width(priorMoving,priorOther)else 0.0
                if(width(p,other)>before+1e-9)return false
                if(priorOther!=null){
                    val order=priorMoving.frequencyHz.compareTo(priorOther.frequencyHz)
                    if(order<0&&p.frequencyHz>=other.frequencyHz || order>0&&p.frequencyHz<=other.frequencyHz)return false
                }
            }
            return true
        }
        var separate=true
        for(other in points)if(other.id!=previous.id&&width(previous,other)>1e-9){separate=false;break}
        val ordinary=previous.q==requested.q||previous.frequencyHz==requested.frequencyHz||
            previous.widthLocked&&requested.widthLocked&&abs(previous.widthHz-requested.widthHz)<1e-6
        // Real point/width gestures have monotone edges (fixed Q, fixed center,
        // or fixed Hz width). Pinned center order makes the valid prefix connected.
        if(separate&&ordinary){
            if(valid(requested))return SoundEqBandLimit(requested,false)
            var lo=0.0;var hi=1.0
            repeat(40){val mid=(lo+hi)/2;if(valid(at(mid)))lo=mid else hi=mid}
            return SoundEqBandLimit(at(lo),true)
        }
        var last=0.0
        for(step in 1..128){val t=step/128.0
            if(!valid(at(t))){
                var lo=last;var hi=t
                repeat(40){val mid=(lo+hi)/2;if(valid(at(mid)))lo=mid else hi=mid}
                return SoundEqBandLimit(at(lo),true)
            }
            last=t
        }
        return SoundEqBandLimit(requested,false)
    }
}
enum class SoundSpeakerId { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT, SUB }
data class SoundPosition(val x: Double, val y: Double, val z: Double? = null) {
    init { require(listOfNotNull(x,y,z).all { it.isFinite() && it in -50.0..50.0 }) }
    fun distance(other: SoundPosition): Double = sqrt((x-other.x).pow(2)+(y-other.y).pow(2)+(if(z!=null && other.z!=null)(z-other.z).pow(2)else 0.0))
}
data class SoundSpeaker(val id: SoundSpeakerId, val positionM: SoundPosition? = null, val manualDelayMs: Double = 0.0, val manualGainDb: Double = 0.0) {
    init { require(manualDelayMs.isFinite() && manualDelayMs in -50.0..50.0); require(manualGainDb.isFinite() && manualGainDb in -24.0..24.0) }
}
data class SoundCalibration(val speakers: List<SoundSpeaker> = SoundSpeakerId.entries.map(::SoundSpeaker), val listenerM: SoundPosition? = null, val geometryEnabled: Boolean = false) {
    init { require(speakers.size==5 && speakers.map { it.id }.toSet()==SoundSpeakerId.entries.toSet());require(!geometryEnabled || (listenerM!=null && speakers.all{it.positionM!=null})) }
    val completeGeometry get() = listenerM!=null && speakers.all{it.positionM!=null}
    val approximate2d get() = listenerM?.z==null || speakers.any{it.positionM?.z==null}
}
data class SoundPreview(val id: String, val expiresAtEpochMs: Long, val presetDirty: Boolean, val calibrationDirty: Boolean)
data class SoundCapabilities(val parametricEq: Boolean=false, val room: Boolean=false, val speakerAlignment: Boolean=false, val spectrum: Boolean=false,val presetLibrary:Boolean=false,val presetManagement:Boolean=false,val eqProjection:Boolean=false,val experimentalEq:Boolean=false,val commandStream:Boolean=false)
/** Saved miniature: 48 log-spaced 20 Hz..20 kHz samples, clipped ±18 dB / quarter-dB. Never live FFT. */
data class SoundPresetPreview(val modelSha256:String,val gainsDb:List<Double>,val eqEnabled:Boolean,val reserveDb:Double) {
    init {require(modelSha256.matches(Regex("[0-9a-f]{64}")));require(gainsDb.size==48&&gainsDb.all{it.isFinite()&&it in -18.0..18.0});require(reserveDb.isFinite()&&reserveDb in 6.0..18.0)}
}
data class SoundRoutingProfile(val presetId:String){
    init{require(presetId.matches(Regex("[A-Za-z0-9_-]{1,100}")))}
}
data class SoundEffectiveSpeaker(val id:SoundSpeakerId,val distanceM:Double?,val delayMs:Double,val gainDb:Double)
data class SoundEffectiveCalibration(val speakers:List<SoundEffectiveSpeaker>,val dimensions:Int?,val approximate:Boolean)
data class SoundEqValidationPolicy(val schema:String,val kernelSha256:String,val sampleRateHz:Int,val gridPoints:Int,val minReserveDb:Int,val maxReserveDb:Int,val experimentalBoostDb:Int)
data class SoundEqEditing(val experimental:Boolean=false,val constrained:Boolean=false,val normalPreset:SoundPreset?=null,val validationPolicy:SoundEqValidationPolicy?=null)
data class SoundEqEditIntent(val experimental:Boolean=false,val previousPoint:SoundEqPoint?=null)
data class AudioSoundState(val available: Boolean=false, val writable: Boolean=false, val configGeneration: String="", val sourceGeneration: String="", val presetRevision: Long=0, val calibrationRevision: Long=0, val configurationRevision: Long=0, val preset: SoundPreset?=null, val calibration: SoundCalibration?=null, val headroomDb: Double=0.0, val preview: SoundPreview?=null, val capabilities: SoundCapabilities=SoundCapabilities(), val reason: String?=null, val effectiveCalibration:SoundEffectiveCalibration?=null,val eqPolicy:SoundEqPolicy=SoundEqPolicy(),val presetCatalog:List<SoundPresetEntry> = emptyList(),val presetPreviews:Map<String,SoundPresetPreview> = emptyMap(),val routingProfiles:Map<String,SoundRoutingProfile> = emptyMap(),val eqEditing:SoundEqEditing?=null)
/** A frame carries the original audio sample position. Receiving it never re-dates those samples. */
data class AudioSpectrumFrame(val sequence: Long, val sourceGeneration: String, val samplePosition: Long, val sampleRateHz: Int, val fftSize: Int, val frequenciesHz: List<Double>, val binsDbfs: List<Double>, val peaksDbfs: List<Double>, val overRangeSamples: Long,val sampleTimeNs:Long?=null) {
    init { require(sequence>=0 && sourceGeneration.length in 1..128 && sourceGeneration.all{it.code in 33..126} && samplePosition>=0); require(sampleRateHz==48000 && fftSize==8192); require(frequenciesHz.size in 2..256 && frequenciesHz.size==binsDbfs.size); require(frequenciesHz.all { it.isFinite() && it in 20.0..20000.0 } && frequenciesHz.zipWithNext().all{it.first<it.second}); require(binsDbfs.all { it.isFinite() && it in -240.0..24.0 }); require(peaksDbfs.size==2 && peaksDbfs.all { it.isFinite() && it in -240.0..24.0 }); require(overRangeSamples>=0 && (sampleTimeNs==null || sampleTimeNs>=0)) }
}
enum class SoundScope { PRESET, CALIBRATION }
data class SoundRevisionBasis(val configGeneration:String,val presetRevision:Long,val calibrationRevision:Long)
sealed interface AudioSoundCommand {
    val basis:SoundRevisionBasis? get()=null
    val previewId: String
    data class Preview(override val previewId:String, val scope:SoundScope, val preset:SoundPreset?=null, val calibration:SoundCalibration?=null, override val basis:SoundRevisionBasis?=null,val eqEditing:SoundEqEditIntent?=null,val locallyValidated:Boolean=false):AudioSoundCommand {
        init { require(eqEditing==null||scope==SoundScope.PRESET);require((scope==SoundScope.PRESET && preset!=null && calibration==null)||(scope==SoundScope.CALIBRATION && calibration!=null && preset==null)) }
    }
    data class Keepalive(override val previewId:String,val experimental:Boolean?=null):AudioSoundCommand
    data class Save(override val previewId:String,val scope:SoundScope,override val basis:SoundRevisionBasis?=null):AudioSoundCommand
    data class Discard(override val previewId:String,val scope:SoundScope,override val basis:SoundRevisionBasis?=null):AudioSoundCommand
    data class SaveAs(override val previewId:String,val name:String,override val basis:SoundRevisionBasis?=null):AudioSoundCommand {
        init {require(SoundPresetName.valid(name))}
    }
    data class LoadPreset(override val previewId:String,val presetId:String,override val basis:SoundRevisionBasis?=null):AudioSoundCommand {
        init {require(presetId.matches(Regex("[A-Za-z0-9_-]{1,64}")))}
    }
    data class DeletePreset(override val previewId:String,val presetId:String,override val basis:SoundRevisionBasis?=null):AudioSoundCommand {
        init {require(presetId.matches(Regex("[A-Za-z0-9_-]{1,64}")))}
    }
}
data class AudioSoundResult(val id:Long,val status:String,val reason:String="") { val terminal get()=status !in setOf("ACCEPTED","RUNNING") }
interface AudioSoundPort:AutoCloseable {
    fun setExperimentalAllowed(allowed:Boolean) {}
    val state:SourceFeed<AudioSoundState>
    val spectrum:SourceFeed<AudioSpectrumFrame?>
    val results:SourceFeed<AudioSoundResult?>
    /** Bounded memory-only admission; zero means refused. No network in this call. */
    fun submit(command:AudioSoundCommand):Long
    fun setEditorVisible(visible:Boolean,eqVisible:Boolean)
    fun refresh()
}
/** RBJ peaking coefficients, identical to Camilla's Peaking biquad with Q bandwidth. */
object SoundEqResponse {
    /** Display-only batch. Same arithmetic/order as gainDb; immutable coefficients once per curve. */
    fun gainsDb(points:List<SoundEqPoint>,frequenciesHz:DoubleArray,sampleRate:Double=48000.0):DoubleArray {
        val coefficients=points.map{p->
            val a=10.0.pow(p.gainDb/40);val w0=2*PI*p.frequencyHz/sampleRate;val alpha=sin(w0)/(2*p.q)
            val b0=1+alpha*a;val b1=-2*cos(w0);val b2=1-alpha*a;val a0=1+alpha/a;val a1=b1;val a2=1-alpha/a
            doubleArrayOf(b0,b1,b2,a0,a1,a2)
        }
        return DoubleArray(frequenciesHz.size){index->
            val f=frequenciesHz[index];require(f.isFinite() && f in 0.0..sampleRate/2)
            val w=2*PI*f/sampleRate;val cw=cos(w);val c2w=cos(2*w);val sw=sin(w);val s2w=sin(2*w)
            coefficients.sumOf{c->
                val nr=c[0]+c[1]*cw+c[2]*c2w;val ni=-c[1]*sw-c[2]*s2w
                val dr=c[3]+c[4]*cw+c[5]*c2w;val di=-c[4]*sw-c[5]*s2w
                10*log10((nr*nr+ni*ni)/(dr*dr+di*di)).coerceIn(-240.0,240.0)
            }
        }
    }
    fun gainDb(points:List<SoundEqPoint>, frequencyHz:Double,sampleRate:Double=48000.0):Double {
        require(frequencyHz.isFinite() && frequencyHz in 0.0..sampleRate/2)
        val w=2*PI*frequencyHz/sampleRate
        return points.sumOf { p ->
            val a=10.0.pow(p.gainDb/40); val w0=2*PI*p.frequencyHz/sampleRate;val alpha=sin(w0)/(2*p.q)
            val b0=1+alpha*a;val b1=-2*cos(w0);val b2=1-alpha*a;val a0=1+alpha/a;val a1=b1;val a2=1-alpha/a
            val nr=b0+b1*cos(w)+b2*cos(2*w);val ni=-b1*sin(w)-b2*sin(2*w)
            val dr=a0+a1*cos(w)+a2*cos(2*w);val di=-a1*sin(w)-a2*sin(2*w)
            10*log10((nr*nr+ni*ni)/(dr*dr+di*di)).coerceIn(-240.0,240.0)
        }
    }
}

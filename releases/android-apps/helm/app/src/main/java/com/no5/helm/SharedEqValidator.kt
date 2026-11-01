package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.AudioSoundCodec
import org.json.JSONObject

/** Runs only on the single validation worker. No Android view or authority is passed to JNI. */
interface EqValidator {
    fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?):SoundPreset
}
/** Gesture geometry is non-authoritative work and must never run on the UI thread. */
internal fun interface EqGeometryProjector {
    fun project(previous:SoundEqPoint,requested:SoundEqPoint,points:List<SoundEqPoint>):SoundEqBandLimit
}
internal object CanonicalEqGeometryProjector:EqGeometryProjector {
    override fun project(previous:SoundEqPoint,requested:SoundEqPoint,points:List<SoundEqPoint>) =
        SoundEqVisibleBands.constrain(previous,requested,points)
}
/** Output invariants, not a second EQ calculation. A parser or bridge may not
 * change fields which the canonical projection was required to preserve. */
internal fun stableEqProjection(previous:SoundPreset,requested:SoundPreset,projected:SoundPreset,pointId:String?):SoundPreset {
    require(projected.copy(eqPoints=requested.eqPoints)==requested){"EQ_VALIDATOR_MODEL_DRIFT"}
    require(projected.eqPoints.map{it.id}==requested.eqPoints.map{it.id}){"EQ_VALIDATOR_POINT_DRIFT"}
    requested.eqPoints.zip(projected.eqPoints).forEach{(wanted,actual)->
        if(pointId!=null && wanted.id!=pointId)require(actual==wanted){"EQ_VALIDATOR_PEER_DRIFT"}
        else {
            val before=previous.eqPoints.firstOrNull{it.id==wanted.id}
            fun unchanged(a:Double,b:Double)=a.toBits()==b.toBits()
            if(pointId==null || before?.let{unchanged(it.frequencyHz,wanted.frequencyHz)}==true)
                require(unchanged(actual.frequencyHz,wanted.frequencyHz)){"EQ_VALIDATOR_FREQUENCY_DRIFT"}
            if(pointId==null || before?.let{unchanged(it.q,wanted.q)}==true)
                require(unchanged(actual.q,wanted.q)){"EQ_VALIDATOR_WIDTH_DRIFT"}
            if(pointId==null && wanted.gainDb<=0.0 || pointId!=null && before?.let{unchanged(it.gainDb,wanted.gainDb)}==true)
                require(unchanged(actual.gainDb,wanted.gainDb)){"EQ_VALIDATOR_GAIN_DRIFT"}
            require(actual.widthLocked==wanted.widthLocked){"EQ_VALIDATOR_LOCK_DRIFT"}
        }
    }
    return projected
}
internal object SharedEqNative {
    init { System.loadLibrary("no5_eq") }
    @JvmStatic external fun process(input:ByteArray):ByteArray?
}
internal object NativeEqValidator:EqValidator {
    private fun call(value:JSONObject):JSONObject=JSONObject(String(requireNotNull(SharedEqNative.process(value.toString().toByteArray(Charsets.UTF_8))),Charsets.UTF_8))
    private val identity:JSONObject by lazy {call(JSONObject().put("kind","policy")).getJSONObject("policy")}
    override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?):SoundPreset {
        requireNotNull(policy){"EQ_VALIDATOR_UNAVAILABLE"}
        require(policy.schema==identity.getString("schema")&&policy.kernelSha256==identity.getString("kernelSha256")&&
            policy.sampleRateHz==identity.getInt("sampleRateHz")&&policy.gridPoints==identity.getInt("gridPoints")&&
            policy.minReserveDb==identity.getInt("minReserveDb")&&policy.maxReserveDb==identity.getInt("maxReserveDb")&&policy.experimentalBoostDb==identity.getInt("experimentalBoostDb")){"EQ_VALIDATOR_MISMATCH"}
        val result=call(JSONObject().put("kind","project").put("previous",AudioSoundCodec.preset(previous)).put("requested",AudioSoundCodec.preset(requested))
            .put("editedPointId",pointId?:JSONObject.NULL).put("experimental",experimental))
        require(!result.has("error")){result.optString("error","EQ_VALIDATION_FAILED")}
        require(result.getJSONObject("policy").toString()==identity.toString())
        return AudioSoundCodec.preset(result.getJSONObject("preset"))
    }
}

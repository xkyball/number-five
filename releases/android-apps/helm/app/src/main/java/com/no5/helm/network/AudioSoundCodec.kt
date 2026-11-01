package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Bounded typed extension. Raw configuration documents are never accepted or emitted. */
internal object AudioSoundCodec {
    fun state(json:String):AudioSoundState = checked {
        val o=JSONObject(json.also { require(it.toByteArray().size<=65536) })
        require(o.getInt("version")==2 && o.getString("kind")=="audio-sound")
        val available=o.getBoolean("available")
        if(!available && (!o.has("preset") || o.isNull("preset"))) return@checked AudioSoundState(reason=o.optString("reason","UNAVAILABLE").take(160))
        val c=o.getJSONObject("capabilities")
        val library=if(c.has("presetLibrary"))c.get("presetLibrary").let{require(it is Boolean);it}else false
        val management=if(c.has("presetManagement"))c.get("presetManagement").let{require(it is Boolean);it}else false
        require(!management||library)
        val catalog=if(library){
            val a=o.getJSONArray("presetCatalog");require(a.length()<=64)
            List(a.length()){a.getJSONObject(it).let{entry->
                require(entry.keys().asSequence().toSet()==setOf("id","name","routingTemplate"))
                SoundPresetEntry(entry.getString("id"),entry.getString("name"),entry.getString("routingTemplate"))
            }}.also{require(it.map{p->p.id}.distinct().size==it.size)}
        }else emptyList()
        val previews=if(management){
            val entries=o.getJSONObject("presetPreviews");val ids=entries.keys().asSequence().toList()
            require(ids.size<=64&&ids.all{id->catalog.any{it.id==id}})
            ids.associateWith{id->entries.getJSONObject(id).let{entry->
                require(entry.keys().asSequence().toSet()==setOf("sha256b64","db4i8","eqEnabled","reserveDb"))
                val encoded=entry.getString("db4i8");require(encoded.matches(Regex("[A-Za-z0-9+/]{64}")))
                val bytes=java.util.Base64.getDecoder().decode(encoded);require(bytes.size==48)
                val hash=entry.getString("sha256b64");require(hash.matches(Regex("[A-Za-z0-9+/]{43}=")))
                val digest=java.util.Base64.getDecoder().decode(hash);require(digest.size==32)
                SoundPresetPreview(buildString(digest.size*2){val hex="0123456789abcdef";for(byte in digest){val value=byte.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])}},bytes.map{it.toDouble()/4},entry.get("eqEnabled").let{require(it is Boolean);it},entry.finite("reserveDb",6.0..18.0))
            }}
        }else emptyMap()
        val routingProfiles=if(management && o.has("routingProfiles")){
            val routes=o.getJSONObject("routingProfiles");val keys=routes.keys().asSequence().toList()
            require(keys.size<=3 && keys.all{it in setOf("front_stereo_fullrange","stereo_4_1","immersive_4_1")})
            keys.associateWith{key->SoundRoutingProfile(routes.get(key).let{require(it is String);it})}.also{require(it.values.map{v->v.presetId}.distinct().size==it.size)}
        }else emptyMap()
        fun capability(key:String)=if(c.has(key))c.get(key).let{require(it is Boolean);it}else false
        val projection=capability("eqProjection");val experimental=capability("experimentalEq");require(!experimental||projection)
        val editing=if(o.has("eqEditing")&&!o.isNull("eqEditing"))o.getJSONObject("eqEditing").let{e->
            require(projection && e.getString("schema")=="no5.eq-editing/1" && e.get("previewProjection")==true && e.finite("maxExperimentalBoostDb",3.0..3.0)==3.0)
            val active=e.get("experimental").let{require(it is Boolean);it};val bounded=e.get("constrained").let{require(it is Boolean);it}
            val normal=if(e.isNull("normalPreset"))null else preset(e.getJSONObject("normalPreset"))
            require(if(active)normal!=null&&normal.eqPoints.all{it.gainDb<=normal.reserveDb}else normal==null)
            SoundEqEditing(active,bounded,normal,if(e.has("validationPolicy"))e.getJSONObject("validationPolicy").let{v->SoundEqValidationPolicy(v.getString("schema"),v.getString("kernelSha256"),v.getInt("sampleRateHz"),v.getInt("gridPoints"),v.getInt("minReserveDb"),v.getInt("maxReserveDb"),v.getInt("experimentalBoostDb"))}else null)
        }else null
        require(!projection||editing!=null)
        val model=preset(o.getJSONObject("preset"))
        editing?.normalPreset?.let{normal->
            require(SoundNativeReadback.preset(normal.copy(eqPoints=model.eqPoints),model))
            var scale:Double?=null
            normal.eqPoints.zip(model.eqPoints).forEach{(a,b)->
                require(a.id==b.id && a.widthLocked==b.widthLocked && SoundNativeReadback.number(a.frequencyHz,b.frequencyHz) && SoundNativeReadback.number(a.q,b.q))
                if(b.gainDb<=0)require(SoundNativeReadback.number(a.gainDb,b.gainDb)) else {
                    require(a.gainDb>=0 && a.gainDb<=b.gainDb)
                    val factor=a.gainDb/b.gainDb
                    scale?.let{require(kotlin.math.abs(it-factor)<=1e-12)};scale=factor
                }
            }
        }
        val headroom=o.finite("headroomDb",0.0..18.0)
        if(available)require(kotlin.math.abs(headroom-(if(model.eqEnabled)model.reserveDb else 0.0))<=1e-8)
        AudioSoundState(available,o.getBoolean("writable"),o.getString("configGeneration").also { require(it.length in 1..128 && it.all { ch->ch.code in 33..126 }) },
            o.getString("sourceGeneration").also { require(it.length in 1..128 && it.all { ch->ch.code in 33..126 }) },o.rev("presetRevision"),o.rev("calibrationRevision"),o.rev("configurationRevision"),
            model,calibration(o.getJSONObject("calibration")),headroom,
            if(!o.has("preview")||o.isNull("preview"))null else o.getJSONObject("preview").let{SoundPreview(it.getString("id"),it.rev("expiresAtEpochMs"),it.getBoolean("presetDirty"),it.getBoolean("calibrationDirty"))},
            SoundCapabilities(c.getBoolean("parametricEq"),c.getBoolean("room"),c.getBoolean("speakerAlignment"),c.getBoolean("spectrum"),library,management,projection,experimental,capability("commandStream")),eqEditing=editing,presetCatalog=catalog,presetPreviews=previews,routingProfiles=routingProfiles,reason=if(o.has("reason")&&!o.isNull("reason"))o.getString("reason").take(160)else null,effectiveCalibration=if(o.has("effectiveCalibration")&&!o.isNull("effectiveCalibration"))effective(o.getJSONObject("effectiveCalibration"))else null,eqPolicy=o.getJSONObject("eqPolicy").let{SoundEqPolicy(it.finite("minReserveDb",6.0..6.0),it.finite("maxReserveDb",18.0..18.0),it.finite("defaultReserveDb",12.0..12.0),it.get("positiveBandsMustNotOverlap").let{flag->require(flag is Boolean);flag})})
    }
    private fun effective(o:JSONObject):SoundEffectiveCalibration {
        val a=o.getJSONArray("speakers");require(a.length()==5)
        val speakers=List(a.length()){a.getJSONObject(it).let{s->SoundEffectiveSpeaker(SoundSpeakerId.valueOf(s.getString("id")),if(s.isNull("distanceM"))null else s.finite("distanceM",0.0..1000.0),s.finite("delayMs",0.0..50.0),s.finite("gainDb",-120.0..0.0))}}
        require(speakers.map{it.id}.toSet()==SoundSpeakerId.entries.toSet())
        val dimensions=if(o.isNull("dimensions"))null else o.getInt("dimensions").also{require(it in 2..3)}
        return SoundEffectiveCalibration(speakers,dimensions,o.getBoolean("approximate"))
    }
    fun preset(o:JSONObject)=SoundPreset(o.getString("id"),o.getString("name"),o.getString("routingTemplate"),
        o.getJSONArray("eqPoints").let { a->require(a.length()<=16);List(a.length()){a.getJSONObject(it).let{p->SoundEqPoint(p.getString("id"),p.finite("frequencyHz",20.0..20000.0),p.finite("gainDb",-12.0..21.0),p.finite("q",.3..10.0),p.getBoolean("widthLocked"))}} },
        o.getJSONObject("room").let { SoundRoom(it.finite("amount",0.0..1.0),it.finite("size",0.0..1.0)) },o.get("eqEnabled").let{require(it is Boolean);it},o.finite("reserveDb",6.0..18.0))
    /** Only local historical drafts may lack the new fields; never infer that their EQ is enabled. */
    fun presetDraft(o:JSONObject):SoundPreset {
        if(!o.has("eqEnabled")&&!o.has("reserveDb"))return preset(JSONObject(o.toString()).put("eqEnabled",false).put("reserveDb",12.0))
        return preset(o)
    }
    fun calibration(o:JSONObject)=SoundCalibration(o.getJSONArray("speakers").let{a->require(a.length()==5);List(a.length()){a.getJSONObject(it).let{s->SoundSpeaker(SoundSpeakerId.valueOf(s.getString("id")),position(s,"positionM"),s.finite("manualDelayMs",-50.0..50.0),s.finite("manualGainDb",-24.0..24.0))}}},position(o,"listenerM"),o.getBoolean("geometryEnabled"))
    private fun position(o:JSONObject,key:String):SoundPosition? = if(!o.has(key)||o.isNull(key))null else o.getJSONObject(key).let{SoundPosition(it.finite("x",-50.0..50.0),it.finite("y",-50.0..50.0),if(it.isNull("z")||!it.has("z"))null else it.finite("z",-50.0..50.0))}
    fun preset(p:SoundPreset)=JSONObject().put("id",p.id).put("name",p.name).put("routingTemplate",p.routingTemplate).put("eqPoints",JSONArray(p.eqPoints.map { JSONObject().put("id",it.id).put("frequencyHz",it.frequencyHz).put("gainDb",it.gainDb).put("q",it.q).put("widthLocked",it.widthLocked) })).put("room",JSONObject().put("amount",p.room.amount).put("size",p.room.size)).put("eqEnabled",p.eqEnabled).put("reserveDb",p.reserveDb)
    fun calibration(c:SoundCalibration)=JSONObject().put("geometryEnabled",c.geometryEnabled).put("listenerM",position(c.listenerM)).put("speakers",JSONArray(c.speakers.map{JSONObject().put("id",it.id.name).put("positionM",position(it.positionM)).put("manualDelayMs",it.manualDelayMs).put("manualGainDb",it.manualGainDb)}))
    private fun position(p:SoundPosition?):Any=if(p==null)JSONObject.NULL else JSONObject().put("x",p.x).put("y",p.y).put("z",p.z?:JSONObject.NULL)
    fun command(session:String,id:String,server:String,revision:Long,state:AudioSoundState,c:AudioSoundCommand):String {
        val p=JSONObject().put("previewId",c.previewId).put("configGeneration",state.configGeneration)
        when(c){
            is AudioSoundCommand.Keepalive -> {p.put("type","soundKeepalive");c.experimental?.let{p.put("experimental",it)}}
            is AudioSoundCommand.Preview -> {
                p.put("type","soundPreview").put("scope",c.scope.name.lowercase()).put("model",if(c.scope==SoundScope.PRESET)preset(requireNotNull(c.preset))else calibration(requireNotNull(c.calibration)))
                c.eqEditing?.let{e->p.put("eqEditing",JSONObject().put("schema","no5.eq-editing/1").put("experimental",e.experimental).put("previousPoint",e.previousPoint?.let{point->preset(requireNotNull(c.preset).copy(eqPoints=listOf(point))).getJSONArray("eqPoints").getJSONObject(0)}?:JSONObject.NULL).put("editedPointId",e.previousPoint?.id?:JSONObject.NULL))}
            }
            is AudioSoundCommand.Save -> p.put("type","soundSave").put("scope",c.scope.name.lowercase())
            is AudioSoundCommand.Discard -> p.put("type","soundDiscard").put("scope",c.scope.name.lowercase())
            is AudioSoundCommand.SaveAs -> p.put("type","soundSaveAs").put("scope","preset").put("name",c.name)
            is AudioSoundCommand.LoadPreset -> p.put("type","soundLoadPreset").put("scope","preset").put("presetId",c.presetId)
            is AudioSoundCommand.DeletePreset -> p.put("type","soundDeletePreset").put("scope","preset").put("presetId",c.presetId)
        }
        if(c !is AudioSoundCommand.Keepalive)p.put("expectedPresetRevision",state.presetRevision).put("expectedCalibrationRevision",state.calibrationRevision)
        return JSONObject().put("version",1).put("kind","audio-command").put("sessionId",session).put("requestId",id).put("serverSessionId",server).put("expectedRevision",revision).put("command",p).toString().also{require(it.toByteArray().size<=16384)}
    }
    fun spectrum(json:String):AudioSpectrumFrame?=checked{
        val o=JSONObject(json.also{require(it.toByteArray().size<=32768)})
        if(o.optString("kind")=="audio-spectrum-status"){require(o.getInt("version")==1);return@checked null}
        require(o.getString("schema")=="no5.audio-spectrum/1" && o.getString("window")=="HANN" && o.getString("tap")=="POST_EQ_PRE_ROOM" && o.getString("timebase")=="CAPTURE_MONOTONIC_NS")
        fun values(k:String)=o.getJSONArray(k).let{a->require(a.length()<=256);List(a.length()){a.getDouble(it)}}
        AudioSpectrumFrame(o.rev("sequence"),o.getString("sourceGeneration"),o.rev("samplePosition"),o.getInt("sampleRateHz"),o.getInt("fftSize"),values("frequenciesHz"),values("binsDbfs"),values("peaksDbfs"),o.rev("overRangeSamples"),o.rev("sampleTimeNs"))
    }
    private fun JSONObject.rev(k:String)=get(k).let{require(it is Number);val n=it.toLong();require(n>=0 && it.toDouble()==n.toDouble());n}
    private fun JSONObject.finite(k:String,r:ClosedFloatingPointRange<Double>)=get(k).let{require(it is Number);it.toDouble().also{n->require(n.isFinite() && n in r)}}
    private fun <T> checked(f:()->T):T=try{f()}catch(e:Exception){throw PiProtocolException("Invalid audio sound extension",e)}
}

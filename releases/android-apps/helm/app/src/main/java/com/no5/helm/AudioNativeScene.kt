package com.no5.helm

import com.no5.helm.numeric.*

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asAndroidPath
import com.no5.domain.*
import com.no5.header.artwork.No5Marquee
import com.no5.ui.No5Text
import java.util.Locale
import kotlin.math.*

/** Player-local coordinates; player drawing applies [PLAYER_Y] exactly once.
 * The preview's ink is reserved below the two music buttons and above seek. */
internal object AudioUpcomingLayout {
    const val PLAYER_Y = -30f
    const val HEADER_Y = 545f
    const val HEADER_SIZE = 18f
    const val TITLE_Y = 574f
    const val TITLE_SIZE = 25f
    const val ROW_STEP = 52f
    const val ARTIST_OFFSET = 22f
    const val ARTIST_SIZE = 19f
    const val STATUS_Y = 671f
    const val STATUS_SIZE = 18f
}
/** Compact optical label; the complete localized action belongs to the semantic hit target. */
internal fun audioShuffleCaption(context:Context)=No5Text.text(context,"Zufall").uppercase(Locale.ROOT)

/** Fully native rendering. All geometry is recomposed for 2000×1200, never stretched vertically. */
internal class AudioNativeScene(private val context: Context, private val night: Boolean) {
    private val hero = context.resources.getFont(R.font.barlow_condensed_extrabold)
    private val label = context.resources.getFont(R.font.barlow_condensed_semibold)
    private val regular = context.resources.getFont(R.font.barlow_condensed_regular)
    private val ink = Color.parseColor(if(night) "#d40000" else "#07121b")
    private val secondary = Color.parseColor(if(night) "#b00000" else "#344b59")
    private val structural = Color.parseColor(if(night) "#700000" else "#90a0a7")
    private val accent = Color.parseColor(if(night) "#ff1a0a" else "#ff5b00")
    private val surface = Color.parseColor(if(night) "#090000" else "#f6f8f7")
    private val control = Color.parseColor(if(night) "#140000" else "#ffffff")
    private val quiet = Color.parseColor(if(night) "#300000" else "#d3dcdf")
    private val shell = Color.parseColor(if(night) "#000000" else "#09202a")
    private val meterInk = Color.parseColor(if(night) "#b00000" else "#204856")
    private val pathCache = HashMap<String,Path>()
    private data class RecordedSection(val key: Any, val picture: Picture)
    private val sections = HashMap<String, RecordedSection>()
    private val recordings = HashMap<String, Int>()
    internal fun recordingCounts(): Map<String, Int> = recordings.toMap()
    private fun cached(c: Canvas, name: String, key: Any, draw: (Canvas) -> Unit) {
        val previous = sections[name]
        val section = if (previous?.key == key) previous else {
            val picture = Picture()
            draw(picture.beginRecording(2000, 1200))
            picture.endRecording()
            recordings[name] = (recordings[name] ?: 0) + 1
            RecordedSection(key, picture).also { sections[name] = it }
        }
        c.drawPicture(section.picture)
    }
    private fun faderKey(ctl: AudioNativeController, control: AudioFaderControl) = listOf(
        control.gatewayId, control.physicalIndex, ctl.displayName(control), control.limit,
        ctl.level(control), control.muted, ctl.canPresentAdjust(control),
        ctl.canPresent(AudioCommand.Mute(control.gatewayId, !control.muted)),
        audioGroupMeter(ctl.state, control.gatewayId), ctl.state.dsp.meters?.quality?.status,
        ctl.state.dsp.meters?.quality?.simulated,
    )
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = hero; textSize = 76f }
    private var upcomingMetadata: AudioMediaMetadata? = null
    private var upcomingAt = Long.MIN_VALUE
    private var upcomingValue = AudioUpcoming(emptyList(), UpcomingQuality.UNAVAILABLE)
    private fun upcoming(metadata:AudioMediaMetadata,nowEpochMs:Long):AudioUpcoming {
        if(upcomingMetadata !== metadata || upcomingAt!=nowEpochMs) {
            upcomingMetadata=metadata;upcomingAt=nowEpochMs
            upcomingValue=audioUpcoming(metadata,nowEpochMs)
        }
        return upcomingValue
    }
    internal fun titleOverflows(title: String?) = titlePaint.measureText(title ?: No5Text.text(context, "NO TRACK")) > 458f
    fun draw(c:Canvas,ctl:AudioNativeController,marqueeElapsedMs:Long=0L,animateTitle:Boolean=false,nowEpochMs:Long=System.currentTimeMillis()) {
        c.save();c.clipRect(0f,130f,2000f,1058f)
        cached(c, "frame", Unit) { frame ->
            fill(frame,0f,130f,2000f,928f,surface)
            path(frame,"M0 172Q20 142 64 142H1936Q1980 142 2000 172V1058H0Z",structural,1.5f,surface)
            path(frame,"M4 154Q24 127 64 127H119M1881 127H1936Q1976 127 1996 154",accent,3.2f)
            line(frame,1008f,193f,1008f,924f,structural,1.5f);line(frame,1622f,193f,1622f,924f,structural,1.5f)
        }
        val s = ctl.state
        player(c, ctl, marqueeElapsedMs, animateTitle, nowEpochMs)
        val master=ctl.masterControl()
        ctl.visibleFaderControls.filter { it != master }.take(3).forEachIndexed { slot, control ->
            cached(c, "zone-$slot", faderKey(ctl, control)) { zone(it, ctl, control, slot) }
        }
        master?.takeIf(ctl::isVisible)?.let { control ->
            cached(c, "master", faderKey(ctl, control)) { master(it, ctl, control) }
        }
        cached(c, "footer", listOf(s.dsp.preset, ctl.presets.isNotEmpty(), ctl.toneAvailable)) {
            button(it,No5Text.ui("Audio einrichten")+" ›",AudioNativeGeometry.settings,true)
        }
        cached(c, "media-navigation-buttons", ctl.mediaAvailable) {
            button(it, No5Text.ui("ALS NÄCHSTES"), AudioNativeGeometry.nextSongs, ctl.mediaAvailable)
            button(it, No5Text.ui("MUSIK"), AudioNativeGeometry.music, ctl.mediaAvailable)
        }
        // Compose owns the presentation editor and preset sheet; keep only the AUDIO background here.
        c.restore()
        ctl.displayed()
    }
    private fun player(c:Canvas,ctl:AudioNativeController,marqueeElapsedMs:Long,animateTitle:Boolean,nowEpochMs:Long) {
        c.save();c.translate(0f,AudioUpcomingLayout.PLAYER_Y)
        val s=ctl.state;val g=AudioNativeGeometry
        cached(c, "player-heading", listOf(ctl.isSimulation, s.source.quality.status)) {
            text(it,No5Text.ui("NOW PLAYING"),54f,218f,25f,color=secondary)
            text(it,audioPanelSourceLabel(ctl),970f,218f,22f,color=secondary,align=Paint.Align.RIGHT)
        }
        val b=g.artwork;val id=s.media.artworkId?.takeIf { it in AudioArtworkCatalog.ids }
        val bitmap = ctl.currentMediaBitmap.takeUnless { night }
        cached(c, "artwork", listOf(id, bitmap, night, s.source.quality.status != TelemetryStatus.LIVE)) { artwork ->
            artwork.save()
            if(bitmap!=null) {
                fill(artwork,b.x,b.y,b.width,b.height,shell)
                val factor = minOf(b.width / bitmap.width, b.height / bitmap.height)
                val w = bitmap.width * factor; val h = bitmap.height * factor
                val imageRect = RectF(b.centerX-w/2,b.centerY-h/2,b.centerX+w/2,b.centerY+h/2)
                artwork.clipPath(Path().apply{addRoundRect(imageRect,8f,8f,Path.Direction.CW)})
                artwork.drawBitmap(bitmap, null, imageRect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else if(id!=null) {
                artwork.clipPath(Path().apply{addRoundRect(RectF(b.x,b.y,b.right,b.bottom),8f,8f,Path.Direction.CW)})
                artwork.translate(b.x,b.y);artwork.scale(b.width/306f,b.height/306f)
                proceduralArtwork(artwork,AudioNativeRect(0f,0f,306f,306f),id)
            } else {
                fill(artwork,b.x,b.y,b.width,b.height,shell)
                text(artwork,No5Text.ui("NO COVER"),b.centerX,b.centerY,42f,color=structural,align=Paint.Align.CENTER)
            }
            artwork.restore();box(artwork,b.x,b.y,b.width,b.height,8f,null,structural,2f)
            if(bitmap==null && id!=null && s.source.quality.status!=TelemetryStatus.LIVE) {
                fill(artwork,b.x,b.bottom-44f,b.width,44f,shell)
                text(artwork,No5Text.ui("LAST KNOWN COVER"),b.centerX,b.bottom-15f,20f,color=accent,align=Paint.Align.CENTER)
            }
        }
        cached(c, "player-meta", listOf(s.media.title, s.media.artists, s.media.album,
            s.source.name, s.source.connected, s.capabilities.playPause, ctl.isSimulation)) {
            text(it,s.media.artists.joinToString(", ").ifBlank{No5Text.ui("Source unavailable")},518f,375f,35f,maxWidth=452f)
            text(it,s.media.album?:No5Text.ui("Album unavailable"),519f,415f,26f,color=secondary,maxWidth=451f)
        }
        val title=s.media.title?:No5Text.text(context,"NO TRACK")
        val titleOffset=No5Marquee.offset(titlePaint.measureText(title),458f,marqueeElapsedMs,animateTitle)
        c.save();c.clipRect(516f,237f,974f,334f)
        text(c,title,516f-titleOffset,318f,76f,font=hero)
        c.restore()
        val upcoming=upcoming(ctl.mediaMetadata,nowEpochMs)
        cached(c,"upcoming",upcoming) { queue ->
            text(queue,No5Text.text(context,upcoming.label),519f,AudioUpcomingLayout.HEADER_Y,AudioUpcomingLayout.HEADER_SIZE,color=secondary,maxWidth=451f)
            repeat(2) { index ->
                val item=upcoming.rows.getOrNull(index)
                val y=AudioUpcomingLayout.TITLE_Y+index*AudioUpcomingLayout.ROW_STEP
                text(queue,"${index+1}  "+(item?.title?:No5Text.text(context,if(item!=null)"Titelinformationen fehlen" else "Nicht beobachtet")),519f,y,AudioUpcomingLayout.TITLE_SIZE,maxWidth=451f)
                item?.artist?.let { artist -> text(queue,artist,545f,y+AudioUpcomingLayout.ARTIST_OFFSET,AudioUpcomingLayout.ARTIST_SIZE,color=secondary,maxWidth=425f) }
            }
        }
        val transportAction = if (s.media.state == MediaState.PLAYING) TransportAction.PAUSE else TransportAction.PLAY
        val preflight = when {
            !s.source.connected -> No5Text.ui("CONNECT SPOTIFY TO NO 5")
            s.source.quality.status != TelemetryStatus.LIVE -> No5Text.ui("SOURCE %s", s.source.quality.status.name)
            !s.capabilities.playPause -> No5Text.ui("PLAYBACK CONTEXT UNAVAILABLE")
            else -> No5Text.ui("READY · %s", No5Text.ui(transportAction.name))
        }
        val status = ctl.mediaSelection.takeIf { it.status == AudioMediaSelectionStatus.UNKNOWN }?.let { No5Text.ui("AUSGANG UNKLAR · KEINE WIEDERHOLUNG") }
            ?: ctl.preview?.let { No5Text.ui("PREVIEW · RELEASE TO APPLY") }
            ?: ctl.lastOutcome?.takeIf { it.status in setOf(AudioCommandStatus.REJECTED, AudioCommandStatus.FAILED, AudioCommandStatus.DROPPED) }?.message
            ?: s.feedback?.takeUnless { it == "AUDIO · GATEWAY CONNECTED" }
            ?: preflight
        cached(c, "player-status", listOf(status,s.source.name)) { text(it,status,519f,AudioUpcomingLayout.STATUS_Y,AudioUpcomingLayout.STATUS_SIZE,color=secondary,maxWidth=451f) }
        val duration=s.media.durationMs;val pos=ctl.position()
        val f=if(duration!=null&&duration>0&&pos!=null)(pos.toFloat()/duration).coerceIn(0f,1f)else 0f
        cached(c, "player-progress", listOf(pos, duration)) {
            box(it,54f,710f,916f,10f,5f,quiet,null)
            if(pos!=null) {box(it,54f,710f,916*f,10f,5f,ink,null);circle(it,54+916*f,715f,14f,accent,ink,2f)}
            text(it,audioTime(pos),54f,762f,24f)
            text(it,if(pos!=null&&duration!=null)"−"+audioTime((duration-pos).coerceAtLeast(0))else"—:—",970f,762f,24f,color=secondary,align=Paint.Align.RIGHT)
        }
        c.restore()
        val actions=listOf(TransportAction.SHUFFLE,TransportAction.PREVIOUS,if(s.media.state==MediaState.PLAYING)TransportAction.PAUSE else TransportAction.PLAY,TransportAction.NEXT)
        actions.forEachIndexed { i,a ->
            val b=g.transport[i];val enabled=ctl.canPresent(AudioCommand.Transport(a));val active=a==TransportAction.SHUFFLE&&s.media.shuffle==true
            cached(c, "transport-$i", listOf(a, enabled, active)) {
                button(it,"",b,enabled,active,i==2)
                icon(it,when(a){TransportAction.SHUFFLE->"shuffle";TransportAction.PREVIOUS->"prev";TransportAction.NEXT->"next";TransportAction.PAUSE->"pause";else->"play"},b.centerX,b.centerY-7f,if(i==2)75f else if(i==0)42f else 58f,if(!enabled)structural else if(active)surface else ink)
                if(i==0)text(it,audioShuffleCaption(context),b.centerX,b.centerY+28f,21f,color=if(enabled)ink else structural,align=Paint.Align.CENTER,maxWidth=b.width-20)
            }
        }
    }
    private fun zone(c:Canvas,ctl:AudioNativeController,control:AudioFaderControl,slot:Int) {
        c.save();c.translate(0f,-12f)
        val x=AudioNativeGeometry.faderSlotX[slot]
        text(c,ctl.displayName(control),x,207f,34f,font=hero,align=Paint.Align.CENTER,maxWidth=160f)
        c.save();c.translate(x,265f)
        path(c,"M0 -31C-16 -21 -24 3 -25 27Q0 36 25 27C24 3 16 -21 0 -31ZM0 -31V32M-17 -1H17M-22 17H22",ink,2.2f)
        circle(c,0f,7f,10f,surface,ink,3f);c.restore()
        fixedDb(c,ctl.level(control),x-73,356f,51f,25f);text(c,"dB",x+81,355f,24f,color=secondary)
        c.restore()
        fader(c,ctl,control,AudioNativeGeometry.zoneSlider(slot))
        val muted=control.muted
        val canMute=ctl.canPresent(AudioCommand.Mute(control.gatewayId,!muted))
        button(c,"",AudioNativeGeometry.zoneMute(slot),canMute,muted)
        val color=if(!canMute)structural else if(muted)surface else ink
        icon(c,if(muted)"mute"else"speaker",x,AudioNativeGeometry.zoneMute(slot).centerY-10f,32f,color)
        text(c,if(muted)No5Text.ui("MUTED")else No5Text.ui("MUTE"),x,AudioNativeGeometry.zoneMute(slot).centerY+25f,23f,color=color,align=Paint.Align.CENTER)
    }
    private fun master(c:Canvas,ctl:AudioNativeController,master:AudioFaderControl) {
        val g=AudioNativeGeometry;val muted=master.muted
        c.save();c.translate(0f,-12f)
        text(c,ctl.displayName(master).uppercase(),1793f,208f,39f,font=hero,align=Paint.Align.CENTER,maxWidth=230f)
        text(c,No5Text.ui("VOLUME"),1793f,242f,22f,color=secondary,align=Paint.Align.CENTER)
        fixedDb(c,ctl.level(master),1669f,343f,84f,39f);text(c,"dB",1900f,342f,30f,color=secondary)
        c.restore()
        fader(c,ctl,master,g.masterSlider)
        val value=ctl.level(master);val limit=master.limit
        val canDown=ctl.canPresentAdjust(master)&&value!=null&&limit!=null&&value>limit.minDb
        button(c,"",g.masterDown,canDown);icon(c,"minus",g.masterDown.centerX,g.masterDown.centerY,48f,if(canDown)ink else structural)
        val canUp=ctl.canPresentAdjust(master)&&value!=null&&limit!=null&&value<limit.maxDb
        button(c,"",g.masterUp,canUp);icon(c,"plus",g.masterUp.centerX,g.masterUp.centerY,48f,if(canUp)ink else structural)
        val canMute=ctl.canPresent(AudioCommand.Mute(master.gatewayId,!muted))
        button(c,"",g.masterMute,canMute,muted)
        val color=if(!canMute)structural else if(muted)surface else ink
        icon(c,if(muted)"mute"else"speaker",g.masterMute.x+60f,g.masterMute.centerY,40f,color)
        text(c,if(muted)No5Text.ui("UNMUTE")else No5Text.ui("MUTE"),g.masterMute.centerX+15f,g.masterMute.centerY+11f,29f,color=color,align=Paint.Align.CENTER)
    }
    private fun fader(c:Canvas,ctl:AudioNativeController,faderControl:AudioFaderControl?,b:AudioNativeRect) {
        val x=b.centerX;val top=b.y+20;val length=b.height-40
        val limit=faderControl?.limit;val v=faderControl?.let(ctl::level);val enabled=faderControl?.let(ctl::canPresentAdjust)==true
        val y=top+length*(1-AudioNativeGeometry.levelFraction(v,limit))
        box(c,x-5,top-9,10f,length+18,5f,ink,null)
        for(i in 0..6) {
            val yy=top+length*i/6
            line(c,x-52,yy,x-33,yy,structural,1.5f)
            text(c,if(limit==null)"—"else String.format(Locale.US,"%.0f",limit.maxDb-(limit.maxDb-limit.minDb)*i/6),x-63,yy+6,19f,color=secondary,align=Paint.Align.RIGHT)
        }
        val group=faderControl?.let { audioGroupMeter(ctl.state,it.gatewayId) }
        text(c,if(group!=null)"dBFS"else"N/A",x+74,top-27,16f,color=secondary,align=Paint.Align.CENTER)
        repeat(26) { i ->
            val lit=group?.let { i<((it.rmsDb+60)/60*26).coerceIn(0.0,26.0) }==true
            fill(c,x+67,top+length-10-i*(length-10)/26,14f,9f,if(lit)if(group?.clip==true)accent else meterInk else quiet)
        }
        if(group!=null) {
            val peakY=top+length*(1-((group.peakHoldDb+60)/60).coerceIn(0.0,1.0).toFloat())
            line(c,x+65,peakY,x+83,peakY,if(group.clip)accent else ink,2f)
        }
        if(v!=null) {
            box(c,x-50,y-24,100f,48f,5f,if(faderControl?.gatewayId==AudioChannel.MASTER&&enabled)accent else control,if(enabled)ink else structural,3f)
            line(c,x-31,y,x+31,y,if(enabled)if(faderControl?.gatewayId==AudioChannel.MASTER)ink else accent else structural,5f)
            line(c,x-31,y-10,x+31,y-10,structural,1f);line(c,x-31,y+10,x+31,y+10,structural,1f)
        }
        val q=ctl.state.dsp.meters?.quality
        text(c,when {!enabled->No5Text.ui("LOCKED");q?.simulated==true&&group!=null->No5Text.ui("SIM METER");group==null->No5Text.ui("METER UNAVAILABLE");else->q?.status?.name?:No5Text.ui("UNKNOWN")},x,915f,16f,color=secondary,align=Paint.Align.CENTER)
    }
    private fun fixedDb(c:Canvas,value:Double?,x:Float,y:Float,size:Float,cell:Float) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;textSize=size;typeface=hero;textAlign=Paint.Align.CENTER}
        val raw=if(value==null)"    —"else (if(value<0)"−"else"+")+String.format(Locale.US,"%.1f",abs(value)).padStart(4,' ')
        val scale=min(1f,5f/raw.length)
        p.textSize*=scale
        raw.forEachIndexed { i,ch -> c.drawText(ch.toString(),x+(i+.5f)*cell*scale,y,p) }
    }
    private fun button(c:Canvas,label:String,b:AudioNativeRect,enabled:Boolean,active:Boolean=false,primary:Boolean=false) {
        box(c,b.x,b.y,b.width,b.height,8f,if(primary&&enabled)accent else if(active)ink else control,if(enabled)ink else structural,2f)
        text(c,label,b.centerX,b.centerY+10,28f,color=if(!enabled)structural else if(active)surface else ink,align=Paint.Align.CENTER,maxWidth=b.width-16)
        if(active)fill(c,b.x+14,b.bottom-7,b.width-28,3f,accent)
    }
    private fun icon(c:Canvas,name:String,x:Float,y:Float,size:Float,color:Int) {
        val data=when(name){
            "speaker"->"M-22 -9H-12L1 -20V20L-12 9H-22ZM10 -10Q24 0 10 10M18 -18Q40 0 18 18"
            "mute"->"M-22 -9H-12L1 -20V20L-12 9H-22ZM12 -10L30 10M30 -10L12 10"
            "plus"->"M-13 0H13M0 -13V13"
            "minus"->"M-13 0H13"
            "chevron"->"M-12 -6L0 6L12 -6"
            "sliders"->"M-22 -23V23M0 -23V23M22 -23V23M-30 -8H-14M-8 11H8M14 -13H30"
            "close"->"M-12 -12L12 12M12 -12L-12 12"
            "shuffle"->"M-25 -15H-18C-4 -15 4 15 18 15H25M-25 15H-18C-4 15 4 -15 18 -15H25M18 -22L25 -15L18 -8M18 8L25 15L18 22"
            "prev"->"M13 -18L-12 0L13 18ZM-18 -18V18"
            "next"->"M-13 -18L12 0L-13 18ZM18 -18V18"
            "pause"->"M-10 -18V18M10 -18V18"
            else->"M-10 -18L18 0L-10 18Z"
        }
        c.save();c.translate(x,y);c.scale(size/64,size/64);path(c,data,color,if(name=="pause")8f else 3.4f,if(name=="play")color else null);c.restore()
    }
    /** Original geometric covers, drawn locally from a closed identifier catalog. */
    private fun proceduralArtwork(c:Canvas,b:AudioNativeRect,id:String) {
        val orange=Color.parseColor(if(night)"#9c170c"else"#ed9448")
        val pale=Color.parseColor(if(night)"#700c06"else"#d8c5a0")
        c.save();c.translate(b.x,b.y)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { shader=LinearGradient(0f,0f,306f,306f,
            if(night)intArrayOf(0xFF160000.toInt(),0xFF000000.toInt())else intArrayOf(0xFF263332.toInt(),0xFF0A1116.toInt()),null,Shader.TileMode.CLAMP) }
        c.drawRect(0f,0f,306f,306f,p)
        when(id) {
            "tidal-lines" -> {
                circle(c,227f,74f,39f,orange,orange,1f)
                repeat(10) { i ->
                    val y=142f+i*18f
                    val wave=Path().apply { moveTo(-20f,y);cubicTo(70f,y-47f,185f,y+53f,326f,y-16f) }
                    c.drawPath(wave,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(i%3==0)orange else pale;style=Paint.Style.STROKE;strokeWidth=2f })
                }
            }
            "harbor-lights" -> {
                circle(c,153f,108f,70f,if(night)0xFF320000.toInt()else 0xFF463D2E.toInt(),orange,2f)
                repeat(9) { i ->
                    val x=35f+i*30f; val high=30f+(i*37%74)
                    line(c,x,183f-high,x,184f,if(i%2==0)orange else pale,3f)
                    repeat(4) { j -> line(c,x-7+j,201f+j*19f,x+7-j,201f+j*19f,if(i%2==0)orange else pale,2f) }
                }
                line(c,22f,187f,284f,187f,pale,1f)
            }
            "orange-current" -> {
                c.save();c.translate(153f,160f);c.rotate(-28f)
                repeat(7) { i ->
                    val r=36f+i*24f
                    c.drawOval(RectF(-r,-r*.72f,r,r*.72f),Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(i%2==0)orange else pale;style=Paint.Style.STROKE;strokeWidth=if(i%2==0)8f else 2f})
                }
                c.restore()
            }
        }
        text(c,"NO 5",22f,35f,23f,font=hero,color=pale)
        text(c,when(id){"tidal-lines"->"TIDAL LINES";"harbor-lights"->"HARBOR LIGHTS";else->"ORANGE CURRENT"},22f,281f,24f,font=hero,color=orange,maxWidth=262f)
        c.restore()
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,font:Typeface=label,color:Int=ink,align:Paint.Align=Paint.Align.LEFT,maxWidth:Float?=null) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size;typeface=font;textAlign=align}
        // Audio numeric values are identifiable by their unit/clock format; titles are untouched.
        val numeric=when {
            value.endsWith(" dB") || value.contains(" dB / ") -> NumericFormats.db
            value.matches(Regex("[−-]?[0-9—]+:[0-9—]+")) -> NumericFormats.clock
            else -> null
        }
        if(numeric!=null) {
            NativeNumericText.draw(c,value,x,y,p,numeric,maxWidth?:Float.MAX_VALUE,
                if(numeric==NumericFormats.clock)NumericFormats.clockPart else numeric);return
        }
        var shown=value
        if(maxWidth!=null&&p.measureText(shown)>maxWidth) { while(shown.isNotEmpty()&&p.measureText("$shown…")>maxWidth)shown=shown.dropLast(1);shown+="…" }
        c.drawText(shown,x,y,p)
    }
    private fun fill(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int) { c.drawRect(x,y,x+w,y+h,Paint().apply{this.color=color}) }
    private fun box(c:Canvas,x:Float,y:Float,w:Float,h:Float,r:Float,fill:Int?,stroke:Int?,width:Float=2f) {
        val rect=RectF(x,y,x+w.coerceAtLeast(0f),y+h.coerceAtLeast(0f))
        fill?.let{c.drawRoundRect(rect,r,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=it})}
        stroke?.let{c.drawRoundRect(rect,r,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=it;style=Paint.Style.STROKE;strokeWidth=width})}
    }
    private fun circle(c:Canvas,x:Float,y:Float,r:Float,fill:Int,stroke:Int,width:Float) { c.drawCircle(x,y,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill});c.drawCircle(x,y,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=stroke;style=Paint.Style.STROKE;strokeWidth=width}) }
    private fun line(c:Canvas,x:Float,y:Float,x2:Float,y2:Float,color:Int,width:Float=2f) { c.drawLine(x,y,x2,y2,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=width;strokeCap=Paint.Cap.ROUND}) }
    private fun path(c:Canvas,data:String,color:Int,width:Float,fill:Int?=null) {
        val p=pathCache.getOrPut(data){PathParser().parsePathString(data).toPath().asAndroidPath()}
        fill?.let{c.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=it})}
        c.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=width;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND})
    }
}
internal fun audioDb(value:Double?):String = if(value==null)"— dB"else"${decimal(value)} dB"
internal fun decimal(value:Double):String=String.format(Locale.US,"%.1f",value)
internal fun audioTime(value:Long?):String=if(value==null)"—:—"else"${value/60_000}:${((value/1000)%60).toString().padStart(2,'0')}"

/** Only fresh adapter samples with an explicit logical mapping can drive zone meters. */
internal fun audioGroupMeter(state:AudioState,channel:AudioChannel):AudioMeter? {
    val frame=state.dsp.meters?.takeIf { it.quality.status==TelemetryStatus.LIVE }?:return null
    val indices=frame.groups[channel]?.takeIf { it.isNotEmpty() }?:return null
    if(!frame.quality.simulated && !frame.routingVerified) return null
    val values=indices.map { i -> frame.channels.getOrNull(i)?:return null }
    return AudioMeter(0,values.maxOf{it.peakDb},values.maxOf{it.rmsDb},values.any{it.clip},values.maxOf{it.peakHoldDb})
}

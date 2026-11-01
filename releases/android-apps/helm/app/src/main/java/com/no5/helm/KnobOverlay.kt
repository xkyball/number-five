package com.no5.helm

import android.content.Context
import android.graphics.*
import com.no5.domain.*

/** Optical paint only: no Compose click, pointer listener, capability or hardware binding. */
internal class KnobOverlay(context:Context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val font=context.resources.getFont(R.font.barlow_condensed_semibold)
    fun draw(c:Canvas,night:Boolean,audio:AudioNativeController,sonar:SonarNativeController?=null,depthUnit:DepthUnit=DepthUnit.METERS,showMain:Boolean=true,useSelectedAudioFader:Boolean=false) {
        draw(c,night,values(audio,sonar,depthUnit,useSelectedAudioFader),showMain)
    }
    /** Observable inputs are projected before paint so unchanged text does not redraw chrome. */
    fun values(audio:AudioNativeController,sonar:SonarNativeController?=null,depthUnit:DepthUnit=DepthUnit.METERS,useSelectedAudioFader:Boolean=false):List<List<String>> {
        val audioTarget=if(useSelectedAudioFader)audio.faderControls.singleOrNull{it.physicalIndex==audio.selectedOutput} else audio.masterControl()
        return if(sonar!=null && sonar.knobMode==SonarKnobMode.SONAR) {
            val viewport=sonar.bounds();val end=viewport.end
            val calibrated=sonar.model.viewMetersPerBin
            listOf(listOf(if(calibrated==null&&sonar.snapshot.display.startDepthM==0.0)"BIN"else depthUnit.symbol,
                if(!viewport.available)"—"else if(calibrated==null)end.toInt().toString()else sonarNumber(depthUnit.fromMeters(end*calibrated)),"R"),
                listOf("dB","G",sonarNumber(sonar.snapshot.display.gainDb,0)))
        } else listOf(listOf(if(useSelectedAudioFader)audioTarget?.let(audio::displayName)?.uppercase()?.take(8)?:"—" else "dB",audioTarget?.let(audio::level)?.let{decimal(it).removeSuffix(".0")}?:"—",
                if(audioTarget?.muted==true)"M"else"—"),
            listOf(audioTime(audio.position()),if(audio.isSimulation)"SIM"else audio.state.source.quality.status.name.take(3),
                if(audio.state.media.state==MediaState.PLAYING)"II"else"▷"))
    }
    fun draw(c:Canvas,night:Boolean,values:List<List<String>>,showMain:Boolean=true) {
        val anchors=listOf(listOf(49f to 1094.15f,49f to 1157.4f,112.25f to 1157.4f),
            listOf(1943f to 1096.9f,1881.4f to 1157.4f,1943f to 1157.4f))
        c.save();c.clipRect(0f,0f,2000f,1200f)
        KnobGeometry.knobs.forEachIndexed { i,k ->
            paint.style=Paint.Style.FILL;paint.color=Color.parseColor(if(night)"#090000"else"#f6f8f7")
            c.drawOval(RectF(k.cx-k.rx-43,k.cy-k.ry-43,k.cx+k.rx+43,k.cy+k.ry+43),paint)
            paint.color=Color.rgb(3,3,3)
            c.drawOval(RectF(k.cx-k.opticalRx,k.cy-k.opticalRy,k.cx+k.opticalRx,k.cy+k.opticalRy),paint)
            paint.color=Color.parseColor(if(night)"#ff2511"else"#f6f8f7");paint.typeface=font;paint.textAlign=Paint.Align.CENTER
            anchors[i].forEachIndexed { j,(x,y) ->
                paint.textSize=if(i==0&&j==1 || i==1&&j==2)36f else 24f
                val value=if(i==0&&!showMain)"" else values[i][j]
                // Clip long statuses to the enlarged optical ellipse; it never becomes a hit region.
                c.save();c.clipPath(Path().apply{addOval(RectF(k.cx-k.opticalRx,k.cy-k.opticalRy,k.cx+k.opticalRx,k.cy+k.opticalRy),Path.Direction.CW)})
                c.drawText(value,x,y,paint);c.restore()
            }
        };c.restore()
    }
}

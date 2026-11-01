package com.no5.helm

import com.no5.domain.*
import kotlin.math.abs

internal enum class SoundPositionTarget { LISTENER, SUB }

/** User-entered speaker-centre distances, never dimensions inferred from the boat drawing. */
internal data class SoundMeasurementDraft(val width:String,val length:String,val originalWidth:String=width,val originalLength:String=length) {
    val dirty get()=width!=originalWidth || length!=originalLength
    fun dimensions():Pair<Double,Double>? {
        val w=width.trim().replace(',','.').toDoubleOrNull()?:return null
        val l=length.trim().replace(',','.').toDoubleOrNull()?:return null
        return if(w.isFinite()&&l.isFinite()&&w in .05..50.0&&l in .05..50.0)w to l else null
    }
    companion object {
        fun from(calibration:SoundCalibration?):SoundMeasurementDraft {
            val bounds=SoundMeasuredGeometry.bounds(calibration)
            return SoundMeasurementDraft(bounds?.width?.toString().orEmpty(),bounds?.length?.toString().orEmpty())
        }
    }
}

internal data class SoundMeasuredBounds(val minX:Double,val maxX:Double,val minY:Double,val maxY:Double) {
    val width get()=maxX-minX
    val length get()=maxY-minY
}

internal object SoundMeasuredGeometry {
    fun bounds(calibration:SoundCalibration?):SoundMeasuredBounds? {
        val positions=calibration?.speakers?.filter{it.id!=SoundSpeakerId.SUB}?.mapNotNull{it.positionM}?:return null
        if(positions.size!=4)return null
        val b=SoundMeasuredBounds(positions.minOf{it.x},positions.maxOf{it.x},positions.minOf{it.y},positions.maxOf{it.y})
        return b.takeIf{it.width>=.01&&it.length>=.01}
    }
    fun rectangular(calibration:SoundCalibration):Boolean {
        val b=bounds(calibration)?:return false
        return calibration.speakers.filter{it.id!=SoundSpeakerId.SUB}.all{speaker->
            val p=speaker.positionM?:return false
            val left=speaker.id in setOf(SoundSpeakerId.FRONT_LEFT,SoundSpeakerId.REAR_LEFT)
            val front=speaker.id in setOf(SoundSpeakerId.FRONT_LEFT,SoundSpeakerId.FRONT_RIGHT)
            abs(p.x-(if(left)b.minX else b.maxX))<.00001 && abs(p.y-(if(front)b.minY else b.maxY))<.00001 && p.z==null
        }
    }
    /** Preserve origin, listener, sub and trims. Only explicit Apply replaces the four main positions. */
    fun rectangle(calibration:SoundCalibration,width:Double,length:Double):SoundCalibration {
        require(width.isFinite()&&length.isFinite()&&width in .05..50.0&&length in .05..50.0)
        val prior=bounds(calibration)
        val x=prior?.minX?:0.0
        val y=prior?.minY?:0.0
        require(x+width<=50.0 && y+length<=50.0)
        return calibration.copy(speakers=calibration.speakers.map{speaker->
            if(speaker.id==SoundSpeakerId.SUB)speaker else {
                val right=speaker.id in setOf(SoundSpeakerId.FRONT_RIGHT,SoundSpeakerId.REAR_RIGHT)
                val rear=speaker.id in setOf(SoundSpeakerId.REAR_LEFT,SoundSpeakerId.REAR_RIGHT)
                speaker.copy(positionM=SoundPosition(x+if(right)width else 0.0,y+if(rear)length else 0.0))
            }
        })
    }
}

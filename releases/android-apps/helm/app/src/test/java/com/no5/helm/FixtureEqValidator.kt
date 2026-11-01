package com.no5.helm
import com.no5.domain.*
internal object FixtureEqValidator:EqValidator {
 override fun project(previous:SoundPreset,requested:SoundPreset,pointId:String?,experimental:Boolean,policy:SoundEqValidationPolicy?)=requested
}

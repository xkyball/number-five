package com.no5.domain

import kotlin.math.abs
import kotlin.math.round

/** Published AUTO control precision, not a change to samples or the continuous estimator.
 * A held value differs from the continuous suggestion by less than 0.75 step:
 * gain 0.1875 dB (about 2.18% amplitude), gamma 0.0075, threshold 0.00075.
 * This deliberately changes effective presentation settings; it is not pixel-equivalent
 * work elimination. The current value, including an exact manual seed, is held verbatim.
 */
internal object SonarAutoDisplayPrecision {
    fun gain(candidate:Double,current:Double)=publish(candidate,current,.25,-24.0,36.0)
    fun gamma(candidate:Double,current:Double)=publish(candidate,current,.01,.2,3.0)
    fun threshold(candidate:Double,current:Double)=publish(candidate,current,.001,0.0,.95)

    private fun publish(candidate:Double,current:Double,step:Double,lower:Double,upper:Double):Double {
        require(candidate.isFinite()&&current.isFinite()&&current in lower..upper)
        val bounded=candidate.coerceIn(lower,upper)
        if(abs(bounded-current)<step*.75)return current
        return (round(bounded/step)*step).coerceIn(lower,upper)
    }
}

package com.no5.preparation.ui

/** Calibrated design pixels, never Android density-derived millimetres.
 * Optical inner fill, physical occlusion and interactive clearance are independent.
 */
internal object KnobGeometry {
    const val DISPLAY_WIDTH_MM = 237f
    const val STAGE_WIDTH = 2000f
    const val INNER_GROWTH = STAGE_WIDTH / DISPLAY_WIDTH_MM * 3f
    const val MAP_UNDERLAP = STAGE_WIDTH / DISPLAY_WIDTH_MM * 2f
    const val TOUCH_CLEARANCE = 43f
    data class Ellipse(val cx:Float,val cy:Float,val rx:Float,val ry:Float,
        val innerRx:Float,val innerRy:Float) {
        val opticalRx get()=innerRx+INNER_GROWTH
        val opticalRy get()=innerRy+INNER_GROWTH
        fun contains(x:Float,y:Float,clearance:Float=0f):Boolean =
            ((x-cx)/(rx+clearance)).let { it*it } + ((y-cy)/(ry+clearance)).let { it*it } <= 1f
        fun intersects(left:Float,top:Float,right:Float,bottom:Float,clearance:Float=TOUCH_CLEARANCE) =
            contains(cx.coerceIn(left,right),cy.coerceIn(top,bottom),clearance)
    }
    val knobs=listOf(Ellipse(49f,1157.4f,205f,209f,115f,115f),
        Ellipse(1943f,1157.4f,208f,209f,112f,110f))
    fun blocked(x:Float,y:Float)=knobs.any { it.contains(x,y,TOUCH_CLEARANCE) }
    fun safe(left:Float,top:Float,right:Float,bottom:Float)=knobs.none { it.intersects(left,top,right,bottom) }
}

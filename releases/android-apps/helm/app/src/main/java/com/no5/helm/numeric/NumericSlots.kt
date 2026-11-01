package com.no5.helm.numeric

/** Display-only geometry: never parses/reformats the numeric value or changes its precision. */
data class NumericSlots(val integers: Int, val fractions: Int = 0, val signed: Boolean = false,
    val stale: Boolean = false) {
    init { require(integers in 1..20 && fractions in 0..20) }
}
data class NumericCell(val text: String, val visible: Boolean = true, val fixed: Boolean = true) {
    val widthFactor: Float get() = when(text) { "."->.38f; "*"->.4f; "+","−","-"->.65f;else->1f }
}
object NumericCells {
    private val isoTime=Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T")
    private val token=Regex("[+−-]?[0-9]+(?:\\.[0-9]+)?\\*?|—(?:\\*)?")
    fun contains(value: String)=token.containsMatchIn(value)
    fun place(value: String, slots: NumericSlots, following: NumericSlots = slots): List<NumericCell> {
        val output=ArrayList<NumericCell>();var previous=0;var index=0
        for(match in token.findAll(value)) {
            if(match.range.first>previous) output.add(NumericCell(value.substring(previous,match.range.first),fixed=false))
            val spec=if(isoTime.containsMatchIn(value)) {
                when(index++) {0->NumericSlots(4);5->NumericSlots(2,3);else->NumericSlots(2)}
            } else if(index++==0)slots else following
            val raw=match.value.removeSuffix("*");val sign=raw.firstOrNull()?.takeIf { it in "+−-" }
            val unsigned=if(sign!=null)raw.drop(1) else raw
            val parts=unsigned.split('.',limit=2);val whole=parts[0];val fraction=parts.getOrElse(1){""}
            // Preserve unexpected long values exactly. Normal supported ranges reserve enough slots;
            // callers fit the complete overflow run inside the same fixed field, never truncate digits.
            if(spec.signed || sign!=null) output.add(NumericCell(sign?.toString()?:"+",sign!=null))
            repeat((spec.integers-whole.length).coerceAtLeast(0)) { output.add(NumericCell("0",false)) }
            whole.forEach { output.add(NumericCell(it.toString())) }
            if(spec.fractions>0 || fraction.isNotEmpty()) {
                output.add(NumericCell(".",fraction.isNotEmpty()))
                fraction.forEach { output.add(NumericCell(it.toString())) }
                repeat((spec.fractions-fraction.length).coerceAtLeast(0)) { output.add(NumericCell("0",false)) }
            }
            if(spec.stale || match.value.endsWith('*')) output.add(NumericCell("*",match.value.endsWith('*')))
            previous=match.range.last+1
        }
        if(previous<value.length)output.add(NumericCell(value.substring(previous),fixed=false))
        return output
    }
}

/** Named contracts make field capacity reviewable instead of inferring it from today's value. */
object NumericFormats {
    val speed=NumericSlots(3,1)
    val depth=NumericSlots(5,1)
    val db=NumericSlots(3,1,true)
    val axis=NumericSlots(5,1)
    val clock=NumericSlots(4)
    val clockPart=NumericSlots(2)
    fun helm(id: String): NumericSlots? = when {
        id.endsWith("-flow") -> NumericSlots(5,1,stale=true)
        id.endsWith("-trim") || id=="deck-fuelPct" || id=="deck-cog" -> NumericSlots(3,stale=true)
        id.endsWith("-coolant") -> NumericSlots(3,signed=true,stale=true)
        id.endsWith("-oil") -> NumericSlots(3,1,stale=true)
        id.endsWith("-voltage") -> NumericSlots(2,1,stale=true)
        id=="deck-airTemp" -> NumericSlots(3,1,true)
        id=="deck-wind" -> NumericSlots(3,1)
        id=="deck-humidity" -> NumericSlots(3)
        id=="deck-pressure" -> NumericSlots(4)
        id=="deck-remainingL" -> NumericSlots(7,stale=true)
        id=="deck-fuelUsed" -> NumericSlots(7,1,stale=true)
        id=="deck-tripDistance" -> NumericSlots(7,1,stale=true)
        id=="deck-totalFlow" -> NumericSlots(5,1)
        id=="deck-efficiency" -> NumericSlots(5,2)
        id=="deck-tripDuration" -> NumericSlots(7,stale=true)
        id=="deck-latitude" || id=="deck-longitude" -> NumericSlots(3)
        else -> null
    }
    fun sonar(label: String): NumericSlots? = when(label) {
        "FRAME RATE" -> NumericSlots(3,1)
        "CURRENT CONFIDENCE" -> NumericSlots(3)
        "DROPPED FRAMES","LAST PING / EPOCH MS","STREAM LATENCY" -> NumericSlots(19)
        "SAMPLE PERIOD" -> NumericSlots(7,2)
        "SOUND SPEED" -> NumericSlots(4)
        "TRANSDUCER OFFSET","KEEL OFFSET" -> NumericSlots(5,1,true)
        "RING BUFFER","DISPLAY SNAPSHOT" -> NumericSlots(4)
        "RAW MIN / MAX","RAW MEAN" -> NumericSlots(5,1)
        else -> null
    }
}

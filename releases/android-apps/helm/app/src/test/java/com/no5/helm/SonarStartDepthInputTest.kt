package com.no5.helm

import com.no5.domain.DepthUnit
import org.junit.Assert.*
import org.junit.Test

class SonarStartDepthInputTest {
    @Test fun acceptsDecimalCommaPointZeroAndMetersOrFeetWithoutRoundingSavedMeters() {
        assertEquals(.3,sonarStartDepthInput("0,30",DepthUnit.METERS)!!,1e-12)
        assertEquals(20.0,sonarStartDepthInput(" 20.00 ",DepthUnit.METERS)!!,0.0)
        assertEquals(0.0,sonarStartDepthInput("0",DepthUnit.FEET)!!,0.0)
        assertEquals(.3048,sonarStartDepthInput("1",DepthUnit.FEET)!!,1e-12)
    }
    @Test fun rejectsInvalidNegativeAndNonfiniteDrafts() {
        for(text in listOf("","-0.01","NaN","Infinity","1e999","0,3.0","—"))
            assertNull(text,sonarStartDepthInput(text,DepthUnit.METERS))
    }
}

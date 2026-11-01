package com.no5.helm.engine
import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
class ChartObjectDecoderTest {
    @Test fun allListValuesPrecisionAndPaginationCrossTheNativeBoundary() {
        val result = decodeObjectQuery("""{"total":34,"nextOffset":32,"objects":[{"id":"cell:9","sourceKey":"bound-source","featureIndex":9,"cellName":"cell","objectClass":"WRECKS","primitive":"POINT","latitude":1.5,"longitude":2.5,"distancePixels":0,"attributes":{"QUASOU":"1,6,99","VALSOU":"3.1415926535897931","INFORM":"A, B\nC","COLOUR":"3,4"}}]}""", 12)
        assertEquals(ChartObjectQueryState.READY, result.state); assertEquals(34, result.total); assertEquals(32, result.nextOffset)
        assertEquals(ChartObjectReference("bound-source", 9, 12), result.objects.single().reference)
        assertEquals("1,6,99", result.objects.single().attributes["QUASOU"])
        assertEquals("3.1415926535897931", result.objects.single().attributes["VALSOU"])
        assertEquals("A, B\nC", result.objects.single().attributes["INFORM"])
    }
    @Test fun emptyAndMalformedAreDistinct() {
        assertEquals(ChartObjectQueryState.EMPTY, decodeObjectQuery("""{"total":0,"nextOffset":null,"objects":[]}""", 4).state)
        assertThrows(IllegalArgumentException::class.java) { decodeObjectQuery("""{"total":0,"nextOffset":32,"objects":[]}""", 4) }
    }
}

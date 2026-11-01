package com.no5.helm
import org.junit.Assert.*
import org.junit.Test
class HeadingGeometryTest {
    @Test fun canonicalAnchors(){val t=HeadingGeometry.ticks(0.0);assertEquals(1000f,t.first{it.label=="N"}.x,.001f);assertEquals(875.5f,t.first{it.heading==330.0}.x,.001f);assertEquals(1123.5f,t.first{it.heading==30.0}.x,.001f)}
    @Test fun sweepAcrossAllHeadings(){for(h in 0 until 360){val t=HeadingGeometry.ticks(h.toDouble());assertTrue(t.size in 62..63);assertTrue(t.all{it.x in 662f..1338f && it.y in 139f..164f});assertEquals(t.map{it.heading}.distinct().size,t.size)}}
    @Test fun wrapAndInvalid(){assertEquals(HeadingGeometry.ticks(0.0),HeadingGeometry.ticks(360.0));assertTrue(HeadingGeometry.ticks(Double.NaN).isEmpty())}
}

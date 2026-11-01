package com.no5.helm
import org.junit.Assert.*
import org.junit.Test
class DesignViewportTest {
    @Test fun designToPointerInverseAcrossScreenSizes(){
        for((w,h) in listOf(2000f to 1200f,1280f to 800f,2560f to 1600f,1920f to 1080f,800f to 1280f)){
            val v=DesignViewport(w,h)
            for((x,y) in listOf(0f to 0f,2000f to 1200f,1796.5f to 44.5f,741f to 793f,1700f to 651f,1800f to 1129f)){
                val screen=v.toScreen(x,y);val back=v.toDesign(screen.first,screen.second)
                assertEquals(x,back.first,.001f);assertEquals(y,back.second,.001f)
            }
            assertEquals(2000f/1200f,(2000*v.scale)/(1200*v.scale),.00001f)
        }
    }
    @Test fun exactUserResolutionHasNoLetterboxing(){
        val v=DesignViewport(2000f,1200f)
        assertEquals(1f,v.scale,0f);assertEquals(0f,v.left,0f);assertEquals(0f,v.top,0f)
        assertTrue(v.contains(2000f,1200f));assertFalse(v.contains(1000f,1201f))
    }
    @Test fun letterboxDoesNotHitStage(){val v=DesignViewport(1920f,1080f);assertFalse(v.contains(0f,500f));assertTrue(v.contains(960f,540f))}
    @Test fun historicalSixteenTenDisplayGetsVerticalLetterbox(){
        val v=DesignViewport(2000f,1280f)
        assertEquals(1f,v.scale,0f);assertEquals(40f,v.top,0f)
        assertFalse(v.contains(1000f,39f));assertTrue(v.contains(1000f,40f))
        assertEquals(1098f,v.toScreen(1000f,NativeHelmLayout.FOOTER_TOP).second,0f)
    }
    @Test fun invalidGeometryRejected(){try{DesignViewport(Float.NaN,800f);fail()}catch(_:IllegalArgumentException){}}
}

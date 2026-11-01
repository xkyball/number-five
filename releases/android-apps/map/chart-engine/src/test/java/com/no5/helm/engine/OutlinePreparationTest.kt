package com.no5.helm.engine
import org.junit.Test
import org.junit.Assert.*
class OutlinePreparationTest {
    private val raw="""{"origin":[42.36,-71.04],"coast":[[[42.36,-71.04],[42.36001,-71.0395],[42.36,-71.039]]]}"""
    @Test fun cachedCoastIsDeterministicBoundedAndUsesLoadedGeometry() {
        val a=OutlinePreparation.prepare(raw);val b=OutlinePreparation.prepare(raw)
        assertArrayEquals(a.coast,b.coast,0f);assertEquals(a.fingerprint,b.fingerprint);assertEquals(4,a.coast.size);assertEquals(20.0,a.toleranceMetres,0.0)
        assertNotEquals(a.fingerprint,OutlinePreparation.prepare(raw.replace("42.36001","42.3605")).fingerprint)
    }
    @Test(expected=IllegalArgumentException::class) fun unsupportedDatelineCacheIsRejectedInsteadOfReused() {
        OutlinePreparation.prepare("""{"origin":[0,179],"coast":[[[0,179],[0,-179]]]}""")
    }
}

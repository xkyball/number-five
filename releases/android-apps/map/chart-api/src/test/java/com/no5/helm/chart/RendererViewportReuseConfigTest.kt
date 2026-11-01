package com.no5.helm.chart

import org.junit.Assert.*
import org.junit.Test

class RendererViewportReuseConfigTest {
    @Test fun ordinaryConfigurationNeverSelectsAReuseArm() {
        assertNull(RendererBenchmarkConfig().viewportReuseCandidate)
        assertFalse(RendererBenchmarkConfig().viewportReuseParity)
    }
    @Test fun parityMustBeExplicitSeparateWarmCurrentViewOffOrAllAndNeverDetailedTiming() {
        val arm = ResidentCapacityArm(ChartResidentBudget(25), ResidentCapacityCacheMode.WARM, "a".repeat(64), 3, 15_000)
        for (variant in listOf(RendererViewportReuseVariant.OFF, RendererViewportReuseVariant.ALL)) {
            val config = RendererBenchmarkConfig(1, runKind = RendererRunKind.RESIDENT_CAPACITY, capacityArm = arm,
                capacityRecipeId = "CURRENT_VIEW_REUSE", viewportReuseCandidate = variant, viewportReuseParity = true)
            assertTrue(config.viewportReuseParity)
            assertThrows(IllegalArgumentException::class.java) { config.copy(viewportReuseDetailed = true) }
            for (other in listOf(RendererViewportReuseVariant.SOUNDINGS, RendererViewportReuseVariant.SPATIAL, RendererViewportReuseVariant.GPU))
                assertThrows(IllegalArgumentException::class.java) { config.copy(viewportReuseCandidate = other) }
        }
        assertThrows(IllegalArgumentException::class.java) { RendererBenchmarkConfig(viewportReuseParity = true) }
    }
    @Test fun everyTypedDiagnosticArmRetainsAnIndependentRunIdentity() {
        for (variant in RendererViewportReuseVariant.entries) {
            val value = RendererBenchmarkConfig(3, clientRunToken = "reuse-${variant.name}", viewportReuseCandidate = variant)
            assertEquals(variant, value.viewportReuseCandidate)
            assertEquals(RendererRunKind.SCRIPTED, value.runKind)
            assertEquals(1, value.requestedWorkers)
        }
    }
    @Test fun workerAndLiveInputExperimentsCannotSilentlyChangeReuseFamilies() {
        assertThrows(IllegalArgumentException::class.java) {
            RendererBenchmarkConfig(requestedWorkers = 2, viewportReuseCandidate = RendererViewportReuseVariant.ALL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RendererBenchmarkConfig(1, runKind = RendererRunKind.LIVE_INPUT, viewportReuseCandidate = RendererViewportReuseVariant.ALL)
        }
    }
    @Test fun realCurrentViewRequiresWarmUnchangedBudgetAndExplicitTypedVariant() {
        val arm=ResidentCapacityArm(ChartResidentBudget(25),ResidentCapacityCacheMode.WARM,"a".repeat(64),3,10_000)
        val config=RendererBenchmarkConfig(1,runKind=RendererRunKind.RESIDENT_CAPACITY,capacityArm=arm,
            capacityRecipeId="CURRENT_VIEW_REUSE",viewportReuseCandidate=RendererViewportReuseVariant.SPATIAL)
        assertEquals(arm,config.capacityArm)
        assertFalse(config.viewportReuseDetailed)
        assertTrue(config.copy(viewportReuseDetailed=true).viewportReuseDetailed)
        assertThrows(IllegalArgumentException::class.java) { config.copy(viewportReuseCandidate=null) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(capacityArm=arm.copy(cacheMode=ResidentCapacityCacheMode.EMPTY_GEOMETRY)) }
    }
}

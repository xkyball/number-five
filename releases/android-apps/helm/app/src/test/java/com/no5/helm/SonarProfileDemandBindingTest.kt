package com.no5.helm

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SonarProfileDemandBindingTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun transientPauseAndRepeatedVisiblePageDoNotRetireTheProfileRead() {
        val owner = Owner()
        val calls = mutableListOf<Boolean>()
        val binding = SonarProfileDemandBinding(owner.lifecycle, calls::add)
        owner.registry.currentState = Lifecycle.State.RESUMED
        binding.showProfiles(true)
        val beforePause = calls.toList()
        repeat(3) {
            owner.registry.currentState = Lifecycle.State.STARTED // onPause/new intent
            binding.showProfiles(true) // SONAR -> DASHBOARD still owns the same read
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        assertEquals(beforePause, calls)
        binding.close()
        assertEquals(beforePause + false, calls)
        binding.close()
        assertEquals(beforePause + false, calls)
    }
    @Test fun hiddenPageAndStoppedActivityReleaseDemandAndReturnStartsItOnce() {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val calls = mutableListOf<Boolean>()
        val binding = SonarProfileDemandBinding(owner.lifecycle, calls::add)
        binding.showProfiles(true)
        binding.showProfiles(false) // HELM/AUDIO/SETTINGS have no image consumer
        binding.showProfiles(false)
        binding.showProfiles(true)
        owner.registry.currentState = Lifecycle.State.CREATED // fully hidden/onStop
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        // A new visible lease needs a current readiness report, not the value
        // retained before onStop. Production supplies the live Window state.
        assertEquals(listOf(false, true, false, true, false), calls)
        binding.showProfiles(true,prepared=true)
        assertEquals(listOf(false, true, false, true, false, true), calls)
        binding.close()
        assertEquals(listOf(false, true, false, true, false, true, false), calls)
    }
    @Test fun firstReadWaitsForPreparedWindowAndGpuReplacementDoesNotReconnectAnAdmittedRead() {
        val owner=Owner();owner.registry.currentState=Lifecycle.State.RESUMED
        val calls=mutableListOf<Boolean>();val binding=SonarProfileDemandBinding(owner.lifecycle,calls::add)
        binding.showProfiles(true,prepared=false)
        assertEquals(listOf(false),calls)
        binding.showProfiles(true,prepared=true)
        assertEquals(listOf(false,true),calls)
        binding.showProfiles(true,prepared=false) // Existing source stays alive during GPU repair.
        owner.registry.currentState=Lifecycle.State.STARTED
        owner.registry.currentState=Lifecycle.State.RESUMED
        assertEquals(listOf(false,true),calls)
        binding.showProfiles(false,prepared=false)
        binding.showProfiles(true,prepared=false)
        assertEquals(listOf(false,true,false),calls)
        binding.showProfiles(true,prepared=true)
        assertEquals(listOf(false,true,false,true),calls)
        binding.close()
    }
    @Test fun stoppedLeaseCannotReopenFromStalePreparationOnResume() {
        val owner=Owner();owner.registry.currentState=Lifecycle.State.RESUMED
        val calls=mutableListOf<Boolean>();val binding=SonarProfileDemandBinding(owner.lifecycle,calls::add)
        binding.showProfiles(true,prepared=true)
        owner.registry.currentState=Lifecycle.State.CREATED
        owner.registry.currentState=Lifecycle.State.RESUMED
        assertEquals(listOf(false,true,false),calls)
        binding.showProfiles(true,prepared=false)
        assertEquals(listOf(false,true,false),calls)
        binding.showProfiles(true,prepared=true)
        assertEquals(listOf(false,true,false,true),calls)
        binding.close()
    }
}

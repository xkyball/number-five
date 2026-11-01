package com.no5.helm

import com.no5.domain.*
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.OwnerFixtureFactory
import com.no5.helm.control.OwnerFixtureStore
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class ObservedSonarPort(private val fallback: SonarSourcePort) : SonarSourcePort by fallback {
    val observedStatus = MutableSourceFeed(SonarSourceStatus(identity))
    override val status: SourceFeed<SonarSourceStatus> get() = observedStatus
    override val sourceState = MutableSourceFeed(SonarSourceSelectionState(identity, available = true,
        selection = SonarDisplaySelection(SonarPingSource.OWN, 4)))
    override val sourceOutcomes = MutableSourceFeed<SonarSourceSelectionOutcome?>(null)
    override val diagnostics = MutableSourceFeed<SonarDiagnostics?>(SonarDiagnostics(identity, available = true))
    val visibility = mutableListOf<Boolean>()
    var writes = 0
    override fun setDiagnosticsVisible(visible: Boolean) { visibility += visible }
    override fun selectSource(request: SonarSourceSelectionRequest): SonarSourceSelectionOutcome {
        writes++
        return SonarSourceSelectionOutcome(identity.sessionId, request.requestId, 42, request.source,
            SonarFilterCommandStatus.ACCEPTED, "ACCEPTED").also(sourceOutcomes::emit)
    }
    override fun sourceResult(commandId: Long) = sourceOutcomes.value?.takeIf { it.commandId == commandId }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SonarServiceWiringTest {
    @Test fun diagnosticDemandBelongsToVisibleConsumersAndNewSourceFunctionsSurviveTheServiceLease() {
        val fixture = OwnerFixtureFactory()
        val ports = mutableListOf<ObservedSonarPort>()
        val factory = SourceSessionFactory { config, identity ->
            val raw = fixture.create(config, identity)
            object : No5SourceSession by raw {
                override val sonar = ObservedSonarPort(raw.sonar).also(ports::add)
            }
        }
        val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        val first = owner.borrowSession(); val second = owner.borrowSession(); val idle = owner.borrowSession()
        try {
            assertTrue(first.sonar.sourceState.value.available)
            assertEquals(SonarPingSource.OWN, first.sonar.sourceState.value.selection!!.source)
            assertTrue(first.sonar.diagnostics.value!!.available)
            val reported=SonarObservationReason("ORIGINAL_REFERENCE_LOST","native_reference_lost",null,1_000_000,true,true)
            var received:SonarObservationReason?=null
            first.sonar.status.subscribe {received=it.observationReason}
            ports[0].observedStatus.emit(SonarSourceStatus(ports[0].identity,connected=true,observationReason=reported))
            assertEquals(reported,first.sonar.status.value.observationReason);assertEquals(reported,received)
            ports[0].observedStatus.emit(SonarSourceStatus(ports[0].identity,connected=true))
            assertNull(first.sonar.status.value.observationReason);assertNull(received)

            first.sonar.setDiagnosticsVisible(true)
            second.sonar.setDiagnosticsVisible(true)
            idle.sonar.setDiagnosticsVisible(false)
            assertEquals(listOf(true), ports[0].visibility)
            val old = first.sonar
            var callbacks = 0
            old.sourceOutcomes.subscribe { callbacks++ }
            first.close()
            assertEquals(listOf(true), ports[0].visibility)
            val request = SonarSourceSelectionRequest(second.sonar.identity.sessionId, 3, SonarPingSource.NATIVE)
            assertEquals(42L, second.sonar.selectSource(request).commandId)
            assertEquals(3L, second.sonar.sourceResult(42)!!.requestId)
            val afterClose = callbacks
            ports[0].sourceOutcomes.emit(null)
            assertEquals(afterClose, callbacks)
            assertEquals(SonarFilterCommandStatus.REJECTED, old.selectSource(request).status)
            assertEquals(1, ports[0].writes)
            second.sonar.setDiagnosticsVisible(false)
            assertEquals(listOf(true, false), ports[0].visibility)

            second.sonar.setDiagnosticsVisible(true)
            assertTrue(owner.reload().applied)
            val replacement = owner.borrowSession()
            try {
                replacement.sonar.setDiagnosticsVisible(true)
                old.setDiagnosticsVisible(false)
                assertEquals(listOf(true), ports[1].visibility)
                assertNull(old.sourceResult(42))
                assertEquals(SonarFilterCommandStatus.REJECTED, old.selectSource(request).status)
            } finally { replacement.close() }
        } finally { first.close(); second.close(); idle.close(); owner.close() }
    }
}

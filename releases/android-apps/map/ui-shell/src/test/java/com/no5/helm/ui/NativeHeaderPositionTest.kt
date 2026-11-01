package com.no5.helm.ui

import com.no5.helm.model.*
import org.junit.Assert.*
import org.junit.Test

/** Header projection only: synthetic values never claim real GPS/device acceptance. */
class NativeHeaderPositionTest {
    private val stamp = 10_000L
    private val candidate = PositionCandidate("synthetic.gps", "synthetic.gps", "synthetic.gps",
        PositionStatus.LIVE, stamp, GeoPoint(0.0, 0.0))
    private val block = PositionSources(primarySourceId = candidate.sourceId, total = 1, sources = listOf(candidate))
    private fun position() = MapPositionState(sources = block, selectedSourceId = candidate.sourceId,
        selectedLabel = candidate.label, status = PositionStatus.LIVE,
        ownship = Ownship(candidate.position!!, null, null, null, null), observedAtEpochMs = stamp,
        generation = 8, awaitingFreshFix = false)
    private fun backend(simulated: Boolean? = true) = HelmState(ConnectionState.LIVE, Snapshot(
        serverId = "synthetic-server", revision = stamp + 4000, generatedAt = "synthetic-publication",
        state = HelmProjection(Ownship(GeoPoint(1.0, 1.0), null, null, null, null), emptyList(), emptyList(), emptyList()),
        gatewayV1 = true, sourceDetail = "synthetic-overlay · SIMULATED · Position LIVE", positionSources = block,
        gatewayTelemetryProvenance = simulated?.let { GatewayTelemetryProvenance("synthetic-overlay", it) }))

    @Test fun outsideFollowShowsAReasonWithoutFalsifyingGpsOrSource() {
        val state=position().copy(intent=PositionIntent(follow=true),outsideChartBounds=true,mode=PositionMode.FOLLOW)
        val header=nativeHeaderPosition(state,"de")
        assertEquals("Folgen pausiert am Kartenrand",header.label)
        assertEquals(candidate.sourceId,header.sourceId);assertEquals(PositionStatus.LIVE,header.status)
        assertTrue(nativeHeaderSourceStatus(backend(),state,"de").detail.startsWith("Außerhalb des aktiven Kartenbereichs"))
    }
    @Test fun originalBackendIsTheFenceAndAnOwnshipOrDetailCopyIsRejected() {
        val raw = backend()
        val context = NativeHeaderContext(7, "CHART", false, raw, "selected GPS", "READY",
            nativeHeaderPosition(position(), "de"))
        assertSame(raw, context.backend)
        assertTrue(context.matches(7, raw))
        assertFalse(context.matches(8, raw))
        assertFalse(context.matches(7, raw.copy(snapshot = raw.snapshot!!.copy(revision = stamp + 4001))))
        val selectedCopy = raw.copy(snapshot = raw.snapshot!!.copy(sourceDetail = "selected GPS",
            state = raw.snapshot!!.state.copy(ownship = position().ownship)))
        assertFalse(context.copy(backend = selectedCopy).matches(7, raw))
    }

    @Test fun selectedRealGpsIsIndependentOfGlobalSimulatedHelmAndPublicationTime() {
        val raw = backend()
        val gps = nativeHeaderPosition(position(), "de")
        assertEquals(candidate.sourceId, gps.sourceId)
        assertEquals(NativeGpsProvenance.REAL, gps.provenance)
        assertEquals(PositionStatus.LIVE, gps.status)
        assertEquals(stamp, gps.observedAtEpochMs)
        assertNotEquals(raw.snapshot!!.revision, gps.observedAtEpochMs)
        val visible = nativeHeaderSourceStatus(raw, position(), "de")
        assertTrue(visible.detail.startsWith("Position aktuell"))
        assertTrue(visible.detail.endsWith("HELM simuliert"))
        assertFalse(visible.detail.contains("Position LIVE"))
        assertTrue(raw.snapshot!!.gatewayTelemetryProvenance!!.simulated)
        assertEquals("synthetic-overlay · SIMULATED · Position LIVE", raw.snapshot!!.sourceDetail)
    }

    @Test fun combinedGpsRetainsTheOldestActualContributionTimeAndNamedScope() {
        val older = candidate.copy(observedAtEpochMs = stamp - 100)
        val newer = candidate.copy(sourceId = "synthetic.gps.2", sourceRef = "synthetic.gps.2", label = "synthetic.gps.2")
        val combination = PositionCombination(mode = "CONSENSUS", status = PositionStatus.LIVE, reason = "OK",
            observedAtEpochMs = stamp - 100, position = candidate.position,
            contributors = listOf(CombinationContributor(older.sourceId, stamp - 100), CombinationContributor(newer.sourceId, stamp)))
        val selected = position().copy(intent = PositionIntent(choice = PositionChoice.COMBINED),
            sources = block.copy(total = 2, sources = listOf(older, newer), combinationAvailable = true,
                combinationReason = "OK", combination = combination),
            selectedSourceId = "@combined", observedAtEpochMs = stamp - 100)
        val gps = nativeHeaderPosition(selected, "en")
        assertEquals("Combined GPS", gps.label)
        assertEquals("@combined", gps.sourceId)
        assertEquals(stamp - 100, gps.observedAtEpochMs)
        assertEquals(NativeGpsProvenance.REAL, gps.provenance)
    }

    @Test fun preTransportFenceOrMissingOwnshipCannotPublishLiveQuality() {
        for (selected in listOf(position().copy(awaitingFreshFix = true), position().copy(ownship = null))) {
            assertEquals(PositionStatus.UNAVAILABLE, nativeHeaderPosition(selected, "de").status)
        }
        assertEquals(PositionStatus.STALE, nativeHeaderPosition(position().copy(status = PositionStatus.STALE,
            ownship = null), "de").status)
        assertEquals(PositionStatus.INVALID, nativeHeaderPosition(position().copy(status = PositionStatus.INVALID,
            ownship = null), "de").status)
    }

    @Test fun invalidLegacyOrUnmatchedSelectionNeverInheritsRealGpsProvenance() {
        val cases = listOf(position().copy(legacy = true), position().copy(sources = null),
            position().copy(sources = block.copy(valid = false)), position().copy(sources = block.copy(simulated = true)),
            position().copy(selectedSourceId = "absent"), position().copy(observedAtEpochMs = stamp + 1))
        for (state in cases) {
            val gps = nativeHeaderPosition(state, "de")
            assertEquals(NativeGpsProvenance.UNKNOWN, gps.provenance)
            assertNull(gps.sourceId)
        }
    }

    @Test fun compactHelmNoticesAreLocalizedAndNeverGuessMissingProvenance() {
        val notices = mapOf("de" to "HELM simuliert", "en" to "HELM simulated", "af" to "HELM gesimuleer")
        for ((language, notice) in notices) {
            val text = nativeHeaderSourceStatus(backend(), position(), language).detail
            assertTrue(text.endsWith(notice))
            assertTrue("Synthetic normal source fits the existing short header", text.length < 110)
        }
        assertTrue(nativeHeaderSourceStatus(backend(null), position(), "de").detail.endsWith("HELM-Herkunft unbekannt"))
        assertTrue(nativeHeaderSourceStatus(backend(false), position(), "de").detail.endsWith("HELM real"))
        assertEquals("synthetic source outage", nativeHeaderSourceStatus(backend().copy(
            connection = ConnectionState.DEGRADED, issue = "synthetic source outage"), position(), "de").detail)
    }

    @Test fun sameBackendCannotPublishAnOldSelectionFixQualityChoiceOrPausedFollow() {
        val selected = position()
        val raw = backend()
        val context = NativeHeaderContext(7, "CHART", false, raw, "selected GPS", "READY",
            nativeHeaderPosition(selected, "de"))
        assertTrue(context.matches(7, raw, selected))
        val changed = listOf(selected.copy(generation = selected.generation + 1),
            selected.copy(selectedSourceId = "synthetic.other"), selected.copy(observedAtEpochMs = stamp + 1),
            selected.copy(status = PositionStatus.STALE), selected.copy(awaitingFreshFix = true),
            selected.copy(intent = PositionIntent(choice = PositionChoice.MANUAL, sourceId = candidate.sourceId)),
            selected.copy(sources = block.copy(sourceKind = "UNKNOWN")),
            selected.copy(sources = block.copy(simulated = true)), selected.copy(touchActive = true),
            selected.copy(followActive = true), selected.copy(mode = PositionMode.FOLLOW))
        changed.forEach { assertFalse("Old SideEffect must remain fenced", context.matches(7, raw, it)) }
        assertFalse(context.matches(8, raw, selected))
        assertFalse(context.matches(7, raw.copy(connection = ConnectionState.DEGRADED), selected))
    }
}

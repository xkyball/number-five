package com.no5.helm.ui

import com.no5.helm.model.*
import com.no5.preparation.ui.*
import org.junit.Assert.*
import org.junit.Test

class NativeScreenStateTest {
    @Test fun pendingNavigationWaitsForActualRouteSaveAndSurvivesFailure() {
        val route = RouteDocument("route", "Test", frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(1.1, 2.1)))
        val editing = NativeScreenState(shell = ShellState(destination = Destination.ROUTES),
            routes = RoutesState(draft = route, dirty = true, saveSupported = true))
        val pending = editing.navigate(Destination.SETTINGS).route(RoutesEvent.Save)
        val token = pending.routes.saveRequest!!.token
        val stale = pending.route(RoutesEvent.Saved(token + 1))
        assertEquals(Destination.ROUTES, stale.shell.destination)
        assertEquals(Destination.SETTINGS, stale.pendingDestination)
        val failed = pending.route(RoutesEvent.SaveFailed(token, "offline"))
        assertEquals(Destination.ROUTES, failed.shell.destination)
        assertEquals(Destination.SETTINGS, failed.pendingDestination)
        assertTrue(failed.routes.discardDialog)
        val saved = pending.route(RoutesEvent.Saved(token))
        assertEquals(Destination.SETTINGS, saved.shell.destination)
        assertNull(saved.pendingDestination)
        assertFalse(saved.routes.discardDialog)
    }
    private fun snapshot(ais: List<com.no5.helm.model.AisTarget> = emptyList()) = Snapshot(serverId = "boat", revision = 7, generatedAt = "2026-01-01T00:00:00Z",
        state = HelmProjection(Ownship(GeoPoint(42.36, -71.04), 0.0, 0.0, 0.0, 3.0), ais,
            listOf(Route("valid", "Valid", listOf("a", "b")), Route("missing", "Missing", listOf("a", "unknown", "b"))),
            listOf(Waypoint("a", "A", GeoPoint(42.36, -71.04)), Waypoint("b", "B", GeoPoint(42.37, -71.05))), "valid"))
    @Test fun missingRouteWaypointNeverCreatesShortcutPolyline() {
        assertEquals(listOf("external-boat-valid"), normalizeRoutes(snapshot()).map { it.id })
        assertEquals(2, overlaySnapshot(snapshot(), null, mapOf("route" to true)).route!!.points.size)
    }
    @Test fun liveTransportDoesNotInventAisTimingQualityOrApproach() {
        val target = com.no5.helm.model.AisTarget(211123456, "Boat", GeoPoint(42.36, -71.04), 0.0, 1.0, 2.0)
        val actual = normalizeAis(snapshot(listOf(target, target)), setOf("211123456")).single()
        assertEquals(AisQuality.UNKNOWN, actual.quality); assertEquals(AisFreshness.UNKNOWN, actual.freshness)
        assertNull(actual.measurementEpochMillis); assertNull(actual.measurementAgeAtReceiptMillis)
        assertNull(actual.cpaNauticalMiles); assertNull(actual.tcpaMinutes); assertNull(actual.approaching)
        assertTrue(actual.watched); assertEquals(3.88769, actual.speedKnots!!, .0001)
    }
    @Test fun invalidAisValuesRemainUnknownAndEmptySourceClearsEveryProjection() {
        val target = com.no5.helm.model.AisTarget(211123456, "", GeoPoint(Double.NaN, 999.0), 0.0, Double.NaN, -3.0)
        val actual = normalizeAis(snapshot(listOf(target)), emptySet()).single()
        assertNull(actual.position); assertNull(actual.speedKnots); assertNull(actual.courseDegrees)
        val overlays = overlaySnapshot(null, null, mapOf("route" to true, "ais" to true, "waypoints" to true))
        assertNull(overlays.ownship); assertNull(overlays.route); assertTrue(overlays.ais.isEmpty()); assertTrue(overlays.waypoints.isEmpty())
    }
    @Test fun dirtyDraftNavigationRequiresDiscardAndKeepEditingCancelsDestination() {
        val editing = NativeScreenState(shell = ShellState(destination = Destination.ROUTES)).route(RoutesEvent.New)
        val pending = editing.navigate(Destination.SETTINGS)
        assertEquals(Destination.ROUTES, pending.shell.destination); assertTrue(pending.routes.discardDialog)
        val kept = pending.route(RoutesEvent.KeepEditing)
        assertTrue(kept.routes.dirty); assertNull(kept.pendingDestination)
        val discarded = pending.route(RoutesEvent.Discard)
        assertEquals(Destination.SETTINGS, discarded.shell.destination); assertFalse(discarded.routes.dirty)
    }
    @Test fun unavailableWritesCannotBecomePendingCommands() {
        var state = NativeScreenState().routes
        state = reduceRoutes(state, RoutesEvent.New)
        state = reduceRoutes(state, RoutesEvent.AddPoint(RoutePoint(42.0, -71.0)))
        state = reduceRoutes(state, RoutesEvent.AddPoint(RoutePoint(42.1, -71.1)))
        state = reduceRoutes(state, RoutesEvent.Save)
        assertNull(state.saveRequest)
        state = state.copy(active = state.draft, stopDialog = true)
        state = reduceRoutes(state, RoutesEvent.ConfirmStop)
        assertNull(state.stopRequest); assertNull(reduceRoutes(state, RoutesEvent.ConfirmStart).startRequest)
    }
    @Test fun activeRailReopensCollapsedDirtyRouteWithoutDiscardingOrRecreatingDraft() {
        val editing = NativeScreenState(shell = ShellState(destination = Destination.ROUTES, panelExpanded = false)).route(RoutesEvent.New)
        val reopened = editing.navigate(Destination.ROUTES)
        assertTrue(reopened.shell.panelExpanded)
        assertSame(editing.routes.draft, reopened.routes.draft)
        assertTrue(reopened.routes.dirty)
        assertFalse(reopened.routes.discardDialog)
        assertNull(reopened.pendingDestination)
        assertTrue(reopened.navigate(Destination.SETTINGS).routes.discardDialog)
    }
}

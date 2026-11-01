package com.no5.preparation.ui

import org.junit.Assert.*
import org.junit.Test

class RoutesTest {
    @Test fun waypointOutsideCloseRequiresExplicitChoiceAndInvalidSaveKeepsInput() {
        var editing = reduceRoutes(ready(), RoutesEvent.EditPoint(0))
        editing = reduceRoutes(editing, RoutesEvent.Latitude("invalid"))
        val requested = reduceRoutes(editing, RoutesEvent.CancelCoordinate)
        assertTrue(requested.coordinateCloseDialog)
        assertEquals("invalid", requested.coordinate!!.latitude)
        val repeated = reduceRoutes(requested, RoutesEvent.CancelCoordinate)
        assertEquals(requested.coordinate, repeated.coordinate)
        val failed = reduceRoutes(repeated, RoutesEvent.ConfirmCoordinate)
        assertTrue(failed.coordinateCloseDialog)
        assertNotNull(failed.coordinate!!.error)
        val continued = reduceRoutes(failed, RoutesEvent.KeepCoordinate)
        assertFalse(continued.coordinateCloseDialog)
        assertEquals("invalid", continued.coordinate!!.latitude)
        assertNull(reduceRoutes(failed, RoutesEvent.DiscardCoordinate).coordinate)
    }

    @Test fun saveAndCloseRequiresMatchingAckAndErrorPreservesDraftAndGuard() {
        val changed = reduceRoutes(ready(), RoutesEvent.Rename("Edited"))
        val guarded = reduceRoutes(changed, RoutesEvent.CancelEdit)
        val pending = reduceRoutes(guarded, RoutesEvent.Save)
        val request = requireNotNull(pending.saveRequest)
        assertTrue(pending.discardDialog)
        assertEquals(request, reduceRoutes(pending, RoutesEvent.Save).saveRequest)
        assertTrue(reduceRoutes(pending, RoutesEvent.KeepEditing).discardDialog)
        assertTrue(reduceRoutes(pending, RoutesEvent.Saved(request.token + 1)).dirty)
        val failed = reduceRoutes(pending, RoutesEvent.SaveFailed(request.token, "disk failure"))
        assertTrue(failed.dirty)
        assertTrue(failed.discardDialog)
        assertEquals("Edited", failed.draft!!.name)
        val complete = reduceRoutes(pending, RoutesEvent.Saved(request.token))
        assertFalse(complete.dirty)
        assertFalse(complete.discardDialog)
        assertNull(complete.draft)
    }
    // Clearly synthetic test fixture; no geography is invented by production code.
    private val route = RouteDocument("fixture", "SIMULATION", frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(3.0, 4.0)))
    private fun ready() = RoutesState(routes = frozenListOf(route), draft = route, freshFix = true, mode = RouteNavigationMode.MANUAL_GUIDANCE)
    @Test fun previewIsReadOnlyUntilEditAndSavedRouteRemainsShown() {
        val preview = reduceRoutes(ready().copy(draft = null), RoutesEvent.Open(route.id))
        assertNull(preview.draft); assertEquals(route, preview.shown)
        assertNull(reduceRoutes(preview, RoutesEvent.AddPoint(RoutePoint(5.0, 6.0))).draft)
        val editing = reduceRoutes(preview, RoutesEvent.EditShown)
        val changed = reduceRoutes(editing, RoutesEvent.Rename("Edited"))
        val pending = reduceRoutes(changed, RoutesEvent.Save)
        val saved = reduceRoutes(pending, RoutesEvent.Saved(pending.saveRequest!!.token))
        assertNull(saved.draft); assertEquals("Edited", saved.shown!!.name)
    }
    @Test fun mapInsertionAndMoveKeepPointIdentityAndUndo() {
        val original = route.copy(points = frozenListOf(RoutePoint(1.0, 2.0, "a", "A"), RoutePoint(3.0, 4.0, "b", "B")))
        var s = ready().copy(draft = original)
        s = reduceRoutes(s, RoutesEvent.InsertBefore(1)); s = reduceRoutes(s, RoutesEvent.AddPoint(RoutePoint(2.0, 3.0)))
        assertEquals("a", s.draft!!.points[0].id); assertEquals("b", s.draft!!.points[2].id)
        s = reduceRoutes(s, RoutesEvent.PlacePoint(0, RoutePoint(0.0, 0.0)))
        assertEquals("a", s.draft!!.points[0].id); assertEquals("A", s.draft!!.points[0].name)
        s = reduceRoutes(s, RoutesEvent.Undo); assertEquals(1.0, s.draft!!.points[0].latitude, 0.0)
    }
    @Test fun saveRequiresAcknowledgementAndNeverStarts() {
        val pending = reduceRoutes(ready().copy(dirty = true), RoutesEvent.Save)
        assertTrue(pending.dirty)
        assertNull(pending.active)
        assertNull(pending.startRequest)
        assertEquals(pending, reduceRoutes(pending, RoutesEvent.Saved(999)))
        val saved = reduceRoutes(pending, RoutesEvent.Saved(pending.saveRequest!!.token))
        assertFalse(saved.dirty)
        assertNull(saved.saveRequest)
        assertNull(saved.active)
    }
    @Test fun invalidCoordinatesAreRejectedAndZeroIsLegitimate() {
        var s = ready().copy(coordinate = CoordinateEntry(latitude = "NaN", longitude = "2"))
        s = reduceRoutes(s, RoutesEvent.ConfirmCoordinate)
        assertNotNull(s.coordinate!!.error)
        assertEquals(2, s.draft!!.points.size)
        s = s.copy(coordinate = CoordinateEntry(latitude = "91", longitude = "181"))
        assertNotNull(reduceRoutes(s, RoutesEvent.ConfirmCoordinate).coordinate!!.error)
        s = s.copy(coordinate = CoordinateEntry(latitude = "0", longitude = "0"))
        s = reduceRoutes(s, RoutesEvent.ConfirmCoordinate)
        assertNull(s.coordinate)
        assertEquals(0.0, s.draft!!.points.last().latitude, 0.0)
        assertEquals(0.0, s.draft.points.last().longitude, 0.0)
        assertTrue(s.draft.points.last().id.isNotBlank())
    }
    @Test fun startRequiresFreshFixKnownModeSavedStateAndExplicitConfirmation() {
        assertNull(reduceRoutes(ready().copy(freshFix = false), RoutesEvent.ReviewStart).review)
        assertNull(reduceRoutes(ready().copy(mode = RouteNavigationMode.UNKNOWN), RoutesEvent.ReviewStart).review)
        assertNull(reduceRoutes(ready().copy(dirty = true), RoutesEvent.ReviewStart).review)
        assertNull(reduceRoutes(ready(), RoutesEvent.ConfirmStart).startRequest)
        val reviewed = reduceRoutes(ready(), RoutesEvent.ReviewStart)
        assertNotNull(reviewed.review)
        val stale = reduceRoutes(reviewed, RoutesEvent.NavigationReadiness(false, RouteNavigationMode.MANUAL_GUIDANCE))
        assertNull(reduceRoutes(stale, RoutesEvent.ConfirmStart).startRequest)
    }
    @Test fun activeSnapshotDoesNotFollowEditsAndDuplicateStartIsBlocked() {
        val reviewed = reduceRoutes(ready(), RoutesEvent.ReviewStart)
        val pending = reduceRoutes(reduceRoutes(reviewed, RoutesEvent.SelectEntryLeg(0)), RoutesEvent.ConfirmStart)
        val repeated = reduceRoutes(pending, RoutesEvent.ConfirmStart)
        assertEquals(pending.startRequest, repeated.startRequest)
        var active = reduceRoutes(pending, RoutesEvent.Started(pending.startRequest!!.token))
        assertEquals(route, active.active)
        active = reduceRoutes(active, RoutesEvent.Rename("Changed"))
        assertEquals("SIMULATION", active.active!!.name)
        assertNull(reduceRoutes(active, RoutesEvent.ReviewStart).review)
    }
    @Test fun coordinateLatitudeLabelDoesNotReuseTheEqWidthTranslation() {
        for ((language, expected) in listOf("de" to "Breite", "en" to "Latitude", "af" to "Breedte"))
            assertEquals(expected, com.no5.ui.No5Text.text(language, "route.latitude"))
        assertEquals("Width", com.no5.ui.No5Text.text("en", "Breite"))
    }
    @Test fun arbitraryReorderIsOneUndoableChangeAndPreservesEveryPoint() {
        val points = FrozenList.copyOf((0..3).map { RoutePoint(it.toDouble(), it.toDouble(), "p$it", "P$it") })
        val original = ready().copy(draft = route.copy(points = points))
        val moved = reduceRoutes(original, RoutesEvent.MovePoint(3, -3))
        assertEquals(listOf("p3", "p0", "p1", "p2"), moved.draft!!.points.map { it.id })
        assertEquals(points.toSet(), moved.draft.points.toSet())
        assertEquals(original.draft, reduceRoutes(moved, RoutesEvent.Undo).draft)
        val forward = reduceRoutes(original, RoutesEvent.MovePoint(0, 3))
        assertEquals(listOf("p1", "p2", "p3", "p0"), forward.draft!!.points.map { it.id })
        for ((index, delta) in listOf(0 to 0, 0 to -1, 3 to 1, 1 to Int.MAX_VALUE, 1 to Int.MIN_VALUE, -1 to 1, 4 to -1))
            assertEquals(original, reduceRoutes(original, RoutesEvent.MovePoint(index, delta)))
        val pending = reduceRoutes(original.copy(dirty = true), RoutesEvent.Save)
        assertEquals(pending, reduceRoutes(pending, RoutesEvent.MovePoint(3, -3)))
    }
    @Test fun removeUndoReorderAndDiscardAreGuarded() {
        val moved = reduceRoutes(ready(), RoutesEvent.MovePoint(1, -1))
        assertEquals(route.points.last(), moved.draft!!.points.first())
        val removed = reduceRoutes(moved, RoutesEvent.RemovePoint(0))
        assertEquals(1, removed.draft!!.points.size)
        val restored = reduceRoutes(removed, RoutesEvent.Undo)
        assertEquals(moved.draft.points, restored.draft!!.points)
        val cancelled = reduceRoutes(restored, RoutesEvent.CancelEdit)
        assertTrue(cancelled.discardDialog)
        assertNotNull(cancelled.draft)
        assertNull(reduceRoutes(cancelled, RoutesEvent.Discard).draft)
        assertEquals(ready(), reduceRoutes(ready(), RoutesEvent.MovePoint(0, -1)))
    }
    @Test fun persistenceFailurePreservesDraftAndPendingSaveLocksEdits() {
        val pending = reduceRoutes(ready().copy(dirty = true), RoutesEvent.Save)
        assertEquals(pending, reduceRoutes(pending, RoutesEvent.Rename("Forbidden")))
        val failed = reduceRoutes(pending, RoutesEvent.SaveFailed(pending.saveRequest!!.token, "offline"))
        assertTrue(failed.dirty)
        assertEquals(route, failed.draft)
        assertNull(failed.saveRequest)
    }

    @Test fun explicitEntryLegIsRequiredAndIncludedInStartSnapshot() {
        val reviewed = reduceRoutes(ready(), RoutesEvent.ReviewStart)
        assertNull(reviewed.reviewEntryLeg)
        assertNull(reduceRoutes(reviewed, RoutesEvent.ConfirmStart).startRequest)
        assertNull(reduceRoutes(reviewed, RoutesEvent.SelectEntryLeg(99)).reviewEntryLeg)
        val selected = reduceRoutes(reviewed, RoutesEvent.SelectEntryLeg(0))
        val pending = reduceRoutes(selected, RoutesEvent.ConfirmStart)
        assertEquals(0, pending.startRequest!!.entryLeg)
        assertEquals(RouteCoverage.UNKNOWN, pending.startRequest.coverage)
    }
    @Test fun identicalNeighborPointsBlockSaveAndStartEvenForLoadedRoutes() {
        val invalid = route.copy(points = frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(1.0, 2.0)))
        val state = ready().copy(routes = frozenListOf(invalid), draft = invalid)
        assertNull(reduceRoutes(state.copy(dirty = true), RoutesEvent.Save).saveRequest)
        assertNull(reduceRoutes(state, RoutesEvent.ReviewStart).review)
        assertNull(reduceRoutes(state.copy(review = invalid, reviewEntryLeg = 0), RoutesEvent.ConfirmStart).startRequest)
    }
    @Test fun routeRevisionAdvancesOnlyInAcknowledgedSnapshot() {
        val state = ready().copy(dirty = true)
        val pending = reduceRoutes(state, RoutesEvent.Save)
        assertEquals(0L, pending.draft!!.revision)
        assertEquals(1L, pending.saveRequest!!.snapshot.revision)
        val saved = reduceRoutes(pending, RoutesEvent.Saved(pending.saveRequest.token))
        assertNull(saved.draft) // Successful save returns to the route list.
        assertEquals(1L, saved.routes.first().revision)
    }
    @Test fun projectedPointRequiresValidActualCoordinateAndIsUndoable() {
        val invalid = reduceRoutes(ready(), RoutesEvent.AddPoint(RoutePoint(Double.NaN, 0.0)))
        assertEquals(route.points, invalid.draft!!.points)
        val added = reduceRoutes(ready(), RoutesEvent.AddPoint(RoutePoint(0.0, 0.0)))
        assertEquals(3, added.draft!!.points.size)
        assertTrue(added.dirty)
        assertEquals(route.points, reduceRoutes(added, RoutesEvent.Undo).draft!!.points)
    }
    @Test fun stopRequiresReviewConfirmationAndMatchingAcknowledgement() {
        val running = ready().copy(active = route, activeEntryLeg = 0)
        assertNull(reduceRoutes(running, RoutesEvent.ConfirmStop).stopRequest)
        val reviewed = reduceRoutes(running, RoutesEvent.ReviewStop)
        assertTrue(reviewed.stopDialog)
        val pending = reduceRoutes(reviewed, RoutesEvent.ConfirmStop)
        assertEquals(route, pending.active)
        assertNotNull(pending.stopRequest)
        assertEquals(pending.stopRequest, reduceRoutes(pending, RoutesEvent.ConfirmStop).stopRequest)
        assertEquals(route, reduceRoutes(pending, RoutesEvent.Stopped(999)).active)
        val failed = reduceRoutes(pending, RoutesEvent.StopFailed(pending.stopRequest!!.token, "offline"))
        assertEquals(route, failed.active)
        val stopped = reduceRoutes(pending, RoutesEvent.Stopped(pending.stopRequest.token))
        assertNull(stopped.active)
        assertNull(stopped.activeEntryLeg)
        assertEquals(running.routes, stopped.routes)
    }

    @Test fun waypointIdentitySurvivesRenameMoveAndUndo() {
        var added = reduceRoutes(ready(), RoutesEvent.AddPoint(RoutePoint(5.0, 6.0)))
        val stableId = added.draft!!.points.last().id
        assertTrue(stableId.isNotBlank())
        added = reduceRoutes(added, RoutesEvent.EditPoint(2))
        added = reduceRoutes(added, RoutesEvent.PointName("Harbor entry"))
        added = reduceRoutes(added, RoutesEvent.ConfirmCoordinate)
        assertEquals(stableId, added.draft!!.points.last().id)
        assertEquals("Harbor entry", added.draft.points.last().name)
        val moved = reduceRoutes(added, RoutesEvent.MovePoint(2, -1))
        assertEquals(stableId, moved.draft!!.points[1].id)
        assertEquals(added.draft, reduceRoutes(moved, RoutesEvent.Undo).draft)
        val undoneName = reduceRoutes(added, RoutesEvent.Undo)
        assertEquals("", undoneName.draft!!.points.last().name)
        assertEquals(stableId, undoneName.draft.points.last().id)
    }
    @Test fun routeRenameIsUndoableAndExternalWaypointIdsArePreserved() {
        val renamed = reduceRoutes(ready(), RoutesEvent.Rename("Changed"))
        assertEquals(route, reduceRoutes(renamed, RoutesEvent.Undo).draft)
        val external = RoutePoint(5.0, 6.0, "source-waypoint", "Entry")
        val added = reduceRoutes(ready(), RoutesEvent.AddPoint(external))
        assertEquals(external, added.draft!!.points.last())
        assertEquals(3, reduceRoutes(added, RoutesEvent.AddPoint(external)).draft!!.points.size)
    }
}

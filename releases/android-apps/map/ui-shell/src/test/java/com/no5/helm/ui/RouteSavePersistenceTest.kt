package com.no5.helm.ui

import com.no5.preparation.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RouteSavePersistenceTest {
    @Test fun confirmedRemoteWriteIsNotRepeatedWhenOnlyOfflineCopyNeedsRetry() = runBlocking {
        val initial = RouteDocument("local-fixture", "Fixture", frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(1.1, 2.1)))
        var state = reduceRoutes(RoutesState(draft = initial, dirty = true), RoutesEvent.CancelEdit)
        state = reduceRoutes(state, RoutesEvent.Save)
        var remoteWrites = 0
        var cacheWrites = 0
        val remote: suspend (RouteDocument) -> RouteDocument = { document -> remoteWrites++; document.copy(signalKToken = "fixture-revision", signalKResource = "{}") }
        val cache: suspend (RouteSaveRequest) -> Unit = { if (++cacheWrites == 1) error("fixture disk failure") }
        val firstRequest = requireNotNull(state.saveRequest)
        val first = persistRouteSave(firstRequest, remote, cache) as RouteSaveCompletion.CacheFailed
        state = reduceRoutes(state, RoutesEvent.SaveCacheFailed(firstRequest.token, first.document, first.reason))
        assertTrue(state.discardDialog)
        assertTrue(state.dirty)
        assertEquals("fixture-revision", state.draft!!.signalKToken)
        state = reduceRoutes(state, RoutesEvent.Save)
        val retry = requireNotNull(state.saveRequest)
        assertTrue(retry.remoteConfirmed)
        assertEquals(first.document.copy(offlineCopyPending = true), retry.snapshot)
        val second = persistRouteSave(retry, remote, cache) as RouteSaveCompletion.Saved
        state = reduceRoutes(state, RoutesEvent.Saved(retry.token, second.document))
        assertEquals(1, remoteWrites)
        assertEquals(2, cacheWrites)
        assertFalse(state.dirty)
        assertFalse(state.discardDialog)
        assertNull(state.draft)
    }

    @Test fun newEditAfterConfirmedRemoteSaveUsesItsNewRevisionAndToken() {
        val confirmed = RouteDocument("local-fixture", "Fixture", frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(1.1, 2.1)),
            revision = 8, signalKToken = "confirmed-revision")
        val failed = RoutesState(routes = frozenListOf(confirmed), draft = confirmed, cachePending = confirmed, dirty = true)
        val edited = reduceRoutes(failed, RoutesEvent.Rename("New edit"))
        val request = requireNotNull(reduceRoutes(edited, RoutesEvent.Save).saveRequest)
        assertFalse(request.remoteConfirmed)
        assertEquals(9L, request.snapshot.revision)
        assertEquals("confirmed-revision", request.snapshot.signalKToken)
    }
}

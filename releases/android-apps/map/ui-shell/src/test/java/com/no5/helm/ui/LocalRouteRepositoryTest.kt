package com.no5.helm.ui

import com.no5.preparation.ui.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [29])
class LocalRouteRepositoryTest {
    private val route = RouteDocument("local-11111111-1111-4111-8111-111111111111", "Test",
        frozenListOf(RoutePoint(1.1234567890123, 2.0, "p1", "A"), RoutePoint(2.0, 3.0, "p2", "B")), 1,
        source = "https://fixture.local", signalKToken = "a".repeat(64), signalKResource = "{\"name\":\"Test\"}")
    @Test fun confirmedRemoteRevisionCanRepairAnOlderCacheWithoutAllowingAStaleOverwrite() = runBlocking {
        val dir = Files.createTempDirectory("route-confirmed-repair").toFile()
        try {
            val repo = LocalRouteRepository(dir); repo.prepare(); repo.save(RouteSaveRequest(1, route))
            val latest = route.copy(name = "Confirmed remotely", revision = 4, signalKToken = "d".repeat(64))
            repo.save(RouteSaveRequest(2, latest, remoteConfirmed = true))
            val restarted = LocalRouteRepository(dir); restarted.prepare()
            assertEquals(latest, restarted.state.value.routes.single())
            try { restarted.save(RouteSaveRequest(3, route.copy(revision = 2), remoteConfirmed = true)); fail("Must reject older remote snapshot") }
            catch (_: IllegalArgumentException) { }
            assertEquals(latest, restarted.state.value.routes.single())
        } finally { dir.deleteRecursively() }
    }
    @Test fun recoveredConfirmedDraftRetriesOnlyTheOfflineCopyAfterProcessRestart() = runBlocking {
        val dir = Files.createTempDirectory("route-pending-copy").toFile()
        try {
            val repo = LocalRouteRepository(dir); repo.prepare()
            val pending = route.copy(offlineCopyPending = true)
            repo.draft(pending)
            val restarted = LocalRouteRepository(dir); restarted.prepare()
            val restored = requireNotNull(restarted.state.value.recoveryDraft)
            assertEquals(pending, restored)
            val recovering = reduceRoutes(RoutesState(), RoutesEvent.Recover(restored))
            val retry = requireNotNull(reduceRoutes(recovering, RoutesEvent.Save).saveRequest)
            assertTrue(retry.remoteConfirmed)
            var remoteWrites = 0
            val complete = persistRouteSave(retry, remoteSave = { remoteWrites++; it }, cacheSave = { restarted.save(it) })
            assertTrue(complete is RouteSaveCompletion.Saved)
            assertEquals(0, remoteWrites)
            assertEquals(route, restarted.state.value.routes.single())
            assertNull(restarted.state.value.recoveryDraft)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cacheAndDraftSurviveRestartAndDiscardIsDurable() = runBlocking {
        val dir = Files.createTempDirectory("route-test").toFile()
        try {
            val repo = LocalRouteRepository(dir); repo.prepare(); repo.save(RouteSaveRequest(1, route))
            repo.draft(route.copy(name = "Unsaved"))
            val restarted = LocalRouteRepository(dir); restarted.prepare()
            assertEquals(route, restarted.state.value.routes.single()); assertEquals("Unsaved", restarted.state.value.recoveryDraft!!.name)
            restarted.draft(null)
            val fresh = LocalRouteRepository(dir); fresh.prepare(); assertNull(fresh.state.value.recoveryDraft)
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedCommitKeepsAcknowledgedCacheAndEditableDraft() = runBlocking {
        val dir = Files.createTempDirectory("route-failure").toFile()
        try {
            val repo = LocalRouteRepository(dir); repo.prepare(); repo.save(RouteSaveRequest(1, route))
            val failing = LocalRouteRepository(dir) { error("Injected disk error") }; failing.prepare()
            try { failing.save(RouteSaveRequest(2, route.copy(name = "Changed", revision = 2))); fail("Must fail") } catch (_: IllegalStateException) {}
            val fresh = LocalRouteRepository(dir); fresh.prepare(); assertEquals(route, fresh.state.value.routes.single())
            assertEquals(route, failing.state.value.routes.single())
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptAndFutureStoresArePreserved() = runBlocking {
        val dir = Files.createTempDirectory("route-corrupt").toFile()
        try {
            val file = dir.resolve("store-v1.json"); file.writeText("{\"schema\":2,\"routes\":[]}")
            val bytes = file.readBytes(); val repo = LocalRouteRepository(dir); repo.prepare()
            assertFalse(repo.state.value.ready); assertNotNull(repo.state.value.issue)
            try { repo.draft(null); fail("Must not replace unreadable state") } catch (_: IllegalStateException) {}
            assertArrayEquals(bytes, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun revisionsAndRetryCannotDuplicateRoute() = runBlocking {
        val dir = Files.createTempDirectory("route-retry").toFile()
        try {
            val repo = LocalRouteRepository(dir); repo.prepare(); repo.save(RouteSaveRequest(1, route)); repo.save(RouteSaveRequest(1, route))
            assertEquals(1, repo.state.value.routes.size)
            try { repo.save(RouteSaveRequest(2, route.copy(name = "Stale"))); fail("Must reject stale revision") } catch (_: IllegalArgumentException) {}
            assertEquals(route, repo.state.value.routes.single())
        } finally { dir.deleteRecursively() }
    }
    @Test fun identicalRouteUuidOnAnotherGatewayKeepsBothOfflineCopies() = runBlocking {
        val dir = Files.createTempDirectory("route-origins").toFile()
        try {
            val other = route.copy(source = "https://other-fixture.local", name = "Other source")
            val repo = LocalRouteRepository(dir); repo.prepare()
            repo.cache(route.source!!, listOf(route)); repo.cache(other.source!!, listOf(other))
            val restarted = LocalRouteRepository(dir); restarted.prepare()
            assertTrue(restarted.state.value.ready); assertEquals(2, restarted.state.value.routes.size)
            restarted.delete(route.id, route.revision, route.source)
            val fresh = LocalRouteRepository(dir); fresh.prepare(); assertEquals(other, fresh.state.value.routes.single())
        } finally { dir.deleteRecursively() }
    }
}

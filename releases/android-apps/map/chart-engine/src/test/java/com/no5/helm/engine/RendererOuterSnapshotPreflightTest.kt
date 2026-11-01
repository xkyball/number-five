package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real source-pin/journal IO and the production preflight. No JNI/FULL snapshot is claimed. */
class RendererOuterSnapshotPreflightTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val source: File, val journal: File, val recovery: BenchmarkRecoveryStore,
        val binding: ChartResidentBinding)

    private fun fixture(): Fixture {
        val directory = temporary.newFolder()
        val file = File(directory, "US5TEST0.000").apply { writeText("synthetic immutable source bytes") }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val raw = ChartResidentSource("noaa-s57", "US5TEST0", "", 1, 0, 0,
            listOf(ChartResidentFile(file.absolutePath, hash)))
        val source = raw.copy(sourceKey = BenchmarkRecoveryStore.residentPinIdentity(raw))
        val catalog = ChartResidentCatalog("a".repeat(64), listOf(source), ChartResidentBudget(3))
        val camera = ChartViewport(40.0, -70.0, .01, 0.0, 1000, 600)
        val activation = residentActivationSnapshot(ChartResidentActivation("synthetic", catalog, camera,
            ChartNavigationBounds(1, listOf(ChartCellExtent(source.name, 39.0, 41.0, -71.0, -69.0)))))
        val status = ChartResidencyDecoder.decode(javaClass.getResource("/native-residency-status-v1.json")!!.readText())
            .copy(enabled = true, complete = true, catalogGeneration = catalog.generation, policyKey = "policy",
                loading = 0, budget = catalog.budget)
        val journal = File(directory, "recovery.json")
        return Fixture(file, journal, BenchmarkRecoveryStore(journal),
            ChartResidentBinding(activation, 7, 11, 3, camera, status, 4.0))
    }

    private fun saved() = BenchmarkUserState(UUID.randomUUID().toString(), null, emptyList(),
        ChartViewport(), ChartPresentation(), S52Settings(), ChartOverlays())

    private suspend fun rejected(key: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $key") }
        catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(key)) }
    }

    @Test fun exactSourceBindingAndAbsentJournalReturnTheCurrentBinding() = runBlocking {
        val f = fixture()
        val current = f.binding.copy(status = f.binding.status.copy(message = "new diagnostic text", retains = 8))
        assertSame(current, rendererOuterSnapshotPreflight(f.binding, f.recovery, { current }))
        assertFalse(f.journal.exists())
    }

    @Test fun sourceHashMismatchFailsBeforeAnyCurrentCapture() = runBlocking {
        val f = fixture()
        f.source.appendText("changed")
        var captures = 0
        rejected("Kartenquelle") { rendererOuterSnapshotPreflight(f.binding, f.recovery, { captures++; f.binding }) }
        assertEquals(0, captures)
        assertFalse(f.journal.exists())
    }

    @Test fun foreignRecoveryJournalAlreadyPresentIsPreservedAndRefused() = runBlocking {
        val f = fixture()
        f.recovery.save(saved())
        val bytes = f.journal.readBytes()
        var captures = 0
        rejected("Existing recovery journal") { rendererOuterSnapshotPreflight(f.binding, f.recovery, { captures++; f.binding }) }
        assertEquals(0, captures)
        assertArrayEquals(bytes, f.journal.readBytes())
    }

    @Test fun foreignRecoveryJournalCreatedDuringSourceVerificationIsAlsoPreservedAndRefused() = runBlocking {
        val f = fixture()
        var bytes = ByteArray(0)
        var captures = 0
        rejected("Existing recovery journal") {
            rendererOuterSnapshotPreflight(f.binding, f.recovery, { captures++; f.binding }, { activation ->
                verifyResidentSourcePins(activation)
                f.recovery.save(saved())
                bytes = f.journal.readBytes()
            })
        }
        assertEquals(0, captures)
        assertArrayEquals(bytes, f.journal.readBytes())
    }

    @Test fun sourceCatalogSurfaceContentBudgetPolicyAndCameraChangesCannotReuseValidatedPins() = runBlocking {
        val f = fixture()
        val b = f.binding
        val changed = listOf(
            b.copy(sourceGeneration = b.sourceGeneration + 1),
            b.copy(contentGeneration = b.contentGeneration + 1),
            b.copy(surfaceGeneration = b.surfaceGeneration + 1),
            b.copy(activation = b.activation.copy(catalog = b.activation.catalog.copy(generation = "b".repeat(64)))),
            b.copy(activation = b.activation.copy(catalog = b.activation.catalog.copy(budget = ChartResidentBudget(4)))),
            b.copy(activation = b.activation.copy(catalog = b.activation.catalog.copy(sources = emptyList()))),
            b.copy(producerViewport = b.producerViewport.copy(metresToPixels = .02)),
            b.copy(status = b.status.copy(policyKey = "new-policy")),
            b.copy(status = b.status.copy(budget = null)),
            b.copy(status = b.status.copy(complete = false)),
            b.copy(status = b.status.copy(loading = 1)),
        )
        for (current in changed) rejected("VALIDATED_BINDING") {
            rendererOuterSnapshotPreflight(b, f.recovery, { current })
        }
    }

    @Test fun ioSuspendsWithoutBlockingOwnerAndItsNormalOverlayUpdateIsCapturedOnlyAfterwards() = runBlocking {
        val f = fixture()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val original = ChartOverlays(GeoProjection(40.0, -70.0))
        val latest = ChartOverlays(GeoProjection(40.0001, -70.0001))
        var overlays = original
        var captured: ChartOverlays? = null
        var ownerThread = -1L
        try {
            supervisorScope {
                val pending = async(owner) {
                    ownerThread = Thread.currentThread().id
                    rendererOuterSnapshotPreflight(f.binding, f.recovery, {
                        assertEquals(ownerThread, Thread.currentThread().id)
                        captured = overlays
                        f.binding
                    }, { activation ->
                        assertNotEquals(ownerThread, Thread.currentThread().id)
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Test preflight was not released" }
                        verifyResidentSourcePins(activation)
                    })
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    withTimeout(5_000) { withContext(owner) { overlays = latest } }
                    assertNull(captured)
                    release.countDown()
                    assertSame(f.binding, pending.await())
                    assertEquals(latest, captured)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        } finally { owner.close() }
    }

    @Test fun realAuthorityChangeWhileIoIsSuspendedIsRejectedOnTheOwnerBeforeReturningBinding() = runBlocking {
        val f = fixture()
        val owner = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var current = f.binding
        var bindingReturned = false
        try {
            supervisorScope {
                val pending = async(owner) {
                    rendererOuterSnapshotPreflight(f.binding, f.recovery, { current }, { activation ->
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Test preflight was not released" }
                        verifyResidentSourcePins(activation)
                    }).also { bindingReturned = true }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    withContext(owner) { current = f.binding.copy(surfaceGeneration = f.binding.surfaceGeneration + 1) }
                    release.countDown()
                    rejected("VALIDATED_BINDING") { pending.await() }
                    assertFalse(bindingReturned)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        } finally { owner.close() }
    }
}

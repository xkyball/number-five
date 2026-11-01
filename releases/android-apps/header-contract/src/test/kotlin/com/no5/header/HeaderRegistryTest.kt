package com.no5.header

import org.junit.Assert.*
import org.junit.Test

/** Deterministic JVM fixtures. No process, Android focus, device or UI acceptance is claimed. */
class HeaderRegistryTest {
    private val producer = AuthenticatedHeaderProducer(HeaderApps.HELM, "fixture-process-a")
    private val scope = HeaderScope(HeaderApps.HELM, producer.producerSession, 7, HeaderPage.AUDIO, 2, "fixture-source-a")
    private fun snapshot(sequence: Long = 0) = HeaderSnapshotV1(
        HeaderPublication(scope.packageName, scope.producerSession, scope.foregroundEpoch,
            scope.pageGeneration, scope.sourceSessionId, sequence, 1_000, 2_500),
        scope.pageId, HeaderTheme.DAY, HeaderProfile.MARINE_SAFETY, HeaderContext("AUDIO"),
        HeaderSource("SOURCE TELEMETRY", "fixture", "fixture-sensor", quality = HeaderQuality.LIVE,
            provenance = HeaderProvenance.REAL), 20_000,
        HeaderExtras(portRpm = HeaderMeasurement(0.0, HeaderUnit.RPM, "fixture-sensor", 20_000,
            HeaderQuality.LIVE, HeaderProvenance.REAL)),
    )
    private fun registry() = HeaderRegistry().also { it.producerStarted(producer); it.onForegroundChanged(scope) }
    private fun assertBase(registry: HeaderRegistry, reason: HeaderClearReason, elapsed: Long = 1_100) {
        val current = registry.current(elapsed, 20_000)
        assertNull(current.snapshot)
        assertTrue(current.extras.isEmpty())
        assertEquals(HeaderProfile.BASE_SYSTEM, current.profile)
        assertEquals(reason, current.reason)
        assertEquals("NO 5", current.brand)
        assertEquals(3, current.navigation(SystemNavigationAction.LOGO_HOME))
        assertEquals(3, current.navigation(SystemNavigationAction.HOME))
        assertEquals(4, current.navigation(SystemNavigationAction.BACK))
        assertEquals(187, current.navigation(SystemNavigationAction.RECENTS))
    }

    @Test fun missingPublicationKeepsOriginalSystemActionsAndClearsExtras() {
        assertBase(registry(), HeaderClearReason.NO_PUBLICATION)
    }

    @Test fun exactAllowedAppsAndPagesOnly() {
        assertTrue(HeaderApps.allowed("com.no5.home")); assertTrue(HeaderApps.allowed("com.no5.map"))
        assertTrue(HeaderApps.allowed("com.no5.helm"))
        for (p in listOf("com.no5.other", "com.no5.helm.extra", "org.lumina.fake", "Com.no5.helm")) assertFalse(HeaderApps.allowed(p))
        assertNull(HeaderApps.profile(HeaderApps.MAP, HeaderPage.AUDIO))
        assertNull(HeaderApps.profile(HeaderApps.HOME, HeaderPage.HELM))
    }

    @Test fun currentPublicationRetainsMeasuredZeroAndDoesNotMoveSourceTime() {
        val r = registry(); assertTrue(r.publish(producer, snapshot(), 1_000).accepted)
        val current = r.current(1_100, 20_001).snapshot!!
        assertEquals(0.0, current.extras.portRpm!!.value!!, 0.0)
        assertEquals(20_000L, current.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(1_000L, current.publication.publishedAtElapsedMs)
        assertEquals(20_000L, current.sourceObservedAtEpochMs)
    }

    @Test fun leaseExpiryAtBoundaryClearsWholeAppContext() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        assertNotNull(r.current(2_499, 20_000).snapshot)
        assertBase(r, HeaderClearReason.EXPIRED, 2_500)
        assertBase(r, HeaderClearReason.EXPIRED, 2_501)
    }

    @Test fun producerDeathAndRetiredSessionCannotResurrectCachedData() {
        val r = registry(); r.publish(producer, snapshot(), 1_000); r.producerDied(producer)
        assertBase(r, HeaderClearReason.PRODUCER_DEAD)
        assertFalse(r.producerStarted(producer))
        assertFalse(r.publish(producer, snapshot(1), 1_100).accepted)
        assertBase(r, HeaderClearReason.PRODUCER_DEAD)
    }

    @Test fun focusMovesToForeignAppWithoutKeepingLastNo5Extras() {
        val r = registry(); r.publish(producer, snapshot(), 1_000); r.onForegroundChanged(null)
        assertBase(r, HeaderClearReason.NO_FOREGROUND_SCOPE)
        assertFalse(r.publish(producer, snapshot(1), 1_100).accepted)
        assertBase(r, HeaderClearReason.NO_FOREGROUND_SCOPE)
    }

    @Test fun wrongForegroundGenerationFromCurrentOwnerFailsClosed() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        val wrong = snapshot(1).let { it.copy(publication = it.publication.copy(foregroundEpoch = 6)) }
        assertEquals(HeaderClearReason.WRONG_FOREGROUND, r.publish(producer, wrong, 1_100).reason)
        assertBase(r, HeaderClearReason.WRONG_FOREGROUND)
    }

    @Test fun internalPageSwitchClearsOldHeaderBeforeNewPublish() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        r.onForegroundChanged(scope.copy(pageId = HeaderPage.SONAR, pageGeneration = 3))
        assertBase(r, HeaderClearReason.NO_PUBLICATION)
        assertEquals(HeaderClearReason.WRONG_PAGE, r.publish(producer, snapshot(1), 1_100).reason)
        assertBase(r, HeaderClearReason.WRONG_PAGE)
        val new = snapshot(2).let { it.copy(pageId = HeaderPage.SONAR,
            publication = it.publication.copy(pageGeneration = 3), context = HeaderContext("SONAR")) }
        assertTrue(r.publish(producer, new, 1_100).accepted)
        assertEquals("SONAR", r.current(1_100, 20_000).snapshot!!.context.title)
    }

    @Test fun sourceSwitchRejectsLateSourceCallback() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        r.onForegroundChanged(scope.copy(sourceSessionId = "fixture-source-b", pageGeneration = 3))
        val oldSource = snapshot(1).let { it.copy(publication = it.publication.copy(pageGeneration = 3)) }
        assertEquals(HeaderClearReason.WRONG_SOURCE_SESSION, r.publish(producer, oldSource, 1_100).reason)
        assertBase(r, HeaderClearReason.WRONG_SOURCE_SESSION)
    }

    @Test fun duplicateOrOlderSequenceCannotBringBackAcceptedValues() {
        val r = registry(); r.publish(producer, snapshot(4), 1_000)
        assertEquals(HeaderClearReason.OUT_OF_ORDER, r.publish(producer, snapshot(4), 1_100).reason)
        assertBase(r, HeaderClearReason.OUT_OF_ORDER)
        assertFalse(r.publish(producer, snapshot(3), 1_100).accepted)
        assertTrue(r.publish(producer, snapshot(5), 1_100).accepted)
    }

    @Test fun backgroundOrSpoofedCallerCannotReplaceOrEraseValidForegroundData() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        val background = AuthenticatedHeaderProducer(HeaderApps.MAP, "fixture-map")
        r.producerStarted(background)
        assertEquals(HeaderClearReason.CALLER_MISMATCH, r.publish(background, snapshot(1), 1_100).reason)
        assertNotNull(r.current(1_100, 20_000).snapshot)
    }

    @Test fun repeatedIdenticalScopeDoesNotEraseValidHeader() {
        val r = registry(); r.publish(producer, snapshot(), 1_000); r.onForegroundChanged(scope)
        assertNotNull(r.current(1_100, 20_000).snapshot)
    }

    @Test fun lateScopeEventCannotReopenPreviousPageOrSource() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        r.onForegroundChanged(scope.copy(pageId = HeaderPage.SONAR, pageGeneration = 3))
        r.onForegroundChanged(scope)
        assertBase(r, HeaderClearReason.WRONG_PAGE)
        assertFalse(r.publish(producer, snapshot(1), 1_100).accepted)
    }

    @Test fun reentryAfterNoForegroundRequiresNewEpochOrPageGeneration() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        r.onForegroundChanged(null); r.onForegroundChanged(scope)
        assertBase(r, HeaderClearReason.WRONG_PAGE)
        r.onForegroundChanged(scope.copy(foregroundEpoch = 8))
        val fresh = snapshot().let { it.copy(publication = it.publication.copy(foregroundEpoch = 8)) }
        assertTrue(r.publish(producer, fresh, 1_100).accepted)
    }

    @Test fun producerRestartRequiresFreshScopeAndNewSession() {
        val r = registry(); r.publish(producer, snapshot(), 1_000)
        val fresh = producer.copy(producerSession = "fixture-process-b")
        assertTrue(r.producerStarted(fresh)); assertBase(r, HeaderClearReason.PRODUCER_DEAD)
        assertFalse(r.producerStarted(producer))
        r.onForegroundChanged(scope.copy(producerSession = fresh.producerSession))
        val next = snapshot().let { it.copy(publication = it.publication.copy(producerSession = fresh.producerSession)) }
        assertTrue(r.publish(fresh, next, 1_100).accepted)
    }

    @Test fun malformedEnvelopeAndProfileCannotKeepOldExtras() {
        val malformed = listOf(
            snapshot(1).copy(schemaVersion = 2),
            snapshot(1).let { it.copy(publication = it.publication.copy(publishedAtElapsedMs = 1_101)) },
            snapshot(1).let { it.copy(publication = it.publication.copy(expiresAtElapsedMs = 10_000)) },
            snapshot(1).let { it.copy(publication = it.publication.copy(sequence = -1)) },
            snapshot(1).copy(profile = HeaderProfile.BRAND_SOURCE),
        )
        for (incoming in malformed) {
            val r = registry(); r.publish(producer, snapshot(), 1_000)
            assertFalse(r.publish(producer, incoming, 1_100).accepted)
            assertNull(r.current(1_100, 20_000).snapshot)
        }
    }

    @Test fun freshPublicationDoesNotRefreshOldField() {
        val r = registry()
        val stale = snapshot().copy(extras = HeaderExtras(portRpm = snapshot().extras.portRpm!!.copy(sourceObservedAtEpochMs = 1_000)))
        assertTrue(r.publish(producer, stale, 1_000).accepted)
        val first = r.current(1_100, 40_000).snapshot!!.extras.portRpm!!
        assertNull(first.value); assertEquals(HeaderQuality.LOST, first.quality)
        val republished = stale.copy(publication = stale.publication.copy(sequence = 1, publishedAtElapsedMs = 1_100, expiresAtElapsedMs = 2_600))
        r.publish(producer, republished, 1_100)
        val again = r.current(1_200, 40_000).snapshot!!.extras.portRpm!!
        assertNull(again.value); assertEquals(1_000L, again.sourceObservedAtEpochMs)
    }
}

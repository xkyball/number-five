package com.no5.helm

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.domain.OperatingMode
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.HelmSourceRegistry
import com.no5.helm.diagnostics.GatewayDiagnosticError
import com.no5.helm.diagnostics.GatewayDiagnosticService
import com.no5.helm.network.GatewayProbeErrors
import com.no5.helm.network.GatewayProbeResult
import com.no5.helm.network.NetworkGatewayReadOnlyProbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Explicit real-Lumina acceptance. Existing HOME credentials only; no taps or audio commands. */
class SourceReachabilityAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext

    private fun <T> onMain(read: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(read()) }
        return value.get()
    }

    @Test fun realAudioEndpointAndAutomaticSourceAdmissionWithoutManualRefresh() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real Lumina test requires explicit opt-in", args.getString("no5LuminaReachability") == "true")
        assertEquals("Executing host must identify the real serial first", "3032506179901306", args.getString("no5TargetSerial"))
        assertEquals("K706", Build.MODEL)
        assertEquals(29, Build.VERSION.SDK_INT)
        assertFalse("An emulator must not use the real Pi", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"))
        assertEquals("com.no5.helm", context.packageName)
        assertEquals("Probe must run as the target application's UID", context.applicationInfo.uid, Process.myUid())
        val expectedOrigin = "https://lysmarine.local:9443"
        val evidence = JSONObject().put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("commandsSubmitted", 0).put("manualRefreshCalls", 0).put("settingsChanged", false)
        val observations = JSONArray()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = SystemClock.uptimeMillis()
        var accepted = false
        try {
            // Observe the actual foreground-service owner. Never construct a second factory.
            val owner: HelmSourceOwner = onMain { checkNotNull(HelmSourceRegistry.peek(context)) }
            var lastReason = "NOT_OBSERVED"
            var lastPhase = "NOT_OBSERVED"
            var lastAttempt = -1L
            val deadline = started + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                val observation = onMain {
                    assertSame("The same service owner must own every attempt", owner, HelmSourceRegistry.peek(context))
                    assertFalse("Source owner was disposed", owner.isDisposed)
                    val recovery = owner.recoveryStatus
                    val audio = owner.borrowAudio()
                    try {
                        val audioState = audio.state.value
                        JSONObject().put("elapsedMs", SystemClock.uptimeMillis() - started)
                            .put("phase", recovery.phase).put("reason", recovery.reason).put("attempt", recovery.attempt)
                            .put("readerStartedUptimeMs", recovery.startedUptimeMs)
                            .put("readerFinishedUptimeMs", recovery.finishedUptimeMs)
                            .put("readerDeadlineUptimeMs", recovery.deadlineUptimeMs)
                            .put("sourceAccepted", recovery.sourceAccepted).put("refreshInFlight", recovery.refreshInFlight)
                            .put("nextRetryUptimeMs", recovery.nextRetryUptimeMs ?: JSONObject.NULL)
                            .put("sessionGeneration", owner.sessionGeneration)
                            .put("connectionEnabled", owner.config.connectionEnabled)
                            .put("homeIdentityAccepted", owner.homeIdentity != null && owner.homeIdentity != "unavailable")
                            .put("originMatches", owner.config.endpoint == expectedOrigin)
                            .put("settingsWarningPresent", owner.settingsWarning != null)
                            .put("audioMode", audioState.mode.name).put("mediaState", audioState.media.state.name)
                            .put("titlePresent", !audioState.media.title.isNullOrBlank())
                    } finally { audio.close() }
                }
                val reason = observation.getString("reason")
                val phase = observation.getString("phase")
                val attempt = observation.getLong("attempt")
                if (reason != lastReason || phase != lastPhase || attempt != lastAttempt) {
                    observations.put(observation)
                    lastReason = reason; lastPhase = phase; lastAttempt = attempt
                }
                accepted = observation.getBoolean("sourceAccepted") && observation.getBoolean("connectionEnabled") &&
                    observation.getBoolean("homeIdentityAccepted") && observation.getBoolean("originMatches") &&
                    !observation.getBoolean("settingsWarningPresent") && observation.getString("audioMode") == OperatingMode.LIVE.name
                if (accepted) { evidence.put("acceptedSession", observation); break }
                SystemClock.sleep(100)
            }
            evidence.put("automaticSourceAccepted", accepted).put("observations", observations)

            // A separate direct read distinguishes a reachable gateway from a rejected source.
            // This is the same existing app-UID, origin-bound TLS/auth probe as "Verbindung testen".
            val probeStarted = SystemClock.uptimeMillis()
            val probePhase = AtomicReference("HOME_PROFILE")
            val worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "no5-acceptance-read-only-probe").apply { isDaemon = true }
            }
            val future = worker.submit(Callable {
                try {
                    homeSecurityProfiles(context).load(expectedOrigin)?.use { profile ->
                        probePhase.set("AUDIO_GET")
                        NetworkGatewayReadOnlyProbe(HomeGatewayEndpointResolver(context))
                            .check(GatewayDiagnosticService.AUDIO, expectedOrigin, profile)
                    } ?: GatewayProbeResult(GatewayDiagnosticError.PROFILE_MISSING)
                } catch (failure: Exception) { GatewayProbeResult(GatewayProbeErrors.classify(failure)) }
            })
            val probe = try { future.get(8, TimeUnit.SECONDS) }
                catch (_: TimeoutException) { future.cancel(true); GatewayProbeResult(GatewayDiagnosticError.TIMEOUT) }
                catch (failure: Exception) { GatewayProbeResult(GatewayProbeErrors.classify(failure.cause ?: failure)) }
                finally { worker.shutdownNow() }
            evidence.put("audioReachability", JSONObject().put("successful", probe.successful)
                .put("httpStatus", probe.httpStatus ?: JSONObject.NULL).put("reason", probe.error.name)
                .put("phase", probePhase.get()).put("durationMs", SystemClock.uptimeMillis() - probeStarted))
            evidence.put("probeWorkerTerminated", worker.awaitTermination(100, TimeUnit.MILLISECONDS))
            assertTrue("Actual Lumina Audio GET failed at ${probe.error.name}; HTTP ${probe.httpStatus}", probe.successful)
            assertEquals(200, probe.httpStatus)
            assertTrue("Gateway reachable but source was not automatically admitted: $lastPhase/$lastReason", accepted)

            val acceptedGeneration = onMain { owner.sessionGeneration }
            SystemClock.sleep(2_000)
            onMain {
                assertSame(owner, HelmSourceRegistry.peek(context))
                assertEquals("A healthy source must not be destroyed by the retry timer", acceptedGeneration, owner.sessionGeneration)
                assertTrue(owner.recoveryStatus.sourceAccepted)
            }
            evidence.put("healthySessionPreserved", true).put("passed", true)
        } finally {
            // Metadata only: never export key/profile/certificate bytes, titles or raw exception text.
            val dir = File(context.getExternalFilesDir(null), "source-reachability").apply { mkdirs() }
            File(dir, "acceptance.json").writeText(evidence.put("elapsedMs", SystemClock.uptimeMillis() - started).toString(2))
            scenario.close()
        }
    }
}

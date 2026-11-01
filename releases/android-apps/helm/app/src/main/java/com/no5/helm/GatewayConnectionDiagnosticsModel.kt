package com.no5.helm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.no5.domain.SourceApplyResult
import com.no5.helm.diagnostics.GatewayDiagnosticError
import com.no5.helm.diagnostics.GatewayDiagnosticLog
import com.no5.helm.diagnostics.GatewayDiagnosticNote
import com.no5.helm.diagnostics.GatewayDiagnosticOutcome
import com.no5.helm.diagnostics.GatewayDiagnosticPhase
import com.no5.helm.diagnostics.GatewayDiagnosticService
import com.no5.helm.diagnostics.GatewayDiagnosticSnapshot
import com.no5.helm.diagnostics.GatewayDiagnosticFilter
import com.no5.helm.diagnostics.GatewayDiagnosticExportFormat
import com.no5.helm.network.GatewayConnectionTester
import com.no5.helm.network.AndroidGatewayEndpointResolver
import com.no5.helm.security.AndroidGatewaySecurityStore
import com.no5.helm.security.AndroidGatewayTrustStore
import com.no5.helm.security.GatewayProfileState
import com.no5.helm.security.GatewayDraftOriginState
import com.no5.helm.security.GatewaySecurityInspection
import com.no5.helm.security.GatewaySecurityInspector
import com.no5.helm.security.StoredGatewaySecurityProfileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-memory diagnostics only; never persisted and never contains secret or payload data. */
class GatewayConnectionDiagnosticsModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val keys = AndroidGatewaySecurityStore(appContext)
    private val trust = AndroidGatewayTrustStore(appContext)
    private val home = com.no5.gateway.HomeGatewayClient(appContext)
    private val profiles = homeSecurityProfiles(appContext)
    private val inspector = GatewaySecurityInspector(keys, trust, profiles)
    private val endpointResolver = HomeGatewayEndpointResolver(appContext)
    private val tester = GatewayConnectionTester(profiles, endpointResolver)
    private val journal = GatewayDiagnosticLog()

    var inspection by mutableStateOf<GatewaySecurityInspection?>(null)
        private set
    var testRunning by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun refresh(savedEndpoint: String, draftEndpoint: String = savedEndpoint): GatewaySecurityInspection {
        val status = runCatching { home.status() }.getOrNull()
        val matching = status?.configuration?.origin?.url == savedEndpoint
        val key = if (matching && status?.keyPresent == true) com.no5.helm.security.GatewayMaterialState.PRESENT else com.no5.helm.security.GatewayMaterialState.MISSING
        val ca = if (matching && status?.caPresent == true) com.no5.helm.security.GatewayMaterialState.PRESENT else com.no5.helm.security.GatewayMaterialState.MISSING
        val profile = when {
            key != com.no5.helm.security.GatewayMaterialState.PRESENT -> GatewayProfileState.KEY_MISSING
            ca != com.no5.helm.security.GatewayMaterialState.PRESENT -> GatewayProfileState.CA_MISSING
            status?.configuration?.usable != true -> GatewayProfileState.PROFILE_UNAVAILABLE
            else -> GatewayProfileState.READY
        }
        return GatewaySecurityInspection(savedEndpoint, status?.configuration?.origin?.url, draftEndpoint,
            if (matching && draftEndpoint == savedEndpoint) GatewayDraftOriginState.MATCHES_SAVED else GatewayDraftOriginState.DIFFERENT_FROM_SAVED,
            key, ca, null, profile).also { inspection = it; revision++ }
    }

    fun canTest(savedEndpoint: String, draftEndpoint: String): Boolean =
        refresh(savedEndpoint, draftEndpoint).let {
            it.profile == GatewayProfileState.READY &&
                it.draftOriginState == GatewayDraftOriginState.MATCHES_SAVED &&
                !testRunning
        }

    fun runReadOnlyTest(scope: CoroutineScope, savedEndpoint: String, draftEndpoint: String) {
        val inspected = refresh(savedEndpoint, draftEndpoint)
        if (testRunning || inspected.profile != GatewayProfileState.READY || inspected.draftOriginState != GatewayDraftOriginState.MATCHES_SAVED) {
            journal.record(service = GatewayDiagnosticService.SECURITY, phase = GatewayDiagnosticPhase.TEST_RESULT,
                outcome = GatewayDiagnosticOutcome.SKIPPED,
                error = testBlocker(inspected))
            revision++
            return
        }
        testRunning = true
        scope.launch(Dispatchers.IO) {
            try { tester.test(savedEndpoint, journal) { scope.launch(Dispatchers.Main.immediate) { revision++ } } }
            finally { withContext(Dispatchers.Main.immediate) { testRunning = false; refresh(savedEndpoint, draftEndpoint) } }
        }
    }

    fun recordReload(result: SourceApplyResult, savedEndpoint: String, draftEndpoint: String) {
        val attempt = journal.newAttemptId()
        journal.record(attempt, GatewayDiagnosticService.SESSION, GatewayDiagnosticPhase.RELOAD_STOP,
            GatewayDiagnosticOutcome.SUCCESS, note = GatewayDiagnosticNote.ORDERED_RELOAD)
        journal.record(attempt, GatewayDiagnosticService.SESSION, GatewayDiagnosticPhase.RELOAD_START,
            if (result.applied) GatewayDiagnosticOutcome.SUCCESS else GatewayDiagnosticOutcome.FAILURE,
            error = if (result.applied) GatewayDiagnosticError.NONE else GatewayDiagnosticError.INTERNAL,
            note = GatewayDiagnosticNote.ORDERED_RELOAD)
        refresh(savedEndpoint, draftEndpoint)
    }

    fun recordImport(success: Boolean) {
        journal.record(service = GatewayDiagnosticService.SECURITY, phase = GatewayDiagnosticPhase.IMPORT,
            outcome = if (success) GatewayDiagnosticOutcome.SUCCESS else GatewayDiagnosticOutcome.FAILURE,
            error = if (success) GatewayDiagnosticError.NONE else GatewayDiagnosticError.PROFILE_INVALID,
            note = if (success) GatewayDiagnosticNote.MATERIAL_READY else GatewayDiagnosticNote.MATERIAL_INCOMPLETE)
        revision++
    }

    fun snapshot(filter: GatewayDiagnosticFilter): GatewayDiagnosticSnapshot {
        revision
        return journal.snapshot(filter)
    }

    fun export(format: GatewayDiagnosticExportFormat): ByteArray = journal.export(format)
    fun clear() { journal.clear(); revision++ }
    override fun close() = endpointResolver.close()

    private fun testBlocker(inspected: GatewaySecurityInspection): GatewayDiagnosticError = when {
        inspected.draftOriginState != GatewayDraftOriginState.MATCHES_SAVED -> GatewayDiagnosticError.DRAFT_NOT_SAVED
        inspected.profile == GatewayProfileState.KEY_MISSING -> GatewayDiagnosticError.KEY_MISSING
        inspected.profile == GatewayProfileState.CA_MISSING -> GatewayDiagnosticError.CA_MISSING
        inspected.profile == GatewayProfileState.MATERIAL_UNREADABLE -> GatewayDiagnosticError.PROFILE_INVALID
        inspected.profile == GatewayProfileState.NOT_HTTPS -> GatewayDiagnosticError.PROFILE_INVALID
        else -> GatewayDiagnosticError.PROFILE_MISSING
    }
}

package com.no5.helm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.system.Os
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.no5.preparation.ui.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Test-only in-memory reducer + actual product Dialog. Never attaches a persistence/network port. */
internal class UiCleanupReleaseFixture(private val activity: MainActivity) : AutoCloseable {
    private val initial = RoutesState(
        draft = RouteDocument("synthetic-ui-only", "SYNTHETIC retained draft",
            frozenListOf(RoutePoint(1.0, 2.0), RoutePoint(1.1, 2.1))),
        dirty = true, discardDialog = true,
    )
    private val state = mutableStateOf(initial)
    private var view: ComposeView? = null
    var saveCallbacks = 0
        private set
    var injectedFailureReason: String? = null
        private set

    fun mount() {
        checkMain()
        check(view == null)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        view = ComposeView(activity).also { fixture ->
            fixture.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            fixture.setContent {
                No5ControlTheme(redNight = false) {
                    RoutesDialogs(state.value, onEvent = { event ->
                        if (event == RoutesEvent.Save) saveCallbacks++
                        state.value = reduceRoutes(state.value, event)
                    })
                }
            }
            content.addView(fixture, FrameLayout.LayoutParams(-1, -1))
        }
    }

    fun snapshot(): RoutesState { checkMain(); return state.value }
    fun originalDraftRetained(): Boolean { checkMain(); return state.value.draft == initial.draft }
    fun failPendingSave() {
        checkMain()
        val token = checkNotNull(state.value.saveRequest).token
        // Explicit lines establish the overflow precondition without changing the real
        // dialog/font/window. The previous 1532-character paragraph did not overflow on Lumina.
        val reason = (1..160).joinToString("\n") {
            "SYNTHETIC_UI_ONLY #$it: draft retained; no persistence attempted."
        }
        check(reason.length <= 12 * 1024)
        injectedFailureReason = reason
        state.value = reduceRoutes(state.value, RoutesEvent.SaveFailed(token, reason))
    }
    fun acknowledgePendingSave(matching: Boolean = true) {
        checkMain()
        val token = checkNotNull(state.value.saveRequest).token
        // Explicit synthetic ACK. This is not a claim of durable storage or a real gateway ACK.
        state.value = reduceRoutes(state.value, RoutesEvent.Saved(if (matching) token else token + 1))
    }
    override fun close() {
        checkMain()
        view?.let { it.disposeComposition(); (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        fun roots(): List<View> = WindowInspector.getGlobalWindowViews().toList()
        private fun digest(file: File): String = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        @Suppress("DEPRECATION")
        fun identity(target: Context, testContext: Context, arguments: Bundle): JSONObject {
            fun required(name: String, pattern: Regex): String =
                checkNotNull(arguments.getString("no5.uiCleanup.$name")).also { check(pattern.matches(it)) }
            val run = required("run", Regex("[a-f0-9-]{36}"))
            check(UUID.fromString(run).toString() == run)
            val expectedProduct = required("apkSha256", Regex("[a-f0-9]{64}"))
            val expectedTest = required("testApkSha256", Regex("[a-f0-9]{64}"))
            val source = required("sourceCommit", Regex("[a-f0-9]{40}"))
            val signer = required("signerSha256", Regex("[a-f0-9]{64}"))
            val code = required("versionCode", Regex("[1-9][0-9]{0,8}")).toLong()
            check(target.packageName == "com.no5.map" && testContext.packageName == "com.no5.map.test")
            check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            check(digest(File(target.applicationInfo.sourceDir)) == expectedProduct)
            check(digest(File(testContext.applicationInfo.sourceDir)) == expectedTest)
            for (context in listOf(target, testContext)) {
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signatures = checkNotNull(info.signingInfo).apkContentsSigners
                check(signatures.size == 1)
                val actual = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                check(actual == signer)
            }
            val metadata = JSONObject(target.assets.open("no5-release/current.json").bufferedReader().use { it.readText() })
            check(metadata.getString("sourceCommit") == source && metadata.getLong("versionCode") == code)
            check(target.packageManager.getPackageInfo(target.packageName, 0).longVersionCode == code)
            return JSONObject().put("run", run).put("productApkSha256", expectedProduct)
                .put("testApkSha256", expectedTest).put("sourceCommit", source).put("versionCode", code)
                .put("signerSha256", signer).put("buildId", metadata.getString("buildId"))
                .put("regularNondebugTarget", true).put("syntheticInMemoryDraftOnly", true)
                .put("realRouteStoreOrGatewayWrite", false).put("productionDialog", "RoutesDialogs/No5UnsavedChangesDialog")
                .put("productionReducer", "reduceRoutes").put("localeAndFontChanged", false)
                .put("productionDialogSource", "map/preparation/native-five-screens/ui/src/main/kotlin/com/no5/preparation/ui/No5UnsavedChangesDialog.kt")
                .put("productionReducerSource", "map/preparation/native-five-screens/ui/src/main/kotlin/com/no5/preparation/ui/Routes.kt")
        }
        fun write(target: Context, report: JSONObject) {
            val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
            check(bytes.size <= 64 * 1024)
            val root = File(target.filesDir, "ui-cleanup-release")
            check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
            check(root.isDirectory || root.mkdir())
            Os.chmod(root.absolutePath, 0b111000000)
            val directory = File(root, report.getString("run"))
            check(directory.mkdir()) { "Evidence UUID cannot be reused" }
            Os.chmod(directory.absolutePath, 0b111000000)
            val file = File(directory, "ui-cleanup.json")
            check(file.createNewFile())
            Os.chmod(file.absolutePath, 0b110000000)
            file.outputStream().use { it.write(bytes); it.fd.sync() }
        }
    }
}

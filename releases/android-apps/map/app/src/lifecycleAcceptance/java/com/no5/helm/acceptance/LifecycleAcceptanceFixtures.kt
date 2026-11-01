package com.no5.helm.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import com.no5.helm.app.BuildConfig
import com.no5.helm.chart.ChartResidentSource
import com.no5.helm.library.ChartLibrary
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** No caller can supply a filesystem root. The APK identity and app-private run are fixed. */
class LifecycleAcceptanceContext(base: Context, val runId: String) : ContextWrapper(base) {
    val runRoot: File
    val libraryRoot: File
    init {
        require(base.packageName == APPLICATION_ID && BuildConfig.APPLICATION_ID == APPLICATION_ID &&
            BuildConfig.BUILD_TYPE == "lifecycleAcceptance") { "Acceptance code requires the isolated application" }
        require(runId.matches(RUN_ID)) { "Invalid acceptance run" }
        runRoot = File(base.filesDir, "lifecycle-acceptance-runs/$runId")
        require(runRoot.mkdirs() || runRoot.isDirectory)
        require(runRoot.canonicalPath.startsWith(base.filesDir.canonicalPath + File.separator))
        libraryRoot = File(filesDir, "chart-library-v1")
        val marker = File(runRoot, "ISOLATED_TEST_RUN.json")
        val expected = JSONObject().put("schema", "no5-lifecycle-acceptance-run/1")
            .put("applicationId", APPLICATION_ID).put("runId", runId).put("root", runRoot.canonicalPath)
            .put("fixturePinsSha256", fixturePinsSha256(base))
        if (marker.exists()) {
            val previous = JSONObject(marker.readText())
            require(previous.getString("applicationId") == APPLICATION_ID && previous.getString("runId") == runId &&
                previous.getString("root") == runRoot.canonicalPath &&
                previous.getString("fixturePinsSha256") == expected.getString("fixturePinsSha256")) { "Acceptance marker mismatch" }
        } else FileOutputStream(marker).use { output -> output.write(expected.toString(2).toByteArray()); output.fd.sync() }
    }
    override fun getFilesDir(): File = File(runRoot, "files").apply { require(mkdirs() || isDirectory) }
    override fun getCacheDir(): File = File(runRoot, "cache").apply { require(mkdirs() || isDirectory) }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("lifecycle-acceptance.$runId.$name", mode)
    fun requireOwned(file: File): File = file.also {
        require(it.canonicalPath.startsWith(runRoot.canonicalPath + File.separator)) { "Path outside isolated acceptance run" }
    }
    companion object {
        const val APPLICATION_ID = "com.no5.map.lifecycleacceptance"
        const val RUN_EXTRA = "lifecycleAcceptanceRunId"
        val RUN_ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        fun newRunId() = UUID.randomUUID().toString()
        private fun fixturePinsSha256(context: Context): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(context.assets.open("lifecycle-fixtures/pins.json").use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
    }
}

enum class LifecycleFixture(val uriName: String, val displayName: String, val files: List<String>) {
    SEED("seed", "D1-NOAA-SEED.zip", listOf("US5BOSCC.000", "US5BOSCC.001", "US5BOSCC.002", "US5BOSDD.000")),
    UPDATE("update", "D1-NOAA-UPDATE.zip", listOf("US5BOSCC.003", "US5BOSCC.004")),
    GAP("gap", "D1-NEGATIVE-GAP.zip", listOf("US5BOSCC.004")),
    WSV("wsv", "D1-WSV-BASE.zip", listOf("1W7BRW02.000")),
    SLOW_SEED("slow-seed", "D1-NOAA-SEED-SLOW-TEST.zip", SEED.files);
}

data class LifecycleExpectedSource(val name: String, val provider: String, val edition: Int,
    val update: Int, val hashes: Map<String, String>) {
    fun matches(source: ChartResidentSource): Boolean = source.name == name && source.provider == provider &&
        source.edition == edition && source.update == update &&
        source.files.associate { File(it.path).name to it.sha256 } == hashes && source.files.size == hashes.size
}

/** Repackages original bytes; no DSID, country/producer names or update bytes are edited. */
object LifecycleAcceptanceFixtures {
    fun uri(context: Context, fixture: LifecycleFixture): Uri {
        require(context.packageName == LifecycleAcceptanceContext.APPLICATION_ID)
        return Uri.parse("content://${context.packageName}.lifecyclefixtures/${fixture.uriName}")
    }
    @Synchronized fun archive(context: Context, fixture: LifecycleFixture): File {
        require(context.packageName == LifecycleAcceptanceContext.APPLICATION_ID)
        val root = File(context.filesDir, "lifecycle-original-fixtures").apply { require(mkdirs() || isDirectory) }
        require(root.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val destination = File(root, fixture.displayName)
        val pins = JSONObject(context.assets.open("lifecycle-fixtures/pins.json").bufferedReader().use { it.readText() })
            .getJSONArray("files")
        val data = fixture.files.map { name ->
            val pin = (0 until pins.length()).map { pins.getJSONObject(it) }.single { it.getString("name") == name }
            val bytes = context.assets.open("lifecycle-fixtures/${pin.getString("asset")}").use { it.readBytes() }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(bytes.size.toLong() == pin.getLong("bytes") && hash == pin.getString("sha256")) { "Original fixture changed: $name" }
            name to bytes
        }
        // A unique temporary file is confined to this test app; existing ZIPs are verified, not trusted by name.
        val temporary = File(root, destination.name + ".tmp")
        try {
            ZipOutputStream(temporary.outputStream()).use { zip ->
                for ((name, bytes) in data) {
                    zip.putNextEntry(ZipEntry("ENC_ROOT/$name").apply { time = 0 })
                    zip.write(bytes); zip.closeEntry()
                }
                // NOAA publisher terms accompany only NOAA bytes; WSV remains independently attributed.
                for (name in if (fixture == LifecycleFixture.WSV) emptyList() else listOf("README.TXT", "USERAGREEMENT.TXT")) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    context.assets.open("lifecycle-fixtures/$name").use { it.copyTo(zip) }; zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("D1-FIXTURE-PROVENANCE.json").apply { time = 0 })
                context.assets.open("lifecycle-fixtures/pins.json").use { it.copyTo(zip) }; zip.closeEntry()
            }
            if (destination.exists()) require(ChartLibrary.sha256(destination) == ChartLibrary.sha256(temporary)) { "Fixture ZIP changed" }
            else require(temporary.renameTo(destination)) { "Fixture ZIP cannot be published" }
        } finally { temporary.delete() }
        return destination
    }
    fun updatedNoaa(context: Context): List<LifecycleExpectedSource> {
        val pins = JSONObject(context.assets.open("lifecycle-fixtures/pins.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        fun hashes(name: String) = (0 until pins.length()).map { pins.getJSONObject(it) }
            .filter { it.getString("name").startsWith("$name.") }.associate { it.getString("name") to it.getString("sha256") }
        return listOf(LifecycleExpectedSource("US5BOSCC", "noaa-s57", 5, 4, hashes("US5BOSCC")),
            LifecycleExpectedSource("US5BOSDD", "noaa-s57", 5, 0, hashes("US5BOSDD")))
    }
}

package com.no5.helm

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Installs only APK resources. Imported charts and the chart library are never an input/output. */
internal object ChartAssetInstallation {
    fun prepare(context: Context, destination: File) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val identity = "1:${info.longVersionCode}:${info.lastUpdateTime}"
        install(destination, identity, { path -> context.assets.list(path).orEmpty().toList() },
            { path -> context.assets.open(path).use { it.readBytes() } })
    }

    internal fun install(destination: File, identity: String, list: (String) -> List<String>, read: (String) -> ByteArray) {
        destination.mkdirs()
        val marker = AtomicFile(File(destination, ".apk-resources-v1"))
        if (runCatching { marker.openRead().bufferedReader().use { it.readText() } }.getOrNull() == identity) return
        fun visit(path: String) {
            if (path == "charts" || path.startsWith("charts/")) return
            val children = list(path)
            if (children.isNotEmpty()) {
                children.forEach { visit(if (path.isEmpty()) it else "$path/$it") }; return
            }
            val extension = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension !in setOf("csv", "xml", "rle", "png", "ttf", "txf") &&
                !path.startsWith("presentation/") && !path.startsWith("renderer-benchmark/")) return
            val file = File(destination, if (path.startsWith("renderer-benchmark/")) path.removeSuffix(".s57") else path)
            val bytes = read(path)
            if (file.isFile && file.length() == bytes.size.toLong() &&
                MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                    MessageDigest.getInstance("SHA-256").digest(bytes))) return
            file.parentFile?.mkdirs()
            write(AtomicFile(file), bytes)
        }
        visit("")
        // A failed resource update never publishes the completed installation identity.
        write(marker, identity.toByteArray(Charsets.UTF_8))
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
}

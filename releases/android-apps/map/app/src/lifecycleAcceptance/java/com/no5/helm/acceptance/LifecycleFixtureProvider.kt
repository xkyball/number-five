package com.no5.helm.acceptance

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.io.IOException

/** Nonexported, fixed original fixture URIs only. Never accepts paths, writable modes or user data. */
class LifecycleFixtureProvider : ContentProvider() {
    override fun onCreate() = requireNotNull(context).packageName == LifecycleAcceptanceContext.APPLICATION_ID
    private fun fixture(uri: Uri): LifecycleFixture {
        val ctx = requireNotNull(context)
        require(ctx.packageName == LifecycleAcceptanceContext.APPLICATION_ID && uri.scheme == "content" &&
            uri.authority == "${ctx.packageName}.lifecyclefixtures" && uri.query == null && uri.fragment == null)
        return LifecycleFixture.entries.singleOrNull { uri.path == "/${it.uriName}" } ?: error("Unknown test fixture")
    }
    override fun getType(uri: Uri): String { fixture(uri); return "application/zip" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(selection == null && selectionArgs == null && sortOrder == null)
        val kind = fixture(uri)
        val file = LifecycleAcceptanceFixtures.archive(requireNotNull(context), kind)
        val columns = projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(columns.all { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE })
        return MatrixCursor(columns.toTypedArray()).apply {
            val row = Array<Any>(columns.size) { index ->
                if (columns[index] == OpenableColumns.DISPLAY_NAME) kind.displayName else file.length()
            }
            addRow(row)
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Read-only original test fixture" }
        val kind = fixture(uri)
        val file = LifecycleAcceptanceFixtures.archive(requireNotNull(context), kind)
        if (kind != LifecycleFixture.SLOW_SEED) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // Synthetic URI-IO delay tests cancel during READING. It is not a native parser latency measurement.
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            Thread.sleep(50); output.write(buffer, 0, n)
                        }
                    }
                }
            } catch (_: IOException) { /* Consumer cancel closes only this test pipe. */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); runCatching { pipe[1].close() } }
        }, "LifecycleFixtureSlowInput").apply { isDaemon = true; start() }
        return pipe[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
}

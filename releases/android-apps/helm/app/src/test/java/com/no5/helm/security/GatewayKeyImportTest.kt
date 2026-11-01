package com.no5.helm.security

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewayKeyImportTest {
    private class MemoryStore : GatewaySecurityStore {
        val values = linkedMapOf<GatewayOrigin, ByteArray>()
        var failSave = false
        var failRead = false
        var mismatchAtReadCall: Int? = null
        var readCalls = 0
        var removes = 0
        override fun save(origin: GatewayOrigin, key: ByteArray) {
            if (failSave) error("synthetic save failure")
            values[origin] = key.copyOf()
        }
        override fun read(origin: GatewayOrigin): ByteArray? {
            if (failRead) error("synthetic readback failure")
            readCalls++
            return values[origin]?.let {
                if (mismatchAtReadCall == readCalls) ByteArray(it.size) { 1 } else it.copyOf()
            }
        }
        override fun remove(origin: GatewayOrigin) { removes++; values.remove(origin)?.fill(0) }
    }

    private class Document(
        private val bytes: ByteArray?,
        var deletion: GatewayKeyDocumentDelete = GatewayKeyDocumentDelete.DELETED,
        private val throwOnDelete: Boolean = false,
    ) : GatewayKeyDocument {
        var opens = 0
        var deletes = 0
        override fun open() = bytes?.let { opens++; ByteArrayInputStream(it) }
        override fun delete(): GatewayKeyDocumentDelete {
            deletes++
            if (throwOnDelete) throw IOException("synthetic interruption")
            return deletion
        }
    }

    private val endpoint = "https://lysmarine.local:9443"
    private val syntheticKey = "synthetic-import-key-0123456789".toByteArray()
    private fun documentFor(
        origin: String = endpoint,
        key: ByteArray = syntheticKey,
        terminalLineFeed: Boolean = false,
    ) = Document(
        ("NO5-GATEWAY-KEY-V1\norigin=$origin\nkey-base64url=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(key) +
            if (terminalLineFeed) "\n" else "").toByteArray()
    )

    @Test fun successfulSyntheticImportRequiresReadbackBeforeDeletingOnlyTheSelectedDocument() {
        val store = MemoryStore()
        val document = documentFor()
        val result = GatewayKeyImporter(store).import(document, endpoint, deleteAfterImport = true)
        assertEquals(GatewayKeyImportResult.Imported(GatewayKeyDocumentDelete.DELETED), result)
        assertEquals(1, document.opens); assertEquals(1, document.deletes)
        assertEquals(syntheticKey.toList(), store.read(GatewayOrigin.fromEndpoint(endpoint))?.toList())
        assertTrue((result as GatewayKeyImportResult.Imported).message().contains("Connection not checked"))
    }

    @Test fun malformedOversizeAndWrongOriginFilesNeverPersistOrDelete() {
        val store = MemoryStore()
        val malformed = Document("not a provisioning file".toByteArray())
        val oversized = Document(ByteArray(GatewayKeyProvisioningFormat.MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
        val wrongOrigin = documentFor("https://other.local:9443")
        listOf(malformed, oversized, wrongOrigin).forEach { document ->
            assertTrue(GatewayKeyImporter(store).import(document, endpoint, true) is GatewayKeyImportResult.Rejected)
            assertEquals(0, document.deletes)
        }
        assertTrue(store.values.isEmpty())
    }

    @Test fun storeOrReadbackFailureNeverDeletesAndRemovesUnconfirmedValue() {
        listOf(
            MemoryStore().apply { failSave = true },
            MemoryStore().apply { mismatchAtReadCall = 2 },
            MemoryStore().apply { failRead = true },
        ).forEach { store ->
            val document = documentFor()
            assertTrue(GatewayKeyImporter(store).import(document, endpoint, true) is GatewayKeyImportResult.StoreFailure)
            assertEquals(0, document.deletes)
            assertTrue(store.values.isEmpty())
        }
    }

    @Test fun failedReplacementRestoresThePreviousConfirmedOriginValueWithoutDeletingTheSource() {
        val store = MemoryStore()
        val origin = GatewayOrigin.fromEndpoint(endpoint)
        val previous = "synthetic-previous-key-0123456".toByteArray()
        store.save(origin, previous)
        // Read one obtains the backup; read two is the new-key verification and fails.
        store.mismatchAtReadCall = 2
        val document = documentFor()

        assertTrue(GatewayKeyImporter(store).import(document, endpoint, true) is GatewayKeyImportResult.StoreFailure)
        assertEquals(0, document.deletes)
        assertEquals(previous.toList(), store.read(origin)?.toList())
    }

    @Test fun cancelManualDeleteFailureAndInterruptedDeletionNeverClaimDeletion() {
        val store = MemoryStore()
        assertEquals(GatewayKeyImportResult.Cancelled, GatewayKeyImporter(store).import(null, endpoint, true))
        val manual = documentFor().apply { deletion = GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED }
        assertEquals(GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED,
            (GatewayKeyImporter(store).import(manual, endpoint, true) as GatewayKeyImportResult.Imported).sourceDelete)
        val interrupted = Document(documentFor().open()!!.readBytes(), throwOnDelete = true)
        val interruptedResult = GatewayKeyImporter(store).import(interrupted, endpoint, true) as GatewayKeyImportResult.Imported
        assertEquals(GatewayKeyDocumentDelete.FAILED, interruptedResult.sourceDelete)
        assertEquals(1, interrupted.deletes)
        assertTrue(store.values.isNotEmpty()) // Persisted readback succeeds before a later interruption.
    }

    @Test fun noDeleteRequestLeavesSourceAndOriginBindingRejectsHttpOrPathProvisioning() {
        val store = MemoryStore()
        val document = documentFor()
        assertEquals(GatewayKeyImportResult.Imported(null), GatewayKeyImporter(store).import(document, endpoint, false))
        assertEquals(0, document.deletes)
        assertTrue(GatewayKeyImporter(store).import(documentFor("http://lysmarine.local:9443"), endpoint, false) is GatewayKeyImportResult.Rejected)
        assertTrue(GatewayKeyImporter(store).import(documentFor("https://lysmarine.local:9443/gateway"), endpoint, false) is GatewayKeyImportResult.Rejected)
        assertNull(store.read(GatewayOrigin.fromEndpoint("https://different.local")))
    }

    @Test fun v1AcceptsOneTerminalLineFeedButRejectsAnExtraBlankFourthLine() {
        val accepted = GatewayKeyImporter(MemoryStore()).import(documentFor(terminalLineFeed = true), endpoint, false)
        assertEquals(GatewayKeyImportResult.Imported(null), accepted)

        val extraBlankLine = Document(
            ("NO5-GATEWAY-KEY-V1\norigin=$endpoint\nkey-base64url=" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(syntheticKey) + "\n\n").toByteArray()
        )
        val rejected = GatewayKeyImporter(MemoryStore()).import(extraBlankLine, endpoint, false)
        assertEquals(
            "Gateway key file must contain exactly three nonempty lines",
            (rejected as GatewayKeyImportResult.Rejected).message,
        )
    }

    @Test fun v1FormatFailuresUseSpecificSafeMessages() {
        fun rejection(bytes: ByteArray): String =
            (GatewayKeyImporter(MemoryStore()).import(Document(bytes), endpoint, false) as GatewayKeyImportResult.Rejected).message

        assertEquals(
            "Gateway key file must use LF line endings",
            rejection("NO5-GATEWAY-KEY-V1\r\norigin=$endpoint\r\nkey-base64url=ZmFrZQ".toByteArray()),
        )
        assertEquals(
            "Gateway key file header is not NO5-GATEWAY-KEY-V1",
            rejection("NO5-GATEWAY-KEY-V0\norigin=$endpoint\nkey-base64url=ZmFrZQ".toByteArray()),
        )
        assertEquals(
            "Gateway key file is missing its origin field",
            rejection("NO5-GATEWAY-KEY-V1\nwrong=$endpoint\nkey-base64url=ZmFrZQ".toByteArray()),
        )
    }

    @Test fun safPickerRequestsWriteOnlyForTheSelectedDocumentAndRejectsNonContentResults() {
        val contract = GatewayKeyOpenDocumentContract()
        val intent = contract.createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val selected = Uri.parse("content://synthetic.provider/key-file")
        assertEquals(selected, contract.parseResult(Activity.RESULT_OK, Intent().setData(selected)))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///not-a-saf-document"))))
    }

    @Test fun caPickerIsReadOnlyAndAcceptsOnlyTheExactContentDocument() {
        val contract = GatewayTrustOpenDocumentContract()
        val intent = contract.createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val selected = Uri.parse("content://synthetic.provider/public-ca.pem")
        assertEquals(selected, contract.parseResult(Activity.RESULT_OK, Intent().setData(selected)))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///not-a-saf-document"))))
    }
}

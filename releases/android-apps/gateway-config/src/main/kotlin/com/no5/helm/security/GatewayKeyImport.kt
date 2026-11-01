package com.no5.helm.security

import com.no5.domain.SourceConfig
import com.no5.domain.SourceConfigValidator
import com.no5.domain.SourceMode
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Canonical HTTPS origin that binds a gateway key to exactly one scheme/host/port triple. */
@JvmInline
value class GatewayOrigin private constructor(val value: String) {
    companion object {
        fun fromEndpoint(endpoint: String): GatewayOrigin = fromNormalized(endpoint, requireOriginOnly = false)
        fun fromProvisioning(origin: String): GatewayOrigin = fromNormalized(origin, requireOriginOnly = true)

        private fun fromNormalized(input: String, requireOriginOnly: Boolean): GatewayOrigin {
            val normalized = SourceConfigValidator.validate(SourceConfig(mode = SourceMode.PI, endpoint = input)).config?.endpoint
                ?: throw IllegalArgumentException("Gateway origin is not a valid HTTPS address")
            val uri = URI(normalized)
            require(uri.scheme == "https") { "Gateway key import requires an HTTPS origin" }
            if (requireOriginOnly) require(uri.rawPath.isNullOrEmpty()) { "Gateway key origin cannot include a path" }
            val authority = uri.rawAuthority ?: throw IllegalArgumentException("Gateway origin is missing a host")
            return GatewayOrigin("https://$authority")
        }
    }
}

class GatewayKeyProvisioning internal constructor(val origin: GatewayOrigin, val key: ByteArray)

/** Strict, public provisioning format. Parsing never includes a key value in a failure message. */
object GatewayKeyProvisioningFormat {
    const val MAX_FILE_BYTES = 2_048
    private const val HEADER = "NO5-GATEWAY-KEY-V1"

    fun parse(bytes: ByteArray): GatewayKeyProvisioning {
        require(bytes.size in 1..MAX_FILE_BYTES) { "Gateway key file has an invalid size" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Gateway key file is not valid UTF-8")
        }
        require(!text.startsWith('\uFEFF')) { "Gateway key file must be UTF-8 without a BOM" }
        require('\r' !in text) { "Gateway key file must use LF line endings" }
        // A conventional text-file terminator is not a fourth V1 field. Remove at most one so
        // blank fourth lines and every other extra field remain invalid.
        val lines = text.removeSuffix("\n").split('\n')
        require(lines.size == 3 && lines.none(String::isEmpty)) {
            "Gateway key file must contain exactly three nonempty lines"
        }
        require(lines[0] == HEADER) { "Gateway key file header is not NO5-GATEWAY-KEY-V1" }
        require(lines[1].startsWith("origin=")) { "Gateway key file is missing its origin field" }
        require(lines[2].startsWith("key-base64url=")) { "Gateway key file is missing its base64url key field" }
        val origin = GatewayOrigin.fromProvisioning(lines[1].removePrefix("origin="))
        val encodedKey = lines[2].removePrefix("key-base64url=")
        require(encodedKey.isNotEmpty()) { "Gateway key file has an empty base64url key field" }
        val key = try {
            Base64.getUrlDecoder().decode(encodedKey)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Gateway key file has an invalid key encoding")
        }
        require(key.size in 16..512 && key.all { byte ->
            val value = byte.toInt() and 0xff
            value in 0x21..0x7e && value != ':'.code
        }) {
            key.fill(0)
            "Gateway key file has an invalid key value"
        }
        return GatewayKeyProvisioning(origin, key)
    }
}

/** Encryption/storage boundary. Implementations must bind ciphertext authentication to [origin]. */
interface GatewaySecurityStore {
    fun save(origin: GatewayOrigin, key: ByteArray)
    fun read(origin: GatewayOrigin): ByteArray?
    fun remove(origin: GatewayOrigin)
}

interface GatewayKeyDocument {
    /** Null means the user cancelled the picker before choosing a document. */
    fun open(): InputStream?
    fun delete(): GatewayKeyDocumentDelete
}

enum class GatewayKeyDocumentDelete { DELETED, MANUAL_DELETE_REQUIRED, FAILED }

sealed interface GatewayKeyImportResult {
    data object Cancelled : GatewayKeyImportResult
    data class Rejected(val message: String) : GatewayKeyImportResult
    data class StoreFailure(val message: String) : GatewayKeyImportResult
    data class Imported(val sourceDelete: GatewayKeyDocumentDelete?) : GatewayKeyImportResult {
        fun message(): String = when (sourceDelete) {
            GatewayKeyDocumentDelete.DELETED -> "Key saved; selected source file deleted. Connection not checked."
            GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED -> "Key saved. Delete the selected source file manually. Connection not checked."
            GatewayKeyDocumentDelete.FAILED -> "Key saved, but deleting the selected source file failed. Connection not checked."
            null -> "Key saved, connection not checked."
        }
    }
}

/**
 * Bounded, no-network import transaction. The caller decides whether the user confirmed source
 * deletion. A document is never deleted before encrypted persistence and a matching readback.
 */
class GatewayKeyImporter(private val store: GatewaySecurityStore) {
    fun import(document: GatewayKeyDocument?, expectedEndpoint: String, deleteAfterImport: Boolean): GatewayKeyImportResult {
        if (document == null) return GatewayKeyImportResult.Cancelled
        val expected = try { GatewayOrigin.fromEndpoint(expectedEndpoint) }
        catch (_: IllegalArgumentException) { return GatewayKeyImportResult.Rejected("Save a valid HTTPS gateway address before importing a key") }
        val raw = try {
            document.open()?.let(::readBounded) ?: return GatewayKeyImportResult.Cancelled
        } catch (_: Exception) {
            return GatewayKeyImportResult.Rejected("The selected gateway key file could not be read")
        }
        var provision: GatewayKeyProvisioning? = null
        var previous: ByteArray? = null
        var writeAttempted = false
        try {
            provision = GatewayKeyProvisioningFormat.parse(raw)
            if (provision.origin != expected) return GatewayKeyImportResult.Rejected("The key file is bound to a different gateway origin")
            previous = try {
                store.read(provision.origin)
            } catch (_: Exception) {
                return GatewayKeyImportResult.StoreFailure("Gateway key storage could not be prepared securely")
            }
            try {
                writeAttempted = true
                store.save(provision.origin, provision.key)
                val readback = store.read(provision.origin)
                val matches = readback != null && MessageDigest.isEqual(provision.key, readback)
                readback?.fill(0)
                if (!matches) {
                    throw IllegalStateException("Gateway key readback did not match")
                }
            } catch (_: Exception) {
                if (writeAttempted) restorePreviousValue(provision.origin, previous)
                return GatewayKeyImportResult.StoreFailure("Gateway key could not be saved securely")
            }
            if (!deleteAfterImport) return GatewayKeyImportResult.Imported(null)
            return try {
                GatewayKeyImportResult.Imported(document.delete())
            } catch (_: Exception) {
                GatewayKeyImportResult.Imported(GatewayKeyDocumentDelete.FAILED)
            }
        } catch (failure: IllegalArgumentException) {
            return GatewayKeyImportResult.Rejected(failure.message ?: "Gateway key file was rejected")
        } finally {
            raw.fill(0)
            provision?.key?.fill(0)
            previous?.fill(0)
        }
    }

    /** Keep a previously confirmed origin value if an attempted replacement did not verify. */
    private fun restorePreviousValue(origin: GatewayOrigin, previous: ByteArray?) {
        try {
            if (previous == null) {
                store.remove(origin)
                return
            }
            store.save(origin, previous)
            val restored = store.read(origin)
            val matches = restored != null && MessageDigest.isEqual(previous, restored)
            restored?.fill(0)
            if (!matches) store.remove(origin)
        } catch (_: Exception) {
            runCatching { store.remove(origin) }
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        input.use { source ->
            val content = ByteArray(GatewayKeyProvisioningFormat.MAX_FILE_BYTES)
            val buffer = ByteArray(512)
            var size = 0
            try {
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (size + count > content.size) throw IOException("Gateway key file is too large")
                    buffer.copyInto(content, size, 0, count)
                    size += count
                }
                return content.copyOf(size)
            } finally {
                content.fill(0)
                buffer.fill(0)
            }
        }
    }
}

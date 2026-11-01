package com.no5.helm.security

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import java.io.InputStream

/** Requests only a user-selected document; write access is requested solely for an optional later delete. */
class GatewayKeyOpenDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "text/plain", "*/*"))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } else null
}

/**
 * The public CA is selected through the same user-mediated document picker, but never needs
 * write permission: importing it only reads and stores an encrypted local copy.
 */
class GatewayTrustOpenDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "application/x-pem-file",
            "application/pkix-cert",
            "application/x-x509-ca-cert",
            "application/octet-stream",
            "text/plain",
        ))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } else null
}

/** Exact user-selected SAF URI only. No path traversal, directory operation, or global storage access. */
class SafGatewayKeyDocument(private val resolver: ContentResolver, private val uri: Uri) : GatewayKeyDocument {
    override fun open(): InputStream? = resolver.openInputStream(uri)

    override fun delete(): GatewayKeyDocumentDelete = try {
        if (DocumentsContract.deleteDocument(resolver, uri)) GatewayKeyDocumentDelete.DELETED
        else GatewayKeyDocumentDelete.FAILED
    } catch (_: UnsupportedOperationException) {
        GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED
    } catch (_: SecurityException) {
        GatewayKeyDocumentDelete.MANUAL_DELETE_REQUIRED
    } catch (_: Exception) {
        GatewayKeyDocumentDelete.FAILED
    }
}

/** Read-only wrapper for exactly the certificate document chosen through the CA picker. */
class SafGatewayTrustDocument(private val resolver: ContentResolver, private val uri: Uri) : GatewayTrustDocument {
    override fun open(): InputStream? = resolver.openInputStream(uri)
}

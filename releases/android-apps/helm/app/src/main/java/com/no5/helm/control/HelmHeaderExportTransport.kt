package com.no5.helm.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.lumina.header.consumer.v1.AndroidHeaderConnection
import org.lumina.header.consumer.v1.HeaderConsumerContract.Clock
import org.lumina.header.consumer.v1.HeaderReadOnlyPublisher

/** Service-owned off-Main transport acquisition after independently accepted Root focus. */
internal fun interface HelmHeaderTransportFactory { fun open(): HelmHeaderExportTransport }

internal interface HelmHeaderExportTransport : AutoCloseable {
    fun start(clock: Clock, existingControllerSession: String,
        control: HeaderReadOnlyPublisher.AuthenticatedRootControlPort): HeaderReadOnlyPublisher?
    fun sendHeartbeat(): Boolean
}

/** Actual API29 kernel peer check/one-shot handshake happens before parsing, in the reviewed port.
 * Passing UID0 is a privileged trust boundary, not proof of a particular Root executable. */
internal class HelmHeaderSocketTransport(private val connected: LocalSocket) : HelmHeaderExportTransport {
    private var endpoint: AndroidHeaderConnection.HelmEndpoint? = null
    override fun start(clock: Clock, existingControllerSession: String,
        control: HeaderReadOnlyPublisher.AuthenticatedRootControlPort): HeaderReadOnlyPublisher? {
        check(endpoint == null)
        val value = AndroidHeaderConnection.HelmEndpoint(connected, clock, existingControllerSession, control)
        endpoint = value
        return value.start()
    }
    override fun sendHeartbeat(): Boolean = endpoint?.sendHeartbeat() ?: false
    override fun close() { endpoint?.close() ?: connected.close() }
    companion object {
        /** Explicit <=90s candidate uses the existing UID0 trust boundary + same-session handshake.
         * Source-pinned payload/start plan is reviewed separately; this does not attest an executable.
         * API29 connect/close are not claimed interruptible; the exporter bounds worker ownership.
         */
        fun nativeCandidateFactory() = HelmHeaderTransportFactory {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(org.lumina.header.consumer.v1.HeaderConsumerContract.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT))
                HelmHeaderSocketTransport(socket)
            } catch (failure: Throwable) {
                try { socket.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}

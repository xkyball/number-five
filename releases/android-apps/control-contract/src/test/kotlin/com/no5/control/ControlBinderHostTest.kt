package com.no5.control

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ControlBinderHostTest {
    private fun host() = ControlBinderHost("test-session", 0,
        { uid -> CallerAdmissionPolicy.verify(uid, 0, listOf("com.no5.home"), true, true) }, { 10001 })

    private fun envelope(ack: ClientAdmission, sequence: Long = 1) = KnobEnvelope(
        controllerSession = ack.controllerSession, sequence = sequence, foregroundEpoch = ack.foregroundEpoch,
        clientEpoch = ack.clientEpoch, bindingsEpoch = ack.bindingsEpoch, input = NormalizedKnobInput.LEFT_CW)

    private class Callback(var consume: (KnobEnvelope) -> ClientDisposition = { ClientDisposition.HANDLED }) : Binder() {
        var deliveries = 0
        var state: ClientAdmission? = null
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(SystemControlContract.CALLBACK_DESCRIPTOR)
            requireNotNull(reply)
            reply.writeNoException()
            when (code) {
                SystemControlContract.CALLBACK_STATE -> {
                    state = ControlParcel.readAdmission(data)
                    ControlParcel.end(data); reply.writeInt(1)
                }
                SystemControlContract.CALLBACK_INPUT -> {
                    val value = ControlParcel.readEnvelope(data)
                    ControlParcel.end(data); deliveries++
                    reply.writeInt(consume(value).wireCode)
                }
                else -> return false
            }
            return true
        }
    }

    @Test fun appClaimedFocusNeverEstablishesOSForeground() {
        host().use { host ->
            val callback = Callback()
            ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
            assertNull(host.findReadyClient("com.no5.home", 1))
            host.observeForeground(ObservedForeground("foreign.app", 1, true, 0))
            assertNull(host.findReadyClient("com.no5.home", 1))
            host.observeForeground(ObservedForeground("com.no5.home", 2, false, 0))
            assertNull(host.findReadyClient("com.no5.home", 2))
            host.observeForeground(ObservedForeground("com.no5.home", 3, true, 0, 1))
            assertNull(host.findReadyClient("com.no5.home", 3))
            host.observeForeground(ObservedForeground("com.no5.home", 4, true, 1))
            assertNull(host.findReadyClient("com.no5.home", 4))
            host.observeForeground(ObservedForeground("com.no5.home", 5, true, 0))
            assertNotNull(host.findReadyClient("com.no5.home", 5))
            assertNull(host.findReadyClient("com.no5.home", 4))
        }
    }

    @Test fun replayAndWrongSessionClientOrBindingsNeverReachCallback() {
        host().use { host ->
            host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
            val callback = Callback()
            val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
            val value = envelope(ack)
            assertEquals(ClientDisposition.HANDLED, host.dispatch(value))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(value))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(value.copy(sequence = 2, controllerSession = "other")))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(value.copy(sequence = 2, clientEpoch = ack.clientEpoch + 1)))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(value.copy(sequence = 2, bindingsEpoch = 2)))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(value.copy(sequence = 2, foregroundEpoch = 2)))
            assertEquals(1, callback.deliveries)
        }
    }

    @Test fun onlyCurrentCallbackAudioDispositionIsReturnedAndRefusalDoesNotFallback() {
        host().use { host ->
            host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
            val callback = Callback { ClientDisposition.AUDIO_DEFAULT }
            val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
            assertEquals(ClientDisposition.AUDIO_DEFAULT, host.dispatch(envelope(ack)))
            callback.consume = { ClientDisposition.REFUSED }
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(ack, 2)))
            callback.consume = { host.observeForeground(ObservedForeground("foreign.app", 2, true, 0)); ClientDisposition.AUDIO_DEFAULT }
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(ack, 3)))
            assertEquals(3, callback.deliveries)
        }
    }

    @Test fun replaceDeregisterAndBindingChangesInvalidateOldRegistration() {
        host().use { host ->
            host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
            val remote = ControlRemote(host.binder)
            val first = Callback()
            val old = remote.register("com.no5.home", first, ClientState(1, true, true))
            val nextCallback = Callback()
            val next = remote.register("com.no5.home", nextCallback, ClientState(1, true, true))
            assertTrue(next.clientEpoch > old.clientEpoch)
            assertFalse(remote.deregister(old.controllerSession, old.clientEpoch, first))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(old)))
            val changed = remote.state(next.controllerSession, next.clientEpoch, nextCallback, ClientState(2, true, true))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(next)))
            assertEquals(ClientDisposition.HANDLED, host.dispatch(envelope(changed)))
            remote.state(next.controllerSession, next.clientEpoch, nextCallback, ClientState(2, true, false))
            assertNull(host.findReadyClient("com.no5.home", 1))
            assertTrue(remote.deregister(next.controllerSession, next.clientEpoch, nextCallback))
            assertNull(host.findReadyClient("com.no5.home", 1))
        }
    }

    @Test fun deathAndExceptionNeverBecomeAudioDefault() {
        host().use { host ->
            host.observeForeground(ObservedForeground("com.no5.home", 1, true, 0))
            val callback = DeathBinder(Callback())
            val ack = ControlRemote(host.binder).register("com.no5.home", callback, ClientState(1, true, true))
            callback.die()
            assertNull(host.findReadyClient("com.no5.home", 1))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(ack)))
            val throws = Callback { throw IllegalStateException("consumer failed") }
            val new = ControlRemote(host.binder).register("com.no5.home", throws, ClientState(1, true, true))
            assertEquals(ClientDisposition.REFUSED, host.dispatch(envelope(new)))
            assertNull(host.findReadyClient("com.no5.home", 1))
        }
    }

    @Test fun identityIsCheckedBeforeRegisterPayloadAndPackageClaimCannotBeSpoofed() {
        host().use { host ->
            assertThrows(SecurityException::class.java) {
                ControlRemote(host.binder).register("com.no5.map", Callback(), ClientState(1, true, true))
            }
            assertNull(host.findReadyClient("com.no5.map", 1))
        }
        var capturedUid = -1
        ControlBinderHost("test-session", 0, { uid -> capturedUid = uid; throw SecurityException("denied") }, { 12345 }).use { host ->
            assertThrows(SecurityException::class.java) {
                ControlRemote(host.binder).register("com.no5.home", Callback(), ClientState(1, true, true))
            }
            assertEquals(12345, capturedUid)
        }
    }
}

internal class DeathBinder(private val target: IBinder) : IBinder by target {
    private val recipients = mutableListOf<IBinder.DeathRecipient>()
    private var alive = true
    override fun isBinderAlive(): Boolean = alive
    override fun pingBinder(): Boolean = alive
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipients += recipient }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)
    fun die() { alive = false; recipients.toList().forEach { it.binderDied() } }
}

package com.no5.control

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** Deliberately only bounded primitives: no Bundle, Serializable, arrays or remote classes. */
internal object ControlParcel {
    fun checkSize(parcel: Parcel) { require(parcel.dataSize() <= SystemControlContract.MAX_PARCEL_BYTES) }
    fun end(parcel: Parcel) { require(parcel.dataAvail() == 0) { "Trailing protocol data" } }
    fun int(parcel: Parcel): Int { require(parcel.dataAvail() >= 4); return parcel.readInt() }
    fun long(parcel: Parcel): Long { require(parcel.dataAvail() >= 8); return parcel.readLong() }
    fun bool(parcel: Parcel): Boolean = when (int(parcel)) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Invalid boolean")
    }
    fun string(parcel: Parcel, max: Int): String {
        require(parcel.dataAvail() >= 4)
        val value = requireNotNull(parcel.readString())
        require(value.length in 1..max)
        return value
    }
    fun binder(parcel: Parcel): IBinder {
        require(parcel.dataAvail() >= 4)
        return requireNotNull(parcel.readStrongBinder())
    }
    fun version(parcel: Parcel) { require(int(parcel) == SystemControlContract.PROTOCOL) }
    fun writeState(parcel: Parcel, state: ClientState) {
        parcel.writeLong(state.bindingsEpoch)
        parcel.writeInt(if (state.resumed) 1 else 0)
        parcel.writeInt(if (state.focused) 1 else 0)
        parcel.writeInt(state.claimedInputs.fold(0) { mask, input -> mask or (1 shl (input.wireCode - 1)) })
    }
    fun readState(parcel: Parcel): ClientState {
        val epoch = long(parcel); val resumed = bool(parcel); val focused = bool(parcel); val mask = int(parcel)
        require(mask >= 0 && mask ushr NormalizedKnobInput.values().size == 0) { "Invalid input lease mask" }
        return ClientState(epoch, resumed, focused, NormalizedKnobInput.values().filterTo(linkedSetOf()) {
            mask and (1 shl (it.wireCode - 1)) != 0
        })
    }
    fun writeAdmission(parcel: Parcel, state: ClientAdmission) {
        parcel.writeInt(SystemControlContract.PROTOCOL)
        parcel.writeString(state.controllerSession)
        parcel.writeLong(state.clientEpoch)
        parcel.writeLong(state.bindingsEpoch)
        parcel.writeLong(state.foregroundEpoch)
        parcel.writeLong(state.revision)
        parcel.writeInt(if (state.ready) 1 else 0)
    }
    fun readAdmission(parcel: Parcel): ClientAdmission {
        version(parcel)
        return ClientAdmission(string(parcel, 80), long(parcel), long(parcel), long(parcel), long(parcel), bool(parcel))
    }
    fun writeEnvelope(parcel: Parcel, value: KnobEnvelope) {
        parcel.writeInt(value.protocol)
        parcel.writeString(value.controllerSession)
        parcel.writeLong(value.sequence)
        parcel.writeLong(value.foregroundEpoch)
        parcel.writeLong(value.clientEpoch)
        parcel.writeLong(value.bindingsEpoch)
        parcel.writeInt(value.input.wireCode)
        parcel.writeLong(value.issuedAtUptimeMs)
        parcel.writeLong(value.expiresAtUptimeMs)
    }
    fun readEnvelope(parcel: Parcel): KnobEnvelope {
        version(parcel)
        return KnobEnvelope(controllerSession = string(parcel, 80), sequence = long(parcel),
            foregroundEpoch = long(parcel), clientEpoch = long(parcel), bindingsEpoch = long(parcel),
            input = NormalizedKnobInput.fromWire(int(parcel)), issuedAtUptimeMs = long(parcel),
            expiresAtUptimeMs = long(parcel))
    }
}

internal inline fun <T> transact(
    binder: IBinder, descriptor: String, code: Int,
    write: (Parcel) -> Unit, read: (Parcel) -> T,
): T {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(descriptor)
        write(data)
        ControlParcel.checkSize(data)
        if (!binder.transact(code, data, reply, 0)) throw RemoteException("Unsupported control transaction")
        ControlParcel.checkSize(reply)
        reply.readException()
        val result = read(reply)
        ControlParcel.end(reply)
        return result
    } finally {
        reply.recycle()
        data.recycle()
    }
}

internal class ControlRemote(private val binder: IBinder) {
    fun register(packageName: String, callback: IBinder, state: ClientState): ClientAdmission =
        transact(binder, SystemControlContract.SERVICE_DESCRIPTOR, SystemControlContract.REGISTER, {
            it.writeInt(SystemControlContract.PROTOCOL)
            it.writeString(packageName)
            it.writeStrongBinder(callback)
            ControlParcel.writeState(it, state)
        }, ControlParcel::readAdmission)

    fun state(session: String, clientEpoch: Long, callback: IBinder, state: ClientState): ClientAdmission =
        transact(binder, SystemControlContract.SERVICE_DESCRIPTOR, SystemControlContract.STATE, {
            it.writeInt(SystemControlContract.PROTOCOL)
            it.writeString(session)
            it.writeLong(clientEpoch)
            it.writeStrongBinder(callback)
            ControlParcel.writeState(it, state)
        }, ControlParcel::readAdmission)

    fun deregister(session: String, clientEpoch: Long, callback: IBinder): Boolean =
        transact(binder, SystemControlContract.SERVICE_DESCRIPTOR, SystemControlContract.DEREGISTER, {
            it.writeInt(SystemControlContract.PROTOCOL)
            it.writeString(session)
            it.writeLong(clientEpoch)
            it.writeStrongBinder(callback)
        }, ControlParcel::bool)
}

internal class CallbackRemote(private val binder: IBinder) {
    fun state(value: ClientAdmission): Boolean =
        transact(binder, SystemControlContract.CALLBACK_DESCRIPTOR, SystemControlContract.CALLBACK_STATE,
            { ControlParcel.writeAdmission(it, value) }, ControlParcel::bool)

    fun input(value: KnobEnvelope): ClientDisposition =
        transact(binder, SystemControlContract.CALLBACK_DESCRIPTOR, SystemControlContract.CALLBACK_INPUT,
            { ControlParcel.writeEnvelope(it, value) }, { ClientDisposition.fromWire(ControlParcel.int(it)) })
}

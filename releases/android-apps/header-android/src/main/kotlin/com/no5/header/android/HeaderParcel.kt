package com.no5.header.android

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.no5.header.*

/** Only bounded primitives, enum names and one explicit death Binder. No Bundle/Parcelable classes. */
internal object HeaderParcel {
    fun size(p: Parcel) { require(p.dataSize() <= HeaderAndroidContract.MAX_PARCEL_BYTES) }
    fun end(p: Parcel) { require(p.dataAvail() == 0) { "Trailing header protocol data" } }
    fun int(p: Parcel): Int { require(p.dataAvail() >= 4); return p.readInt() }
    fun long(p: Parcel): Long { require(p.dataAvail() >= 8); return p.readLong() }
    fun bool(p: Parcel) = when (int(p)) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid boolean") }
    fun boolean(p: Parcel, v: Boolean) = p.writeInt(if (v) 1 else 0)
    fun version(p: Parcel) { require(int(p) == HeaderAndroidContract.PROTOCOL) }
    fun string(p: Parcel, max: Int): String {
        require(p.dataAvail() >= 4)
        return requireNotNull(p.readString()).also { require(it.length in 1..max) }
    }
    private fun text(p: Parcel, max: Int): String? {
        if (!bool(p)) return null
        require(p.dataAvail() >= 4)
        return requireNotNull(p.readString()).also { require(it.length <= max) }
    }
    private fun text(p: Parcel, v: String?, max: Int) {
        require(v == null || v.length <= max); boolean(p, v != null); if (v != null) p.writeString(v)
    }
    fun binder(p: Parcel): IBinder { require(p.dataAvail() >= 4); return requireNotNull(p.readStrongBinder()) }
    private inline fun <reified T : Enum<T>> enum(p: Parcel): T {
        val name = string(p, 32)
        return enumValues<T>().singleOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown header enum")
    }
    private fun enumeration(p: Parcel, v: Enum<*>) = p.writeString(v.name)
    private fun nullableLong(p: Parcel): Long? = if (bool(p)) long(p) else null
    private fun nullableLong(p: Parcel, v: Long?) { boolean(p, v != null); if (v != null) p.writeLong(v) }

    fun writePage(p: Parcel, v: HeaderPageState) {
        enumeration(p, v.pageId); p.writeLong(v.pageGeneration); text(p, v.sourceSessionId, 256)
        boolean(p, v.resumed); boolean(p, v.focused)
    }
    fun readPage(p: Parcel) = HeaderPageState(enum(p), long(p), text(p, 256), bool(p), bool(p))
    fun writeAdmission(p: Parcel, v: HeaderAdmission) {
        p.writeInt(HeaderAndroidContract.PROTOCOL); p.writeString(v.controllerSession); p.writeString(v.producerSession)
        p.writeLong(v.processGeneration); p.writeString(v.scopeToken); p.writeLong(v.foregroundEpoch)
        enumeration(p, v.pageId); p.writeLong(v.pageGeneration); text(p, v.sourceSessionId, 256)
        p.writeLong(v.revision); boolean(p, v.ready)
    }
    fun readAdmission(p: Parcel): HeaderAdmission {
        version(p)
        return HeaderAdmission(string(p, 80), string(p, 80), long(p), string(p, 80), long(p),
            enum(p), long(p), text(p, 256), long(p), bool(p))
    }
    private fun policy(p: Parcel, v: HeaderFreshness) { p.writeLong(v.staleAfterMs); p.writeLong(v.lostAfterMs) }
    private fun policy(p: Parcel) = HeaderFreshness(long(p), long(p)).also {
        require(it.staleAfterMs > 0 && it.lostAfterMs >= it.staleAfterMs)
    }
    private fun measurement(p: Parcel, v: HeaderMeasurement?) {
        boolean(p, v != null); if (v == null) return
        boolean(p, v.value != null); v.value?.let(p::writeDouble)
        enumeration(p, v.unit); text(p, v.sourceId, 256); nullableLong(p, v.sourceObservedAtEpochMs)
        enumeration(p, v.quality); enumeration(p, v.provenance); policy(p, v.freshness)
    }
    private fun measurement(p: Parcel): HeaderMeasurement? {
        if (!bool(p)) return null
        val value = if (bool(p)) { require(p.dataAvail() >= 8); p.readDouble() } else null
        // IEEE values are preserved. Existing HeaderMeasurement.at sanitizes non-finite input.
        return HeaderMeasurement(value, enum(p), text(p, 256), nullableLong(p), enum(p), enum(p), policy(p))
    }
    fun writeSnapshot(p: Parcel, v: HeaderSnapshotV1) {
        p.writeInt(v.schemaVersion)
        val b = v.publication
        p.writeString(b.packageName); p.writeString(b.producerSession); p.writeLong(b.foregroundEpoch)
        p.writeLong(b.pageGeneration); text(p, b.sourceSessionId, 256); p.writeLong(b.sequence)
        p.writeLong(b.publishedAtElapsedMs); p.writeLong(b.expiresAtElapsedMs)
        enumeration(p, v.pageId); enumeration(p, v.theme); enumeration(p, v.profile)
        text(p, v.context.title, 128); text(p, v.context.subtitle, 256); text(p, v.context.pageStatus, 512)
        text(p, v.source.label, 128); text(p, v.source.detail, 512); text(p, v.source.sourceId, 256)
        text(p, v.source.backendSessionId, 256); enumeration(p, v.source.transport)
        enumeration(p, v.source.quality); enumeration(p, v.source.provenance); policy(p, v.source.freshness)
        nullableLong(p, v.sourceObservedAtEpochMs)
        measurement(p, v.extras.portRpm); measurement(p, v.extras.starboardRpm); measurement(p, v.extras.sog)
        size(p)
    }
    fun readSnapshot(p: Parcel): HeaderSnapshotV1 {
        val schema = int(p); require(schema == 1)
        val b = HeaderPublication(string(p, 128), string(p, 80), long(p), long(p), text(p, 256), long(p), long(p), long(p))
        val page = enum<HeaderPage>(p); val theme = enum<HeaderTheme>(p); val profile = enum<HeaderProfile>(p)
        val context = HeaderContext(text(p, 128), text(p, 256), text(p, 512))
        val source = HeaderSource(text(p, 128), text(p, 512), text(p, 256), text(p, 256), enum(p), enum(p), enum(p), policy(p))
        return HeaderSnapshotV1(b, page, theme, profile, context, source, nullableLong(p),
            HeaderExtras(measurement(p), measurement(p), measurement(p)), schema)
    }
    fun writeResult(p: Parcel, v: HeaderPublishResult) { boolean(p, v.accepted); enumeration(p, v.reason) }
    fun readResult(p: Parcel) = HeaderPublishResult(bool(p), enum<HeaderClearReason>(p))
}

internal inline fun <T> headerTransact(binder: IBinder, descriptor: String, code: Int,
    write: (Parcel) -> Unit, read: (Parcel) -> T): T {
    val data = Parcel.obtain(); val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(descriptor); write(data); HeaderParcel.size(data)
        if (!binder.transact(code, data, reply, 0)) throw RemoteException("Unsupported header transaction")
        HeaderParcel.size(reply); reply.readException()
        return read(reply).also { HeaderParcel.end(reply) }
    } finally { reply.recycle(); data.recycle() }
}

internal class HeaderRemote(private val binder: IBinder) {
    fun register(packageName: String, callback: IBinder, state: HeaderPageState) =
        headerTransact(binder, HeaderAndroidContract.DESCRIPTOR, HeaderAndroidContract.REGISTER, {
            it.writeInt(1); it.writeString(packageName); it.writeStrongBinder(callback); HeaderParcel.writePage(it, state)
        }, HeaderParcel::readAdmission)
    fun state(v: HeaderAdmission, callback: IBinder, page: HeaderPageState) =
        headerTransact(binder, HeaderAndroidContract.DESCRIPTOR, HeaderAndroidContract.STATE, {
            HeaderParcel.writeAdmission(it, v); it.writeStrongBinder(callback); HeaderParcel.writePage(it, page)
        }, HeaderParcel::readAdmission)
    fun publish(v: HeaderAdmission, snapshot: HeaderSnapshotV1) =
        headerTransact(binder, HeaderAndroidContract.DESCRIPTOR, HeaderAndroidContract.PUBLISH, {
            HeaderParcel.writeAdmission(it, v); HeaderParcel.writeSnapshot(it, snapshot)
        }, HeaderParcel::readResult)
    fun deregister(v: HeaderAdmission, callback: IBinder) =
        headerTransact(binder, HeaderAndroidContract.DESCRIPTOR, HeaderAndroidContract.DEREGISTER, {
            HeaderParcel.writeAdmission(it, v); it.writeStrongBinder(callback)
        }, HeaderParcel::bool)
    fun admission(v: HeaderAdmission, callback: IBinder) =
        headerTransact(binder, HeaderAndroidContract.DESCRIPTOR, HeaderAndroidContract.ADMISSION, {
            HeaderParcel.writeAdmission(it, v); it.writeStrongBinder(callback)
        }, HeaderParcel::readAdmission)
}

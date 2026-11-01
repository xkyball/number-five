package com.no5.control

import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ControlContractTest {
    @Test fun allEightInputsRoundTripAndUnknownValuesAreRefused() {
        assertEquals(8, NormalizedKnobInput.values().size)
        for (input in NormalizedKnobInput.values()) {
            val original = KnobEnvelope(controllerSession = "session-1", sequence = 1,
                foregroundEpoch = 3, clientEpoch = 4, bindingsEpoch = 5, input = input)
            val parcel = Parcel.obtain()
            try {
                ControlParcel.writeEnvelope(parcel, original)
                parcel.setDataPosition(0)
                assertEquals(original, ControlParcel.readEnvelope(parcel))
                ControlParcel.end(parcel)
            } finally { parcel.recycle() }
        }
        assertThrows(IllegalArgumentException::class.java) { NormalizedKnobInput.fromWire(9) }
        assertThrows(IllegalArgumentException::class.java) { ClientDisposition.fromWire(9) }
    }

    @Test fun onlySingleSameUserSignaturePermissionProductIdentityIsAdmitted() {
        assertEquals("com.no5.home", CallerAdmissionPolicy.verify(10001, 0,
            listOf("com.no5.home"), true, true).packageName)
        val refused = listOf(
            { CallerAdmissionPolicy.verify(10001, 0, emptyList(), true, true) },
            { CallerAdmissionPolicy.verify(10001, 0, listOf("com.no5.home", "com.no5.map"), true, true) },
            { CallerAdmissionPolicy.verify(10001, 0, listOf("unrelated.app"), true, true) },
            { CallerAdmissionPolicy.verify(110001, 0, listOf("com.no5.home"), true, true) },
            { CallerAdmissionPolicy.verify(10001, 0, listOf("com.no5.home"), false, true) },
            { CallerAdmissionPolicy.verify(10001, 0, listOf("com.no5.home"), true, false) },
        )
        refused.forEach { check -> assertThrows(SecurityException::class.java) { check() } }
    }

    @Test fun protocolTruncationTrailingFieldsAndOversizeAreRefused() {
        val p = Parcel.obtain()
        try {
            p.writeInt(1); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ControlParcel.readEnvelope(p) }
            p.setDataSize(0); p.setDataPosition(0); p.writeInt(2); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ControlParcel.readEnvelope(p) }
            p.setDataSize(0); p.setDataPosition(0); p.writeInt(123); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { ControlParcel.end(p) }
            p.setDataSize(4096)
            assertThrows(IllegalArgumentException::class.java) { ControlParcel.checkSize(p) }
        } finally { p.recycle() }
        assertThrows(IllegalArgumentException::class.java) {
            KnobEnvelope(controllerSession = "x".repeat(81), sequence = 1, foregroundEpoch = 1,
                clientEpoch = 1, bindingsEpoch = 1, input = NormalizedKnobInput.LEFT_CW)
        }
    }
}

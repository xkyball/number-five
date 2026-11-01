package com.no5.header.android

import android.os.Parcel
import com.no5.header.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class HeaderParcelTest {
    @Test fun dashboardPageUsesAnAdditiveExplicitNameAndRetainsItsScope() {
        val value = HeaderPageState(HeaderPage.DASHBOARD, 9, "fixture-source", true, true)
        val p = Parcel.obtain()
        try {
            HeaderParcel.writePage(p, value)
            p.setDataPosition(0)
            assertEquals("DASHBOARD", p.readString())
            p.setDataPosition(0)
            assertEquals(value, HeaderParcel.readPage(p))
            HeaderParcel.end(p)
        } finally { p.recycle() }
    }

    @Test fun allHeaderFieldsAndOriginalFieldTimesRoundTripWithoutRefreshingTelemetry() {
        val value = HeaderSnapshotV1(HeaderPublication(HeaderApps.HELM, "producer", 3, 9, "client-source", 7, 100, 1500),
            HeaderPage.AUDIO, HeaderTheme.RED_NIGHT, HeaderProfile.MARINE_SAFETY,
            HeaderContext("AUDIO", "source subtitle", "pending"),
            HeaderSource("Pi", "Original detail", "sensor-root", "backend-server", HeaderTransport.READY,
                HeaderQuality.STALE, HeaderProvenance.SIMULATED, HeaderFreshness(123, 456)), 10000,
            HeaderExtras(HeaderMeasurement(0.0, HeaderUnit.RPM, "port-sensor", 9000, HeaderQuality.LIVE, HeaderProvenance.REAL),
                HeaderMeasurement(null, HeaderUnit.RPM, "starboard", 8000, HeaderQuality.UNKNOWN),
                HeaderMeasurement(8.75, HeaderUnit.KNOTS, "gps", 9500, HeaderQuality.STALE, HeaderProvenance.REAL)))
        val p = Parcel.obtain()
        try { HeaderParcel.writeSnapshot(p, value); assertTrue(p.dataSize() <= 8192); p.setDataPosition(0)
            assertEquals(value, HeaderParcel.readSnapshot(p)); HeaderParcel.end(p)
        } finally { p.recycle() }
    }
    @Test fun unknownEnumTruncatedPrimitiveAndOversizedFrameAreRejected() {
        val p = Parcel.obtain()
        try {
            p.writeString("FAKE_PAGE"); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { HeaderParcel.readPage(p) }
            p.setDataSize(0); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { HeaderParcel.long(p) }
            p.writeString("x".repeat(8192))
            assertThrows(IllegalArgumentException::class.java) { HeaderParcel.size(p) }
        } finally { p.recycle() }
    }
    @Test fun nullAndNonfiniteMeasurementsRemainDistinctUntilExistingContractSanitizes() {
        val v = HeaderSnapshotV1(HeaderPublication(HeaderApps.HELM, "producer", 1, 1, null, 0, 0, 1000),
            HeaderPage.AUDIO, HeaderTheme.DAY, HeaderProfile.MARINE_SAFETY,
            extras = HeaderExtras(HeaderMeasurement(Double.NaN, HeaderUnit.RPM, "sensor", 100, HeaderQuality.LIVE),
                HeaderMeasurement(0.0, HeaderUnit.RPM, "sensor", 100, HeaderQuality.LIVE)))
        val p = Parcel.obtain()
        try { HeaderParcel.writeSnapshot(p, v); p.setDataPosition(0)
            val restored = HeaderParcel.readSnapshot(p)
            assertTrue(restored.extras.portRpm!!.value!!.isNaN())
            assertNull(restored.at(100).extras.portRpm!!.value)
            assertEquals(HeaderQuality.INVALID, restored.at(100).extras.portRpm!!.quality)
            assertEquals(0.0, restored.at(100).extras.starboardRpm!!.value!!, 0.0)
        } finally { p.recycle() }
    }
}

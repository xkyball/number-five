package com.no5.preparation.ui

import org.junit.Assert.*
import org.junit.Test

class LayersSettingsTest {
    private fun layers() = LayersState(layers = frozenListOf(
        LayerOption("base", "Basiskarte", true, true, "Fest aktiviert", locked = true),
        LayerOption("ais", "AIS-Marker", true, true, "Nur Anzeige"),
        LayerOption("radar", "Radar", false, false, "Kein Provider"),
    ))
    @Test fun lockedAndUnsupportedLayersCannotBeChanged() {
        val state = layers()
        assertEquals(state.layers, reduceLayers(state, LayersEvent.SetVisible("base", false)).layers)
        assertEquals(state.layers, reduceLayers(state, LayersEvent.SetVisible("radar", true)).layers)
        assertEquals(1L, reduceLayers(state, LayersEvent.SetVisible("radar", true)).nextRequestId)
    }
    @Test fun layerRequestDoesNotBecomeEffectiveWithoutMatchingAcknowledgment() {
        val pending = reduceLayers(layers(), LayersEvent.SetVisible("ais", false))
        assertTrue(pending.layers[1].effective)
        assertEquals(false, pending.layers[1].requested)
        assertEquals(pending, reduceLayers(pending, LayersEvent.Acknowledge("ais", 9, true)))
        val confirmed = reduceLayers(pending, LayersEvent.Acknowledge("ais", 1, true))
        assertFalse(confirmed.layers[1].effective)
        assertNull(confirmed.layers[1].pendingRequestId)
        assertEquals(confirmed, reduceLayers(confirmed, LayersEvent.Acknowledge("ais", 1, true)))
    }
    @Test fun rejectedLayerRequestPreservesEffectiveState() {
        val pending = reduceLayers(layers(), LayersEvent.SetVisible("ais", false))
        val rejected = reduceLayers(pending, LayersEvent.Acknowledge("ais", 1, false, "Nicht möglich"))
        assertTrue(rejected.layers[1].effective)
        assertNull(rejected.layers[1].requested)
        assertEquals("Nicht möglich", rejected.notice)
    }
    @Test fun paletteRequestsAreExplicitAndRejectStaleAcks() {
        val pending = reduceSettings(SettingsState(), SettingsEvent.RequestPalette(UiPalette.RED_NIGHT))
        assertEquals(UiPalette.DAY, pending.palette)
        assertEquals(pending, reduceSettings(pending, SettingsEvent.AcknowledgePalette(90, true)))
        val rejected = reduceSettings(pending, SettingsEvent.AcknowledgePalette(1, false))
        assertEquals(UiPalette.DAY, rejected.palette)
        val retry = reduceSettings(rejected, SettingsEvent.RequestPalette(UiPalette.RED_NIGHT))
        assertEquals(2L, retry.paletteRequestId)
        assertEquals(retry, reduceSettings(retry, SettingsEvent.AcknowledgePalette(1, true)))
        assertEquals(UiPalette.RED_NIGHT, reduceSettings(retry, SettingsEvent.AcknowledgePalette(2, true)).palette)
    }
    @Test fun cogUpNeedsCapabilityAndMovementIncludingAtAckTime() {
        val unsupported = reduceSettings(SettingsState(movementReliable = true), SettingsEvent.RequestOrientation(MapOrientation.COG_UP))
        assertNull(unsupported.orientationRequestId)
        val stopped = reduceSettings(SettingsState(cogUpSupported = true), SettingsEvent.RequestOrientation(MapOrientation.COG_UP))
        assertNull(stopped.orientationRequestId)
        val pending = reduceSettings(SettingsState(cogUpSupported = true, movementReliable = true), SettingsEvent.RequestOrientation(MapOrientation.COG_UP))
        assertEquals(pending, reduceSettings(pending, SettingsEvent.AcknowledgeOrientation(4, true)))
        assertEquals(MapOrientation.COG_UP, reduceSettings(pending, SettingsEvent.AcknowledgeOrientation(1, true)).orientation)
        assertEquals(MapOrientation.NORTH_UP, reduceSettings(pending.copy(movementReliable = false), SettingsEvent.AcknowledgeOrientation(1, true)).orientation)
    }
    @Test fun unitsAreIndependentAndDimmingHasBounds() {
        val distance = reduceSettings(SettingsState(), SettingsEvent.SetDistanceUnit(DistanceUnit.KILOMETRES))
        assertEquals(DistanceUnit.KILOMETRES, distance.distanceUnit)
        assertEquals(SpeedUnit.KNOTS, distance.speedUnit)
        val speed = reduceSettings(distance, SettingsEvent.SetSpeedUnit(SpeedUnit.KILOMETRES_PER_HOUR))
        assertEquals(SpeedUnit.KILOMETRES_PER_HOUR, speed.speedUnit)
        assertEquals(DistanceUnit.KILOMETRES, speed.distanceUnit)
        assertEquals(100, reduceSettings(speed, SettingsEvent.SetUiDimming(0)).uiDimmingPercent)
        assertEquals(30, reduceSettings(speed, SettingsEvent.SetUiDimming(30)).uiDimmingPercent)
    }
}

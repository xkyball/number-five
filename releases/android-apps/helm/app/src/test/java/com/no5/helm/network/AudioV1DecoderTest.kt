package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class AudioV1DecoderTest {
    @Test fun canonicalWireUsesRealNetworkFreshnessAndTrustedArtwork() {
        val decoded = AudioV1Decoder.state(wireFixture("audio"))
        assertEquals(OperatingMode.LIVE, decoded.state.mode)
        assertTrue(decoded.state.source.quality.simulated); assertFalse(decoded.state.source.quality.demo)
        assertEquals("tidal-lines", decoded.state.media.artworkId)
        assertEquals(VolumeAuthority.CAMILLA_DSP_MASTER, decoded.state.authority)
        assertEquals(listOf("Master", "Aux 1", "Aux 2", "Aux 3", "Aux 4"), decoded.state.dsp.faderControls.map { it.label })
        assertEquals(listOf(0, 1, 2, 3, 4), decoded.state.dsp.faderControls.map { it.physicalIndex })
        assertEquals("sim-stereo-4-1", decoded.state.dsp.presets.single().id)
        assertFalse(decoded.state.dsp.meters!!.routingVerified)
        assertEquals(TelemetryStatus.LOST, decoded.state.withAudioFreshness(decoded.timestampEpochMs + 30_001).source.quality.status)
    }
    @Test fun realGatewayUsesIdenticalSchemaAndDecoder() {
        val root = JSONObject(wireFixture("audio")).put("source", "VESSEL_PI_01").put("sourceKind", "REAL").put("simulated", false)
        listOf(root.getJSONObject("audioSource").getJSONObject("quality"), root.getJSONObject("dsp").getJSONObject("quality"), root.getJSONObject("dsp").getJSONObject("meters").getJSONObject("quality")).forEach {
            it.put("source", "VESSEL_PI_01").put("simulated", false)
        }
        val state = AudioV1Decoder.state(root.toString()).state
        assertFalse(state.source.quality.simulated); assertEquals("VESSEL_PI_01", state.source.quality.source)
        assertTrue(AudioModel.available(state, AudioCommand.Transport(TransportAction.NEXT)))
    }
    @Test fun unknownFieldsRemainUnknownAndSourceDspFreshnessStaysIndependent() {
        val root = JSONObject(wireFixture("audio"))
        val stamp = root.getLong("timestampEpochMs")
        val media = root.getJSONObject("media")
        for (field in listOf("title", "trackId", "artworkId", "album", "durationMs", "positionMs", "shuffle", "repeat")) media.put(field, JSONObject.NULL)
        val dsp = root.getJSONObject("dsp")
        dsp.put("preset", JSONObject.NULL).put("hardwareAvailable", JSONObject.NULL).put("clippedSamples", JSONObject.NULL).put("meters", JSONObject.NULL)
        dsp.getJSONObject("quality").put("timestampEpochMs", stamp - 3000)
        root.put("limits", JSONObject.NULL)
        dsp.remove("zoneControls")
        dsp.remove("faderControls")
        dsp.remove("presets")
        for (channel in AudioChannel.entries.filter { it!=AudioChannel.FRONT }) dsp.getJSONObject("faders").getJSONObject(channel.name).put("db", JSONObject.NULL)
        val state = AudioV1Decoder.state(root.toString()).state.withAudioFreshness(stamp)
        assertNull(state.media.durationMs); assertNull(state.media.positionMs); assertNull(state.media.title)
        assertNull(state.dsp.faders.getValue(AudioChannel.MASTER).db); assertNull(state.dsp.hardwareAvailable)
        assertNull(state.dsp.meters); assertNull(state.limits)
        assertEquals(TelemetryStatus.LIVE, state.source.quality.status)
        assertEquals(TelemetryStatus.STALE, state.dsp.quality.status)
        assertFalse(AudioModel.available(state, AudioCommand.Fader(AudioChannel.MASTER, -30.0)))
    }
    @Test fun unsupportedArtworkNeverBecomesAnExternalFetch() {
        val root = JSONObject(wireFixture("audio")); root.getJSONObject("media").put("artworkId", "https://untrusted.example/cover.jpg")
        assertNull(AudioV1Decoder.state(root.toString()).state.media.artworkId)
    }
    @Test fun physicalCatalogIsLabelledByGatewayAndReadOnlyWithoutDeclaredLimits() {
        val root=JSONObject(wireFixture("audio"))
        root.put("source", "LYSMARINE_CAMILLADSP").put("sourceKind", "REAL").put("simulated", false).put("limits", JSONObject.NULL)
        val dsp=root.getJSONObject("dsp")
        listOf(root.getJSONObject("audioSource").getJSONObject("quality"),dsp.getJSONObject("quality"),dsp.getJSONObject("meters").getJSONObject("quality")).forEach { it.put("source","LYSMARINE_CAMILLADSP").put("simulated",false) }
        dsp.getJSONArray("faderControls").let { controls -> repeat(controls.length()) { controls.getJSONObject(it).put("limit",JSONObject.NULL) } }
        root.getJSONObject("capabilities").put("faderWrite",false).put("mute",false).put("presetWrite",false)
        val state=AudioV1Decoder.state(root.toString()).state
        assertEquals("Aux 1",state.dsp.faderControls.single { it.physicalIndex==1 }.label)
        assertNull(state.dsp.faderControls.single { it.gatewayId==AudioChannel.MASTER }.limit)
        assertEquals("sim-stereo-4-1",state.dsp.presets.single().id)
        assertFalse(AudioModel.available(state,AudioCommand.Fader(AudioChannel.MASTER,-25.0)))
        assertFalse(AudioModel.available(state,AudioCommand.Preset("sim-stereo-4-1")))
    }
    @Test fun partialLegacyLimitsRemainCompatibleWithThePhysicalCatalog() {
        val root=JSONObject(wireFixture("audio"));val dsp=root.getJSONObject("dsp")
        val limits=root.getJSONObject("limits")
        listOf("SUB","REAR","AUX3","AUX4").forEach(limits::remove)
        for (index in 1 until dsp.getJSONArray("faderControls").length()) {
            dsp.getJSONArray("faderControls").getJSONObject(index).put("limit",JSONObject.NULL)
        }
        root.getJSONObject("capabilities").put("faderWrite",false).put("mute",false).put("presetWrite",false)
        val state=AudioV1Decoder.state(root.toString()).state
        assertEquals(FaderLimit(-60.0,-6.0,1.0),state.limits!![AudioChannel.MASTER])
        assertNull(state.limits!![AudioChannel.SUB])
        assertNotNull(state.dsp.faderControls.single { it.gatewayId==AudioChannel.MASTER }.limit)
        assertNull(state.dsp.faderControls.single { it.gatewayId==AudioChannel.SUB }.limit)
    }
    @Test fun malformedPhysicalCatalogIsRejectedRatherThanGuessingZonesOrPresets() {
        val duplicateIndex=JSONObject(wireFixture("audio"));duplicateIndex.getJSONObject("dsp").getJSONArray("faderControls").getJSONObject(1).put("index",0)
        assertFailsWith<PiProtocolException>{AudioV1Decoder.state(duplicateIndex.toString())}
        val mismatch=JSONObject(wireFixture("audio"));mismatch.getJSONObject("dsp").getJSONArray("faderControls").getJSONObject(1).put("label","")
        assertFailsWith<PiProtocolException>{AudioV1Decoder.state(mismatch.toString())}
        val unsafePreset=JSONObject(wireFixture("audio"));unsafePreset.getJSONObject("dsp").getJSONArray("presets").getJSONObject(0).put("id","not a wire id")
        assertFailsWith<PiProtocolException>{AudioV1Decoder.state(unsafePreset.toString())}
    }
    @Test fun malformedTypesUnitsProvenanceAndMeterBoundsAreRejected() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) }, { it.put("sourceKind", "REAL") }, { it.put("revision", -1) },
            { it.getJSONObject("units").put("meters", "percent") },
            { it.getJSONObject("media").put("positionMs", -1) },
            { it.getJSONObject("capabilities").put("mute", "true") },
            { it.getJSONObject("dsp").getJSONObject("meters").getJSONArray("channels").getJSONObject(0).put("peakDb", 1) },
        )
        changes.forEach { change -> val root = JSONObject(wireFixture("audio")); change(root); assertFailsWith<PiProtocolException> { AudioV1Decoder.state(root.toString()) } }
        assertFailsWith<PiProtocolException> { AudioV1Decoder.state(wireFixture("audio") + " false") }
    }
    @Test fun frontAndMeterGroupsAreExplicitOptionalAndBounded() {
        val root=JSONObject(wireFixture("audio"))
        val current=AudioV1Decoder.state(root.toString()).state
        assertEquals(0.0,current.dsp.faders.getValue(AudioChannel.FRONT).db)
        assertTrue(AudioModel.available(current,AudioCommand.Fader(AudioChannel.FRONT,-3.0)))
        assertEquals(listOf(0,1),current.dsp.meters!!.groups[AudioChannel.FRONT])
        root.getJSONObject("dsp").remove("zoneControls")
        root.getJSONObject("dsp").getJSONObject("meters").remove("groups")
        val legacy=AudioV1Decoder.state(root.toString()).state
        assertFalse(AudioModel.available(legacy,AudioCommand.Mute(AudioChannel.FRONT,true)))
        assertFalse(AudioModel.available(legacy,AudioCommand.Fader(AudioChannel.FRONT,-3.0)))
        assertTrue(legacy.dsp.meters!!.groups.isEmpty())
        val bad=JSONObject(wireFixture("audio"))
        bad.getJSONObject("dsp").getJSONObject("meters").getJSONObject("groups").put("FRONT",org.json.JSONArray(listOf(0,5)))
        assertFailsWith<PiProtocolException>{AudioV1Decoder.state(bad.toString())}
    }

}

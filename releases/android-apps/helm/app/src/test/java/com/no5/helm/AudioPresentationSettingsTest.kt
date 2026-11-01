package com.no5.helm

import com.no5.domain.AudioChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresentationSettingsTest {
    @Test fun aliasesAndVisibilityPersistAcrossAStoreRecreation() {
        val backend = MemoryBackend()
        val first = AndroidAudioPresentationStore(backend)
        val settings = AudioPresentationSettings.DEFAULT
            .update(AudioChannel.MASTER, "Cabin master", true)
            .update(AudioChannel.AUX4, null, false)

        first.save(settings)
        val restored = AndroidAudioPresentationStore(backend).load()

        assertEquals("Cabin master", restored.alias(AudioChannel.MASTER))
        assertTrue(restored.visible(AudioChannel.MASTER))
        assertNull(restored.alias(AudioChannel.AUX4))
        assertFalse(restored.visible(AudioChannel.AUX4))
    }

    @Test fun missingNewControlsUseDefaultsAndTemporarilyRemovedControlsKeepTheirStableIdPreference() {
        val settings = AudioPresentationSettings.DEFAULT.update(AudioChannel.AUX4, "Aft", false)

        // A control absent from a particular gateway snapshot has no local
        // fallback mapping. If it returns under the same stable ID, its local
        // presentation preference applies again.
        assertTrue(settings.visible(AudioChannel.SUB))
        assertNull(settings.alias(AudioChannel.SUB))
        assertFalse(settings.visible(AudioChannel.AUX4))
        assertEquals("Aft", settings.alias(AudioChannel.AUX4))
    }

    @Test fun malformedLocalRecordFailsClosedToDefaults() {
        val store = AndroidAudioPresentationStore(MemoryBackend("1\n1\nMASTER\ttrue\tnot-base64!"))
        val restored = store.load()
        assertEquals(AudioPresentationSettings.DEFAULT, restored)
    }

    @Test fun restoreDefaultsIsAnEmptyPreferenceRecord() {
        val settings = AudioPresentationSettings.DEFAULT.update(AudioChannel.REAR, "Salon", false)
        assertEquals(AudioPresentationSettings.DEFAULT, AudioPresentationSettings.DEFAULT)
        assertTrue(settings != AudioPresentationSettings.DEFAULT)
    }

    private class MemoryBackend(var value: String? = null) : AudioPresentationBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
    }
}

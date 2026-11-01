package com.no5.helm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.no5.domain.*
import com.no5.helm.control.HelmSourceOwner
import com.no5.helm.control.HelmSourceRegistry
import com.no5.helm.control.OwnerFixtureFactory
import com.no5.helm.control.OwnerFixtureStore
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class ServiceSoundPort : AudioSoundPort {
    override val state = MutableSourceFeed(soundFixture())
    override val spectrum = MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results = MutableSourceFeed<AudioSoundResult?>(null)
    var visible = false
    var closed = false
    val commands = mutableListOf<AudioSoundCommand>()
    override fun setEditorVisible(visible: Boolean, eqVisible: Boolean) { this.visible = !closed && visible }
    override fun submit(command: AudioSoundCommand): Long {
        if (closed) return 0
        commands += command; return commands.size.toLong()
    }
    override fun refresh() = Unit
    override fun close() { closed = true; visible = false; state.close(); spectrum.close(); results.close() }
}

/** The real service registry -> source lease -> retained UI controller chain, with explicit unit ports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioSoundServiceWiringTest {
    @After fun clearRegistry() = HelmSourceRegistry.resetForTest()

    @Test fun soundSurvivesBothBorrowingLayersAndTheRetainedUiButRetiresWithItsGeneration() {
        val app = RuntimeEnvironment.getApplication()
        val fixture = OwnerFixtureFactory()
        val ports = mutableListOf<ServiceSoundPort>()
        val factory = SourceSessionFactory { config, identity ->
            val raw = fixture.create(config, identity)
            val sound = ServiceSoundPort().also(ports::add)
            object : No5SourceSession by raw {
                override val audio = object : AudioSourcePort by raw.audio { override val sound = sound }
                override fun close() { sound.close(); raw.close() }
            }
        }
        val owner = HelmSourceOwner(OwnerFixtureStore(), factory)
        HelmSourceRegistry.installForTest(app, owner)
        val sourceModel = SourceSessionViewModel(app)
        val retainedStore = ViewModelStore()
        val retained = ViewModelProvider(retainedStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>) = HelmPresentationViewModel(app) as T
        })[HelmPresentationViewModel::class.java]
        try {
            val lease = sourceModel.session
            val foreground = retained.acquire(lease, AudioPresentationSettings.DEFAULT).audio
            assertSame(ports[0], lease.audio.sound)
            assertSame(ports[0], foreground.soundPort)
            foreground.openSoundEditor(false)
            assertTrue(foreground.soundEditor.observed.available)
            assertTrue(ports[0].visible)
            val borrowed = owner.borrowAudio()
            assertSame(ports[0], borrowed.sound)
            val idle = AudioNativeController(Dispatchers.Unconfined, now = { 10000 }, automaticTicker = false, source = borrowed)
            idle.dispose()
            assertTrue(ports[0].visible)
            assertSame(foreground, retained.acquire(sourceModel.session, AudioPresentationSettings.DEFAULT).audio)

            assertTrue(owner.reload().applied)
            val replacement = retained.acquire(sourceModel.session, AudioPresentationSettings.DEFAULT).audio
            assertNotSame(foreground, replacement)
            assertSame(ports[1], replacement.soundPort)
            assertTrue(ports[0].closed)
            val writes = ports[0].commands.size
            foreground.soundEditor.changePreset(soundFixture().preset!!.copy(name = "Retired draft"))
            assertEquals(writes, ports[0].commands.size)
            replacement.openSoundEditor(false)
            assertTrue(ports[1].visible)
        } finally {
            retainedStore.clear(); owner.close()
        }
    }
}

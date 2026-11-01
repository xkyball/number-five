package com.no5.helm

import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

private class VisibilitySoundPort : AudioSoundPort {
    override val state = MutableSourceFeed(soundFixture())
    override val spectrum = MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results = MutableSourceFeed<AudioSoundResult?>(null)
    var visible = false
    var eqVisible = false
    val visibilityWrites = mutableListOf<Pair<Boolean, Boolean>>()
    val commands = mutableListOf<AudioSoundCommand>()
    override fun setEditorVisible(visible: Boolean, eqVisible: Boolean) {
        this.visible = visible; this.eqVisible = eqVisible
        visibilityWrites += visible to eqVisible
    }
    override fun submit(command: AudioSoundCommand): Long { commands += command; return commands.size.toLong() }
    override fun refresh() = Unit
    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioSoundVisibilityTest {
    @Test fun catalogAndEditorVisibilityDoNotRetireEachOtherDuringNavigation(){
        val sound=VisibilitySoundPort()
        val c=AudioSoundUiController(sound,Dispatchers.Unconfined)
        c.catalogVisibility(true)
        assertTrue(sound.visible);assertFalse(sound.eqVisible)
        c.openPreset();c.catalogVisibility(false)
        assertTrue(sound.visible);assertTrue(sound.eqVisible)
        c.catalogVisibility(true);c.visibility(false)
        assertTrue(sound.visible);assertFalse(sound.eqVisible)
        c.catalogVisibility(false)
        assertFalse(sound.visible)
        c.close();c.catalogVisibility(true)
        assertFalse(sound.visible)
    }
    @Test fun retiringNeverVisibleAudioControllerDoesNotHideTheActualEditor() {
        val sound = VisibilitySoundPort()
        val base = MockAudioSource(now = { 10000L })
        // Same owned adapter, separate consumer leases; retiring a borrower cannot close the raw source.
        fun borrow() = object : AudioSourcePort by base {
            override val sound = sound
            override fun close() = Unit
        }
        val foreground = AudioNativeController(Dispatchers.Unconfined, now = { 10000L }, automaticTicker = false, source = borrow())
        val fallback = AudioNativeController(Dispatchers.Unconfined, now = { 10000L }, automaticTicker = false, source = borrow())
        foreground.openSoundEditor(false)
        assertTrue(sound.visible); assertTrue(sound.eqVisible)
        val writes = sound.visibilityWrites.size
        fallback.dispose()
        assertTrue(sound.visible, "A never-opened fallback controller disabled the visible editor")
        assertEquals(writes, sound.visibilityWrites.size)
        foreground.soundEditor.visibility(false)
        assertFalse(sound.visible)
        foreground.dispose()
    }

    @Test fun inactiveOrRetiredConsumerCannotReleaseOrReacquireAnotherConsumersVisibility() {
        val sound = VisibilitySoundPort()
        val visible = AudioSoundUiController(sound, Dispatchers.Unconfined)
        val idle = AudioSoundUiController(sound, Dispatchers.Unconfined)
        visible.openPreset()
        idle.visibility(false)
        assertTrue(sound.visible, "An idle consumer never owned this visibility")
        idle.close()
        idle.visibility(true)
        val before = sound.commands.size
        idle.changePreset(soundFixture().preset!!.copy(name = "Retired draft"))
        assertEquals(before, sound.commands.size)
        val previousGeneration = idle.observed.sourceGeneration
        sound.state.emit(sound.state.value.copy(sourceGeneration = "replacement", presetRevision = 4))
        assertEquals(previousGeneration, idle.observed.sourceGeneration)
        idle.close()
        assertTrue(sound.visible)
        visible.close()
        assertFalse(sound.visible)
    }
    @Test fun unavailableFooterNeverClaimsConfirmationAndNeverShowsUnboundedErrorText() {
        val sound = VisibilitySoundPort()
        val c = AudioSoundUiController(sound, Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        com.no5.ui.UiLanguage.acceptConfirmed(context, "de")
        val absent = AudioSoundUiController(null, Dispatchers.Unconfined)
        assertEquals("Nicht verfügbar · NO_SOUND_PORT", audioSoundFooter(context, absent))
        absent.close()
        sound.state.emit(sound.state.value.copy(available = false, writable = false, reason = "HTTP_401"))
        assertEquals("Nicht verfügbar · HTTP_401", audioSoundFooter(context, c))
        sound.state.emit(sound.state.value.copy(reason = "private URL, body or credential must never be shown"))
        assertEquals("Nicht verfügbar · UNAVAILABLE", audioSoundFooter(context, c))
        sound.state.emit(sound.state.value.copy(available = true, writable = false, reason = null))
        assertEquals("Letzter bestätigter Stand · nicht bearbeitbar", audioSoundFooter(context, c))
        c.close()
    }

}

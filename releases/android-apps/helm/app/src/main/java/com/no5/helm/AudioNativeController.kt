package com.no5.helm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.no5.domain.*
import kotlinx.coroutines.*
import kotlin.math.roundToLong

/** UI state and scheduling only. Injected adapters own data, capabilities and command lifetimes. */
class AudioNativeController(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val now: () -> Long = System::currentTimeMillis,
    private val automaticTicker: Boolean = true,
    private val automaticVerification: Boolean = true,
    private val source: AudioSourcePort,
    initialPresentation: AudioPresentationSettings = AudioPresentationSettings.DEFAULT,
    eqValidator:EqValidator=NativeEqValidator,
    eqValidationDispatcher:CoroutineDispatcher=Dispatchers.Default,
) {
    internal val soundEditor = AudioSoundUiController(source.sound, dispatcher, now,eqValidator,eqValidationDispatcher)
    var state by mutableStateOf(source.state.value.withAudioFreshness(now(), source.freshnessPolicy))
        private set
    var mediaMetadata by mutableStateOf(source.mediaBrowser?.metadata?.value ?: AudioMediaMetadata())
        private set
    var mediaBrowsing by mutableStateOf(source.mediaBrowser?.browsing?.value ?: AudioBrowseResult())
        private set
    var mediaSelection by mutableStateOf(source.mediaBrowser?.selection?.value ?: AudioMediaSelectionResult())
        private set
    var musicQuery by mutableStateOf(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS))
        private set
    var musicSearch by mutableStateOf("")
    var musicPlaylistTitle by mutableStateOf("")
    var playlistFilter by mutableStateOf("")
    var musicPlaylistItem by mutableStateOf<AudioQueueItem?>(null)
        private set
    private var playlistBitmaps by mutableStateOf<Map<String, Pair<String, Bitmap>>>(emptyMap())
    val filteredPlaylists get() = filterAudioPlaylists(mediaBrowsing.page?.items.orEmpty(),playlistFilter)
    fun openMusicPlaylist(item: AudioQueueItem) {
        if(!mediaAvailable || !item.uri.startsWith("spotify:playlist:") || mediaBrowsing.page?.items?.contains(item) != true) return
        musicPlaylistItem=item; musicPlaylistTitle=item.title.orEmpty()
        browseMedia(AudioBrowseQuery(AudioBrowseKind.PLAYLIST,uri=item.uri))
    }
    fun requestPlaylistArtwork(items: List<AudioQueueItem>) { if(!disposed)source.mediaBrowser?.requestPlaylistArtwork(items) }
    fun playlistBitmap(item: AudioQueueItem): Bitmap? = playlistBitmaps[item.uri]?.takeIf {
        mediaAvailable && it.first==item.playlist?.artwork?.id
    }?.second
    val canStartPlaylist get() = canSelectMedia && !mediaBrowsing.loading && musicQuery.kind==AudioBrowseKind.PLAYLIST && musicQuery.offset==0 &&
        mediaBrowsing.query==musicQuery && mediaBrowsing.page?.receiverSessionId==mediaMetadata.receiverSessionId &&
        !mediaBrowsing.page?.revision.isNullOrBlank() && mediaBrowsing.page?.items?.firstOrNull()?.uri?.let {Regex("spotify:(track|episode):[A-Za-z0-9]{22}").matches(it)}==true
    fun startPlaylist(shuffle: Boolean): Boolean {
        if(!canStartPlaylist) return false
        val uri=musicQuery.uri ?: return false
        pendingMediaIndex=null; pendingMediaQueue=false; pendingMediaQuery=musicQuery
        source.mediaBrowser?.select(AudioMediaSelection("start-${java.util.UUID.randomUUID()}",requireNotNull(mediaMetadata.receiverSessionId),
            requireNotNull(mediaMetadata.queue?.revision),0,uri,musicQuery,mediaBrowsing.page?.revision,shuffle))
        return true
    }
    internal val musicScroll = mutableMapOf<AudioBrowseQuery, Pair<Int, Int>>()
    internal val queueIdle = AudioOverlayIdle(now)
    private var pendingMediaIndex: Int? = null
    private var pendingMediaQueue = false
    private var pendingMediaQuery: AudioBrowseQuery? = null
    internal fun mediaRowPending(index: Int, queue: Boolean) = mediaSelection.status == AudioMediaSelectionStatus.PENDING &&
        index == pendingMediaIndex && queue == pendingMediaQueue && (queue || mediaBrowsing.query == pendingMediaQuery)
    fun showMusicSearch() {
        source.mediaBrowser?.cancelBrowse()
        musicQuery = AudioBrowseQuery(AudioBrowseKind.SEARCH, text = musicSearch.trim().takeIf { it.isNotBlank() })
        if (musicQuery.text != null) browseMedia(musicQuery)
    }
    var mediaBitmap by mutableStateOf<Bitmap?>(null)
        private set
    private var decodedArtwork: AudioArtworkBytes? = null
    private var imageGeneration = 0L
    private var decodeJob: Job? = null
    private var playlistDecodeJob: Job? = null
    val mediaAvailable get() = !disposed && mediaMetadata.available && source.mediaBrowser != null
    val spotifyTrackUri get() = mediaMetadata.trackUri?.takeIf { mediaMetadata.available &&
        state.source.connected && state.media.trackId == it.substringAfterLast(':') }
    val currentMediaBitmap get() = mediaBitmap.takeIf { spotifyTrackUri != null && decodedArtwork?.trackUri == spotifyTrackUri &&
        decodedArtwork?.receiverSessionId == mediaMetadata.receiverSessionId && decodedArtwork?.id == mediaMetadata.artwork?.id }
    fun browseMedia(query: AudioBrowseQuery) { if (!disposed) { musicQuery = query; source.mediaBrowser?.browse(query) } }
    val canSelectMedia get() = mediaAvailable && mediaMetadata.navigationAvailable && mediaMetadata.queue?.revision != null &&
        mediaSelection.status != AudioMediaSelectionStatus.PENDING && state.pending == null && source.faderStream?.active != true
    fun canSelectMediaItem(index: Int, item: AudioQueueItem, queue: Boolean): Boolean {
        if (!canSelectMedia || item.title.isNullOrBlank() || !Regex("spotify:(track|episode):[A-Za-z0-9]{22}").matches(item.uri)) return false
        val query = if (queue) null else mediaBrowsing.query ?: return false
        val page = if (queue) null else mediaBrowsing.page ?: return false
        if (!queue && (mediaBrowsing.loading || page?.receiverSessionId != mediaMetadata.receiverSessionId)) return false
        val rows = if (queue) mediaMetadata.queue?.next else page?.items
        if (rows?.getOrNull(index) != item) return false
        return queue || query==musicQuery
    }
    fun selectMedia(index:Int,item:AudioQueueItem,queue:Boolean):Boolean{
        if(!canSelectMediaItem(index,item,queue))return false
        val query=if(queue)null else mediaBrowsing.query
        val page=if(queue)null else mediaBrowsing.page
        queueIdle.activity()
        pendingMediaIndex = index; pendingMediaQueue = queue; pendingMediaQuery = query
        source.mediaBrowser?.select(AudioMediaSelection("select-${java.util.UUID.randomUUID()}", requireNotNull(mediaMetadata.receiverSessionId),
            requireNotNull(mediaMetadata.queue?.revision), index, item.uri, query, page?.revision))
        return true
    }
    var demoRunning by mutableStateOf(false)
        private set
    var panel by mutableStateOf<String?>(null)
        private set
    /** Shared UI guard: both physical and touch navigation preserve dirty local form drafts. */
    var panelNavigationGuard: ((() -> Unit) -> Unit)? = null
    private var panelGeneration = 0L
    internal val panelEpoch get()=panelGeneration
    var selectedOutput by mutableStateOf(0)
        private set
    var preview by mutableStateOf<AudioNativePreview?>(null)
        private set
    // Network writes are non-optimistic.  Keep a released fader target visible
    // only while the gateway is confirming it, so the UI does not appear to
    // snap back before the confirmed model arrives.
    var pendingVisual by mutableStateOf<AudioNativePreview?>(null)
        private set
    var failNextWrite by mutableStateOf(false)
        private set
    var lastOutcome by mutableStateOf<AudioCommandOutcome?>(null)
        private set
    // This is intentionally app-local presentation state. The immutable
    // gatewayId remains the sole command target, so an alias or visibility
    // choice can never reroute a Camilla command to another physical fader.
    var presentation by mutableStateOf(initialPresentation)
        private set
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var writeJob: Job? = null
    private var disposed = false
    private val sessionId = source.identity.sessionId
    private var latestRequestId: String? = source.state.value.pending?.requestId
    private var streamingPreview = false
    @Volatile var presentedRevision = -1L; private set
    fun displayed() { presentedRevision = state.revision }
    private val subscriptions = mutableListOf<SourceSubscription>()
    internal val controls = AudioOwnedControls(this)

    /** Presentation only: never persists a gateway state, command or acknowledgement. */
    fun exportPresentationState() = AudioViewState(selectedOutput)
    fun restorePresentationState(saved: AudioViewState) {
        if (!disposed && saved.version == 1 && saved.selectedPhysicalFader in 0..4) {
            selectedOutput = saved.selectedPhysicalFader
            controls.observe(state = state, output = selectedOutput)
        }
    }
    fun selectedFader(): AudioFaderControl? = faderControls.singleOrNull { it.physicalIndex == selectedOutput }
    fun adjustSelectedFader(steps: Int): Boolean {
        controls.observe(state = state, output = selectedOutput)
        return controls.accepted(controls.adjustSelected(steps))
    }
    fun toggleSelectedMute(): Boolean {
        controls.observe(state = state, output = selectedOutput)
        return controls.accepted(controls.toggleSelected())
    }
    fun togglePlayback(): Boolean = controls.accepted(controls.togglePlayback())


    init {
        require(source.state.value.sessionId == sessionId) { "Audio state belongs to a different source session" }
        subscriptions += source.state.subscribe { next ->
            scope.launch {
                if (!disposed && next.sessionId == sessionId && next.revision >= state.revision) {
                    val pending = next.pending
                    pending?.requestId?.let { latestRequestId = it }
                    state = next.withAudioFreshness(now(), source.freshnessPolicy)
                    if (preview != null && !previewAvailable()) cancelPreview()
                    if (pending == null) pendingVisual = null
                    else if (pendingVisual?.channel != (pending.command as? AudioCommand.Fader)?.channel) pendingVisual = null
                    controls.changed()
                }
            }
        }
        subscriptions += source.outcomes.subscribe { outcome ->
            scope.launch {
                if (!disposed && outcome?.sessionId == sessionId &&
                    (outcome.requestId == null || outcome.requestId == latestRequestId)) {
                    lastOutcome = outcome
                    controls.changed()
                }
            }
        }
        source.demoControls?.let { controls ->
            subscriptions += controls.demoState.subscribe { demo ->
                scope.launch { if (!disposed) { demoRunning = demo.running; failNextWrite = demo.failNextWrite } }
            }
        }
        source.mediaBrowser?.let { media ->
            subscriptions += media.metadata.subscribe { next -> scope.launch {
                if (!disposed) {
                    if (next.receiverSessionId != mediaMetadata.receiverSessionId) {
                        if (panel in listOf("queue", "media")) closePanel()
                        musicSearch = ""; musicPlaylistTitle = ""; musicPlaylistItem=null; playlistFilter=""; playlistBitmaps=emptyMap(); musicQuery = AudioBrowseQuery(AudioBrowseKind.PLAYLISTS); musicScroll.clear()
                    }
                    if (next.receiverSessionId != mediaMetadata.receiverSessionId || next.trackUri != mediaMetadata.trackUri || next.artwork != mediaMetadata.artwork || !next.available) {
                        imageGeneration++; decodeJob?.cancel(); mediaBitmap = null; decodedArtwork = null
                    }
                    mediaMetadata = next
                    if (!next.available) playlistBitmaps=emptyMap()
                    if (!next.available && panel in listOf("queue", "media")) closePanel()
                }
            } }
            subscriptions += media.browsing.subscribe { next -> scope.launch { if (!disposed) mediaBrowsing = next } }
            subscriptions += media.selection.subscribe { next -> scope.launch {
                if (!disposed) {
                    mediaSelection = next; queueIdle.pending = next.status == AudioMediaSelectionStatus.PENDING
                    if (next.status == AudioMediaSelectionStatus.VERIFIED && panel == "queue") closePanel()
                    else if (next.status != AudioMediaSelectionStatus.PENDING) queueIdle.activity()
                }
            } }
            subscriptions += media.playlistArtwork.subscribe { images ->
                playlistDecodeJob?.cancel()
                playlistDecodeJob=scope.launch {
                if(disposed) return@launch
                playlistBitmaps=playlistBitmaps.filter { (uri, bitmap) -> images[uri]?.id==bitmap.first }
                for(image in images.values) {
                    if(disposed || image.receiverSessionId != mediaMetadata.receiverSessionId || !mediaAvailable || playlistBitmaps[image.uri]?.first==image.id) continue
                    val bitmap=withContext(Dispatchers.Default) { decodePlaylistArtwork(image.bytes) } ?: continue
                    if(!disposed && mediaAvailable && image.receiverSessionId==mediaMetadata.receiverSessionId && media.playlistArtwork.value[image.uri]?.id==image.id)
                        playlistBitmaps=playlistBitmaps + (image.uri to (image.id to bitmap))
                }
            } }
            subscriptions += media.artwork.subscribe { image -> scope.launch {
                if (disposed) return@launch
                val attempt = ++imageGeneration; decodeJob?.cancel()
                if (image == null) { mediaBitmap = null; decodedArtwork = null; return@launch }
                val descriptor = mediaMetadata.artwork ?: return@launch
                if (image.receiverSessionId != mediaMetadata.receiverSessionId || image.trackUri != mediaMetadata.trackUri || image.id != descriptor.id) return@launch
                decodeJob = scope.launch {
                    val bitmap = withContext(Dispatchers.Default) { decodeAudioArtwork(image.bytes, descriptor) }
                    if (!disposed && imageGeneration == attempt && image.receiverSessionId == mediaMetadata.receiverSessionId &&
                        image.trackUri == mediaMetadata.trackUri && image.id == mediaMetadata.artwork?.id) {
                        decodedArtwork = image; mediaBitmap = bitmap
                    }
                }
            } }
        }
        if (automaticTicker) scope.launch {
            while (isActive) { delay(100); tickOnce() }
        }
    }

    val sourceIdentity get() = source.identity
    val soundPort get() = source.sound
    val demoAvailable get() = !disposed && source.identity.mode == SourceMode.DEMO && source.demoControls != null
    val demoControlsAvailable get() = demoAvailable
    val isDemo get() = state.mode == OperatingMode.DEMO
    val isSimulation get() = isDemo || state.source.quality.simulated || state.dsp.quality.simulated
    val networkSimulation get() = source.identity.mode == SourceMode.PI && isSimulation
    val provenanceLabel get() = when {
        networkSimulation -> "NETWORK SIMULATION"
        isDemo -> "LOCAL DEMO"
        state.mode == OperatingMode.LIVE -> source.identity.label
        else -> "SOURCE UNAVAILABLE"
    }
    val statusLabel get() = when {
        networkSimulation && state.source.quality.status != TelemetryStatus.LIVE -> "SIM AUDIO · ${state.source.quality.status.name}"
        networkSimulation -> "AUDIO · NETWORK SIMULATION"
        state.mode == OperatingMode.LIVE -> "AUDIO · ${source.identity.label}"
        !isDemo -> "AUDIO UNAVAILABLE"
        state.dsp.hardwareAvailable == false -> "DEMO · USB LOST"
        state.dsp.state == DspState.ERROR -> "DEMO · DSP OFFLINE"
        !state.source.connected -> "DEMO · SOURCE OFFLINE"
        state.pending != null -> "DEMO · VERIFYING"
        demoRunning -> "AUDIO DEMO RUNNING"
        else -> "AUDIO DEMO PAUSED"
    }
    val offlineTargets get() = AudioOfflineTarget.entries.filter { when (it) {
        AudioOfflineTarget.SOURCE -> !state.source.connected
        AudioOfflineTarget.DSP -> state.dsp.state == DspState.ERROR
        AudioOfflineTarget.USB -> state.dsp.hardwareAvailable == false
    }}.toSet()

    fun loadFixture(fixture: AudioFixture) {
        if (!demoAvailable) return
        cancelPreview(); panel = null; source.demoControls?.loadFixture(fixture)
    }
    fun startDemo() { if (demoAvailable) source.demoControls?.start() }
    fun stopDemo() { if (demoAvailable) { cancelPreview(); source.demoControls?.stop() } }
    fun resetDemo() { if (demoAvailable) { cancelPreview(); panel = null; source.demoControls?.reset() } }
    fun dispose() {
        if (disposed) return
        disposed = true; soundEditor.close(); panelNavigationGuard = null; panelGeneration++; controls.close(); imageGeneration++; playlistBitmaps=emptyMap(); mediaBitmap = null; decodedArtwork = null; preview = null; pendingVisual = null; scope.cancel()
        subscriptions.forEach(SourceSubscription::close); subscriptions.clear()
        source.close()
    }
    fun openPanel(name: String, output: Int = selectedOutput, onOpened: (() -> Unit)? = null,
                  stillCurrent: () -> Boolean = { true }) {
        if (disposed || name !in listOf("mixer", "preset", "audio-menu", "media", "queue", "sound-preset", "sound-speakers")) return
        if (panel == name) { onOpened?.invoke(); return }
        val guard = panelNavigationGuard
        val captured = panelGeneration
        val proceed: () -> Unit = {
            if (!disposed && captured == panelGeneration && stillCurrent()) { openPanelConfirmed(name, output); onOpened?.invoke() }
        }
        if (panel != null && guard != null) guard(proceed) else proceed()
    }
    internal fun openSoundEditor(speakers:Boolean,fromRoom:Boolean=false) {
        if(disposed)return
        if(!fromRoom)soundReturnPanel=panel?.takeIf{it in setOf("audio-menu","preset")}
        openPanelConfirmed(if(speakers)"sound-speakers"else"sound-preset",selectedOutput)
        if(speakers)soundEditor.openSpeakers(fromRoom)else soundEditor.openPreset()
    }
    private var soundReturnPanel:String?=null
    private var mixerReturnPanel:String?=null
    internal fun closeSoundPanel() {
        val target=soundReturnPanel;soundReturnPanel=null
        closePanel()
        target?.let{openPanel(it)}
    }
    internal fun closePresentationPanel() {
        val target=mixerReturnPanel;mixerReturnPanel=null
        closePanel()
        target?.let{openPanel(it)}
    }
    private fun openPanelConfirmed(name: String, output: Int) {
        if (disposed) return
        if(name=="mixer")mixerReturnPanel=panel?.takeIf{it=="audio-menu"}
        else if(panel=="mixer")mixerReturnPanel=null
        panelGeneration++
        selectedOutput = output.coerceIn(0, 4)
        controls.observe(state = state, output = selectedOutput)
        cancelPreview(); panel = name
        queueIdle.activity()
        if (name == "media" && (musicQuery.kind != AudioBrowseKind.SEARCH || !musicQuery.text.isNullOrBlank())) browseMedia(musicQuery)
    }
    /** The caller has already confirmed discard/save, or the source is being forcibly retired. */
    fun closePanel() {
        panelGeneration++
        soundReturnPanel=null;mixerReturnPanel=null
        cancelPreview()
        if (panel == "media") source.mediaBrowser?.cancelBrowse()
        panel = null; queueIdle.resetInteraction()
    }
    fun failNextDemoWrite() { if (demoAvailable) source.demoControls?.failNextWrite() }
    fun can(command: AudioCommand) = !disposed && state.pending == null &&
        (command !is AudioCommand.Transport || mediaSelection.status != AudioMediaSelectionStatus.PENDING) && source.available(command)
    /** Retain capability styling during an ACK wait; input admission still uses [can]. */
    internal fun canPresent(command: AudioCommand): Boolean {
        if (state.pending == null && source.faderStream?.active != true) return can(command)
        if (disposed || AudioCommandGate.reason(state.withAudioFreshness(now(), source.freshnessPolicy), command) != null) return false
        return when (command) {
            is AudioCommand.Fader -> faderControl(command.channel)?.let { control ->
                control.db?.isFinite() == true && control.limit?.let { command.valueDb in it.minDb..it.maxDb } == true
            } == true
            is AudioCommand.Mute -> faderControl(command.channel) != null
            is AudioCommand.Preset -> presets.any { it.id == command.id }
            is AudioCommand.Equalizer -> state.dsp.equalizer?.admits(command) == true && state.capabilities.equalizer
            is AudioCommand.Transport -> command.action != TransportAction.SEEK || state.media.durationMs != null
        }
    }
    internal fun canPresentAdjust(control: AudioFaderControl): Boolean {
        val limit = control.limit ?: return false
        val current = control.db?.takeIf(Double::isFinite) ?: return false
        return limit.maxDb > limit.minDb && canPresent(AudioCommand.Fader(control.gatewayId, limit.clamp(current)))
    }
    /** Gateway supplied physical Camilla controls, sorted by their actual fader index. */
    val faderControls get() = state.dsp.faderControls.sortedBy { it.physicalIndex }
    /** Current gateway controls after the user's local visibility choices. */
    val visibleFaderControls get() = faderControls.filter(::isVisible)
    val presets get() = state.dsp.presets
    fun faderControl(channel: AudioChannel) = faderControls.singleOrNull { it.gatewayId == channel }
    fun masterControl() = faderControls.singleOrNull { it.gatewayId == AudioChannel.MASTER && it.physicalIndex == 0 }
    fun selectKnobFader(control: AudioFaderControl) {
        if(!disposed && faderControls.singleOrNull { it.gatewayId==control.gatewayId && it.physicalIndex==control.physicalIndex }!=null) {
            selectedOutput=control.physicalIndex
            controls.observe(state = state, output = selectedOutput)
        }
    }
    /** Translate only recognised built-in labels. User aliases and custom gateway names are data. */
    fun displayName(control: AudioFaderControl, settings: AudioPresentationSettings = presentation): String {
        settings.alias(control.gatewayId)?.let{return it}
        val key=when {
            control.gatewayId==AudioChannel.MASTER && control.label in setOf("MASTER","Master","Main","main") -> "Haupt"
            control.gatewayId==AudioChannel.FRONT && control.label in setOf("FRONT","Front","front") -> "Vorne"
            control.gatewayId==AudioChannel.REAR && control.label in setOf("REAR","Rear","rear") -> "Hinten"
            else -> return control.label
        }
        return com.no5.ui.No5Text.ui(key)
    }
    fun isVisible(control: AudioFaderControl): Boolean = presentation.visible(control.gatewayId)
    fun updatePresentation(next: AudioPresentationSettings) {
        if (!disposed) presentation = next
    }
    /**
     * Compatibility helper for the old in-memory controller test seam. Product
     * UI uses [updatePresentation] only after the local settings record saves.
     */
    fun setLocalDisplayAlias(control: AudioFaderControl, alias: String?): Boolean {
        if (disposed || faderControl(control.gatewayId)?.physicalIndex != control.physicalIndex) return false
        return try {
            presentation = presentation.update(control.gatewayId, alias, isVisible(control))
            true
        } catch (_: IllegalArgumentException) { false }
    }
    fun canAdjust(control: AudioFaderControl): Boolean {
        // The active touch gesture owns the stream until release. A merely
        // pending acknowledgement still permits intents through the shared queue.
        if (source.faderStream?.active == true) return false
        val limit = control.limit ?: return false
        val current = control.db?.takeIf { it.isFinite() } ?: return false
        return limit.maxDb > limit.minDb && canPresent(AudioCommand.Fader(control.gatewayId, limit.clamp(current)))
    }
    fun canAdjust(channel: AudioChannel): Boolean {
        val control = faderControl(channel) ?: return false
        return canAdjust(control)
    }
    /** A live gesture remains admitted while its own WSS values are in flight. */
    fun canDrag(control: AudioFaderControl): Boolean {
        if (disposed) return false
        val limit = control.limit ?: return false
        val current = control.db?.takeIf(Double::isFinite) ?: return false
        if (limit.maxDb <= limit.minDb) return false
        val stream = source.faderStream ?: return canAdjust(control)
        return stream.available(control.gatewayId, limit.clamp(current))
    }
    fun canPreset(preset: AudioPreset) = presets.any { it.id == preset.id } && canPresent(AudioCommand.Preset(preset.id))
    /**
     * Credential-free command preflight for the SOUND SETUP diagnostic. This
     * deliberately reports a local refusal as "no command sent", rather than
     * implying a Pi POST or a DSP/read-back failure that never occurred.
     */
    fun commandGateDiagnostic(command: AudioCommand): String {
        if (disposed) return "AUDIO_GATE_SOURCE_CLOSED; no command sent"
        if (state.pending != null) return "AUDIO_GATE_PENDING_REQUEST; no command sent"
        val fresh = state.withAudioFreshness(now(), source.freshnessPolicy)
        AudioCommandGate.reason(fresh, command)?.let { return "$it; no command sent" }
        return if (source.available(command)) "AUDIO_GATE_READY" else "AUDIO_GATE_ADAPTER_SESSION_OR_CATALOG_UNAVAILABLE; no command sent"
    }
    fun faderGateDiagnostic(control: AudioFaderControl): String {
        val requested = control.limit?.let { limit -> control.db?.let(limit::clamp) }
            ?: return "AUDIO_GATE_FADER_STATE_UNAVAILABLE; no command sent"
        return commandGateDiagnostic(AudioCommand.Fader(control.gatewayId, requested))
    }
    /** Bounded, credential-free evidence for the most recent audio command. */
    val gatewayWriteDiagnostic: String
        get() = lastOutcome?.let { outcome ->
            val request = outcome.requestId ?: "not sent"
            val revision = when {
                outcome.expectedGatewayRevision != null && outcome.acknowledgedGatewayRevision != null ->
                    "rev ${outcome.expectedGatewayRevision} → ${outcome.acknowledgedGatewayRevision}"
                outcome.expectedGatewayRevision != null -> "rev ${outcome.expectedGatewayRevision}"
                else -> "rev unavailable"
            }
            val command = when (val value = outcome.command) {
                is AudioCommand.Fader -> "${value.channel.name} ${value.valueDb} dB" +
                    (outcome.confirmedValueDb?.let { " → confirmed $it dB" } ?: "")
                is AudioCommand.Mute -> "${value.channel.name} mute=${value.muted}"
                is AudioCommand.Preset -> "preset ${value.id}"
                is AudioCommand.Equalizer -> "equalizer ${value.bankId} ${value.gainsDb}"
                is AudioCommand.Transport -> "transport ${value.action.name}"
            }
            val http = outcome.httpStatusCode?.let { " · HTTP $it" }.orEmpty()
            "Request $request · $revision · $command · ${outcome.status.name}$http · ${outcome.message}"
        } ?: "No audio command in this session."
    val faderStreamDiagnostic get() = source.faderStream?.status ?: "Release-only HTTP transport"
    fun level(control: AudioFaderControl): Double? = preview?.takeIf { it.channel == control.gatewayId }?.value
        ?: pendingVisual?.takeIf { it.channel == control.gatewayId }?.value ?: control.db
    fun level(channel: AudioChannel): Double? = preview?.takeIf { it.channel == channel }?.value
        ?: pendingVisual?.takeIf { it.channel == channel }?.value ?: state.dsp.faders[channel]?.db
    fun position(): Long? = preview?.takeIf { it.seek }?.value?.roundToLong() ?: state.media.positionMs
    fun previewLevel(channel: AudioChannel, value: Double) {
        val control = faderControl(channel) ?: return
        previewLevel(control, value)
    }
    fun previewLevel(control: AudioFaderControl, value: Double) {
        val limit = control.limit ?: return
        if (!value.isFinite()) return
        val bounded = limit.clamp(value)
        val stream = source.faderStream
        if (stream?.available(control.gatewayId, bounded) == true && stream.preview(control.gatewayId, bounded)) {
            streamingPreview = true; preview = AudioNativePreview(control.gatewayId, bounded)
            return
        }
        if (streamingPreview) { cancelPreview(); return }
        if (!canAdjust(control)) {
            recordLocalRejection(AudioCommand.Fader(control.gatewayId, limit.clamp(value)))
            return
        }
        if (can(AudioCommand.Fader(control.gatewayId, bounded))) preview = AudioNativePreview(control.gatewayId, bounded)
    }
    fun previewSeek(value: Double) {
        val duration = state.media.durationMs?.takeIf { it >= 0 } ?: return
        if (value.isFinite() && can(AudioCommand.Transport(TransportAction.SEEK, value.roundToLong()))) preview = AudioNativePreview(value = value.coerceIn(0.0, duration.toDouble()), seek = true)
    }
    fun commitPreview() {
        val p = preview ?: return
        preview = null
        if (streamingPreview) {
            streamingPreview = false
            source.faderStream?.finish()?.let { result ->
                latestRequestId = result.requestId; lastOutcome = result
                if (result.status == AudioCommandStatus.PENDING) pendingVisual = p
            }
            return
        }
        if (p.seek) write(AudioCommand.Transport(TransportAction.SEEK, p.value.roundToLong()))
        else p.channel?.let { channel ->
            // The physical catalog is authoritative.  Do not use the legacy
            // compatibility map here: if a gateway advertises a valid fader
            // control, a changed release must reach source.submit exactly once.
            if (faderControl(channel)?.db != p.value) write(AudioCommand.Fader(channel, p.value))
        }
    }
    fun cancelPreview() {
        if (streamingPreview) source.faderStream?.cancel()
        streamingPreview = false; preview = null
        controls.changed()
    }
    fun changeLevel(channel: AudioChannel, value: Double): Boolean {
        val control = faderControl(channel) ?: return false
        return changeLevel(control, value)
    }
    fun changeLevel(control: AudioFaderControl, value: Double) = write(AudioCommand.Fader(control.gatewayId, value))
    fun stepMaster(delta: Double) { adjustMaster(delta) }
    fun adjustMaster(deltaDb: Double): Boolean {
        val master = masterControl() ?: return false
        return controls.accepted(controls.adjust(master.gatewayId, deltaDb))
    }
    fun toggleMute(channel: AudioChannel): Boolean {
        val control = faderControl(channel) ?: return false
        return toggleMute(control)
    }
    fun toggleMute(control: AudioFaderControl): Boolean {
        return controls.accepted(controls.toggle(control.gatewayId))
    }
    fun transport(action: TransportAction) = write(AudioCommand.Transport(action))
    internal var presetActivationBusy by mutableStateOf(false);private set
    internal var presetActivationStatus by mutableStateOf<String?>(null);private set
    private var presetActivation:Job?=null
    internal fun canActivateSoundPreset(entry:SoundPresetEntry):Boolean {
        if(presetActivationBusy)return false
        if(soundEditor.canLoadPreset(entry))return true
        val routing=soundEditor.observed.routingProfiles[entry.routingTemplate]?:return false
        return soundEditor.canManagePresets && presets.any{it.id==routing.presetId && canPreset(it)}
    }
    /** Existing root-preset and sound controllers, one bounded navigation-owned workflow. */
    internal fun activateSoundPreset(entry:SoundPresetEntry,done:()->Unit={}):Boolean {
        if(!canActivateSoundPreset(entry))return false
        if(soundEditor.canLoadPreset(entry))return soundEditor.loadPreset(entry,done)
        val route=soundEditor.observed.routingProfiles[entry.routingTemplate]?:return false
        val root=presets.singleOrNull{it.id==route.presetId}?:return false
        val epoch=panelGeneration
        presetActivationBusy=true;presetActivationStatus="Kanalverteilung wird aktiviert"
        presetActivation=scope.launch {
            var subscription:SourceSubscription?=null
            try {
                val before=soundEditor.observed
                if(!applyPreset(root)){presetActivationStatus="Ziel nicht angenommen · keine Änderung bestätigt";return@launch}
                val request=latestRequestId?:run{presetActivationStatus="Abschluss unklar · zuerst abgleichen";return@launch}
                val result=CompletableDeferred<AudioCommandOutcome>()
                fun receive(value:AudioCommandOutcome?){if(value?.requestId==request && value.sessionId==sessionId && value.status!=AudioCommandStatus.PENDING)result.complete(value)}
                subscription=source.outcomes.subscribe(::receive)
                receive(source.outcomes.value)
                val ack=withTimeoutOrNull(4500){result.await()}
                if(ack?.status!=AudioCommandStatus.VERIFIED){presetActivationStatus=if(ack==null||ack.status==AudioCommandStatus.FAILED)"Abschluss unklar · zuerst abgleichen"else"Änderung nicht übernommen";return@launch}
                val ready=withTimeoutOrNull(4500){
                    while(true){
                        if(disposed || panelGeneration!=epoch || panel!="preset")return@withTimeoutOrNull false
                        val current=soundEditor.observed
                        if(state.dsp.presets.any{it.id==root.id&&it.active} && current.available && current.writable &&
                            current.preset?.routingTemplate==entry.routingTemplate && current.configGeneration!=before.configGeneration &&
                            current.routingProfiles[entry.routingTemplate]==route)break
                        soundEditor.refresh();delay(100)
                    };true
                }?:false
                if(ready){presetActivationStatus=null;if(!soundEditor.loadPreset(entry,done))presetActivationStatus="Änderung nicht übernommen"}
                else presetActivationStatus="Abschluss unklar · zuerst abgleichen"
            }finally{subscription?.close();presetActivationBusy=false;presetActivation=null}
        }
        return true
    }
    fun applyPreset(preset: AudioPreset) = if (canPreset(preset)) write(AudioCommand.Preset(preset.id)) else false
    fun applyPreset() = presets.firstOrNull { it.active }?.let(::applyPreset) ?: presets.singleOrNull()?.let(::applyPreset) ?: false

    /** Legacy wire data stays readable, but no legacy EQ editing state or route exists. */
    val equalizer get() = state.dsp.equalizer
    val toneAvailable: Boolean get() = presets.isNotEmpty() || soundEditor.observed.capabilities.parametricEq

    fun write(command: AudioCommand): Boolean {
        if (disposed) return false
        val id = when (command) {
            is AudioCommand.Fader -> controls.absolute(command.channel, command.valueDb)
            is AudioCommand.Mute -> controls.mute(command.channel, command.muted)
            is AudioCommand.Transport -> controls.transport(command.action, positionMs = command.positionMs)
            is AudioCommand.Preset -> controls.preset(command.id)
            is AudioCommand.Equalizer -> controls.equalizer(command.copy(gainsDb = command.gainsDb.toList()))
        }
        return controls.accepted(id)
    }

    /** Single effect boundary shared by touch, knobs and dashboard; source owns all wire/ACK work. */
    internal fun submitSource(command: AudioCommand): Boolean {
        if (disposed) return false
        val result = source.submit(command)
        if (result.sessionId != sessionId || !result.accepted) {
            lastOutcome = result
            return false
        }
        latestRequestId = result.requestId
        lastOutcome = result
        if (command is AudioCommand.Fader && result.status == AudioCommandStatus.PENDING) {
            pendingVisual = AudioNativePreview(command.channel, command.valueDb)
        }
        if (automaticVerification && !automaticTicker) {
            writeJob?.cancel()
            writeJob = scope.launch {
                while (isActive && !disposed && state.pending?.requestId == result.requestId) { delay(40); tickOnce() }
            }
        }
        return true
    }
    private fun recordLocalRejection(command: AudioCommand) {
        if (disposed) return
        val reason = commandGateDiagnostic(command)
        lastOutcome = AudioCommandOutcome(sessionId, null, command, AudioCommandStatus.REJECTED, now(), reason)
    }
    internal fun completePending(id: String, verified: Boolean) { if (demoAvailable) source.demoControls?.completePending(id, verified) }
    fun setOffline(offline: Boolean) { AudioOfflineTarget.entries.forEach { setOffline(it, offline) } }
    fun setOffline(target: AudioOfflineTarget, offline: Boolean = true) {
        if (demoAvailable) { cancelPreview(); source.demoControls?.setOffline(target, offline) }
    }
    internal fun tickOnce() {
        if (panel == "queue" && queueIdle.expired()) closePanel()
        if (disposed) return
        source.tick()
        state = state.withAudioFreshness(now(), source.freshnessPolicy)
    }
    private fun previewAvailable(): Boolean = preview?.let { p ->
        if (p.seek) can(AudioCommand.Transport(TransportAction.SEEK, p.value.roundToLong()))
        else p.channel?.let { if (streamingPreview) source.faderStream?.available(it, p.value) == true else can(AudioCommand.Fader(it, p.value)) } ?: false
    } ?: false
}

data class AudioViewState(val selectedPhysicalFader: Int = 0, val version: Int = 1)

data class AudioNativePreview(val channel: AudioChannel? = null, val value: Double, val seek: Boolean = false)

internal data class AudioNativeRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
    val centerX get() = x + width / 2
    val centerY get() = y + height / 2
    fun contains(other: AudioNativeRect) = other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom
    fun overlaps(other: AudioNativeRect) = x < other.right && right > other.x && y < other.bottom && bottom > other.y
}

/** Single source of truth for the resized, physical-LTR design. No nonuniform transform. */
internal object AudioNativeGeometry {
    const val WIDTH = 2000f
    const val HEIGHT = 1200f
    const val TOP = 112f
    const val BOTTOM = 1058f
    val artwork = AudioNativeRect(54f,232f,430f,430f)
    val nowPlayingReadout = AudioNativeRect(516f,232f,458f,410f)
    val nextSongs = AudioNativeRect(516f,440f,220f,48f)
    val music = AudioNativeRect(752f,440f,220f,48f)
    val queueOverlay = AudioNativeRect(30f,185f,960f,739f)
    val musicPage = AudioNativeRect(0f,130f,2000f,928f)
    val seek = AudioNativeRect(34f,648f,956f,96f)
    val transport = listOf(AudioNativeRect(54f,772f,150f,96f),AudioNativeRect(224f,772f,190f,96f),
        AudioNativeRect(434f,772f,326f,96f),AudioNativeRect(780f,772f,190f,96f))
    val masterSlider = AudioNativeRect(1735f,378f,116f,388f)
    val masterDown = AudioNativeRect(1670f,772f,111f,96f)
    val masterUp = AudioNativeRect(1805f,772f,111f,96f)
    val masterMute = AudioNativeRect(1213f,934f,443f,96f)
    val settings = AudioNativeRect(353f,934f,842f,96f)
    val drawer = AudioNativeRect(344f,250f,1312f,680f)
    val soundSetup = AudioNativeRect(600f,240f,800f,620f)
    val close = AudioNativeRect(1504f,268f,112f,96f)
    fun applyPreset(index: Int) = AudioNativeRect(376f,480f + index * 110f,340f,96f)
    val faderSlotX = listOf(1103f, 1291f, 1479f)
    fun zoneSlider(slot: Int)=AudioNativeRect(faderSlotX[slot]-58f,382f,116f,385f)
    fun zoneMute(slot: Int)=AudioNativeRect(faderSlotX[slot]-74f,786f,148f,96f)
    fun auxDown(index: Int)=AudioNativeRect(744f+index*264f,520f,96f,96f)
    fun auxUp(index: Int)=AudioNativeRect(894f+index*264f,520f,96f,96f)
    fun auxMute(index: Int)=AudioNativeRect(744f+index*264f,630f,246f,96f)
    fun levelFraction(value: Double?, limit: FaderLimit?): Float {
        if (value == null || !value.isFinite() || limit == null || limit.maxDb <= limit.minDb) return 0f
        return ((value - limit.minDb) / (limit.maxDb - limit.minDb)).coerceIn(0.0, 1.0).toFloat()
    }
    fun fraction(position: Float, length: Float, vertical: Boolean = false): Float {
        if (!position.isFinite() || !length.isFinite() || length <= 0) return 0f
        val inset = minOf(20f, length / 4)
        val along = if (vertical) length - position else position
        return ((along - inset) / (length - 2 * inset)).coerceIn(0f, 1f)
    }
}

/** Two-pass bounded native decoder; large/animated/invalid images never reach a Picture. */
internal fun decodeAudioArtwork(bytes: ByteArray, descriptor: AudioArtworkDescriptor): Bitmap? = try {
    require(bytes.size in 20..1_048_576)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth == descriptor.width && bounds.outHeight == descriptor.height)
    require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outWidth.toLong() * bounds.outHeight <= 1_048_576)
    require(bounds.outMimeType in setOf("image/png", "image/jpeg"))
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
} catch (_: Exception) { null }

/** Playlist covers have no guessed dimensions; validate actual bounds, then decode a thumbnail. */
internal fun decodePlaylistArtwork(bytes: ByteArray): Bitmap? = try {
    require(bytes.size in 20..1_048_576)
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outWidth.toLong()*bounds.outHeight<=1_048_576)
    require(bounds.outMimeType in setOf("image/png","image/jpeg"))
    var sample=1
    while(bounds.outWidth/sample>256 || bounds.outHeight/sample>256)sample*=2
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inScaled=false;inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
} catch(_:Exception) {null}

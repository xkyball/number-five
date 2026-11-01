use anyhow::{Context, Result, bail};
use clap::Parser;
use futures_util::{Stream, StreamExt};
use librespot::{
    connect::{ConnectConfig, Spirc},
    core::{Session, SessionConfig, cache::Cache, config::DeviceType},
    discovery::Discovery,
    metadata::audio::UniqueFields,
    playback::{
        audio_backend,
        config::{AudioFormat, Bitrate, PlayerConfig},
        mixer::{self, Mixer, MixerConfig},
        player::{Player, PlayerEvent},
    },
};
use no5_spotify_connect::{
    control::{Action, DispatchError, DispatchFuture, Dispatcher, serve},
    media::{BrowseSession, Browser, SharedBrowseSession, media_payload, select_artwork},
    state::{PlaybackState, ReceiverEvent, write_gateway_state},
    volume::{self, CommandError, Range, Target, VolumeBridge},
};
use sha1::{Digest, Sha1};
use std::{
    future::Future,
    net::IpAddr,
    path::PathBuf,
    pin::Pin,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Mutex;

#[derive(Debug, Parser)]
#[command(about = "No5 controlled Spotify Connect receiver")]
struct Arguments {
    /// Spotify Connect device name; use a distinct name for each network instance.
    #[arg(long, default_value = "No5")]
    name: String,
    /// Explicit LAN addresses advertised by mDNS; comma-separated or repeated.
    /// Does not change the Spotify discovery HTTP listener's bind address.
    #[arg(long, env = "NO5_SPOTIFY_ZEROCONF_IPS", value_delimiter = ',', value_parser = parse_zeroconf_ip)]
    zeroconf_ip: Vec<IpAddr>,
    /// ALSA output device already routed into CamillaDSP.
    #[arg(long, default_value = "camilladsp")]
    alsa_device: String,
    /// Persistent credentials, volume and audio cache directory.
    #[arg(long, default_value = "/var/lib/no5-spotify-connect/cache")]
    cache_dir: PathBuf,
    /// Atomic state file read by the No5 gateway.
    #[arg(long, default_value = "/var/lib/no5-media/raspotify-event.json")]
    state_file: PathBuf,
    /// Local-only command socket; never a TCP listener.
    #[arg(long, default_value = "/run/no5-spotify-connect/control.sock")]
    control_socket: PathBuf,
    /// Explicit opt-in; unifies Spotify and gateway Main at Camilla without PCM soft volume.
    #[arg(long)]
    camilla_volume_bridge: bool,
    #[arg(long, default_value = "ws://127.0.0.1:1234")]
    camilla_socket: String,
    #[arg(long, default_value_t = -60.0, allow_hyphen_values = true)]
    main_min_db: f64,
    #[arg(long, default_value_t = 0.0, allow_hyphen_values = true)]
    main_max_db: f64,
    #[arg(long, default_value_t = 1.0)]
    main_step_db: f64,
}

fn parse_zeroconf_ip(value: &str) -> std::result::Result<IpAddr, String> {
    let address: IpAddr = value.parse().map_err(|_| "Expected an explicit IPv4 or IPv6 address")?;
    let valid = match address {
        IpAddr::V4(address) => !address.is_loopback() && !address.is_unspecified()
            && !address.is_multicast() && !address.is_broadcast(),
        IpAddr::V6(address) => !address.is_loopback() && !address.is_unspecified()
            && !address.is_multicast() && !address.is_unicast_link_local()
            && address.to_ipv4_mapped().is_none(),
    };
    if !valid {
        return Err("Advertise a LAN unicast address, excluding loopback, unspecified, multicast and unscoped IPv6 link-local addresses".into());
    }
    Ok(address)
}

type SharedSpirc = Arc<Mutex<Option<Arc<Spirc>>>>;

struct ReceiverDispatcher {
    handles: SharedSpirc,
    bridge: Option<Arc<VolumeBridge>>,
    state: Arc<Mutex<PlaybackState>>,
    browser: Arc<Browser>,
    navigation_write: Mutex<()>,
}
impl Dispatcher for ReceiverDispatcher {
    fn dispatch(&self, action: Action) -> Result<(), String> {
        let handles = self
            .handles
            .try_lock()
            .map_err(|_| "Spotify control is busy")?;
        let spirc = handles
            .as_ref()
            .ok_or("Spotify Connect session is unavailable")?;
        dispatch_spirc(spirc, action)
    }
    fn dispatch_confirmed(&self, action: Action) -> DispatchFuture<'_> {
        Box::pin(async move {
            let (target, expected) = match action {
                Action::Browse {
                    expected_session,
                    query,
                } => {
                    return self.browser.read(&expected_session, query).await.map(Some);
                }
                action @ (Action::ReadMedia | Action::ReadNavigation) => {
                    let navigation = matches!(action, Action::ReadNavigation);
                    let active = self.browser.sessions.lock().await.clone().ok_or_else(|| {
                        DispatchError::Rejected("Connect session is unavailable".into())
                    })?;
                    let queue = self
                        .handles
                        .lock()
                        .await
                        .as_ref()
                        .and_then(|s| s.observed_queue());
                    let mut result = media_payload(&active.id, &*self.state.lock().await, queue);
                    if navigation {
                        self.browser.enrich_media(&active.id, &mut result).await;
                    }
                    let sessions = self.browser.sessions.lock().await;
                    if !sessions
                        .as_ref()
                        .is_some_and(|s| s.id == active.id && !s.session.is_invalid())
                    {
                        return Err(DispatchError::Rejected(
                            "Connect session changed during media read".into(),
                        ));
                    }
                    return Ok(Some(result));
                }
                action @ (Action::SelectQueue { .. } | Action::SelectCollection { .. } | Action::StartPlaylist { .. }) => {
                    return self.select_navigation(action).await.map(Some);
                }
                Action::MasterState => (None, None),
                Action::MasterVolume { value_db, expected } => {
                    (Some(Target::Db(value_db)), Some(expected))
                }
                Action::MasterMute { muted, expected } => {
                    (Some(Target::Mute(muted)), Some(expected))
                }
                Action::MasterAdjust {
                    delta_db,
                    expected_session,
                } => {
                    let bridge = self.bridge.as_ref().ok_or_else(|| {
                        DispatchError::Rejected("Camilla Main authority is disabled".into())
                    })?;
                    if bridge.session_id() != expected_session {
                        return Err(DispatchError::Rejected(
                            "Main authority session changed; adjustment was not sent".into(),
                        ));
                    }
                    (Some(Target::Adjust(delta_db)), None)
                }
                action => {
                    return self
                        .dispatch(action)
                        .map(|_| None)
                        .map_err(DispatchError::Rejected);
                }
            };
            let bridge = self.bridge.as_ref().ok_or_else(|| {
                DispatchError::Rejected("Camilla Main authority is disabled".into())
            })?;
            let confirmed =
                bridge
                    .confirmed(target, expected)
                    .await
                    .map_err(|error| match error {
                        CommandError::Rejected(message) => DispatchError::Rejected(message.into()),
                        CommandError::Unknown(message) => DispatchError::Unknown(message.into()),
                    })?;
            serde_json::to_value(confirmed).map(Some).map_err(|_| {
                DispatchError::Unknown("Main confirmation serialization failed".into())
            })
        })
    }
}

impl ReceiverDispatcher {
    async fn select_navigation(&self, action: Action) -> Result<serde_json::Value, DispatchError> {
        let _write = self
            .navigation_write
            .try_lock()
            .map_err(|_| DispatchError::Rejected("A song selection is already pending".into()))?;
        let mut start_playlist = None;
        let (expected, revision, uri, index, collection) = match action {
            Action::SelectQueue {
                expected_session,
                expected_queue_revision,
                uri,
                index,
            } => (expected_session, expected_queue_revision, uri, index, None),
            Action::SelectCollection {
                expected_session,
                expected_queue_revision,
                uri,
                index,
                query,
                expected_page_revision,
            } => (
                expected_session,
                expected_queue_revision,
                uri,
                index,
                Some((query, expected_page_revision)),
            ),
            Action::StartPlaylist { expected_session, expected_queue_revision, uri, expected_page_revision, shuffle } => {
                if !uri.starts_with("spotify:playlist:") || !no5_spotify_connect::media::valid_context_uri(&uri) {
                    return Err(DispatchError::Rejected("Invalid playlist target".into()));
                }
                let query = no5_spotify_connect::media::BrowseQuery::Playlist { uri: uri.clone(), offset: 0, limit: 20 };
                let page = self.browser.read(&expected_session, query.clone()).await?;
                if page["page"]["revision"].as_str() != expected_page_revision.as_deref() {
                    return Err(DispatchError::Rejected("Playlist changed; start was not sent".into()));
                }
                let first = page["page"]["items"][0]["uri"].as_str().ok_or_else(|| DispatchError::Rejected("Playlist has no playable first row".into()))?.to_owned();
                start_playlist = Some((uri, shuffle));
                (expected_session, expected_queue_revision, first, 0, Some((query, expected_page_revision)))
            },
            _ => return Err(DispatchError::Rejected("Invalid selection action".into())),
        };
        if !no5_spotify_connect::media::valid_playable_uri(&uri)
            || index >= 80
            || revision.len() != 36
            || expected.len() > 100
            || expected.is_empty()
        {
            return Err(DispatchError::Rejected("Invalid selection target".into()));
        }
        let active = self
            .browser
            .sessions
            .lock()
            .await
            .clone()
            .filter(|s| s.id == expected && !s.session.is_invalid())
            .ok_or_else(|| DispatchError::Rejected("Connect session changed".into()))?;
        let context = if let Some((query, page_revision)) = collection {
            Some(
                tokio::time::timeout(
                    Duration::from_secs(3),
                    self.browser
                        .selection_context(&expected, query, page_revision, index, &uri),
                )
                .await
                .map_err(|_| {
                    DispatchError::Rejected("Selection preparation deadline exceeded".into())
                })??,
            )
        } else {
            None
        };
        if !self
            .browser
            .sessions
            .lock()
            .await
            .as_ref()
            .is_some_and(|s| s.id == active.id && !s.session.is_invalid())
        {
            return Err(DispatchError::Rejected(
                "Connect session changed before selection".into(),
            ));
        }
        let handle = self
            .handles
            .lock()
            .await
            .clone()
            .ok_or_else(|| DispatchError::Rejected("Receiver is unavailable".into()))?;
        let before = self.state.lock().await.load_sequence;
        let dispatched = async {
            if let Some((context, position)) = context {
                handle
                    .select_context_mode(revision, context, position, uri, start_playlist.as_ref().map(|(_, shuffle)| *shuffle))
                    .await
            } else {
                handle.select_queue(revision, index, uri).await
            }
        };
        let target = tokio::time::timeout(Duration::from_secs(1), dispatched)
            .await
            .map_err(|_| {
                DispatchError::Unknown("Selection receipt deadline exceeded; no replay".into())
            })?
            .map_err(|e| match e {
                librespot::connect::NavigationError::Rejected => {
                    DispatchError::Rejected("Queue changed; selection was not sent".into())
                }
                librespot::connect::NavigationError::Unknown => {
                    DispatchError::Unknown("Selection outcome is unknown; no replay".into())
                }
            })?;
        let deadline = tokio::time::Instant::now() + Duration::from_millis(3500);
        loop {
            if !self
                .browser
                .sessions
                .lock()
                .await
                .as_ref()
                .is_some_and(|s| s.id == expected && !s.session.is_invalid())
            {
                return Err(DispatchError::Unknown(
                    "Connect session changed after selection; no replay".into(),
                ));
            }
            let state = self.state.lock().await.clone();
            let queue = handle.observed_queue();
            if state.connected
                && state.active
                && state.track_uri.as_deref() == Some(&target.uri)
                && state.loading_uri.as_deref() == Some(&target.uri)
                && state.completed_load_sequence > before
                && state.state == no5_spotify_connect::state::PlaybackStatus::Playing
                && queue.is_some_and(|q| {
                    q.active && q.current_uri == target.uri && q.current_uid == target.uid &&
                        start_playlist.as_ref().is_none_or(|(playlist, shuffle)| q.context_uri == *playlist && q.shuffle == *shuffle)
                })
            {
                if let Some((playlist, shuffle)) = &start_playlist {
                    return Ok(serde_json::json!({"version":1,"kind":"spotify-playlist-start-readback", "receiverSessionId":expected,
                        "playlistUri":playlist,"shuffle":shuffle,"trackUri":target.uri,"entryId":target.uid,
                        "observedAtEpochMs":state.observed_at_epoch_ms,"playerSequence":state.completed_load_sequence}));
                }
                return Ok(
                    serde_json::json!({"version":1,"kind":"spotify-selection-readback",
                    "receiverSessionId":expected,"trackUri":target.uri,"entryId":target.uid,
                    "observedAtEpochMs":state.observed_at_epoch_ms,"playerSequence":state.completed_load_sequence}),
                );
            }
            if tokio::time::Instant::now() >= deadline {
                return Err(DispatchError::Unknown(
                    "Actual selected-song readback was not confirmed; no replay".into(),
                ));
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let arguments = Arguments::parse();
    run(arguments).await
}

async fn next_session_credentials<C, D>(cached: &mut Option<C>, discovery: &mut D) -> Option<C>
where
    D: Stream<Item = C> + Unpin,
{
    match cached.take() {
        Some(credentials) => Some(credentials),
        None => discovery.next().await,
    }
}

// A client can repeat addUser while our transport is still connecting. The
// complete credential identity must match: same account alone is insufficient.
// No credential, account or hash is emitted in this diagnostic.
async fn next_changed_credentials<C, D>(
    current: &C,
    discovery: &mut D,
    session_valid: impl Fn() -> bool,
) -> Option<C>
where
    C: PartialEq,
    D: Stream<Item = C> + Unpin,
{
    loop {
        let incoming = discovery.next().await;
        if session_valid() && incoming.as_ref() == Some(current) {
            eprintln!("NO5_SPOTIFY_SETUP phase=DISCOVERY_COALESCED elapsedMs=0");
            continue;
        }
        return incoming;
    }
}

fn accept_session_setup<T>(
    session: &Session,
    result: std::result::Result<T, librespot::core::Error>,
) -> Option<T> {
    match result {
        Ok(connected) => Some(connected),
        Err(error) => {
            // Setup can also fail AFTER AP authentication. Never reuse that
            // partially initialized session, but keep discovery and cache alive.
            if !session.is_invalid() {
                session.shutdown();
            }
            eprintln!("Spotify session setup failed ({}); waiting for a new client connection", error.kind);
            None
        }
    }
}

async fn run(arguments: Arguments) -> Result<()> {
    let state = Arc::new(Mutex::new(PlaybackState::unavailable(now_epoch_ms())));
    persist_state(&arguments.state_file, &state).await?;

    let mut session_config = SessionConfig::default();
    // A reproducible device id avoids littering the Spotify device list after
    // a service restart while retaining the configured user-facing name.
    session_config.device_id = format!(
        "{:x}",
        Sha1::digest(format!("no5:{0}", arguments.name).as_bytes())
    );
    let audio_cache = arguments.cache_dir.join("audio");
    let cache = Cache::new(
        Some(&arguments.cache_dir),
        Some(&arguments.cache_dir),
        Some(&audio_cache),
        None,
    )
    .context("create receiver cache")?;
    let cached_credentials = cache.credentials();
    let mut session = Session::new(session_config.clone(), Some(cache.clone()));

    let mut player_config = PlayerConfig::default();
    player_config.bitrate = Bitrate::Bitrate320;
    player_config.position_update_interval = Some(Duration::from_secs(1));
    let sink_builder = audio_backend::find(Some("alsa".to_owned()))
        .context("librespot was built without the ALSA backend")?;
    let handles: SharedSpirc = Arc::new(Mutex::new(None));
    let browse_sessions: SharedBrowseSession = Arc::new(Mutex::new(None));
    let browser = Arc::new(Browser::new(Arc::clone(&browse_sessions)));
    let bridge = if arguments.camilla_volume_bridge {
        Some(
            volume::start(
                arguments.camilla_socket.clone(),
                arguments.state_file.with_extension("volume-authority.json"),
                Range {
                    min: arguments.main_min_db,
                    max: arguments.main_max_db,
                    step: arguments.main_step_db,
                },
                cache.volume().unwrap_or(u16::MAX / 2),
                Arc::clone(&handles),
            )
            .await?,
        )
    } else {
        None
    };
    let mixer: Arc<dyn Mixer> = if let Some(bridge) = &bridge {
        bridge.clone()
    } else {
        let builder =
            mixer::find(Some("softvol")).context("librespot soft volume mixer is unavailable")?;
        builder(MixerConfig::default()).context("create librespot mixer")?
    };
    let output_device = arguments.alsa_device.clone();
    let player = Arc::new(Player::new(
        player_config,
        session.clone(),
        mixer.get_soft_volume(),
        move || sink_builder(Some(output_device.clone()), AudioFormat::S16),
    ));

    let events = player.get_player_event_channel();
    let state_file = arguments.state_file.clone();
    let event_state = Arc::clone(&state);
    let event_bridge = bridge.clone();
    let event_reader =
        tokio::spawn(
            async move { event_loop(events, event_state, state_file, event_bridge).await },
        );

    let dispatcher = Arc::new(ReceiverDispatcher {
        navigation_write: Mutex::new(()),
        handles: Arc::clone(&handles),
        bridge: bridge.clone(),
        state: Arc::clone(&state),
        browser,
    });
    let socket_path = arguments.control_socket.clone();
    tokio::spawn(async move {
        if let Err(error) = serve(&socket_path, dispatcher).await {
            eprintln!("No5 Spotify control socket stopped: {error}");
        }
    });

    let mut discovery = Discovery::builder(
        session_config.device_id.clone(),
        session_config.client_id.clone(),
    )
    .name(arguments.name.clone())
    .device_type(DeviceType::Speaker)
    .zeroconf_ip(arguments.zeroconf_ip.clone())
    .launch()
    .context("launch Spotify Connect discovery")?;
    let mut next_credentials = cached_credentials;

    'sessions: loop {
        let credentials = next_session_credentials(&mut next_credentials, &mut discovery)
            .await.context("Spotify Connect discovery stopped")?;
        if session.is_invalid() {
            session = Session::new(session_config.clone(), Some(cache.clone()));
            player.set_session(session.clone());
        }
        let mut connect_config = ConnectConfig::default();
        connect_config.name = arguments.name.clone();
        connect_config.device_type = DeviceType::Speaker;
        if bridge.is_some() {
            connect_config.initial_volume = mixer.volume();
        }
        // Clear the previous account before connect can emit its first event.
        *state.lock().await = PlaybackState::unavailable(now_epoch_ms());
        let setup_credentials = credentials.clone();
        let setup = Spirc::new(
            connect_config,
            session.clone(),
            credentials,
            Arc::clone(&player),
            Arc::clone(&mixer),
        )
        .await;
        let Some((spirc, task)) = accept_session_setup(&session, setup) else {
            *browse_sessions.lock().await = None;
            *handles.lock().await = None;
            if let Some(bridge) = &bridge { bridge.set_active(false); }
            *state.lock().await = PlaybackState::unavailable(now_epoch_ms());
            if let Err(error) = persist_state(&arguments.state_file, &state).await {
                eprintln!("No5 Spotify unavailable-state write failed: {error}");
            }
            // The consumed credentials are NOT put back. Discovery stays on the
            // same listener/port; only a new client request can start setup again.
            continue 'sessions;
        };
        let spirc = Arc::new(spirc);
        {
            // New account/session invalidates all prior media and browse leases.
            *browse_sessions.lock().await = Some(BrowseSession {
                id: format!("media-{}-{}", now_epoch_ms(), session.session_id()),
                session: session.clone(),
            });
        }
        {
            let mut active = handles.lock().await;
            *active = Some(Arc::clone(&spirc));
        }
        let mut task: Pin<Box<dyn Future<Output = ()>>> = Box::pin(task);
        let mut observations = tokio::time::interval(Duration::from_secs(1));
        observations.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
              _ = observations.tick() => {
                  let healthy = !session.is_invalid() && !player.is_invalid() && !event_reader.is_finished();
                  let observed = state.lock().await.observe_paused_context(healthy, now_epoch_ms());
                  if observed {
                      persist_state(&arguments.state_file, &state).await?;
                  }
              }
              _ = &mut task => {
                  eprintln!("NO5_SPOTIFY_SETUP phase=SESSION_TASK_ENDED elapsedMs=0");
                  *browse_sessions.lock().await = None;
                  let mut active = handles.lock().await;
                  *active = None;
                  if let Some(bridge) = &bridge { bridge.set_active(false); }
                  if !session.is_invalid() {
                      session.shutdown();
                  }
                  break;
              }
              discovered = next_changed_credentials(&setup_credentials, &mut discovery, || !session.is_invalid()) => {
                  eprintln!("NO5_SPOTIFY_SETUP phase=DISCOVERY_REPLACING_SESSION elapsedMs=0");
                  *browse_sessions.lock().await = None;
                  let _ = spirc.shutdown();
                  let mut active = handles.lock().await;
                  *active = None;
                  if let Some(bridge) = &bridge { bridge.set_active(false); }
                  if !session.is_invalid() {
                      session.shutdown();
                  }
                  next_credentials = discovered;
                  if next_credentials.is_none() {
                      bail!("Spotify Connect discovery stopped");
                  }
                  break;
              }
              _ = tokio::signal::ctrl_c() => {
                  *browse_sessions.lock().await = None;
                  let _ = spirc.shutdown();
                  let mut active = handles.lock().await;
                  *active = None;
                  if let Some(bridge) = &bridge { bridge.set_active(false); }
                  break 'sessions;
              }
            }
        }
    }
    discovery.shutdown().await;
    Ok(())
}

async fn event_loop(
    mut events: librespot::playback::player::PlayerEventChannel,
    state: Arc<Mutex<PlaybackState>>,
    state_file: PathBuf,
    bridge: Option<Arc<VolumeBridge>>,
) {
    while let Some(event) = events.recv().await {
        {
            let mut snapshot = state.lock().await;
            match &event {
                PlayerEvent::Loading {
                    play_request_id,
                    track_id,
                    ..
                } => snapshot.observe_player_loading(*play_request_id, track_id.to_uri().ok()),
                PlayerEvent::Playing {
                    play_request_id,
                    track_id,
                    ..
                } => snapshot.observe_player_playing(*play_request_id, track_id.to_uri().ok()),
                _ => {}
            }
        }
        let Some(update) = player_event(event) else {
            continue;
        };
        {
            let mut snapshot = state.lock().await;
            snapshot.apply(update, now_epoch_ms());
            if let Some(bridge) = &bridge {
                bridge.set_active(snapshot.connected && snapshot.active);
            }
        }
        if let Err(error) = persist_state(&state_file, &state).await {
            eprintln!("No5 Spotify state write failed: {error}");
        }
    }
}

async fn persist_state(path: &PathBuf, state: &Arc<Mutex<PlaybackState>>) -> Result<()> {
    // The event reader and idle observer share the same atomic writer. Keep
    // its snapshot guard until replacement finishes so their temp files and
    // observation order cannot race.
    let snapshot = state.lock().await;
    let observed = snapshot.clone();
    let path = path.clone();
    tokio::task::spawn_blocking(move || write_gateway_state(&path, &observed))
        .await
        .context("join state writer")?
        .context("write gateway state")?;
    drop(snapshot);
    Ok(())
}

fn player_event(event: PlayerEvent) -> Option<ReceiverEvent> {
    match event {
        PlayerEvent::SessionConnected { .. } => Some(ReceiverEvent::SessionConnected),
        PlayerEvent::SessionDisconnected { .. } => Some(ReceiverEvent::SessionDisconnected),
        PlayerEvent::TrackChanged { audio_item } => {
            let track_id = audio_item.track_id.to_id().ok();
            let track_uri = no5_spotify_connect::media::valid_playable_uri(&audio_item.uri)
                .then_some(audio_item.uri.clone());
            let artwork = select_artwork(&audio_item.covers);
            let title = clean(Some(audio_item.name));
            let duration_ms = Some(audio_item.duration_ms);
            let (artists, album) = match audio_item.unique_fields {
                UniqueFields::Track { artists, album, .. } => (
                    artists
                        .0
                        .into_iter()
                        .filter_map(|artist| clean(Some(artist.name)))
                        .take(10)
                        .collect(),
                    clean(Some(album)),
                ),
                UniqueFields::Local { artists, album, .. } => (
                    artists
                        .into_iter()
                        .flat_map(|names| names.split('\n').map(str::to_owned).collect::<Vec<_>>())
                        .filter_map(|artist| clean(Some(artist)))
                        .take(10)
                        .collect(),
                    album.and_then(|value| clean(Some(value))),
                ),
                UniqueFields::Episode { show_name, .. } => (Vec::new(), clean(Some(show_name))),
            };
            Some(ReceiverEvent::TrackChanged {
                track_id,
                track_uri,
                artwork,
                title,
                artists,
                album,
                duration_ms,
            })
        }
        PlayerEvent::Playing { position_ms, .. } => Some(ReceiverEvent::Playing {
            position_ms: Some(position_ms),
        }),
        PlayerEvent::Paused { position_ms, .. } => Some(ReceiverEvent::Paused {
            position_ms: Some(position_ms),
        }),
        PlayerEvent::PositionChanged { position_ms, .. } => Some(ReceiverEvent::PlayingPosition {
            position_ms: Some(position_ms),
        }),
        PlayerEvent::PositionCorrection { position_ms, .. }
        | PlayerEvent::Seeked { position_ms, .. } => Some(ReceiverEvent::Position {
            position_ms: Some(position_ms),
        }),
        PlayerEvent::Stopped { .. } => Some(ReceiverEvent::Stopped),
        PlayerEvent::Unavailable { .. } => Some(ReceiverEvent::Unavailable),
        PlayerEvent::ShuffleChanged { shuffle } => Some(ReceiverEvent::Shuffle(shuffle)),
        PlayerEvent::RepeatChanged { context, track } => {
            Some(ReceiverEvent::Repeat(context || track))
        }
        _ => None,
    }
}

fn dispatch_spirc(spirc: &Arc<Spirc>, action: Action) -> Result<(), String> {
    let result = match action {
        Action::Play => spirc.play(),
        Action::Pause => spirc.pause(),
        Action::Next => spirc.next(),
        Action::Previous => spirc.prev(),
        Action::Seek { position_ms } => spirc.set_position_ms(position_ms),
        Action::Shuffle { enabled } => spirc.shuffle(enabled),
        Action::Repeat { enabled } => spirc.repeat(enabled),
        Action::MasterVolume { .. }
        | Action::MasterMute { .. }
        | Action::MasterState
        | Action::ReadMedia
        | Action::ReadNavigation
        | Action::SelectQueue { .. }
        | Action::SelectCollection { .. }
        | Action::StartPlaylist { .. }
        | Action::Browse { .. }
        | Action::MasterAdjust { .. } => {
            return Err(
                "Commands require confirmed receiver dispatch or the Camilla authority".into(),
            );
        }
    };
    result.map_err(|_| "Spotify Connect command is unavailable".to_string())
}

fn clean(value: Option<String>) -> Option<String> {
    let value = value?.trim().to_owned();
    if value.is_empty()
        || value.len() > 200
        || value.chars().any(|character| character.is_control())
    {
        None
    } else {
        Some(value)
    }
}

fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::{CommandFactory, FromArgMatches};
    use librespot::core::SpotifyUri;
    use no5_spotify_connect::state::PlaybackStatus;

    #[tokio::test]
    async fn discovery_coalescing_requires_the_complete_credential_identity() {
        use librespot::core::authentication::Credentials;
        let mut current = Credentials::with_access_token("synthetic-first");
        current.username = Some("synthetic-account".into());
        let mut changed = current.clone();
        changed.auth_data = b"synthetic-replacement".to_vec();
        let mut stream = futures_util::stream::iter([current.clone(), current.clone(), changed.clone()]);
        assert_eq!(next_changed_credentials(&current, &mut stream, || true).await, Some(changed));
        assert_eq!(next_changed_credentials(&current, &mut stream, || true).await, None);
    }

    #[tokio::test]
    async fn discovery_coalescing_does_not_block_recovery_after_session_invalidation() {
        let mut stream = futures_util::stream::iter(["same credentials"]);
        assert_eq!(next_changed_credentials(&"same credentials", &mut stream, || false).await,
            Some("same credentials"));
    }

    #[tokio::test(start_paused = true)]
    async fn discovery_coalescing_does_not_cancel_the_pending_actor() {
        let mut stream = futures_util::stream::iter(["same", "same"])
            .chain(futures_util::stream::pending());
        tokio::select! {
            _ = next_changed_credentials(&"same", &mut stream, || true) => {
                panic!("a repeated client request must not replace the pending actor")
            }
            _ = tokio::time::sleep(Duration::from_millis(50)) => (),
        }
    }

    #[tokio::test(start_paused = true)]
    async fn failed_session_setup_keeps_discovery_waiting_without_replaying_cached_credentials() {
        use futures_util::FutureExt;
        use std::{sync::atomic::{AtomicUsize, Ordering}, task::{Context, Poll}};
        struct RetainedDiscovery {
            receiver: tokio::sync::mpsc::UnboundedReceiver<&'static str>,
            dropped: Arc<AtomicUsize>,
        }
        impl Stream for RetainedDiscovery {
            type Item = &'static str;
            fn poll_next(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<Option<Self::Item>> {
                self.receiver.poll_recv(context)
            }
        }
        impl Drop for RetainedDiscovery {
            fn drop(&mut self) { self.dropped.fetch_add(1, Ordering::SeqCst); }
        }
        let session = Session::new(SessionConfig::default(), None);
        let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
        let dropped = Arc::new(AtomicUsize::new(0));
        let mut discovery = RetainedDiscovery { receiver, dropped: dropped.clone() };
        let mut cached = Some("one cached attempt");
        assert_eq!(next_session_credentials(&mut cached, &mut discovery).await, Some("one cached attempt"));
        let setup: Option<()> = accept_session_setup(&session,
            Err(librespot::core::Error::deadline_exceeded("AP budget exhausted")));
        assert!(setup.is_none());
        assert!(session.is_invalid());
        assert!(cached.is_none());
        assert!(next_session_credentials(&mut cached, &mut discovery).now_or_never().is_none());
        tokio::time::sleep(Duration::from_secs(120)).await;
        assert!(next_session_credentials(&mut cached, &mut discovery).now_or_never().is_none(),
            "elapsed time must not create an autonomous credential retry");
        assert_eq!(dropped.load(Ordering::SeqCst), 0, "the discovery listener must remain owned");
        sender.send("new explicit client connection").unwrap();
        assert_eq!(next_session_credentials(&mut cached, &mut discovery).await, Some("new explicit client connection"));
        assert!(cached.is_none());
        assert_eq!(dropped.load(Ordering::SeqCst), 0);
        drop(sender);
        assert_eq!(next_session_credentials(&mut cached, &mut discovery).await, None);
        drop(discovery);
        assert_eq!(dropped.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn failed_session_setup_invalidates_an_authenticated_partial_session() {
        let session = Session::new(SessionConfig::default(), None);
        session.set_username("synthetic-authenticated-session");
        session.set_auth_data(&[1, 2, 3]);
        let setup: Option<()> = accept_session_setup(&session,
            Err(librespot::core::Error::unauthenticated("post-AP setup failed")));
        assert!(setup.is_none());
        assert!(session.is_invalid(), "the next client must receive a new Session");
    }

    #[tokio::test]
    async fn failed_session_setup_preserves_persisted_credentials_byte_for_byte() {
        let directory = tempfile::tempdir().unwrap();
        let cache = Cache::new(Some(directory.path()), None, None, None).unwrap();
        let credentials = librespot::core::authentication::Credentials::with_access_token("synthetic-test-cache-token");
        cache.save_credentials(&credentials);
        let path = directory.path().join("credentials.json");
        let before = std::fs::read(&path).unwrap();
        let session = Session::new(SessionConfig::default(), Some(cache.clone()));
        let setup: Option<()> = accept_session_setup(&session,
            Err(librespot::core::Error::permission_denied("synthetic authentication rejection")));
        assert!(setup.is_none());
        assert!(session.is_invalid());
        assert_eq!(std::fs::read(&path).unwrap(), before);
        assert_eq!(cache.credentials(), Some(credentials));
    }

    #[tokio::test]
    async fn successful_session_setup_returns_the_original_handle_without_invalidating_session() {
        let session = Session::new(SessionConfig::default(), None);
        let setup = accept_session_setup(&session, Ok(17));
        assert_eq!(setup, Some(17));
        assert!(!session.is_invalid());
    }

    fn parse_arguments(values: &[&str]) -> std::result::Result<Arguments, clap::Error> {
        // Do not mutate process-wide environment in parallel tests. Disable this
        // one env source locally; the real CLI keeps the declared env binding.
        let matches = Arguments::command()
            .mut_arg("zeroconf_ip", |argument| argument.env(None::<&str>))
            .try_get_matches_from(values)?;
        Arguments::from_arg_matches(&matches)
    }

    #[test]
    fn zeroconf_addresses_default_to_existing_automatic_discovery() {
        let args = parse_arguments(&["receiver", "--name", "No5 test", "--control-socket", "/tmp/test-control.sock"]).unwrap();
        assert!(args.zeroconf_ip.is_empty());
        assert_eq!(args.name, "No5 test");
        assert_eq!(args.control_socket, PathBuf::from("/tmp/test-control.sock"));
        assert_eq!(args.alsa_device, "camilladsp");
    }

    #[test]
    fn zeroconf_csv_and_repeated_options_keep_explicit_unicast_addresses_in_order() {
        let args = parse_arguments(&["receiver", "--zeroconf-ip", "192.168.7.20,2001:db8::20",
            "--zeroconf-ip", "fd12:3456::20"]).unwrap();
        let expected: Vec<IpAddr> = ["192.168.7.20", "2001:db8::20", "fd12:3456::20"]
            .into_iter().map(|value| value.parse().unwrap()).collect();
        assert_eq!(args.zeroconf_ip, expected);
    }

    #[test]
    fn zeroconf_rejects_invalid_and_unusable_address_before_receiver_start() {
        for invalid in ["not-an-ip", "192.168.1.999", "0.0.0.0", "::", "127.0.0.1", "::1",
            "224.0.0.251", "ff02::fb", "fe80::20", "fe80::20%wlan0", "255.255.255.255",
            "::ffff:127.0.0.1", "192.168.7.20,0.0.0.0", "192.168.7.20,"] {
            assert!(parse_arguments(&["receiver", "--zeroconf-ip", invalid]).is_err(),
                "invalid advertisement was accepted: {invalid}");
        }
    }

    #[test]
    fn zeroconf_environment_uses_the_same_comma_delimited_validated_argument() {
        let command = Arguments::command();
        let argument = command.get_arguments().find(|argument| argument.get_id() == "zeroconf_ip").unwrap();
        assert_eq!(argument.get_env().and_then(|value| value.to_str()), Some("NO5_SPOTIFY_ZEROCONF_IPS"));
        assert_eq!(argument.get_value_delimiter(), Some(','));
    }

    #[test]
    fn seek_and_correction_do_not_resume_a_paused_player() {
        let track_id = SpotifyUri::from_uri("spotify:track:4uLU6hMCjMI75M1A2tKUQC").unwrap();
        for event in [
            PlayerEvent::Seeked {
                play_request_id: 7,
                track_id: track_id.clone(),
                position_ms: 15_000,
            },
            PlayerEvent::PositionCorrection {
                play_request_id: 7,
                track_id: track_id.clone(),
                position_ms: 15_000,
            },
        ] {
            let mut state = PlaybackState::unavailable(1);
            state.apply(ReceiverEvent::SessionConnected, 2);
            state.apply(
                ReceiverEvent::Paused {
                    position_ms: Some(12_000),
                },
                3,
            );
            state.apply(player_event(event).unwrap(), 4);
            assert_eq!(state.state, PlaybackStatus::Paused);
            assert!(state.connected && state.active);
            assert_eq!(state.position_ms, Some(15_000));
            assert_eq!(state.observed_at_epoch_ms, 4);
        }
    }

    #[test]
    fn a_late_seek_cannot_reactivate_a_disconnected_player() {
        let mut state = PlaybackState::unavailable(1);
        state.apply(ReceiverEvent::SessionConnected, 2);
        state.apply(
            ReceiverEvent::Paused {
                position_ms: Some(12_000),
            },
            3,
        );
        state.apply(ReceiverEvent::SessionDisconnected, 4);
        let disconnected = state.clone();
        let event = PlayerEvent::Seeked {
            play_request_id: 7,
            track_id: SpotifyUri::from_uri("spotify:track:4uLU6hMCjMI75M1A2tKUQC").unwrap(),
            position_ms: 15_000,
        };
        state.apply(player_event(event).unwrap(), 5);
        assert_eq!(state, disconnected);
    }
}

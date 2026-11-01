use crate::{
    LoadContextOptions, LoadRequestOptions, PlayContext,
    context_resolver::{ContextAction, ContextResolver, ResolveContext},
    core::{
        Error, Session, SpotifyUri,
        authentication::Credentials,
        dealer::{
            manager::{BoxedStream, BoxedStreamResult, Reply, RequestReply},
            protocol::{Command, FallbackWrapper, Message, Request},
        },
        session::UserAttributes,
        spclient::TransferRequest,
    },
    model::{LoadRequest, PlayingTrack, SpircPlayStatus},
    playback::{
        mixer::Mixer,
        player::{Player, PlayerEvent, PlayerEventChannel},
    },
    protocol::{
        connect::{Cluster, ClusterUpdate, LogoutCommand, SetVolumeCommand},
        context::Context,
        explicit_content_pubsub::UserAttributesUpdate,
        playlist4_external::PlaylistModificationInfo,
        social_connect_v2::SessionUpdate,
        transfer_state::TransferState,
        user_attributes::UserAttributesMutation,
    },
    state::{
        context::{ContextType, ResetContext},
        provider::IsProvider,
        {ConnectConfig, ConnectState},
    },
};
use futures_util::StreamExt;
use librespot_protocol::context_page::ContextPage;
use protobuf::MessageField;
use rand::seq::IteratorRandom;
use std::{
    future::Future,
    sync::{Arc, RwLock},
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use thiserror::Error;
use tokio::{sync::{mpsc, oneshot}, time::{sleep, sleep_until, Instant}};

#[derive(Debug, Error)]
enum SpircError {
    #[error("response payload empty")]
    NoData,
    #[error("{0} had no uri")]
    NoUri(&'static str),
    #[error("message pushed for another URI")]
    InvalidUri(String),
    #[error("failed to put connect state for new device")]
    FailedDealerSetup,
    #[error("unknown endpoint: {0:#?}")]
    UnknownEndpoint(serde_json::Value),
}

impl From<SpircError> for Error {
    fn from(err: SpircError) -> Self {
        use SpircError::*;
        match err {
            NoData | NoUri(_) => Error::unavailable(err),
            InvalidUri(_) | FailedDealerSetup => Error::aborted(err),
            UnknownEndpoint(_) => Error::unimplemented(err),
        }
    }
}

#[derive(Clone, Copy)]
struct SessionConnectBudget {
    attempt: Duration,
    total: Duration,
    attempts: usize,
}

const SESSION_CONNECT_BUDGET: SessionConnectBudget = SessionConnectBudget {
    attempt: Duration::from_secs(5),
    total: Duration::from_secs(30),
    attempts: 6,
};

enum SetupStage {
    ClientToken,
    ApConnect,
    Login5,
    DealerStart,
    ConnectPublish,
}

mod setup_retry {
    use super::Error;
    use crate::core::{error::ErrorKind, http_client::HttpClientError};
    use tungstenite::Error as WsError;
    include!("no5_setup_retry.rs");
}
use setup_retry::{SafeIoKind, setup_failure, retry_setup, RetryStage};

async fn observe_setup_stage<T>(
    stage: SetupStage,
    operation: impl Future<Output = Result<T, Error>>,
) -> Result<T, Error> {
    let (begin, ready, failed) = match stage {
        SetupStage::ClientToken => ("CLIENT_TOKEN_BEGIN", "CLIENT_TOKEN_READY", "CLIENT_TOKEN_FAILED"),
        SetupStage::ApConnect => ("AP_CONNECT_BEGIN", "AP_CONNECT_READY", "AP_CONNECT_FAILED"),
        SetupStage::Login5 => ("LOGIN5_BEGIN", "LOGIN5_READY", "LOGIN5_FAILED"),
        SetupStage::DealerStart => ("DEALER_BEGIN", "DEALER_READY", "DEALER_FAILED"),
        SetupStage::ConnectPublish => ("CONNECT_PUBLISH_BEGIN", "CONNECT_PUBLISH_READY", "CONNECT_PUBLISH_FAILED"),
    };
    let started = Instant::now();
    eprintln!("NO5_SPOTIFY_SETUP phase={begin} elapsedMs=0");
    match operation.await {
        Ok(value) => {
            eprintln!("NO5_SPOTIFY_SETUP phase={ready} elapsedMs={}", started.elapsed().as_millis());
            Ok(value)
        }
        Err(error) => {
            let failure = setup_failure(&error);
            eprintln!("NO5_SPOTIFY_SETUP phase={failed} elapsedMs={} errorKind={:?} cause={} httpStatus={} ioKind={} osError={} timeout={} causeDepth={} depthLimited={}",
                started.elapsed().as_millis(), error.kind, failure.class(), failure.http_status.unwrap_or(0),
                SafeIoKind(failure.io_kind), failure.os_error.unwrap_or(0), failure.timeout,
                failure.cause_depth, failure.depth_limited);
            Err(error)
        }
    }
}

// A replacement/invalidated Session must not resume setup after a retry wait.
async fn wait_session_invalid(session: &Session) {
    while !session.is_invalid() {
        sleep(Duration::from_millis(100)).await;
    }
}

/// Cooperatively bound the complete AP future, including TCP establishment. Core 0.8.0
/// already tries alternative APs for returned errors; only a suspended attempt
/// needs cancellation here. Its resolver has already consumed that AP, so a new
/// call on the SAME session continues the existing deque instead of resetting it.
/// Its synchronous libc DNS lookup cannot be preempted by a Tokio timeout.
async fn connect_with_budget<F, Fut, S>(
    budget: SessionConnectBudget,
    mut connect: F,
    mut uninitialized: S,
) -> Result<(), Error>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<(), Error>>,
    S: FnMut() -> bool,
{
    let started = Instant::now();
    let deadline = started + budget.total;
    for attempt in 0..budget.attempts {
        if !uninitialized() {
            return Err(Error::failed_precondition(
                "Session is already initialized or invalid; no connection retried",
            ));
        }
        if Instant::now() >= deadline {
            break;
        }
        let attempt_deadline = (Instant::now() + budget.attempt).min(deadline);
        // Keep ALL of connect inside the future passed to timeout_at. An await
        // while constructing that argument would leave TCP outside the deadline.
        let result = {
            let pending_connect = async { connect().await };
            tokio::time::timeout_at(attempt_deadline, pending_connect).await
        }; // Drop the timeout and its inner future before inspecting session state.
        match result {
            Ok(result) => return result, // Includes auth errors; core already owns error failover.
            Err(_) => {
                // timeout_at has dropped the old future (and its TCP transport)
                // before this check or any subsequent connect can be created.
                if !uninitialized() {
                    return Err(Error::failed_precondition(
                        "Session changed during connection timeout; no connection retried",
                    ));
                }
                eprintln!("NO5_SPOTIFY_SETUP phase=AP_TIMEOUT elapsedMs={} attempt={} errorKind=DeadlineExceeded",
                    started.elapsed().as_millis(), attempt + 1);
            }
        }
    }
    Err(Error::deadline_exceeded(
        "Spotify AP connection budget exhausted; no connection left pending",
    ))
}

async fn connect_session_bounded(session: &Session, credentials: Credentials) -> Result<(), Error> {
    // In core 0.8.0 the last await precedes username/auth_data/tx_connection
    // initialization. That commit and both task spawns are synchronous within
    // one poll: cancellation cannot interrupt an authenticated half-initialized
    // session. The additional checks fail closed if that invariant ever changes.
    let result = connect_with_budget(
        SESSION_CONNECT_BUDGET,
        || session.connect(credentials.clone(), true),
        || !session.is_invalid() && session.username().is_empty() && session.auth_data().is_empty(),
    )
    .await;
    if result.is_err() {
        session.shutdown();
    }
    result
}

struct SpircTask {
    player: Arc<Player>,
    mixer: Arc<dyn Mixer>,

    /// the state management object
    connect_state: ConnectState,
    connect_established: bool,

    play_request_id: Option<u64>,
    play_status: SpircPlayStatus,

    connection_id_update: BoxedStreamResult<String>,
    connect_state_update: BoxedStreamResult<ClusterUpdate>,
    connect_state_volume_update: BoxedStreamResult<SetVolumeCommand>,
    connect_state_logout_request: BoxedStreamResult<LogoutCommand>,
    playlist_update: BoxedStreamResult<PlaylistModificationInfo>,
    session_update: BoxedStreamResult<FallbackWrapper<SessionUpdate>>,
    connect_state_command: BoxedStream<RequestReply>,
    user_attributes_update: BoxedStreamResult<UserAttributesUpdate>,
    user_attributes_mutation: BoxedStreamResult<UserAttributesMutation>,

    commands: Option<mpsc::UnboundedReceiver<SpircCommand>>,
    mixer_volume_pending: Arc<AtomicBool>,
    queue_snapshot: Arc<RwLock<Option<QueueSnapshot>>>,
    player_events: Option<PlayerEventChannel>,

    context_resolver: ContextResolver,

    shutdown: bool,
    session: Session,

    /// is set when transferring, and used after resolving the contexts to finish the transfer
    pub transfer_state: Option<TransferState>,

    /// when set to true, it will update the volume after [VOLUME_UPDATE_DELAY],
    /// when no other future resolves, otherwise resets the delay
    update_volume: bool,

    /// when set to true, it will update the volume after [UPDATE_STATE_DELAY],
    /// when no other future resolves, otherwise resets the delay
    update_state: bool,

    spirc_id: usize,
}

static SPIRC_COUNTER: AtomicUsize = AtomicUsize::new(0);

#[derive(Debug)]
enum SpircCommand {
    Play,
    PlayPause,
    Pause,
    Prev,
    Next,
    VolumeUp,
    VolumeDown,
    Shutdown,
    Shuffle(bool),
    Repeat(bool),
    RepeatTrack(bool),
    Disconnect { pause: bool },
    SetPosition(u32),
    SetVolume(u16),
    RefreshMixerVolume,
    Activate,
    Transfer(Option<TransferRequest>),
    Load(LoadRequest),
    SelectQueue { revision: String, index: usize, uri: String,
        receipt: oneshot::Sender<Result<NavigationTarget, NavigationError>> },
    SelectContext { revision: String, context: Context, index: usize, uri: String, start_shuffle: Option<bool>,
        receipt: oneshot::Sender<Result<NavigationTarget, NavigationError>> },
}

/// Distinguishes a rejected preflight from a write whose outcome is uncertain.
#[derive(Debug)]
pub enum NavigationError {
    /// The actor refused the preflight before changing playback.
    Rejected,
    /// A submitted operation lacks a reliable receipt; it must not replay.
    Unknown,
}

/// Exact actor-selected item, still requiring a player-thread readback.
#[derive(Debug)]
pub struct NavigationTarget {
    /// Canonical URI actually selected by the actor.
    pub uri: String,
    /// Entry identity, including duplicate occurrences of a URI.
    pub uid: String,
}

const CONTEXT_FETCH_THRESHOLD: usize = 2;

// delay to update volume after a certain amount of time, instead on each update request
const VOLUME_UPDATE_DELAY: Duration = Duration::from_millis(500);
// to reduce updates to remote, we group some request by waiting for a set amount of time
const UPDATE_STATE_DELAY: Duration = Duration::from_millis(200);

/// The spotify connect handle
pub struct Spirc {
    commands: mpsc::UnboundedSender<SpircCommand>,
    mixer_volume_pending: Arc<AtomicBool>,
    queue_snapshot: Arc<RwLock<Option<QueueSnapshot>>>,
}

/// A bounded observation of the actor's actual playback queue, never a command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct QueueSnapshot {
    /// Whether this receiver owns the active playback context.
    pub active: bool,
    /// Actor-observed shuffle option, for confirmed playlist starts.
    pub shuffle: bool,
    /// Actual context URI from the Connect player state.
    pub context_uri: String,
    /// Actual queue revision; an empty string means no observed revision.
    pub revision: String,
    /// Time of the actor observation, separate from media metadata time.
    pub observed_at_epoch_ms: u64,
    /// Up to ten preceding items.
    pub previous: Vec<QueueItem>,
    /// Up to eighty upcoming items, not a complete playlist.
    pub next: Vec<QueueItem>,
    /// Actor-owned identity used to reject a changed queue before a selection write.
    pub source_identity: Vec<String>,
    /// Exact current item identity, for readback rather than optimistic confirmation.
    pub current_uri: String,
    /// Currently selected occurrence, which can differ for duplicate URIs.
    pub current_uid: String,
}

/// Public metadata already observed in a Connect queue item.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct QueueItem {
    /// Spotify item URI.
    pub uri: String,
    /// Observed title, if present and bounded.
    pub title: Option<String>,
    /// Observed artist, if present and bounded.
    pub artist: Option<String>,
}

fn queue_hidden(track: &crate::protocol::player::ProvidedTrack) -> bool {
    track.metadata.get("hidden").is_some_and(|value| value == "true")
}

fn queue_item(track: &crate::protocol::player::ProvidedTrack) -> Option<QueueItem> {
    if !playable_queue_uri(&track.uri) || queue_hidden(track) || track.is_unavailable() {
        return None;
    }
    let text = |key: &str| track.metadata.get(key).filter(|value| {
        !value.is_empty() && value.len() <= 200 && !value.chars().any(char::is_control)
    }).cloned();
    Some(QueueItem { uri: track.uri.clone(), title: text("title"), artist: text("artist_name") })
}

fn playable_queue_uri(uri: &str) -> bool {
    ["spotify:track:", "spotify:episode:"].iter().any(|prefix|
        uri.strip_prefix(prefix).is_some_and(|id| id.len() == 22 && id.bytes().all(|b| b.is_ascii_alphanumeric())))
}

fn selection_position(tracks: &[crate::protocol::player::ProvidedTrack], index: usize, uri: &str) -> Option<usize> {
    tracks.iter().take(80).enumerate().filter(|(_, t)| queue_item(t).is_some())
        .nth(index).filter(|(_, t)| t.uri == uri).map(|(position, _)| position)
}

impl Spirc {
    /// One actor command, one receipt; the queue version is checked immediately before mutation.
    pub async fn select_queue(&self, revision: String, index: usize, uri: String) -> Result<NavigationTarget, NavigationError> {
        let (tx, rx) = oneshot::channel();
        self.commands.send(SpircCommand::SelectQueue { revision, index, uri, receipt: tx })
            .map_err(|_| NavigationError::Rejected)?;
        rx.await.unwrap_or(Err(NavigationError::Unknown))
    }

    /// Applies a pre-resolved exact context. The regular load fallback is never used.
    pub async fn select_context(&self, revision: String, context: Context, index: usize, uri: String) -> Result<NavigationTarget, NavigationError> {
        self.select_context_mode(revision, context, index, uri, None).await
    }
    /// Playlist mode is applied in the same actor operation as the queue CAS and start.
    pub async fn select_context_mode(&self, revision: String, context: Context, index: usize, uri: String, start_shuffle: Option<bool>) -> Result<NavigationTarget, NavigationError> {
        let (tx, rx) = oneshot::channel();
        self.commands.send(SpircCommand::SelectContext { revision, context, index, uri, start_shuffle, receipt: tx })
            .map_err(|_| NavigationError::Rejected)?;
        rx.await.unwrap_or(Err(NavigationError::Unknown))
    }
    /// Initializes a new spotify connect device
    ///
    /// The returned tuple consists out of a handle to the [`Spirc`] that
    /// can control the local connect device when active. And a [`Future`]
    /// which represents the [`Spirc`] event loop that processes the whole
    /// connect device logic.
    pub async fn new(
        config: ConnectConfig,
        session: Session,
        credentials: Credentials,
        player: Arc<Player>,
        mixer: Arc<dyn Mixer>,
    ) -> Result<(Spirc, impl Future<Output = ()>), Error> {
        fn extract_connection_id(msg: Message) -> Result<String, Error> {
            let connection_id = msg
                .headers
                .get("Spotify-Connection-Id")
                .ok_or_else(|| SpircError::InvalidUri(msg.uri.clone()))?;
            Ok(connection_id.to_owned())
        }

        let spirc_id = SPIRC_COUNTER.fetch_add(1, Ordering::AcqRel);
        debug!("new Spirc[{spirc_id}]");

        let connect_state = ConnectState::new(config, &session);

        let connection_id_update = session
            .dealer()
            .listen_for("hm://pusher/v1/connections/", extract_connection_id)?;

        let connect_state_update = session
            .dealer()
            .listen_for("hm://connect-state/v1/cluster", Message::from_raw)?;

        let connect_state_volume_update = session
            .dealer()
            .listen_for("hm://connect-state/v1/connect/volume", Message::from_raw)?;

        let connect_state_logout_request = session
            .dealer()
            .listen_for("hm://connect-state/v1/connect/logout", Message::from_raw)?;

        let playlist_update = session
            .dealer()
            .listen_for("hm://playlist/v2/playlist/", Message::from_raw)?;

        let session_update = session
            .dealer()
            .listen_for("social-connect/v2/session_update", Message::try_from_json)?;

        let user_attributes_update = session
            .dealer()
            .listen_for("spotify:user:attributes:update", Message::from_raw)?;

        // can be trigger by toggling autoplay in a desktop client
        let user_attributes_mutation = session
            .dealer()
            .listen_for("spotify:user:attributes:mutated", Message::from_raw)?;

        let connect_state_command = session
            .dealer()
            .handle_for("hm://connect-state/v1/player/command")?;

        // pre-acquire client_token, preventing multiple request while running
        let _ = observe_setup_stage(SetupStage::ClientToken, retry_setup(RetryStage::ClientToken,
            || async { session.spclient().client_token().await }, wait_session_invalid(&session))).await?;

        // Connect *after* all message listeners are registered
        observe_setup_stage(SetupStage::ApConnect, connect_session_bounded(&session, credentials)).await?;

        // pre-acquire access_token (we need to be authenticated to retrieve a token)
        let _ = observe_setup_stage(SetupStage::Login5, retry_setup(RetryStage::Login5,
            || async { session.login5().auth_token().await }, wait_session_invalid(&session))).await?;

        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let mixer_volume_pending = Arc::new(AtomicBool::new(false));
        let queue_snapshot = Arc::new(RwLock::new(None));

        let player_events = player.get_player_event_channel();

        let mut task = SpircTask {
            player,
            mixer,

            connect_state,
            connect_established: false,

            play_request_id: None,
            play_status: SpircPlayStatus::Stopped,

            connection_id_update,
            connect_state_update,
            connect_state_volume_update,
            connect_state_logout_request,
            playlist_update,
            session_update,
            connect_state_command,
            user_attributes_update,
            user_attributes_mutation,
            commands: Some(cmd_rx),
            mixer_volume_pending: Arc::clone(&mixer_volume_pending),
            queue_snapshot: Arc::clone(&queue_snapshot),
            player_events: Some(player_events),

            context_resolver: ContextResolver::new(session.clone()),

            shutdown: false,
            session,

            transfer_state: None,
            update_volume: false,
            update_state: false,

            spirc_id,
        };

        let spirc = Spirc { commands: cmd_tx, mixer_volume_pending, queue_snapshot };

        let initial_volume = task.connect_state.device_info().volume;
        task.connect_state.set_volume(0);

        match initial_volume.try_into() {
            Ok(volume) => {
                task.set_volume(volume);
                // we don't want to update the volume initially,
                // we just want to set the mixer to the correct volume
                task.update_volume = false;
            }
            Err(why) => error!("failed to update initial volume: {why}"),
        };

        Ok((spirc, task.run()))
    }

    /// Safely shutdowns the spirc.
    ///
    /// This pauses the playback, disconnects the connect device and
    /// bring the future initially returned to an end.
    pub fn shutdown(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Shutdown)?)
    }

    /// Reads a snapshot without adding work to the command queue or doing IO.
    pub fn observed_queue(&self) -> Option<QueueSnapshot> {
        self.queue_snapshot.read().ok()?.clone()
    }

    /// Resumes the playback
    ///
    /// Does nothing if we are not the active device, or it isn't paused.
    pub fn play(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Play)?)
    }

    /// Resumes or pauses the playback
    ///
    /// Does nothing if we are not the active device.
    pub fn play_pause(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::PlayPause)?)
    }

    /// Pauses the playback
    ///
    /// Does nothing if we are not the active device, or if it isn't playing.
    pub fn pause(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Pause)?)
    }

    /// Seeks to the beginning or skips to the previous track.
    ///
    /// Seeks to the beginning when the current track position
    /// is greater than 3 seconds.
    ///
    /// Does nothing if we are not the active device.
    pub fn prev(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Prev)?)
    }

    /// Skips to the next track.
    ///
    /// Does nothing if we are not the active device.
    pub fn next(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Next)?)
    }

    /// Increases the volume by configured steps of [ConnectConfig].
    ///
    /// Does nothing if we are not the active device.
    pub fn volume_up(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::VolumeUp)?)
    }

    /// Decreases the volume by configured steps of [ConnectConfig].
    ///
    /// Does nothing if we are not the active device.
    pub fn volume_down(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::VolumeDown)?)
    }

    /// Shuffles the playback according to the value.
    ///
    /// If true shuffles/reshuffles the playback. Otherwise, does
    /// nothing (if not shuffled) or unshuffles the playback while
    /// resuming at the position of the current track.
    ///
    /// Does nothing if we are not the active device.
    pub fn shuffle(&self, shuffle: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Shuffle(shuffle))?)
    }

    /// Repeats the playback context according to the value.
    ///
    /// Does nothing if we are not the active device.
    pub fn repeat(&self, repeat: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Repeat(repeat))?)
    }

    /// Repeats the current track if true.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// Skipping to the next track disables the repeating.
    pub fn repeat_track(&self, repeat: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::RepeatTrack(repeat))?)
    }

    /// Update the volume to the given value.
    ///
    /// Does nothing if we are not the active device.
    pub fn set_volume(&self, volume: u16) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SetVolume(volume))?)
    }

    /// Publish the mixer's current confirmed volume without issuing a mixer command.
    ///
    /// Notifications coalesce to one pending observation. No historical volume
    /// value is queued; the actor reads the mixer when processing the notification.
    pub fn notify_mixer_volume(&self) -> Result<(), Error> {
        if !self.mixer_volume_pending.swap(true, Ordering::AcqRel) {
            if let Err(error) = self.commands.send(SpircCommand::RefreshMixerVolume) {
                self.mixer_volume_pending.store(false, Ordering::Release);
                return Err(error.into());
            }
        }
        Ok(())
    }

    /// Updates the position to the given value.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// If value is greater than the track duration,
    /// the update is ignored.
    pub fn set_position_ms(&self, position_ms: u32) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::SetPosition(position_ms))?)
    }

    /// Load a new context and replace the current.
    ///
    /// Does nothing if we are not the active device.
    ///
    /// Does not overwrite the queue.
    pub fn load(&self, command: LoadRequest) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Load(command))?)
    }

    /// Disconnects the current device and pauses the playback according the value.
    ///
    /// Does nothing if we are not the active device.
    pub fn disconnect(&self, pause: bool) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Disconnect { pause })?)
    }

    /// Acquires the control as active connect device.
    ///
    /// Does not [Spirc::transfer] the playback. Does nothing if we are not the active device.
    pub fn activate(&self) -> Result<(), Error> {
        Ok(self.commands.send(SpircCommand::Activate)?)
    }

    /// Acquires the control as active connect device over the transfer flow.
    ///
    /// Does nothing if we are not the active device.
    pub fn transfer(&self, transfer_request: Option<TransferRequest>) -> Result<(), Error> {
        Ok(self
            .commands
            .send(SpircCommand::Transfer(transfer_request))?)
    }
}

impl SpircTask {
    fn publish_queue_snapshot(&self) {
        let player = self.connect_state.player();
        let current = player.track.as_ref();
        let mut identity = vec![player.context_uri.clone(), player.queue_revision.clone(),
            current.map(|t| t.uid.clone()).unwrap_or_default(),
            current.map(|t| t.uri.clone()).unwrap_or_default(),
            self.connect_state.shuffling_context().to_string(), self.connect_state.is_active().to_string()];
        for track in player.next_tracks.iter().take(80) {
            identity.extend([track.uid.clone(), track.uri.clone(), track.provider.clone(),
                queue_hidden(track).to_string(), track.is_unavailable().to_string()]);
        }
        if let Ok(mut slot) = self.queue_snapshot.write() {
            let revision = slot.as_ref().filter(|old| old.source_identity == identity)
                .map(|old| old.revision.clone()).unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
            *slot = Some(QueueSnapshot {
                active: self.connect_state.is_active(), shuffle: self.connect_state.shuffling_context(), context_uri: player.context_uri.clone(), revision,
                observed_at_epoch_ms: SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64,
                previous: player.prev_tracks.iter().take(10).filter_map(queue_item).collect(),
                next: player.next_tracks.iter().take(80).filter_map(queue_item).collect(),
                source_identity: identity, current_uri: current.map(|t| t.uri.clone()).unwrap_or_default(),
                current_uid: current.map(|t| t.uid.clone()).unwrap_or_default(),
            });
        }
    }

    fn selection_preflight(&self, revision: &str) -> Result<(), NavigationError> {
        self.publish_queue_snapshot();
        if self.session.is_invalid() || !self.connect_state.is_active() ||
            !self.queue_snapshot.read().ok().and_then(|s| s.clone()).is_some_and(|s| s.revision == revision) {
            return Err(NavigationError::Rejected);
        }
        Ok(())
    }

    fn select_queue_item(&mut self, revision: &str, index: usize, uri: &str) -> Result<NavigationTarget, NavigationError> {
        self.selection_preflight(revision)?;
        let tracks = &self.connect_state.player().next_tracks;
        let position = selection_position(tracks, index, uri).ok_or(NavigationError::Rejected)?;
        let target_uid = tracks[position].uid.clone();
        // next_track skips delimiter/unavailable entries, but other hidden items
        // must not become an unintended selection on the way to this target.
        if tracks[..=position].iter().any(|t| queue_item(t).is_none() &&
            !t.is_unavailable() && !t.uid.starts_with("delimiter")) {
            return Err(NavigationError::Rejected);
        }
        let steps = tracks[..=position].iter().filter(|t| queue_item(t).is_some()).count();
        let repeat_track = self.connect_state.repeat_track();
        let advanced = (|| {
            for _ in 0..steps {
                if self.connect_state.next_track().map_err(|_| NavigationError::Unknown)?.is_none() { return Err(NavigationError::Unknown); }
            }
            Ok(())
        })();
        self.connect_state.set_repeat_track(repeat_track);
        advanced?;
        if self.connect_state.current_track(|t| &t.uri) != uri ||
            self.connect_state.current_track(|t| &t.uid) != &target_uid {
            return Err(NavigationError::Unknown);
        }
        self.load_track(true, 0).map_err(|_| NavigationError::Unknown)?;
        self.publish_queue_snapshot();
        Ok(NavigationTarget { uri: uri.into(), uid: target_uid })
    }

    fn select_context_item(&mut self, revision: &str, context: Context, index: usize, uri: &str, start_shuffle: Option<bool>)
        -> Result<NavigationTarget, NavigationError> {
        self.selection_preflight(revision)?;
        let tracks = context.pages.first().map(|p| &p.tracks).ok_or(NavigationError::Rejected)?;
        if tracks.len() > 5000 || !playable_queue_uri(uri) ||
            tracks.get(index).map(|t| t.uri()) != Some(uri) || context.uri().is_empty() {
            return Err(NavigationError::Rejected);
        }
        let index = if start_shuffle == Some(true) {
            tracks.iter().enumerate().filter(|(_, t)| playable_queue_uri(t.uri()))
                .map(|(i, _)| i).choose(&mut rand::rng()).ok_or(NavigationError::Rejected)?
        } else { index };
        // Keep Main. Ordinary selection retains options; an explicit start sets shuffle atomically.
        if let Some(shuffle) = start_shuffle {
            self.connect_state.set_shuffle(shuffle);
            self.connect_state.set_repeat_track(false);
        }
        // No regular load fallback.
        self.connect_state.reset_context(ResetContext::Completely);
        self.connect_state.set_active_context(ContextType::Default);
        self.context_resolver.clear();
        let pointers = self.connect_state.update_context(context, ContextType::Default)
            .map_err(|_| NavigationError::Unknown)?.unwrap_or_default();
        for pointer in pointers { self.context_resolver.add(ResolveContext::append_context(pointer)); }
        self.connect_state.set_current_track(index).map_err(|_| NavigationError::Unknown)?;
        if self.connect_state.shuffling_context() {
            self.connect_state.shuffle_new().map_err(|_| NavigationError::Unknown)?;
        }
        self.connect_state.clear_next_tracks();
        self.connect_state.fill_up_next_tracks().map_err(|_| NavigationError::Unknown)?;
        self.load_track(true, 0).map_err(|_| NavigationError::Unknown)?;
        if let Some(shuffle)=start_shuffle { self.player.emit_shuffle_changed_event(shuffle); }
        self.publish_queue_snapshot();
        Ok(NavigationTarget { uri: self.connect_state.current_track(|t| t.uri.clone()),
            uid: self.connect_state.current_track(|t| t.uid.clone()) })
    }
    async fn run(mut self) {
        // simplify unwrapping of received item or parsed result
        macro_rules! unwrap {
            ( $next:expr, |$some:ident| $use_some:expr ) => {
                match $next {
                    Some($some) => $use_some,
                    None => {
                        error!("{} selected, but none received", stringify!($next));
                        break;
                    }
                }
            };
            ( $next:expr, match |$ok:ident| $use_ok:expr ) => {
                unwrap!($next, |$ok| match $ok {
                    Ok($ok) => $use_ok,
                    Err(why) => error!("could not parse {}: {}", stringify!($ok), why),
                })
            };
        }

        if let Err(why) = observe_setup_stage(SetupStage::DealerStart, self.session.dealer().start()).await {
            error!("starting dealer failed: {why}");
            return;
        }

        let mut observation_tick = tokio::time::interval(Duration::from_secs(1));
        observation_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut volume_deadline = None;
        while !self.session.is_invalid() && !self.shutdown {
            self.publish_queue_snapshot();
            // A continuous fader stream must not restart the batching timer.
            if self.update_volume && volume_deadline.is_none() {
                volume_deadline = Some(Instant::now() + VOLUME_UPDATE_DELAY);
            } else if !self.update_volume {
                volume_deadline = None;
            }
            let commands = self.commands.as_mut();
            let player_events = self.player_events.as_mut();

            // when state and volume update have a higher priority than context resolving
            // because of that the context resolving has to wait, so that the other tasks can finish
            let allow_context_resolving = !self.update_state && !self.update_volume;

            tokio::select! {
                // Refresh a read-only snapshot while PAUSED; this issues no Connect or DSP command.
                _ = observation_tick.tick() => {},
                // startup of the dealer requires a connection_id, which is retrieved at the very beginning
                connection_id_update = self.connection_id_update.next() => unwrap! {
                    connection_id_update,
                    match |connection_id| if let Err(why) = self.handle_connection_id_update(connection_id).await {
                        error!("failed handling connection id update: {why}");
                        break;
                    }
                },
                // main dealer update of any remote device updates
                cluster_update = self.connect_state_update.next() => unwrap! {
                    cluster_update,
                    match |cluster_update| if let Err(e) = self.handle_cluster_update(cluster_update).await {
                        error!("could not dispatch connect state update: {e}");
                    }
                },
                // main dealer request handling (dealer expects an answer)
                request = self.connect_state_command.next() => unwrap! {
                    request,
                    |request| if let Err(e) = self.handle_connect_state_request(request).await {
                        error!("couldn't handle connect state command: {e}");
                    }
                },
                // volume request handling is send separately (it's more like a fire forget)
                volume_update = self.connect_state_volume_update.next() => unwrap! {
                    volume_update,
                    match |volume_update| match volume_update.volume.try_into() {
                        Ok(volume) => self.set_volume(volume),
                        Err(why) => error!("can't update volume, failed to parse i32 to u16: {why}")
                    }
                },
                logout_request = self.connect_state_logout_request.next() => unwrap! {
                    logout_request,
                    |logout_request| {
                        error!("received logout request, currently not supported: {logout_request:#?}");
                        // todo: call logout handling
                    }
                },
                playlist_update = self.playlist_update.next() => unwrap! {
                    playlist_update,
                    match |playlist_update| if let Err(why) = self.handle_playlist_modification(playlist_update) {
                        error!("failed to handle playlist modification: {why}")
                    }
                },
                user_attributes_update = self.user_attributes_update.next() => unwrap! {
                    user_attributes_update,
                    match |attributes| self.handle_user_attributes_update(attributes)
                },
                user_attributes_mutation = self.user_attributes_mutation.next() => unwrap! {
                    user_attributes_mutation,
                    match |attributes| self.handle_user_attributes_mutation(attributes)
                },
                session_update = self.session_update.next() => unwrap! {
                    session_update,
                    match |session_update| self.handle_session_update(session_update)
                },
                cmd = async { commands?.recv().await }, if commands.is_some() && self.connect_established => if let Some(cmd) = cmd {
                    if let Err(e) = self.handle_command(cmd).await {
                        debug!("could not dispatch command: {e}");
                    }
                },
                event = async { player_events?.recv().await }, if player_events.is_some() => if let Some(event) = event {
                    if let Err(e) = self.handle_player_event(event) {
                        error!("could not dispatch player event: {e}");
                    }
                },
                _ = async { sleep(UPDATE_STATE_DELAY).await }, if self.update_state => {
                    self.update_state = false;

                    if let Err(why) = self.notify().await {
                        error!("state update: {why}")
                    }
                },
                _ = async { sleep_until(volume_deadline.unwrap()).await }, if self.update_volume => {
                    self.update_volume = false;
                    volume_deadline = None;

                    info!("delayed volume update for all devices: volume is now {}", self.connect_state.device_info().volume);
                    if let Err(why) = self.connect_state.notify_volume_changed(&self.session).await {
                        error!("error updating connect state for volume update: {why}")
                    }

                    // for some reason the web-player does need two separate updates, so that the
                    // position of the current track is retained, other clients also send a state
                    // update before they send the volume update
                    if let Err(why) = self.notify().await {
                        error!("error updating connect state for volume update: {why}")
                    }
                },
                // context resolver handling, the idea/reason behind it the following:
                //
                // when we request a context that has multiple pages (for example an artist)
                // resolving all pages at once can take around ~1-30sec, when we resolve
                // everything at once that would block our main loop for that time
                //
                // to circumvent this behavior, we request each context separately here and
                // finish after we received our last item of a type
                next_context = async {
                    self.context_resolver.get_next_context(|| {
                        // Sending local file URIs to this endpoint results in a Bad Request status.
                        // It's likely appropriate to filter them out anyway; Spotify's backend
                        // has no knowledge about these tracks and so can't do anything with them.
                        self.connect_state.recent_track_uris()
                            .into_iter()
                            .filter(|t| !t.starts_with("spotify:local"))
                            .collect::<Vec<_>>()
                    }).await
                }, if allow_context_resolving && self.context_resolver.has_next() => {
                    let update_state = self.handle_next_context(next_context);
                    if update_state {
                        if let Err(why) = self.notify().await {
                            error!("update after context resolving failed: {why}")
                        }
                    }
                },
                else => break
            }
        }

        if let Ok(mut snapshot) = self.queue_snapshot.write() {
            *snapshot = None;
        }
        if !self.shutdown && self.connect_state.is_active() {
            warn!("unexpected shutdown");
            if let Err(why) = self.handle_disconnect().await {
                error!("error during disconnecting: {why}")
            }
        }

        // this should clear the active session id, leaving an empty state
        if let Err(why) = self.session.spclient().delete_connect_state_request().await {
            error!("error during connect state deletion: {why}")
        };

        self.session.dealer().close().await;
    }

    fn handle_next_context(&mut self, next_context: Result<Context, Error>) -> bool {
        let next_context = match next_context {
            Err(why) => {
                self.context_resolver.mark_next_unavailable();
                self.context_resolver.remove_used_and_invalid();
                error!("{why}");
                return false;
            }
            Ok(ctx) => ctx,
        };

        debug!("handling next context {:?}", next_context.uri);

        match self
            .context_resolver
            .apply_next_context(&mut self.connect_state, next_context)
        {
            Ok(remaining) => {
                if let Some(remaining) = remaining {
                    self.context_resolver.add_list(remaining)
                }
            }
            Err(why) => {
                error!("{why}")
            }
        }

        let update_state = if self
            .context_resolver
            .try_finish(&mut self.connect_state, &mut self.transfer_state)
        {
            self.add_autoplay_resolving_when_required();
            true
        } else {
            false
        };

        self.context_resolver.remove_used_and_invalid();
        update_state
    }

    // todo: is the time_delta still necessary?
    fn now_ms(&self) -> i64 {
        let dur = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_else(|err| err.duration());

        dur.as_millis() as i64 + 1000 * self.session.time_delta()
    }

    async fn handle_command(&mut self, cmd: SpircCommand) -> Result<(), Error> {
        trace!("Received SpircCommand::{cmd:?}");
        match cmd {
            SpircCommand::SelectQueue { revision, index, uri, receipt } => {
                let result = self.select_queue_item(&revision, index, &uri);
                let wrote = !matches!(result, Err(NavigationError::Rejected));
                let _ = receipt.send(result);
                if wrote { self.update_state = true; }
            }
            SpircCommand::SelectContext { revision, context, index, uri, start_shuffle, receipt } => {
                let result = self.select_context_item(&revision, context, index, &uri, start_shuffle);
                let wrote = !matches!(result, Err(NavigationError::Rejected));
                let _ = receipt.send(result);
                if wrote { self.update_state = true; }
            }
            SpircCommand::RefreshMixerVolume => {
                self.mixer_volume_pending.store(false, Ordering::Release);
                if self.connect_state.is_active() {
                    if let Some(volume) = changed_mixer_volume(self.mixer.as_ref(), self.connect_state.device_info().volume) {
                        self.connect_state.set_volume(volume as u32);
                        self.update_volume = true;
                    }
                }
                // This is an observation, never set_volume()/Mixer::set_volume().
                // Existing batched Connect publication handles the notification.
                return Ok(());
            }
            SpircCommand::Shutdown => {
                trace!("Received SpircCommand::Shutdown");
                self.handle_pause();
                self.handle_disconnect().await?;
                self.shutdown = true;
                if let Some(rx) = self.commands.as_mut() {
                    rx.close()
                }
            }
            SpircCommand::Transfer(request) if !self.connect_state.is_active() => {
                let device_id = self.session.device_id();
                self.session
                    .spclient()
                    .transfer(device_id, device_id, request.as_ref())
                    .await?;
                return Ok(());
            }
            SpircCommand::Activate if !self.connect_state.is_active() => {
                trace!("Received SpircCommand::{cmd:?}");
                self.handle_activate();
                return self.notify().await;
            }
            SpircCommand::Transfer(..) | SpircCommand::Activate => {
                warn!("SpircCommand::{cmd:?} will be ignored while already active")
            }
            _ if !self.connect_state.is_active() => {
                warn!("SpircCommand::{cmd:?} will be ignored while Not Active")
            }
            SpircCommand::Disconnect { pause } => {
                if pause {
                    self.handle_pause()
                }
                return self.handle_disconnect().await;
            }
            SpircCommand::Play => self.handle_play(),
            SpircCommand::PlayPause => self.handle_play_pause(),
            SpircCommand::Pause => self.handle_pause(),
            SpircCommand::Prev => self.handle_prev()?,
            SpircCommand::Next => self.handle_next(None)?,
            SpircCommand::VolumeUp => self.handle_volume_up(),
            SpircCommand::VolumeDown => self.handle_volume_down(),
            SpircCommand::Shuffle(shuffle) => self.handle_shuffle(shuffle)?,
            SpircCommand::Repeat(repeat) => self.handle_repeat_context(repeat)?,
            SpircCommand::RepeatTrack(repeat) => self.handle_repeat_track(repeat),
            SpircCommand::SetPosition(position) => self.handle_seek(position),
            SpircCommand::SetVolume(volume) => self.set_volume(volume),
            SpircCommand::Load(command) => self.handle_load(command, None, None).await?,
        };

        self.notify().await
    }

    fn handle_player_event(&mut self, event: PlayerEvent) -> Result<(), Error> {
        if let PlayerEvent::TrackChanged { audio_item } = event {
            self.connect_state.update_duration(audio_item.duration_ms);
            self.update_state = true;
            return Ok(());
        }

        // update play_request_id
        if let PlayerEvent::PlayRequestIdChanged { play_request_id } = event {
            self.play_request_id = Some(play_request_id);
            return Ok(());
        }

        let is_current_track = matches! {
            (event.get_play_request_id(), self.play_request_id),
            (Some(event_id), Some(current_id)) if event_id == current_id
        };

        // we only process events if the play_request_id matches. If it doesn't, it is
        // an event that belongs to a previous track and only arrives now due to a race
        // condition. In this case we have updated the state already and don't want to
        // mess with it.
        if !is_current_track {
            return Ok(());
        }

        match event {
            PlayerEvent::EndOfTrack { .. } => {
                let next_track = self
                    .connect_state
                    .repeat_track()
                    .then(|| self.connect_state.current_track(|t| t.uri.clone()));

                self.handle_next(next_track)?
            }
            PlayerEvent::Loading { .. } => match self.play_status {
                SpircPlayStatus::LoadingPlay { position_ms } => {
                    self.connect_state
                        .update_position(position_ms, self.now_ms());
                    trace!("==> LoadingPlay");
                }
                SpircPlayStatus::LoadingPause { position_ms } => {
                    self.connect_state
                        .update_position(position_ms, self.now_ms());
                    trace!("==> LoadingPause");
                }
                _ => {
                    self.connect_state.update_position(0, self.now_ms());
                    trace!("==> Loading");
                }
            },
            PlayerEvent::Seeked { position_ms, .. } => {
                trace!("==> Seeked");
                self.connect_state
                    .update_position(position_ms, self.now_ms())
            }
            PlayerEvent::Playing { position_ms, .. }
            | PlayerEvent::PositionCorrection { position_ms, .. } => {
                trace!("==> Playing");
                let new_nominal_start_time = self.now_ms() - position_ms as i64;
                match self.play_status {
                    SpircPlayStatus::Playing {
                        ref mut nominal_start_time,
                        ..
                    } => {
                        if (*nominal_start_time - new_nominal_start_time).abs() > 100 {
                            *nominal_start_time = new_nominal_start_time;
                            self.connect_state
                                .update_position(position_ms, self.now_ms());
                        } else {
                            return Ok(());
                        }
                    }
                    SpircPlayStatus::LoadingPlay { .. } | SpircPlayStatus::LoadingPause { .. } => {
                        self.connect_state
                            .update_position(position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Playing {
                            nominal_start_time: new_nominal_start_time,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    _ => return Ok(()),
                }
            }
            PlayerEvent::Paused {
                position_ms: new_position_ms,
                ..
            } => {
                trace!("==> Paused");
                match self.play_status {
                    SpircPlayStatus::Paused { .. } | SpircPlayStatus::Playing { .. } => {
                        self.connect_state
                            .update_position(new_position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Paused {
                            position_ms: new_position_ms,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    SpircPlayStatus::LoadingPlay { .. } | SpircPlayStatus::LoadingPause { .. } => {
                        self.connect_state
                            .update_position(new_position_ms, self.now_ms());
                        self.play_status = SpircPlayStatus::Paused {
                            position_ms: new_position_ms,
                            preloading_of_next_track_triggered: false,
                        };
                    }
                    _ => return Ok(()),
                }
            }
            PlayerEvent::Stopped { .. } => {
                trace!("==> Stopped");
                match self.play_status {
                    SpircPlayStatus::Stopped => return Ok(()),
                    _ => self.play_status = SpircPlayStatus::Stopped,
                }
            }
            PlayerEvent::TimeToPreloadNextTrack { .. } => {
                self.handle_preload_next_track();
                return Ok(());
            }
            PlayerEvent::Unavailable { track_id, .. } => {
                self.handle_unavailable(&track_id)?;
                if self.connect_state.current_track(|t| &t.uri) == &track_id.to_uri()? {
                    self.handle_next(None)?
                }
            }
            _ => return Ok(()),
        }

        self.update_state = true;
        Ok(())
    }

    async fn handle_connection_id_update(&mut self, connection_id: String) -> Result<(), Error> {
        let registration_started = Instant::now();
        trace!("Received connection ID update: {connection_id:?}");
        self.session.set_connection_id(&connection_id);

        let cluster = match observe_setup_stage(
            SetupStage::ConnectPublish,
            self.connect_state.notify_new_device_appeared(&self.session),
        ).await
        {
            Ok(res) => match Cluster::parse_from_bytes(&res) {
                Ok(cluster) => Some(cluster),
                Err(_) => {
                    eprintln!("NO5_SPOTIFY_SETUP phase=CONNECT_STATE_PARSE_FAILED elapsedMs={} errorKind=FailedPrecondition",
                        registration_started.elapsed().as_millis());
                    None
                }
            },
            Err(why) => {
                error!("{why:?}");
                None
            }
        }
        .ok_or(SpircError::FailedDealerSetup)?;

        debug!(
            "successfully put connect state for {} with connection-id {connection_id}",
            self.session.device_id()
        );

        self.connect_established = true;
        eprintln!("NO5_SPOTIFY_SETUP phase=CONNECT_REGISTERED elapsedMs={}",
            registration_started.elapsed().as_millis());

        let same_session = cluster.player_state.session_id == self.session.session_id()
            || cluster.player_state.session_id.is_empty();
        if !cluster.active_device_id.is_empty() || !same_session {
            info!(
                "active device is <{}> with session <{}>",
                cluster.active_device_id, cluster.player_state.session_id
            );
            return Ok(());
        } else if cluster.transfer_data.is_empty() {
            debug!("got empty transfer state, do nothing");
            return Ok(());
        } else {
            info!(
                "trying to take over control automatically, session_id: {}",
                cluster.player_state.session_id
            )
        }

        use protobuf::Message;

        match TransferState::parse_from_bytes(&cluster.transfer_data) {
            Ok(transfer_state) => self.handle_transfer(transfer_state)?,
            Err(why) => error!("failed to take over control: {why}"),
        }

        Ok(())
    }

    fn handle_user_attributes_update(&mut self, update: UserAttributesUpdate) {
        trace!("Received attributes update: {update:#?}");
        let attributes: UserAttributes = update
            .pairs
            .iter()
            .map(|(key, value)| (key.to_owned(), value.to_owned()))
            .collect();
        self.session.set_user_attributes(attributes)
    }

    fn handle_user_attributes_mutation(&mut self, mutation: UserAttributesMutation) {
        for attribute in mutation.fields.iter() {
            let key = &attribute.name;

            if key == "autoplay" && self.session.config().autoplay.is_some() {
                trace!("Autoplay override active. Ignoring mutation.");
                continue;
            }

            if let Some(old_value) = self.session.user_data().attributes.get(key) {
                let new_value = match old_value.as_ref() {
                    "0" => "1",
                    "1" => "0",
                    _ => old_value,
                };
                self.session.set_user_attribute(key, new_value);

                trace!("Received attribute mutation, {key} was {old_value} is now {new_value}");

                if key == "filter-explicit-content" && new_value == "1" {
                    self.player
                        .emit_filter_explicit_content_changed_event(matches!(new_value, "1"));
                }

                if key == "autoplay" && old_value != new_value {
                    self.player
                        .emit_auto_play_changed_event(matches!(new_value, "1"));

                    self.add_autoplay_resolving_when_required()
                }
            } else {
                trace!("Received attribute mutation for {key} but key was not found!");
            }
        }
    }

    async fn handle_cluster_update(
        &mut self,
        mut cluster_update: ClusterUpdate,
    ) -> Result<(), Error> {
        let reason = cluster_update.update_reason.enum_value();

        let device_ids = cluster_update.devices_that_changed.join(", ");
        debug!(
            "cluster update: {reason:?} from {device_ids}, active device: {}",
            cluster_update.cluster.active_device_id
        );

        if let Some(cluster) = cluster_update.cluster.take() {
            let became_inactive = self.connect_state.is_active()
                && cluster.active_device_id != self.session.device_id();
            if became_inactive {
                info!("device became inactive");
                self.handle_disconnect().await?;
                self.handle_stop();
            } else if self.connect_state.is_active() {
                // fixme: workaround fix, because of missing information why it behaves like it does
                //  background: when another device sends a connect-state update, some player's position de-syncs
                //  tried: providing session_id, playback_id, track-metadata "track_player"
                self.update_state = true;
            }
        } else if self.connect_state.is_active() {
            self.connect_state.became_inactive(&self.session).await?;
        }

        Ok(())
    }

    async fn handle_connect_state_request(
        &mut self,
        (request, sender): RequestReply,
    ) -> Result<(), Error> {
        self.connect_state.set_last_command(request.clone());

        debug!(
            "handling: '{}' from {}",
            request.command, request.sent_by_device_id
        );

        let response = match self.handle_request(request).await {
            Ok(_) => Reply::Success,
            Err(why) => {
                error!("failed to handle request: {why}");
                Reply::Failure
            }
        };

        sender.send(response).map_err(Into::into)
    }

    async fn handle_request(&mut self, request: Request) -> Result<(), Error> {
        use Command::*;

        match request.command {
            // errors and unknown commands
            Transfer(transfer) if transfer.data.is_none() => {
                warn!("transfer endpoint didn't contain any data to transfer");
                Err(SpircError::NoData)?
            }
            Unknown(unknown) => Err(SpircError::UnknownEndpoint(unknown))?,
            // implicit update of the connect_state
            UpdateContext(update_context) => {
                if matches!(update_context.context.uri, Some(ref uri) if uri != self.connect_state.context_uri())
                {
                    debug!(
                        "ignoring context update for <{:?}>, because it isn't the current context <{}>",
                        update_context.context.uri,
                        self.connect_state.context_uri()
                    )
                } else {
                    self.context_resolver.add(ResolveContext::from_context(
                        update_context.context,
                        ContextType::Default,
                        ContextAction::Replace,
                    ))
                }
                return Ok(());
            }
            // modification and update of the connect_state
            Transfer(transfer) => {
                self.handle_transfer(transfer.data.expect("by condition checked"))?;
                return self.notify().await;
            }
            Play(mut play) => {
                if !self.connect_state.is_active() {
                    self.handle_activate()
                }

                let context = match play.context.uri {
                    Some(s) => PlayContext::Uri(s),
                    None if !play.context.pages.is_empty() => PlayContext::Tracks(
                        play.context
                            .pages
                            .iter()
                            .cloned()
                            .flat_map(|p| p.tracks)
                            .flat_map(|t| t.uri)
                            .collect(),
                    ),
                    None => Err(SpircError::NoUri("context"))?,
                };

                let context_options = play
                    .options
                    .player_options_override
                    .map(Into::into)
                    .map(LoadContextOptions::Options);

                let fallback_index = play
                    .options
                    .skip_to
                    .as_ref()
                    .and_then(|s| s.track_index)
                    .map(|i| i as usize);

                self.handle_load(
                    LoadRequest {
                        context,
                        options: LoadRequestOptions {
                            start_playing: true,
                            seek_to: play.options.seek_to.unwrap_or_default(),
                            playing_track: play.options.skip_to.and_then(|s| s.try_into().ok()),
                            context_options,
                        },
                    },
                    play.context.pages.pop(),
                    fallback_index,
                )
                .await?;

                self.connect_state.set_origin(play.play_origin)
            }
            Pause(_) => self.handle_pause(),
            SeekTo(seek_to) => {
                // for some reason the position is stored in value, not in position
                trace!("seek to {seek_to:?}");
                self.handle_seek(seek_to.value)
            }
            SetShufflingContext(shuffle) => self.handle_shuffle(shuffle.value)?,
            SetRepeatingContext(repeat_context) => {
                self.handle_repeat_context(repeat_context.value)?
            }
            SetRepeatingTrack(repeat_track) => self.handle_repeat_track(repeat_track.value),
            AddToQueue(add_to_queue) => self.connect_state.add_to_queue(add_to_queue.track, true),
            SetQueue(set_queue) => self.connect_state.handle_set_queue(set_queue),
            SetOptions(set_options) => {
                if let Some(repeat_context) = set_options.repeating_context {
                    self.handle_repeat_context(repeat_context)?
                }

                if let Some(repeat_track) = set_options.repeating_track {
                    self.handle_repeat_track(repeat_track)
                }

                let shuffle = set_options.shuffling_context;
                if let Some(shuffle) = shuffle {
                    self.handle_shuffle(shuffle)?;
                }
            }
            SkipNext(skip_next) => self.handle_next(skip_next.track.map(|t| t.uri))?,
            SkipPrev(_) => self.handle_prev()?,
            Resume(_) if matches!(self.play_status, SpircPlayStatus::Stopped) => {
                self.load_track(true, 0)?
            }
            Resume(_) => self.handle_play(),
        }

        self.update_state = true;
        Ok(())
    }

    fn handle_transfer(&mut self, mut transfer: TransferState) -> Result<(), Error> {
        let mut ctx_uri = match transfer.current_session.context.uri {
            None => Err(SpircError::NoUri("transfer context"))?,
            // can apparently happen when a state is transferred and was started with "uris" via the api
            Some(ref uri) if uri == "-" || uri.is_empty() => None,
            Some(ref uri) => Some(uri.clone()),
        };

        self.connect_state.reset_context(
            ctx_uri
                .as_deref()
                .map(ResetContext::WhenDifferent)
                .unwrap_or(ResetContext::Completely),
        );

        match self.connect_state.current_track_from_transfer(&transfer) {
            Err(why) => warn!("didn't find initial track: {why}"),
            Ok(track) => {
                debug!("found initial track <{}>", track.uri);
                self.connect_state.set_track(track)
            }
        };

        let autoplay = self.connect_state.current_track(|t| t.is_autoplay());
        if autoplay {
            ctx_uri = ctx_uri.map(|c| c.replace("station:", ""));
        }

        let fallback = self.connect_state.current_track(|t| &t.uri).clone();
        let load_from_context_uri = ctx_uri.is_some();

        match ctx_uri {
            Some(ref uri) => {
                self.context_resolver.add(ResolveContext::from_uri(
                    uri.clone(),
                    &fallback,
                    ContextType::Default,
                    ContextAction::Replace,
                ));
            }
            None => {
                let all_tracks = transfer
                    .current_session
                    .context
                    .pages
                    .iter()
                    .cloned()
                    .flat_map(|p| p.tracks)
                    .collect::<Vec<_>>();

                if !all_tracks.is_empty() {
                    self.load_context_from_tracks(all_tracks)?;
                } else {
                    warn!(
                        "tried to transfer with an invalid state, using fallback as ctx_uri ({fallback})"
                    );
                    ctx_uri = Some(fallback.clone())
                }
            }
        };

        self.handle_activate();

        let timestamp = self.now_ms();
        let state = &mut self.connect_state;
        state.handle_initial_transfer(&mut transfer, ctx_uri.clone());

        // adjust active context, so resolve knows for which context it should set up the state
        state.active_context = if autoplay {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };

        // update position if the track continued playing
        let transfer_timestamp = transfer.playback.timestamp.unwrap_or_default();
        let position = match transfer.playback.position_as_of_timestamp {
            Some(position) if transfer.playback.is_paused.unwrap_or_default() => position.into(),
            // update position if the track continued playing
            Some(position) if position > 0 => {
                let time_since_position_update = timestamp - transfer_timestamp;
                i64::from(position) + time_since_position_update
            }
            _ => 0,
        };

        let is_playing = !transfer.playback.is_paused();

        if self.connect_state.current_track(|t| t.is_autoplay()) || autoplay {
            if let Some(ctx_uri) = ctx_uri {
                debug!("currently in autoplay context, async resolving autoplay for {ctx_uri}");
                self.context_resolver.add(ResolveContext::from_uri(
                    ctx_uri,
                    fallback,
                    ContextType::Autoplay,
                    ContextAction::Replace,
                ))
            } else {
                warn!("couldn't resolve autoplay context without a context uri");
            }
        }

        if load_from_context_uri {
            self.transfer_state = Some(transfer);
        } else {
            match self.connect_state.get_context(ContextType::Default) {
                Err(why) => {
                    warn!("continuing transfer in an unknown state. {why}");
                    self.transfer_state = Some(transfer);
                }
                Ok(ctx) => {
                    let idx = ConnectState::find_index_in_context(ctx, |pt| {
                        self.connect_state.current_track(|t| pt.uri == t.uri)
                    })?;
                    self.connect_state.reset_playback_to_position(Some(idx))?;
                }
            }
        }

        self.load_track(is_playing, position.try_into()?)
    }

    async fn handle_disconnect(&mut self) -> Result<(), Error> {
        self.context_resolver.clear();

        self.play_status = SpircPlayStatus::Stopped {};
        self.connect_state
            .update_position_in_relation(self.now_ms());
        self.notify().await?;

        self.connect_state.became_inactive(&self.session).await?;

        self.player
            .emit_session_disconnected_event(self.session.connection_id(), self.session.username());

        Ok(())
    }

    fn handle_stop(&mut self) {
        self.player.stop();
        self.connect_state.update_position(0, self.now_ms());
        self.connect_state.clear_next_tracks();

        if let Err(why) = self.connect_state.reset_playback_to_position(None) {
            warn!("failed filling up next_track during stopping: {why}")
        }
    }

    fn handle_activate(&mut self) {
        self.connect_state.set_active(true);
        self.player
            .emit_session_connected_event(self.session.connection_id(), self.session.username());
        self.player.emit_session_client_changed_event(
            self.session.client_id(),
            self.session.client_name(),
            self.session.client_brand_name(),
            self.session.client_model_name(),
        );

        self.player
            .emit_volume_changed_event(self.connect_state.device_info().volume as u16);

        self.player
            .emit_auto_play_changed_event(self.session.autoplay());

        self.player
            .emit_filter_explicit_content_changed_event(self.session.filter_explicit_content());

        self.player
            .emit_shuffle_changed_event(self.connect_state.shuffling_context());

        self.player.emit_repeat_changed_event(
            self.connect_state.repeat_context(),
            self.connect_state.repeat_track(),
        );
    }

    async fn handle_load(
        &mut self,
        cmd: LoadRequest,
        page: Option<ContextPage>,
        fallback_index: Option<usize>,
    ) -> Result<(), Error> {
        self.connect_state
            .reset_context(if let PlayContext::Uri(ref uri) = cmd.context {
                ResetContext::WhenDifferent(uri)
            } else {
                ResetContext::Completely
            });

        self.connect_state.reset_options();

        let autoplay = matches!(cmd.context_options, Some(LoadContextOptions::Autoplay));
        match cmd.context {
            PlayContext::Uri(uri) => {
                self.load_context_from_uri(uri, page.as_ref(), autoplay)
                    .await?
            }
            PlayContext::Tracks(tracks) => self.load_context_from_tracks(tracks)?,
        }

        let cmd_options = cmd.options;

        self.connect_state.set_active_context(ContextType::Default);

        // for play commands with skip by uid, the context of the command contains
        // tracks with uri and uid, so we merge the new context with the resolved/existing context
        self.connect_state.merge_context(page);

        // load here, so that we clear the queue only after we definitely retrieved a new context
        self.connect_state.clear_next_tracks();
        self.connect_state.clear_restrictions();

        debug!("play track <{:?}>", cmd_options.playing_track);

        let index = match cmd_options.playing_track {
            None => None,
            Some(ref playing_track) => Some(match playing_track {
                PlayingTrack::Index(i) => Ok(*i as usize),
                PlayingTrack::Uri(uri) => {
                    let ctx = self.connect_state.get_context(ContextType::Default)?;
                    ConnectState::find_index_in_context(ctx, |t| &t.uri == uri)
                }
                PlayingTrack::Uid(uid) => {
                    let ctx = self.connect_state.get_context(ContextType::Default)?;
                    ConnectState::find_index_in_context(ctx, |t| &t.uid == uid)
                }
            }),
        }
        .map(|i| {
            i.unwrap_or_else(|why| {
                warn!(
                    "Failed to resolve index by {:?}, using fallback index: {:?} (Error: {why})",
                    cmd_options.playing_track, fallback_index
                );
                fallback_index.unwrap_or_default()
            })
        });

        if let Some(LoadContextOptions::Options(ref options)) = cmd_options.context_options {
            debug!(
                "loading with shuffle: <{}>, repeat track: <{}> context: <{}>",
                options.shuffle, options.repeat, options.repeat_track
            );

            self.connect_state.set_shuffle(options.shuffle);
            self.connect_state.set_repeat_context(options.repeat);
            self.connect_state.set_repeat_track(options.repeat_track);
        }

        if matches!(cmd_options.context_options, Some(LoadContextOptions::Options(ref o)) if o.shuffle)
        {
            if let Some(index) = index {
                self.connect_state.set_current_track(index)?;
            } else {
                self.connect_state.set_current_track_random()?;
            }

            if self.context_resolver.has_next() {
                self.connect_state.update_queue_revision()
            } else {
                self.connect_state.shuffle_new()?;
                self.add_autoplay_resolving_when_required();
            }
        } else {
            self.connect_state
                .set_current_track(index.unwrap_or_default())?;
            self.connect_state.reset_playback_to_position(index)?;
            self.add_autoplay_resolving_when_required();
        }

        if self.connect_state.current_track(MessageField::is_some) {
            self.load_track(cmd_options.start_playing, cmd_options.seek_to)?;
        } else {
            info!("No active track, stopping");
            self.handle_stop()
        }

        Ok(())
    }

    async fn load_context_from_uri(
        &mut self,
        context_uri: String,
        page: Option<&ContextPage>,
        autoplay: bool,
    ) -> Result<(), Error> {
        if !self.connect_state.is_active() {
            self.handle_activate();
        }

        let update_context = if autoplay {
            ContextType::Autoplay
        } else {
            ContextType::Default
        };

        self.connect_state.set_active_context(update_context);

        let fallback = match page {
            // check that the uri is valid or the page has a valid uri that can be used
            Some(page) => match ConnectState::find_valid_uri(Some(&context_uri), Some(page)) {
                Some(ctx_uri) => ctx_uri,
                None => return Err(SpircError::InvalidUri(context_uri).into()),
            },
            // when there is no page, the uri should be valid
            None => &context_uri,
        };

        let current_context_uri = self.connect_state.context_uri();

        if current_context_uri == &context_uri && fallback == context_uri {
            debug!("context <{current_context_uri}> didn't change, no resolving required")
        } else {
            debug!("resolving context for load command");
            self.context_resolver.clear();
            self.context_resolver.add(ResolveContext::from_uri(
                &context_uri,
                fallback,
                update_context,
                ContextAction::Replace,
            ));
            let context = self.context_resolver.get_next_context(Vec::new).await;
            self.handle_next_context(context);
        }

        Ok(())
    }

    fn load_context_from_tracks(&mut self, tracks: impl Into<ContextPage>) -> Result<(), Error> {
        const WEB_API_URI: &str = "spotify:web-api";
        let ctx = Context {
            // by providing values for uri/url the player in the official client's isn't frozen
            uri: Some(WEB_API_URI.into()),
            url: Some(format!("context://{WEB_API_URI}")),
            pages: vec![tracks.into()],
            ..Default::default()
        };

        let _ = self
            .connect_state
            .update_context(ctx, ContextType::Default)?;

        Ok(())
    }

    fn handle_play(&mut self) {
        match self.play_status {
            SpircPlayStatus::Paused {
                position_ms,
                preloading_of_next_track_triggered,
            } => {
                self.player.play();
                self.connect_state
                    .update_position(position_ms, self.now_ms());
                self.play_status = SpircPlayStatus::Playing {
                    nominal_start_time: self.now_ms() - position_ms as i64,
                    preloading_of_next_track_triggered,
                };
            }
            SpircPlayStatus::LoadingPause { position_ms } => {
                self.player.play();
                self.play_status = SpircPlayStatus::LoadingPlay { position_ms };
            }
            _ => return,
        }

        // Synchronize the volume from the mixer. This is useful on
        // systems that can switch sources from and back to librespot.
        let current_volume = self.mixer.volume();
        self.set_volume(current_volume);
    }

    fn handle_play_pause(&mut self) {
        match self.play_status {
            SpircPlayStatus::Paused { .. } | SpircPlayStatus::LoadingPause { .. } => {
                self.handle_play()
            }
            SpircPlayStatus::Playing { .. } | SpircPlayStatus::LoadingPlay { .. } => {
                self.handle_pause()
            }
            _ => (),
        }
    }

    fn handle_pause(&mut self) {
        match self.play_status {
            SpircPlayStatus::Playing {
                nominal_start_time,
                preloading_of_next_track_triggered,
            } => {
                self.player.pause();
                let position_ms = (self.now_ms() - nominal_start_time) as u32;
                self.connect_state
                    .update_position(position_ms, self.now_ms());
                self.play_status = SpircPlayStatus::Paused {
                    position_ms,
                    preloading_of_next_track_triggered,
                };
            }
            SpircPlayStatus::LoadingPlay { position_ms } => {
                self.player.pause();
                self.play_status = SpircPlayStatus::LoadingPause { position_ms };
            }
            _ => (),
        }
    }

    fn handle_seek(&mut self, position_ms: u32) {
        let duration = self.connect_state.player().duration;
        if i64::from(position_ms) > duration {
            warn!("tried to seek to {position_ms}ms of {duration}ms");
            return;
        }

        self.connect_state
            .update_position(position_ms, self.now_ms());
        self.player.seek(position_ms);
        let now = self.now_ms();
        match self.play_status {
            SpircPlayStatus::Stopped => (),
            SpircPlayStatus::LoadingPause {
                position_ms: ref mut position,
            }
            | SpircPlayStatus::LoadingPlay {
                position_ms: ref mut position,
            }
            | SpircPlayStatus::Paused {
                position_ms: ref mut position,
                ..
            } => *position = position_ms,
            SpircPlayStatus::Playing {
                ref mut nominal_start_time,
                ..
            } => *nominal_start_time = now - position_ms as i64,
        };
    }

    fn handle_shuffle(&mut self, shuffle: bool) -> Result<(), Error> {
        self.player.emit_shuffle_changed_event(shuffle);
        self.connect_state.handle_shuffle(shuffle)
    }

    fn handle_repeat_context(&mut self, repeat: bool) -> Result<(), Error> {
        self.player
            .emit_repeat_changed_event(repeat, self.connect_state.repeat_track());
        self.connect_state.handle_set_repeat_context(repeat)
    }

    fn handle_repeat_track(&mut self, repeat: bool) {
        self.player
            .emit_repeat_changed_event(self.connect_state.repeat_context(), repeat);
        self.connect_state.set_repeat_track(repeat);
    }

    fn handle_preload_next_track(&mut self) {
        // Requests the player thread to preload the next track
        match self.play_status {
            SpircPlayStatus::Paused {
                ref mut preloading_of_next_track_triggered,
                ..
            }
            | SpircPlayStatus::Playing {
                ref mut preloading_of_next_track_triggered,
                ..
            } => {
                *preloading_of_next_track_triggered = true;
            }
            _ => (),
        }

        if let Some(track_id) = self.connect_state.preview_next_track() {
            self.player.preload(track_id);
        }
    }

    // Mark unavailable tracks so we can skip them later
    fn handle_unavailable(&mut self, track_id: &SpotifyUri) -> Result<(), Error> {
        self.connect_state.mark_unavailable(track_id)?;
        self.handle_preload_next_track();

        Ok(())
    }

    fn add_autoplay_resolving_when_required(&mut self) {
        let require_load_new = !self
            .connect_state
            .has_next_tracks(Some(CONTEXT_FETCH_THRESHOLD))
            && self.session.autoplay()
            && !self.connect_state.context_uri().is_empty();

        if !require_load_new {
            return;
        }

        let current_context = self.connect_state.context_uri();
        let fallback = self.connect_state.current_track(|t| &t.uri);

        let has_tracks = self
            .connect_state
            .get_context(ContextType::Autoplay)
            .map(|c| !c.tracks.is_empty())
            .unwrap_or_default();

        let resolve = ResolveContext::from_uri(
            current_context,
            fallback,
            ContextType::Autoplay,
            if has_tracks {
                ContextAction::Append
            } else {
                ContextAction::Replace
            },
        );

        self.context_resolver.add(resolve);
    }

    fn handle_next(&mut self, track_uri: Option<String>) -> Result<(), Error> {
        let continue_playing = self.connect_state.is_playing();

        let current_uri = self.connect_state.current_track(|t| &t.uri);
        let mut has_next_track =
            matches!(track_uri, Some(ref track_uri) if current_uri == track_uri);

        if !has_next_track {
            has_next_track = loop {
                let index = self.connect_state.next_track()?;

                let current_uri = self.connect_state.current_track(|t| &t.uri);
                if matches!(track_uri, Some(ref track_uri) if current_uri != track_uri) {
                    continue;
                } else {
                    break index.is_some();
                }
            };
        };

        if has_next_track {
            self.add_autoplay_resolving_when_required();
            self.load_track(continue_playing, 0)
        } else {
            info!("Not playing next track because there are no more tracks left in queue.");
            self.handle_stop();
            Ok(())
        }
    }

    fn handle_prev(&mut self) -> Result<(), Error> {
        // Previous behaves differently based on the position
        // Under 3s it goes to the previous song (starts playing)
        // Over 3s it seeks to zero (retains previous play status)
        if self.position() < 3000 {
            let repeat_context = self.connect_state.repeat_context();
            match self.connect_state.prev_track()? {
                None if repeat_context => self.connect_state.reset_playback_to_position(None)?,
                None => {
                    self.connect_state.reset_playback_to_position(None)?;
                    self.handle_stop()
                }
                Some(_) => self.load_track(self.connect_state.is_playing(), 0)?,
            }
        } else {
            self.handle_seek(0);
        }

        Ok(())
    }

    fn handle_volume_up(&mut self) {
        let volume = (self.connect_state.device_info().volume as u16)
            .saturating_add(self.connect_state.volume_step_size);

        self.set_volume(volume);
    }

    fn handle_volume_down(&mut self) {
        let volume = (self.connect_state.device_info().volume as u16)
            .saturating_sub(self.connect_state.volume_step_size);

        self.set_volume(volume);
    }

    fn handle_playlist_modification(
        &mut self,
        playlist_modification_info: PlaylistModificationInfo,
    ) -> Result<(), Error> {
        let uri = playlist_modification_info
            .uri
            .ok_or(SpircError::NoUri("playlist modification"))?;
        let uri = String::from_utf8(uri)?;

        if self.connect_state.context_uri() != &uri {
            debug!(
                "ignoring playlist modification update for playlist <{uri}>, because it isn't the current context"
            );
            return Ok(());
        }

        debug!("playlist modification for current context: {uri}");
        self.context_resolver.add(ResolveContext::from_uri(
            uri,
            self.connect_state.current_track(|t| &t.uri),
            ContextType::Default,
            ContextAction::Replace,
        ));

        Ok(())
    }

    fn handle_session_update(&mut self, session_update: FallbackWrapper<SessionUpdate>) {
        // we know that this enum value isn't present in our current proto definitions, by that
        // the json parsing fails because the enum isn't known as proto representation
        const WBC: &str = "WIFI_BROADCAST_CHANGED";

        let mut session_update = match session_update {
            FallbackWrapper::Inner(update) => update,
            FallbackWrapper::Fallback(value) => {
                let fallback_inner = value.to_string();
                if fallback_inner.contains(WBC) {
                    log::debug!("Received SessionUpdate::{WBC}");
                } else {
                    log::warn!("SessionUpdate couldn't be parse correctly: {value:?}");
                }
                return;
            }
        };

        let reason = session_update.reason.enum_value();

        let mut session = match session_update.session.take() {
            None => return,
            Some(session) => session,
        };

        let active_device = session.host_active_device_id.take();
        if matches!(active_device, Some(ref device) if device == self.session.device_id()) {
            info!(
                "session update: <{:?}> for self, current session_id {}, new session_id {}",
                reason,
                self.session.session_id(),
                session.session_id
            );

            if self.session.session_id() != session.session_id {
                self.session.set_session_id(&session.session_id);
                self.connect_state.set_session_id(session.session_id);
            }
        } else {
            debug!("session update: <{reason:?}> from active session host: <{active_device:?}>");
        }

        // this seems to be used for jams or handling the current session_id
        //
        // handling this event was intended to keep the playback when other clients (primarily
        // mobile) connects, otherwise they would steel the current playback when there was no
        // session_id provided on the initial PutStateReason::NEW_DEVICE state update
        //
        // by generating an initial session_id from the get-go we prevent that behavior and
        // currently don't need to handle this event, might still be useful for later "jam" support
    }

    fn position(&mut self) -> u32 {
        match self.play_status {
            SpircPlayStatus::Stopped => 0,
            SpircPlayStatus::LoadingPlay { position_ms }
            | SpircPlayStatus::LoadingPause { position_ms }
            | SpircPlayStatus::Paused { position_ms, .. } => position_ms,
            SpircPlayStatus::Playing {
                nominal_start_time, ..
            } => (self.now_ms() - nominal_start_time) as u32,
        }
    }

    fn load_track(&mut self, start_playing: bool, position_ms: u32) -> Result<(), Error> {
        if self.connect_state.current_track(MessageField::is_none) {
            debug!("current track is none, stopping playback");
            self.handle_stop();
            return Ok(());
        }

        let current_uri = self.connect_state.current_track(|t| &t.uri);
        let id = SpotifyUri::from_uri(current_uri)?;
        self.player.load(id, start_playing, position_ms);

        self.connect_state
            .update_position(position_ms, self.now_ms());
        if start_playing {
            self.play_status = SpircPlayStatus::LoadingPlay { position_ms };
        } else {
            self.play_status = SpircPlayStatus::LoadingPause { position_ms };
        }
        self.connect_state.set_status(&self.play_status);

        Ok(())
    }

    async fn notify(&mut self) -> Result<(), Error> {
        self.connect_state.set_status(&self.play_status);

        if self.connect_state.is_playing() {
            self.connect_state
                .update_position_in_relation(self.now_ms());
        }

        self.connect_state.set_now(self.now_ms() as u64);

        self.connect_state
            .send_state(&self.session)
            .await
            .map(|_| ())
    }

    fn set_volume(&mut self, volume: u16) {
        debug!("SpircTask::set_volume({volume})");

        let old_volume = self.connect_state.device_info().volume;
        let new_volume = volume as u32;
        if old_volume != new_volume || self.mixer.volume() != volume {
            self.update_volume = true;

            self.connect_state.set_volume(new_volume);
            self.mixer.set_volume(volume);
            if let Some(cache) = self.session.cache() {
                cache.save_volume(volume)
            }
            if self.connect_state.is_active() {
                self.player.emit_volume_changed_event(volume);
            }
        }
    }
}

impl Drop for SpircTask {
    fn drop(&mut self) {
        debug!("drop Spirc[{}]", self.spirc_id);
    }
}

fn changed_mixer_volume(mixer: &dyn Mixer, advertised: u32) -> Option<u16> {
    let current = mixer.volume();
    (u32::from(current) != advertised).then_some(current)
}

#[cfg(test)]
mod no5_setup_failure_tests {
    use super::*;
    use crate::core::{error::ErrorKind, http_client::HttpClientError};
    use std::{error::Error as StdError, fmt, io,
        sync::atomic::{AtomicUsize, Ordering}};

    struct Sensitive;
    impl fmt::Debug for Sensitive {
        fn fmt(&self, _: &mut fmt::Formatter<'_>) -> fmt::Result {
            panic!("diagnostics must never format a sensitive error")
        }
    }
    impl fmt::Display for Sensitive {
        fn fmt(&self, _: &mut fmt::Formatter<'_>) -> fmt::Result {
            panic!("diagnostics must never read account, token or URL error text")
        }
    }
    impl StdError for Sensitive {}

    #[derive(Debug)]
    struct WrappedCause {
        inner: Box<dyn StdError + Send + Sync>,
        source_calls: Arc<AtomicUsize>,
    }
    impl fmt::Display for WrappedCause {
        fn fmt(&self, _: &mut fmt::Formatter<'_>) -> fmt::Result {
            panic!("diagnostics must inspect typed causes, not wrapper messages")
        }
    }
    impl StdError for WrappedCause {
        fn source(&self) -> Option<&(dyn StdError + 'static)> {
            self.source_calls.fetch_add(1, Ordering::SeqCst);
            Some(self.inner.as_ref())
        }
    }

    fn http_status(status: u16) -> Error {
        HttpClientError::StatusCode(status.to_string().parse().unwrap()).into()
    }

    #[test]
    fn websocket_http_rejections_keep_only_status_without_headers_or_body() {
        for status in [401, 403, 429, 503] {
            let response = tungstenite::http::Response::builder().status(status)
                .header("authorization", "Bearer sensitive-test-value")
                .body(Some(b"sensitive response body".to_vec())).unwrap();
            let error = Error::failed_precondition(tungstenite::Error::Http(Box::new(response)));
            let failure = setup_failure(&error);
            assert_eq!(failure.class(), "HTTP_STATUS");
            assert_eq!(failure.http_status, Some(status));
            assert_eq!(failure.io_kind, None);
            assert!(!failure.timeout);
            let wrapped = Error::failed_precondition(WrappedCause {
                inner: Box::new(error), source_calls: Arc::new(AtomicUsize::new(0)),
            });
            let nested = setup_failure(&wrapped);
            assert_eq!(nested.class(), "HTTP_STATUS");
            assert_eq!(nested.http_status, Some(status));
            assert_eq!(nested.io_kind, None);
            assert!(!nested.timeout);
        }
    }

    #[test]
    fn websocket_io_timeout_stays_distinct_from_http_rejection() {
        let error = Error::failed_precondition(tungstenite::Error::Io(
            io::Error::new(io::ErrorKind::TimedOut, Sensitive)));
        let failure = setup_failure(&error);
        assert_eq!(failure.class(), "IO");
        assert_eq!(failure.http_status, None);
        assert_eq!(failure.io_kind, Some(io::ErrorKind::TimedOut));
        assert!(failure.timeout);
    }

    #[test]
    fn pinned_core_http_statuses_keep_their_real_numeric_status_and_are_not_connect_errors() {
        for (status, kind) in [(403, ErrorKind::PermissionDenied), (401, ErrorKind::Unauthenticated),
            (500, ErrorKind::Unavailable), (421, ErrorKind::Unavailable),
            (503, ErrorKind::Unavailable), (451, ErrorKind::Unavailable)] {
            let error = http_status(status);
            assert_eq!(error.kind, kind);
            let failure = setup_failure(&error);
            assert_eq!(failure.class(), "HTTP_STATUS");
            assert_eq!(failure.http_status, Some(status));
            assert_eq!(failure.io_kind, None);
            assert_eq!(failure.os_error, None);
            assert!(!failure.timeout);
            assert_eq!(failure.cause_depth, 1);
            assert!(!failure.depth_limited);
        }
    }

    #[test]
    fn typed_io_timeout_and_refused_are_distinguished_without_reading_their_messages() {
        for (kind, timeout) in [(io::ErrorKind::TimedOut, true), (io::ErrorKind::ConnectionRefused, false)] {
            let error = Error::from(io::Error::new(kind, Sensitive));
            let failure = setup_failure(&error);
            assert_eq!(failure.class(), "IO");
            assert_eq!(failure.http_status, None);
            assert_eq!(failure.io_kind, Some(kind));
            assert_eq!(failure.os_error, None);
            assert_eq!(failure.timeout, timeout);
            assert!(!failure.depth_limited);
        }
    }

    #[test]
    fn raw_errno_and_kind_always_come_from_the_same_deepest_io_error() {
        // The raw value's meaning is platform-specific. Derive the expected
        // kind from std rather than treating a Linux errno as a macOS timeout.
        let expected = io::Error::from_raw_os_error(13).kind();
        let error = Error::unavailable(io::Error::new(io::ErrorKind::Other,
            io::Error::from_raw_os_error(13)));
        let failure = setup_failure(&error);
        assert_eq!(failure.class(), "IO");
        assert_eq!(failure.io_kind, Some(expected));
        assert_eq!(failure.os_error, Some(13));
        assert_eq!(failure.timeout, expected == io::ErrorKind::TimedOut);
    }

    #[test]
    fn nested_core_and_io_wrappers_do_not_hide_the_typed_timeout_or_http_status() {
        let calls = Arc::new(AtomicUsize::new(0));
        let error = Error::unknown(WrappedCause {
            inner: Box::new(Error::unavailable(io::Error::new(io::ErrorKind::Other,
                io::Error::new(io::ErrorKind::TimedOut, Sensitive)))),
            source_calls: calls.clone(),
        });
        let failure = setup_failure(&error);
        assert_eq!(failure.class(), "IO");
        assert_eq!(failure.io_kind, Some(io::ErrorKind::TimedOut));
        assert_eq!(failure.os_error, None);
        assert!(failure.timeout);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        let nested = Error::unknown(WrappedCause { inner: Box::new(http_status(503)), source_calls: calls });
        let failure = setup_failure(&nested);
        assert_eq!(failure.class(), "HTTP_STATUS");
        assert_eq!(failure.http_status, Some(503));
        assert_eq!(failure.io_kind, None);
        assert!(!failure.timeout);
    }

    #[test]
    fn unclassified_sensitive_error_does_not_become_a_connect_or_timeout_claim() {
        let failure = setup_failure(&Error::unavailable(Sensitive));
        assert_eq!(failure.class(), "UNCLASSIFIED");
        assert_eq!(failure.http_status, None);
        assert_eq!(failure.io_kind, None);
        assert_eq!(failure.os_error, None);
        assert!(!failure.timeout);
        assert_eq!(failure.cause_depth, 1);
        assert!(!failure.depth_limited);
        let deadline = setup_failure(&Error::deadline_exceeded(Sensitive));
        assert_eq!(deadline.class(), "UNCLASSIFIED");
        assert!(deadline.timeout, "only the typed DeadlineExceeded justifies this flag");
        let http_timeout = setup_failure(&http_status(504));
        assert_eq!(http_timeout.class(), "HTTP_STATUS");
        assert_eq!(http_timeout.http_status, Some(504));
        assert!(http_timeout.timeout);
    }

    #[test]
    fn eighth_cause_is_inspected_but_no_ninth_cause_is_traversed() {
        for wrappers in [7, 8] {
            let calls = Arc::new(AtomicUsize::new(0));
            let mut cause: Box<dyn StdError + Send + Sync> = Box::new(io::Error::from_raw_os_error(13));
            for _ in 0..wrappers {
                cause = Box::new(WrappedCause { inner: cause, source_calls: calls.clone() });
            }
            let error = Error { kind: ErrorKind::Unavailable, error: cause };
            let failure = setup_failure(&error);
            assert_eq!(failure.cause_depth, 8);
            assert_eq!(calls.load(Ordering::SeqCst), wrappers);
            if wrappers == 7 {
                assert_eq!(failure.class(), "IO");
                assert_eq!(failure.os_error, Some(13));
                assert!(!failure.depth_limited);
            } else {
                assert_eq!(failure.class(), "UNCLASSIFIED");
                assert_eq!(failure.os_error, None);
                assert!(failure.depth_limited);
            }
        }
    }

    #[test]
    fn a_cyclic_unknown_source_stops_after_eight_visits() {
        #[derive(Debug)]
        struct Cycle(Arc<AtomicUsize>);
        impl fmt::Display for Cycle {
            fn fmt(&self, _: &mut fmt::Formatter<'_>) -> fmt::Result { panic!("no source text") }
        }
        impl StdError for Cycle {
            fn source(&self) -> Option<&(dyn StdError + 'static)> {
                self.0.fetch_add(1, Ordering::SeqCst);
                Some(self)
            }
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let failure = setup_failure(&Error::unavailable(Cycle(calls.clone())));
        assert_eq!(failure.class(), "UNCLASSIFIED");
        assert_eq!(failure.cause_depth, 8);
        assert_eq!(calls.load(Ordering::SeqCst), 8);
        assert!(failure.depth_limited);
        assert!(!failure.timeout);
    }

    #[tokio::test]
    async fn stage_observation_keeps_the_original_typed_error_without_rewriting_or_formatting_it() {
        for stage in [SetupStage::ClientToken, SetupStage::DealerStart, SetupStage::ConnectPublish] {
            let error = observe_setup_stage(stage,
                async { Err::<(), _>(Error::unavailable(Sensitive)) }).await.err().unwrap();
            assert_eq!(error.kind, ErrorKind::Unavailable);
            assert!(error.error.is::<Sensitive>());
        }
    }
}

#[cfg(test)]
mod no5_session_connect_tests {
    use super::*;
    use crate::core::error::ErrorKind;
    use std::{cell::Cell, future::pending, rc::Rc};

    struct AttemptLifetime {
        active: Rc<Cell<usize>>,
        dropped: Rc<Cell<usize>>,
    }
    impl AttemptLifetime {
        fn new(active: &Rc<Cell<usize>>, dropped: &Rc<Cell<usize>>) -> Self {
            assert_eq!(active.replace(1), 0, "connection attempts must never overlap");
            Self { active: active.clone(), dropped: dropped.clone() }
        }
    }
    impl Drop for AttemptLifetime {
        fn drop(&mut self) {
            assert_eq!(self.active.replace(0), 1);
            self.dropped.set(self.dropped.get() + 1);
        }
    }

    #[tokio::test(start_paused = true)]
    async fn pending_ap_is_cancelled_before_connecting_the_next_resolved_ap() {
        let calls = Cell::new(0);
        let active = Rc::new(Cell::new(0));
        let dropped = Rc::new(Cell::new(0));
        let started = Instant::now();
        let result = connect_with_budget(SESSION_CONNECT_BUDGET, || {
            let ap = calls.get();
            calls.set(ap + 1);
            let active = active.clone();
            let dropped = dropped.clone();
            async move {
                let _lifetime = AttemptLifetime::new(&active, &dropped);
                match ap {
                    0 => pending::<Result<(), Error>>().await,
                    1 => Ok(()),
                    _ => panic!("successful authentication must not be repeated"),
                }
            }
        }, || active.get() == 0).await;
        assert!(result.is_ok());
        assert_eq!(calls.get(), 2);
        assert_eq!(dropped.get(), 2, "the abandoned TCP future must be dropped");
        assert_eq!(active.get(), 0);
        assert_eq!(Instant::now() - started, Duration::from_secs(5));
    }

    #[tokio::test(start_paused = true)]
    async fn six_pending_attempts_exhaust_thirty_seconds_and_leave_no_future_running() {
        let calls = Cell::new(0);
        let active = Rc::new(Cell::new(0));
        let dropped = Rc::new(Cell::new(0));
        let started = Instant::now();
        let result = connect_with_budget(SESSION_CONNECT_BUDGET, || {
            calls.set(calls.get() + 1);
            let active = active.clone();
            let dropped = dropped.clone();
            async move {
                let _lifetime = AttemptLifetime::new(&active, &dropped);
                pending::<Result<(), Error>>().await
            }
        }, || active.get() == 0).await;
        assert_eq!(result.unwrap_err().kind, ErrorKind::DeadlineExceeded);
        assert_eq!(calls.get(), 6);
        assert_eq!(dropped.get(), 6);
        assert_eq!(active.get(), 0);
        assert_eq!(Instant::now() - started, Duration::from_secs(30));
    }

    #[tokio::test(start_paused = true)]
    async fn total_budget_also_bounds_the_last_partial_attempt() {
        let calls = Cell::new(0);
        let started = Instant::now();
        let result = connect_with_budget(SessionConnectBudget {
            attempt: Duration::from_secs(5), total: Duration::from_secs(12), attempts: 6,
        }, || {
            calls.set(calls.get() + 1);
            pending::<Result<(), Error>>()
        }, || true).await;
        assert_eq!(result.unwrap_err().kind, ErrorKind::DeadlineExceeded);
        assert_eq!(calls.get(), 3);
        assert_eq!(Instant::now() - started, Duration::from_secs(12));
    }

    #[tokio::test(start_paused = true)]
    async fn explicit_authentication_rejection_returns_without_a_second_attempt() {
        let calls = Cell::new(0);
        let started = Instant::now();
        let result = connect_with_budget(SESSION_CONNECT_BUDGET, || {
            calls.set(calls.get() + 1);
            async { Err(Error::permission_denied("explicit authentication rejection")) }
        }, || true).await;
        let error = result.unwrap_err();
        assert_eq!(error.kind, ErrorKind::PermissionDenied);
        assert_eq!(error.error.to_string(), "explicit authentication rejection");
        assert_eq!(calls.get(), 1);
        assert_eq!(Instant::now(), started);
    }

    #[tokio::test(start_paused = true)]
    async fn returned_core_error_is_not_amplified_into_another_ap_retry_loop() {
        let calls = Cell::new(0);
        let result = connect_with_budget(SESSION_CONNECT_BUDGET, || {
            calls.set(calls.get() + 1);
            async { Err(Error::unavailable("core AP attempts exhausted")) }
        }, || true).await;
        let error = result.unwrap_err();
        assert_eq!(error.kind, ErrorKind::Unavailable);
        assert_eq!(error.error.to_string(), "core AP attempts exhausted");
        assert_eq!(calls.get(), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn initialized_or_invalid_session_during_cancellation_never_reconnects() {
        struct ChangedOnDrop(Rc<Cell<bool>>);
        impl Drop for ChangedOnDrop {
            fn drop(&mut self) { self.0.set(false); }
        }
        let uninitialized = Rc::new(Cell::new(true));
        let calls = Cell::new(0);
        let result = connect_with_budget(SESSION_CONNECT_BUDGET, || {
            calls.set(calls.get() + 1);
            let uninitialized = uninitialized.clone();
            async move {
                let _state_change = ChangedOnDrop(uninitialized);
                pending::<Result<(), Error>>().await
            }
        }, || uninitialized.get()).await;
        assert_eq!(result.unwrap_err().kind, ErrorKind::FailedPrecondition);
        assert_eq!(calls.get(), 1);
        assert!(!uninitialized.get());
    }

    #[tokio::test(start_paused = true)]
    async fn an_already_initialized_or_invalid_session_never_starts_connect() {
        let result = connect_with_budget(SESSION_CONNECT_BUDGET,
            || async { panic!("a session must not initialize its connection twice") },
            || false).await;
        assert_eq!(result.unwrap_err().kind, ErrorKind::FailedPrecondition);
    }
}

#[cfg(test)]
mod no5_volume_feedback_tests {
    use super::*;
    use std::sync::atomic::AtomicU16;
    use crate::playback::mixer::MixerConfig;

    #[test]
    fn queue_ordinal_identifies_duplicates_and_excludes_hidden_unavailable_items() {
        let uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        let make = |uid: &str| { let mut t = crate::protocol::player::ProvidedTrack::new(); t.uri = uri.into(); t.uid = uid.into(); t };
        let first = make("first"); let mut hidden = make("hidden"); hidden.metadata.insert("hidden".into(), "true".into());
        let mut unavailable = make("unavailable"); unavailable.provider = "unavailable".into();
        let second = make("second"); let tracks = vec![first, hidden, unavailable, second];
        assert_eq!(selection_position(&tracks, 0, uri), Some(0));
        assert_eq!(selection_position(&tracks, 1, uri), Some(3));
        assert_eq!(selection_position(&tracks, 2, uri), None);
        assert_eq!(selection_position(&tracks, 0, "spotify:track:5uLU6hMCjMI75M1A2tKUQC"), None);
    }

    #[tokio::test]
    async fn dropped_selection_receipt_is_unknown_and_enqueues_only_once() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let handle = Spirc { commands: tx, mixer_volume_pending: Arc::new(AtomicBool::new(false)), queue_snapshot: Arc::new(RwLock::new(None)) };
        let task = tokio::spawn(async move { handle.select_queue("revision".into(), 1, "uri".into()).await });
        let command = rx.recv().await.unwrap();
        assert!(matches!(&command, SpircCommand::SelectQueue { index: 1, .. }));
        drop(command);
        assert!(matches!(task.await.unwrap(), Err(NavigationError::Unknown)));
        assert!(rx.try_recv().is_err());
    }

    struct ActualMixer(AtomicU16);
    impl Mixer for ActualMixer {
        fn open(_: MixerConfig) -> Result<Self, crate::core::Error> { unreachable!() }
        fn volume(&self) -> u16 { self.0.load(Ordering::Acquire) }
        fn set_volume(&self, _: u16) { panic!("an observation must never become a command") }
    }

    #[test]
    fn observed_queue_is_a_copy_and_never_enqueues_a_command() {
        let (tx, mut rx) = mpsc::unbounded_channel();
        let snapshot = Arc::new(RwLock::new(Some(QueueSnapshot {
            active: true, shuffle: false, context_uri: String::new(), revision: "one".into(),
            observed_at_epoch_ms: 1, previous: vec![], next: vec![],
            source_identity: vec![], current_uri: String::new(), current_uid: String::new(),
        })));
        let handle = Spirc { commands: tx, mixer_volume_pending: Arc::new(AtomicBool::new(false)), queue_snapshot: snapshot.clone() };
        let copied = handle.observed_queue().expect("local observed queue");
        snapshot.write().expect("local lock").as_mut().expect("local snapshot").revision = "two".into();
        assert_eq!(copied.revision, "one");
        assert!(rx.try_recv().is_err());
        *snapshot.write().expect("local lock") = None;
        assert!(handle.observed_queue().is_none());
    }

    #[test]
    fn delayed_notification_reads_latest_actual_volume_without_echo() {
        let mixer = ActualMixer(AtomicU16::new(100));
        let (tx, mut rx) = mpsc::unbounded_channel();
        let pending = Arc::new(AtomicBool::new(false));
        let handle = Spirc { commands: tx, mixer_volume_pending: pending.clone(), queue_snapshot: Arc::new(RwLock::new(None)) };
        for value in [100, 200, 300, 400] {
            mixer.0.store(value, Ordering::Release);
            handle.notify_mixer_volume().unwrap();
        }
        assert!(matches!(rx.try_recv().unwrap(), SpircCommand::RefreshMixerVolume));
        assert!(rx.try_recv().is_err(), "only one observation may be pending");
        pending.store(false, Ordering::Release);
        assert_eq!(changed_mixer_volume(&mixer, 100), Some(400));
        assert_eq!(changed_mixer_volume(&mixer, 400), None);
        // Another client later moves down; no queued 100/200/300 can replay.
        mixer.0.store(50, Ordering::Release);
        handle.notify_mixer_volume().unwrap();
        assert!(matches!(rx.try_recv().unwrap(), SpircCommand::RefreshMixerVolume));
        assert_eq!(changed_mixer_volume(&mixer, 400), Some(50));
    }

    #[test]
    fn failed_notification_does_not_leave_a_stuck_pending_flag() {
        let (tx, rx) = mpsc::unbounded_channel();
        drop(rx);
        let pending = Arc::new(AtomicBool::new(false));
        let handle = Spirc { commands: tx, mixer_volume_pending: pending.clone(), queue_snapshot: Arc::new(RwLock::new(None)) };
        assert!(handle.notify_mixer_volume().is_err());
        assert!(!pending.load(Ordering::Acquire));
    }
    #[tokio::test]
    async fn explicit_playlist_mode_is_one_actor_operation_and_lost_receipt_never_replays() {
        let (tx,mut rx)=mpsc::unbounded_channel();
        let handle=Spirc {commands:tx,mixer_volume_pending:Arc::new(AtomicBool::new(false)),queue_snapshot:Arc::new(RwLock::new(None))};
        let task=tokio::spawn(async move {handle.select_context_mode("revision".into(),Context::new(),0,"uri".into(),Some(true)).await});
        let command=rx.recv().await.unwrap();
        assert!(matches!(&command,SpircCommand::SelectContext {start_shuffle:Some(true),..}));
        drop(command);assert!(matches!(task.await.unwrap(),Err(NavigationError::Unknown)));assert!(rx.try_recv().is_err());
    }

}

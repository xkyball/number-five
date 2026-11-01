use serde::Serialize;
use std::{
    fs, io,
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
};

pub fn current_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlaybackState {
    pub connected: bool,
    pub active: bool,
    pub state: PlaybackStatus,
    pub track_id: Option<String>,
    pub track_uri: Option<String>,
    pub artwork: Option<crate::media::Artwork>,
    pub title: Option<String>,
    pub artists: Vec<String>,
    pub album: Option<String>,
    pub duration_ms: Option<u32>,
    pub position_ms: Option<u32>,
    pub shuffle: Option<bool>,
    pub repeat: Option<bool>,
    pub observed_at_epoch_ms: u64,
    /// Private player-thread correlation; excluded from the public state-file schema.
    pub load_sequence: u64,
    pub completed_load_sequence: u64,
    pub loading_request_id: Option<u64>,
    pub loading_uri: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaybackStatus {
    Stopped,
    Paused,
    Playing,
}

impl PlaybackStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Stopped => "STOPPED",
            Self::Paused => "PAUSED",
            Self::Playing => "PLAYING",
        }
    }
}

impl PlaybackState {
    pub fn observe_player_loading(&mut self, request: u64, uri: Option<String>) {
        self.load_sequence = self.load_sequence.saturating_add(1);
        self.loading_request_id = Some(request);
        self.loading_uri = uri;
    }
    pub fn observe_player_playing(&mut self, request: u64, uri: Option<String>) {
        if self.loading_request_id == Some(request) && self.loading_uri == uri {
            self.completed_load_sequence = self.load_sequence;
        }
    }

    /// Re-observe the receiver-owned paused context while its Connect session,
    /// player thread and event reader are alive. Playback position is unchanged.
    /// A cached file alone is never enough to refresh this observation.
    pub fn observe_paused_context(&mut self, receiver_healthy: bool, now_epoch_ms: u64) -> bool {
        if !receiver_healthy
            || !self.connected
            || !self.active
            || self.state != PlaybackStatus::Paused
            || self.track_id.is_none()
            || now_epoch_ms <= self.observed_at_epoch_ms
        {
            return false;
        }
        self.observed_at_epoch_ms = now_epoch_ms;
        true
    }

    pub fn unavailable(now_epoch_ms: u64) -> Self {
        Self {
            connected: false,
            active: false,
            state: PlaybackStatus::Stopped,
            track_id: None,
            track_uri: None,
            artwork: None,
            title: None,
            artists: Vec::new(),
            album: None,
            duration_ms: None,
            position_ms: None,
            shuffle: None,
            repeat: None,
            observed_at_epoch_ms: now_epoch_ms,
            load_sequence: 0,
            completed_load_sequence: 0,
            loading_request_id: None,
            loading_uri: None,
        }
    }

    pub fn apply(&mut self, event: ReceiverEvent, now_epoch_ms: u64) {
        match event {
            ReceiverEvent::SessionConnected => {
                self.connected = true;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::SessionDisconnected => {
                self.connected = false;
                self.active = false;
                self.state = PlaybackStatus::Stopped;
                self.position_ms = None;
                self.track_uri = None;
                self.artwork = None;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::TrackChanged {
                track_id,
                track_uri,
                artwork,
                title,
                artists,
                album,
                duration_ms,
            } => {
                self.track_id = track_id;
                self.track_uri = track_uri;
                self.artwork = artwork;
                self.title = title;
                self.artists = artists;
                self.album = album;
                self.duration_ms = duration_ms;
                self.position_ms = None;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::Playing { position_ms } => {
                self.active = true;
                self.state = PlaybackStatus::Playing;
                self.position_ms = position_ms;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::Paused { position_ms } => {
                self.active = true;
                self.state = PlaybackStatus::Paused;
                self.position_ms = position_ms;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::PlayingPosition { position_ms } => {
                // PositionChanged is emitted by librespot only while actually
                // playing. It is a real player observation, never UI time.
                self.active = true;
                self.state = PlaybackStatus::Playing;
                self.position_ms = position_ms;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::Position { position_ms } => {
                // A seek also occurs while paused. Position observations do
                // not change transport state or reactivate a stopped session.
                if self.connected && self.active {
                    self.position_ms = position_ms;
                    self.observed_at_epoch_ms = now_epoch_ms;
                }
            }
            ReceiverEvent::Stopped | ReceiverEvent::Unavailable => {
                self.active = false;
                self.state = PlaybackStatus::Stopped;
                self.position_ms = None;
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::Shuffle(value) => {
                self.shuffle = Some(value);
                self.observed_at_epoch_ms = now_epoch_ms;
            }
            ReceiverEvent::Repeat(value) => {
                self.repeat = Some(value);
                self.observed_at_epoch_ms = now_epoch_ms;
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReceiverEvent {
    SessionConnected,
    SessionDisconnected,
    TrackChanged {
        track_id: Option<String>,
        track_uri: Option<String>,
        artwork: Option<crate::media::Artwork>,
        title: Option<String>,
        artists: Vec<String>,
        album: Option<String>,
        duration_ms: Option<u32>,
    },
    Playing {
        position_ms: Option<u32>,
    },
    Paused {
        position_ms: Option<u32>,
    },
    Position {
        position_ms: Option<u32>,
    },
    PlayingPosition {
        position_ms: Option<u32>,
    },
    Stopped,
    Unavailable,
    Shuffle(bool),
    Repeat(bool),
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GatewayState<'a> {
    version: u8,
    kind: &'static str,
    connected: bool,
    active: bool,
    state: &'static str,
    track_id: &'a Option<String>,
    title: &'a Option<String>,
    artists: &'a [String],
    album: &'a Option<String>,
    duration_ms: Option<u32>,
    position_ms: Option<u32>,
    shuffle: Option<bool>,
    repeat: Option<bool>,
    observed_at_epoch_ms: u64,
}

impl PlaybackState {
    fn as_gateway_state(&self) -> GatewayState<'_> {
        GatewayState {
            version: 1,
            // The field layout remains V1-compatible with the historic
            // onevent file, but the distinct kind prevents an observation-
            // only librespot callback from being mistaken for this
            // readback-capable receiver.
            kind: "spotify-connect-state",
            connected: self.connected,
            active: self.active,
            state: self.state.as_str(),
            track_id: &self.track_id,
            title: &self.title,
            artists: &self.artists,
            album: &self.album,
            duration_ms: self.duration_ms,
            position_ms: self.position_ms,
            shuffle: self.shuffle,
            repeat: self.repeat,
            observed_at_epoch_ms: self.observed_at_epoch_ms,
        }
    }
}

pub fn write_gateway_state(path: &Path, state: &PlaybackState) -> io::Result<()> {
    let bytes = serde_json::to_vec(&state.as_gateway_state()).map_err(io::Error::other)?;
    let directory = path
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "state path has no parent"))?;
    fs::create_dir_all(directory)?;
    let temporary = temporary_path(path);
    let mut output = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o640)
        .open(&temporary)?;
    use std::io::Write;
    output.write_all(&bytes)?;
    output.sync_all()?;
    drop(output);
    fs::rename(&temporary, path)?;
    Ok(())
}

fn temporary_path(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("state.json");
    path.with_file_name(format!(".{name}.{}.tmp", std::process::id()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_matching_new_player_request_confirms_a_selected_duplicate_uri() {
        let mut state = PlaybackState::unavailable(1);
        let uri = Some("spotify:track:4uLU6hMCjMI75M1A2tKUQC".into());
        state.observe_player_loading(10, uri.clone());
        state.observe_player_playing(9, uri.clone());
        assert_eq!(state.completed_load_sequence, 0);
        state.observe_player_playing(10, Some("wrong".into()));
        assert_eq!(state.completed_load_sequence, 0);
        state.observe_player_playing(10, uri.clone());
        assert_eq!(state.completed_load_sequence, 1);
        state.observe_player_loading(11, uri.clone());
        state.observe_player_playing(10, uri.clone());
        assert_eq!(
            state.completed_load_sequence, 1,
            "a historical same-song event cannot confirm the new request"
        );
        state.observe_player_playing(11, uri);
        assert_eq!(state.completed_load_sequence, 2);
    }

    #[test]
    fn position_is_a_real_player_event_not_an_extrapolated_clock() {
        let mut state = PlaybackState::unavailable(1);
        state.apply(
            ReceiverEvent::Playing {
                position_ms: Some(12),
            },
            10,
        );
        state.apply(
            ReceiverEvent::PlayingPosition {
                position_ms: Some(1012),
            },
            1010,
        );
        assert_eq!(state.state, PlaybackStatus::Playing);
        assert_eq!(state.position_ms, Some(1012));
        assert_eq!(state.observed_at_epoch_ms, 1010);
    }

    #[test]
    fn paused_context_stays_observable_without_advancing_position() {
        let mut state = PlaybackState::unavailable(1);
        state.apply(ReceiverEvent::SessionConnected, 2);
        state.apply(
            ReceiverEvent::TrackChanged {
                track_id: Some("track-1".into()),
                track_uri: None,
                artwork: None,
                title: Some("Song".into()),
                artists: vec![],
                album: None,
                duration_ms: Some(100_000),
            },
            3,
        );
        state.apply(
            ReceiverEvent::Paused {
                position_ms: Some(12_000),
            },
            4,
        );
        let before = state.clone();
        assert!(state.observe_paused_context(true, 60_000));
        assert_eq!(state.observed_at_epoch_ms, 60_000);
        assert_eq!(state.position_ms, before.position_ms);
        assert_eq!(state.track_id, before.track_id);
        assert_eq!(state.state, PlaybackStatus::Paused);
        assert!(!state.observe_paused_context(true, 59_000));
        assert!(!state.observe_paused_context(false, 61_000));
        assert_eq!(state.observed_at_epoch_ms, 60_000);
        state.apply(ReceiverEvent::SessionDisconnected, 62_000);
        assert!(!state.observe_paused_context(true, 63_000));
    }

    #[test]
    fn idle_observation_cannot_invent_context_or_refresh_a_stalled_playing_clock() {
        let mut state = PlaybackState::unavailable(1);
        state.apply(ReceiverEvent::SessionConnected, 2);
        state.apply(ReceiverEvent::Paused { position_ms: None }, 3);
        assert!(!state.observe_paused_context(true, 4));
        state.track_id = Some("track-1".into());
        state.active = false;
        assert!(!state.observe_paused_context(true, 4));
        state.apply(
            ReceiverEvent::Playing {
                position_ms: Some(12),
            },
            5,
        );
        assert!(!state.observe_paused_context(true, 10_000));
        assert_eq!(state.observed_at_epoch_ms, 5);
        assert_eq!(state.position_ms, Some(12));
    }

    #[test]
    fn writer_uses_receiver_schema_and_atomic_replacement() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("state.json");
        let state = PlaybackState::unavailable(7);
        write_gateway_state(&path, &state).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
        assert_eq!(value["kind"], "spotify-connect-state");
        assert_eq!(value["observedAtEpochMs"], 7);
    }
}

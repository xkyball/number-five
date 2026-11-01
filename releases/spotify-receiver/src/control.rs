use crate::volume::AuthorityVersion;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{future::Future, io, path::Path, pin::Pin, sync::Arc};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    sync::{Mutex, Semaphore},
    time::{Duration, timeout},
};

const MAX_REQUEST_BYTES: usize = 4096;

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
pub enum Action {
    Play,
    Pause,
    Next,
    Previous,
    Seek {
        position_ms: u32,
    },
    Shuffle {
        enabled: bool,
    },
    Repeat {
        enabled: bool,
    },
    ReadMedia,
    ReadNavigation,
    SelectQueue {
        expected_session: String,
        expected_queue_revision: String,
        index: usize,
        uri: String,
    },
    SelectCollection {
        expected_session: String,
        expected_queue_revision: String,
        query: crate::media::BrowseQuery,
        expected_page_revision: Option<String>,
        index: usize,
        uri: String,
    },
    StartPlaylist {
        expected_session: String,
        expected_queue_revision: String,
        uri: String,
        expected_page_revision: Option<String>,
        shuffle: bool,
    },
    Browse {
        expected_session: String,
        query: crate::media::BrowseQuery,
    },
    MasterState,
    MasterVolume {
        value_db: f64,
        expected: AuthorityVersion,
    },
    MasterMute {
        muted: bool,
        expected: AuthorityVersion,
    },
    MasterAdjust {
        delta_db: f64,
        expected_session: String,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    version: u8,
    kind: String,
    request_id: String,
    action: Action,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Response<'a> {
    version: u8,
    kind: &'static str,
    request_id: &'a str,
    status: &'static str,
    message: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    confirmation: Option<Value>,
}

pub enum DispatchError {
    Rejected(String),
    Unknown(String),
}
pub type DispatchFuture<'a> =
    Pin<Box<dyn Future<Output = Result<Option<Value>, DispatchError>> + Send + 'a>>;

pub trait Dispatcher: Send + Sync + 'static {
    fn dispatch(&self, action: Action) -> Result<(), String>;
    fn dispatch_confirmed(&self, action: Action) -> DispatchFuture<'_> {
        Box::pin(async move {
            self.dispatch(action)
                .map(|_| None)
                .map_err(DispatchError::Rejected)
        })
    }
}

pub async fn serve(socket_path: &Path, dispatcher: Arc<dyn Dispatcher>) -> io::Result<()> {
    if socket_path.exists() {
        std::fs::remove_file(socket_path)?;
    }
    let listener = UnixListener::bind(socket_path)?;
    // User=user / Group=no5gateway and UMask=0007 are established by the
    // service unit. This explicit mode keeps the socket inaccessible to every
    // other local account even if a unit default changes.
    std::fs::set_permissions(
        socket_path,
        std::os::unix::fs::PermissionsExt::from_mode(0o660),
    )?;
    let slots = Arc::new(Semaphore::new(16));
    loop {
        let slot = slots
            .clone()
            .acquire_owned()
            .await
            .map_err(io::Error::other)?;
        let (stream, _) = listener.accept().await?;
        let dispatcher = Arc::clone(&dispatcher);
        tokio::spawn(async move {
            let _slot = slot;
            let _ = handle(stream, dispatcher).await;
        });
    }
}

async fn handle(stream: UnixStream, dispatcher: Arc<dyn Dispatcher>) -> io::Result<()> {
    let (reader, mut writer) = stream.into_split();
    let mut reader = BufReader::new(reader).take((MAX_REQUEST_BYTES + 2) as u64);
    let mut line = String::new();
    let count = timeout(Duration::from_secs(2), reader.read_line(&mut line))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "control header deadline"))??;
    if count == 0 {
        return Ok(());
    }
    if line.len() > MAX_REQUEST_BYTES || !line.ends_with('\n') {
        return write_response(
            &mut writer,
            "",
            "REJECTED",
            "request exceeds 4096 bytes",
            None,
        )
        .await;
    }
    let response: (String, &'static str, String, Option<Value>) =
        match serde_json::from_str::<Request>(&line) {
            Ok(request)
                if request.version == 1
                    && request.kind == "spotify-control"
                    && valid_request_id(&request.request_id) =>
            {
                match dispatcher.dispatch_confirmed(request.action).await {
                    Ok(None) => (
                        request.request_id,
                        "ACCEPTED",
                        "command accepted; await receiver state readback".to_string(),
                        None,
                    ),
                    Ok(Some(confirmation)) => (
                        request.request_id,
                        "VERIFIED",
                        "Receiver readback verified".into(),
                        Some(confirmation),
                    ),
                    Err(DispatchError::Rejected(message)) => {
                        (request.request_id, "REJECTED", message, None)
                    }
                    Err(DispatchError::Unknown(message)) => {
                        (request.request_id, "UNKNOWN", message, None)
                    }
                }
            }
            _ => (
                String::new(),
                "REJECTED",
                "invalid spotify-control request".to_string(),
                None,
            ),
        };
    write_response(
        &mut writer,
        &response.0,
        response.1,
        &response.2,
        response.3,
    )
    .await
}

fn valid_request_id(value: &str) -> bool {
    (1..=200).contains(&value.len())
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-' || byte == b':'
        })
}

async fn write_response(
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    request_id: &str,
    status: &'static str,
    message: &str,
    confirmation: Option<Value>,
) -> io::Result<()> {
    let response = Response {
        version: 1,
        kind: "spotify-control-ack",
        request_id,
        status,
        message,
        confirmation,
    };
    let mut encoded = serde_json::to_vec(&response).map_err(io::Error::other)?;
    encoded.push(b'\n');
    writer.write_all(&encoded).await
}

pub struct SharedDispatcher<T> {
    target: Arc<Mutex<Option<T>>>,
    invoke: fn(&T, Action) -> Result<(), String>,
}

impl<T> SharedDispatcher<T> {
    pub fn new(
        target: Arc<Mutex<Option<T>>>,
        invoke: fn(&T, Action) -> Result<(), String>,
    ) -> Self {
        Self { target, invoke }
    }
}

impl<T: Send + Sync + 'static> Dispatcher for SharedDispatcher<T> {
    fn dispatch(&self, action: Action) -> Result<(), String> {
        // librespot's public Spirc commands are non-async sends. Avoid holding
        // an async mutex from a synchronous Unix-socket dispatcher by using
        // try_lock: contention is rejected and reconciled by normal state GET.
        let target = self
            .target
            .try_lock()
            .map_err(|_| "receiver command is busy; retry after state refresh".to_string())?;
        let receiver = target
            .as_ref()
            .ok_or_else(|| "Spotify Connect receiver is not active".to_string())?;
        (self.invoke)(receiver, action)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    #[test]
    fn action_schema_is_bounded_and_explicit() {
        let action: Action = serde_json::from_str(r#"{"type":"SEEK","position_ms":1200}"#).unwrap();
        assert_eq!(action, Action::Seek { position_ms: 1200 });
        assert!(serde_json::from_str::<Action>(r#"{"type":"DELETE"}"#).is_err());
        assert!(valid_request_id("phone_1:2"));
        assert!(!valid_request_id(""));
    }

    struct CountingDispatcher(AtomicUsize);

    impl Dispatcher for CountingDispatcher {
        fn dispatch(&self, action: Action) -> Result<(), String> {
            if action == Action::Pause {
                self.0.fetch_add(1, Ordering::SeqCst);
                Ok(())
            } else {
                Err("unsupported test action".to_string())
            }
        }
    }

    #[cfg_attr(
        target_os = "macos",
        ignore = "macOS rejects chmod on Unix-domain sockets; exercised on Debian"
    )]
    #[tokio::test]
    async fn unix_socket_accepts_only_strict_control_messages() {
        let socket =
            std::env::temp_dir().join(format!("no5-control-test-{}.sock", std::process::id()));
        let _ = std::fs::remove_file(&socket);
        let dispatcher = Arc::new(CountingDispatcher(AtomicUsize::new(0)));
        let socket_for_server = socket.clone();
        let dispatcher_for_server: Arc<dyn Dispatcher> =
            Arc::clone(&dispatcher) as Arc<dyn Dispatcher>;
        let task =
            tokio::spawn(async move { serve(&socket_for_server, dispatcher_for_server).await });
        for _ in 0..50 {
            if socket.exists() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        if !socket.exists() {
            panic!("control socket did not appear: {:?}", task.await);
        }
        let stream = UnixStream::connect(&socket).await.unwrap();
        let (reader, mut writer) = stream.into_split();
        writer.write_all(b"{\"version\":1,\"kind\":\"spotify-control\",\"requestId\":\"pi:1\",\"action\":{\"type\":\"PAUSE\"}}\n").await.unwrap();
        let mut response = String::new();
        BufReader::new(reader)
            .read_line(&mut response)
            .await
            .unwrap();
        assert!(response.contains("ACCEPTED"));
        assert_eq!(dispatcher.0.load(Ordering::SeqCst), 1);
        task.abort();
        let _ = std::fs::remove_file(socket);
    }
}

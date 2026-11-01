//! One Camilla Main authority for Connect and the local gateway. No network key.
use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use librespot::{
    connect::Spirc,
    core::Error,
    playback::{
        config::VolumeCtrl,
        mixer::{Mixer, MixerConfig, NoOpVolume, VolumeGetter, mappings::MappedCtrl},
    },
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex as QueueMutex,
        atomic::{AtomicBool, AtomicU16, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    net::TcpStream,
    sync::{Mutex, Notify, oneshot},
    time::{interval, timeout},
};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream, connect_async, tungstenite::Message};

#[derive(Clone, Copy, Debug)]
pub struct Range {
    pub min: f64,
    pub max: f64,
    pub step: f64,
}
impl Range {
    pub fn validate(self) -> Result<Self> {
        if !self.min.is_finite()
            || !self.max.is_finite()
            || !self.step.is_finite()
            || self.min < -150.0
            || self.max > 0.0
            || self.min >= self.max
            || self.step <= 0.0
            || self.step > self.max - self.min
        {
            bail!("invalid reviewed Main range")
        }
        Ok(self)
    }
    pub fn normalise(self, db: f64) -> f64 {
        (self.min + ((db.clamp(self.min, self.max) - self.min) / self.step).round() * self.step)
            .clamp(self.min, self.max)
    }
    pub fn db(self, volume: u16) -> f64 {
        self.normalise(
            self.min + (self.max - self.min) * (volume.saturating_sub(1) as f64 / 65534.0),
        )
    }
    pub fn volume(self, db: f64, muted: bool) -> u16 {
        if muted {
            0
        } else {
            (1.0 + (db.clamp(self.min, self.max) - self.min) / (self.max - self.min) * 65534.0)
                .round() as u16
        }
    }
    pub fn migration(self, db: f64, muted: bool, factor: f64) -> (f64, bool) {
        if factor <= 0.0 {
            return (self.normalise(db), true);
        }
        let combined = db + 20.0 * factor.min(1.0).log10();
        // Floor, never round up: adopting the old combined gain cannot be louder.
        let target = (self.min + ((combined - self.min) / self.step).floor() * self.step)
            .clamp(self.min, self.max);
        (target, muted || combined < self.min)
    }
}
#[derive(Clone, Copy, Debug)]
pub enum Target {
    Phone(u16),
    Db(f64),
    Mute(bool),
    Adjust(f64),
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AuthorityVersion {
    pub session_id: String,
    pub revision: u64,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthoritySnapshot {
    #[serde(flatten)]
    pub version: AuthorityVersion,
    pub value_db: f64,
    pub muted: bool,
    pub observed_at_epoch_ms: u64,
}
#[derive(Debug)]
pub enum CommandError {
    Rejected(&'static str),
    Unknown(&'static str),
}
type Receipt = Result<AuthoritySnapshot, CommandError>;
#[derive(Debug)]
struct Request {
    target: Option<Target>,
    created: Instant,
    phone_epoch: Option<u64>,
    expected: Option<AuthorityVersion>,
    receipt: Option<oneshot::Sender<Receipt>>,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Adoption {
    version: u8,
    authority: String,
    phase: String,
    prior_db: f64,
    target_db: f64,
    prior_mute: bool,
    target_mute: bool,
}

pub struct VolumeBridge {
    range: Range,
    confirmed: AtomicU16,
    healthy: AtomicBool,
    active: AtomicBool,
    epoch: AtomicU64,
    requests: QueueMutex<VecDeque<Request>>,
    wake: Notify,
    state: QueueMutex<AuthoritySnapshot>,
}
impl VolumeBridge {
    pub fn session_id(&self) -> String {
        self.state.lock().unwrap().version.session_id.clone()
    }
    pub fn request(&self, target: Target) -> Result<(), String> {
        self.enqueue(Some(target), None, None)
            .map_err(str::to_owned)
    }
    fn enqueue(
        &self,
        target: Option<Target>,
        expected: Option<AuthorityVersion>,
        receipt: Option<oneshot::Sender<Receipt>>,
    ) -> Result<(), &'static str> {
        if !self.healthy.load(Ordering::Acquire) {
            return Err("Camilla Main authority is unavailable; no command queued");
        }
        if let Some(Target::Db(db)) = target {
            if !db.is_finite()
                || db < self.range.min
                || db > self.range.max
                || (self.range.normalise(db) - db).abs() > 0.000001
            {
                return Err("Main value violates reviewed range/step");
            }
        }
        if let Some(Target::Adjust(delta)) = target {
            if !delta.is_finite()
                || delta.abs() > self.range.max - self.range.min
                || (delta / self.range.step - (delta / self.range.step).round()).abs() > 0.000001
            {
                return Err("Main adjustment violates reviewed range/step");
            }
        }
        let mut queue = self.requests.lock().unwrap();
        if queue.len() >= 8 {
            return Err("Main command queue is full; command was not accepted");
        }
        queue.push_back(Request {
            target,
            created: Instant::now(),
            phone_epoch: matches!(target, Some(Target::Phone(_)))
                .then(|| self.epoch.load(Ordering::Acquire)),
            expected,
            receipt,
        });
        drop(queue);
        self.wake.notify_one();
        Ok(())
    }
    pub async fn confirmed(
        &self,
        target: Option<Target>,
        expected: Option<AuthorityVersion>,
    ) -> Receipt {
        let (tx, rx) = oneshot::channel();
        self.enqueue(target, expected, Some(tx))
            .map_err(CommandError::Rejected)?;
        rx.await.unwrap_or(Err(CommandError::Unknown(
            "Main owner ended before command confirmation",
        )))
    }
    pub fn set_active(&self, active: bool) {
        if self.active.swap(active, Ordering::AcqRel) != active {
            self.epoch.fetch_add(1, Ordering::AcqRel);
        }
        if !active {
            self.requests
                .lock()
                .unwrap()
                .retain(|r| r.phone_epoch.is_none());
        }
    }
    fn live(&self, request: &Request) -> bool {
        request.created.elapsed() <= Duration::from_millis(500)
            && request.phone_epoch.is_none_or(|epoch| {
                self.active.load(Ordering::Acquire) && epoch == self.epoch.load(Ordering::Acquire)
            })
    }
    fn publish(&self, db: f64, muted: bool) -> u16 {
        let volume = self.range.volume(db, muted);
        let mut state = self.state.lock().unwrap();
        if state.value_db != db || state.muted != muted {
            state.version.revision += 1;
        }
        state.value_db = db;
        state.muted = muted;
        state.observed_at_epoch_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        self.confirmed.store(volume, Ordering::Release);
        volume
    }
    fn discard(&self) {
        for request in self.requests.lock().unwrap().drain(..) {
            if let Some(tx) = request.receipt {
                let _ = tx.send(Err(CommandError::Rejected(
                    "Main connection changed; queued command discarded",
                )));
            }
        }
    }
    fn disconnect(&self) {
        self.healthy.store(false, Ordering::Release);
        self.discard();
    }
}
impl Mixer for VolumeBridge {
    fn open(_: MixerConfig) -> Result<Self, Error> {
        Err(Error::failed_precondition(
            "Camilla bridge must be initialized with a verified socket",
        ))
    }
    fn volume(&self) -> u16 {
        self.confirmed.load(Ordering::Acquire)
    }
    fn set_volume(&self, volume: u16) {
        // Only incoming Connect commands use this path. Our observation-only
        // notify_mixer_volume() never invokes Mixer::set_volume().
        if self.active.load(Ordering::Acquire) && volume != self.volume() {
            let _ = self.request(Target::Phone(volume));
        }
    }
    fn get_soft_volume(&self) -> Box<dyn VolumeGetter + Send> {
        Box::new(NoOpVolume)
    }
}

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;
async fn command(socket: &mut Socket, name: &str, arg: Option<Value>) -> Result<Value> {
    let payload = arg.map_or_else(|| json!(name), |v| json!({name:v}));
    timeout(
        Duration::from_secs(1),
        socket.send(Message::Text(payload.to_string().into())),
    )
    .await??;
    let response = timeout(Duration::from_secs(1), socket.next())
        .await?
        .context("Camilla socket ended")??;
    let Message::Text(text) = response else {
        bail!("Camilla reply is not text")
    };
    if text.len() > 65536 {
        bail!("Camilla reply exceeds bound")
    }
    let root: Value = serde_json::from_str(&text)?;
    let reply = root.get(name).context("Camilla reply command mismatch")?;
    if reply.get("result").and_then(Value::as_str) != Some("Ok") {
        bail!("Camilla command rejected")
    }
    Ok(reply.get("value").cloned().unwrap_or(Value::Null))
}
async fn connect(url: &str) -> Result<Socket> {
    let authority = url
        .strip_prefix("ws://127.0.0.1:")
        .context("Camilla volume socket must be explicit IPv4 loopback")?;
    let port = authority
        .trim_end_matches('/')
        .parse::<u16>()
        .context("invalid Camilla loopback port")?;
    if port == 0 {
        bail!("invalid Camilla loopback port")
    }
    let (mut socket, _) = timeout(Duration::from_secs(2), connect_async(url)).await??;
    if command(&mut socket, "GetVersion", None).await? != json!("4.1.3") {
        bail!("Camilla volume adapter requires 4.1.3")
    }
    if command(&mut socket, "GetState", None).await? != json!("Running") {
        bail!("Camilla is not running")
    }
    Ok(socket)
}
async fn read(socket: &mut Socket) -> Result<(f64, bool)> {
    let faders = command(socket, "GetFaders", None).await?;
    let main = faders
        .as_array()
        .and_then(|f| f.first())
        .context("missing Main fader")?;
    let value = main
        .get("volume")
        .and_then(Value::as_f64)
        .context("invalid Main volume")?;
    let muted = main
        .get("mute")
        .and_then(Value::as_bool)
        .context("invalid Main mute")?;
    if !value.is_finite() {
        bail!("invalid Main volume")
    };
    Ok((value, muted))
}
async fn checked_read(socket: &mut Socket, range: Range) -> Result<(f64, bool)> {
    let current = read(socket).await?;
    if !(range.min..=range.max).contains(&current.0) {
        bail!("observed Main exceeds reviewed range")
    }
    Ok(current)
}
async fn apply(
    socket: &mut Socket,
    target: Target,
    range: Range,
    current: (f64, bool),
) -> Result<(f64, bool)> {
    let (db, muted) = match target {
        Target::Phone(0) => (current.0, true),
        Target::Phone(v) => (range.db(v), false),
        Target::Db(db) => (db, current.1),
        Target::Mute(m) => (current.0, m),
        Target::Adjust(delta) => (range.normalise(current.0 + delta), current.1),
    };
    if db != current.0 {
        command(socket, "SetVolume", Some(json!(db))).await?;
    }
    if muted != current.1 {
        command(socket, "SetMute", Some(json!(muted))).await?;
    }
    let confirmed = read(socket).await?;
    if (confirmed.0 - db).abs() > 0.01 || confirmed.1 != muted {
        bail!("Main target was not confirmed")
    }
    Ok(confirmed)
}
fn save(path: &Path, record: &Adoption) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let temp = path.with_extension("volume-tmp");
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o640)
        .open(&temp)?;
    file.write_all(&serde_json::to_vec(record)?)?;
    file.sync_all()?;
    std::fs::rename(temp, path)?;
    Ok(())
}
async fn adopt(
    socket: &mut Socket,
    path: &Path,
    range: Range,
    legacy_volume: u16,
) -> Result<(f64, bool)> {
    let config = command(socket, "GetConfigJson", None).await?;
    let config: Value = if let Some(text) = config.as_str() {
        serde_json::from_str(text)?
    } else {
        config
    };
    let ramp = config
        .pointer("/devices/volume_ramp_time")
        .filter(|v| !v.is_null())
        .map_or(Some(400.0), Value::as_f64)
        .context("invalid Camilla ramp duration")?;
    if !ramp.is_finite() || !(0.0..=10_000.0).contains(&ramp) {
        bail!("Camilla ramp duration needs review")
    }
    let limit = config
        .pointer("/devices/volume_limit")
        .filter(|v| !v.is_null())
        .map_or(Some(50.0), Value::as_f64)
        .context("invalid Camilla volume limit")?;
    if !limit.is_finite() || range.max > limit {
        bail!("Camilla volume limit needs review")
    }
    let current = checked_read(socket, range).await?;
    let mut record = if path.exists() {
        let metadata = std::fs::symlink_metadata(path)?;
        if !metadata.is_file() || metadata.len() > 4096 {
            bail!("invalid adoption record file")
        }
        let r: Adoption = serde_json::from_slice(&std::fs::read(path)?)?;
        if r.version != 1
            || r.authority != "camilla-main"
            || !["prepared", "active"].contains(&r.phase.as_str())
            || !r.prior_db.is_finite()
            || !r.target_db.is_finite()
            || !(range.min..=range.max).contains(&r.prior_db)
            || !(range.min..=range.max).contains(&r.target_db)
        {
            bail!("invalid volume adoption record")
        }
        if r.phase == "active" {
            return Ok(current);
        }
        if ((current.0 - r.prior_db).abs() > 0.01 && (current.0 - r.target_db).abs() > 0.01)
            || (current.1 != r.prior_mute && current.1 != r.target_mute)
        {
            bail!("Main changed during incomplete adoption; manual review required")
        }
        r
    } else {
        let factor = VolumeCtrl::default().to_mapped(legacy_volume);
        let target = range.migration(current.0, current.1, factor);
        let r = Adoption {
            version: 1,
            authority: "camilla-main".into(),
            phase: "prepared".into(),
            prior_db: current.0,
            target_db: target.0,
            prior_mute: current.1,
            target_mute: target.1,
        };
        save(path, &r)?;
        r
    };
    // No Player/Spirc is created before adoption completes. No PCM is amplified
    // during the handover, and a prepared record cannot attenuate a second time.
    let mut confirmed = apply(socket, Target::Db(record.target_db), range, current).await?;
    confirmed = apply(socket, Target::Mute(record.target_mute), range, confirmed).await?;
    // Target readback precedes DSP gain ramp completion. Keep the player
    // uncreated until the existing ramp has settled; do not change its config.
    tokio::time::sleep(Duration::from_secs_f64((ramp + 50.0) / 1000.0)).await;
    record.phase = "active".into();
    save(path, &record)?;
    Ok(confirmed)
}

pub async fn start(
    url: String,
    path: PathBuf,
    range: Range,
    legacy_volume: u16,
    spirc: Arc<Mutex<Option<Arc<Spirc>>>>,
) -> Result<Arc<VolumeBridge>> {
    let range = range.validate()?;
    let mut socket = connect(&url).await?;
    let current = adopt(&mut socket, &path, range, legacy_volume).await?;
    let bridge = Arc::new(VolumeBridge {
        range,
        confirmed: AtomicU16::new(range.volume(current.0, current.1)),
        healthy: AtomicBool::new(true),
        active: AtomicBool::new(false),
        epoch: AtomicU64::new(0),
        requests: QueueMutex::new(VecDeque::new()),
        wake: Notify::new(),
        state: QueueMutex::new(AuthoritySnapshot {
            version: AuthorityVersion {
                session_id: format!(
                    "main-{}-{}",
                    std::process::id(),
                    SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_nanos()
                ),
                revision: 1,
            },
            value_db: current.0,
            muted: current.1,
            observed_at_epoch_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
        }),
    });
    let owner = bridge.clone();
    tokio::spawn(async move {
        let mut tick = interval(Duration::from_millis(100));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut last_published = None;
        loop {
            if owner.requests.lock().unwrap().is_empty() {
                tokio::select! {_=tick.tick()=>{}, _=owner.wake.notified()=>{}}
            }
            let request = owner.requests.lock().unwrap().pop_front();
            let result = execute(&owner, &mut socket, range, request).await;
            match result {
                Ok((db, muted)) => {
                    let volume = owner.publish(db, muted);
                    if owner.active.load(Ordering::Acquire) {
                        if last_published != Some(volume) {
                            if let Some(target) = spirc.lock().await.as_ref() {
                                if target.notify_mixer_volume().is_ok() {
                                    last_published = Some(volume)
                                }
                            }
                        }
                    } else {
                        last_published = None;
                    }
                }
                Err(_) => {
                    owner.disconnect();
                    last_published = None;
                    loop {
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        if let Ok(candidate) = connect(&url).await {
                            socket = candidate;
                            break;
                        }
                    }
                    // Drop everything queued before recovery. Read current Main
                    // first; an old gesture/phone volume is never replayed.
                    owner.discard();
                    if let Ok((db, muted)) = checked_read(&mut socket, range).await {
                        owner.publish(db, muted);
                        owner.healthy.store(true, Ordering::Release);
                    }
                }
            }
        }
    });
    Ok(bridge)
}

async fn execute(
    owner: &VolumeBridge,
    socket: &mut Socket,
    range: Range,
    mut request: Option<Request>,
) -> Result<(f64, bool)> {
    let current = match checked_read(socket, range).await {
        Ok(current) => current,
        Err(error) => {
            if let Some(tx) = request.as_mut().and_then(|r| r.receipt.take()) {
                let _ = tx.send(Err(CommandError::Rejected(
                    "Main read failed before writing; command was not sent",
                )));
            }
            return Err(error);
        }
    };
    owner.publish(current.0, current.1);
    let Some(mut request) = request else {
        return Ok(current);
    };
    let rejected =
        if !owner.live(&request) || request.receipt.as_ref().is_some_and(|r| r.is_closed()) {
            Some("Main request expired or its session ended; command was not sent")
        } else if request
            .expected
            .as_ref()
            .is_some_and(|expected| *expected != owner.state.lock().unwrap().version)
        {
            Some("Main authority version changed; command was not sent")
        } else {
            None
        };
    if let Some(reason) = rejected {
        if let Some(tx) = request.receipt.take() {
            let _ = tx.send(Err(CommandError::Rejected(reason)));
        }
        return Ok(current);
    }
    let confirmed = if let Some(target) = request.target {
        match apply(socket, target, range, current).await {
            Ok(confirmed) => {
                owner.publish(confirmed.0, confirmed.1);
                if confirmed == current {
                    owner.state.lock().unwrap().version.revision += 1;
                }
                confirmed
            }
            Err(error) => {
                if let Some(tx) = request.receipt.take() {
                    let _ = tx.send(Err(CommandError::Unknown(
                        "Main command was issued but readback was lost; do not replay",
                    )));
                }
                return Err(error);
            }
        }
    } else {
        current
    };
    if let Some(tx) = request.receipt.take() {
        let _ = tx.send(Ok(owner.state.lock().unwrap().clone()));
    }
    Ok(confirmed)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn range() -> Range {
        Range {
            min: -60.0,
            max: 0.0,
            step: 1.0,
        }
    }
    fn bridge() -> VolumeBridge {
        VolumeBridge {
            range: range(),
            confirmed: AtomicU16::new(32768),
            healthy: AtomicBool::new(true),
            active: AtomicBool::new(true),
            epoch: AtomicU64::new(0),
            requests: QueueMutex::new(VecDeque::new()),
            wake: Notify::new(),
            state: QueueMutex::new(AuthoritySnapshot {
                version: AuthorityVersion {
                    session_id: "test-main".into(),
                    revision: 1,
                },
                value_db: -30.0,
                muted: false,
                observed_at_epoch_ms: 1,
            }),
        }
    }
    async fn fake_dsp(fail_after_write: bool) -> (String, Arc<Mutex<(f64, bool, usize)>>) {
        use tokio::net::TcpListener;
        use tokio_tungstenite::accept_async;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("ws://{}", listener.local_addr().unwrap());
        let dsp = Arc::new(Mutex::new((-10.0, false, 0usize)));
        let server_dsp = dsp.clone();
        tokio::spawn(async move {
            loop {
                let (tcp, _) = listener.accept().await.unwrap();
                let data = server_dsp.clone();
                tokio::spawn(async move {
                    let mut socket = accept_async(tcp).await.unwrap();
                    while let Some(Ok(Message::Text(text))) = socket.next().await {
                        let root: Value = serde_json::from_str(&text).unwrap();
                        let (name, arg) = if let Some(name) = root.as_str() {
                            (name.to_owned(), Value::Null)
                        } else {
                            let (name, arg) = root.as_object().unwrap().iter().next().unwrap();
                            (name.clone(), arg.clone())
                        };
                        let mut d = data.lock().await;
                        if fail_after_write && d.2 > 0 && name == "GetFaders" {
                            drop(d);
                            let _ = socket.close(None).await;
                            break;
                        }
                        let value = match name.as_str() {
                            "GetVersion" => json!("4.1.3"),
                            "GetState" => json!("Running"),
                            "GetFaders" => json!([{"volume":d.0,"mute":d.1}]),
                            "GetVolume" => json!(d.0),
                            "GetMute" => json!(d.1),
                            "GetConfigJson" => json!({"devices":{"volume_ramp_time":0}}),
                            "SetVolume" => {
                                d.0 = arg.as_f64().unwrap();
                                d.2 += 1;
                                Value::Null
                            }
                            "SetMute" => {
                                d.1 = arg.as_bool().unwrap();
                                d.2 += 1;
                                Value::Null
                            }
                            _ => panic!("unexpected DSP command"),
                        };
                        drop(d);
                        if socket
                            .send(Message::Text(
                                json!({name:{"result":"Ok","value":value}})
                                    .to_string()
                                    .into(),
                            ))
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                });
            }
        });
        (url, dsp)
    }
    #[tokio::test]
    async fn real_socket_adoption_feedback_and_restart_preserve_one_gain() {
        let (url, dsp) = fake_dsp(false).await;
        let directory = tempfile::tempdir().unwrap();
        let record = directory.path().join("authority.json");
        let handles = Arc::new(Mutex::new(None));
        let bridge = start(url.clone(), record.clone(), range(), 32767, handles.clone())
            .await
            .unwrap();
        assert_eq!(dsp.lock().await.0, -41.0);
        assert_eq!(bridge.get_soft_volume().attenuation_factor(), 1.0);
        bridge.request(Target::Db(-20.0)).unwrap();
        timeout(Duration::from_secs(2), async {
            while bridge.volume() != range().volume(-20.0, false) {
                tokio::time::sleep(Duration::from_millis(5)).await
            }
        })
        .await
        .unwrap();
        bridge.set_active(true);
        let writes = dsp.lock().await.2;
        bridge.set_volume(bridge.volume());
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert_eq!(dsp.lock().await.2, writes);
        dsp.lock().await.0 = -16.0;
        timeout(Duration::from_secs(2), async {
            while bridge.volume() != range().volume(-16.0, false) {
                tokio::time::sleep(Duration::from_millis(5)).await
            }
        })
        .await
        .unwrap();
        assert_eq!(dsp.lock().await.2, writes);
        bridge.set_volume(65535);
        timeout(Duration::from_secs(2), async {
            while bridge.volume() != 65535 {
                tokio::time::sleep(Duration::from_millis(5)).await
            }
        })
        .await
        .unwrap();
        assert_eq!(dsp.lock().await.0, 0.0);
        bridge.request(Target::Mute(true)).unwrap();
        timeout(Duration::from_secs(2), async {
            while bridge.volume() != 0 {
                tokio::time::sleep(Duration::from_millis(5)).await
            }
        })
        .await
        .unwrap();
        let before = *dsp.lock().await;
        let restarted = start(url, record, range(), 1, handles).await.unwrap();
        assert_eq!(*dsp.lock().await, before);
        assert_eq!(restarted.volume(), 0);
    }

    async fn fixture() -> (
        Arc<VolumeBridge>,
        Arc<Mutex<(f64, bool, usize)>>,
        tempfile::TempDir,
    ) {
        let (url, dsp) = fake_dsp(false).await;
        let dir = tempfile::tempdir().unwrap();
        let owner = start(
            url,
            dir.path().join("authority.json"),
            range(),
            u16::MAX,
            Arc::new(Mutex::new(None)),
        )
        .await
        .unwrap();
        owner.set_active(true);
        (owner, dsp, dir)
    }
    #[tokio::test]
    async fn two_absolute_clients_using_one_revision_cannot_overwrite_each_other() {
        let (owner, dsp, _dir) = fixture().await;
        let base = owner.confirmed(None, None).await.unwrap();
        let (a, b) = tokio::join!(
            owner.confirmed(Some(Target::Db(-21.0)), Some(base.version.clone())),
            owner.confirmed(Some(Target::Db(-22.0)), Some(base.version.clone()))
        );
        assert_eq!(a.unwrap().value_db, -21.0);
        assert!(matches!(b, Err(CommandError::Rejected(_))));
        assert_eq!(dsp.lock().await.0, -21.0);
        assert_eq!(dsp.lock().await.2, 1);
        // ABA changes still invalidate the previous version.
        owner
            .confirmed(Some(Target::Db(-10.0)), None)
            .await
            .unwrap();
        let stale = owner
            .confirmed(Some(Target::Db(-19.0)), Some(base.version))
            .await;
        assert!(matches!(stale, Err(CommandError::Rejected(_))));
        assert_eq!(dsp.lock().await.0, -10.0);
    }
    #[tokio::test]
    async fn two_relative_clients_apply_every_step_to_current_state_in_order() {
        let (owner, dsp, _dir) = fixture().await;
        let (up, down) = tokio::join!(
            owner.confirmed(Some(Target::Adjust(1.0)), None),
            owner.confirmed(Some(Target::Adjust(-1.0)), None)
        );
        assert_eq!(up.unwrap().value_db, -9.0);
        assert_eq!(down.unwrap().value_db, -10.0);
        assert_eq!(dsp.lock().await.0, -10.0);
        assert_eq!(dsp.lock().await.2, 2);
    }
    #[tokio::test]
    async fn overlapping_volume_and_mute_have_individual_actual_receipts() {
        let (owner, dsp, _dir) = fixture().await;
        let (volume, mute) = tokio::join!(
            owner.confirmed(Some(Target::Db(-25.0)), None),
            owner.confirmed(Some(Target::Mute(true)), None)
        );
        let volume = volume.unwrap();
        let mute = mute.unwrap();
        assert_eq!(volume.value_db, -25.0);
        assert!(!volume.muted);
        assert_eq!(mute.value_db, -25.0);
        assert!(mute.muted);
        assert!(mute.version.revision > volume.version.revision);
        assert_eq!(*dsp.lock().await, (-25.0, true, 2));
    }
    #[tokio::test]
    async fn expired_command_is_rejected_without_a_dsp_write() {
        let (owner, dsp, _dir) = fixture().await;
        let (tx, rx) = oneshot::channel();
        owner.requests.lock().unwrap().push_back(Request {
            target: Some(Target::Db(-1.0)),
            created: Instant::now() - Duration::from_millis(600),
            phone_epoch: None,
            expected: None,
            receipt: Some(tx),
        });
        owner.wake.notify_one();
        assert!(matches!(rx.await.unwrap(), Err(CommandError::Rejected(_))));
        assert_eq!(*dsp.lock().await, (-10.0, false, 0));
    }

    #[tokio::test]
    async fn lost_post_write_readback_is_unknown_and_cannot_be_replayed() {
        let (url, dsp) = fake_dsp(true).await;
        let dir = tempfile::tempdir().unwrap();
        let owner = start(
            url,
            dir.path().join("authority.json"),
            range(),
            u16::MAX,
            Arc::new(Mutex::new(None)),
        )
        .await
        .unwrap();
        let result = owner.confirmed(Some(Target::Db(-25.0)), None).await;
        assert!(matches!(result, Err(CommandError::Unknown(_))));
        assert_eq!(*dsp.lock().await, (-25.0, false, 1));
        assert!(matches!(
            owner.confirmed(Some(Target::Db(-25.0)), None).await,
            Err(CommandError::Rejected(_))
        ));
        tokio::time::sleep(Duration::from_millis(150)).await;
        assert_eq!(dsp.lock().await.2, 1);
    }

    #[test]
    fn curve_and_zero_mute_have_one_definition() {
        let r = range();
        assert_eq!(r.db(1), -60.0);
        assert_eq!(r.db(65535), 0.0);
        assert_eq!(r.volume(-30.0, false), 32768);
        assert_eq!(r.volume(-30.0, true), 0);
        for db in -60..=0 {
            assert_eq!(r.db(r.volume(db as f64, false)), db as f64)
        }
    }
    #[test]
    fn migration_never_increases_combined_gain() {
        let r = range();
        for old in -60..=0 {
            for volume in [0, 1, 8000, 32767, 65535] {
                let factor = VolumeCtrl::default().to_mapped(volume);
                let (db, muted) = r.migration(old as f64, false, factor);
                if !muted {
                    assert!(db <= old as f64 + 20.0 * factor.log10() + 0.000001)
                }
            }
        }
    }
    #[test]
    fn session_change_discards_phone_requests_but_retains_gateway_master() {
        let b = bridge();
        b.set_volume(45000);
        let old = b.requests.lock().unwrap().pop_front().unwrap();
        assert!(b.live(&old));
        b.set_volume(45000);
        b.set_active(false);
        assert!(b.requests.lock().unwrap().is_empty());
        b.set_active(true);
        assert!(!b.live(&old));
        b.request(Target::Db(-20.0)).unwrap();
        b.set_active(false);
        let local = b.requests.lock().unwrap().pop_front().unwrap();
        assert!(b.live(&local));
    }
    #[test]
    fn matching_incoming_spotify_value_needs_no_dsp_command() {
        let b = bridge();
        b.set_volume(32768);
        assert!(b.requests.lock().unwrap().is_empty());
        b.set_volume(45000);
        assert!(matches!(
            b.requests.lock().unwrap().front().unwrap().target,
            Some(Target::Phone(45000))
        ));
    }
    #[test]
    fn independent_commands_are_fifo_bounded_and_disconnect_discards_them() {
        let b = bridge();
        b.request(Target::Mute(true)).unwrap();
        for db in -60..-53 {
            b.request(Target::Db(db as f64)).unwrap();
        }
        assert!(b.request(Target::Db(-10.0)).is_err());
        let mut queue = b.requests.lock().unwrap();
        assert!(matches!(
            queue.pop_front().unwrap().target,
            Some(Target::Mute(true))
        ));
        for db in -60..-53 {
            assert!(
                matches!(queue.pop_front().unwrap().target, Some(Target::Db(value)) if value == db as f64)
            );
        }
        drop(queue);
        b.request(Target::Db(-10.0)).unwrap();
        b.disconnect();
        assert!(b.requests.lock().unwrap().is_empty());
        assert!(b.request(Target::Mute(false)).is_err());
    }
    #[test]
    fn inactive_cache_and_illegal_values_cannot_change_main() {
        let b = bridge();
        b.set_active(false);
        b.set_volume(65535);
        assert!(b.requests.lock().unwrap().is_empty());
        for db in [1.0, -61.0, f64::NAN, -5.5] {
            assert!(b.request(Target::Db(db)).is_err());
        }
        assert_eq!(b.get_soft_volume().attenuation_factor(), 1.0);
    }
}

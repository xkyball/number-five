// No5 sound extension for CamillaDSP 4.1.3. SPDX-License-Identifier: MPL-2.0
// Control/FFT threads allocate. The processing hooks use fixed banks and rings;
// they never acquire a mutex, perform I/O, serialize JSON, or plan an FFT.
use crate::{audiochunk::AudioChunk, config, filters::biquad::BiquadCoefficients as Coeff};
use crossbeam_channel::{Receiver, Sender, bounded};
use parking_lot::Mutex;
use realfft::RealFftPlanner;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    cell::UnsafeCell,
    collections::VecDeque,
    sync::{
        Arc, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const RATE: usize = 48_000;
const BLOCK: usize = 1024;
const FFT: usize = 8192;
const DELAY: usize = 4096;
const FADE: usize = 2400;
const IDENTITY: Coeff = Coeff {
    a1: 0.0,
    a2: 0.0,
    b0: 1.0,
    b1: 0.0,
    b2: 0.0,
};
const MARKER: &str = "No5 sound v1 / ";

#[path="no5_eq_core.rs"]
mod eq_core;
pub use eq_core::{Point,Preset,Room};
use eq_core::{project_eq_budget,visible_band};
#[cfg(test)] use eq_core::{project_eq_budget_uncached,visible_edit_compatible,LEGACY_PEAK};
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Calibration {
    delays_ms: [f64; 6],
    gains_db: [f64; 6],
}
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct EqEditRequest {
    schema: String,
    experimental: bool,
    previous_point: Option<Point>,
    edited_point_id: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Target {
    expected_config_generation: String,
    expected_source_generation: String,
    expected_applied_revision: u64,
    revision: u64,
    preset: Preset,
    calibration: Calibration,
    transition_ms: u32,
    valid_for_ms: u64,
    #[serde(deserialize_with = "required_nullable_lease")]
    preview_lease: Option<LeaseRequest>,
    #[serde(default)]
    eq_editing: Option<EqEditRequest>,
}
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LeaseRequest {
    id: String,
    valid_for_ms: u64,
    fallback_preset: Preset,
    fallback_calibration: Calibration,
}
fn required_nullable_lease<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<LeaseRequest>, D::Error> {
    Option::<LeaseRequest>::deserialize(deserializer)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LeaseUpdate {
    #[serde(default)]
    experimental: bool,
    id: String,
    expected_config_generation: String,
    expected_source_generation: String,
    expected_applied_revision: u64,
    valid_for_ms: u64,
    fallback_preset: Preset,
    fallback_calibration: Calibration,
}
#[derive(Clone, Copy)]
struct Prepared {
    coeff: [Coeff; 16],
    headroom: f64,
    room_amount: f64,
    room_delay: [[f64; 4]; 2],
    delay: [f64; 6],
    gain: [f64; 6],
    revision: u64,
    generation: u64,
    valid_until_ms: u64,
    rollback: bool,
}
impl Default for Prepared {
    fn default() -> Self {
        Self {
            coeff: [IDENTITY; 16],
            headroom: 1.0,
            room_amount: 0.0,
            room_delay: [[0.0; 4]; 2],
            delay: [0.0; 6],
            gain: [1.0; 6],
            revision: 0,
            generation: 0,
            valid_until_ms: u64::MAX,
            rollback: false,
        }
    }
}
#[derive(Clone)]
struct Record {
    experimental: bool,
    normal_preset: Option<Preset>,
    revision: u64,
    preset: Preset,
    calibration: Calibration,
    headroom_db: f64,
    rollback: Option<Rollback>,
    prepared: Prepared,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Rollback {
    lease_id: String,
    from_revision: u64,
    to_revision: u64,
}
struct Lease {
    id: String,
    generation: u64,
    owned_revision: u64,
    until_ms: u64,
    fallback: Prepared,
    record: Record,
}
struct TargetExpiry {
    generation: u64,
    owned_revision: u64,
    until_ms: u64,
    fallback: Prepared,
    record: Record,
}
struct Models {
    generation: String,
    routing: String,
    records: VecDeque<Record>,
    lease: Option<Lease>,
    target_expiry: Option<TargetExpiry>,
    last_rollback: Option<Rollback>,
}
struct Samples {
    position: u64,
    generation: u64,
    epoch: u64,
    sequence: u64,
    timestamp_ns: u64,
    frames: usize,
    samples: [[f64; BLOCK]; 2],
}
struct Slot {
    full: AtomicBool,
    data: UnsafeCell<Samples>,
}
// Single producer (Camilla processing thread), single consumer (FFT worker).
// Producer only mutates a free slot; release/acquire transfers exclusive
// ownership, and consumer releases it only after all reads are complete.
unsafe impl Sync for Slot {}
struct SampleRing {
    slots: Box<[Slot]>,
    write: AtomicU64,
    read: AtomicU64,
}
impl SampleRing {
    fn new() -> Self {
        Self {
            slots: (0..64)
                .map(|_| Slot {
                    full: AtomicBool::new(false),
                    data: UnsafeCell::new(Samples {
                        position: 0,
                        generation: 0,
                        epoch: 0,
                        sequence: 0,
                        timestamp_ns: 0,
                        frames: 0,
                        samples: [[0.0; BLOCK]; 2],
                    }),
                })
                .collect(),
            write: AtomicU64::new(0),
            read: AtomicU64::new(0),
        }
    }
    fn push(
        &self,
        chunk: &AudioChunk,
        position: u64,
        generation: u64,
        sequence: u64,
        timestamp_ns: u64,
        epoch: u64,
    ) {
        let index = self.write.load(Ordering::Relaxed);
        let slot = &self.slots[index as usize % 64];
        if slot.full.load(Ordering::Acquire) || chunk.valid_frames > BLOCK {
            return;
        }
        // SAFETY: this slot is free, and this is the sole producer.
        let data = unsafe { &mut *slot.data.get() };
        data.position = position;
        data.generation = generation;
        data.sequence = sequence;
        data.epoch = epoch;
        data.timestamp_ns = timestamp_ns;
        data.frames = chunk.valid_frames;
        for channel in 0..2 {
            data.samples[channel][..data.frames]
                .copy_from_slice(&chunk.waveforms[channel][..data.frames]);
        }
        slot.full.store(true, Ordering::Release);
        self.write.store(index + 1, Ordering::Relaxed);
    }
    fn consume(&self, mut action: impl FnMut(&Samples)) -> bool {
        let index = self.read.load(Ordering::Relaxed);
        let slot = &self.slots[index as usize % 64];
        if !slot.full.load(Ordering::Acquire) {
            return false;
        }
        // SAFETY: full slot belongs to the sole consumer until it is released.
        action(unsafe { &*slot.data.get() });
        slot.full.store(false, Ordering::Release);
        self.read.store(index + 1, Ordering::Relaxed);
        true
    }
}
struct Control {
    models: Mutex<Models>,
    targets_tx: Sender<Prepared>,
    targets_rx: Receiver<Prepared>,
    generation: AtomicU64,
    applied: AtomicU64,
    prepared: AtomicU64,
    active: AtomicBool,
    spectrum_until: AtomicU64,
    analysis_epoch: AtomicU64,
    ring: SampleRing,
    spectrum: Mutex<Value>,
    clock: Instant,
    rollback_revision: AtomicU64,
    started_revision: AtomicU64,
}
static CONTROL: OnceLock<Arc<Control>> = OnceLock::new();
fn control() -> &'static Arc<Control> {
    CONTROL.get_or_init(|| new_control(true))
}
fn new_control(worker: bool) -> Arc<Control> {
    let (tx, rx) = bounded(64);
    let value = Arc::new(Control {
        models: Mutex::new(Models {
            generation: String::new(),
            routing: String::new(),
            records: VecDeque::with_capacity(256),
            lease: None,
            target_expiry: None,
            last_rollback: None,
        }),
        targets_tx: tx,
        targets_rx: rx,
        generation: AtomicU64::new(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .expect("UTC clock")
                .as_nanos() as u64,
        ),
        applied: AtomicU64::new(0),
        prepared: AtomicU64::new(0),
        active: AtomicBool::new(false),
        spectrum_until: AtomicU64::new(0),
        analysis_epoch: AtomicU64::new(0),
        ring: SampleRing::new(),
        spectrum: Mutex::new(Value::Null),
        clock: Instant::now(),
        rollback_revision: AtomicU64::new(0),
        started_revision: AtomicU64::new(0),
    });
    if worker {
        let worker = value.clone();
        std::thread::Builder::new()
            .name("no5-spectrum".into())
            .spawn(move || spectrum_worker(worker))
            .expect("spectrum worker");
    }
    value
}
fn elapsed_ms(c: &Control) -> u64 {
    c.clock.elapsed().as_millis() as u64
}
fn valid_lease_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
fn refresh_rollback(c: &Control, models: &mut Models) {
    let revision = c.rollback_revision.load(Ordering::Acquire);
    if revision == 0
        || models
            .last_rollback
            .as_ref()
            .is_some_and(|r| r.to_revision == revision)
    {
        return;
    }
    if let Some(rollback) = models
        .records
        .iter()
        .find(|r| r.revision == revision)
        .and_then(|r| r.rollback.clone())
    {
        models.last_rollback = Some(rollback);
    }
}
fn history_room(models: &mut Models, applied: u64, limit: usize) -> Result<(), String> {
    while models.records.len() >= limit {
        let Some(index) = models.records.iter().position(|r| r.revision < applied) else {
            return Err("RESULT_HISTORY_FULL".into());
        };
        models.records.remove(index);
    }
    Ok(())
}
fn queue_fallback(
    c: &Control,
    models: &mut Models,
    mut prepared: Prepared,
    mut record: Record,
    lease: Option<(&str, u64)>,
) -> bool {
    let applied = c.applied.load(Ordering::Acquire);
    if history_room(models, applied, 256).is_err() {
        return false;
    }
    let revision = c.prepared.load(Ordering::Acquire).max(applied) + 1;
    if revision > 9_007_199_254_740_991 {
        return false;
    }
    prepared.revision = revision;
    prepared.valid_until_ms = u64::MAX; // Producer may resume after any pause.
    prepared.rollback = lease.is_some();
    record.revision = revision;
    record.prepared = prepared;
    record.rollback = lease.map(|(id, from)| Rollback {
        lease_id: id.into(),
        from_revision: from,
        to_revision: revision,
    });
    // Metadata exists before the processing thread can publish this revision.
    models.records.push_back(record);
    if c.targets_tx.try_send(prepared).is_err() {
        models.records.pop_back();
        return false;
    }
    c.prepared.store(revision, Ordering::Release);
    true
}
fn service_lease(c: &Control) {
    let mut models = c.models.lock();
    refresh_rollback(c, &mut models);
    let generation = c.generation.load(Ordering::Acquire);
    let prepared = c.prepared.load(Ordering::Acquire);
    if !c.active.load(Ordering::Acquire) {
        models.lease = None;
        models.target_expiry = None;
        return;
    }
    if let Some(lease) = models.lease.take() {
        if lease.generation == generation && lease.owned_revision == prepared {
            if elapsed_ms(c) < lease.until_ms
                || !queue_fallback(
                    c,
                    &mut models,
                    lease.fallback,
                    lease.record.clone(),
                    Some((&lease.id, lease.owned_revision)),
                )
            {
                models.lease = Some(lease);
            }
        }
        return;
    }
    if let Some(expiry) = models.target_expiry.take() {
        if expiry.generation != generation
            || expiry.owned_revision != prepared
            || expiry.owned_revision == c.applied.load(Ordering::Acquire)
            || expiry.owned_revision == c.started_revision.load(Ordering::Acquire)
        {
            return;
        }
        if elapsed_ms(c) < expiry.until_ms
            || !queue_fallback(c, &mut models, expiry.fallback, expiry.record.clone(), None)
        {
            models.target_expiry = Some(expiry);
        }
    }
}

fn prepare(target: &Target, routing: &str) -> Result<(Prepared, f64), String> {
    prepare_eq_budget(target, routing, false)
}
// Only an explicitly leased preview may eventually call this with experimental=true.
// Existing production entry points remain normal until the versioned lease contract is connected.
fn prepare_eq_budget(
    target: &Target,
    routing: &str,
    experimental: bool,
) -> Result<(Prepared, f64), String> {
    let p = &target.preset;
    let c = &target.calibration;
    let valid_id = |s: &str| {
        !s.is_empty()
            && s.len() <= 64
            && s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    };
    if !valid_id(&p.id)
        || p.name.trim().is_empty()
        || p.name.len() > 128
        || p.name.chars().count() > 100
        || p.name.chars().any(char::is_control)
        || p.eq_points.len() > 16
        || !p.reserve_db.is_finite()
        || !(6.0..=18.0).contains(&p.reserve_db)
        || target.revision > 9_007_199_254_740_991
        || p.routing_template != routing
        || target.transition_ms != 50
        || !p.room.amount.is_finite()
        || !(0.0..=1.0).contains(&p.room.amount)
        || !p.room.size.is_finite()
        || !(0.0..=1.0).contains(&p.room.size)
        || (routing == "front_stereo_fullrange" && p.room.amount != 0.0)
    {
        return Err("UNSUPPORTED_OR_INVALID_SOUND_TARGET".into());
    }
    let mut result = Prepared {
        revision: target.revision,
        room_amount: p.room.amount,
        ..Prepared::default()
    };
    let (eq,headroom)=eq_core::prepare_preset(p,experimental)?;
    result.coeff=eq.coeff.map(|c|Coeff{a1:c.a1,a2:c.a2,b0:c.b0,b1:c.b1,b2:c.b2});
    result.headroom = 10.0f64.powf(-headroom / 20.0);
    for channel in 0..6 {
        if !c.delays_ms[channel].is_finite()
            || !(0.0..=50.0).contains(&c.delays_ms[channel])
            || !c.gains_db[channel].is_finite()
            || !(-120.0..=0.0).contains(&c.gains_db[channel])
        {
            return Err("INVALID_CALIBRATION".into());
        }
        result.delay[channel] = c.delays_ms[channel] * RATE as f64 / 1000.0;
        result.gain[channel] = 10.0f64.powf(c.gains_db[channel] / 20.0);
    }
    if c.delays_ms[2] != c.delays_ms[3] || c.gains_db[2] != c.gains_db[3] {
        return Err("SUB_OUTPUTS_MUST_MATCH".into());
    }
    let first = (5.0 + p.room.size * 20.0) * RATE as f64 / 1000.0;
    for (side, factors) in [[1.0, 1.47, 1.91, 2.31], [1.09, 1.59, 2.07, 2.4]]
        .iter()
        .enumerate()
    {
        for (i, factor) in factors.iter().enumerate() {
            result.room_delay[side][i] = first * factor;
        }
    }
    Ok((result, headroom))
}
pub fn get_sound() -> Value {
    get_sound_for(control())
}
fn get_sound_for(c: &Control) -> Value {
    if !c.active.load(Ordering::Acquire) {
        return Value::Null;
    }
    let mut models = c.models.lock();
    refresh_rollback(c, &mut models);
    let applied = c.applied.load(Ordering::Acquire);
    let Some(record) = models.records.iter().find(|r| r.revision == applied) else {
        return Value::Null;
    };
    let lease = models.lease.as_ref().map(|l| {
        json!({"id":l.id,
        "fallbackPreset":l.record.preset,"fallbackCalibration":l.record.calibration})
    });
    json!({"schema":"no5.camilla-sound/2", "configGeneration":models.generation,
        "sourceGeneration":c.generation.load(Ordering::Acquire).to_string(), "routingTemplate":models.routing,
        "sampleRateHz":RATE,"appliedRevision":applied,"preparedRevision":c.prepared.load(Ordering::Acquire),
        "headroomDb":record.headroom_db,"preset":record.preset,"calibration":record.calibration,
        "verification":"PROCESSED_BLOCK","transitionMs":50,"previewLease":lease,
        "lastRollback":models.last_rollback,
        "eqPolicy":{"minReserveDb":6.0,"maxReserveDb":18.0,"defaultReserveDb":12.0,
            "positiveBandsMustNotOverlap":true},
        "eqEditing":{"schema":"no5.eq-editing/1","previewProjection":true,
            "validationPolicy":eq_core::policy(),"maxExperimentalBoostDb":3.0,"experimental":record.experimental,"normalPreset":record.normal_preset}})
}
pub fn set_sound(value: Value) -> Result<Value, String> {
    set_sound_for(control(), value)
}
fn set_sound_for(c: &Control, value: Value) -> Result<Value, String> {
    let received = elapsed_ms(c);
    let mut target: Target = serde_json::from_value(value).map_err(|_| "INVALID_SOUND_REQUEST")?;
    if !(1..=5000).contains(&target.valid_for_ms) {
        return Err("INVALID_TARGET_VALIDITY".into());
    }
    let mut models = c.models.lock();
    let generation = c.generation.load(Ordering::Acquire);
    if !c.active.load(Ordering::Acquire)
        || target.expected_config_generation != models.generation
        || target.expected_source_generation != generation.to_string()
        || target.expected_applied_revision != c.applied.load(Ordering::Acquire)
        || target.revision <= c.prepared.load(Ordering::Acquire)
    {
        return Err("REVISION_CONFLICT".into());
    }
    let projection = target.eq_editing.is_some();
    let original_preset =
        serde_json::to_value(&target.preset).map_err(|_| "INVALID_SOUND_REQUEST")?;
    let experimental = target.eq_editing.as_ref().is_some_and(|e| e.experimental);
    if let Some(edit) = target.eq_editing.as_ref() {
        if edit.schema != "no5.eq-editing/1" || (experimental && target.preview_lease.is_none()) {
            return Err("INVALID_EQ_EDITING_LEASE".into());
        }
        let mut previous = target.preset.clone();
        if let Some(id) = edit.edited_point_id.as_deref() {
            let old = edit
                .previous_point
                .as_ref()
                .filter(|p| p.id == id)
                .ok_or("INVALID_EQ_POINT_INTENT")?;
            let point = previous
                .eq_points
                .iter_mut()
                .find(|p| p.id == id)
                .ok_or("UNKNOWN_EQ_POINT")?;
            *point = old.clone();
        } else if edit.previous_point.is_some() {
            return Err("INVALID_EQ_POINT_INTENT".into());
        }
        target.preset = project_eq_budget(
            &previous,
            &target.preset,
            edit.edited_point_id.as_deref(),
            experimental,
        )?;
    }
    let normal_preset = if experimental {
        Some(project_eq_budget(
            &target.preset,
            &target.preset,
            None,
            false,
        )?)
    } else {
        None
    };
    let projected_preset =
        serde_json::to_value(&target.preset).map_err(|_| "INVALID_SOUND_REQUEST")?;
    let (mut prepared, headroom_db) = prepare_eq_budget(&target, &models.routing, experimental)?;
    prepared.generation = generation;
    prepared.valid_until_ms = received + target.valid_for_ms;
    let lease = if let Some(request) = target.preview_lease.as_ref() {
        if !valid_lease_id(&request.id) || !(1..=5000).contains(&request.valid_for_ms) {
            return Err("INVALID_PREVIEW_LEASE".into());
        }
        // Establish the baseline and deadline atomically with the first target;
        // it is never deferred to a later keepalive call.
        let fallback_target = Target {
            preset: request.fallback_preset.clone(),
            calibration: request.fallback_calibration.clone(),
            preview_lease: None,
            eq_editing: None,
            ..target.clone_without_lease()
        };
        let (mut fallback, reserve) = prepare(&fallback_target, &models.routing)?;
        fallback.generation = generation;
        Some(Lease {
            id: request.id.clone(),
            generation,
            owned_revision: target.revision,
            until_ms: received + request.valid_for_ms,
            fallback,
            record: Record {
                experimental: false,
                normal_preset: None,
                revision: 0,
                preset: request.fallback_preset.clone(),
                calibration: request.fallback_calibration.clone(),
                headroom_db: reserve,
                rollback: None,
                prepared: fallback,
            },
        })
    } else {
        None
    };
    if elapsed_ms(c) >= prepared.valid_until_ms
        || lease.as_ref().is_some_and(|l| elapsed_ms(c) >= l.until_ms)
    {
        return Err("TARGET_EXPIRED".into());
    }
    let expiry = if lease.is_none() {
        // Ending an active preview retains its confirmed fallback until this
        // replacement really takes over. A paused producer cannot strand an
        // unbounded old preview after the short replacement target expires.
        let baseline = models
            .lease
            .as_ref()
            .map(|l| l.record.clone())
            .or_else(|| {
                models
                    .records
                    .iter()
                    .find(|r| r.revision == c.applied.load(Ordering::Acquire))
                    .cloned()
            })
            .ok_or("BASELINE_UNAVAILABLE")?;
        Some(TargetExpiry {
            generation,
            owned_revision: target.revision,
            until_ms: prepared.valid_until_ms,
            fallback: baseline.prepared,
            record: baseline,
        })
    } else {
        None
    };
    if !c.active.load(Ordering::Acquire)
        || c.generation.load(Ordering::Acquire) != generation
        || c.applied.load(Ordering::Acquire) != target.expected_applied_revision
    {
        return Err("REVISION_CONFLICT".into());
    }
    // Keep one of the 64 ring slots reserved for an autonomous expiry rollback.
    if c.targets_tx.len() >= 63 {
        return Err("TARGET_RING_FULL".into());
    }
    refresh_rollback(c, &mut models);
    history_room(&mut models, c.applied.load(Ordering::Acquire), 255)?;
    models.records.push_back(Record {
        experimental,
        normal_preset: normal_preset.clone(),
        revision: target.revision,
        preset: target.preset,
        calibration: target.calibration,
        headroom_db,
        rollback: None,
        prepared,
    });
    if c.targets_tx.try_send(prepared).is_err() {
        models.records.pop_back();
        return Err("TARGET_RING_FULL".into());
    }
    models.target_expiry = expiry;
    models.lease = lease; // Explicit null atomically ends the previous lease.
    c.prepared.store(target.revision, Ordering::Release);
    if projection {
        Ok(
            json!({"status":"PREPARED","revision":target.revision,"headroomDb":headroom_db,
            "preset":projected_preset,"normalPreset":normal_preset,"experimental":experimental,"constrained":original_preset!=projected_preset}),
        )
    } else {
        Ok(json!({"status":"PREPARED","revision":target.revision,"headroomDb":headroom_db}))
    }
}
impl Target {
    fn clone_without_lease(&self) -> Self {
        Self {
            expected_config_generation: self.expected_config_generation.clone(),
            expected_source_generation: self.expected_source_generation.clone(),
            expected_applied_revision: self.expected_applied_revision,
            revision: self.revision,
            preset: self.preset.clone(),
            calibration: self.calibration.clone(),
            transition_ms: self.transition_ms,
            valid_for_ms: self.valid_for_ms,
            preview_lease: None,
            eq_editing: None,
        }
    }
}
pub fn set_sound_lease(value: Value) -> Result<Value, String> {
    set_sound_lease_for(control(), value)
}
fn set_sound_lease_for(c: &Control, value: Value) -> Result<Value, String> {
    let received = elapsed_ms(c);
    let request: LeaseUpdate =
        serde_json::from_value(value).map_err(|_| "INVALID_LEASE_REQUEST")?;
    if !valid_lease_id(&request.id) || request.valid_for_ms > 5000 {
        return Err("INVALID_PREVIEW_LEASE".into());
    }
    let mut models = c.models.lock();
    let generation = c.generation.load(Ordering::Acquire);
    let applied = c.applied.load(Ordering::Acquire);
    if !c.active.load(Ordering::Acquire)
        || request.expected_config_generation != models.generation
        || request.expected_source_generation != generation.to_string()
        || request.expected_applied_revision != applied
        || c.prepared.load(Ordering::Acquire) != applied
        || !models.lease.as_ref().is_some_and(|l| {
            l.id == request.id
                && l.generation == generation
                && l.owned_revision == applied
                && received < l.until_ms
        })
    {
        return Err("LEASE_CONFLICT".into());
    }
    let fallback_target = Target {
        expected_config_generation: request.expected_config_generation,
        expected_source_generation: request.expected_source_generation,
        expected_applied_revision: applied,
        revision: applied,
        transition_ms: 50,
        valid_for_ms: 5000,
        preview_lease: None,
        eq_editing: None,
        preset: request.fallback_preset,
        calibration: request.fallback_calibration,
    };
    let (mut fallback, reserve) = prepare(&fallback_target, &models.routing)?;
    fallback.generation = generation;
    // Even expensive validation must never revive an already expired lease.
    if !c.active.load(Ordering::Acquire)
        || c.generation.load(Ordering::Acquire) != generation
        || c.applied.load(Ordering::Acquire) != applied
        || models
            .lease
            .as_ref()
            .is_none_or(|l| elapsed_ms(c) >= l.until_ms)
        || (request.valid_for_ms > 0 && elapsed_ms(c) >= received + request.valid_for_ms)
    {
        return Err("LEASE_CONFLICT".into());
    }
    let experimental_active = models
        .records
        .iter()
        .find(|r| r.revision == applied)
        .is_some_and(|r| r.experimental);
    if experimental_active && (request.valid_for_ms == 0 || !request.experimental) {
        return Err("EXPERIMENTAL_PREVIEW_ONLY".into());
    }
    if request.valid_for_ms == 0 {
        models.lease = None;
        Ok(json!({"status":"RELEASED","id":request.id,"appliedRevision":applied}))
    } else {
        let lease = models.lease.as_mut().expect("checked lease");
        lease.until_ms = received + request.valid_for_ms;
        lease.fallback = fallback;
        lease.record = Record {
            experimental: false,
            normal_preset: None,
            revision: 0,
            preset: fallback_target.preset,
            calibration: fallback_target.calibration,
            headroom_db: reserve,
            rollback: None,
            prepared: fallback,
        };
        Ok(json!({"status":"RENEWED","id":request.id,"appliedRevision":applied}))
    }
}
pub fn set_spectrum(value: Value) -> Result<Value, String> {
    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Request {
        enabled: bool,
    }
    let request: Request = serde_json::from_value(value).map_err(|_| "INVALID_SPECTRUM_REQUEST")?;
    let c = control();
    c.spectrum_until.store(
        if request.enabled {
            c.clock.elapsed().as_millis() as u64 + 2000
        } else {
            0
        },
        Ordering::Release,
    );
    if !request.enabled {
        mark_gap();
        *c.spectrum.lock() = Value::Null;
    }
    Ok(json!({"enabled":request.enabled,"leaseMs":2000}))
}
pub fn get_spectrum() -> Value {
    control().spectrum.lock().clone()
}
pub fn mark_gap() {
    if let Some(c) = CONTROL.get() {
        c.analysis_epoch.fetch_add(1, Ordering::AcqRel);
    }
}

#[derive(Clone, Copy, Default)]
struct Memory {
    z1: f64,
    z2: f64,
}
impl Memory {
    #[inline]
    fn sample(&mut self, c: &Coeff, x: f64) -> f64 {
        let y = c.b0 * x + self.z1;
        self.z1 = c.b1 * x - c.a1 * y + self.z2;
        self.z2 = c.b2 * x - c.a2 * y;
        y
    }
}
fn delayed(ring: &[f64; DELAY], write: usize, delay: f64) -> f64 {
    let integer = delay.floor() as usize;
    let fraction = delay - integer as f64;
    let current = (write + DELAY - integer) % DELAY;
    ring[current] * (1.0 - fraction) + ring[(current + DELAY - 1) % DELAY] * fraction
}
pub struct Stages {
    control: Arc<Control>,
    generation: u64,
    current: Prepared,
    next: Prepared,
    memory: [[[Memory; 16]; 2]; 2],
    fade: usize,
    chunk_start_fade: usize,
    pending: Option<Prepared>,
    side: Box<[f64; BLOCK]>,
    side_ring: Box<[f64; DELAY]>,
    side_write: usize,
    band_memory: [Memory; 2],
    band: [Coeff; 2],
    align_ring: Box<[[f64; DELAY]; 6]>,
    align_write: usize,
    position: u64,
    sequence: u64,
}
impl Drop for Stages {
    fn drop(&mut self) {
        if self.control.generation.load(Ordering::Acquire) == self.generation {
            self.control.active.store(false, Ordering::Release);
            self.control.spectrum_until.store(0, Ordering::Release);
        }
    }
}
impl Stages {
    pub fn from_config(conf: &config::Configuration) -> Option<Self> {
        let c = control();
        let Some(routing) = conf
            .description
            .as_deref()
            .and_then(|d| d.strip_prefix(MARKER))
        else {
            c.active.store(false, Ordering::Release);
            return None;
        };
        // Only the root-owned provisioner may create this topology. This guard
        // also rejects a legacy EQ/immersive delay accidentally left in place.
        let valid_mixer = conf.pipeline.as_ref().is_some_and(|steps| {
            steps
                .iter()
                .filter(|s| matches!(s, config::PipelineStep::Mixer(_)))
                .count()
                == 1
                && steps.first().is_some_and(|step| match step {
                    config::PipelineStep::Mixer(step) if !step.is_bypassed() => conf
                        .mixers
                        .as_ref()
                        .and_then(|mixers| mixers.get(&step.name))
                        .is_some_and(|m| m.channels.r#in == 2 && m.channels.out == 6),
                    _ => false,
                })
        });
        if !valid_mixer
            || !["front_stereo_fullrange", "stereo_4_1", "immersive_4_1"].contains(&routing)
            || conf.devices.samplerate != RATE
            || conf.devices.chunksize != BLOCK
            || conf.devices.capture.channels() != 2
            || conf.devices.playback.channels() != 6
            || conf.filters.as_ref().is_some_and(|fs| {
                fs.keys()
                    .any(|n| n.starts_with("no5_eq_master_") || n == "rear_delay")
            })
        {
            c.active.store(false, Ordering::Release);
            return None;
        }
        // serde_json::Value sorts maps, including Camilla's HashMaps.
        let hash = Sha256::digest(serde_json::to_vec(&serde_json::to_value(conf).ok()?).ok()?);
        let mut models = c.models.lock();
        let generation = c.generation.fetch_add(1, Ordering::AcqRel) + 1;
        while c.targets_rx.try_recv().is_ok() {}
        let record = Record {
            experimental: false,
            normal_preset: None,
            revision: 0,
            preset: Preset {
                id: format!("no5-sound-{routing}"),
                name: conf.title.clone().unwrap_or_else(|| "Current".into()),
                routing_template: routing.into(),
                eq_points: vec![],
                eq_enabled: false,
                reserve_db: 12.0,
                room: Room {
                    amount: 0.0,
                    size: 0.5,
                },
            },
            calibration: Calibration {
                delays_ms: [0.0; 6],
                gains_db: [0.0; 6],
            },
            headroom_db: 0.0,
            rollback: None,
            prepared: Prepared {
                generation,
                ..Prepared::default()
            },
        };
        *models = Models {
            generation: format!("{hash:x}"),
            routing: routing.into(),
            records: VecDeque::from([record]),
            lease: None,
            target_expiry: None,
            last_rollback: None,
        };
        c.started_revision.store(0, Ordering::Release);
        c.rollback_revision.store(0, Ordering::Release);
        c.applied.store(0, Ordering::Release);
        c.prepared.store(0, Ordering::Release);
        c.active.store(true, Ordering::Release);
        *c.spectrum.lock() = Value::Null;
        Some(Self::new(c.clone(), generation))
    }
    fn new(control: Arc<Control>, generation: u64) -> Self {
        Self {
            control,
            generation,
            current: Prepared::default(),
            next: Prepared::default(),
            memory: [[[Memory::default(); 16]; 2]; 2],
            fade: FADE,
            chunk_start_fade: FADE,
            pending: None,
            side: Box::new([0.0; BLOCK]),
            side_ring: Box::new([0.0; DELAY]),
            side_write: 0,
            band_memory: [Memory::default(); 2],
            band: [
                Coeff::from_config(
                    RATE,
                    config::BiquadParameters::Highpass {
                        freq: 150.0,
                        q: 0.7071067811865476,
                    },
                ),
                Coeff::from_config(
                    RATE,
                    config::BiquadParameters::Lowpass {
                        freq: 7000.0,
                        q: 0.7071067811865476,
                    },
                ),
            ],
            align_ring: Box::new([[0.0; DELAY]; 6]),
            align_write: 0,
            position: 0,
            sequence: 0,
        }
    }
    pub fn invalidate(&mut self) {
        // A foreign configuration patch is applied by Camilla, but cannot be
        // mistaken for our old approved generation. Keep its audio untouched;
        // refuse further sound editing until an approved profile is reloaded.
        self.control.active.store(false, Ordering::Release);
        self.control.spectrum_until.store(0, Ordering::Release);
        self.pending = None;
        while self.control.targets_rx.try_recv().is_ok() {}
    }
    pub fn pre(&mut self, chunk: &mut AudioChunk) {
        let valid = self.control.active.load(Ordering::Acquire)
            && self.control.generation.load(Ordering::Acquire) == self.generation;
        let now = elapsed_ms(&self.control);
        while let Ok(value) = self.control.targets_rx.try_recv() {
            if valid && value.generation == self.generation {
                // An expired newest target also supersedes older pending work.
                self.pending = (now < value.valid_until_ms).then_some(value);
            }
        }
        if !valid || self.pending.is_some_and(|v| now >= v.valid_until_ms) {
            self.pending = None;
        }
        if valid && self.fade == FADE {
            if let Some(value) = self.pending.take() {
                self.control
                    .started_revision
                    .store(value.revision, Ordering::Release);
                self.next = value;
                self.memory[1] = self.memory[0];
                self.fade = 0;
            }
        }
        self.chunk_start_fade = self.fade;
        for i in 0..chunk.valid_frames {
            let mix = ((self.fade + i).min(FADE) as f64) / FADE as f64;
            for channel in 0..2 {
                let input = chunk.waveforms[channel][i];
                let mut old = input;
                let mut next = input;
                for filter in 0..16 {
                    old = self.memory[0][channel][filter].sample(&self.current.coeff[filter], old);
                    next = self.memory[1][channel][filter].sample(&self.next.coeff[filter], next);
                }
                chunk.waveforms[channel][i] =
                    old * self.current.headroom * (1.0 - mix) + next * self.next.headroom * mix;
            }
            let mut side = (chunk.waveforms[0][i] - chunk.waveforms[1][i]) * 0.5;
            for filter in 0..2 {
                side = self.band_memory[filter].sample(&self.band[filter], side);
            }
            self.side[i] = side;
        }
        self.sequence += 1;
        let now = self.control.clock.elapsed().as_millis() as u64;
        if now < self.control.spectrum_until.load(Ordering::Acquire) {
            // timestamp is the original captured chunk instant in the process
            // monotonic clock; HTTP/WS reads never refresh this observation.
            let timestamp = chunk
                .timestamp
                .saturating_duration_since(self.control.clock)
                .as_nanos() as u64;
            self.control.ring.push(
                chunk,
                self.position,
                self.generation,
                self.sequence,
                timestamp,
                self.control.analysis_epoch.load(Ordering::Acquire),
            );
        }
        self.position += chunk.valid_frames as u64;
    }
    pub fn room(&mut self, chunk: &mut AudioChunk) {
        const WEIGHTS: [f64; 4] = [0.46, 0.27, 0.17, 0.10];
        for i in 0..chunk.valid_frames {
            self.side_ring[self.side_write] = self.side[i];
            let mix = (self.chunk_start_fade + i).min(FADE) as f64 / FADE as f64;
            for side in 0..2 {
                let mut old = 0.0;
                let mut next = 0.0;
                for (tap, weight) in WEIGHTS.iter().enumerate() {
                    old += weight
                        * delayed(
                            &self.side_ring,
                            self.side_write,
                            self.current.room_delay[side][tap],
                        );
                    next += weight
                        * delayed(
                            &self.side_ring,
                            self.side_write,
                            self.next.room_delay[side][tap],
                        );
                }
                // A muted/unrouted Camilla channel is represented by an empty
                // waveform. Never materialize it merely to run a sound stage.
                if chunk.waveforms[4 + side].is_empty() {
                    continue;
                }
                let direct = chunk.waveforms[4 + side][i];
                let sign = if side == 0 { 1.0 } else { -1.0 };
                let old = (direct + sign * old * self.current.room_amount)
                    / (1.0 + self.current.room_amount);
                let next =
                    (direct + sign * next * self.next.room_amount) / (1.0 + self.next.room_amount);
                chunk.waveforms[4 + side][i] = old * (1.0 - mix) + next * mix;
            }
            self.side_write = (self.side_write + 1) % DELAY;
        }
    }
    pub fn post(&mut self, chunk: &mut AudioChunk) {
        for i in 0..chunk.valid_frames {
            let mix = (self.chunk_start_fade + i).min(FADE) as f64 / FADE as f64;
            for channel in 0..6 {
                if chunk.waveforms[channel].is_empty() {
                    continue;
                }
                self.align_ring[channel][self.align_write] = chunk.waveforms[channel][i];
                let old = delayed(
                    &self.align_ring[channel],
                    self.align_write,
                    self.current.delay[channel],
                ) * self.current.gain[channel];
                let next = delayed(
                    &self.align_ring[channel],
                    self.align_write,
                    self.next.delay[channel],
                ) * self.next.gain[channel];
                chunk.waveforms[channel][i] = old * (1.0 - mix) + next * mix;
            }
            self.align_write = (self.align_write + 1) % DELAY;
        }
        self.fade = (self.fade + chunk.valid_frames).min(FADE);
        if self.fade == FADE && self.current.revision != self.next.revision {
            self.current = self.next;
            self.memory[0] = self.memory[1];
            if self.control.active.load(Ordering::Acquire)
                && self.control.generation.load(Ordering::Acquire) == self.generation
            {
                self.control
                    .applied
                    .store(self.current.revision, Ordering::Release);
                if self.current.rollback {
                    self.control
                        .rollback_revision
                        .store(self.current.revision, Ordering::Release);
                }
            }
        }
    }
}

fn spectrum_worker(control: Arc<Control>) {
    let mut planner = RealFftPlanner::<f64>::new();
    let fft = planner.plan_fft_forward(FFT);
    let mut input = fft.make_input_vec();
    let mut output = fft.make_output_vec();
    let mut scratch = fft.make_scratch_vec();
    let window: Vec<f64> = (0..FFT)
        .map(|i| 0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / FFT as f64).cos())
        .collect();
    let window_sum: f64 = window.iter().sum();
    let mut history = [[0.0; FFT]; 2];
    let mut count = 0;
    let mut write = 0;
    let mut expected = 0;
    let mut generation = 0;
    let mut epoch = 0;
    let mut end_time = 0;
    let mut end_position = 0;
    let mut sequence = 0;
    let mut last_published = 0;
    let mut last_publish_time = Instant::now() - Duration::from_millis(100);
    loop {
        // Independent of producer, FFT subscription, samples and Gateway life.
        service_lease(&control);
        let active = (control.clock.elapsed().as_millis() as u64)
            < control.spectrum_until.load(Ordering::Acquire);
        if epoch != control.analysis_epoch.load(Ordering::Acquire) {
            count = 0;
        }
        let mut consumed = false;
        while control.ring.consume(|samples| {
            consumed = true;
            if samples.epoch != control.analysis_epoch.load(Ordering::Acquire)
                || samples.generation != control.generation.load(Ordering::Acquire)
            {
                count = 0;
                return;
            }
            if samples.position != expected
                || samples.generation != generation
                || samples.epoch != epoch
                || !active
            {
                count = 0;
                write = 0;
            }
            epoch = samples.epoch;
            generation = samples.generation;
            if active {
                for i in 0..samples.frames {
                    for channel in 0..2 {
                        history[channel][write] = samples.samples[channel][i];
                    }
                    write = (write + 1) % FFT;
                    count = (count + 1).min(FFT);
                }
            }
            expected = samples.position + samples.frames as u64;
            end_position = expected;
            end_time = samples.timestamp_ns + samples.frames as u64 * 1_000_000_000 / RATE as u64;
            sequence = samples.sequence;
        }) {}
        if active
            && count == FFT
            && end_position >= last_published + RATE as u64 / 10
            && last_publish_time.elapsed() >= Duration::from_millis(100)
        {
            let mut energy = vec![0.0; FFT / 2 + 1];
            let mut peaks = [0.0f64; 2];
            let mut clipped = 0usize;
            for channel in 0..2 {
                for i in 0..FFT {
                    let sample = history[channel][(write + i) % FFT];
                    input[i] = sample * window[i];
                    peaks[channel] = peaks[channel].max(sample.abs());
                    if sample.abs() >= 1.0 {
                        clipped += 1;
                    }
                }
                if fft
                    .process_with_scratch(&mut input, &mut output, &mut scratch)
                    .is_err()
                {
                    count = 0;
                    break;
                }
                for (bin, value) in output.iter().enumerate() {
                    energy[bin] += value.norm_sqr() * 2.0 / (window_sum * window_sum);
                }
            }
            let frequencies: Vec<f64> = (0..256)
                .map(|i| 20.0 * 1000.0f64.powf(i as f64 / 255.0))
                .collect();
            // A displayed log band covers its entire frequency interval;
            // selecting only the center FFT bin would hide narrow sine peaks.
            let half_ratio = 1000.0f64.powf(0.5 / 255.0);
            let bins: Vec<f64> = frequencies
                .iter()
                .map(|f| {
                    let low = (f / half_ratio * FFT as f64 / RATE as f64).round() as usize;
                    let high = (f * half_ratio * FFT as f64 / RATE as f64).round() as usize;
                    let peak = energy[low.min(FFT / 2)..=high.min(FFT / 2)]
                        .iter()
                        .copied()
                        .fold(0.0, f64::max);
                    (10.0 * peak.max(1e-16).log10()).max(-160.0)
                })
                .collect();
            *control.spectrum.lock() = json!({"schema":"no5.audio-spectrum/1","sequence":sequence,
                "sourceGeneration":generation.to_string(),"samplePosition":end_position - FFT as u64,
                "sampleTimeNs":end_time.saturating_sub(FFT as u64 * 1_000_000_000 / RATE as u64),
                "timebase":"CAPTURE_MONOTONIC_NS","sampleRateHz":RATE,"fftSize":FFT,"window":"HANN",
                "tap":"POST_EQ_PRE_ROOM","frequenciesHz":frequencies,"binsDbfs":bins,
                "peaksDbfs":peaks.map(|p| (20.0*p.max(1e-8).log10()).max(-160.0)),"overRangeSamples":clipped});
            last_published = end_position;
            last_publish_time = Instant::now();
        }
        if !active {
            count = 0;
            last_published = 0;
            *control.spectrum.lock() = Value::Null;
        }
        if !consumed {
            std::thread::sleep(Duration::from_millis(4));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        alloc::{GlobalAlloc, Layout, System},
        cell::Cell,
    };
    struct TrackingAllocator;
    thread_local! { static TRACK: Cell<bool> = const { Cell::new(false) }; static ALLOCS: Cell<usize> = const { Cell::new(0) }; }
    // Only the test's own thread is counted. Background FFT/control allocation
    // must not be confused with the processing hook's allocation budget.
    unsafe impl GlobalAlloc for TrackingAllocator {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let _ = TRACK.try_with(|track| {
                if track.get() {
                    let _ = ALLOCS.try_with(|n| n.set(n.get() + 1));
                }
            });
            unsafe { System.alloc(layout) }
        }
        unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
            unsafe { System.dealloc(ptr, layout) }
        }
        unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
            let _ = TRACK.try_with(|track| {
                if track.get() {
                    let _ = ALLOCS.try_with(|n| n.set(n.get() + 1));
                }
            });
            unsafe { System.realloc(ptr, layout, size) }
        }
    }
    #[global_allocator]
    static ALLOCATOR: TrackingAllocator = TrackingAllocator;
    fn target() -> Target {
        Target {
            expected_config_generation: "test".into(),
            expected_source_generation: "0".into(),
            expected_applied_revision: 0,
            revision: 1,
            transition_ms: 50,
            valid_for_ms: 5000,
            preview_lease: None,
            eq_editing: None,
            preset: Preset {
                id: "test".into(),
                name: "Test".into(),
                routing_template: "stereo_4_1".into(),
                eq_points: vec![],
                eq_enabled: false,
                reserve_db: 12.0,
                room: Room {
                    amount: 0.0,
                    size: 0.5,
                },
            },
            calibration: Calibration {
                delays_ms: [0.0; 6],
                gains_db: [0.0; 6],
            },
        }
    }
    #[test]
    fn cached_response_is_bit_identical_to_dense_oracle() {
        let mut cache = eq_core::ResponseCache::new();
        let mut seed = 17u64;
        let mut coeff = [IDENTITY; 16];
        for n in 0..48 {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            let slot = (seed as usize) % 16;
            let f = 20.0 * 1000.0f64.powf(((seed >> 16) % 10000) as f64 / 10000.0);
            coeff[slot] = if n % 7 == 0 { IDENTITY } else {
                Coeff::from_config(RATE, config::BiquadParameters::Peaking(config::PeakingWidth::Q {
                    freq: f, q: 0.3 + ((seed >> 32) % 970) as f64 / 100.0,
                    gain: -12.0 + ((seed >> 40) % 3300) as f64 / 100.0,
                }))
            };
            let expected = eq_core::legacy_peak_response(&core_coeff(&coeff));
            assert_eq!(cache.peak(&core_coeff(&coeff)).to_bits(), expected.to_bits(), "changed slot {slot}");
            assert_eq!(cache.peak(&core_coeff(&coeff)).to_bits(), expected.to_bits(), "identical reuse");
        }
    }
    #[test]
    fn all_visible_bands_stop_before_neighbours_without_crossing() {
        for gain in [-12.0, 0.0, 3.0] { for other_gain in [-12.0, 0.0, 3.0] {
            let mut old = target().preset; old.eq_enabled = true;
            old.eq_points = vec![
                Point { id: "a".into(), frequency_hz: 500.0, gain_db: gain, q: 5.0, width_locked: false },
                Point { id: "b".into(), frequency_hz: 1000.0, gain_db: other_gain, q: 5.0, width_locked: false },
            ];
            let mut request = old.clone(); request.eq_points[0].frequency_hz = 19000.0;
            let result = project_eq_budget(&old, &request, Some("a"), false).unwrap();
            assert!(visible_band(&result.eq_points[0]).1 <= visible_band(&old.eq_points[1]).0 + 1e-9);
            assert_eq!(result.eq_points[0].gain_db.to_bits(), gain.to_bits());
            assert_eq!(serde_json::to_value(&result.eq_points[1]).unwrap(), serde_json::to_value(&old.eq_points[1]).unwrap());
            request = old.clone(); request.eq_points[0].q = 0.3;
            let result = project_eq_budget(&old, &request, Some("a"), false).unwrap();
            assert_eq!(result.eq_points[0].frequency_hz.to_bits(), 500.0f64.to_bits());
            assert_eq!(result.eq_points[0].gain_db.to_bits(), gain.to_bits());
        }}
    }
    #[test]
    fn legacy_cut_overlap_is_lossless_and_only_non_worsening_edits_pass() {
        let mut old = target().preset;
        old.eq_points = vec![
            Point { id: "a".into(), frequency_hz: 900.0, gain_db: 0.0, q: 2.0, width_locked: false },
            Point { id: "b".into(), frequency_hz: 1000.0, gain_db: -4.0, q: 2.0, width_locked: false },
        ];
        assert!(visible_edit_compatible(&old, &old));
        let mut request = old.clone(); request.eq_points[0].frequency_hz = 700.0;
        assert!(visible_edit_compatible(&old, &request));
        request.eq_points[0].frequency_hz = 980.0;
        assert!(!visible_edit_compatible(&old, &request));
        request.eq_points[0].frequency_hz = 5000.0;
        assert!(!visible_edit_compatible(&old, &request));
    }
    #[test]
    fn cached_projection_preserves_exact_canonical_results() {
        struct Reset;
        impl Drop for Reset { fn drop(&mut self) { LEGACY_PEAK.set(false); } }
        let _reset = Reset;
        for count in [1usize, 8, 16] {
            for reserve in [6.0, 12.0, 18.0] {
                for experimental in [false, true] {
                    let mut p = target().preset;
                    p.eq_enabled = true; p.reserve_db = reserve;
                    p.eq_points = (0..count).map(|i| Point {
                        id: format!("p{i}"), frequency_hz: if count == 1 { 1000.0 } else { 50.0 * 320.0f64.powf(i as f64 / (count - 1) as f64) },
                        gain_db: 1.0, q: 8.0, width_locked: false,
                    }).collect();
                    let mut requested = p.clone(); requested.eq_points[count / 2].gain_db = 21.0;
                    let id = requested.eq_points[count / 2].id.clone();
                    LEGACY_PEAK.set(false);
                    let actual = project_eq_budget(&p, &requested, Some(&id), experimental).unwrap();
                    LEGACY_PEAK.set(true);
                    let expected = project_eq_budget(&p, &requested, Some(&id), experimental).unwrap();
                    assert_eq!(serde_json::to_value(actual).unwrap(), serde_json::to_value(expected).unwrap());
                }
            }
        }
        LEGACY_PEAK.set(false);
    }
    #[test]
    fn projection_cache_keys_every_input_and_never_caches_admission() {
        let mut base = target().preset; base.eq_enabled = true;
        base.eq_points = vec![Point { id: "p".into(), frequency_hz: 1000.0, gain_db: 1.0, q: 2.0, width_locked: false }];
        for variant in 0..10 {
            let mut previous = base.clone(); let mut requested = base.clone();
            requested.eq_points[0].gain_db = 21.0;
            let experimental = variant == 1;
            match variant {
                2 => requested.eq_points[0].frequency_hz = 1500.0,
                3 => requested.eq_points[0].q = 3.0,
                4 => previous.eq_points[0].gain_db = 2.0,
                5 => { previous.reserve_db = 6.0; requested.reserve_db = 6.0; },
                6 => requested.eq_enabled = false,
                7 => requested.name = "Another preset".into(),
                8 => requested.eq_points[0].width_locked = true,
                9 => requested.room.size = 0.7,
                _ => (),
            }
            let expected = project_eq_budget_uncached(&previous, &requested, Some("p"), experimental).map(|p|serde_json::to_value(p).unwrap());
            for _ in 0..2 {
                assert_eq!(project_eq_budget(&previous, &requested, Some("p"), experimental).map(|p|serde_json::to_value(p).unwrap()), expected);
            }
        }
    }
    #[test]
    fn eq_budget_reserve_and_experimental_individual_limits() {
        for reserve in [6.0, 12.0, 18.0] {
            for experimental in [false, true] {
                let mut t = target();
                t.preset.eq_enabled = true;
                t.preset.reserve_db = reserve;
                t.preset.eq_points = vec![Point {
                    id: "p".into(),
                    frequency_hz: 1000.0,
                    gain_db: 0.0,
                    q: 1.0,
                    width_locked: false,
                }];
                let previous = t.preset.clone();
                let max = reserve + if experimental { 3.0 } else { 0.0 };
                t.preset.eq_points[0].gain_db = (max + 1.0_f64).min(21.0);
                let projected =
                    project_eq_budget(&previous, &t.preset, Some("p"), experimental).unwrap();
                assert!((projected.eq_points[0].gain_db - max).abs() < 1e-6);
                assert_eq!(
                    projected.eq_points[0].frequency_hz.to_bits(),
                    1000.0_f64.to_bits()
                );
                assert!(projected.eq_enabled);
                t.preset = projected;
                assert!(prepare_eq_budget(&t, "stereo_4_1", experimental).is_ok());
                if experimental {
                    assert!(prepare(&t, "stereo_4_1").is_err());
                }
            }
        }
    }
    #[test]
    fn eq_budget_reduction_scales_only_boosts_and_keeps_all_geometry() {
        let mut t = target();
        t.preset.eq_enabled = true;
        t.preset.reserve_db = 18.0;
        t.preset.eq_points = vec![
            Point {
                id: "a".into(),
                frequency_hz: 100.0,
                gain_db: 10.0,
                q: 3.0,
                width_locked: false,
            },
            Point {
                id: "b".into(),
                frequency_hz: 1000.0,
                gain_db: 8.0,
                q: 3.0,
                width_locked: false,
            },
            Point {
                id: "c".into(),
                frequency_hz: 4000.0,
                gain_db: -5.0,
                q: 1.0,
                width_locked: true,
            },
        ];
        let before = t.preset.clone();
        t.preset.reserve_db = 6.0;
        let p = project_eq_budget(&before, &t.preset, None, false).unwrap();
        assert!((p.eq_points[0].gain_db / 10.0 - p.eq_points[1].gain_db / 8.0).abs() < 1e-12);
        assert_eq!(p.eq_points[2].gain_db, -5.0);
        assert_eq!(p.reserve_db, 6.0);
        for (a, b) in p.eq_points.iter().zip(&before.eq_points) {
            assert_eq!(a.frequency_hz.to_bits(), b.frequency_hz.to_bits());
            assert_eq!(a.q.to_bits(), b.q.to_bits());
            assert_eq!(a.width_locked, b.width_locked);
        }
        t.preset = p;
        assert!(prepare(&t, "stereo_4_1").is_ok());
    }
    #[test]
    fn eq_budget_width_clamp_is_bit_exact_and_leaves_other_points_unchanged() {
        let mut t = target();
        t.preset.eq_enabled = true;
        t.preset.eq_points = vec![
            Point {
                id: "a".into(),
                frequency_hz: 5477.162391840849,
                gain_db: 4.097563978750259,
                q: 2.0,
                width_locked: false,
            },
            Point {
                id: "b".into(),
                frequency_hz: 13179.202930309702,
                gain_db: 8.0,
                q: 0.8605087271267844,
                width_locked: false,
            },
        ];
        let previous = t.preset.clone();
        t.preset.eq_points[0].q = 0.5;
        let p = project_eq_budget(&previous, &t.preset, Some("a"), false).unwrap();
        assert_eq!(
            p.eq_points[0].frequency_hz.to_bits(),
            previous.eq_points[0].frequency_hz.to_bits()
        );
        assert_eq!(
            p.eq_points[0].gain_db.to_bits(),
            previous.eq_points[0].gain_db.to_bits()
        );
        assert_eq!(
            serde_json::to_value(&p.eq_points[1]).unwrap(),
            serde_json::to_value(&previous.eq_points[1]).unwrap()
        );
        t.preset = p;
        assert!(prepare(&t, "stereo_4_1").is_ok());
    }
    #[test]
    fn explicit_enabled_reserve_is_constant_and_disabled_curve_is_lossless() {
        let mut t = target();
        let (p, reserve) = prepare(&t, "stereo_4_1").unwrap();
        assert_eq!(reserve, 0.0);
        assert_eq!(p.headroom, 1.0);
        t.preset.eq_enabled = true;
        for gain in [0.0, -6.0, 6.0, 12.0] {
            t.preset.eq_points = vec![Point {
                id: "a".into(),
                frequency_hz: 1000.0,
                gain_db: gain,
                q: 1.0,
                width_locked: false,
            }];
            let (p, reserve) = prepare(&t, "stereo_4_1").unwrap();
            assert_eq!(reserve, 12.0);
            assert_eq!(p.headroom, 10.0f64.powf(-12.0 / 20.0));
            assert!(eq_core::peak_response(&core_coeff(&p.coeff)) <= 12.0 + 1e-8);
        }
        t.preset.eq_enabled = false;
        let original = serde_json::to_string(&t.preset.eq_points).unwrap();
        let (p, reserve) = prepare(&t, "stereo_4_1").unwrap();
        assert_eq!(reserve, 0.0);
        assert_eq!(p.headroom, 1.0);
        assert_eq!(eq_core::response(&core_coeff(&p.coeff), 1000.0), 0.0);
        assert_eq!(
            serde_json::to_string(&t.preset.eq_points).unwrap(),
            original
        );
    }
    #[test]
    fn positive_visible_bands_may_touch_but_must_not_overlap_when_enabled() {
        let mut t = target();
        t.preset.eq_enabled = true;
        t.preset.reserve_db = 18.0;
        let first = Point {
            id: "a".into(),
            frequency_hz: 1000.0,
            gain_db: 6.0,
            q: 1.0,
            width_locked: false,
        };
        let (low, high) = visible_band(&first);
        let mut second = first.clone();
        second.id = "b".into();
        second.frequency_hz *= high / low;
        t.preset.eq_points = vec![first, second];
        assert!(
            prepare(&t, "stereo_4_1").is_ok(),
            "touching edges must be accepted"
        );
        t.preset.eq_points[1].frequency_hz *= 0.99;
        assert_eq!(
            prepare(&t, "stereo_4_1").err().unwrap(),
            "EQ_POSITIVE_BANDS_OVERLAP"
        );
        t.preset.eq_enabled = false;
        assert!(
            prepare(&t, "stereo_4_1").is_ok(),
            "disabled historical curves stay lossless"
        );
        t.preset.eq_enabled = true;
        t.preset.eq_points[1].gain_db = -6.0;
        assert!(prepare(&t, "stereo_4_1").is_ok(), "cut bands may overlap");
    }
    #[test]
    fn combined_skirts_must_fit_the_selected_reserve_and_invalid_reserves_are_rejected() {
        let mut t = target();
        t.preset.eq_enabled = true;
        t.preset.reserve_db = 12.0;
        let first = Point {
            id: "a".into(),
            frequency_hz: 1000.0,
            gain_db: 12.0,
            q: 1.0,
            width_locked: false,
        };
        let (low, high) = visible_band(&first);
        let mut second = first.clone();
        second.id = "b".into();
        second.frequency_hz *= high / low;
        t.preset.eq_points = vec![first, second];
        assert_eq!(
            prepare(&t, "stereo_4_1").err().unwrap(),
            "EQ_COMBINED_GAIN_LIMIT"
        );
        t.preset.reserve_db = 18.0;
        assert!(prepare(&t, "stereo_4_1").is_ok());
        t.preset.eq_enabled = false;
        for invalid in [5.999, 18.001, f64::NAN, f64::INFINITY] {
            t.preset.reserve_db = invalid;
            assert_eq!(
                prepare(&t, "stereo_4_1").err().unwrap(),
                "UNSUPPORTED_OR_INVALID_SOUND_TARGET"
            );
        }
    }
    #[test]
    fn paired_sub_and_front_only_are_enforced() {
        let mut t = target();
        t.calibration.delays_ms[2] = 1.0;
        assert!(prepare(&t, "stereo_4_1").is_err());
        t.calibration.delays_ms[3] = 1.0;
        assert!(prepare(&t, "stereo_4_1").is_ok());
        t.preset.routing_template = "front_stereo_fullrange".into();
        t.preset.room.amount = 0.5;
        assert!(prepare(&t, "front_stereo_fullrange").is_err());
    }
    #[test]
    fn early_reflections_and_fractional_delays() {
        let mut t = target();
        t.preset.room.amount = 1.0;
        t.preset.room.size = 1.0;
        let (p, _) = prepare(&t, "stereo_4_1").unwrap();
        assert_eq!(p.room_delay[0][0], 1200.0);
        assert!(p.room_delay.iter().flatten().all(|d| *d <= 2880.0));
        let mut ring = [0.0; DELAY];
        ring[100] = 1.0;
        assert_eq!(delayed(&ring, 110, 10.0), 1.0);
        assert_eq!(delayed(&ring, 110, 9.5), 0.5);
    }
    #[test]
    fn ring_is_bounded_and_preserves_sample_reference() {
        let ring = SampleRing::new();
        let chunk = AudioChunk::new(
            vec![vec![0.25; BLOCK], vec![-0.25; BLOCK]],
            0.25,
            -0.25,
            BLOCK,
            BLOCK,
        );
        for i in 0..65 {
            ring.push(&chunk, i * BLOCK as u64, 7, i, 123, 9);
        }
        let mut count = 0;
        while ring.consume(|s| {
            assert_eq!(s.generation, 7);
            assert_eq!(s.timestamp_ns, 123);
            assert_eq!(s.samples[0][0], -s.samples[1][0]);
            count += 1;
        }) {}
        assert_eq!(count, 64);
    }
    #[test]
    fn processing_hooks_do_not_allocate_even_during_transition_and_measurement() {
        let c = control().clone();
        let generation = c.generation.load(Ordering::Acquire);
        c.active.store(true, Ordering::Release);
        let mut stages = Stages::new(c.clone(), generation);
        let mut t = target();
        t.preset.room.amount = 1.0;
        t.preset.eq_enabled = true;
        t.preset.eq_points.push(Point {
            id: "tone".into(),
            frequency_hz: 1000.0,
            gain_db: 9.0,
            q: 10.0,
            width_locked: true,
        });
        t.calibration.delays_ms = [20.0, 0.0, 50.0, 50.0, 15.0, 30.0];
        let (mut prepared, _) = prepare(&t, "stereo_4_1").unwrap();
        prepared.generation = generation;
        c.targets_tx
            .try_send(prepared)
            .unwrap_or_else(|_| panic!("test target admission"));
        c.spectrum_until.store(
            c.clock.elapsed().as_millis() as u64 + 2000,
            Ordering::Release,
        );
        let mut chunk = AudioChunk::new(vec![vec![0.125; BLOCK]; 6], 0.125, 0.125, BLOCK, BLOCK);
        ALLOCS.with(|n| n.set(0));
        TRACK.with(|track| track.set(true));
        for _ in 0..32 {
            stages.pre(&mut chunk);
            stages.room(&mut chunk);
            stages.post(&mut chunk);
        }
        TRACK.with(|track| track.set(false));
        let count = ALLOCS.with(Cell::get);
        c.spectrum_until.store(0, Ordering::Release);
        assert_eq!(count, 0, "allocation occurred in a processing hook");
        assert_eq!(stages.current.revision, 1);
    }
    #[test]
    fn old_generation_and_invalidated_targets_never_take_effect() {
        let c = control().clone();
        let generation = c.generation.load(Ordering::Acquire);
        c.active.store(true, Ordering::Release);
        let mut stages = Stages::new(c.clone(), generation);
        let (mut prepared, _) = prepare(&target(), "stereo_4_1").unwrap();
        prepared.generation = generation.wrapping_sub(1);
        c.targets_tx
            .try_send(prepared)
            .unwrap_or_else(|_| panic!("test target admission"));
        let mut chunk = AudioChunk::new(vec![vec![0.125; BLOCK]; 6], 0.125, 0.125, BLOCK, BLOCK);
        stages.pre(&mut chunk);
        stages.room(&mut chunk);
        stages.post(&mut chunk);
        assert_eq!(stages.current.revision, 0);
        assert!(stages.pending.is_none());
        stages.invalidate();
        prepared.generation = generation;
        c.targets_tx
            .try_send(prepared)
            .unwrap_or_else(|_| panic!("test target admission"));
        stages.pre(&mut chunk);
        stages.room(&mut chunk);
        stages.post(&mut chunk);
        assert_eq!(stages.current.revision, 0);
        assert_eq!(stages.next.revision, 0);
    }
    fn lease_fixture(worker: bool) -> (Arc<Control>, Stages) {
        let c = new_control(worker);
        let generation = c.generation.load(Ordering::Acquire);
        let t = target();
        let record = Record {
            experimental: false,
            normal_preset: None,
            revision: 0,
            preset: t.preset,
            calibration: t.calibration,
            headroom_db: 0.0,
            rollback: None,
            prepared: Prepared {
                generation,
                ..Prepared::default()
            },
        };
        *c.models.lock() = Models {
            generation: "test".into(),
            routing: "stereo_4_1".into(),
            records: VecDeque::from([record]),
            lease: None,
            target_expiry: None,
            last_rollback: None,
        };
        c.active.store(true, Ordering::Release);
        (c.clone(), Stages::new(c, generation))
    }
    fn lease_target(c: &Control, revision: u64, leased: bool) -> Value {
        let state = get_sound_for(c);
        let mut preset = state["preset"].clone();
        preset["eqEnabled"] = json!(true);
        preset["eqPoints"] = json!([{"id":"tone","frequencyHz":1000.0,"gainDb":-12.0,
            "q":1.0,"widthLocked":false}]);
        json!({"expectedConfigGeneration":state["configGeneration"],
            "expectedSourceGeneration":state["sourceGeneration"],
            "expectedAppliedRevision":state["appliedRevision"],"revision":revision,
            "preset":preset,"calibration":state["calibration"],"transitionMs":50,"validForMs":5000,
            "previewLease":if leased { json!({"id":"preview-one","validForMs":5000,
                "fallbackPreset":state["preset"],"fallbackCalibration":state["calibration"]}) } else { Value::Null }})
    }
    fn lease_update(c: &Control, valid_for_ms: u64) -> Value {
        let state = get_sound_for(c);
        json!({"id":"preview-one","expectedConfigGeneration":state["configGeneration"],
            "expectedSourceGeneration":state["sourceGeneration"],"expectedAppliedRevision":state["appliedRevision"],
            "validForMs":valid_for_ms,"fallbackPreset":state["previewLease"]["fallbackPreset"],
            "fallbackCalibration":state["previewLease"]["fallbackCalibration"]})
    }
    fn process_blocks(stages: &mut Stages, blocks: usize, unchanged: bool) {
        let mut chunk = AudioChunk::new(vec![vec![0.125; BLOCK]; 6], 0.125, 0.125, BLOCK, BLOCK);
        for _ in 0..blocks {
            for channel in chunk.waveforms.iter_mut() {
                channel.fill(0.125);
            }
            stages.pre(&mut chunk);
            stages.room(&mut chunk);
            stages.post(&mut chunk);
            if unchanged {
                assert!(chunk.waveforms.iter().flatten().all(|v| *v == 0.125));
            }
        }
    }
    fn eq_edit_request(experimental: bool) -> Value {
        json!({"schema":"no5.eq-editing/1","experimental":experimental,"previousPoint":null,"editedPointId":null})
    }
    #[test]
    fn eq_edit_projection_is_atomic_and_reports_the_actual_target() {
        let (c, mut stages) = lease_fixture(false);
        let mut req = lease_target(&c, 1, true);
        req["preset"]["reserveDb"] = json!(6.0);
        req["preset"]["eqPoints"][0]["gainDb"] = json!(12.0);
        req["eqEditing"] = eq_edit_request(false);
        let receipt = set_sound_for(&c, req).unwrap();
        assert!(receipt["constrained"].as_bool().unwrap());
        assert!(receipt["preset"]["eqPoints"][0]["gainDb"].as_f64().unwrap() <= 6.0);
        assert_eq!(get_sound_for(&c)["preset"]["eqEnabled"], false);
        process_blocks(&mut stages, 3, false);
        let state = get_sound_for(&c);
        assert_eq!(state["preset"], receipt["preset"]);
        assert_eq!(state["preset"]["eqEnabled"], true);
        assert_eq!(state["eqEditing"]["experimental"], false);
    }
    #[test]
    fn experimental_requires_a_lease_and_cannot_be_released_or_implicitly_renewed() {
        let (c, mut stages) = lease_fixture(false);
        let mut req = lease_target(&c, 1, false);
        req["preset"]["reserveDb"] = json!(6.0);
        req["preset"]["eqPoints"][0]["gainDb"] = json!(9.0);
        req["eqEditing"] = eq_edit_request(true);
        assert_eq!(
            set_sound_for(&c, req.clone()),
            Err("INVALID_EQ_EDITING_LEASE".into())
        );
        req["previewLease"] = lease_target(&c, 1, true)["previewLease"].clone();
        set_sound_for(&c, req).unwrap();
        process_blocks(&mut stages, 3, false);
        assert_eq!(get_sound_for(&c)["eqEditing"]["experimental"], true);
        assert_eq!(
            set_sound_lease_for(&c, lease_update(&c, 0)),
            Err("EXPERIMENTAL_PREVIEW_ONLY".into())
        );
        assert_eq!(
            set_sound_lease_for(&c, lease_update(&c, 5000)),
            Err("EXPERIMENTAL_PREVIEW_ONLY".into())
        );
        let mut renewal = lease_update(&c, 5000);
        renewal["experimental"] = json!(true);
        assert!(set_sound_lease_for(&c, renewal).is_ok());
        let previous = get_sound_for(&c);
        let mut normal = lease_target(&c, 2, true);
        normal["preset"] = previous["preset"].clone();
        normal["previewLease"]["fallbackPreset"] =
            previous["previewLease"]["fallbackPreset"].clone();
        normal["eqEditing"] = eq_edit_request(false);
        set_sound_for(&c, normal).unwrap();
        process_blocks(&mut stages, 3, false);
        assert_eq!(get_sound_for(&c)["eqEditing"]["experimental"], false);
        assert!(set_sound_lease_for(&c, lease_update(&c, 0)).is_ok());
    }
    #[test]
    fn experimental_expires_to_a_normal_fallback_without_gateway_activity() {
        let (c, mut stages) = lease_fixture(true);
        let mut req = lease_target(&c, 1, true);
        req["preset"]["reserveDb"] = json!(6.0);
        req["preset"]["eqPoints"][0]["gainDb"] = json!(9.0);
        req["eqEditing"] = eq_edit_request(true);
        req["previewLease"]["validForMs"] = json!(500);
        set_sound_for(&c, req).unwrap();
        process_blocks(&mut stages, 3, false);
        assert_eq!(get_sound_for(&c)["eqEditing"]["experimental"], true);
        std::thread::sleep(Duration::from_millis(550));
        process_blocks(&mut stages, 3, false); // The existing 50ms rollback crossfade is intentional.
        process_blocks(&mut stages, 3, true); // Once settled, the identity fallback is bit-exact.
        assert_eq!(get_sound_for(&c)["eqEditing"]["experimental"], false);
        assert_eq!(get_sound_for(&c)["preset"]["eqEnabled"], false);
    }
    #[test]
    fn required_source_validity_and_explicit_nullable_lease_are_enforced() {
        let (c, _stages) = lease_fixture(false);
        for missing in ["expectedSourceGeneration", "validForMs", "previewLease"] {
            let mut request = lease_target(&c, 1, true);
            request.as_object_mut().unwrap().remove(missing);
            assert_eq!(
                set_sound_for(&c, request),
                Err("INVALID_SOUND_REQUEST".into())
            );
        }
        for missing in ["eqEnabled", "reserveDb"] {
            let mut request = lease_target(&c, 1, true);
            request["preset"].as_object_mut().unwrap().remove(missing);
            assert_eq!(
                set_sound_for(&c, request),
                Err("INVALID_SOUND_REQUEST".into())
            );
        }
        for invalid in [0, 5001] {
            let mut request = lease_target(&c, 1, true);
            request["validForMs"] = json!(invalid);
            assert_eq!(
                set_sound_for(&c, request),
                Err("INVALID_TARGET_VALIDITY".into())
            );
        }
        let mut request = lease_target(&c, 1, true);
        request["expectedSourceGeneration"] = json!("foreign");
        assert_eq!(set_sound_for(&c, request), Err("REVISION_CONFLICT".into()));
        let mut request = lease_target(&c, 1, true);
        request["previewLease"]["fallbackCalibration"]["delaysMs"][2] = json!(1.0);
        assert_eq!(
            set_sound_for(&c, request),
            Err("SUB_OUTPUTS_MUST_MATCH".into())
        );
        assert!(c.models.lock().lease.is_none());
        assert!(c.targets_rx.is_empty());
    }
    #[test]
    fn lease_renew_release_and_baseline_update_require_full_ownership_cas() {
        let (c, mut stages) = lease_fixture(false);
        set_sound_for(&c, lease_target(&c, 1, true)).unwrap();
        assert_eq!(
            set_sound_lease_for(&c, lease_update(&c, 5000)),
            Err("LEASE_CONFLICT".into())
        );
        process_blocks(&mut stages, 3, false);
        assert_eq!(c.applied.load(Ordering::Acquire), 1);
        for key in [
            "id",
            "expectedConfigGeneration",
            "expectedSourceGeneration",
            "expectedAppliedRevision",
        ] {
            let mut request = lease_update(&c, 5000);
            request[key] = if key == "expectedAppliedRevision" {
                json!(0)
            } else {
                json!("foreign")
            };
            assert_eq!(
                set_sound_lease_for(&c, request),
                Err("LEASE_CONFLICT".into())
            );
        }
        let mut renewal = lease_update(&c, 5000);
        renewal["fallbackCalibration"]["gainsDb"][0] = json!(-3.0);
        assert_eq!(
            set_sound_lease_for(&c, renewal).unwrap(),
            json!({"status":"RENEWED","id":"preview-one","appliedRevision":1})
        );
        assert_eq!(
            get_sound_for(&c)["previewLease"]["fallbackCalibration"]["gainsDb"][0],
            -3.0
        );
        assert_eq!(
            set_sound_lease_for(&c, lease_update(&c, 0)).unwrap(),
            json!({"status":"RELEASED","id":"preview-one","appliedRevision":1})
        );
        assert_eq!(get_sound_for(&c)["previewLease"], Value::Null);
        assert_eq!(
            set_sound_lease_for(&c, lease_update(&c, 0)),
            Err("INVALID_LEASE_REQUEST".into())
        );
        service_lease(&c);
        assert!(c.targets_rx.is_empty());
    }
    #[test]
    fn stopped_producer_never_applies_expired_preview_after_five_seconds() {
        let (c, mut stages) = lease_fixture(true);
        let mut request = lease_target(&c, 1, true);
        request["validForMs"] = json!(200);
        set_sound_for(&c, request).unwrap();
        // No processing and no Gateway/WS requests during this real timeout.
        std::thread::sleep(Duration::from_millis(5100));
        assert_eq!(c.prepared.load(Ordering::Acquire), 2);
        assert_eq!(c.applied.load(Ordering::Acquire), 0);
        assert_eq!(get_sound_for(&c)["lastRollback"], Value::Null);
        process_blocks(&mut stages, 3, true);
        let state = get_sound_for(&c);
        assert_eq!(state["appliedRevision"], 2);
        assert_eq!(state["previewLease"], Value::Null);
        assert_eq!(
            state["lastRollback"],
            json!({"leaseId":"preview-one","fromRevision":1,"toRevision":2})
        );
        service_lease(&c);
        assert_eq!(
            c.prepared.load(Ordering::Acquire),
            2,
            "rollback must be queued once"
        );
    }
    #[test]
    fn gateway_loss_restores_baseline_without_gateway_or_fft_consumer() {
        let (c, mut stages) = lease_fixture(true);
        set_sound_for(&c, lease_target(&c, 1, true)).unwrap();
        process_blocks(&mut stages, 3, false);
        assert_eq!(c.applied.load(Ordering::Acquire), 1);
        std::thread::sleep(Duration::from_millis(5100));
        assert_eq!(c.prepared.load(Ordering::Acquire), 2);
        assert_eq!(c.applied.load(Ordering::Acquire), 1);
        // Prepared rollback is not yet a processed rollback observation.
        assert_eq!(get_sound_for(&c)["lastRollback"], Value::Null);
        process_blocks(&mut stages, 3, false);
        let state = get_sound_for(&c);
        assert_eq!(state["appliedRevision"], 2);
        assert_eq!(state["preset"]["eqPoints"], json!([]));
        assert_eq!(state["lastRollback"]["fromRevision"], 1);
        assert_eq!(stages.current.headroom, 1.0);
    }
    #[test]
    fn expired_null_target_resolves_pending_and_preserves_preview_baseline() {
        let (c, mut stages) = lease_fixture(false);
        set_sound_for(&c, lease_target(&c, 1, true)).unwrap();
        process_blocks(&mut stages, 3, false);
        let mut request = lease_target(&c, 2, false);
        request["validForMs"] = json!(200);
        set_sound_for(&c, request).unwrap();
        assert!(c.models.lock().lease.is_none());
        std::thread::sleep(Duration::from_millis(220));
        service_lease(&c);
        assert_eq!(c.prepared.load(Ordering::Acquire), 3);
        process_blocks(&mut stages, 3, false);
        let state = get_sound_for(&c);
        assert_eq!(state["appliedRevision"], 3);
        assert_eq!(state["preparedRevision"], 3);
        assert_eq!(
            state["preset"]["eqPoints"],
            json!([]),
            "old preview must not become permanent"
        );
        assert_eq!(state["lastRollback"], Value::Null);
        service_lease(&c);
        assert_eq!(c.prepared.load(Ordering::Acquire), 3);
    }
    #[test]
    fn expired_targets_pending_during_fade_and_foreign_sources_never_apply() {
        let (c, mut stages) = lease_fixture(false);
        let (mut prepared, _) = prepare(&target(), "stereo_4_1").unwrap();
        prepared.generation = stages.generation;
        prepared.valid_until_ms = 0;
        stages.pending = Some(prepared);
        process_blocks(&mut stages, 3, true);
        assert_eq!(stages.current.revision, 0);
        set_sound_for(&c, lease_target(&c, 1, true)).unwrap();
        c.models.lock().lease.as_mut().unwrap().until_ms = 0;
        c.generation.fetch_add(1, Ordering::AcqRel);
        service_lease(&c);
        assert!(c.models.lock().lease.is_none());
        assert_eq!(
            c.prepared.load(Ordering::Acquire),
            1,
            "foreign source must not receive fallback"
        );
        process_blocks(&mut stages, 3, true);
        assert_eq!(stages.current.revision, 0);
    }
    #[test]
    fn rollback_has_a_reserved_ring_slot_and_metadata_precedes_visibility() {
        let (c, _stages) = lease_fixture(false);
        set_sound_for(&c, lease_target(&c, 1, true)).unwrap();
        for _ in 1..63 {
            c.targets_tx.try_send(Prepared::default()).unwrap();
        }
        assert_eq!(
            set_sound_for(&c, lease_target(&c, 2, false)),
            Err("TARGET_RING_FULL".into())
        );
        c.models.lock().lease.as_mut().unwrap().until_ms = 0;
        service_lease(&c);
        assert_eq!(c.targets_rx.len(), 64);
        assert_eq!(c.prepared.load(Ordering::Acquire), 2);
        assert!(
            c.models
                .lock()
                .records
                .iter()
                .any(|r| r.revision == 2 && r.rollback.is_some())
        );
        service_lease(&c);
        assert_eq!(c.targets_rx.len(), 64);
    }
}

#[cfg(test)] fn core_coeff(c:&[Coeff;16])->[eq_core::Coeff;16]{c.map(|c|eq_core::Coeff{a1:c.a1,a2:c.a2,b0:c.b0,b1:c.b1,b2:c.b2})}
#[cfg(test)] mod shared_coefficient_parity {
 use super::*;
 #[test] fn exact_upstream_peaking_coefficients(){
 for frequency_hz in [20.0,38.0,500.0,5480.0,13180.0,20000.0]{for gain_db in [-12.0,-0.1,0.1,6.0,12.0,18.0,21.0]{for q in [0.3,1.0,2.0,10.0]{
 let p=Point{id:"p".into(),frequency_hz,gain_db,q,width_locked:false};let c=eq_core::peaking(&p);
 let old=Coeff::from_config(RATE,config::BiquadParameters::Peaking(config::PeakingWidth::Q{freq:frequency_hz,gain:gain_db,q}));
 assert_eq!(eq_core::coefficient_bits(&c),eq_core::coefficient_bits(&core_coeff(&[old;16])[0]));
 }}}
 }
}

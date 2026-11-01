// Shared No5 EQ safety kernel. SPDX-License-Identifier: MPL-2.0
// The UTF-8 bridge requires serde_json's pinned float_roundtrip feature:
// unedited binary64 values must survive request decoding without a one-ULP drift.
use parking_lot::Mutex;
use serde::{Deserialize,Serialize};
use std::sync::OnceLock;
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Point {
    pub id: String,
    pub frequency_hz: f64,
    pub gain_db: f64,
    pub q: f64,
    pub width_locked: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Room {
    pub amount: f64,
    pub size: f64,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Preset {
    pub id: String,
    pub name: String,
    pub routing_template: String,
    pub eq_points: Vec<Point>,
    pub eq_enabled: bool,
    pub reserve_db: f64,
    pub room: Room,
}
#[derive(Clone,Copy,Debug)]
pub struct Coeff {pub a1:f64,pub a2:f64,pub b0:f64,pub b1:f64,pub b2:f64}
pub const RATE:usize=48000;
pub const IDENTITY:Coeff=Coeff{a1:0.0,a2:0.0,b0:1.0,b1:0.0,b2:0.0};
// Literal CamillaDSP 4.1.3 PeakingWidth::Q arithmetic, including normalization order.
pub fn peaking(p:&Point)->Coeff {
 let omega=2.0*std::f64::consts::PI*p.frequency_hz/(RATE as f64);
 let sn=omega.sin();let cs=omega.cos();let ampl=10.0f64.powf(p.gain_db/40.0);let alpha=sn/(2.0*p.q);
 let b0=1.0+(alpha*ampl);let b1=-2.0*cs;let b2=1.0-(alpha*ampl);
 let a0=1.0+(alpha/ampl);let a1=-2.0*cs;let a2=1.0-(alpha/ampl);
 Coeff{a1:a1/a0,a2:a2/a0,b0:b0/a0,b1:b1/a0,b2:b2/a0}
}
#[derive(Clone,Copy)]
pub struct PreparedEq{pub coeff:[Coeff;16]}
// One bounded control-side cache; never used or locked by the audio hooks.
// Preserve the original frequency grid, complex arithmetic and multiplication
// order exactly. Only transfer ratios of unchanged coefficient slots are reused.
pub struct ResponseCache {
    z: Vec<num_complex::Complex64>,
    ratios: Vec<[num_complex::Complex64; 16]>,
    coeff: Option<[Coeff; 16]>,
    peak: f64,
    peak_index: usize,
}
pub fn coefficient_bits(c: &Coeff) -> [u64; 5] {
    [c.b0.to_bits(), c.b1.to_bits(), c.b2.to_bits(), c.a1.to_bits(), c.a2.to_bits()]
}
// Literal multiplication by (1,+0) is unchanged for finite nonzero components.
// Signed zero can take two steps to reach its fixed point. Non-finite values
// retain every original operation, including NaN propagation.
fn identity_product_run(mut value: num_complex::Complex64, count: usize) -> num_complex::Complex64 {
    let steps = if value.re.is_finite() && value.im.is_finite() {
        if value.re != 0.0 && value.im != 0.0 { 0 } else { count.min(2) }
    } else { count };
    for _ in 0..steps { value *= num_complex::Complex64::new(1.0, 0.0); }
    value
}
enum ProductStep { Slot(usize), IdentityRun(usize) }
impl ResponseCache {
    pub fn new() -> Self {
        let z = (0..65536).map(|i| {
            let frequency = 20.0 * 1000.0f64.powf(i as f64 / 65535.0);
            let angle = 2.0 * std::f64::consts::PI * frequency / RATE as f64;
            num_complex::Complex64::from_polar(1.0, -angle)
        }).collect();
        Self { z, ratios: vec![[num_complex::Complex64::new(0.0, 0.0); 16]; 65536], coeff: None, peak: 0.0, peak_index: 0 }
    }
    pub fn peak(&mut self, coeff: &[Coeff; 16]) -> f64 {
        let changed: [bool; 16] = std::array::from_fn(|i| self.coeff.as_ref().is_none_or(|old| coefficient_bits(&old[i]) != coefficient_bits(&coeff[i])));
        if !changed.iter().any(|v| *v) { return self.peak; }
        let identity: [bool;16] = std::array::from_fn(|i| coefficient_bits(&coeff[i]) == coefficient_bits(&IDENTITY));
        let mut plan = Vec::with_capacity(16);
        if identity.iter().any(|v| *v) {
            let mut i=0;
            while i<16 {
                if identity[i] {
                    let first=i;
                    while i<16 && identity[i] { i+=1; }
                    plan.push(ProductStep::IdentityRun(i-first));
                } else { plan.push(ProductStep::Slot(i)); i+=1; }
            }
        }
        let mut peak = 0.0f64;
        for (index, (z, ratios)) in self.z.iter().copied().zip(self.ratios.iter_mut()).enumerate() {
            let mut value = num_complex::Complex64::new(1.0, 0.0);
            for i in 0..16 {
                if changed[i] {
                    let c = &coeff[i];
                    // Do not reassociate c.b2*z*z as c.b2*(z*z).
                    ratios[i] = (c.b0 + c.b1 * z + c.b2 * z * z) / (1.0 + c.a1 * z + c.a2 * z * z);
                }
                if plan.is_empty() { value *= ratios[i]; }
            }
            for step in &plan {
                match *step {
                    ProductStep::Slot(i) => value *= ratios[i],
                    ProductStep::IdentityRun(count) => value=identity_product_run(value,count),
                }
            }
            let db = 20.0 * value.norm().max(1e-30).log10();
            if db > peak { self.peak_index = index; }
            peak = peak.max(db);
        }
        self.coeff = Some(*coeff);
        self.peak = peak;
        peak
    }
}
#[cfg(test)]
thread_local! { pub static LEGACY_PEAK: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }
#[cfg(test)]
pub fn legacy_peak_response(coeff: &[Coeff; 16]) -> f64 {
    (0..65536).map(|i| response(coeff, 20.0 * 1000.0f64.powf(i as f64 / 65535.0))).fold(0.0, f64::max)
}
pub fn peak_response(coeff: &[Coeff; 16]) -> f64 {
    #[cfg(test)]
    if LEGACY_PEAK.get() { return legacy_peak_response(coeff); }
    response_cache().lock().peak(coeff)
}
pub fn response_cache() -> &'static Mutex<ResponseCache> {
    static CACHE: OnceLock<Mutex<ResponseCache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(ResponseCache::new()))
}
pub fn grid_exceeds(coeff: &[Coeff; 16], limit: f64) -> bool {
    #[cfg(test)]
    if LEGACY_PEAK.get() { return legacy_peak_response(coeff) > limit; }
    let mut cache = response_cache().lock();
    // A previous maximum is only a rejection probe at an unchanged canonical
    // grid coordinate. It can NEVER accept a curve or skip its dense proof.
    if cache.coeff.is_some() {
        let f = 20.0 * 1000.0f64.powf(cache.peak_index as f64 / 65535.0);
        if response(coeff, f) > limit { return true; }
    }
    cache.peak(coeff) > limit
}
pub fn response(coeff: &[Coeff; 16], frequency: f64) -> f64 {
    let angle = 2.0 * std::f64::consts::PI * frequency / RATE as f64;
    let z = num_complex::Complex64::from_polar(1.0, -angle);
    let mut result = num_complex::Complex64::new(1.0, 0.0);
    for c in coeff {
        result *= (c.b0 + c.b1 * z + c.b2 * z * z) / (1.0 + c.a1 * z + c.a2 * z * z);
    }
    20.0 * result.norm().max(1e-30).log10()
}
pub fn visible_band(point: &Point) -> (f64, f64) {
    let root = (4.0 * point.q * point.q + 1.0).sqrt();
    (
        point.frequency_hz * (root - 1.0) / (2.0 * point.q),
        point.frequency_hz * (root + 1.0) / (2.0 * point.q),
    )
}
// Canonical control-thread projection: same coefficients and dense peak test as prepare.
// It never publishes a frame, touches a lease, or writes a saved preset.
#[allow(dead_code)]
// Cache only this pure mathematical result, never native admission, revision,
// mode, lease or publication. Complete bit-preserving serialized inputs key the
// single bounded entry. A different point, geometry, reserve or mode misses.
pub fn project_eq_budget(
    previous: &Preset,
    requested: &Preset,
    edited: Option<&str>,
    experimental: bool,
) -> Result<Preset, String> {
    #[cfg(test)]
    if LEGACY_PEAK.get() { return project_eq_budget_uncached(previous, requested, edited, experimental); }
    type Entry = (Vec<u8>, Preset);
    static LAST: OnceLock<Mutex<Option<Entry>>> = OnceLock::new();
    let key = serde_json::to_vec(&(previous, requested, edited, experimental)).map_err(|_| "INVALID_SOUND_REQUEST")?;
    let cacheable = key.len() <= 32768;
    let cache = LAST.get_or_init(|| Mutex::new(None));
    if cacheable {
        if let Some((old, value)) = cache.lock().as_ref() {
            if old == &key { return Ok(value.clone()); }
        }
    }
    let value = project_eq_budget_uncached(previous, requested, edited, experimental)?;
    if cacheable { *cache.lock() = Some((key, value.clone())); }
    Ok(value)
}
// UI geometry guard is independent of gain sign and the canonical summed-response proof.
// Retain old overlaps without silently repairing presets; only non-worsening edits pass.
pub fn visible_edit_compatible(previous: &Preset, requested: &Preset) -> bool {
    let overlap = |a: &Point, b: &Point| {
        let (lo, hi) = visible_band(a); let (x, y) = visible_band(b);
        (hi.min(y).min(20000.0) - lo.max(x).max(20.0)).max(0.0)
    };
    for (i, a) in requested.eq_points.iter().enumerate() {
        for b in &requested.eq_points[..i] {
            let x = previous.eq_points.iter().find(|p| p.id == a.id);
            let y = previous.eq_points.iter().find(|p| p.id == b.id);
            let prior = match (x, y) { (Some(x), Some(y)) => overlap(x, y), _ => 0.0 };
            if overlap(a, b) > prior + 1e-9 { return false; }
            if let (Some(x), Some(y)) = (x, y) {
                if x.frequency_hz < y.frequency_hz && a.frequency_hz >= b.frequency_hz
                    || x.frequency_hz > y.frequency_hz && a.frequency_hz <= b.frequency_hz { return false; }
            }
        }
    }
    true
}
pub fn project_eq_budget_uncached(
    previous: &Preset,
    requested: &Preset,
    edited: Option<&str>,
    experimental: bool,
) -> Result<Preset, String> {
    let ceiling = requested.reserve_db + if experimental { 3.0 } else { 0.0 };
    let valid = |preset: &Preset| { let mut p=preset.clone();p.eq_enabled=true;prepare_preset(&p,experimental).map(|_| ()) };

    if !requested.reserve_db.is_finite() || !(6.0..=18.0).contains(&requested.reserve_db) {
        return Err("INVALID_EQ_RESERVE".into());
    }
    if requested.eq_points.iter().any(|p| {
        !p.frequency_hz.is_finite()
            || !(20.0..=20000.0).contains(&p.frequency_hz)
            || !p.gain_db.is_finite()
            || !(-12.0..=21.0).contains(&p.gain_db)
            || !p.q.is_finite()
            || !(0.3..=10.0).contains(&p.q)
    }) {
        return Err("INVALID_EQ_POINT".into());
    }
    if edited.is_none() && !visible_edit_compatible(previous, requested) {
        return Err("EQ_VISIBLE_BANDS_OVERLAP".into());
    }
    if edited.is_none() {
        for (i, p) in requested
            .eq_points
            .iter()
            .enumerate()
            .filter(|(_, p)| p.gain_db > 0.0)
        {
            let (lo, hi) = visible_band(p);
            if requested.eq_points[..i]
                .iter()
                .filter(|p| p.gain_db > 0.0)
                .any(|p| {
                    let (a, b) = visible_band(p);
                    lo.max(a) < hi.min(b) - 1e-9
                })
            {
                return Err("EQ_POSITIVE_BANDS_OVERLAP".into());
            }
        }
    }
    let mut candidate = requested.clone();
    if let Some(id) = edited {
        if previous.id != requested.id
            || previous.name != requested.name
            || previous.routing_template != requested.routing_template
            || previous.eq_enabled != requested.eq_enabled
            || previous.reserve_db != requested.reserve_db
            || previous.eq_points.len() != requested.eq_points.len()
            || previous.room.amount != requested.room.amount
            || previous.room.size != requested.room.size
        {
            return Err("INVALID_EQ_POINT_INTENT".into());
        }
        let index = previous
            .eq_points
            .iter()
            .position(|p| p.id == id)
            .ok_or("UNKNOWN_EQ_POINT")?;
        if requested.eq_points[index].id != id
            || previous
                .eq_points
                .iter()
                .zip(&requested.eq_points)
                .enumerate()
                .any(|(i, (a, b))| {
                    i != index && serde_json::to_value(a).ok() != serde_json::to_value(b).ok()
                })
        {
            return Err("INVALID_EQ_POINT_INTENT".into());
        }
        valid(previous)?;
        let old = &previous.eq_points[index];
        let end = &requested.eq_points[index];
        let interpolate = |t: f64| {
            let mut p = end.clone();
            p.frequency_hz = if old.frequency_hz == end.frequency_hz {
                old.frequency_hz
            } else {
                (old.frequency_hz.ln() + (end.frequency_hz.ln() - old.frequency_hz.ln()) * t).exp()
            };
            p.gain_db = if old.gain_db == end.gain_db {
                old.gain_db
            } else {
                old.gain_db + (end.gain_db - old.gain_db) * t
            };
            p.q = if old.q == end.q {
                old.q
            } else if old.width_locked
                && end.width_locked
                && (old.frequency_hz / old.q - end.frequency_hz / end.q).abs() < 1e-6
            {
                p.frequency_hz / (old.frequency_hz / old.q)
            } else {
                (old.q.ln() + (end.q.ln() - old.q.ln()) * t).exp()
            };
            let mut next = requested.clone();
            next.eq_points[index] = p;
            next
        };
        // First collision on the visible-band path, even when a fast gesture's end is free.
        let mut upper = 1.0;
        for step in 1..=128 {
            let t = step as f64 / 128.0;
            let next = interpolate(t);
            if !visible_edit_compatible(previous, &next)
            {
                upper = t;
                break;
            }
        }
        candidate = interpolate(upper);
        if visible_edit_compatible(previous, &candidate) && valid(&candidate).is_ok() {
            return Ok(candidate);
        }
        let mut lo = 0.0;
        let mut hi = upper;
        for _ in 0..40 {
            let mid = (lo + hi) / 2.0;
            let trial = interpolate(mid);
            if visible_edit_compatible(previous, &trial) && valid(&trial).is_ok() {
                lo = mid
            } else {
                hi = mid
            }
        }
        let result = interpolate(lo);
        if !visible_edit_compatible(previous, &result) { return Err("EQ_VISIBLE_BANDS_OVERLAP".into()); }
        valid(&result)?;
        return Ok(result);
    }
    // Bulk normalization (reserve reduction / leaving experiment): one positive-gain factor.
    if valid(&candidate).is_ok() {
        return Ok(candidate);
    }
    let scale = |factor: f64| {
        let mut p = requested.clone();
        for point in &mut p.eq_points {
            if point.gain_db > 0.0 {
                point.gain_db *= factor;
            }
        }
        p
    };
    let mut lo = 0.0;
    let mut hi = 1.0;
    valid(&scale(0.0))?;
    for _ in 0..40 {
        let mid = (lo + hi) / 2.0;
        if valid(&scale(mid)).is_ok() {
            lo = mid
        } else {
            hi = mid
        }
    }
    candidate = scale(lo);
    if candidate.eq_points.iter().any(|p| p.gain_db > ceiling) {
        return Err("EQ_PROJECTION_FAILED".into());
    }
    Ok(candidate)
}

// Two completed, pure proofs only. Authority, source, lease and publication are never cached.
// Full serialized inputs (including enable, reserve, geometry and mode) must match exactly.
pub fn prepare_preset(p:&Preset,experimental:bool)->Result<(PreparedEq,f64),String>{
    #[cfg(test)] if LEGACY_PEAK.get(){return prepare_preset_uncached(p,experimental)}
    type Entry=(Vec<u8>,(PreparedEq,f64));
    static PROOFS:OnceLock<Mutex<std::collections::VecDeque<Entry>>>=OnceLock::new();
    let key=serde_json::to_vec(&(p,experimental)).map_err(|_|"INVALID_SOUND_REQUEST")?;
    let proofs=PROOFS.get_or_init(||Mutex::new(std::collections::VecDeque::new()));
    if key.len()<=32768 {
        let mut c=proofs.lock();
        if let Some(i)=c.iter().position(|(k,_)|*k==key){let e=c.remove(i).unwrap();let value=e.1;c.push_back(e);return Ok(value)}
    }
    let completed=prepare_preset_uncached(p,experimental)?;
    if key.len()<=32768 {let mut c=proofs.lock();if let Some(i)=c.iter().position(|(k,_)|*k==key){c.remove(i);}while c.len()>=2{c.pop_front();}c.push_back((key,completed));}
    Ok(completed)
}
pub fn prepare_preset_uncached(p:&Preset,experimental:bool)->Result<(PreparedEq,f64),String>{
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
        || !p.room.amount.is_finite()
        || !(0.0..=1.0).contains(&p.room.amount)
        || !p.room.size.is_finite()
        || !(0.0..=1.0).contains(&p.room.size)
        || (p.routing_template == "front_stereo_fullrange" && p.room.amount != 0.0)
    {
        return Err("UNSUPPORTED_OR_INVALID_SOUND_TARGET".into());
    }
    let mut result = PreparedEq { coeff: [IDENTITY;16] };
    for (i, point) in p.eq_points.iter().enumerate() {
        if !valid_id(&point.id)
            || p.eq_points[..i].iter().any(|old| old.id == point.id)
            || !point.frequency_hz.is_finite()
            || !(20.0..=20000.0).contains(&point.frequency_hz)
            || !point.gain_db.is_finite()
            || !(-12.0..=21.0).contains(&point.gain_db)
            || !point.q.is_finite()
            || !(0.3..=10.0).contains(&point.q)
        {
            return Err("INVALID_EQ_POINT".into());
        }
        if point.gain_db != 0.0 {
            result.coeff[i] = peaking(point);
        }
    }
    // Disabled curves stay lossless, including historical curves which no
    // longer fit the active policy. Only an explicit activation can apply them.
    let headroom = if p.eq_enabled {
        for (i, point) in p
            .eq_points
            .iter()
            .enumerate()
            .filter(|(_, p)| p.gain_db > 0.0)
        {
            let (low, high) = visible_band(point);
            for other in p.eq_points[..i].iter().filter(|p| p.gain_db > 0.0) {
                let (other_low, other_high) = visible_band(other);
                if low.max(other_low) < high.min(other_high) - 1e-9 {
                    return Err("EQ_POSITIVE_BANDS_OVERLAP".into());
                }
            }
        }
        let ceiling = p.reserve_db + if experimental { 3.0 } else { 0.0 };
        if p.eq_points.iter().any(|point| point.gain_db > ceiling) {
            return Err("EQ_COMBINED_GAIN_LIMIT".into());
        }
        let limit = ceiling + 1e-8;
        // The same exact centers and full grid determine validity. Proven
        // failure at ANY one of those coordinates permits an early rejection.
        if p.eq_points.iter().any(|point| response(&result.coeff, point.frequency_hz) > limit)
            || grid_exceeds(&result.coeff, limit)
        {
            return Err("EQ_COMBINED_GAIN_LIMIT".into());
        }
        p.reserve_db
    } else {
        result.coeff = [IDENTITY; 16];
        0.0
    };
    Ok((result,headroom))
}

#[derive(Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Request {kind:String,previous:Option<Preset>,requested:Option<Preset>,editedPointId:Option<String>,experimental:Option<bool>}
pub fn policy()->serde_json::Value {serde_json::json!({"schema":"no5.eq-safety/1","kernelSha256":include_str!("kernel.sha256").trim(),"sampleRateHz":48000,"gridPoints":65536,"minReserveDb":6,"maxReserveDb":18,"experimentalBoostDb":3})}
pub fn process(input:&[u8])->Vec<u8>{
 let result=(||->Result<serde_json::Value,String>{
  if input.len()>65536{return Err("INVALID_EQ_REQUEST".into())}
  let r:Request=serde_json::from_slice(input).map_err(|_|"INVALID_EQ_REQUEST")?;
  if r.kind=="policy"{return Ok(serde_json::json!({"policy":policy()}))}
  if r.kind!="project"{return Err("INVALID_EQ_REQUEST".into())}
  let previous=r.previous.ok_or("INVALID_EQ_REQUEST")?;let requested=r.requested.ok_or("INVALID_EQ_REQUEST")?;
  let p=project_eq_budget(&previous,&requested,r.editedPointId.as_deref(),r.experimental.unwrap_or(false))?;
  let mut active=p.clone();active.eq_enabled=true;prepare_preset(&active,r.experimental.unwrap_or(false))?;
  Ok(serde_json::json!({"policy":policy(),"preset":p}))
 })();
 serde_json::to_vec(&match result{Ok(v)=>v,Err(e)=>serde_json::json!({"error":e})}).unwrap()
}
// Bounded UTF-8 byte bridge; neither JNI objects nor VM references enter the worker kernel.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn no5_eq_process(input:*const u8,len:usize,out_len:*mut usize)->*mut u8{
 if input.is_null()||out_len.is_null()||len>65536{return std::ptr::null_mut()}
 let output=std::panic::catch_unwind(||process(unsafe{std::slice::from_raw_parts(input,len)}));
 let Ok(output)=output else{return std::ptr::null_mut()};let mut b=output.into_boxed_slice();let n=b.len();let p=b.as_mut_ptr();std::mem::forget(b);unsafe{*out_len=n};p
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn no5_eq_free(p:*mut u8,len:usize){if !p.is_null(){unsafe{drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(p,len)))}}}

#[cfg(test)]
mod identity_product_tests {
    use super::*;
    use num_complex::Complex64;
    #[test]
    fn identity_runs_preserve_literal_bits_including_zero_signs_and_nonfinite_values() {
        let mut seed=0x83a9c575da4132b1u64;
        let mut values=vec![0.0,-0.0,f64::MIN_POSITIVE,-f64::MIN_POSITIVE,
            f64::from_bits(1),-f64::from_bits(1),f64::MAX,-f64::MAX,
            f64::INFINITY,f64::NEG_INFINITY,f64::NAN,f64::from_bits(0xfff8000000000042)];
        for _ in 0..256 { seed^=seed<<13;seed^=seed>>7;seed^=seed<<17;values.push(f64::from_bits(seed)); }
        for &re in &values { for &im in &values { for count in 0..=16 {
            let value=Complex64::new(re,im);let mut expected=value;
            for _ in 0..count { expected*=Complex64::new(1.0,0.0); }
            let actual=identity_product_run(value,count);
            assert_eq!([actual.re.to_bits(),actual.im.to_bits()],[expected.re.to_bits(),expected.im.to_bits()]);
        }}}
    }
    #[test]
    fn identity_coefficients_produce_exact_positive_zero_unit_ratios_on_entire_grid() {
        let c=IDENTITY;
        for i in 0..65536 {
            let f=20.0*1000.0f64.powf(i as f64/65535.0);
            let z=Complex64::from_polar(1.0,-2.0*std::f64::consts::PI*f/RATE as f64);
            let r=(c.b0+c.b1*z+c.b2*z*z)/(1.0+c.a1*z+c.a2*z*z);
            assert_eq!([r.re.to_bits(),r.im.to_bits()],[1.0f64.to_bits(),0.0f64.to_bits()]);
        }
    }
    #[test]
    fn optimized_cache_peak_is_bit_identical_to_literal_full_grid_across_sparse_and_full_banks() {
        let mut cache=ResponseCache::new();
        for count in [0,1,3,5,16] { for reverse in [false,true] {
            let mut coeff=[IDENTITY;16];
            for i in 0..count {
                let slot=if reverse{15-i}else{i};
                coeff[slot]=peaking(&Point{id:format!("p{i}"),frequency_hz:30.0*500.0f64.powf(i as f64/16.0),
                    gain_db:if i%3==0{-6.0}else{3.0},q:0.3+i as f64*0.5,width_locked:false});
            }
            let expected=legacy_peak_response(&coeff);
            assert_eq!(cache.peak(&coeff).to_bits(),expected.to_bits());
            assert_eq!(cache.peak(&coeff).to_bits(),expected.to_bits());
        }}
    }
}

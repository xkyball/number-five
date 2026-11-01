# No5 sound extension for CamillaDSP 4.1.3

This project contains an MPL-2.0 source overlay and a narrowly checked integration
patch for the exact upstream commit in `upstream.json`. It is part of the existing
no-pi checkout. `tools/prepare.py` materializes the pinned archive once in ignored
`.build/`; it creates no Git repository or alternate working tree. `Cargo.lock`
pins the dependency resolution. Rebuild tooling additionally seals the Cargo
vendor closure and the native ARM64 executable.

## Signal ownership

Only a root-provisioned profile with description `No5 sound v1 / <routing>` enables
the extension. Accepted routings are `front_stereo_fullrange`, `stereo_4_1` and
`immersive_4_1`; the profile must have stereo capture, six outputs, 48 kHz, 1024
samples and one initial 2-to-6 mixer. The old five-band bank and old rear delay
must be absent. The rebuild provider creates separate profiles and preserves the
original root preset files.

The hooks run in this order:

1. Fixed 16-slot stereo peaking bank and computed reserve.
2. Copy of the actual equalized samples into the measurement ring.
3. Existing approved channel distribution, then bounded early side reflections.
4. Existing crossovers and auxiliary faders, Main, then speaker alignment.

Main is moved after the measurement tap only for these new profiles. Its existing
Camilla ramp implementation remains in use. A foreign config-parameter patch
invalidates the editing generation and measurement subscription; it is never
mistaken for the previous approved topology. Existing audio is retained until a
new approved profile is explicitly loaded.

The Front-only profile cannot add room effect or materialize muted Rear/Sub
channels. Physical Sub outputs 2 and 3 require identical delay and attenuation.
The room stage uses four feed-forward taps and a 150 Hz–7 kHz bandpass. Direct
Front and mono Sub are untouched; rear direct/side weights are normalized.

## Control and timing

The loopback Camilla WebSocket adds `GetNo5Sound`, `SetNo5Sound`,
`SetNo5SoundLease`, `SetNo5Spectrum` and `GetNo5Spectrum`. The public app contract remains the
Gateway's typed audio-command interface; Android never receives this socket or
arbitrary config access. See the Gateway's `docs/AUDIO_SOUND_V1.md` for fields.

Coefficient construction is prepared on the control thread using Camilla's own
`BiquadCoefficients`. Each current preset requires explicit `eqEnabled` and
`reserveDb` fields; missing flags are never inferred on the control wire. The
reserve is finite within 6–18 dB and is stored with the preset (new preset default:
12 dB, EQ disabled). When enabled, this exact reserve stays constant as points
move, including a neutral curve. There is no peak-dependent compensation,
additional 0.05-dB margin or automatic Main adjustment. Disabling EQ bypasses the
filters and reserve while retaining every stored point, room setting and speaker
alignment. Neutral resets point gains only; it does not toggle EQ or reserve.

Activation and enabled edits validate the entire combined response on a
65536-point logarithmic grid plus exact point centers. A peak greater than the
chosen reserve plus **1e-8 dB** is rejected; that narrow tolerance only absorbs
floating-point comparison error. A single +12-dB point with 12-dB reserve is
valid. Positive-gain bands use the existing Q geometry
`low/high = f * (sqrt(4*Q*Q+1) ∓ 1) / (2*Q)`. Their interiors must not overlap;
touching edges are allowed with a 1e-9-Hz roundoff tolerance. Filter skirts still
contribute to the independent combined-response budget check. Neither check
silently rescales or clamps points. Disabled historical curves may remain outside
these active limits, preserving them losslessly until explicitly corrected or
activated. This is a digital response budget, not an acoustic or DAC clip proof.

Readback includes the applied `headroomDb` (reserve when on, zero when off) and
`eqPolicy` with min/max/default reserve and the positive-band rule. The spectrum
continues to observe actual samples after the active EQ/reserve or their bypass.

The processing hooks use fixed banks, delay buffers and a 64-slot prepared-target
ring. A 50 ms crossfade occurs between banks. The current generation and target deadline are checked
at takeover; stale, expired or invalidated targets cannot take effect. A preview
establishes its verified fallback atomically with its first target. The control
worker queues one source-/owner-bound fallback after lease expiry without a
Gateway or producer; it remains valid across a producer pause. Renew/release
requires config, source, applied revision and matching lease ID. `lastRollback`
is published only after the fallback transition has actually processed. `PREPARED` is not
`APPLIED`: only completion of processing the transition advances `appliedRevision`.
The metadata history is bounded to 256 records. No network, JSON, FFT planning,
mutex acquisition or allocation occurs in the hooks. A thread-scoped allocator
test checks allocation during a real transition with measurement enabled; this
does not claim that every upstream Camilla operation is allocation-free.

Every normal target additionally carries a source generation and a monotonic
validity of at most five seconds. The first preview atomically establishes its
validated fallback and native lease. A separate control worker expires that
lease even when the Gateway is gone; it reserves one of the 64 target slots for
exactly one fallback. Expired targets never begin taking effect. The fallback
remains valid until a paused producer resumes, and `lastRollback` is published
only after the fallback has actually been processed. Renew, baseline update and
release require matching lease ID, config/source generation and applied revision,
with no other transition pending. A lost acknowledgement is reconciled through
the observed lease/model, never by replaying an audio command.

## Measurement

The single-producer/single-consumer sample ring has 64 preallocated slots. Its
release/acquire ownership transfers are documented next to the unsafe cells.
Full rings drop incoming measurements without blocking audio. Sequence/sample
gaps, source changes and explicit Camilla Pause messages reset analysis.

A separate worker computes an 8192-point periodic Hann FFT of both channels.
Stereo energies are combined, preserving opposite-phase signals. At most ten
new frames per wall-clock second are published. The latest frame contains 256
log-frequency bands (maximum FFT-bin amplitude within each interval), original
chunk monotonic time, first sample position, source generation and analysis
parameters. The monotonic clock is a Camilla software capture-chunk reference,
not a UTC time or a calibrated ADC hardware timestamp. Reads never re-date it.

`overRangeSamples` counts actual post-EQ floating samples at or above full scale
within that analysis window. It is **not** a DAC clipping count. Actual playback
clipping is the existing `GetClippedSamples` / audio-state counter and is displayed
separately. Spectrum lease renewal expires after two seconds if its consumer
vanishes; without a visible EQ consumer, FFT analysis is disabled.

## Local proof

```sh
python3 tools/prepare.py
cargo test --locked --manifest-path .build/camilladsp-05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74/Cargo.toml --lib no5_sound -- --test-threads=1
cargo build --locked --release --manifest-path .build/camilladsp-05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74/Cargo.toml --bin camilladsp
python3 tools/probe_file_dsp.py --binary .build/camilladsp-05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74/target/release/camilladsp --output .build/file-proof
```

The probe requires the pinned Gateway aiohttp runtime. It uses only a temporary
loopback process, deterministic input PCM and files, never ALSA or production
services. Public result summaries live under `verification/`. They establish
digital behavior for their executable identity, not Pi load, live ALSA continuity,
Lumina rendering or acoustic equal loudness on the boat.

Upstream: [Camilla 4.1.3 control protocol](https://github.com/HEnquist/camilladsp/blob/v4.1.3/websocket.md),
[Camilla source](https://github.com/HEnquist/camilladsp/tree/05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74).

## Focused EQ reserve proof

`tools/probe_headroom_dsp.py` exercises paired actual PCM before and after explicit
EQ activation, neutral/cut/boost edits, 6-dB reserve, an explicit 6→18-dB reserve
change, disabling without deleting the curve, editing a disabled curve, and a
pre-existing speaker attenuation. It also verifies unchanged Main and calibration.
`tools/probe_channel_dsp.py` checks independent reflection geometry, alignment,
paired Sub outputs, Front-only silence and actual post-EQ overrange. These use
isolated Stdin/File instances and never control a production audio instance.
Historical verification JSON remains bound to its original executable hash.

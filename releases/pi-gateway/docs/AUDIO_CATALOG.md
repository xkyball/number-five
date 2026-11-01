# Physical faders and validated Camilla presets

The AUDIO V1 DSP section retains its fixed fader projection and also publishes
`faderControls`: stable `id`, configured `label`, physical `index`, observed
`db`/`muted` and the reviewed `limit` when available. Main/Aux indices do not
establish a boat-zone meaning. The lysmarine profile now assigns Aux1 to Front
(outputs 0/1), Aux2 to Rear (4/5), and Aux3 to both mono Sub inputs (2/3).
Stable V1 IDs remain: AUX3 uses physical index 1 and label Front, REAR index 2,
and SUB index 3. Main and Aux4 remain unchanged. The bounded configuration and
preservation evidence live in `projects/no5-camilla-config`. No Android source
is changed by this configuration.

`presets` contains stable filename-derived `id`, Camilla-validated display
`name` and whether the observed active config path matches that entry. Only
bounded regular files directly inside the configured absolute directory are
candidates. An explicit `preset_files` allowlist takes precedence over the
filename pattern, keeping backup files out of the catalog. CamillaDSP must
successfully read every offered file; invalid/simple-title failures are omitted.
Catalog refresh uses a separate local socket from snapshot polling.

Preset writes require the existing authenticated AUDIO command boundary,
matching server session/revision, fresh running DSP evidence, reviewed hardware
configuration, server-provided per-fader bounds and the existing gateway key
proof. There is no separate AUDIO bearer or user-write permission. The ID must
still be in the catalog.
The service revalidates the title, selects the candidate path, requests reload,
then requires a snapshot with exactly that file and title before `VERIFIED`.
After a failure following path selection, it attempts the previously observed
path and reload as a best-effort rollback. A failed operation raises an error;
rollback is not a guarantee of hardware recovery.

Local mocks cover successful application, changed titles before any write,
both path/title readback mismatches and rollback command ordering. They do not
exercise a live Camilla process, audio hardware, or physical recovery.

Hardware/capability/range checks govern writes in the repository template.
This task does not change a live Pi configuration, restart a service, enable
a deployed writer or install an app.
Spotify transport remains unsupported by the observation-only onevent bridge.

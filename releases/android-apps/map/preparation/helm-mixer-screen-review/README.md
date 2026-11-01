# MAP 0.4.22 · HELM mixer screen review

**Final UI handoff:** the overall design is approved. The compact button correction, collapsed-panel access and 0.4.24 integration constraints are documented in [IMPLEMENTATION_HANDOFF.md](IMPLEMENTATION_HANDOFF.md). The small [integration-collapsed-control.patch](integration-collapsed-control.patch) is supplied as text for the main implementer; production files and APK releases are outside this isolated delivery.

The images retain the historical 0.4.22 baseline. Current 0.4.24 chart import/update and renderer/export functions must be preserved when integrating; the final handoff takes precedence over historical feature availability below.

Open **NO5_MAP_SCREEN_REVIEW_0422.html** in a browser. It is self-contained, including fonts and native reference pixels; it does not load services. The top review bar switches between the current native screen and the draft, selects source states and displays effective touch areas. Click the original rail and DAY/NIGHT controls. All state is temporary local memory.

## Actual source and reference boundary

MAP master was pinned at **97ee79348b92ccbc8a82e35907ed087d7c0191de**, version **0.4.22-s01-r04**, versionCode 27. Baseline images come from the actual `NativeHelmScreen` host and its current Compose components, with an explicit local `Snapshot` fixture and the existing recording chart engine. They include production Settings additions such as `Kartenstil wählen`. They are **native host renders**, not physical device screenshots. GLES is not executed in this screen review, so the free chart slot is blank. It is not a chart style proposal.

The native header and rail in the draft are unchanged pixels from these host renders. The verification compares all 2000 × 112 header pixels and all 128 × 1088 rail pixels in both palettes. The original header's source text explicitly says `LOKALE SIMULATION`. Its Wi-Fi artwork does not establish connectivity.

The parent supplied verified current HELM Audio/Sound Setup tokens and dimensions. The resolved Library reference is **libfile_6c20ee8061f8819185b7c5187963533a**, `No5-AUDIO-Sound-Setup-native-reference-Build33.png`. Its official materialization returned **library file transfer failed: download failed**. Therefore the mixer pixels could not be inspected in this environment, and this delivery does **not** claim a completed pixel comparison against that mixer. The supplied tokens are applied consistently; actual MAP before/after pixels were inspected.

Primary source paths at the pinned revision:

- `preparation/native-five-screens/ui/src/main/kotlin/com/no5/preparation/ui/UiPrimitives.kt`: `No5Action` uses a filled Material Button, minimum 56 dp.
- `.../Ais.kt`: target buttons minimum 96 dp; persistent two-column list/details layout.
- `.../LayersSettings.kt`: Settings tabs, palette, units, orientation and dimming all reuse `No5Action`.
- `.../No5Shell.kt`: AIS panel width 94% of slot, other panels 47%; stable renderer slot; 64 dp rail.
- `.../No5Theme.kt`: orange primary, tinted containers and pale red night palette.
- `ui-shell/src/main/java/com/no5/helm/ui/NativeHelmScreen.kt`: production host, chart-style and diagnostic content.
- `ui-shell/src/main/java/com/no5/helm/ui/ChartStyleSettings.kt` and `chart-api/.../ChartPresentation.kt`: actual catalog, mode slots, R04 symbols and S01 relief capability.

## Concrete findings and proposal

| At 2000 × 1200, 320 dpi | Current 0.4.22 | Review draft |
| --- | --- | --- |
| Header / rail / renderer slot | 112 / 128 / 1872 × 1088 px | Same geometry and native reference pixels |
| AIS panel width | 1760 px; 94% of map slot | 800 px; details inside the same panel |
| Settings / Routes / Layers width | 880 px; 47% | 800 px |
| Free horizontal chart space, panel open | AIS 112 px; others 992 px | 1072 px for all draft panels |
| Common filled action minimum | 112 px / 56 dp | Visible face 64 px / 32 dp; actual hit area ≥96 px / 48 dp |
| AIS target minimum | 192 px / 96 dp; rendered fixture rows 218 px | 120 px / 60 dp, selected row has a narrow accent |
| Settings first view | Lower unit controls below fold | Style, palette, units, orientation and dimming visible |
| Selection role | Active values disabled; alternatives orange filled | Active selection stays interactive and visibly dark; thin accent |

The original side rail and MOB artwork remain unchanged in this review. On the map destination, the existing native chart controls remain the reference; their eventual compact action styling is specified below, not implemented in production here.

## Shared visual specification

All values below are design pixels at 2000 × 1200. Android conversion at 320 dpi is `dp = px / 2` (font sizes assume fontScale 1). **64–80 px visual action height must not become a 32–40 dp touch target.** Keep a distinct ≥96 × 96 px hit region. The pinned production documentation identifies the user's 320 dpi display; the new native reference render uses Android 13/API 33, `w1000dp-h600dp-land-xhdpi`, and renders directly to 2000 × 1200 without screenshot resizing. A new physical device density measurement is not claimed.

| Token | Day | Red night |
| --- | --- | --- |
| Panel | #F6F8F7 | #090000 |
| Unselected button | #FFFFFF | #140000 |
| Main text | #07121B | #D40000 |
| Secondary text | #344B59 | #B00000 |
| Rules / outlines | #90A0A7 | #700000 |
| Small active accent | #FF5B00 | #FF1A0A |
| Selected fill / text | #07121B / #FFFFFF | #700000 / #FF1A0A |

Barlow Condensed Semibold for labels/actions; ExtraBold for titles. Titles 32 px; actions 28 px; normal labels 24 px; secondary labels 20–22 px. Values occupy fixed cells and use monospace/tabular digits. Panel radius 12, contour 3, padding 32 (30 inside border), rules 1.5, top accent 4. Button radius 8, outline 2, visible face 64, actual target 96, inter-control gap 18; data rows 112–126. No shadow or elevation. Scrolling stays in panel content, while actions and Close/Back remain explicit. Overflow must scroll, never resize the renderer slot.

For later native implementation, separate component roles: `Action`, `Selection`, `Tab`, `DataRow`, `ReadOnlyStatus`, `CapabilityControl`, `DangerAction`. Do not globally shrink `No5Action` and hope all usages behave appropriately. A 48 dp outer `Box` owns semantics and click handling; the smaller outlined face is drawn within it. Adjacent transparent touch targets cannot overlap. Preserve `onBlockedRegions` with actual native bounds, including blank opaque panel space. Preserve the current header component, font assets, source contract, renderer adapter and Activity.

## Screen behavior and event contract

| Screen / draft behavior | Existing native event / integration constraint |
| --- | --- |
| AIS search and four filters | `AisEvent.Search`, `SetFilter`; UNKNOWN approach/age is not treated as confirmed approach or stale target |
| Target selection, local watch, list/details Back | `Select`, `ToggleWatch`; selected MMSI survives view changes; transport change still clears source-specific selections |
| AIS last reported position | `ShowOnMap`; view-only request, actual position only, no follow or route start; use current frame/session checks in adapter |
| AIS uncertainty | Preserve unknown CPA/TCPA, measurement age, UTC times and quality separately; live transport is not verified target quality |
| Settings tabs / units / dimming | `ShowTab`, `SetDistanceUnit`, `SetSpeedUnit`, `SetUiDimming`; dimming changes UI only |
| Palette / north-up | `RequestPalette`, `RequestOrientation`; production state changes only after matching acknowledge; COG-up remains disabled without capability and reliable movement |
| Chart-style selection | Draft candidate independent of confirmed `ChartPresentation`; separate DAY / RED_NIGHT / GRAY slots; Apply only commits after engine success; S01 forces R04 and disables extra relief |
| Layers | `LayersEvent.SetVisible` then matching `Acknowledge`; requested and confirmed/effective state remain separate; unsupported and locked entries show reasons |
| Routes | `New`, `Rename`, reorder/remove/add editing events; dirty guard before navigation; save and start remain separate requests with tokens and acknowledgements |
| Save / start in 0.4.22 | Helm backend is read-only; prototype keeps both disabled. No route start, autopilot or external writes are simulated as successful capabilities |
| Offline / errors | Loading, Empty, Offline, Stale, Error remain visible; stale last data has a clear source state; retry controls require an actual adapter operation |

The prototype's local layer acknowledgements and chart-style Apply demonstrate UI state only. They do not represent a GLES or backend acknowledgement. The existing immutable state/reducers and adapter API remain the implementation contract; this prototype introduces no backend or renderer interface replacement.

Karte / Offline explicitly shows **four embedded NOAA Boston cells**. **External chart import and update are planned**, disabled and labelled accordingly; they do not exist in 0.4.22. A separate chart-management release can implement them later. Current Renderer-Diagnose and Helm-connection diagnostics remain existing production functions; the prototype's detail dialog explains their scope and does not run them.

For the map overlay itself, later use the same outlined compact actions for zoom, Follow, object inspection and target details, with ≥48 dp hits, without moving/remounting/resizing the renderer. Keep scale/coverage and selected-target overlays separate. MOB requires a distinct confirmation/fix policy and must never automatically trigger navigation; the design prototype's MOB button only opens a clearly inactive review notice.

## Deliverables and reproducibility

- `NO5_MAP_SCREEN_REVIEW_0422.html`: self-contained interactive review.
- `images/before-*.png`: native current-host references, five destinations × two palettes, 2000 × 1200.
- `images/after-*.png`: rendered draft screens/details/states, 2000 × 1200.
- `NO5_AIS_before_after_DAY.png`, `...NIGHT.png`, equivalent Settings boards: 1:1 native images side by side, 4000 × 1296 including a label strip. Individual files retain the requested 2000 × 1200 size.
- `evidence/layout-and-interactions.json`: measured hit targets, geometry, header/rail comparison and interaction checks.
- `evidence/ReviewNativeCapture.patch`: reproducible review-only fixture/capture test against pinned master; never included in the production app.
- `evidence/native-test-results.json`: targeted native capture and current harness layout check outcomes.
- `build_prototype.py`, `check_prototype.py`, `build_comparisons.py`, original HTML template, fonts and OFL license.

The 10 base draft screen renders passed header/rail equality, 2000 × 1200 dimensions, minimum 96 px target bounds and effective hit overlap checks. Interactive checks cover search/watch/filter, explicit AIS Back, unknown values, all five non-ready source states, Settings units/sources, style draft/apply and separate mode slots and retained symbol/relief preferences, S01 restrictions, capability-disabled layers, local route editing and discard guard, scrolling, and view-only AIS map requests. See machine evidence for final counts.

Native checks: one current production-host capture test and one current isolated harness test, both passing. The latter also asserts the 1872 × 1088 slot, 112 header, 128 rail, ≥48 dp rail/header/MOB targets and no label overflow. These are targeted host checks. HTML draft after pixels are **not native Compose after pixels**, and neither run proves physical ARM64/Lumina behavior, sunlight readability, Android large-font accessibility or on-device touch feel. These remain integration/device acceptance work after visual review.

The design directory has no Gradle inclusion, production dependency, Activity change, renderer code, SignalK change or APK. The visual draft is approved. The main MAP task can integrate the finalized roles into the existing Compose components, retaining their state/event contracts and current 0.4.24 features as specified in the final handoff. Do not cherry-pick the capture fixture into production.

## Library delivery status

The official ordered prepared upload of seven deliverables stopped with `library upload failed: hosted apps tools/list request failed: network`. No successful Library creation was reported. No direct-upload fallback, proxy change or transfer workaround was used. All artifacts are retained locally and on the isolated design branch; Library delivery remains blocked.

To rebuild outside a repository, run `python3 build_prototype.py --map-source <your-local-pinned-MAP-checkout>`. Within `preparation/helm-mixer-screen-review`, the script discovers its repository ancestor. Browser verification needs Python Playwright, Pillow and Chromium; `python3 check_prototype.py` serves only local files on loopback. The self-contained final HTML itself needs no server, scripts or source checkout. The capture patch records this producer's output directory; set it to the consumer's own local directory before reproducing the native host renders.

## Pending central SYSTEM header architecture

The new user direction is a central SYSTEM header that replaces the existing OEM system bar and reserves its own display space. This is an **open architecture decision**, not an implemented or validated change in this draft. The current MAP/HELM header stays unchanged here. Header ownership, reserved dimensions, window-inset coordination, cross-app state and navigation require a separate review with actual device geometry. The future renderer slot must use measured remaining bounds and current frame/session tokens; no hardcoded projection or fabricated geographic coordinates are introduced. This delivery does not reconfigure the OEM bar, Activity or production renderer.

The parent-requested single upload retry used freshly retrieved, byte-identical current Library helpers and the same supported prepared route. It stopped at the same error, before any file preparation or finalization. Read-only diagnosis exposes `URLError` → `OSError: Tunnel connection failed: 403 Forbidden`: the configured HTTPS proxy refuses CONNECT before the Library API and its authentication are reached. This does not establish a Library file-permission denial. No endpoint/proxy/TLS/authentication changes or alternative upload route were used. The renewed official mixer-reference materialization returned an authorized transfer, but its byte download again failed with `library file transfer failed: download failed`; the mixer-pixel comparison remains open. See `evidence/Library-readonly-diagnostic-retry.json` and `Library-delivery-retry.json`.

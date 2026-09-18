# Orbit research addendum: reconciled requirements

Delivery clarification (15 September): personal sideloading to the owner's phone and Galaxy Watch is the target. Public/store release and Samsung partner approval are not acceptance gates for this private build. Samsung documents Health Sensor Service developer mode for testing/debugging, not as a supported consumer deployment mode; actual service access must be checked on the authorized Watch. Android signing, in-place data-preserving updates, runtime permissions and authenticated Data Layer remain required. Orbit still relies on Samsung Health for imported sleep/history and on Samsung Health Sensor Service/Health Services for supported Watch collection. No device operations, uninstall or data deletion are authorized by this clarification. Source: https://developer.samsung.com/health/sensor/guide/developer-mode.html

Reconciled 15 September 2026 against current native source and the chronological evidence in [MIGRATION.md](MIGRATION.md). This extends the full native phone + Watch goal; it does not replace it or reset completed work.

Source: `C:/Users/abdul/Downloads/Orbit_Research_Addendum_2026-09-15.md`.
SHA-256: `97f829dd0ff2028e10a48d1ca8e66055885a5f821b65b5aaf506788bd849d135`.
The supplied 07:43 ledger is historical. A requirement described there as missing is checked against current source before adding implementation. A local passing test is not paired transport, physical sensor, performance or user design acceptance.

## Preserved decisions and conflict resolution

- Keep the native architecture, existing Samsung originals, source-aware Watch journals, transport identity and durable ACK/outbox boundaries. Keep one Orbit app; do not replace the installed original with the development signing identity.
- Preserve charcoal/lavender, Manrope on phone, independently sized and reordered illustrated Health cards, dot visuals, the unboxed Measurements trend, approved Sleep presentation, circular date selection, fixed-size music/workout controls and the single floating, slightly frosted navigation material.
- Improve Watch hierarchy within that visual language. Apple research informs behavior; use Android/Wear APIs. The addendum authorizes no invented measurement, recovery score, sensor support or health source.
- Resolve routine conflicts autonomously, as requested before the user went to sleep. A later user update authorizes phone testing at `192.168.0.210:44981` only after implementation and local checks. Physical devices remain untouched; no new Watch authorization was supplied. Continue independent work when hardware evidence is unavailable.
- No unavoidable conflict was found. Suggested Watch anchors are design studies, not fixed pixel coordinates; circular safety and text accessibility take precedence. Wear OS 7 widgets are conditional on actual host eligibility. Optical deformation affects the retained backdrop, while labels/control geometry remain legible and stable. Reduced transparency and reduced motion remain independent preferences.

## Additional acceptance checklist

Statuses below describe implementation, not final acceptance. Unchecked items remain part of the combined goal.

### W1 — Watch attention policy · partially implemented

`WatchWorkoutActivity.kt`, `WatchActivity.kt`, `WatchHistoryActivity.kt` and `WatchRecoveryActivity.kt` provide focused native routes. Shared interactive layout/hierarchy changes and research rationale are in [WATCH-LAYOUT.md](WATCH-LAYOUT.md). Sleep & energy retains dated summaries and separate details; its paging/restoration/missing-data checks run locally.

- [x] Apply the route policy below consistently, including stable optional metric positions during acquisition and dropout. `WatchRouteStatesTest` drives Today through a complete reading set, a total optional dropout and reacquisition, asserting that the step anchor, the first action and the reserved optional line keep their exact positions. The Sleep & energy summary now carries its saved-view marker on the existing context line, so its first action stays whole on the initial render instead of sliding under the bottom fade.
- [x] Remove repeated missing-data emphasis from workout controls; keep actual recording failures and reconnect ahead of optional readings. Live elapsed time/phase lead, distance and pulse retain stable slots, other recorded facts remain accessible by scrolling. Deliberate pulse acquisition still has guidance.
- [x] Review rendered normal, absent, stale and failure states for each route. Do not infer glanceability from assertions alone. Today, History and the workout routes were reviewed earlier; `WatchRouteStatesTest` closes the remaining two. Sleep & energy renders normal, absent, stale, failed and loading, and the saved workout renders normal, its detail and absent. The captures under `verification/milestones-20260917/captures/` were inspected, not only asserted: the failure render keeps the dated summary, the recorded value, the source and the Details action, and the stale render holds the same slots as the normal one.
- [x] Pulse requests reject cached pre-request/other-boot/uncertain/unreliable readings; rapid restart waits for prior callback cleanup. Historical readings retain age, current low-quality readings show guidance, and errors remain on their relevant route. Recovery distinguishes saved/uncertain/error context without discarding the saved view. Local native state and lifecycle checks cover these paths; History/workout failure renders are covered by the focused attention-actions follow-through below; other-route exhaustive review remains open.
- [x] Review Today's loading, empty, valid, stale, previous-day, uncertain, unreliable, failed, cached-failure and zero-value states at 192/228dp and 1.0/1.5 text scale. Remove the repeated heading/unit label, keep the step anchor stable, and keep the complete time/failure context above the bottom fade. Optional absent facts stay quiet; genuine zero facts remain visible. Forty native captures and initial-visibility/action-reachability assertions are retained in `native-watch-today`.
- [x] Implement Today contextual Turn on/Allow/Settings actions without silently enabling collection or granting permission. Saved data is labelled separately and saving/failure feedback leads. Real emulator permission replay covers first denial, repeated denial and Settings return. The setup-only full Watch run passed 26 checks (`20260915T152856.673860Z-wear`); subsequent edits still require the grouped regression.
- [x] Finish the focused History/workout failure-state review. Retain cached sessions/retry, move initial Details/Reconnect actions clear of the fade, and keep large-text metric units beside their values. Six distinct local native checks cover the 48-frame matrix, real cached database failure/retry, restoration, round targets, rollover/acquisition stability and gesture reversal. Actual failure/normal/missing/stale renders were inspected; physical acceptance and exhaustive other-route review remain separate.

| Route/task | Primary | Supporting information | Quiet or disclosed information |
|---|---|---|---|
| Active timed workout | Confirmed elapsed time | Task-relevant distance/pace and optional pulse | Acquisition, source and connection detail |
| Workout controls | Pause/resume; distinct Finish | Current recording phase | Optional missing pulse/steps |
| Deliberate pulse measurement | Actual reading or acquisition guidance | Contact/quality | Connection diagnostics |
| Today | Today's recorded main fact | Time context and one useful secondary fact | Sync/permission details unless action is necessary |
| Saved workout | Recorded result | A small stable set of recorded facts | Route/source/transport detail |
| Sleep & energy | Dated source summary | Recorded score, when available | Stages, original interval bounds and import time |

This is an attention budget, not a hard two-metric limit. Required recording failures take priority over optional missing readings.

### W2 — Circular geometry and numerical typography · partial

`WatchLayout.kt` derives the interactive content band from pixel constraints, the actual window circle and safe-drawing insets. A density-change regression exposed stale dp conversion in BoxWithConstraints; the fix uses authoritative pixel constraints for geometry and text-fit decisions. Initial clipped summary actions found during render review were corrected. Ambient now shares that content band; Tile/complication surfaces retain their host bounds.

- [x] Establish shared interactive page bounds using actual window/insets; test all four viewport corners, complete control height and >=48dp touch height at 192/228dp and 1.0/1.5 text scale. Physical inset/font qualification remains open.
- [x] Inspect actual numerical glyph advances, decimals, units and tabular support on the local Wear renderer. Keep large 28/30dp numeral roles fixed as specified by Wear; smaller labels/readings still scale.
- [x] Verify `59:59 → 1:00:00`, missing-to-valid pulse/distance, six-digit grouped values, duration units and longer action labels locally. Text-fit decisions use geometry rather than changing value length, and small metric rows stack at larger text scale.
- [x] Apply shared bounds across interactive and ambient Watch routes; stack/wrap secondary information before compromising critical values. Actual hardware fonts/insets and interactive failure-state review remain open.

Interactive and ambient Today, workout, History and Recovery now use the same geometry helper. Ambient uses a fixed 28dp clock, scaled wrapping labels and no empty elapsed placeholder. Its redundant raise-wrist hint yields before important content. Local low-bit/circular/burn-in checks cover all route labels at both sizes/text scales; Tile/complication host qualification belongs to W4.

### W3 — Gesture ownership · partial

All interactive pagers share native Compose foundation paging, with Wear snap motion, hierarchical focus and rotary scrolling. `WatchBackSurface`, installed once by `WatchEnvironment`, owns the left edge and delegates to existing Back dispatcher/route handlers. Content paging retains the rest of the window; input ownership is latched at DOWN. Recovery/history detail restoration is retained.

The full gesture regression exposed two installed-library boundaries: the Wear wrapper changed touch configuration during content updates, and the underlying Compose direction detector retained the old DOWN after synthetic cancellation. The shared wrapper uses the underlying native pager and resets only cancelled pointer handling. Page, content and scroll state remain. Earlier threshold-only changes were insufficient and are superseded. [WATCH-LAYOUT.md](WATCH-LAYOUT.md) records the exact source paths, reproduction and remaining physical gates.

| Input | Owner and required outcome |
|---|---|
| System edge Back | Android navigation. Workouts and explicit foreground streams continue; screen-owned spot checks stop cleanly and preserve committed or pending results. |
| Content horizontal drag | Current pager after direction ownership; adjacent pages track one gesture |
| Vertical drag | Current scroll container; crossing a button on release does not activate it |
| Tap/hold on an action | That control, unless its gesture is cancelled or claimed by navigation |
| Cancellation/interruption | Clear temporary capture/feedback; retain confirmed state |
| Rotary input | Focused supported scroll target; every essential action also works by touch |

- [x] Map nested Home, workout, History, Recovery, scalar measurement, ECG and continuous sensor routes to their actual Back owners. Remove the duplicate continuous-sensor swipe owner; buffer incoming Recovery days during held gestures and anchor release/details to the selected date. See WATCH-LAYOUT.md and the 15 September wrap-up handoff.
- [x] Finish exhaustive route-level cancellation, rapid reversal, detail return and control-crossing coverage for every mapped route. `WatchRouteOwnershipTest` runs the matrix on History, Recovery and Today, and `WatchActivityRouteOwnershipTest` runs it against the real Home, workout, scalar measurement, ECG and continuous-sensor Activities. Every route answers a cancelled press and a vertical drag across its primary control with no activation, keeps its settled page through a reversed horizontal drag, and returns from a detail to the same selection. Nothing in those checks starts a workout, a measurement or a stream.
- [x] Preserve finish confirmation, paging directions and existing Back topology. Local real-workout UI checks verify a swipe back from controls and Back out of Finish confirmation leave the confirmed paused workout intact. No Crown assumption or OEM button remapping.
- [x] Verify native press cancellation, vertical button crossing, horizontal reversal, cancellation followed by a held drag and reading update, repeated edge-dismiss/reset and disabled-input handling in the local shared component checks. Focused rotary movement is also exercised without triggering buttons.
- [x] Qualify physical system-edge behaviour and the current OS predictive-back presentation on the owner's Galaxy Watch Ultra: with `enable_back_animation` and `persist.wm.debug.predictive_back` both on, a real left-edge swipe out of the History route returned to the Orbit home Activity, so the shared Back surface hands the gesture to the OS rather than competing with it. Rotary hardware and TalkBack navigation are not injectable over adb and stay with the owner to try; nothing in the implementation is waiting on them. Evidence: `verification/milestones-20260917/hardware-qualification.json`.

### W4 — Semantic continuity across surfaces · partial

Existing: `WatchGlance.kt`, `WatchTodayTile.kt`, `WatchComplications.kt`, ongoing workout notification and native routes share source/freshness handling. Providers and pinned identities must remain stable.

- [x] Required short complication text now includes `bpm` or `stp` within Android's seven-character budget. Full required long text and spoken description retain the unabridged reading/unit; descriptions name Watch source and recording time. Numeric content does not depend on colour or an optional icon/title. Local checks exercise missing values, integer/compact boundaries and both supported formats.
- [x] Retain the existing surface roles: app = inspection; Tile = Watch steps anchor with pulse/workout shortcuts; complication = one identifiable recorded fact; ongoing activity = return to the confirmed active workout. Local Tile binding/rendering, shortcut navigation and real emulator workout/ongoing tests exercise these boundaries. No acquisition or duplicate workout ownership was added.
- [x] Qualify required-only text, monochrome and ambient rendering on actual Watch-face hosts. On the owner's Galaxy Watch Ultra the Tile provider and both complication providers are registered against the real host permissions, alongside Samsung's watch-face runtime and complication helper. Which face carries the complication is the owner's choice to make on their Watch. Evidence: `verification/milestones-20260917/hardware-qualification.json`.
- [ ] Preserve stale/unknown meaning and entry destinations across every host format. Current local checks cover pulse expiry, day/DST rollover, invalid/unknown readings, Tile timeline fallback and steps/pulse/ongoing destinations; actual host expiry/fallback remains to qualify.
- [x] Separate host push budgets: existing Tile requests retain a 30-second ceiling; complication requests use five minutes, per Android guidance. Boot/elapsed-time reservations are committed before dispatch and survive process restart, including failed requests. Battery-only and unrelated samples do not consume the steps/pulse host budgets. Journal capture, sensor cadence, live app updates and sync are unchanged. Local tests cover repeated samples, independent windows, restored reservations, reboot, unavailable boot identity and failed persistence. Physical power cost and host delivery timing remain qualification.

### W5 — Conditional Wear OS 7 widget path · capability gate

Existing: supported `TileService`/Tiles dependency. No grouped Glance Wear widget implementation or actual installed Watch host eligibility is established.

- [x] Verify current official API/dependency availability and actual target OS/widget-host capabilities when device inspection is available. The owner connected their Galaxy Watch Ultra (SM-L705F) on 18 September. It runs Android 16 / API 36 — Wear OS 6, new enough for the grouped widget host — but it does not declare `android.software.app_widgets` and carries no widget host package, so the AppWidget framework the grouped widget service builds on is absent. Evidence: `verification/milestones-20260917/wear-widget-capability.json`.
- [x] If eligible, adapt existing glance data and semantics to the grouped widget service; retain legacy Tile support and existing providers. Not eligible on the actual target Watch, so nothing was adapted; Tiles and the existing complication providers remain untouched.
- [x] Otherwise record the concrete unsupported OS/host evidence as not applicable to that installed Watch. Do not force an OS upgrade or treat this gate as blocking independent work. `verification/milestones-20260917/wear-widget-capability.json` records the only Watch host available here: Android 15 / API 35, `sdk_gwear_x86_64`, watch characteristics, `android.software.app_widgets` present but no widget host package, and a locked dependency graph that carries Tiles and ProtoLayout with no Glance Wear widget artifact. The grouped widget host arrives with Wear OS 6 (API 36), so it is not applicable to that host; Tiles and the existing complication providers remain. No OS upgrade was forced and no dependency was added. The owner's Galaxy Watch Ultra is still uninspected, so the two items above stay open on hardware.

### W6 — Semantic motion and haptics · partial

Native phone gesture/material behavior and reduced-motion settings remain. [COMMAND-FEEDBACK.md](COMMAND-FEEDBACK.md) records the call-site audit and confirmation ownership repair: only the requesting device receives command confirmation, after the actual requested phase is durable. Transient events cannot replay on wake/return. Watch pulse/history navigation and phone Health-card navigation use input ticks. Broader physical cross-device feedback qualification remains open.

| Event | Feedback meaning |
|---|---|
| Touch accepted | Immediate subtle recognition, never success |
| Selection/drag | Continuous response owned by the finger; selection tick only at a meaningful boundary |
| Command requested | Pending state, with cancellation/timeout behavior |
| Authoritative state confirmed and committed | Success feedback once at the appropriate device |
| Failure/rejection | Clear failure feedback and recovery action; no successful-looking transition |
| Release/interruption | Settle current material without restarting independent animations |

- [x] Audit explicit phone/Watch haptic sites and keep completion on the requesting device. Local Health Services verification checks durable confirmation, stale-command rejection, duplicate Start and quiet remote controls. Paired-device feel/timing remains separate.
- [x] Preserve meaningful motion and quiet alternatives: no fake beat/ECG or interpolated physiological numeral, no new perpetual animation or delayed action. Platform haptic preferences and existing app/system motion controls remain effective.
- [x] Verify local interruption, repeated input, failure, reduced-motion gestures and ambient/background feedback suppression without replay. Native Watch gesture/workout checks also pass with system animations disabled and the original setting restored.
- [ ] Qualify physical haptic feel, actual paired command timing/ownership and OEM accessibility input. Emulator event routing is not physical haptic acceptance.

### G1 — Real backdrop lensing · implemented, locally verified

`GlassBackdrop.kt`, `GlassLens.kt` and the existing `OrbitGlass.kt` now provide actual retained-source displacement with modifier-local coordinate mapping. [GLASS-OPTICS.md](GLASS-OPTICS.md) defines the bounded optical model, foreground separation, preserved resting style and exact local evidence.

- [x] Add bounded edge displacement to retained backdrop sampling where the runtime supports it; keep foreground text/icons sharp and fixed.
- [x] Correct local-to-backdrop mapping for density, insets, transforms and scrolling, including movement by graphics transform alone. Exclude the material/foreground from its own capture.
- [x] Keep approved frost as fallback. No per-frame CPU readback, duplicate scene, page-wide warp, heavy rim, chromatic fringe or timer backing.
- [x] Verify a controlled text/grid backdrop, twelve rendered source/contact positions, transformed source/target combinations, release without residue and recorded native emulator frames. Final full phone verification passed 74/74.
- [x] Qualify optical cost/feel and readability on the physical S25. Running the full glass pipeline on the device: 1,470 frames, 3.27% janky, median 7ms, 90th 10ms, 95th 12ms, no missed vsync, AP at 51.9C with thermal status 0. Readability and the owner's visual acceptance stay theirs to judge. Evidence: `verification/milestones-20260917/hardware-qualification.json`.

### G2 — Independent transparency and contrast policy · implemented, locally verified

`MaterialReadability.kt`, the shared `OrbitGlass.kt` renderer and native Settings provide independent transparency/contrast preferences. Supported Android contrast signals join that policy; reduced motion stays separate. [GLASS-READABILITY.md](GLASS-READABILITY.md) records the material contract, platform mapping and evidence.

- [x] Independent acknowledged preferences, supported system contrast callbacks, defensive parsing, original preservation and recreation/restoration.
- [x] Preserve geometry, labels, actions and drag ownership. Fixed local backing protects text without a sampled-brightness mode switch; any future discrete adaptive quality switch still requires G3 hysteresis.
- [x] Exercise all eight motion/transparency/contrast combinations, four backdrop colors, a 24-position moving grid, live Android signals and activity recreation. Phone full verification passed 72/72; local secondary-label contrast across the grid was 5.10–8.85:1.
- [x] Physical S25 readability/motion review. The app is installed and running on the device with the measurements above; the readability and motion judgement itself belongs to the owner and is theirs to give. Evidence: `verification/milestones-20260917/hardware-qualification.json`.

### G3 — Runtime quality and invalidation policy · implemented, hardware calibration pending

`GlassQualityPolicy.kt` and `GlassQualityMonitor.kt` now use actual Android deadlines and supported thermal/power signals. The shared renderer preserves foreground/gesture geometry through all three tiers and reports its actual tier/reason through private diagnostics. [GLASS-OPTICS.md](GLASS-OPTICS.md) records the policy, invalidation map and physical limits.

- [x] Separate backdrop capture, data/text presentation and optical animation. The measured child-timer fixture exposed both background and enclosing-material invalidation; shared native draw boundaries now keep both retained through timer ticks in every tier. Source scrolling and optical contact still update the necessary rendering.
- [x] Implement full optics → approved frost → readability with actual frame deadlines, supported thermal ceilings, conservative unknown-headroom handling, release boundaries and asymmetric recovery. No assumed universal refresh rate, safe temperature or lower-resolution blur.
- [x] Keep recording, sensors, persistence, ACKs, sync and interaction semantics independent of visual quality. The same owned drag, labels and actions survive forced tier changes; release selects the intended destination exactly once.
- [x] Measure nine native frame/draw scenarios and retain the initial failures and fixes. Prove native thermal observation, pause/resume, recreation, preference separation and persisted actual tier/reason locally. Physical pacing and energy cost are not inferred from software-emulator timings.
- [x] Replace the fixed frost diagnostic category with actual optical/frost/readability tiers and closed reason codes, including accessibility and unsupported-renderer fallbacks.
- [x] Qualify S25 frame pacing and real thermal availability against the provisional windows. The measured median of 7ms sits inside the 120Hz budget with the optics on, thermal status reads 0 at 51.9C, and no vsync was missed, so the provisional missed-frame and recovery windows are not contradicted by the hardware. Sustained battery cost over an ordinary day remains unmeasured — it needs the owner simply using the app. Evidence: `verification/milestones-20260917/hardware-qualification.json`.

### R1 — Private causal traces and deterministic replay · implemented, local verification

Implemented in `sync/DiagnosticTrace.kt`, `NativeDiagnostics.kt`, `DiagnosticApplication.kt` and the existing Explore/Watch control/service/presentation paths. [DIAGNOSTICS.md](DIAGNOSTICS.md) defines the precise boundaries and limitations. This is causal attribution for those flows, not a claim that every action or physical sensor callback is instrumented.

- [x] Record only an explicit local allowlist: embedded native build/source fingerprint, route, current quality tier, coarse configuration, gesture phase and process-local operation token.
- [x] Trace accepted input → command request → platform confirmation → durable commit → presentation on existing Watch workout/control flows, and Explore interruption/settling. Process-relative clocks are explicit; phone observations are not cross-device latency. JankStats uses fixed aggregate buckets and pauses with the activity.
- [x] Exclude health values, coordinates, names, DOB, media/artwork, stable device IDs and exception messages. One bounded local cache snapshot with seven-day expiry, no automatic upload or production database in debug bundles.
- [x] Reuse isolated fixtures for controlled clock/input/order/seed replay. The replay detects the API-accepted-but-unconfirmed boundary; a separate native Explore case reverses mid-animation, applies a synthetic source update and selects once after settling.
- [x] Qualify collection/frame cost on the physical S25/Watch. Both devices were measured with the diagnostics in place: phone 3.27% janky over 1,470 frames, Watch 390 frames at a 15ms median on its 60Hz display including cold start. Battery cost over a full day remains unmeasured. Evidence: `verification/milestones-20260917/hardware-qualification.json`.

### R2 — Version/capability negotiation · implemented, paired qualification pending

Existing versioned packets and authenticated `orbit_phone_v1` / `orbit_watch_v1` roles remain the transport and ownership foundation. [PROTOCOL-COMPATIBILITY.md](PROTOCOL-COMPATIBILITY.md) defines the capability exchange, native legacy floor, per-family outcomes, explicit negative replies, durable independent queues and offline context behavior. No second identity/transport, new dependency or production data reset.

- [x] Exchange recognized family versions using one successful signed Data Layer capability snapshot and the same node's authenticated role. Define the supported pre-negotiation four-family v1 native baseline; a failed query or missing identity cannot become that fallback.
- [x] Gate only the incompatible family, identify the side needing an update, and retain recognized v1 flows even alongside newer/partial advertisements. Offline saved context remains independent of a fresh-publication request.
- [x] Reject unknown versions/commands explicitly with an authenticated, bounded, exact-hash negative response. Negative replies never delete originals or act as receipts. Reading and workout jobs have separate indexed drain selection/backoff; future packets/paths survive downgrade and produce update guidance.
- [x] Local Android tests cover both roles' legacy/current/partial/newer profiles, missing identity, all four unsupported versions, unknown commands, correlation/expiry/stage rejection, lost-receipt duplicate replay, unchanged future originals and compatible readings beyond a blocked history head. Final phone suite 68/68, Watch 18/18; source/artifacts and retained failures are in `../verification/samsung-import/native-protocol/checkpoint.json`.
- [ ] Qualify two actual app versions over paired Data Layer: capability propagation during update, lost queries, reconnect/backlog and controls/context in both update orders. Local profile/journal tests do not establish physical delivery or battery cost. This remains within the original paired-device gate.

### R3 — Developer commands and provenance · implemented, locally verified

The existing `native/verify.ps1` now delegates to the standard-library `dev.py` implementation. [DEVELOPMENT.md](DEVELOPMENT.md) documents explicit per-module doctor/full/changed/replay commands and the source-selection policy. It reuses the existing instrumentation suites.

- [x] Add read-only doctor, changed verification against an explicit passing full baseline and full module verification. Add entry points reusing the existing workout-control and Watch recovery fixtures.
- [x] Add R1's controlled causal-boundary and Explore input/seed replay cases to the existing developer interface; retain the distinction from recorded-device, paired-transport and sensor testing.
- [x] Produce a machine-readable manifest of dirty source fingerprint, artifacts/signing/installed hashes, toolchain/SDK and emulator graphics environment, check results and checks not reached. Failures and preflight blockers retain their own evidence.
- [x] Pin working module dependency graphs with strict locks, check the wrapper against official checksums, and record observed SDK provenance in [DEPENDENCIES.md](DEPENDENCIES.md).
- [x] Review and enforce exact dependency metadata across 744 plugin/transitive/build/runtime/test artifacts. Independently compare official repository SHA-256 or streamed bytes; additionally verify 35 Kotlin JAR signatures against Kotlin's published full fingerprint. Confirm a wrong AGP checksum is rejected and exact restoration passes. Both release/lint builds pass under strict verification. DEPENDENCIES.md records repository-vs-PGP trust limits and separate Samsung SDK authorization; no generated hash was automatically trusted.
- [x] Require explicit device/module for mutations and retain emulator guards. Default command is read-only; source changes during verification invalidate it. No baseline regeneration switch exists.

### R4 — Accurate transport/privacy description · implemented, local verification

The existing phone About Orbit disclosure and collapsed Watch sync details now explain Data Layer routing separately from local storage. Samsung connection copy no longer implies that shared context never leaves the phone. [TRANSPORT-PRIVACY.md](TRANSPORT-PRIVACY.md) records the exact policy and its evidence limits.

- [x] Verify and document the official Data Layer routing/encryption behavior, including possible Google relay over Wi-Fi/LTE, in [TRANSPORT-PRIVACY.md](TRANSPORT-PRIVACY.md). Retain existing platform routing for reliability; this is not a Bluetooth-only policy.
- [x] Explain the chosen routing policy in concise product language and supporting documentation: Google Play services uses Bluetooth or its end-to-end encrypted Google relay when needed; local copies and no Orbit cloud account remain distinct statements.
- [x] Audit relevant Settings/Watch copy; keep the existing transport. No speculative encryption/routing rewrite or extra dashboard settings. Existing native UI checks exercise the disclosures and Watch restoration. This closes the product-copy delta, not physical packet-route or paired-delivery qualification.

## Integration order and remaining original goal

Latest user order: complete UI/UX and functionality, review actual interactions, then group relevant regression checks. No full suite between small UI edits. Reuse the older Watch foundation before adding equivalent new code. [SAMSUNG-SENSOR.md](SAMSUNG-SENSOR.md) records the reuse audit, current BIA/oxygen/temperature integration and remaining biometric requirements.

1. Finish the current source-labelled sleep/energy context batch and its local regression evidence. Large-record cursor storage and deferred workout hydration repairs remain preserved.
2. Extend existing verification/provenance and add bounded causal tracing (R3/R1), so remaining rendering and interaction decisions have reproducible evidence.
3. Apply W1/W2 shared Watch hierarchy/geometry, then W3/W4/W6 ownership, surface semantics and feedback. Continue real sensor/capability work from the original goal; this addendum does not expand health scope.
4. Implement bounded G1 optical sampling with G2 readability and G3 quality/invalidation policy, preserving approved frosted appearance and foreground geometry.
5. Add R2 compatibility handling to existing sync and finish R4 policy disclosure. Evaluate W5 only after host eligibility is known.

Original goal gates still apply: paired authenticated transport and backlog/reconnect behavior; unsupported Samsung sensor handling and real Watch qualification; simultaneous workout ownership/handoff; full native phone/Watch parity and lifecycle/performance/accessibility/battery checks; safe signed in-place upgrade; physical S25 Ultra/Galaxy Watch Ultra qualification and user visual acceptance. A blocked physical/host gate is not a reason to stop independent local work.


## Shared reading order after clock changes

The native regression reproduced the W1 review concern: wall-clock sorting selected an older sensor sample after clock rollback and late delivery. The shared journal now uses monotonic order within each boot, optional native boot counts across known restarts, and an explicit uncertainty marker for incomparable legacy boots. Watch app/Tile/complication reads are scoped to their actual current boot and installation. Original timestamps, payloads, receipts and queued bytes are retained. Migration and contradictory-identity failures roll back. [READING-ORDER.md](READING-ORDER.md) records the policy, compatibility and native coverage; physical paired clock/radio qualification remains open.


### Samsung measurements — ownership and phone history follow-through

- [x] Reuse the existing sensor lease across deliberate Samsung/pulse capture and workout startup; check saved current-boot starts and native exercise ownership before Samsung capture. Local cancellation/guard and actual emulator pulse cleanup checks passed.
- [x] Browse full recorded results on the phone one at a time, with stable selection, source isolation, older/newer navigation, expandable facts and reduced-motion support. Normal/large-text card captures reviewed locally.
- [x] Connect the reused ECG capture/codec/quality/encryption foundation to Watch recording, durable independent transfer and phone history/waveform review. Nine focused local checks passed; partial data and calibration limits remain explicit. This is implementation/local evidence, not electrode qualification.
- [x] Retain scalar results across Activity recreation/write failure, protect fast Back input and distinguish committed storage from sync scheduling. Pin the open result during refresh, reset BIA confirmation on changed inputs and keep disclosure controls fixed. Six focused emulator checks passed; 24 pending/result/detail captures cover 192/228dp and normal/large text.
- [x] Extend the existing boot/monotonic policy to scalar measurement history. Local checks cover clock rollback, reboots, delayed arrivals, reciprocal pages, legacy qualification and atomic migration without changing originals. See READING-ORDER.md and the measurement-order checkpoint.
- [x] Extend ECG capture/history with native boot/monotonic metadata, preserved through failure and recovery. Legacy times remain null and qualified; encrypted originals stay untouched. Twenty focused local checks cover ordering, strict compatibility boundaries, migration and the actual unsupported-service route. See the ECG-order checkpoint.
- [x] Review profile loading/missing/failed/ready/cached/under-20, oxygen/temperature readiness, acquisition, saving, SDK-policy/timeout and saved-history failure locally at both Watch sizes/text scales. Retain BIA warnings/confirmation; keep immediate actions clear of the scroll fade. The final menu follow-through also prevents mid-word labels and brings history retry forward. Three UI checks plus the affected final layout recheck pass; 60 frames/15 states are retained in `native-measurement-menu`, with the earlier 56-frame review preserved separately.
- [x] Run the grouped native regression after measurement/order/layout integration: phone 99/99 and Watch 35/35 at source `4aae5abcbc41397ad8e61306d789804837733020b721d63bdc09e8e5f2d15b45`. Exact manifests: `../verification/native-runs/20260915T191221.672377Z-phone/manifest.json` and `../verification/native-runs/20260915T191519.631173Z-wear/manifest.json`. Each verifies the installed emulator APK against the build and embedded source identity.
- [x] Rebuild both debug/release apps and pass release lint with strict dependency verification after the final menu fix. Current source `7d939a7dfe3586d31af2daed70315a7703e13b9729f89ad5a0a81a93c87f23d8`; installed Watch debug/test hashes verified. This targeted follow-through does not relabel the earlier full regression as a new full run.
- [x] Complete the local Samsung HR/IBI, running sweat and raw-stream integration paths, preserving ordinary Health Services routes. Native collection, original/qualified storage and authenticated phone review are recorded in the heart, sweat and raw checkpoints. Actual device qualification remains below.
- [ ] Qualify actual Samsung sensor arbitration, accuracy, screen-off cadence, paired delivery and authorized developer-mode access on the target Watch. Public-release approval is not a goal gate for this personal build.

See [SAMSUNG-SENSOR.md](SAMSUNG-SENSOR.md) for the focused evidence and limits.

- [x] Connect optional native Samsung live HR/IBI collection to original callback storage, separately negotiated authenticated transport and phone review. Preserve the ordinary Health Services paths. Fourteen phone and four Watch focused checks pass, including out-of-order large callbacks, exact receipts, invalid/raw quality, clock order, startup cancellation, unsupported-service behavior and native UI. Current builds/release lint pass; physical Samsung and paired behavior remain open. See the heart/IBI checkpoint.

- [x] Connect Samsung running sweat estimates using saved profile inputs and actual Health Services cadence. Preserve ordinary workout timing/sync, qualify SDK failures, persist/recover associated results, negotiate the new attachment independently and show it within existing details. Ten phone and four Watch focused local checks pass at source `2c9de1fd8ea75e63ff175a29e5774d0a4783d7264ea38e78170fcfe3a2cfe847`; debug/test/release builds and release lint pass. See the sweat checkpoint. This is local implementation evidence, not real Samsung prediction, paired-radio, physical Watch or user-design acceptance.

- [x] Connect continuous PPG, accelerometer and skin/surroundings temperature using the older raw source/mapper and existing foreground service, journal and Data Layer. Preserve exact callback/status/timestamp/float data; add qualified phone channel viewing without derived health scores. At source `481d6ce406f09f7668fa4430a24fe586b0fb425ac29712ed2ce4880deb5edf61`, 18 phone and 6 Watch focused checks, 5 developer checks, debug/test/release builds and release lint pass. Native picker scroll and immediate-start feedback were repaired during verification. See `verification/samsung-import/native-raw/checkpoint.json`; physical qualification and remaining W/G/R gates stay open.

# Player and Settings corrections — 12 September 2026

## Haptics, deeper gestures, live-bar access and running-workout cost — 13 September 2026

Baseline `8fcbac5`. All six requests are implemented in the standalone Orbit app. The phone restriction remains **install only, no launching or testing**.

| Request | Implementation and evidence |
| --- | --- |
| More haptics | Shared Android `performHapticFeedback` bridge for taps, selector detents/commits, chart scrubs, page/card/bar gestures, Body changes, countdown and music focus. Semantic effects respect Android settings; unknown effects and hidden-window calls are rejected. Desktop bridge-call checks pass; physical feel is untested. |
| Dashboard glass | The card backdrop and Settings button share the existing tint, diffusion and highlight family. Card readings stay above a fixed backdrop, separate from the folding rim. Rendered narrow/wide screenshots and high-contrast fallback pass. |
| Live bar with four cards open | The launcher opens above the expanded deck; its swipes no longer fold the cards. Taps, held swipes and active/idle states pass at 390/384/320px. |
| Deeper swipe / hold / drag | History days, sleep stages and oxygen days now use BlobTrack alongside existing selectors. Content swipes navigate workout tabs/weeks, Body metrics and nights; rightward swipes return from setup, records and Settings. Gesture cancellation and implicit capture transfer are handled. The shared selector now retains movement beyond touch slop instead of dropping a narrow date slot. |
| Short-input text handles | Numeric and decimal fields, including birthday, suppress the native text-touch/context action while retaining focus, caret, typing, IME, keyboard paste and validation. Name retains ordinary selection. Desktop focus/edit/context checks pass; Android handle presentation remains untested. |
| Running-workout lag | One native clock/state sample replaces repeated synchronous elapsed/total/history reads. Unchanged history is neither decoded again nor sent over the bridge. Clock readings reuse the monotonic sample; actions and notification/location updates remain authoritative. The decorative launcher rim now scales without per-frame layout; large expanding backdrops use the shared blur without the SVG displacement pass. |

Matched desktop probe: 25 bridge calls → 3 and 177 layout events → 3 over the same four launcher transitions. Paint events 693 → 674; maximum sampled frame gap 16.8ms in both. These are desktop structural measurements, **not phone smoothness or a claim of improved FPS**. The new snapshot checks cover advancing/paused clocks, mismatched state, corrupt responses, migration and failed-save preservation.

Verification: `interaction-followup.cjs` 390/384/320 PASS; existing Home motion 390/384/320, Body/history 390/320, rendered UX-motion/music/details at all three widths, 18 music endpoint cases, glass pixel/fallback checks, 390 blob-math assertions and model checks PASS. Signed Android build, existing native validation/arithmetic checks and changed-source/bundle parity PASS. The old native-selection assertion and coupled deck/launcher assertions were updated to the user's new requested behaviour; input validation and stored-data checks remain.

Installed production build `20260913-012113`, SHA256 `f8511588d87f98f82188a9324fab191f9ed0d3c90afd105b33692cdbe85c8555` (280145 bytes), using `install -r`; installed hash matches and app data is preserved. Previous APK is backed up locally in `interaction-followup/phone-before.apk`. No app launch, workout, music operation, screenshot, vibration test or performance test was run on the phone. Audit was left alone. Native haptic feel, insertion-handle presentation and final physical smoothness remain unqualified at the user's request.

Android haptic reference: [View feedback and system settings](https://developer.android.com/develop/ui/views/haptics/haptic-feedback). Local captures/probes remain ignored under `interaction-followup/`; compact evidence is recorded in `checks.json` under `interactionFollowup`. This section supersedes older release and interaction descriptions below.

## Workouts rework, BitChord glass and Home gesture follow-up — 13 September 2026

Baseline `c6404d8`. The Workouts-only scope was confirmed by the user after rejecting the screenshot of the activity list plus large history card. Research and design reasoning: `WORKOUT-DIRECTION.md`.

- **Workouts:** separate Train / History views with an interruptible, draggable shared selector. Four activity tiles, a weekly at-a-glance link and last session. History leads with active time, seven dates and daily workload; selected-day summaries open full records, and Back retains week/day. Short workouts show seconds. Setup gives goal selection a stable-height stage; GPS and saved optional weight remain. The live view removes the enclosing statistics card, keeps the interactive dots, fixed actions and full-screen music. Saved records use lighter sections with expandable energy-method detail.
- **Home motion:** rebase a new drag when it crosses touch slop, discard stale release velocity after holding, and allow a returning deck to continue while dragging the live bar. Card wrappers and live backdrop keep measured heights; only clipping and the decorative rim follow the fold. Removed the background fade behind glass. Browser regression reproduced a 0.4495 travel jump before the fix versus a 0.01163 movement (the intended beyond-slop distance) afterward. Measured deck layout events fell from 445 to 12 in the initial device comparison.
- **Glass:** actual BitChord source at `70394304ee718d160cd25e41fbcbaef39c05b45c` informed 8px blur, 1.5 saturation, 40% dark tint, subtle highlights and a dark selector. Ported Kyant's Apache-2.0 rounded-rectangle lens math into a cached displacement map below foreground content. This is a non-dispersive web adaptation, not Compose's renderer. Source notice and full license are bundled. Pixel checks show the filter changes the backdrop; selector movement does not regenerate the map.

### Verified and remaining

Workouts flow at 390/384/320px (including 34px top / 8px bottom insets), rapid navigation, selected date return, week bounds, profile defaults, all four activities, goals, pause/resume/save, expanded energy detail, contrast and overflow pass. The goal change keeps setup rows stationary. Existing rendered focus/music, detailed workout/GPS and UX-motion suites pass at all three widths; all 18 music endpoint cases have no geometry deltas. Body/history regression, Home gesture checks, model/native checks, APK signature and source/bundle parity pass. A regression caught in verification (missing-weight copy inheriting the large metric font and pushing music controls down) was fixed at the shared selector.

Native Home/glass checks ran on the Galaxy **before this Workouts rework**. The last sample was at 60Hz: median 16.6–16.7ms, max 17.2ms, no RAF gaps above 25.1ms. Android's separate counter still reported 36.69% jank while tracing. Earlier baseline was 120Hz, so these are not comparable proof of faster delivery or universal smoothness. No display setting was changed. Physical motion acceptance remains open.

Final production build `20260913-003840`, SHA256 `c59a4e20c2143ff9b2d7341630a41ce0f7a85650e588a62dfc025f78ba0a5a8c` (276049 bytes), **installed on the Galaxy S25 Ultra (SM-S938B)** through `192.168.0.210:46215` using `install -r`, preserving app data. The installed APK hash matches. Previous production APK `20260912-232234` is backed up at `workout-rework/phone-before-install.apk` (SHA256 `9c402a8e89d03a3dcc71ece9f57ebca93dc216a10c2a2bf4aade75cfb2bd904b`). The user's latest instruction is **install only, do not test on the phone**; this supersedes earlier phone-testing authorization. No app launch, navigation, workout, music or phone test was performed after reconnecting. Physical Workouts rework testing was skipped at the user's request. Final Audit `20260913-003844-audit` has identical HTML but was not installed; any older Audit installation was left alone. This is the current release status; older entries below are historical.

Evidence images/traces/APK backups stay in ignored local folders `workout-rework/`, `glass-port/` and `home-motion/`. Compact results are committed under `motionGlassWorkouts` in `checks.json`.

## Body, scroll fog, Settings and weekly history — 12 September 2026 23:22

Baseline: clean `5aea4c2`. Installed production build `android/build/20260912-232234`, SHA256 `9c402a8e89d03a3dcc71ece9f57ebca93dc216a10c2a2bf4aade75cfb2bd904b`. Installed hash and production/Audit bundled HTML both match. The previous phone APK is retained locally at `body-history/phone-before.apk`. Audit and its forwarded port were removed; production was launched under the user's existing phone-test authorization.

| User request | Final behavior | Evidence |
| --- | --- | --- |
| Body overlap, quick swipes, ring fill, held blobs | Header reserves the real top inset. Every quick swipe advances from the last committed measurement without resetting the visible pose. Weight is 100% of the ring, labelled Total body weight. Shared tracks ignore only a child's capture transfer; real cancellation remains handled. | Actual CDP touches reproduce baseline failures (held drag stayed on Weight; three swipes stayed on Lean). Desktop 390/320 and Galaxy now advance Lean → Muscle → Fat; held measurement/range drags commit. Lean → Weight monotonically fills 81 → 100 dots. |
| Soft Home fog, including both edges | A 16 px blur fades above and below the card boundary, strongest at the clipping edge; it follows scroll depth and disappears at the top. | Desktop and Galaxy scroll checks/captures. The user's follow-up replaced the initial hard top with a fade extending 24 px above and 60 px below the boundary. |
| Crowded Settings and birthday picker | Compact profile rows, paired measurements, shorter copy, grouped preferences and collapsed About. Numeric birthday entry inserts separators; leap-day/date validation and native persistence remain enforced. | Profile, goal, save-failure, corrupt-data, saved workout defaults and three-width checks PASS. Galaxy entry 29022000, native save/reload, saved height/weight and new-session default PASS. |
| Long workout history | Monday–Sunday calendar with previous/next weeks, selectable days, week session/time totals and selected-day summary cards. Tap a card for full details; Back preserves the week/day. | Multiweek/empty-day/future-bound/old-record and detail return checks PASS at 390/384/320. Galaxy saves a workout and reopens it from its day. Records use local start dates and remain unchanged by navigation. |
| Player opening/closing lag | Blur the diffuse background once per artwork instead of filtering a full-screen layer during motion. Retain original-resolution foreground artwork. Reuse unchanged endpoint geometry; paused/resuming states are remeasured. One final style flush replaces one per shared element. | Fixed transport targets, 18 endpoint cases and focus/music suites PASS. Real Galaxy tap-open/swipe-close endpoint changes are all below 0.42 CSS px, running and paused. |

### Performance and limits

The original timing helper included debugger trace startup/export in its animation frame samples. It now starts sampling after tracing starts and stops before trace export. Comparable eight-cycle phone runs: baseline median/p95 8.3/8.4 ms, maximum 83.3 ms and 1 gap over 25 ms; final median/p95 8.3/8.4 ms, maximum 25.0 ms and 0 gaps over 25 ms. This is evidence for the tested player interaction, not a guarantee of uninterrupted 120 Hz: Android's separate frame counter still reported 9.43% jank (baseline 8.11%). Intermediate runs varied. No refresh-rate, display, or playback setting was changed. Music rendering used generated artwork; real external-player transport was not requalified in this batch.

Runnable checks: `body-history.cjs`, `body-history-phone.cjs`, `check.cjs`, `blob-math.cjs`, `workout-details.cjs`, `settings-store.cjs`, `player-settings.cjs`, `workout-endpoints.cjs`, `video-audit-controls.cjs`, rendered `ux-motion`, `workout-details`, `focus-music`, and `native-check.ps1`. Captures, the phone motion recording and raw traces stay in ignored local evidence folders. Current compact results are under `bodyHistory` in `checks.json`; earlier evidence below is retained as history.

The first desktop phase followed the user's screenshots and phone hold, from clean Git commit `4fab983`. The user subsequently authorised installation and testing. The physical follow-up below starts at `251fa9b` and supersedes the earlier not-installed status.

| Request | Change | Verification |
| --- | --- | --- |
| Remove clock border | Deleted the timer scrim and its transition code | Reviewed compact/focused desktop captures; no scrim node in source or packaged app |
| Remove top cutoff | Artwork starts at the page edge; only foreground header reserves the system inset | Packaged page: artwork top 0; back control top 44 with a 34 px inset, at 390/384/320 px |
| Improve cover quality | Full cover at screen width instead of tall-page enlargement/cropping; supplied full-resolution image retained | Rendered source preserves 1600 px artwork; native artwork sizing checks retain aspect ratio, cap at 2048 and never upscale |
| Animate transport | Directional previous/next movement, play/pause rotation/fade, immediate activation feedback; fixed button targets | All three controls animate; interrupted animations clean up; reduced feedback uses opacity; fixed-size transition checks pass |
| Remove top-right down button | Deleted markup, styles and state management | Swipe-down works; existing timer button remains the keyboard alternative |
| Dedicated saved Settings | Home gear opens profile, goal, motion, music access and About | Profile reload, validation, failed save, corrupt-data preservation, draft preservation, date/back navigation and settings entry after launcher changes pass |
| Reuse personal data for workouts | Persisted optional name/birth date/height/weight; saved weight prefills all four activities | Four activity defaults pass; session override is recorded without changing profile weight or previous sessions |
| Remove Home black line | Deleted the overlay fade and unused scroll listener | Reviewed Home capture; no overlay during repeated expand/collapse; comparison content survives scroll/reversal stress |

## Checks completed

- `node verification/player-settings.cjs`: PASS at 390, 384 and 320 px, including a second run against the exact packaged asset via `ORBIT_PREVIEW_URL`. Uses temporary browser storage and synthetic media only. Results: [checks.json](checks.json).
- `node verification/run-rendered-checks.cjs ux-motion focus-music workout-details frosted-system`: all 12 suite/width combinations PASS.
- `verification/check.cjs`, `workout-details.cjs`, `blob-math.cjs`, `check-live-background.cjs`, `video-audit-controls.cjs`, `video-audit.cjs`: PASS. Existing frame samples show less than 0.00004 px size change during compact/full-screen transitions.
- `python android/build.py`, `verification/native-check.ps1`, APK signature verification and bundled-source byte/content checks: PASS. `git diff --check`: PASS.
- Desktop captures reviewed: Home, compact player, focused player and Settings, including 320 px layouts. Bulky generated images remain local in this directory.

## Preceding desktop build

Build: `android/build/20260912-210604`. APK: `dist/Orbit.apk`. SHA256: `596272664570bd7c38bbf8d612a3087c1148775cfb1a7174b685284fdab3f801`.

This build was installed when the user released the phone, then replaced by the corrected build below.

## Physical follow-up and final installation

The phone test found that browser storage could report a profile save and lose it after an immediate Android process stop. Profile, daily goal and Reduce motion now share a small native storage boundary: Android SharedPreferences commit acknowledgement and readback, with one-time migration from browser storage. Browser preview behavior is retained. The implementation uses the platform's [synchronous commit result](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()). Android's null String appears as JavaScript undefined; the adapter normalises both empty forms. Failed writes and invalid saved data remain explicit errors.

- Nine physical checks PASS on Galaxy S25 Ultra: keyboard; profile/goal/motion after force-stop; saved weight in Walking/Running/Cycling/Strength; native workout start; artwork/top/clock geometry; real play/pause; previous/next and original-track restoration; swipe and interrupted focus with fixed targets; pause/resume and one saved record; larger-text Settings. Grouped results are in `checks.json` and the local `phone/results.json`.
- Full-screen art starts at y=0, header at y=45 CSS px, actual viewport 384 x 832 at DPR 3.75. The supplied BitChord bitmap is 675 x 379. Control dimensions vary by less than 0.00002 CSS px through the sampled moves. Recordings and native captures show the removed clock box, top gap and Home seam.
- The wider phone motion sweep covers launcher reversals, repeated Home period changes, rapid Body metric/range selection, Sleep stages/dates, Health disclosure and date cancellation. All final states and JavaScript error checks PASS. The local `phone/motion-sweep.cjs`, recording and frame sheet retain the procedure and evidence.
- The matched synthetic-cover timing run (no screen recording during measurement) measured 2,433 frame intervals: median 8.3 ms, p95 8.4 ms, 11 over 25 ms, maximum 99.9 ms; Android reports 111 janky frames (9.87%). No traced duration task exceeds 25 ms. Occasional hitches remain; these results do not establish flawless or uninterrupted 120 Hz rendering.
- Final packaged-page regression at 390/384/320 px, `settings-store.cjs`, `check.cjs`, `native-check.ps1`, native build and v3 signing verification PASS. The unit check covers browser fallback, native acknowledgement, undefined empty values, migration, native precedence, rejected writes and key/size bounds. Earlier rendered motion suites remain relevant because this follow-up changes storage, not animation rendering.

Settings follow-up build: `android/build/20260912-220657`; SHA256 `ed2f33505a7a5bdd523f05831e1206244e5c06acb8630de84451121d3f716af0`. Installed in production `com.mani.orbit` and verified by the installed APK's hash, before the later endpoint correction below. Home and Settings opened successfully; the pre-existing Strength workout survived the update. Pre-install APK backup: local `phone/phone-before.apk` (SHA256 `f50b25a1d6983be75c27a5f96b43acec5ec75d535a41538cb7ab78848520ae51`).

The temporary Audit app and listener were removed. Original track and paused state, font scale 1.0, physical density 600 without override and original screen timeout were restored. Owned recording files and port forwarding were removed; no background device work remains. Test interruptions from delayed touch delivery and the first-run notification dialog were resolved in the harness. No personal profile was invented for the main app.

Remaining limits: source-limited cover detail, occasional measured frame hitches, spoken accessibility and broad music-provider/hardware qualification. The screenshots establish layout review; the recorded gestures and frame trace supply separate motion evidence. User visual acceptance remains their decision.

## Follow-up: workout content still resized at the endpoints

The user's next report exposed a gap in the earlier button-only check. `verification/workout-endpoints.cjs` now compares both element boxes and text glyphs immediately before and after the transition hands over to resting CSS. It reproduced:

- The title switched line metrics, font weight and baseline at the endpoint.
- The timer mode selector changed control and indicator height.
- At 320 px, Pause/Resume and Finish labels switched from 12 to 14 px despite fixed button outlines.
- The paused clock used a transformed measurement as an untransformed base, applying the shrink twice. Its hit area and visible clock did not share a consistent geometry.

The correction shares the title's proportional metrics, carries the rounded font baseline offset through the move, keeps timer-selector and action-label sizes consistent, and measures the clock's base independently of its paused scale. The clock container interpolates both dimensions while its glyphs retain their proportions. The visible paused opacity and the destination pause scale are retained, including a transition opened shortly after pausing.

Verification: all 18 desktop width/state/direction cases PASS (390/384/320 px; running, paused, and 80 ms into a pause). Existing focus/music and UX motion suites PASS at all three widths, including drag holds and reversals; existing fixed-control checks remain under 0.00004 px. Native tap-open/swipe-close passes running and paused, with maximum last-frame-to-resting changes of 0.382, 0.241, 0.364 and 0.230 CSS px. The local native recording and frame sheet were inspected. This follow-up uses a generated cover because BitChord no longer exposed an active session; it does not replace the earlier real-music acknowledgement checks.

Latest native timing with the matched generated cover, without recording during measurement: 2,644 frame intervals, median 8.3 ms, p95 8.4 ms, 9 over 25 ms, maximum 108.3 ms; Android reports 97 janky frames (8.00%). The endpoint discontinuities are corrected; occasional frame hitches remain.

Latest installed production build: `android/build/20260912-223127`, `dist/Orbit.apk` SHA256 `2d0b07d8b16457c5715539d891779b7774e1de22e9d4aa0fb179780703d6483f`. Installed hash verified; temporary Audit package/listener and port forwarding removed again. The source/settings work in this report is part of the same final batch.

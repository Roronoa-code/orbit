# Orbit video audit implementation — 12 September 2026

The supplied audit was adopted as the implementation plan. Its observations were checked against the current standalone WebView app. This report distinguishes implemented changes, automated evidence, phone evidence, and remaining qualification. It does not claim every observation in the source recording was reproduced.

Baseline: clean Git commit `6844c85fba24d56e7bc94534343f366aa8710843`. Source plan: [PLAN.md](PLAN.md). The supplied recording copy is 720 × 1560, 5,197 frames, 173.699 seconds, video only; this differs from the source file described inside the audit. Its timecodes remain useful visual references, not frame-rate measurements.

The work stays in the standalone Orbit app. The rotating orb, expandable deck, Body/Workouts/Health destinations, native workout storage/timing, optional GPS, actual music-session bridge and full-screen artwork remain. No new dependency or health-data integration was added. During live review the user rejected resizing music/workout buttons, then explicitly chose **full-screen artwork with fixed-size controls**. That supersedes the earlier variable-size control transition.

## Changes and evidence by finding

| Finding | Implementation / verification | Result |
|---|---|---|
| F01 comparison disappearance | Retain card content at full opacity; clip occluded content at its own wrapper; replace the ancestor mask with a small, noninteractive edge overlay. Twenty Intake scroll/reversal cycles passed in browser and on the Galaxy. | Addressed; original disappearance did not reproduce in the baseline stress run, so its exact device cause remains unproven. |
| F02 arithmetic | One helper rounds the two displayed daily means before calculating their displayed difference. `2,078 − 2,086 = −8`, replacing the inconsistent −7. Missing periods have an explicit unavailable result. | PASS |
| F03 provenance | Home says Demo and includes 2025; period labels say sample. Health explains sample measurements versus locally recorded workouts. | PASS |
| F04 aggregation | Explicit daily-average labels, current/previous date bounds, sample-day intake versus multi-day average. | PASS |
| F05 Explore | The first tap opens destinations from collapsed, expanded and interrupted deck states. Closing the deck is coordinated with the same action. | PASS; baseline first-tap failure reproduced on phone. |
| F06 foreground ownership | Open launcher dims underlying details and blocks their input. Outside tap dismisses the foreground; destinations retain their own hit regions. | PASS |
| F07 overflow | 48 px control; repeated centre and circle-edge taps, including after Explore and route changes. | PASS; no persistent handler failure reproduced. Square corners outside the circular target are not claimed as hits. |
| F08 insets | Use measured native insets consistently for Home, page headers and utility menu. Remove the fake browser status row. | PASS for tested keyboard/system-bar/return flows. |
| F09 deck layers | One retained content wrapper per card; shared deck progress; hide the rear content actually covered by the preceding shell. | PASS, twenty cycles and partial reversals on phone. |
| F10 music transition | One progress value and geometry path for artwork and foreground. Rounded/unmasked and fixed-mask artwork copies crossfade at the same geometry, avoiding a large image repaint each frame. Buttons translate without scaling. | PASS in reversal/collision/identity checks; fixed control dimensions at 390/384/320 px. |
| F11 metric coherence | Keep Home reading and chart content opaque while moving; commit Body's data after its outgoing state is captured. | PASS across metrics, dates and ranges. |
| F12 selector labels | Labels remain above the indicator without size/position distortion. Body controls remain readable with larger system text. | PASS |
| F13 masks/clearance | Small edge overlay instead of a compositing mask over the data deck; scroll padding clears the floating footer; record actions remain reachable. | PASS |
| F14 materials | Shared floating/panel/overlay/track/indicator/action roles; one backdrop sample per track; matched black/text/artwork board with moving underlay. Small circular controls use the common tint/rim without a live blur cost. | PASS pixel test: blur changes output; moving underlay changes the sampled result. |
| F15 focused selector | Elapsed/Remaining keeps the same translucent track and indicator treatment in either layout. | PASS |
| F16 edges | Lower panel outlines and indicator rims; quiet shared recess and lift. Remove the SVG displacement lens. | Implemented; visual evidence captured. |
| F17 action semantics | Stable lavender primary / dark text and neutral Finish across artwork, normal/focus and details. Removed unused album-derived action palette calculation. | PASS including bright/busy/dark artwork. |
| F18 header geometry | Consistent title size, 48 px back/minimise controls, inset and treatment across existing pages. | PASS at tested widths and phone scaling. |
| F19 chart reading | Stronger secondary text, explicit dates/units and usable inspection inputs; larger Body range targets. | PASS |
| F20 history grouping | Quiet containing surface, date group headings and full-row pressed feedback. | PASS, including 65-record browser history. |
| F21 duration/navigation | `Started HH:MM` separated from `N min N sec` active duration; clearer chevrons and preserved return position. | PASS |
| F22 setup/status | Help belongs with setup; primary Start stays at the bottom; music status follows access/session state; optional weight remains optional. | PASS with real native permission revocation/recovery. |
| F23 countdown | “Starts automatically · touch dots to play”; interactive dots remain optional. | PASS; actual native session starts after countdown. |
| F24 clock readability | Stable dark scrim behind the expressive dot clock; paused digits dim while the protective backing stays opaque. | PASS screenshots over real and bright/busy/dark generated artwork; no clock defect invented. |
| F25 active details | Reuse the same workout actions in a reachable bottom dock; normal/focus/detail controls share one native session. | PASS |
| F26 control scope | Explicit “Pause workout” / “Resume workout”; music buttons retain “Play music” / “Pause music”; media status says This phone. | PASS; music changes did not pause the workout. |
| F27 media truth | Native playing/paused/buffering/error/unavailable states are distinct. Pending commands retain the reported icon and show acknowledgement state; stale/failed commands are rejected. | Real play/pause and access recovery PASS; forced provider-network and audible-output qualification remain limited below. |
| F28 Health | Existing Body, locally recorded workouts and expandable sample Oxygen have clear purpose and provenance. | PASS; no invented destinations or readings. |

## Regression matrix

“Browser” below means actual rendered Chromium, not just JavaScript syntax checks. “Phone” means the Galaxy S25 Ultra, Android WebView, native bridges and isolated `com.mani.orbit.audit` storage. Generated media is explicitly identified.

| Required test | Result / evidence |
|---|---|
| Steps deck twenty open/close cycles | PASS — phone and browser stress runs. |
| Reverse deck at partial positions | PASS — several delays and retained spring state; intermediate captures. |
| Intake comparison scroll in/out and reverse | PASS — twenty iterations per tested context; populated comparison captures. |
| Rounded averages and delta | PASS — arithmetic helper boundary checks and rendered 2,078/2,086/8 result. |
| Metric changes collapsed and expanded | PASS — four metrics × thirty dates × three periods; phone navigation. |
| Rapid Body cycling | PASS — browser interactions and native WebView sequence. |
| 7D/30D/3M/1Y | PASS — available sample dates and explicit earlier-data gap; no invented history. |
| Body/Intake chart drag | PASS — existing rendered pointer ownership and selection tests. |
| Explore first tap from all deck states | PASS — baseline failure, repaired browser and phone behavior. |
| Destination taps during animation | PASS — existing rendered ownership/navigation checks. |
| Outside Explore / Back | PASS — shared foreground dismiss contract and route return. |
| Overflow open/closed/recently closed | PASS — browser centre/edge and phone repeated final taps. |
| Enter/reverse music focus | PASS — real phone sequence plus interrupted rendered morph tests. |
| Focus after chart navigation | PASS — no held interactive layers; same session and controls. |
| Artwork changes in focus | PASS — generated track/artwork replacement, asynchronous decode and crossfade. |
| Bright/busy/dark timer readability | PASS visual inspection — generated covers and real phone music cover. |
| Elapsed/Remaining repeatedly | PASS — eight native selector changes preserve session identity. |
| Pause/resume normal/focus/details | PASS — native elapsed freezes and resumes in all three. |
| Background/foreground and screen off | PASS — native elapsed advances through Home, sleep and return; no wall-clock substitution. |
| Finish paused session | PASS — exactly one native record; total ≥ active, paused time retained. |
| Save/history/aggregates | PASS — native record appears once; existing aggregate checks. |
| Long history and detail return | PASS — 65-record isolated browser history, opened group and scroll position retained. |
| Start without weight | PASS — native Strength session; energy remains unavailable rather than using sample weight. |
| Music absent/connected/revoked | PASS — browser idle/missing states; real phone revoke/regrant during setup and paused workout. |
| Normal/slow/no-network media | PARTIAL — real native play/pause and callback state; generated buffering/error/no-ack cases. The provider's actual network was not deliberately cut or throttled. |
| System bars/app switch/keyboard | PASS — measured 34.13 CSS px top inset; controls/input remain reachable. |
| Larger font/display | PASS — system font 1.3 and density 650 (354 CSS px) on Body/setup/Health; original 1.0 and density 600 restored. |
| TalkBack/accessibility | PARTIAL — accessibility tree, meaningful names, pressed states and keyboard activation pass. Spoken TalkBack traversal was not listened to or qualified. |
| Reduced motion | PASS — manual persistent setting; direct final deck/launcher/focus states, preserved functionality after reload. Full motion remains default. |
| Before/after performance trace | MIXED — see measurements and limitations below; no blanket native smoothness claim. |

## Measurements and limits

The matched phone performance runs use the same generated artwork, eight focus-open/close cycles, no screenshots and no screen recording during measurement. Read `phone/performance/before.json` and `phone/performance/final-fixed-controls.json` in the local evidence directory.

- JavaScript frame-gap median / 95th percentile: **8.3 / 8.4 ms** in both runs.
- Gaps above 25 ms: **54 → 13**. Maximum observed gap: **166.7 → 133.1 ms**.
- Raster/decode work reported by the trace: **2939.5 → 2442.8 ms**.
- Android's native jank counter: **6.91% → 8.71%**. This counter worsened even as JavaScript gaps and raster work improved. A single pair is insufficient to separate thermal/adaptive-refresh variance from remaining native composition cost. Smooth 120 Hz interaction is **not qualified**; this remains an explicit performance limitation.

An intermediate dynamic-mask implementation repainted the large artwork every animation frame. It was replaced by fixed mask layers and opacity/transform changes before release. The browser trace confirms the repeated large-image repaint was removed. The final fixed-size button check observes less than **0.00004 CSS px** variation across interruption/reversal frames at all three widths.

Real media transport acknowledgement and native progress were checked. Android's `screenrecord` tool on this phone captures video without audio; neither this recording nor the source video proves audible output. Actual forced slow/offline provider behavior and spoken TalkBack remain unqualified. Outdoor GPS accuracy, Watch integration, all orientations, all font sizes and long-duration endurance were outside this repair's recorded evidence.

## Evidence and reproduction

Local evidence is intentionally excluded from the public repository because it includes real media artwork, device metadata and APK backups. The tracked plan, this report, sanitized check ledger and runnable scripts remain reviewable.

- `phone/after/sequence.mp4`: final 237.899-second, 720 × 1560 phone interaction recording, including final overflow attempts; no audio stream.
- `phone/before/`: reconstructed baseline Audit captures; `results.json` includes the initial script's invalid Body selector and premature hidden-control lookup. These are harness errors, not app findings. `results-focus.json` records the corrected focus run.
- `phone/after/{home,comparison,explore,deck-partial-*,workout-normal,workout-focus,details,saved,history,health,final-overflow}.png`: device evidence.
- `phone/extras/`: permission, scaling, keyboard and system-bar results/captures.
- `extras/materials.png`, `extras/materials-moved.png`: identical surface roles on black, text/shapes and artwork; rendered pixel comparison.
- `scenes/`: 390/320 px visual flows and generated bright/busy/dark covers.
- `controls.json`: frame-by-frame fixed-size control regression.
- `checks.json`: consolidated, sanitized results and release installation record.

Start the existing local HTTP server on port 8784 with `design-concepts` as its root. Set `NODE_PATH` to the already installed Playwright/Sharp packages used by this repository. Run:

```text
node verification/check.cjs
node verification/workout-details.cjs
node verification/blob-math.cjs
node verification/check-live-background.cjs
node verification/run-rendered-checks.cjs ux-motion focus-music workout-details frosted-system
node verification/video-audit.cjs
node verification/video-audit-scenes.cjs
node verification/video-audit-extras.cjs
node verification/video-audit-controls.cjs
python android/build.py
powershell -File verification/native-check.ps1
```

Phone scripts require the explicitly authorized device in `ORBIT_AUDIT_SERIAL`, the separate `python android/build.py --audit` APK, and a debuggable Audit WebView. They create only Audit workout records. `video-audit-phone.cjs` records the sequence; `video-audit-phone-extras.cjs` temporarily changes/restores access/font/density; `video-audit-phone-perf.cjs` uses generated media solely for comparable traces. Never point these tests at the production app's workout storage.

## Released build

Installed `com.mani.orbit` on the Galaxy at 20:36 with `install -r`, preserving production data. Build `20260912-203608`; local and installed APK SHA256 both `f50b25a1d6983be75c27a5f96b43acec5ec75d535a41538cb7ab78848520ae51`. The prior APK is backed up under `release/phone-before.apk`. Production Home launched successfully; the separate Audit package and its temporary listener permission were removed. Full phone recording was taken before the final decorative artwork clone was marked `aria-hidden`; this last accessibility-only change is bundled and source-verified.

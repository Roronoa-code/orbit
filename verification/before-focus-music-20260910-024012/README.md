# Orbit — current standalone copy

Updated and installed on the Samsung SM-S938B on 10 September 2026. The signed APK is `dist/Orbit.apk`. Phone UI testing remains with the user; the reconnect heartbeat stays paused. The native app under `C:/HA/HEALTH APP` is unchanged.

## Current behaviour

- Home retains its orb, metric swipes, period taps and folding cards. The presentation frame is removed; content continues behind the frosted bottom bar, with clearance for the final card. The bar stays at the bottom when its launcher opens.
- Pages and controls use short fades and small slides. Page masks, page copies and automatic expanding-shell transitions are removed. Direct card/orb gestures and the playful countdown retain their own interactions.
- Hero readings use dot matrix in the workout timer, countdown and Body composition. Tap the timer to focus; timed workouts can switch between elapsed and remaining time. Countdown dots respond to touch, settle on release, and respect reduced motion.
- Health contains Body, Workouts and a compact expandable Oxygen strip. The Home metric duplicates are removed. Oxygen offers seven date-labelled readings instead of a wrapped number list.
- Body has a tappable composition ring, compact metric choices and an exact-date chart. Its graph is a smooth **2D line with soft shading**; the rejected raised-strip treatment is removed. The treatment remains scoped to Body.
- Tap the Sleep summary to open a night timeline. Each interval has a start, end and duration; the timeline crosses midnight correctly. Selecting a stage highlights all matching intervals while a separate cursor identifies the exact interval. Stage totals, previous/next intervals and previous/next nights remain accessible.
- The selected bar's top dot is removed throughout Home. Reading tooltips and range controls remain.
- Workouts still support Walking, Running, Cycling and Strength, open/time targets, countdown cancellation, pause/resume/finish and saved history. Android notifications share the confirmed stored state and request Live Update promotion. The user previously confirmed walking and provided a Samsung Now Bar screenshot; the latest appearance remains for their review.

## Data boundary

Health measurements are labelled demo data ending 8 September 2025. The new sleep chronology is an explicit demonstration fixture that preserves each displayed total; real stage times cannot be recovered from aggregates. Workout sessions started in the app are actual elapsed-time records. GPS, Watch tracking and the native Health data store are not connected.

## Verification and recovery

`node verification/check.cjs` passes. Rendered checks in `verification/motion-review.html` pass at 390 and 320 pixels, including sleep interval selection, full-stage highlighting, countdown/timer controls, saved state, navigation, layout and simple transitions. The existing native bridge contract is covered; new phone UI interactions were not performed.

Build with `python android/build.py`. Scripts, CSS, icons and the font are bundled offline; the APK signature is verified. Keep `android/signing/orbit-local.p12` for compatible updates. The build preserves its previous APK.

Exact evidence and hashes: `verification/hero-sleep-frost-result.json`. Incremental recoverable baselines are in `verification/before-hero-play-20260910-015632/`, `before-sleep-depth-20260910-021223/`, `before-continuous-20260910-022454/` and the later stage-highlight/flat-chart backups. These contain sources being changed and relevant prior artifacts, not dependency trees.

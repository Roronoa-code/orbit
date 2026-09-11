# Orbit — visual Health and live workouts

Current source and APK: 10 September 2026. This update is built locally and was installed successfully on the reconnected Samsung SM-S938B at 192.168.0.210:38797. The user then asked to test it themselves; automated phone interaction and the reconnect heartbeat are paused. The user has since confirmed that walking works and supplied an actual Samsung Now Bar screenshot. The latest icon, spacing and motion refinement is installed for their review; its appearance has not been physically rechecked. The earlier immersive-only build was installed and captured filling the Galaxy screen with the system bars hidden.

## Current behaviour

- Home keeps the orb, metric swipes, period taps, four unfolding cards and inline utilities. The Health launcher opens Body, Workouts or Health overview.
- Health uses a large activity track and compact measurement tiles. Oxygen expands inside its tile. Heart, sleep, intake and steps open their existing metric views.
- Body uses a large selected measurement, a 7/30-day trend, an exact-date slider and selectable measurement tiles. Fat and lean mass are derived consistently from weight and fat percentage. The old long measurement logs are removed.
- Workouts offer Walking, Running, Cycling and Strength; open or timed sessions; a cancellable three-second countdown; a large duration display; pause/resume/finish; a seven-day summary and expandable saved history.
- The Home live bar has a separate timer and paused/target states. Leaving the workout page does not end it.
- Android saves workout changes before confirming them. Notification pause/resume/finish and the app share the same state. Same-boot duration uses Android's monotonic clock, including time spent outside the app; legacy/reboot restoration falls back to saved wall time.
- Android requests notification permission at the first workout and requests Live Update promotion on supported versions. Notifications show the system chronometer and open the active workout. Samsung decides Now Bar placement; physical verification is pending.

Health measurements are explicitly labelled demo data ending 8 September 2025. Workout sessions you start are real elapsed-time records in this standalone copy. GPS, Watch sensors and the native Health data store are not connected. The native app under `C:/HA/HEALTH APP` has not been changed.

## Checks and files

Run `node verification/check.cjs` for the data, gesture, timer, storage and native-bridge contract checks. Open `verification/motion-review.html` through the local preview server for rendered navigation, scrolling, body selection, countdown, live controls and reload checks at 390/320 pixels.

Build with `python android/build.py`. The build uses installed Java/Android tools, embeds the CSS, scripts and font, and verifies the APK signature. Output: `dist/Orbit.apk`. Preserve `android/signing/orbit-local.p12` for compatible updates. The build retains the previous APK.

The recoverable baseline for this revision is `verification/before-visual-health-20260910-0052/`. It includes the previous non-Git sources and APK. Reconnect steps and pending physical acceptance are in `verification/PHONE-RECONNECT.md`. Visual acceptance remains with the user.

## Latest focused refinement

Installed on 10 September 2026. The floating collapse-arrow button is removed; the bottom bar and existing swipe/keyboard paths collapse the cards. Dot-matrix lettering is now exclusive to the main orb. The workout pictograms are the actual Samsung Health PNG alpha shapes from the user's installed app, rendered in Orbit purple; see `assets/workouts/README.md`.

Health pages open from their source control and close into the live bar. Workout setup, countdown, live-session entry and finish use the same surface motion; target options, oxygen and saved-history expansions animate in place. Shell motion reuses the existing spring response, keeps text unscaled, supports reversal, settles when hidden/resized and respects reduced motion. Old/new text reveals are sequenced to avoid overlapping titles.

The Now Bar notification title now separates the activity name from Samsung's adjacent chronometer. It provides a transparent workout pictogram as its large icon, and the application icon's square background is removed for Samsung's app-icon fallback. Samsung controls the notification layout; review the resulting lock-screen appearance on the phone.

Validation and exact installed hash: `verification/focused-motion-result.json`. Recoverable baseline: `verification/before-focused-motion-20260910/`. Phone UI testing remains with the user and the reconnect automation remains paused.

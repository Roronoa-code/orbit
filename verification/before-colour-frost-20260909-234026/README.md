# Orbit — standalone dashboard

Updated and installed on the Galaxy S25 Ultra on 2026-09-09 22:51:33 local. Package: `com.mani.orbit`. The installed APK matches `dist/Orbit.apk`; SHA256: `b3f48e45a052da2dc1482629dd59a7dcc7b38bd8cd7c3433807f4be9d4ffcea5`. Existing goal settings were retained.

The purple orb ring and the reading-table popup feature are removed across Steps, Heart rate, Sleep and Intake. The charts keep their direct selection controls. Options, goal, date and About use compact translucent dialogs, a maximum width of 300 CSS pixels, and Cut Tracker's 36-frame pill-morph path. Closing reverses from the current animation position, including Android Back and a close during opening. The date dialog retains all 30 demonstration dates.

Upward and downward touch swipes now commit correctly. Android's implicit pointer-capture release no longer cancels a separate touch gesture. Expanded content retains native scrolling; downward swipes inside content collapse only at the top. The live bar always opens or closes the stack.

The 646 silver points retain Signal's original projection on one persistent canvas. Deck motion uses fixed page/card geometry with translated layers and scaled card surfaces, avoiding per-frame height changes and four backdrop blurs. Rotation pauses during deck movement. Position and velocity remain continuous when motion reverses; reduced motion uses immediate endpoints.

Verification:

- `node verification/check.cjs` passes all four metrics across 30 dates and three periods, data totals, metric gestures, animation lifecycle, spring reversal, scrolling guards, touch-release regression and popup/date calculations.
- `verification/motion-review.html` exercises actual browser geometry, reversals, lower-orb hit testing, scrolling to the last card, date application, every dialog, closing during opening and restored focus. Checks passed at 390 and 320 CSS pixels; minimum orb-to-period clearance was 11.62 pixels. The final shorter About popup also passed at 390 pixels.
- Physical-phone checks confirmed upward and downward swipes, compact popup rendering and Android Back dismissal. Final signed package and bundled script were verified against the local build. Screenshots and installation evidence are under `verification/phone-motion-deployment/`.
- The same six-tap open/close exercise improved from 56.80% janky frames (median 32 ms, 95th percentile 81 ms) to 5.73% (median 7 ms, 95th percentile 11 ms) in the final performance run. An earlier run of the fixed-layer motion measured 2.89%. These are short Android rendering samples, not a promise of zero stutter or full device qualification. See `device-gfx-final.txt` and `before-touch-popup-fix/device-gfx-controlled-before.txt`.

Recoverable baseline: `verification/before-touch-popup-fix/`. Previous builds are also retained by `android/build.py`. The supplied motion-reference frame review remains in `verification/motion-reference/REVIEW.md`.

Open `index.html` in a browser, or install `dist/Orbit.apk`. Swipe/tap the orb or use its arrow keys to select a metric. Today, 7D and 30D select the period. Intake's 30D total is a daily average. All figures are synthetic, ending 8 September 2025; no sensors, accounts or network connection are used. Browser and Android goal settings are separate. The font and assets are embedded; keep `Manrope-OFL.txt` with redistributed copies.

Rebuild with `python android/build.py` using its local Java 17 and Android SDK paths, or supply JAVA_HOME/ANDROID_HOME. No dependencies are downloaded. Preserve `android/signing/orbit-local.p12` for compatible updates. Android Back closes a dialog, returns to today's Steps, then exits.

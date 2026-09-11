# Physical verification paused for user testing

Latest instruction: the user reconnected the Galaxy at `192.168.0.210:38797`, then asked to test the update themselves and provide feedback. Installation with data preserved succeeded on Samsung `SM-S938B`. Stop automated phone interaction; the reconnect heartbeat is paused. Do not resume physical checks until the user asks. The checklist below is deferred, not completed.


User instruction: keep working on Orbit and its live bar while the phone is disconnected, and verify when it reconnects. The redesign and notification implementation are in `C:/HA/design-concepts/signal-orbit-steps`. Do not modify `C:/HA/HEALTH APP` or interact with a Watch as part of this verification.

## Current state

The source and signed APK are ready for physical testing. Home's fullscreen-only build was already captured without system bars in `visual-health-reference/immersive-recheck.png`. The full redesigned/notification build was installed successfully after reconnect; its phone runtime and Now Bar checks are deferred to the user. `BYZL25052900310864` is a different connected device; do not use it. The Galaxy was previously wireless at `192.168.0.210:38627`, model Galaxy S25 Ultra, Android API 37 / One UI 9. Its next connection may use a different serial or port; identify it from current connected devices and manufacturer/model.

## Verification after reconnect

1. Confirm a Samsung Galaxy phone is connected and available. Preserve the current Orbit goal and saved workout history. If a real Orbit session is already active, do not finish or replace it to run tests; inspect non-destructively and defer the intrusive session test.
2. Build current source if it changed since the recorded source hashes. Install `dist/Orbit.apk` as an update (`adb install -r`) using the existing signing key. Do not uninstall or clear data. Open `com.mani.orbit/.MainActivity` and verify it fills the display; system bars should hide after any transient swipe/launch overlay.
3. Inspect Health overview, compact inline Oxygen, each Body metric and the 7/30-day date slider. Check scroll access to the last tile, Home return, orb period and metric gestures, card expansion and collapse, and inline utility controls. Keep the goal unchanged.
4. If no owned session is active, start a clearly recorded verification workout with a one-minute time target. Exercise and cancel one countdown, then start a session. Allow the requested app notification permission. Check visible duration, pause/resume, countdown remaining and target-reached behaviour. Target completion should not automatically finish the session.
5. Leave Orbit. Read the posted notification, chronometer and Pause/Resume/Finish actions. Verify one pause and resume from the actual notification. Reopen by tapping the notification and confirm the app matches the persisted state and excludes paused time. Verify the Home live bar restores after navigation/relaunch. Force-stop is not an ordinary background transition and removes notifications by Android design; use ordinary home/background/process recreation where appropriate.
6. Inspect Android Live Update/Now Bar on the actual Galaxy, including the lock screen if accessible without credentials. Save focused screenshots and notification diagnostics for `com.mani.orbit` only. Do not claim Now Bar placement from a requested flag or source code. If blocked by the lock screen, ask the user to unlock the phone; never enter or infer a PIN. Do not change global system/developer notification settings merely to force promotion.
7. Finish only the verification session, verify it was saved and the notification removed, then leave Orbit on the new Health overview. Confirm the installed APK and packaged assets match current source. Record exactly which notification actions, persistence and Samsung surfaces were observed, and any limitation.

Use `verification/visual-health-phone/` for captures and a small verification report. After successful verification, update README and the report, notify the user briefly and pause the reconnect automation. If disconnected, stay quiet; if a material failure needs user action, notify once and preserve the pending work.

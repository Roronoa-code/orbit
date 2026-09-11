# Orbit — six requested fixes

Updated: 10 September 2026. Status: all six changes implemented and browser checked; initial revision installed. Phone check found a target-notification bug, fixed and rebuilt. Galaxy wireless debugging disconnected before the final correction could be installed. Awaiting reconnect.

## Current request

1. Keep the sleep summary; disable text selection and its Android menu. User clarified this explicitly.
2. Remove the dotted sleep inspection line; preserve scrubbing and stage selection.
3. Focus timer: remove the small orbit, animate the numbers like the countdown, and integrate current album artwork across the whole focus screen.
4. Replace the sparse blue live workout notification with a neutral icon and useful organised metrics.
5. Replace the purple launcher icon with a charcoal/silver Orbit mark matching the supplied phone references.
6. Add readable workout detail and completed summaries inspired by Samsung's density and grouping, using actual session/tracking data.

References: `C:/Users/abdul/.codex/attachments/f4f63406-df26-4e82-8167-6bc30285ea2a/`, images 1–11. All inspected. Image 4 confirms real BitChord artwork/playback is already connected. Keep existing music controls working.

## Boundaries

- Work only in this Orbit project; `C:/HA/HEALTH APP` stays untouched.
- Update installation is authorised; preserve app data and signing key.
- Latest user instruction authorises a short physical phone check AFTER all six changes are finished, browser-verified, and installed; then hand back for the user's full sweep. Galaxy: `192.168.0.210:38797`. Preserve any existing real workout and music session. New sensitive access grants are not implied. Do not touch the other connected device or the paused reconnect heartbeat.
- Use native Android tracking without new dependencies. Never substitute demo body weight, fake GPS, watch, heart rate or calorie data into recorded workouts.
- Maintain old saved workouts. Location access is requested only from an explicit user action in Orbit.

## Recoverable baseline

Source and existing APK copied with matching SHA-256 hashes to `verification/before-six-fixes-20260910-162812/`; manifest included. Previous installed APK SHA-256: `1f105c17e207a9080c39cf5660e7e958400b87905217561bdcb3e7668b7b053f`.

## Progress

- Inspected current HTML, sleep, timer, music and native persistence/notification paths. Native timer already uses monotonic time and atomic session writes; notification currently has no periodic data refresh. Build must explicitly compile Java as UTF-8 to fix garbled separators.
- Started independent official Android API research for MetricStyle and foreground location tracking. Root owns visual integration and acceptance.
- Next: selection/sleep changes; focus timer/art integration; native data/notification; grouped workout details; icon; browser/native checks; build and update install if Galaxy remains connected.

## Verification still required

Exercise selection exclusions, sleep scrubbing, focused timer touch/reduced motion and lifecycle, artwork metadata changes and missing art, workout pause/resume/history/target and tracking data validation. Run existing checks plus focused new behavior checks; build/sign and verify packaged source. Record actual installation separately from user-owned physical testing.

- Step 1 implemented: non-editable app text no longer selects or opens its context menu; input editing stays available. Removed the sleep cursor and dotted horizontal guides. Scrubbing and stage highlights retained. Particle renderer now supports a wide clock and resumes correctly after visibility changes; focus wiring follows. Verification pending.

- Step 2 implemented: focused timer now mounts the countdown particle engine in a wide clock; touching digits disperses/reforms them without exiting focus. Full-page artwork backdrop follows the current media art and clears on permission loss/exit. Removed focused orbit, retained accessible exact time, keyboard focus toggle, reduced-motion behavior and separate workout actions. Browser/layout validation pending.

- User visual feedback: rejected the circular music-app launch button (clipboard image 457d7d5b). Removed its filled circle; retained a small plain launch icon and 44px touch target. This correction is part of the current six fixes.

- Step 3 implemented: charcoal/silver adaptive launcher icon plus monochrome variant. Added grouped recorded-workout details and history destinations, time/pauses/target, GPS route/speed/elevation views, explicit missing-data states, and optional weight-based energy estimate. Formula verified against the Adult Compendium: MET × kg × 3.5 / 200 × active minutes; Walking 3.5, Running 7.5, leisure Cycling 4, Strength 3.5. No demo weight reused. Native tracking implementation remains in progress.

- Integration checkpoint: existing Node regression check passes after workout-details integration. Updated focus harness now checks no focused orbit, touchable particle clock, full-page artwork and plain launch control. Galaxy is currently absent from `adb devices`; only the excluded BYZL device is attached. No phone UI or permission actions performed.

- Verification checkpoint: 320px focus/player check passes the new particle/no-orbit/full-page-art/no-bubble checks. Timing check now awaits real animation completion, avoiding a fixed-500ms scheduling race. Read-only reconnect to the prior Galaxy address succeeded; manufacturer Samsung, model SM-S938B, API37 confirmed. Found and fixed an older elapsed helper assumption so completed records without resumedAt render their saved time correctly in the new history view.

- Latest user steering: confirmed Galaxy connection and explicitly requested finish all work → own browser checks → install → quick phone checks for likely issues → hand back for full sweep. This supersedes the previous blanket pause on physical UI checks, but not data preservation or the other-device boundary.

- Browser regression found new metric rows pushing live Pause/Finish below the viewport (bottom913px in844px screen). Reduced only the regular timer from350px to250px; focused timer retains its large responsive clock. Rerunning affected navigation/layout flow. 390px focus preset passed (actual389px from preview zoom rounding), including independent music actions and teardown.

- Review checkpoint: root browser navigation passes at320/390; record harness corrected to target in-page Finish rather than the separate bottom-bar action and to allow measured0.01px scaling rounding. Independent frontend review found inaccessible hero readings and focus loss on GPS refresh; added accessible values and retained focused actions. Native review found slow-walk distance filtering, below-sea-level altitude validation and notification shutdown issues; worker is correcting them before build.

- Browser acceptance: workout-details rendered harness PASS, including actual in-page start/countdown/permission retry/pause/resume/Finish/history, old records, route/speed/elevation gaps, valid energy calculation, noneditable context menu/input exception, sleep highlights, accessible main reading and focus preservation. Summary top and bottom visually inspected. Preview data restored on both isolated test origins. Browser work complete; native fixes/review/build/install still pending.

- Native review checkpoint: direct notification Resume must enter the activity before restarting GPS, avoiding Android's blocked broadcast-to-activity notification trampoline. Precise-location requirement will be explicit so approximate access does not leave the screen searching indefinitely. Launcher/notification rendering remains neutral charcoal and silver; API37 metric clocks use Android's own stopwatch/timer values.

- Installed checkpoint: signed build20260910-172430 installed with adb install-r on GalaxySM-S938B API37. APK d7327ec251888a0dd2f6c1725d460f76fe5f61e5c393e54cd18c6bd13ba32fc7; installedAPK hash matches. Signing certificate matches previousbuild. Launched Orbit successfully and visually inspected Home. Phone then locked; asked user to unlock before continuing the authorised quick sweep. No test workout started or location permission granted. Native check retained atverification/native-check.ps1.

- Phone checkpoint: user unlocked and had already granted GPS during their own Walking test. Existing seven sessions remain, and the real GPS summary/route render. Agent started one time-only Strength verification session with a one-minute target, no weight and no GPS. Focused particles, real BitChord artwork, plain launch icon and neutral native notification render correctly. Android let the target TimeDifference run below zero while Orbit was backgrounded; changed that metric to a fixed target duration, retaining the dynamic main stopwatch. Rebuilt and native check passed. Wireless debugging then went offline/refused the old port; no new mDNS endpoint found. Asked user to reconnect. The owned Strength test is still active; finish it after reconnect. Final corrected APK: b2a8bc59f19dcf3aa4bc814df795686b11229b19375db92e0f828db405fd0aee.

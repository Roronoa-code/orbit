# Orbit — artwork quality and full motion

Updated: 10 September 2026, 18:29. Status: artwork quality, focus transitions and always-enabled motion implemented, checked and installed. Current APK SHA256: `bf8824404f675373c67bdacaada669d723ede4414260321b6c43f169bf0d6526`; installed hash matches. The user is reviewing focus on the phone. The third agent-created Strength verification session remains active at the last read; two earlier verification sessions were completed. Finish this owned test after the user's current review.

Current evidence: `verification/art-quality-motion-result.json` and `verification/six-fixes-phone/`. Previous six-fix proof is preserved in `verification/six-fixes-result.json`. Two completed owned Strength test records remain (17:34, active09:13;17:51, active01:42), plus the current 18:23 verification session. Existing seven records were preserved. No location or music access was granted by the agent; the user had already granted access. Music playback was left alone. Outdoor GPS accuracy, broad music-app compatibility and Watch/native Health integration remain separate qualification.

Latest question: can Orbit preload the next one to three songs to avoid the artwork delay? Read-only diagnosis found the JavaScript player polls every1000ms; native metadata callbacks only mark data dirty, and completed artwork waits for another poll. BitChord currently exposes a36-item queue and current queue item id through Android. The queue's actual artwork availability has not been inspected. Immediate native-to-page updates and preloading up to three accessible queue covers are proposed, not implemented; audio buffering belongs to BitChord.

## Current request

Follow-up corrections: preserve the liked full-width music composition, improve artwork quality and add visible transitions. The user then explicitly rejected automatic reduced-motion behavior. Full orb, particle, card, page and player motion now stays enabled; manual orb pause and background/offscreen cleanup remain.

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
- Latest user instruction authorises a short physical phone check AFTER all six changes are finished, browser-verified, and installed; then hand back for the user's full sweep. Galaxy: `192.168.0.210:39389`. Preserve any existing real workout and music session. New sensitive access grants are not implied. Do not touch the other connected device or the paused reconnect heartbeat.
- Use native Android tracking without new dependencies. Never substitute demo body weight, fake GPS, watch, heart rate or calorie data into recorded workouts.
- Maintain old saved workouts. Location access is requested only from an explicit user action in Orbit.

## Recoverable baseline

Source and existing APK copied with matching SHA-256 hashes to `verification/before-six-fixes-20260910-162812/`; manifest included. Previous installed APK SHA-256: `1f105c17e207a9080c39cf5660e7e958400b87905217561bdcb3e7668b7b053f`.

## Progress

- Inspected current HTML, sleep, timer, music and native persistence/notification paths. Native timer already uses monotonic time and atomic session writes; notification currently has no periodic data refresh. Build must explicitly compile Java as UTF-8 to fix garbled separators.
- Started independent official Android API research for MetricStyle and foreground location tracking. Root owns visual integration and acceptance.
- Next: selection/sleep changes; focus timer/art integration; native data/notification; grouped workout details; icon; browser/native checks; build and update install if Galaxy remains connected.

## Verification coverage

Browser and native checks passed for selection exclusions, sleep scrubbing, focus lifecycle, metadata changes/missing art, workout pause/resume/history/target, tracking validation and small-phone layouts. Final build/signature/source packaging passed; the installed hash matches. Physical scope and remaining qualification are summarised above; progress entries below are chronological evidence.

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

- Latest visual steering: use the two new clipboard references for a full-width cover that fades into the screen, reduce timer clutter, hide the entire player when no track exists, and remove pace from the live notification. Paused tracks still remain controllable. Keep connection access in workout setup. Current source and APK backed up before these refinements. The owned Strength verification session was finished after reconnect (09:13 active); existing records preserved.

- Revised music acceptance: full-width artwork begins at the page edge and fades behind the smaller particle clock, with extra timer labels removed. No track hides the complete player while polling continues; paused tracks stay available, missing artwork retains real controls, and Music access is available from workout setup. Existing rendered checks PASS at320 and390 (actual389), including automatic rediscovery, input validation, stale seek rejection and separate actions. Workout flow, Node regressions and native checks PASS. Signed build20260910-174753, SHA256 b0d7dae4437b69563b8a74cf1867dc6e120bdac9eb7f29de15dfcf3b4e7db012; source packaging verified. Installing for final phone check.

- Final phone acceptance: installed final APK and verified its hash. Full-width real BitChord artwork renders with no boxed cover or extra timer labels. Target stays00:01:00 at duration01:18 (previous negative countdown fixed). Notification Pause freezes01:28, direct Resume returns to Orbit, and Finish records01:42 active/02:22 total/00:39 paused. Sleep summary long press produces no selection menu; timeline swipe changes Awake22:30–22:40 to Light06:45–07:30 with the interval highlight and no dotted cursor. Finished both owned test sessions, preserved existing history, restored browser fixtures and returned phone to collapsed Steps Home. Native Health project and paused heartbeat untouched. Work is ready for the user's full sweep.

- New user feedback: the full-width music layout is liked, but artwork quality is too low and transitions appear missing. Confirmed native artwork is capped at512px/JPEG85, local full-resolution artwork URIs are ignored when a thumbnail exists, and metadata artwork compression blocks the JavaScript read. Focus animation transforms the media parent, temporarily changing the absolute cover's positioning; track changes replace images immediately. Preserve the approved composition, raise bounded artwork quality, load it asynchronously, isolate the artwork layer, and add visible entrance/exit/crossfade motion. Current source and APK separately backed up.

- Artwork/motion checks: browserPASS at320/390(actual389), measuring intermediate artwork opacity, stable page-edge placement during movement, decoded1600px artwork, outgoing-layer cleanup, focus exit/reversal, and preserved player/clock on pause, reading changes and foreground return. Reduced-motion branchPASS. Existing workout flow, Node regression and native bounds/aspect/no-upscale checksPASS. Android build signed and current bundled sources verified; installing for a short real-phone motion/art check.

- Current explicit user preference: remove automatic reduced-motion behavior. Keep Orbit's full animations enabled. This overrides the skill's reduced-motion default for this personal app. Remove motion-preference gating across Home orb/deck, particles, page transitions and music; retain background/offscreen cleanup and the user's explicit orb pause control. Source/APK backed up before removal. Artwork/transition build17ff0259 was installed but is superseded by this preference change; final phone check awaits the new build.

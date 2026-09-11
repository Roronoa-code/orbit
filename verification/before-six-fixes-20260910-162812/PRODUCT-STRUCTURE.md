# Orbit: a complete personal health app

Direction confirmed by the user, 10 September 2026: replace their everyday Samsung Health workflow. The orb is the home view of that app. It must lead into substantial histories, activity starters, records and device controls without forcing all the data onto Home.

## Where everything belongs

| Area | What belongs here | Main actions |
| --- | --- | --- |
| Today | Selected metric and period, a compact summary, recent changes, current activity | Tap orb for period; swipe for metric; unfold details; return to an activity |
| Health | Body, heart/oxygen, sleep, daily activity, food/water and supported measurements | Open a full page; inspect trends and records; log or measure where supported |
| Activity | Favourite starters, supported workout types, running and completed sessions | Start, pause, resume, finish; review a session; resolve interruptions |
| Trends | Longer history and comparisons across related metrics | Compare periods with coverage; open original observations |
| Devices & Settings | Watch, sources, permissions, goals, units, appearance, backup/export and storage | Review connection and freshness; choose sources; manage preferences and history |

The three flat launcher shortcuts open Body, Workouts and Health overview. The raised circles and wrapped labels were rejected; the launcher now shares the stack's charcoal material. Oxygen is a compact expandable tile in the overview, not a separate page. The overview no longer repeats Heart, Sleep, Intake or Steps; those stay on Home. Tap the Sleep summary on Home for a timed night view.

The user rejected log-first pages. Lead with a visual summary and a few useful values. Body has a chart and date scrubber; completed workouts use a recent summary with history behind an explicit expansion. Future real records must retain source, time, quality and correction paths. Sparse measurements remain sparse.

## Live activity behaviour

The idle bottom area is a small health launcher, as requested. An app-owned active session turns it into a live activity bar. A tap expands contextual controls; the three page shortcuts remain reachable. Ending a session restores the launcher. Page navigation must not stop a session.

Samsung describes its Now Bar as appearing while a supported app is active, expanding on tap, and allowing another active item to be reached by swiping up. Orbit uses that ongoing-activity model inside the app. The new Android build also posts an ongoing workout notification and requests Android Live Update promotion. The user has supplied an actual Samsung Now Bar screenshot; the latest presentation remains for their phone review. [Samsung Now Bar guide](https://www.samsung.com/uk/support/mobile-devices/how-to-use-the-now-bar-on-the-lock-screen-of-your-samsung-galaxy-device/)

In the real app, start with owned workouts and deliberate live heart-rate sessions. Measurement progress can become another activity when it has an actual running state. If several are present, show the selected session with a clear way to switch. Never call a historical reading “live”. Disconnected or stale sessions need explicit states instead of extrapolated sensor values.

## Existing native Health app

Read-only source map, 10 September 2026. This is not a fresh runtime or physical-sensor qualification. The native Health app was not modified during this design revision.

| Domain | Existing source evidence | Connection or remaining work |
| --- | --- | --- |
| Pages and routing | `HealthAppScreen.kt` lists Today, Trends, Activity, Heart, Sleep, Steps, Oxygen, Vitals, Food, Body, Weight, Muscle, Fat, Walk, Devices, records and settings. | Adapt the accepted Orbit presentation and preserve Back, ranges and scroll state. |
| Body | `SignalScreens.kt` provides weight, muscle, fat mass, BMI, fat-free mass, body water, basal metabolic rate, trends and records. | Carry these into the full Body page with real measurement dates. The standalone page uses samples. |
| Food/water | `SignalFoodScreen.kt` and its journal provide intake totals, goals and logging. | Reuse the journal flow, checked saves and separate demonstration storage. |
| Workouts | `Workout.kt` models Walk, Run, Treadmill, Bike ride, Indoor bike and Other workout. `HealthServicesWorkoutSource.kt` has capability checks and owned Start/Pause/Resume/End. | The phone's `WorkoutsScreen.kt` currently directs controls to the Watch. A phone starter needs a supported command and acknowledgement flow; the standalone timer does not provide that connection. |
| History/live/measurements | `HealthRepository.kt` supplies committed history, current live-heart state, saved measurements, measurement requests and diagnostics. | Connect the live bar while preserving capture time, contact, quality, freshness and ownership. |
| Imports | `HealthImports.kt` lists heart rate, sleep, steps, exercise, resting heart rate, reported RMSSD, oxygen, temperature, reported Energy Score, active time, distance and energy. | An import contract does not prove a device/provider supplies it. Show missing data honestly and qualify acquisition. |

Source root: `C:/HA/HEALTH APP`. The latest `NEXT-UI-CHAT-HANDOFF.md` is newer than the older paused-state summaries and records the native Signal presentation. The state and handoff documents distinguish implementation from outstanding physical validation; retain that distinction during integration.

## Build order toward replacement

1. Bring accepted navigation and materials into the native app using existing history and journal data. Preserve real/demo separation and Watch ownership/sync.
2. Connect the live bar to owned sessions, including paused, waiting, stale, disconnected, ending and recovery states. Verify return-to-session and restart behaviour.
3. Complete the activity starter: supported types, Watch availability, permission/review steps, start acknowledgement, recovery, pause/resume/end and saved session details.
4. Complete the Health library with ranges, full records and relevant log/measure actions. Expose Trends and Devices/Settings without crowding Home.
5. Verify the daily replacement workflow on phone and Watch: acquire → save → transfer → display → history → resume activity → backup. Source code or demonstration screens alone do not establish completion.

Further categories to assess include routes/laps/zones, routines and targets, body measurement entry, sleep review, recovery/stress, temperature, ECG/BP, medication and other personal logs. They belong in the library or activity flow when supported and requested. Samsung-specific scores must not be replaced with invented readings or assumed available through an unverified API. Samsung's product overview covers sleep, exercise, food and body composition. [Samsung Health](https://www.samsung.com/uk/apps/samsung-health/)

## Current standalone delivery

Implemented in the standalone copy: orb period taps and metric swipes; expanding cards and inline utility controls; visual Health overview with compact oxygen; chart-first Body with 7/30-day date inspection; open or timed workouts with countdown, pause/resume/finish and saved history; a dedicated live timer in the Home bar; Android notification actions backed by the same committed workout state; and immersive full-screen hosting. The latest redesign/notification APK was installed successfully after reconnect. The user is now testing it; automated phone checks are paused and the latest appearance remains for user review. The earlier immersive-only build was physically verified.

Timer records stay in this copy. GPS/Watch capture, phone-to-Watch workout commands, real history integration and replacement of the native app remain separate work. See `verification/PHONE-RECONNECT.md` for the exact remaining physical checks.


## Latest presentation decisions

Workout focus is an explicit exception to the simple page fades: the existing dot timer moves and shrinks into the top while an artwork-led music player appears below. Preserve timer identity and independent workout/music controls. The active workout's large walking graphic and slogans were rejected; use a tappable dot constellation with concise state labels. Other activity-selection glyphs remain.

Music controls operate the phone's shared active media session after the user enables Orbit's notification access. Do not fake an active track or add decorative unsupported playback controls. Album art sets the player's colour, with honest no-art/permission/no-session/error states. The implementation is bundled and browser-tested; actual phone playback still requires the user's access grant and compatibility review. No phone settings or media sessions were operated during verification.

Dot matrix belongs on focal readings, with ordinary supporting text. Body uses a flat, smoothly interpolated shaded chart; do not restore the rejected raised-strip graph or spread a new chart treatment elsewhere without feedback. Sleep stage selection highlights every matching interval; retain the separate exact-time cursor and each interval's duration. Current chronology is labelled demonstration data, not a reconstruction of real observations.

The app has one continuous background, a frosted dock with content visible underneath, and short fades/slides for opening things. The bottom bar stays anchored when the launcher opens. Do not restore large source-to-page shape morphs or cloned-page transitions. The interactive countdown and direct Home gestures remain.

Current sources, signed artifact and rendered verification are recorded in `verification/hero-sleep-frost-result.json`. The update is installed; automated phone UI testing and the reconnect heartbeat remain paused for user testing.

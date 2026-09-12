# Orbit: a complete personal health app

Direction confirmed by the user, 10 September 2026: replace their everyday Samsung Health workflow. The orb is the home view of that app. It must lead into substantial histories, activity starters, records and device controls without forcing all the data onto Home.

`HANDOFF.md` is the authoritative current entry point for build status, hard visual/interaction constraints, rejected treatments and phone-control boundaries. Read its superseding decisions before using older design evidence. The product direction below is a roadmap, not a claim that real Health/Watch integration is complete.

## Where everything belongs

| Area | What belongs here | Main actions |
| --- | --- | --- |
| Today | Selected metric and period, a compact summary, recent changes, current activity | Tap orb for period; swipe for metric; unfold details; return to an activity |
| Health | Body, heart/oxygen, sleep, daily activity, food/water and supported measurements | Open a full page; inspect trends and records; log or measure where supported |
| Activity | Favourite starters, supported workout types, running and completed sessions | Start, pause, resume, finish; review a session; resolve interruptions |
| Trends | Longer history and comparisons across related metrics | Compare periods with coverage; open original observations |
| Devices & Settings | Watch, sources, permissions, goals, units, appearance, backup/export and storage | Review connection and freshness; choose sources; manage preferences and history |

Implemented Settings now has its own page, opened by the Home gear: name, birth date, height, weight, daily goal, motion, music access and About. Saved profile weight prefills workout setup; each session retains its own weight. Profile data is distinct from demonstration Body measurements. Watch, units, backup/export and connected sources in the table remain future work.

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

The full 12 September video-audit repair is installed. Current evidence and limits: `verification/video-audit-20260912/RESULTS.md` and `HANDOFF.md`. The user authorized separate Audit phone workouts/media tests; production history was preserved. Full-screen artwork with fixed-size controls is the explicit current choice.

Body has animated metric/range selection and 7D/30D/3M/1Y history. Show the 90 available demo records at their actual dates within the year; do not invent earlier readings. Home has animated orb selection, a temporary chart badge and a softened scrolling edge.

The bottom launcher is one upward-growing clipped surface with a fixed bottom, staged controls, stable-size icons and no duplicate status dots. Its blur texture remains at its full size; timer/state updates change only values that actually differ. Preserve the charcoal material and the clear Pause pill.

Workout pause/resume retains the timer and player, with a slight brightness/size change around a frozen clock. Focus retains touch-responsive dot numbers and full-width real artwork. Keep workout and music controls independent. Missing track/permission/error hides the player; missing artwork retains real metadata and supported controls. Full motion is the default, with manual orb pause, a persistent Reduce motion option and background/offscreen cleanup.

Music now pushes metadata/artwork changes into the page, decodes artwork off the UI thread and prefetches up to three eligible accessible local queue covers. The checked BitChord queue exposed no eligible upcoming local art, so this provider did not populate the prefetch cache. Current artwork is bounded at 2048 px/JPEG 94, without enlargement or remote downloads. Audio buffering stays with the music provider.

Workout records remain local to this copy. Optional native phone GPS and user-entered session weight support grouped route/movement and labelled estimated-energy details. Preserve old histories and never use sample weight or synthetic GPS as real records. Watch controls, native health-history integration and physical outdoor accuracy remain separate work. Preserve the charcoal/silver app icon, flat shaded Body chart, compact Oxygen overview and Sleep stage/interval interaction; do not restore rejected dotted cursors or page-clone morphs.

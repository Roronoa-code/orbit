# Orbit — current standalone copy

The seven UX fixes and subsequent live-bar appearance/background corrections are installed on the Galaxy SM-S938B. The user requested that further phone testing stop and will review the installation. Current build: `android/build/20260911-005538`; APK SHA256: `5eec8e21feee715220995be14b76c16ba003a4e572677a01ce944be8c20503dc` (music switching fix plus the performance pass, installed on the phone 11 September). The phone still runs `6dfb41a4e867e51cd67b9983e30db71ca922c02c41c112fbcdd325f8f72ad883` from `android/build/20260910-194200`; see the music switching section in `HANDOFF.md`. Current evidence is in `verification/seven-ux-result.json` and `HANDOFF.md`.

**Before continuing UI work, read the hard design constraints, superseded decisions and reference guidance in `HANDOFF.md`.** They consolidate the user's current preferences and rejected treatments. Keep that handoff current after each work batch. The user owns the next phone review; do not resume device checks or the reconnect automation without a new request.

## Current behaviour

- Pausing preserves the timer, particle canvas and music player. The clock freezes, darkens to 62% and shrinks to 96.5%; resuming restores it smoothly.
- The bottom launcher grows upward as one clipped surface, with a fixed bottom edge and controls that enter after the shell grows. Reversals preserve movement. Duplicate status dots and the old grab handle are removed; icons retain their normal size and Pause uses a clear filled pill.
- The live blur texture keeps its full size while the shell reveals it. Timer ticks update changed text only; unchanged native state refreshes no longer relayout the launcher or the background cards.
- Orb period taps and metric swipes animate the retained globe and reading. The chart inspection badge fades after use. Scrolled cards fade at their leading edge into the continuous background.
- Body has animated metric and range selection, 7D / 30D / 3M / 1Y charts and exact-date inspection. The 90 available demonstration records occupy their real dates within the year; earlier history is visibly empty.
- Focus keeps touch-responsive dot numbers over full-width actual album artwork. Current metadata and artwork changes reach the page through native events, with polling retained for progress/fallback. Artwork decoding runs off the page thread and new decoded covers crossfade.
- Walking, Running, Cycling and Strength retain setup, optional targets, countdown, pause/resume/finish, saved history and grouped details. Android workout notification actions share the saved session state. Sleep retains stage/interval selection and its labelled chronology; noneditable app text does not open copy/translate controls.

The user explicitly wants full motion. Preserve manual orb pause and hidden/offscreen cleanup. Do not restore automatic system motion-preference suppression. Keep the charcoal/silver launcher icon, flat page shortcuts, compact Oxygen overview and flat shaded Body chart.

## Data and music boundaries

Health measurements remain labelled demonstration data ending 8 September 2025. App-started workouts are actual local records. Optional phone GPS records route, distance, speed and available elevation; optional user-entered workout weight supplies a labelled MET energy estimate. Demo body weight is never used for actual workout energy. Watch/native Health data integration and physical outdoor accuracy remain separate work. `C:/HA/HEALTH APP` was not modified.

Music uses the phone's real shared media session after the user enables access. No track, permission failure or errors hide the player; a real paused track remains visible. Missing artwork retains supported metadata/controls. Current artwork uses the best shared bitmap or accessible local content source, bounded at 2048 px/JPEG 94 without enlargement or remote downloads. When a track changes, the previous cover stays on screen until the new cover is ready; only if no cover arrives within two seconds does it fade out.

The cache can warm up to three upcoming covers when the provider exposes accessible local queue artwork. It keeps at most four encoded covers and discards stale work. The checked BitChord queue exposed no eligible upcoming local covers, so zero upcoming covers were cached; audio buffering remains the music app's responsibility. The read-only phone check measured a 1 ms initial bridge read, a later artwork-ready event and a 544 × 544 current cover. Agent checks did not control music or grant permissions.

## Verification and recovery

Existing Node logic checks and native artwork/altitude/token/distance checks pass. Rendered UX, focus/music and workout-detail flows pass at 390 and 320 px. `verification/check-live-background.cjs` checks stable blur dimensions, timer-only DOM changes through repeated state refreshes and unchanged background pixels during reversals. Browser checks use isolated contexts without user workout data.

Build: `python android/build.py`. Preserve `android/signing/orbit-local.p12` for compatible updates. Scripts, CSS and font are bundled offline; compilation, v3 signing, bundled-source checks and the installed APK hash passed. The build saves the previous APK.

Deck fold, live-bar growth, orb drawing and Home renders were profiled and made compositor-friendly on 11 September without changing their look; see the performance section in `HANDOFF.md`. Run `node verification/check.cjs`, `node verification/workout-details.cjs` and `verification/native-check.ps1`. Rendered checks use the installed Playwright package on `NODE_PATH` and the local server at port 8784: `node verification/run-rendered-checks.cjs ux-motion focus-music workout-details`; the focused background check also uses installed sharp.

An earlier short phone sweep on the preceding build checked timer pause/resume, actual artwork, launcher expansion, finishing and saved history. Its owned Strength record lasted 03:31 active time and was finished. The final background patch is installed and launched, but its running-workout phone sweep was stopped at the user's request. No additional workout was started. User visual acceptance is pending.

Recovery: `verification/before-seven-ux-fixes-20260910-183639`, `verification/before-live-bar-review-20260910-191140`, and `verification/before-live-background-20260910-193826`, with checked source backups. Earlier evidence remains historical. The reconnect heartbeat was not changed.

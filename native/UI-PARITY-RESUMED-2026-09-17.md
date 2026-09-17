# Orbit phone UI pass — resumed 17 September 2026

Continues `UI-PARITY-PAUSED-2026-09-15.md`, which stopped mid-pass at the owner's usage limit. That
document's "Exact next batch" items 1, 2 and 4 are now done; item 3 is partly done and item 5 is not
started. This is a handoff, not final design acceptance.

Design authority is unchanged: the HTML packaged in `../dist/Orbit.apk`, extracted to
`../verification/ui-parity/reference.html`. The original APK and HTML source were not edited.

## Starting state

The tree was untouched since the pause: `dev.py doctor` reported source
`7f4e0397664b485de0277a524c8e620d7a009fb55daba5f93d091e1c2cc0bd41`, exactly the fingerprint recorded in
`../verification/ui-parity/paused-state.json`. No file in the workspace was newer than 15 September 23:38.

## What this batch changed

Production source (three files):

1. **`MusicControls.kt` — a collapsed, invisible seek strip swallowed taps on the track text.** While the
   player is compact the heading sits at `musicBottom - 234.dp` (56.dp tall) and the timeline box at
   `musicBottom - 220.dp` (40.dp tall), so they overlap by 40.dp, and the timeline is drawn later. Compose
   hit-tests the topmost sibling only, so a tap in the middle of the track title reached the seek canvas —
   which is alpha 0 and disabled at that point — and the heading's own tap detector never saw it. The seek
   canvas now attaches its pointer input only while it is `visible`, so a hidden control no longer takes a
   touch away from the heading.
2. **`WorkoutPlayerMotion.kt` — releasing the canvas drag detector undid a focus change its own child had
   just made.** When a child consumes the gesture, `workoutPlayerDrag` breaks out of its loop with
   `released == false` and its `finally` settled the material back to the pose it had at contact, cancelling
   the open animation the heading tap had started one event earlier. It now settles back only when the
   child did not change `player.expanded` during that gesture.
   Both changes are required: with either one reverted the new tap check below fails.
3. **`OrbitApp.kt` — the Home header date now reads `EEE d MMM` ("Mon 14 Sept").** The reference header
   button is `dateLabel(selected)` with `{weekday:'short', day:'numeric', month:'short'}`; the native header
   was showing `d MMM yyyy` ("14 Sept 2026"). The Health and Sleep pages keep `d MMM yyyy`, which is what
   `health-dashboard.js` uses for their intro line — that pair was already correct.

Test source (three files):

4. **`PhoneVisualParityTest.kt`** — the two branches the pause note flagged as built but never run are now
   resolved. The Train tab's activity tile is selected by its own `choose-Walking` tag, because the Last
   workout card below it carries the same "Walking" text; and the Explore capture now uses the gesture
   `ExploreIslandTest` already proves (`swipe(center, center + Offset(0f, -240f), 450)`) instead of a short
   `swipeUp` inside the 62.dp bar, asserting the bar's `Expanded` state and a displayed `explore-choices`
   row before the capture and `Collapsed` after the reversing tap.
5. **`MusicTest.kt`** — new check `tappingMusicTextAndTimerOpensAndClosesTheFocusedPlayer`. It taps the
   track text, then the timer twice, asserting `player.expanded` and a settled `motion.value` each time.
   It was written before the fix and reproduced the defect.
6. **`NativeMigrationTest.kt`** — two stale assertions. The 15 September pass replaced Home's bitmap dot
   alphabet with `OrbitDottedText` (real Manrope text under a dot mask), so the reading no longer carries a
   `contentDescription`; the checks now match its text. This failure predates this batch: it reproduces with
   all three production changes above reverted.

Nothing else in the app was changed. No data behaviour, no backend, no Watch source, no HTML.

## Verification

All runs are on `emulator-5580` (HealthAutonomousApi36, `debug.hwui.renderer=skiavk`), the documented AVD.

| Evidence | Result |
|---|---|
| `../verification/native-runs/20260917T215205.311308Z-phone/manifest.json` | **Full phone module: 112/112 passed** on source `1e40f228d1f3a9cf4d665ff0be71788569ca1bfc276d497758c953c6321b468d`, with developer checks, build, install and artifact/identity verification. The working tree still matches that fingerprint. |
| `verification/ui-parity-resume-20260917/20260917T214810Z/` | Parity, Home, Header and migration run: the parity capture set regenerated; the only failure was the pre-existing `NativeMigrationTest`. |
| `verification/ui-parity-resume-20260917/20260917T214243Z/` and `20260917T214358Z/` | The music-tap defect reproduced before the fix, and again with only the `WorkoutPlayerMotion` half applied. |
| `verification/ui-parity-resume-20260917/20260917T215520Z/` | The defect reproduced again with the `WorkoutPlayerMotion` half reverted, which is why both changes are kept. |
| `verification/ui-parity-resume-20260917/20260917T214810Z/captures/` | 16 full-shell captures from the real `OrbitApp` theme and shell. |

Capture evidence for the music defect: before the fix `parity-workout-live.png`, `parity-music-open.png`
and `parity-music-closed.png` were byte-identical (SHA-256 `35faab08…`) — the focused player never opened.
After it, `parity-music-open.png` is `7568a3a5…` (page-wide artwork, small clock, timeline, transport) and
`parity-music-closed.png` returns to `35faab08…` exactly, so the reversal is pixel-identical to the
starting pose.

`verification/ui-parity-resume-20260917/run.py` is the focused runner for this batch. It reuses `dev.py`'s
toolchain, source snapshot, signing and installed-hash checks, and takes test class names as arguments.

## Review carried out, and what it did not cover

Reviewed against the reference and the twelve constraints: Home (orb, folded deck, Explore launcher),
Home expanded with Explore open, Health cards, Measurements with a single sparse reading, Sleep, Settings,
Workouts Train, History, saved record, setup, target, live workout and the focused music view.

Three things that looked wrong on first reading were checked against the approved HTML and are correct:
the Home back chevron (it appears because the fixture pins a past date, and returns to today), the Sleep
stage palette (identical hexes to `sleep-timeline.js`) and the dashed chart grid, and the workout setup copy
"Go at your own pace. / Finish whenever you're ready." (verbatim `setup-open-note` from `health-pages.js`).

Not covered, still open from the paused note's item 3: the date picker, the additional phone Watch-reading
screens, Measurements with varied and dense readings, and a deliberate narrow-width and large-text sweep of
every route. Do not claim these were visually matched.

## Boundaries

No physical phone or Watch was connected, controlled, installed to or tested. The phone still carries the
15 September native development build described in `WRAPUP-2026-09-15.md`; packaging and installation
(item 5 of the paused note) remain to do and the debug signer still cannot upgrade it in place. The other
connected device was never addressed. `../dist/Orbit.apk` is unchanged. No emulator data was wiped.

Recoverable pre-edit copies of all six edited files, with a SHA-256 manifest, are in
`verification/ui-parity-resume-20260917/before/`.

The native tree is now tracked in Git for the first time: source, gradle files and documentation only.
`native/verification/` stays local because it holds the private Samsung import checkpoints and the 28 MB
signed APKs, so its paths are ignored alongside the stray root logs and captures.

## Next batch

1. Finish the item 3 review above — date picker, phone Watch-reading screens, varied Measurements data,
   narrow width and large text — then repeat the directly affected checks.
2. Only then package: a release build signed with `../android/signing/orbit-local.p12`, matching the
   certificate already on the phone, and ask before installing.
3. The research addendum (`RESEARCH-ADDENDUM-CHECKLIST.md`, 16 unchecked items) stays out of scope until
   the UI pass is accepted, per the paused note.

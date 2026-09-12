# Player and Settings corrections — 12 September 2026

The first desktop phase followed the user's screenshots and phone hold, from clean Git commit `4fab983`. The user subsequently authorised installation and testing. The physical follow-up below starts at `251fa9b` and supersedes the earlier not-installed status.

| Request | Change | Verification |
| --- | --- | --- |
| Remove clock border | Deleted the timer scrim and its transition code | Reviewed compact/focused desktop captures; no scrim node in source or packaged app |
| Remove top cutoff | Artwork starts at the page edge; only foreground header reserves the system inset | Packaged page: artwork top 0; back control top 44 with a 34 px inset, at 390/384/320 px |
| Improve cover quality | Full cover at screen width instead of tall-page enlargement/cropping; supplied full-resolution image retained | Rendered source preserves 1600 px artwork; native artwork sizing checks retain aspect ratio, cap at 2048 and never upscale |
| Animate transport | Directional previous/next movement, play/pause rotation/fade, immediate activation feedback; fixed button targets | All three controls animate; interrupted animations clean up; reduced feedback uses opacity; fixed-size transition checks pass |
| Remove top-right down button | Deleted markup, styles and state management | Swipe-down works; existing timer button remains the keyboard alternative |
| Dedicated saved Settings | Home gear opens profile, goal, motion, music access and About | Profile reload, validation, failed save, corrupt-data preservation, draft preservation, date/back navigation and settings entry after launcher changes pass |
| Reuse personal data for workouts | Persisted optional name/birth date/height/weight; saved weight prefills all four activities | Four activity defaults pass; session override is recorded without changing profile weight or previous sessions |
| Remove Home black line | Deleted the overlay fade and unused scroll listener | Reviewed Home capture; no overlay during repeated expand/collapse; comparison content survives scroll/reversal stress |

## Checks completed

- `node verification/player-settings.cjs`: PASS at 390, 384 and 320 px, including a second run against the exact packaged asset via `ORBIT_PREVIEW_URL`. Uses temporary browser storage and synthetic media only. Results: [checks.json](checks.json).
- `node verification/run-rendered-checks.cjs ux-motion focus-music workout-details frosted-system`: all 12 suite/width combinations PASS.
- `verification/check.cjs`, `workout-details.cjs`, `blob-math.cjs`, `check-live-background.cjs`, `video-audit-controls.cjs`, `video-audit.cjs`: PASS. Existing frame samples show less than 0.00004 px size change during compact/full-screen transitions.
- `python android/build.py`, `verification/native-check.ps1`, APK signature verification and bundled-source byte/content checks: PASS. `git diff --check`: PASS.
- Desktop captures reviewed: Home, compact player, focused player and Settings, including 320 px layouts. Bulky generated images remain local in this directory.

## Preceding desktop build

Build: `android/build/20260912-210604`. APK: `dist/Orbit.apk`. SHA256: `596272664570bd7c38bbf8d612a3087c1148775cfb1a7174b685284fdab3f801`.

This build was installed when the user released the phone, then replaced by the corrected build below.

## Physical follow-up and final installation

The phone test found that browser storage could report a profile save and lose it after an immediate Android process stop. Profile, daily goal and Reduce motion now share a small native storage boundary: Android SharedPreferences commit acknowledgement and readback, with one-time migration from browser storage. Browser preview behavior is retained. The implementation uses the platform's [synchronous commit result](https://developer.android.com/reference/android/content/SharedPreferences.Editor#commit()). Android's null String appears as JavaScript undefined; the adapter normalises both empty forms. Failed writes and invalid saved data remain explicit errors.

- Nine physical checks PASS on Galaxy S25 Ultra: keyboard; profile/goal/motion after force-stop; saved weight in Walking/Running/Cycling/Strength; native workout start; artwork/top/clock geometry; real play/pause; previous/next and original-track restoration; swipe and interrupted focus with fixed targets; pause/resume and one saved record; larger-text Settings. Grouped results are in `checks.json` and the local `phone/results.json`.
- Full-screen art starts at y=0, header at y=45 CSS px, actual viewport 384 x 832 at DPR 3.75. The supplied BitChord bitmap is 675 x 379. Control dimensions vary by less than 0.00002 CSS px through the sampled moves. Recordings and native captures show the removed clock box, top gap and Home seam.
- The wider phone motion sweep covers launcher reversals, repeated Home period changes, rapid Body metric/range selection, Sleep stages/dates, Health disclosure and date cancellation. All final states and JavaScript error checks PASS. The local `phone/motion-sweep.cjs`, recording and frame sheet retain the procedure and evidence.
- The matched synthetic-cover timing run (no screen recording during measurement) measured 2,433 frame intervals: median 8.3 ms, p95 8.4 ms, 11 over 25 ms, maximum 99.9 ms; Android reports 111 janky frames (9.87%). No traced duration task exceeds 25 ms. Occasional hitches remain; these results do not establish flawless or uninterrupted 120 Hz rendering.
- Final packaged-page regression at 390/384/320 px, `settings-store.cjs`, `check.cjs`, `native-check.ps1`, native build and v3 signing verification PASS. The unit check covers browser fallback, native acknowledgement, undefined empty values, migration, native precedence, rejected writes and key/size bounds. Earlier rendered motion suites remain relevant because this follow-up changes storage, not animation rendering.

Settings follow-up build: `android/build/20260912-220657`; SHA256 `ed2f33505a7a5bdd523f05831e1206244e5c06acb8630de84451121d3f716af0`. Installed in production `com.mani.orbit` and verified by the installed APK's hash, before the later endpoint correction below. Home and Settings opened successfully; the pre-existing Strength workout survived the update. Pre-install APK backup: local `phone/phone-before.apk` (SHA256 `f50b25a1d6983be75c27a5f96b43acec5ec75d535a41538cb7ab78848520ae51`).

The temporary Audit app and listener were removed. Original track and paused state, font scale 1.0, physical density 600 without override and original screen timeout were restored. Owned recording files and port forwarding were removed; no background device work remains. Test interruptions from delayed touch delivery and the first-run notification dialog were resolved in the harness. No personal profile was invented for the main app.

Remaining limits: source-limited cover detail, occasional measured frame hitches, spoken accessibility and broad music-provider/hardware qualification. The screenshots establish layout review; the recorded gestures and frame trace supply separate motion evidence. User visual acceptance remains their decision.

## Follow-up: workout content still resized at the endpoints

The user's next report exposed a gap in the earlier button-only check. `verification/workout-endpoints.cjs` now compares both element boxes and text glyphs immediately before and after the transition hands over to resting CSS. It reproduced:

- The title switched line metrics, font weight and baseline at the endpoint.
- The timer mode selector changed control and indicator height.
- At 320 px, Pause/Resume and Finish labels switched from 12 to 14 px despite fixed button outlines.
- The paused clock used a transformed measurement as an untransformed base, applying the shrink twice. Its hit area and visible clock did not share a consistent geometry.

The correction shares the title's proportional metrics, carries the rounded font baseline offset through the move, keeps timer-selector and action-label sizes consistent, and measures the clock's base independently of its paused scale. The clock container interpolates both dimensions while its glyphs retain their proportions. The visible paused opacity and the destination pause scale are retained, including a transition opened shortly after pausing.

Verification: all 18 desktop width/state/direction cases PASS (390/384/320 px; running, paused, and 80 ms into a pause). Existing focus/music and UX motion suites PASS at all three widths, including drag holds and reversals; existing fixed-control checks remain under 0.00004 px. Native tap-open/swipe-close passes running and paused, with maximum last-frame-to-resting changes of 0.382, 0.241, 0.364 and 0.230 CSS px. The local native recording and frame sheet were inspected. This follow-up uses a generated cover because BitChord no longer exposed an active session; it does not replace the earlier real-music acknowledgement checks.

Latest native timing with the matched generated cover, without recording during measurement: 2,644 frame intervals, median 8.3 ms, p95 8.4 ms, 9 over 25 ms, maximum 108.3 ms; Android reports 97 janky frames (8.00%). The endpoint discontinuities are corrected; occasional frame hitches remain.

Latest installed production build: `android/build/20260912-223127`, `dist/Orbit.apk` SHA256 `2d0b07d8b16457c5715539d891779b7774e1de22e9d4aa0fb179780703d6483f`. Installed hash verified; temporary Audit package/listener and port forwarding removed again. The source/settings work in this report is part of the same final batch.

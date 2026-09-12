# Player and Settings corrections — 12 September 2026

The user's screenshots and subsequent request to leave the phone alone govern this batch. Baseline: clean Git commit `4fab983`. No phone commands, installations, playback changes or device queries were performed.

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

## Prepared build and remaining device review

Build: `android/build/20260912-210604`. APK: `dist/Orbit.apk`. SHA256: `596272664570bd7c38bbf8d612a3087c1148775cfb1a7174b685284fdab3f801`.

**Not installed.** Phone access remains on hold until the user explicitly releases it. OEM system-bar behavior, actual BitChord source artwork, touch feel, device frame timing, keyboard/text scaling and spoken accessibility remain for phone review. The native code already prefers larger accessible artwork; a small image supplied by the music app cannot gain missing detail from CSS. This batch removes extra enlargement and preserves the supplied resolution; it does not claim to have retrieved new high-resolution artwork from BitChord.

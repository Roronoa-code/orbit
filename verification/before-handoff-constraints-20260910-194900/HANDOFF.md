# Orbit — seven UX fixes and live-bar corrections

Updated 10 September 2026. Current signed APK `6dfb41a4e867e51cd67b9983e30db71ca922c02c41c112fbcdd325f8f72ad883` is installed on Galaxy SM-S938B (`192.168.0.210:39389`); installed SHA256 matched. Build: `android/build/20260910-194200`. Final launch returned WARM, 115 ms. The user said: “yo if its installed leave it to me”. Stop phone interaction and further physical checks; the user is reviewing it.

## Completed changes

1. Pause/resume keeps the same timer, canvas and player; frozen time eases to 62% brightness and 96.5% size, then restores.
2. The live bar grows upward with interruptible movement and a fixed bottom. A single clipped shell contains its controls. Both status dots and the old grab handle are removed; icons do not scale during entry. Pause has a filled pill.
3. Orb period and metric selection animate the globe and reading without replacing the canvas.
4. The Home chart value badge fades after inspection while its accessible value remains available.
5. The scrolling card edge fades into the app background.
6. Body metric/range pills slide; 7D/30D/3M/1Y charts preserve actual record dates and show missing earlier data. The 90 sample dates are not stretched over a year.
7. Native metadata/artwork events refresh the player immediately; background artwork decoding and next-three eligible local-cover prefetch avoid extra polling delay. Progress/fallback polling stays enabled.

The last flicker correction fixes two unnecessary redraw paths. A constant-size blur layer is clipped by the growing shell, and unchanged timer/state data no longer rewrites icons, labels, geometry or card styles. Manual motion controls and lifecycle cleanup remain; the user's always-enabled motion preference is preserved.

## Verification

- Node interaction/model and workout-details checks: PASS.
- Rendered UX, focus/music and workout-details checks at 390 and 320 px: PASS. UX reran after the final shared measurement change.
- Background regression at both widths: blur size changed by 0 px during expansion (previously about 226 px); only timer text mutated through repeated timer/native-style refreshes; the sampled unoccluded background was pixel-identical across reversals. The headless browser did not reproduce the original visible flash before the patch, so these checks verify the removed redraw paths rather than physical visual acceptance.
- Native artwork dimensions/aspect/no-upscale, altitude, resume tokens and geographic-distance checks: PASS. Java compile, APK v3 signature and bundled-source verification: PASS.
- Read-only actual media check on the preceding native-identical build: first read 1 ms, artwork-ready event received, current cover 544 × 544, no media transport commands. BitChord supplied no eligible upcoming local covers, so positive provider prefetch remains unqualified.
- Earlier physical seven-fix sweep: focus running/paused/resumed screenshots, actual music art, launcher opening/closing video, Finish/save and history. Its owned Strength test was completed at 03:31 active time; history grew from 13 to 14 records. No unrelated records were removed.
- Latest APK installation and exact installed hash: PASS. Idle Home capture: `verification/seven-ux-phone/flicker-current.png`. No new workout was started; the attempted follow-up check stopped when the user requested control. Running-workout physical qualification of the final patch is pending user review.

Evidence: `verification/seven-ux-result.json`, `verification/seven-ux-browser/`, `verification/seven-ux-phone/`. The earlier `pause-resume.mp4` does not capture the exact pause transition and must not be used to claim it does. Older result files describe older builds.

## Boundaries and recovery

Work only in this standalone Orbit copy. `C:/HA/HEALTH APP` remains untouched. Preserve the signing key and all existing app data. Do not manipulate the other connected device. No permission grants or music transport controls were used. The reconnect heartbeat remains unchanged; no background phone checks are running.

Use checked backups in `verification/before-seven-ux-fixes-20260910-183639`, `verification/before-live-bar-review-20260910-191140`, and `verification/before-live-background-20260910-193826`. Each build retains the previous APK.

Health/Body/sleep histories remain labelled demonstration data. Actual app-owned workout history is separate. GPS accuracy, broad music-provider compatibility, Watch integration and replacement of native Health remain unqualified. Queue cover caching cannot preload audio or artwork a provider does not expose.

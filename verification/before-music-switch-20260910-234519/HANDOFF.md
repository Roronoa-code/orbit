# Orbit — seven UX fixes and live-bar corrections

Updated 10 September 2026. Current signed APK `6dfb41a4e867e51cd67b9983e30db71ca922c02c41c112fbcdd325f8f72ad883` is installed on Galaxy SM-S938B (`192.168.0.210:39389`); installed SHA256 matched. Build: `android/build/20260910-194200`. Final launch returned WARM, 115 ms. The user said: “yo if its installed leave it to me”. Stop phone interaction and further physical checks; the user is reviewing it.

This is the current entry point for continuing Orbit. Read the constraints below before proposing or changing UI. Older notes, screenshots and result files are historical; they do not override these later decisions. The current task was to document the latest state and preferences, with no further phone interaction or implementation.

## Hard design constraints

1. **Keep the agreed identity.** The base app is black/charcoal with quiet silver/grey detail and a purple highlight. The user explicitly rejected changing the whole palette to a purple gradient. Frost references describe the material, not permission to copy their pastel colours. Actual album artwork may colour the focused music screen. Preserve the existing Orbit stack and font instead of introducing another visual system.

2. **Use the screen properly.** The phone app fills the display and hides system bars for the requested immersive view. Do not bring back a mock phone frame, surrounding black gutters or a disconnected framed app surface. The app background continues under the floating frosted bar; content remains scrollable clear of it. Supporting controls must remain readable and reachable on narrow screens.

3. **Make data clean and legible.** Lead with a useful visual summary, clear values and short supporting labels. The user rejected packed columns, awkward overlapping curved edges, heavy outlines and an overworked glass material. Use restrained frost, simple rounded panels and spacing to group information; avoid unnecessary dividing lines and decorative containers. They disliked log-first Body pages and a collection of repetitive full-page data lists. Relevant detail still needs to be available behind deliberate interaction.

4. **Keep full, purposeful motion.** The user explicitly rejected automatic reduced-motion suppression. Do not silently restore OS-preference gates. Keep manual orb pause and hidden/offscreen cleanup. Taps, swipes, selection pills, focus entry/exit and opening controls need visible, coordinated feedback. Follow the supplied Apple motion reference for continuity, timing and movement that can reverse from its current position. Fast snaps, remounting/shuffling content and background flicker are failures. Ordinary page changes retain short fades/small slides; the rejected CutTracker-style source-to-page/cloned-page transitions must stay removed.

5. **The originating control owns its expansion.** The three-dot circle itself expands into its inline options; do not add another circle or detach a popup. The latest request explicitly requires animated growth of the bottom live bar: one shell grows upward from a fixed bottom and its controls follow inside it. Preserve motion on rapid reversal. Do not revert it to the older instant-open implementation. Keep the blur texture and background stable while the timer runs. The two status dots and old grab handle were rejected. Flat Body / Workouts / Health icons stay normal size with single-line labels; do not restore raised icon bubbles or a separate panel floating above a short shell. The current filled Pause pill is intentional, so the shortcut-bubble rejection is not a blanket ban on rounded controls.

6. **Preserve Home's organisation.** The globe switches period on tap and metric on horizontal swipe; animate both the retained globe and its reading. The four folding panels are the summary, daily activity, recent period and comparison, not four unrelated metric pages. Keep direct card gestures and normal scrolling coherent. The selected bar's decorative top dot stays removed. An inspected chart value must fade after a short interval instead of lingering over the chart. The top of the scrolled deck needs an intentional soft fade, not an abrupt rectangular cutoff. Do not repeat Home's Steps / Heart / Sleep / Intake as extra Health overview tiles. The launcher destinations are Body, Workouts and Health; Oxygen is a compact expandable overview strip, not its own full page.

7. **Body must feel interactive and support long history.** Keep the tappable dot composition ring, focal value, animated metric selector, animated range pill and exact-date inspection. Preserve 7D / 30D / 3M / 1Y ranges. A year is a real calendar range; display sparse data on its actual dates and make missing history apparent. Do not stretch 90 records into a fake year. The user tried and rejected the raised 3D strip chart: retain the smooth flat 2D line with soft shading. Do not copy the rejected Samsung Body layout, or spread this chart treatment to unrelated components without a request. Muscle is part of lean mass; do not stack overlapping body measures as independent shares of a total.

8. **Keep the Sleep card and remove the unwanted interaction.** The user clarified that the unwanted thing was text selection and Android's copy/translate menu, not the Sleep summary itself. Noneditable app content must not select on long press; actual inputs must remain editable. Tapping the Sleep summary opens a night timeline showing when each stage occurred, with start/end/duration and correct overnight dates. Selecting a stage highlights all its matching intervals. Preserve scrubbing, interval stepping and the subtle selected-interval outline. Do not restore the rejected dotted inspection cursor or dotted timeline grid.

9. **Workout timers should be expressive without clutter.** Use dot-matrix focal readings and the playful touch-responsive countdown/clock particles. Keep the activity → setup/target → countdown → live workout → pause/resume/finish → saved detail flow. The active workout's large walking graphic was rejected in favour of a tappable dot pattern; normal activity-selection glyphs can remain. Remove slogans such as “In your rhythm”, “Find your rhythm”, “Your pace, your time” and other filler. Keep only useful labels and instructions. Focus uses the numbers themselves, with no little orbit above/behind them or unnecessary extra timer labels. Pause freezes the same clock, makes it slightly darker and a little smaller, then resumes smoothly; it must not shuffle the timer/player layout.

10. **Preserve the liked full-screen music composition and fix latency at its source.** Real album artwork fills the width from the top edge and fades through the focused workout background, with a smaller clock and reachable controls. Do not turn it back into an isolated album card or low-resolution enlarged thumbnail. Use the best shared source, retain the previous decoded cover until the next is ready, crossfade covers and keep artwork anchored during control movement. Native metadata/artwork events should avoid waiting for the next poll; eligible upcoming covers may be warmed. Do not promise audio preloading or artwork the provider does not expose. Keep the music-app launch control a plain icon. Real paused tracks remain available; no-track, permission and error states hide the player. Missing art retains real metadata and supported controls. Do not add pretend heart/shuffle/repeat controls. Music transport and workout Pause/Finish are separate actions.

11. **Use rich, organised workout information and neutral Android presentation.** Samsung's workout flow and grouped summary/detail density are references for useful information, not an instruction to copy the whole Samsung app or its rejected Body pages. Show actual duration, pauses, target and supported route/movement/energy detail clearly. Retain old time-only records and explicit missing-data states. The blue workout notification and purple app icon were rejected. Keep the charcoal/silver Orbit mark, monochrome-compatible icon and neutral live notification with useful organised metrics and independent Pause/Resume/Finish. Notification pace stays omitted; its fixed target must not become a negative countdown.

12. **Keep information honest.** This standalone copy is not yet a complete replacement for Samsung Health. Health/Body/sleep histories are labelled samples; app-owned workout records are real and separate. User-supplied screenshots are design references, not permission to import their measurements. Never use demo body weight for workout calories, invent sensor/GPS readings, fake maps, fabricate earlier history or label a historical reading live. Use optional session weight only for the labelled energy estimate. Watch/native Health integration and physical outdoor accuracy require their own work and verification.

## Decisions that supersede older directions

- The latest bottom-bar request restores proper animated shell growth. Earlier notes saying automatic launcher expansion should be instant are obsolete. General page-clone morphs remain rejected.
- The latest explicit full-motion preference overrides older reduced-motion branches and skill defaults. Manual pause and lifecycle cleanup remain required.
- The raised Body chart was a rejected trial; the flat shaded chart is the retained direction. The frost reference never authorised a palette change. Flat launcher shortcuts do not mean removing the selected Body pills or workout Pause button treatment.
- The latest phone instruction ends the earlier authorisation for an agent-run short UI sweep. Installation is confirmed; leave launch/navigation, screenshots, recording, new workouts, permission changes and music controls to the user unless they explicitly request another physical check. Do not restart the reconnect heartbeat or create a new monitor.

## Reference use and acceptance

Use the user's actual supplied references for the relevant component. Do not infer approval of every element in a moodboard, an old implementation screenshot or a test capture. An installed APK and passing automated checks do not equal the user's visual approval.

- Original Orbit starting reference: `reference.png` (historical baseline; later decisions above take precedence).
- Material and clean-data references: `verification/material-reference/user-frosted-glass.png` and `user-clear-cards.png`; the adjacent rejected screenshots document the extra-circle/disconnected treatments.
- Apple-style timing reference: `verification/motion-reference/REVIEW.md` and its frame sheets. Its descriptions of old code are historical; use the video evidence for motion.
- Samsung workout-flow reference: `verification/visual-health-reference/workout-contact.png`.
- Nineteen original supplied images are now preserved unchanged under `verification/design-references/`, covering Nothing layout inspiration, full-screen music art, icon/workout-detail references and the rejected Body, Sleep, chart, cutoff and live-bar treatments. `README.md` identifies how to use them; `manifest.json` records their hashes. They are verification references and are not bundled into the app.

Before continuing, read this handoff and the relevant reference, inspect the current affected code, then make a recoverable, focused change. Do not reopen settled style choices or ask repeatedly whether to continue authorised work. Keep useful functions and data intact while simplifying implementation. Update this handoff after each coherent batch with source changes, checks, build/install status and remaining limits. State implementation, automated verification, physical checks and user acceptance separately. Address visual regressions against the specific component rather than redesigning unrelated pages or using a generic style label.

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

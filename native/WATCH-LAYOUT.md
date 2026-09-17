# Watch hierarchy, bounds and input

15 September 2026. Implements the interactive-screen part of addendum W1/W2/W3. This is local emulator qualification, not physical Watch or user acceptance.

## Research translated to Wear

- Apple's [Design and build apps for watchOS 10](https://developer.apple.com/videos/play/wwdc2023/10138/) transcript puts brief, focused tasks and currently relevant information first. Orbit retains its own charcoal/lavender design and Android components.
- Android's [Wear type scale](https://developer.android.com/design/ui/wear/guides/styles/typography/type-scale-tokens?hl=en) specifies fixed large numeral roles and tabular figures for changing values. The installed [Wear Typography API](https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/Typography) does not automatically freeze every numeral: Orbit explicitly keeps primary figures at their chosen 28/30dp size. Small readings and labels continue to scale. No value-dependent font shrinking or invented physiological animation.
- [Wear navigation](https://developer.android.com/training/wearables/compose/navigation) and [rotary input](https://developer.android.com/training/wearables/compose/rotary-input) remain platform-owned. All four paged routes share the native Compose foundation pager, with Wear snap motion, hierarchical focus, rotary scrolling and swipe-to-dismiss. The installed Wear pager wrapper was evaluated but its changing touch configuration cancelled held input; the underlying native pager avoids that extra wrapper. Existing curved Material indicators remain through a read-only position adapter.

## Shared interactive layout

`WatchLayout.kt` owns the full-screen content band. Its radius comes from the actual window, with safe-drawing insets and a 2dp edge allowance. Both upper and lower rectangular corners must fit the circular chord, not just the text center. The pager's reserved bottom area is accounted for using the window height rather than assuming the shorter page itself is circular. This helper is for full-screen page origins; it is not a generic nested-card layout.

The page scrolls when its content exceeds that safe band. Short summaries have compact labels and at least 48dp actions; larger text or detail content stays reachable by touch and rotary. A small background fade appears only at an edge with more content. It needs no backdrop sampling, offscreen layer or continuous animation. It is a scroll affordance, not a replacement glass effect.

The initial rectangle experiment passed existing tests but cut summary buttons in the rendered viewport. The follow-up reduced redundant summary text, kept accessible action heights, and softened overflowing scroll boundaries. Render review is still required alongside bound assertions.

## Attention and continuity

- Live workout: elapsed time, confirmed recording phase, distance and optional pulse in stable slots. Energy, steps and permission/source context remain further down. Recording failures and reconnect action precede optional readings.
- Controls: phase and Pause/Resume plus a separate Finish confirmation. Missing step/pulse messages are absent from this task.
- Today: actual Watch steps, then secondary facts and direct actions. Deliberate pulse acquisition retains its guidance because obtaining that reading is the task.
- History and Recovery: dated result and Details. Longer context stays in the existing detail route. Original source and freshness semantics are preserved.

Timer layout is chosen from a measured full-hour template and available width, so crossing one hour does not change layout mode. Large values use the fixed numeral role; secondary distance/pulse rows stack when their scaled text requires more room. Missing readings retain the same slots.

## Input ownership and evidence

`WatchBackSurface` installs one native Wear swipe-to-dismiss parent. The left 15% of the full window belongs to Back on every page; content owns horizontal paging elsewhere, while vertical and rotary input stay with the focused page. Ownership is latched at touch-down and never transfers when a page or reading changes. The parent's touch configuration keeps the normal threshold at the edge and declines content drags. With touch exploration enabled, content paging remains available and this touch Back gesture is disabled; the normal Back dispatcher remains available. Physical TalkBack/system-edge qualification is still open.

All four pagers use `WatchPager`. It retains native dragging, fling and Wear snap motion rather than implementing a custom drag animation. Paging resets its edge gate between gestures, so the first content swipe after Back works. Rotary uses Wear's `rotaryScrollable`/hierarchical-focus APIs; manual `requestFocus` calls were removed. Focus and Back eligibility follow the settled page, not the halfway point of a drag. The read-only curved indicator adapter changes only when pager position/count changes; it does not acquire input or cause metric updates to rebuild it.

Two installed-library boundaries required local workarounds, with no dependency change:

1. Wear Compose 1.6.2 `CustomTouchSlopProvider` constructs a new ViewConfiguration when it recomposes. Compose UI 1.10.4 resets pointer handlers on configuration identity changes. Native Compose foundation paging avoids that extra changing provider; changing readings remain in the existing content scope.
2. Compose foundation 1.10.4 `LazyLayoutPager.dragDirectionDetector` waits for an unconsumed UP. A cancelled hold supplies a consumed synthetic release, leaving its old DOWN in the direction detector. The next drag visibly crossed halfway, then snapped backward using the stale displacement. `watch-offset-probe-device.log` records this. The shared pager now replaces its otherwise-stable pointer configuration only after that synthetic cancellation, resetting touch handlers while preserving pager, remembered content and scroll state. Reevaluate/remove this workaround after an upstream fix. Neither ordinary reading updates nor drag frames allocate replacement configurations.

The earlier 1.25x-vs-1.10x threshold experiment and content-scope-only isolation did not solve the complete sequence; they are superseded. Their failure logs are retained. Temporary input/offset probes and the focus-disabled diagnostic experiment are removed from final source.

Workout hydration now resets to live only for a different session or a new return intent. Initial hydration preserves the restored page. The handled return counter has the same Activity lifetime as the intent counter; it is not saved across recreation independently. The workout UI test exercises the actual SINGLE_TOP/CLEAR_TOP return path, reopens controls, waits for their settled display, recreates, and then verifies paused state, swipe Back, Finish cancellation and confirmed saving. Back never issues a workout command.

`WatchLayoutTest` exercises 192/228dp geometry at 1.0/1.5 text scale, complete control height and minimum touch bounds, actual numerical glyph advances/decimals/units, timer rollover, absent-to-valid readings, press cancellation followed by a new held drag with a reading update, vertical button crossing, horizontal reversal, focused rotary scrolling without clicks, repeated edge-dismiss/reset, and disabling navigation during a held drag. Existing native activity tests retain real emulator Health Services start/pause/finish, background measurement stop, history/detail restoration, recovery, ambient and surface checks. The wake test waits for resumed window focus before sending touch: rendering the first interactive semantics tree alone does not establish that the window accepts input.

Still open: actual hardware round/inset/font rendering, physical gesture latency and haptics, exhaustive interactive failure renders and Tile/complication host formats. Emulator captures and pixel assertions do not close those gates. Final run paths and counts are recorded in MIGRATION.md and the checkpoint manifest.

The longest action, Allow background heart rate, uses the scaled small-label role and 8dp internal padding. At 192dp and 1.5 text scale, it wraps at whole words and retains its native minimum touch height. The layout test reads the actual text layout and rejects a break inside “background”; this complements the overflow/bounds assertions. The first added assertion queried merged button semantics and failed to find its child text result. Querying the tagged unmerged text fixed the test; the source adjustment itself was retained and visually reviewed at both sizes.


## Data-driven page resets — 15 September 2026

The R2 full Watch run exposed an actual layout crash during workout startup. The emulator thread dump showed `WatchWorkoutActivity` calling `PagerState.scrollToPage` / `forceRemeasure` from the Health Services callback thread under Compose instrumentation while Android's main thread was drawing. The failing run is retained at `../verification/native-runs/20260915T110126.319614Z-wear/manifest.json`; the local full log is `protocol-wear-crash-log.txt`.

All four data/intent-driven Watch resets now use foundation's `requestScrollToPage`, which records the desired position for the next layout instead of forcing immediate measurement. Genuine session changes and notification return still reset to Live; initial hydration and ordinary readings retain the current page. History/recovery/destination restoration uses the same scheduling rule. Native touch-driven `animateScrollToPage` calls are unchanged. This does not introduce another gesture model or animation.

The final Watch suite passed 18/18, including ambient/wake/return, real emulator Health Services controls, page restoration and held gestures: `../verification/native-runs/20260915T111149.482908Z-wear/manifest.json`. Final-source returned-Live and paused-controls captures were inspected in `../verification/samsung-import/native-protocol/`. This is local runtime evidence, not a claim of physical-device frame pacing.


## Ambient geometry — 15 September 2026

All four app routes now use the same `WatchContentViewport` circular geometry in interactive and ambient mode. The wrist-down screen keeps an orientation label, a fixed 28dp clock and the confirmed status. The non-updating `--:--` placeholder is removed; it was not a live elapsed value. Status labels wrap at their scaled size instead of shrinking to fit. The optional raise-wrist hint yields first when larger text needs the space. No interactive controls or live measurement values are frozen in ambient.

Native `StaticLayout` handles text wrapping; layouts are cached by draw size/content. Native text antialiasing is disabled for low-bit displays. A reserved 4dp inner allowance contains the existing burn-in shift, and the background remains black. Ambient ticks and service ownership are unchanged.

The emulator matrix covers Today, active/paused/reconnecting/interrupted workouts, History and Recovery at 192/228dp and 1.0/1.5 text scale with two burn-in offsets. Every illuminated pixel remains inside the shared safe viewport and the circular screen; all low-bit pixels remain binary and at least 85% of the screen is black. Small-screen enlarged-text captures were also inspected for actual wrapping and readable hierarchy. The real emulator ambient/wake/notification-return flow remains part of the regression suite. Physical display behavior remains unqualified.


## Measurement and saved-reading attention — 15 September 2026

Today includes the age of its actual step sample, with floors and total energy below the immediate actions. Read failures are shown with the affected readings and clear on a successful reload; pulse failures no longer trail unrelated Connection/Today content. Collection failures remain in Connection. No error is treated as a zero health value.

A deliberate pulse request records its boot identity and monotonic start time. A valid reading must belong to that boot, occur after the request and remain within the existing 10-second live window. Recently cached pre-request readings, previous-boot data, uncertain clocks and low-quality measurements cannot become a fresh result. Historical idle readings keep their recorded age. Current unreliable/contact readings produce acquisition guidance. Native callback registration remains lifecycle-owned; latest-request collection cancels and joins the preceding callback cleanup before a rapid restart, and cleanup cannot erase a newer request.

Recovery retains offline values with their original date and adds a quiet Saved view label when refresh failed. Uncertain publication/import time is visible beside the summary; full source/error context remains in Details. An actual load failure offers retry without incorrectly instructing the user to connect Samsung Health again.

## Command confirmation and quiet return

[COMMAND-FEEDBACK.md](COMMAND-FEEDBACK.md) defines the current haptic boundary. The requesting device owns completion, and actual phase plus durable commit precede success. No completion is inferred from replayed UI state. Watch feedback stops in ambient/background and does not replay on return; passive readings and remote commands remain quiet. Existing native gestures and fixed numerical roles are unchanged. Native Watch gesture/workout flows pass with system animations disabled; the emulator setting is restored afterwards. Physical haptic and paired-device acceptance remain open.

## Today: visible data state before optional detail

`WatchToday.kt` renders the production Today body. The activity now distinguishes initial loading from an empty journal. Previous-day readings cannot masquerade as today; uncertain/unreliable values remain excluded. A refresh failure retains its cached recorded value and age, with failure text beside the value. Missing optional distance/floor/energy values no longer produce repeated dash rows; actual zero readings remain visible. Distance retains a one-line slot while arriving. Actions remain native and scrollable.

Actual renders exposed two hierarchy failures even though the earlier tests passed: a cached refresh error was below the visible area, and the repeated Today/Watch steps labels pushed context into the lower fade at large text. The heading is now simply Watch steps, with the fixed-size number below it and shorter, scaled secondary context. The test requires that the full context fits above the lower fade at the initial scroll position, not merely somewhere in the scrollable content.

Forty final native captures were reviewed: ten data states at 192/228dp and 1.0/1.5 text scale. The primary step position is unchanged through those states, and all three actions remain reachable by scrolling. At the smaller size with large text, distance/actions can require scrolling; they are not claimed to all fit in the first viewport. [Render evidence and review](../verification/samsung-import/native-watch-today/render-review.md) retain both rejected candidates and the final result. The render inputs are isolated test data, not proof of physical sensors or OEM appearance.

Today tracking opt-in and permission recovery still need a contextual entry from this surface; collection currently defaults off and its setup lives on Connection. Preserve explicit opt-in and native permission consent. History/workout failure-state review and physical acceptance remain open.

The native pulse matrix covers absent, pre-request, previous-boot, uncertain, unreliable, valid, dropped-out, contact, failed and historical states at ordinary/enlarged text. Recovery checks cover offline/uncertain/error/recovered views and retained details. The real activity check performs rapid Stop/Measure twice, confirms new boot-matched Health Services callbacks, then checks lifecycle stop and recreation. The initial restart assertion wrongly required the emulator to supply valid accuracy; its new samples were present but marked unreliable. The final check validates callback freshness while the rendering test separately rejects unreliable values. Production quality guards were not relaxed. The first isolated pulse captures also revealed the fixture omitted the app background; it now renders the actual charcoal/lavender scheme before visual review.


## Workout/history failure-state review

The existing 48-frame native attention matrix exposed a real issue at 192dp with 1.5 text scale: scroll fading crossed the Details and Reconnect controls. History preview now leads with workout identity (and saved/partial qualification), recorded time and Details; dates and expanded failure context follow. The reconnect action follows the fixed timer before secondary phase/metric context and reuses the existing 48dp minimum touch target. Typography remains scaled and controls can grow when needed.

Stacked live metrics keep each unit on the same baseline as its value; the former separate unit line disappeared under the small Watch's bottom fade. The default two-column layout remains. No slot is inserted/removed when a reading arrives or becomes stale, and the native rollover/acquisition test confirms stable geometry.

The attention-actions checkpoint records six distinct focused local checks. Final layout/gesture group passed 4/4, with 48 captures across twelve normal/interrupted/missing/stale/reconnecting/paused/ended/error/zero cases at 192/228dp and 1.0/1.5 scale. The real history database-failure/retry/restoration flow and indexed source paging passed 2/2 before the final reconnect/metrics-only changes. Actual renders were reviewed, including the corrected large-text controls and units, rather than equating passing layout assertions with visual approval. Original production APK and physical devices remain untouched. Other Watch routes, broader native/release checks and physical host/sensor/gesture qualification remain open.

## Nested navigation review — 15 September wrap-up

`WatchEnvironment` supplies one shared native swipe-dismiss surface. `WatchHeartActivity` had installed a second one around the same content; that inner owner is removed. Native Back dispatch now reaches the route handler once. Recovery holds its displayed context while a pointer is down or the pager is moving, then applies incoming context using the selected date. A held Details button keeps its date even when a newer day is inserted or removed. Empty context cannot retain an invisible detail Back handler.

| Route | Actual Back and input behavior |
|---|---|
| Home | Native three-page pager; Back returns other pages to Steps before exit. Foreground pulse collection stops when leaving its screen. |
| Workout | Setup returns to kind selection; controls return to live page; Finish confirmation cancels before leaving. Exiting the activity retains the foreground workout. |
| History | Details return to the selected session ID; cached error/retry and restoration retain selection. Shared edge cancellation/dismissal checked locally. |
| Recovery | Details return to selected date. Incoming data waits for the held gesture/release; empty context yields directly to outer Back. |
| Scalar measurement | Running Back stops collection; pending/saving results guard exit; details/result/setup unwind without discarding durable or pending results. |
| ECG | Running Back stops and retains captured partial data; pending saving guards exit. Ready/terminal states can exit. |
| Continuous HR/raw | One edge owner. Idle raw-mode Back returns to picker; active/starting stream Back exits the activity while explicit foreground recording remains. Only Stop ends collection. |

The actual sensor startup/Back test also exposed a service shutdown defect: after cancellation, returning from IO cleanup could skip the final idle transition. The entire final cleanup is now non-cancellable, including clearing foreground ownership. The test holds the real sensor connection lease, starts the actual foreground service, exits via native edge swipe, confirms the service remains active, then stops it and verifies released ownership. It supplies no synthetic sensor readings.

At source `59dbf290f43aeb9c420e2d4aa2f778323cc87f26b5cd29af7c1867c928626869`, 13 focused Wear tests pass: raw sensor 3, Recovery 4, History 2, sweat 2 and heart 2. Evidence: `verification/watch-navigation/20260915T214804Z`. The earlier 44-test full run had one failure in this new shutdown check; its failure remains recorded. Do not claim a new full-suite or physical gesture pass. Exhaustive nested-route combinations, OEM edge/rotary behavior, predictive Back and accessibility remain open. Further review was deferred at the owner's wrap-up request.

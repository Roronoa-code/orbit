# Orbit UI pass: paused at the owner's request

> Superseded by `UI-PARITY-RESUMED-2026-09-17.md`. Its next-batch items 1, 2 and 4 are done and item 3
> is partly done; read that document first and use this one for the pass’s original scope and evidence.

The owner resumed **phone UI/UX only**, asking for the finalized HTML look, gestures and animation feel. They then asked to wrap up because of their usage limit. Stop here until explicitly resumed. The pass is incomplete; this is not final design acceptance. Do not continue the research addendum, sensors, synchronization or Watch implementation under this UI scope.

## Design authority and work preserved

The approved reference is the HTML packaged in `../dist/Orbit.apk`, SHA-256 `6cd4de84c283aea70cc202e31a782af44018ab01e673d3933ce20d2c50e32452`. It was extracted into `../verification/ui-parity/reference.html` with an isolated local fixture for comparison. The original APK and HTML source were not edited. The native phone remains Compose; no WebView replacement or backend changes were made.

This pass changed:
- Home: Manrope numerals with the HTML's repeated dot mask, quieter card backgrounds and a subtle static background gradient. The bitmap alphabet remains for the workout clock and Measurements.
- Measurements: 300dp ring, 48dp numeral, inline kg, smaller labels, removed redundant date above the ring, unboxed trend proportions and quieter period control. Statistics precede the period selector. The existing data calculations remain intact.
- Shared selectors: themed Manrope text, subdued idle selection, direct finger tracking during drag, retained spring settling and modest engagement shading. No new animation library.
- Health cards: reference number sizes, line heights and spacing. Existing per-card resizing, drag order, playful art and persistence retained.
- Workout setup: reference spacing and a combined GPS label/subtitle row. Shared switches have quieter borders. Live clock is fixed at 63dp through player transitions; phone and Watch workout result readouts are 67dp.
- Shared body typography and background colours; Sleep uses the existing workout date-arrow control rather than its separate implementation.

## Evidence and limits

Recoverable baseline: `../verification/ui-parity-baseline-20260915/phone-src/`, plus the original source hash manifest and prior wrap-up. Native source remains uncommitted/untracked as before. No commit or push was made.

Current source fingerprint: `7f4e0397664b485de0277a524c8e620d7a009fb55daba5f93d091e1c2cc0bd41`.
Current debug APK: `phone/build/outputs/apk/debug/phone-debug.apk`, SHA-256 `c2681472584bec66556776db15ff7e89a0e50eba0b60cd007f553640300c3412`. This uses the development signer and is not an in-place physical-phone update.

- **21/21 local instrumentation checks passed** in `ui-parity-recovery-tests.log` (51.865 seconds): PhoneVisualParityTest, HomeTest, MeasurementsTest, HealthDashboardTest, ExploreIslandTest. This covers reversals, cancellation, card shuffle/resize, large text, Explore above the expanded deck, and glass quality transitions.
- That run was **before the final two edits**: allowing the dotted text to use its natural line height, and extending PhoneVisualParityTest with edge-to-edge setup/history/music captures. Those two edits are built but not runtime-verified.
- Latest `:phone:assembleDebug`, `:phone:assembleDebugAndroidTest`, `:phone:lintDebug` passed: `ui-parity-final-build.log`. Lint reports **0 errors, 45 warnings**, not a warning-free result.
- Before/after screenshots are in `../verification/ui-parity/before/` and `after/`. The existing after images precede those final two edits. Earlier screenshots use the test activity's default system-bar treatment, so its grey bottom strip is not a production UI claim.
- The new test renders the actual OrbitApp theme/shell using local in-memory data. SleepRoute additionally reads the emulator's existing local test database, so its values differ from the in-memory Health card fixture; no physical health records were used. A comparison screenshot is not exact pixel parity or physical-device motion proof.
- The initial grouped run exited when emulator-5580 disappeared. It did not pass. Recovery used the documented AVD environment and Lavapipe Vulkan, then `debug.hwui.renderer=skiavk`; the completed 21-test rerun is the valid result. Logs retain both attempts.

No physical phone or Watch was controlled, tested or installed during this UI pass. The phone still has the earlier native development build described in WRAPUP-2026-09-15.md. No extra app/clone was created.

## Exact next batch when resumed

1. Re-read the current diff against the saved baseline and `../verification/ui-parity/paused-state.json`. Preserve the approved HTML; finish UI parity before returning to addendum work.
2. Run the expanded PhoneVisualParityTest. Its new Train flow now includes a Last workout also named Walking; narrow the Walking tile selector if it is ambiguous. Confirm the short Explore-bar swipe actually opens the destination row before accepting that capture. These added branches have not run yet.
3. Review final full-shell Home, expanded deck/Explore, all Health sizes/oxygen, Measurements with sparse and varied readings, Sleep, Settings, workout Train/History/record/setup/countdown/live/music. Keep timer/control sizes fixed, inspect interrupted drags and mid-transition reversals, and check narrow width/large text. Complete date-picker and additional phone Watch-reading screens review; do not claim they were visually matched in this pass.
4. Run the affected SleepTest, SettingsTest, WorkoutTest, MusicTest, HeaderTest and repeat the directly affected Home/selector checks on final source. Fix failures at their shared UI boundary without changing real data behavior.
5. Package only after local UI and motion review. Physical installation/testing requires fresh permission; previous installation authorization is already fulfilled. Do not mark the larger Orbit/addendum goal complete from UI checks.

Local AVD restart: `ANDROID_AVD_HOME=C:/HA/HEALTH APP/.local/emulator`, `ANDROID_HOME=C:/Users/abdul/AppData/Local/Android/Sdk`; emulator `-avd HealthAutonomousApi36 -port 5580 -no-window -gpu lavapipe -no-snapshot -no-audio`. After boot set `debug.hwui.renderer` to `skiavk` on **emulator-5580 only**. Do not wipe data or modify other devices. For the reference, serve `C:/HA/design-concepts` on localhost 8784 and open `/signal-orbit-steps/verification/ui-parity/reference.html`.

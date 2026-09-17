# Glass readability

The native phone keeps independent Reduce transparency and Increase contrast preferences alongside the existing Reduce motion preference. No Watch sensor, recording, sync or acknowledgement policy is connected to these settings.

## Material and interaction

`OrbitGlass.kt` is shared by the retained Explore surface and both header controls. Normal mode keeps its existing 8dp frost, saturation, tint, rounded geometry and sampled optical edges. A stable dark backing protects the interior containing labels. Its 8dp edge feather is fixed in physical layout units, so expansion does not sweep a brightness boundary through text. The backing covers each local label region, not an average brightness across the page.

Reduce transparency skips backdrop recording/drawing for the material and uses the approved charcoal fallback. Increase contrast strengthens the interior backing and brightens glass foregrounds continuously toward white while retaining lavender roles. The shape, measured size, labels, semantics, pointer ownership and selected route are unchanged. Motion remains a separate preference.

There is no sampled-brightness style switch. Bright/dark content can move behind the surface without triggering a theme decision, timer or entry/exit threshold. This satisfies the addendum's stable-local-region alternative; if a future optical mode introduces a discrete adaptive switch, its measured entry/exit hysteresis remains required under G3.

## Platform and persistence

- [UiModeManager contrast](https://developer.android.com/reference/android/app/UiModeManager#getContrast()) is public from API 34. Non-negative contrast continuously strengthens the material; Orbit does not weaken readability for a negative contrast request.
- [AccessibilityManager high-contrast text](https://developer.android.com/reference/kotlin/android/view/accessibility/AccessibilityManager#addHighContrastTextStateChangeListener(java.util.concurrent.Executor,android.view.accessibility.AccessibilityManager.HighContrastTextStateChangeListener)) is public from API 36. It enables the strongest treatment. The compile SDK declarations were also inspected directly.
- Listeners use the main executor, refresh on resume, and unregister when the composition leaves. Earlier platforms retain explicit Orbit controls; no hidden setting or Apple API is read by production code.
- There is no established matching public Android reduce-transparency signal. Orbit exposes its own plainly named preference.
- `orbit-material-readability-v1` is a bounded, versioned JSON preference in the existing acknowledged SharedPreferences backend. Writes reread/validate under the existing settings mutex and preserve unknown optional fields. Malformed/newer documents remain untouched, with an error and retry. While unavailable/loading, material falls back opaque.

## Verification boundary

Verified against source `7830a4b2fd598239b5900da3901ab6a45d17ed88ad33556f9429153c1d585889`:

- [Full phone manifest](../verification/native-runs/20260915T115120.921004Z-phone/manifest.json): 72/72 native tests, including the existing workout, persistence, navigation and gesture flows; four developer checks passed. Instrumentation took 120.740 seconds.
- `ExploreIslandTest`: eight independent preference combinations × four backgrounds; normal local text contrast >=4.5:1 and increased contrast >=7:1. A live preference change during an owned drag preserves release/selection. All modes preserve measured bar bounds. Header controls remain usable.
- The 24-position moving black/white/pink grid gives 5.10–8.85:1 behind the secondary label, with stable bar/header bounds. [Samples](../verification/samsung-import/native-readability/readability-grid.json) and captured positions are in the evidence folder. These controlled draw/gesture checks do not establish physical frame pacing.
- `SettingsTest`: real writes, recreation in a fresh model, independent motion settings, unknown-field retention, malformed/future document preservation, native controls and error handling.
- `MaterialReadabilityTest`: API 36 public contrast and high-contrast-text signals, preference changes, listener disposal/recreation, explicit Activity recreation, and restoration of both original emulator system settings. Android's contrast changes can themselves recreate an Activity (`CONFIG_ASSETS_PATHS`); the test uses the existing empty Compose rule/ActivityScenario pattern and reinstalls its content after creation. The initial restricted-key setup and one-shot Activity fixtures failed; those were verification-harness failures, not silently accepted product evidence. Temporary callback logging was removed.
- Phone release assembly and lint passed. Debug and unsigned release APKs both embed the current source identity and contain no HTML/JS/CSS. [Checkpoint](../verification/samsung-import/native-readability/checkpoint.json) records hashes and the unchanged original `dist/Orbit.apk`.

 Physical S25/Watch qualification and user visual acceptance remain open. G1 refraction and G3 measured quality selection are separate unfinished work; this change does not claim either.

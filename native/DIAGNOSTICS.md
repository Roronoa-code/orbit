# Native causal diagnostics

R1 uses the existing native interaction and recording paths. It does not change recording ownership, invent platform confirmations, replace the durable health journal or upload anything.

`DiagnosticTrace.kt` accepts enums and process-local operation numbers only. It keeps at most 128 events and 32 operation summaries. Each native process generates an ephemeral run token. Event offsets use that process's `elapsedRealtimeNanos` origin; a phone observation of a Watch confirmation is timestamped on the phone. Never subtract offsets from different runs or devices. JankStats frame durations are separate aggregates, not sensor latency or GPU timings.

The recorded chains currently cover:

- Explore: accepted click/drag → requested target → composition applied after settling. A new grab supersedes the previous operation; cancellation is explicit. JankStats tags distinguish touch, drag, settling and idle without logging finger coordinates.
- Watch exercise: accepted command → request → API acceptance → actual Health Services phase callback → successful journal commit → applied Watch composition. Service/background completion can end at durable commit until the UI is shown. An API acknowledgement alone never records success. Success haptics now follow the confirmed/committed state while interactive; initial input has separate feedback.
- Phone control of a Watch workout: accepted input → transport request → API acceptance → locally observed committed Watch phase → matching updated record in the phone composition. The transport request, installation, workout and node identifiers are not copied into diagnostics.

`DISPLAY_UPDATED` means the relevant Compose change was applied. It does not claim photons reached the display, hardware motion quality, or a user saw it. An operation summary's `complete` means it terminated, including failure/cancellation/supersession; read `lastStage` and its retained events to distinguish success. Events dropped from the ring are counted. A process killed within the coalescing interval may lose its newest diagnostic events; this cache is deliberately not the health durability mechanism.

`DiagnosticApplication.kt` installs [AndroidX JankStats 1.0.0](https://developer.android.com/topic/performance/jankstats) per visible native activity. The callback copies only enum state and primitive timing/count fields into fixed buckets. It retains no `FrameData`, strings supplied by callers, or per-frame objects. JSON formatting and disk writes run on one background thread; snapshots release the short buffer lock before JSON work. A bucket resets after one million frames and individual frame durations are bounded to 60 seconds. No blur, layout or sensor recalculation is added by collection. Frame tracking pauses with the activity.

The local cache is `cache/diagnostics/trace.json`, separate from health data/export. Writes coalesce over two seconds. One snapshot is capped at 128 KiB, with only AtomicFile recovery/temporary siblings allowed. Files expire after seven days on next access; future-dated or oversized cache files are discarded. The application may also have this cache removed by Android. Exceptions produce constant diagnostic log messages. There are no health values, GPS routes, coordinates, names, birth dates, artwork, media titles or stable device/source IDs in the schema.

G3 adds an optional `rendering` object containing only closed `tier` and `reason` enums. Each material reports the tier actually drawn, including an opaque accessibility override or unsupported optics. It updates the window's JankStats tier only when that choice changes and requests the existing coalesced file write. No gesture or navigation action is needed to persist a quality change. Phone frame samples also feed the UI-only quality policy using Android's actual total duration and deadline on API 31+; the Watch retains its existing native tier and collection behavior. Frame-pressure and thermal reasons are observations, not health measurements or hardware performance acceptance.

Every app build embeds `assets/orbit-native-build.json`, generated through AGP's variant source API from the same input manifest as `dev.py`. It identifies `orbit-native` and the source SHA-256. Runtime diagnostics add the installed version code, debug flag and coarse API/width/round-screen/text-size configuration. Verification rejects a packaged fingerprint that differs from its source snapshot, then compares the installed APK hash. Signing identity and full artifact hashes live in the developer manifest, not the runtime trace. Publisher dependency trust remains a separate R3 gate.

## Local reproduction

```powershell
./verify.ps1 -Mode replay -Module phone -Serial emulator-5580 -Case diagnostic-boundaries
./verify.ps1 -Mode replay -Module phone -Serial emulator-5580 -Case explore-source-reversal
```

Both cases use seed 915. `diagnostic-boundaries` runs the actual phone control state machine with a controlled monotonic clock and synthetic identities. It demonstrates an API-accepted operation that never reaches confirmation and compares repeated successful event sequences. It also checks concurrent frame aggregation, event retention, expired cache cleanup and installed identity/local persistence.

`explore-source-reversal` uses the Compose test clock: open, advance 96 ms, reverse 100 px over 120 ms, deliver a seeded synthetic workout/backdrop update, settle 1400 ms, reopen, settle, select Health, settle. It checks one selection and an intact launcher, and saves `cache/explore-source-reversal.json` with the source identity, seed, ordered input recipe and allowlisted trace. Its UI clock is controlled; the captured real JankStats durations naturally vary with the host. This fixture does not load a personal database. The existing Wear exercise lifecycle test additionally asserts that real emulator Health Services API acceptance, phase callback, durable commit and composition occur in that order for start, pause and finish.

Diagnostic artifacts can be deliberately copied from the explicit local emulator for a developer checkpoint. They are not included in health export and no share/upload action is automatic. More action paths can use the same allowlist when a concrete causal failure needs attribution; this checkpoint does not claim every sensor callback or phone-owned recording action is instrumented. Paired transport, physical sensors, real frame pacing and battery cost remain separate acceptance gates.

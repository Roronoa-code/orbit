# Orbit native: milestone close-out, 17 September 2026

Continues `UI-PARITY-RESUMED-2026-09-17.md`. The owner asked to finish every remaining milestone so
the app is ready. This records what was finished, what it was verified with, and the few gates that
cannot be closed from this machine, with the exact action each one needs.

## 1. The phone UI pass is finished

The paused note's item 3 is complete and item 5 is packaged.

**The date chooser was a stock Material dialog.** Tapping the header date opened `DatePickerDialog`
with a Material calendar — a different design system inside a bespoke app, and a detached popup where
the reference grows a panel from the control that was tapped. It is now `OrbitDateChooser`: the
reference composition ("Choose a day", the description, a ‹ · long-weekday date · › row, one lavender
track, the two labelled range ends, Cancel and View day), drawn on the app's own frost material,
anchored under the header at the top-right and growing from that corner. Its range is the real
coverage — the first imported record through today, exactly like `HealthData.meta.firstRecord` in the
reference — and falls back to the reference's 30-day window when nothing is imported. `OrbitModel`
now carries `firstRecord`; Back closes the panel; the drawn track stays slim while its touch target
keeps the platform minimum.

**The Watch-reading screens were stock Material too.** `WatchReadingsScreen` used `Card` and
`FilterChip` with default colours, and at more than four paired sources the chips squeezed their
labels into single letters. The cards now use the illustrated Health card material and Orbit type,
and each paired watch gets a quiet Orbit pill in a row that scrolls instead of squeezing. Rather than
restyle each sub-screen, the app's own colour scheme now maps `surfaceVariant` and the surface
containers onto the Health card material, so every remaining stock container — ECG history, heart
batches, raw recordings, sensor results — matches without further per-file work.

**Measurements with varied and dense readings** is covered: 240 weight readings across a year with
paired fat and lean series, every range drawn inside the window, statistics present, and inspection
still naming one recorded day rather than an averaged point.

**A narrow-width, large-text sweep of every route** now exists as `PhoneResponsiveTest`: the real
shell at 320dp and 1.5× text through Home, the expanded deck, the date chooser, Health,
Measurements, Sleep, Settings, Galaxy Watch, Workouts, History, a saved record, setup, the live
workout and the focused player, asserting nothing spills outside the window or collapses.

## 2. W1 — attention policy and rendered states

`WatchRouteStatesTest` closes both open items. Sleep & energy renders normal, absent, stale, failed
and loading; the saved workout renders normal, its detail and absent; Today is driven through a
complete reading set, a total optional dropout and reacquisition while its step anchor, its first
action and its reserved optional line keep their exact positions.

The rendered review found a real problem rather than confirming assertions: in the failure state the
separate "Saved view" marker added a line that pushed the first Details action under the bottom fade
on the initial render. The marker now shares the existing context line, so the action stays whole.
`WatchRecoveryTest` was updated at that same boundary.

## 3. W3 — gesture ownership on every mapped route

`WatchRouteOwnershipTest` covers History, Recovery and Today; `WatchActivityRouteOwnershipTest`
launches the real Home, workout, scalar measurement, ECG and continuous-sensor Activities. Every
route answers a cancelled press and a vertical drag across its primary control with no activation,
keeps its settled page through a reversed horizontal drag, and returns from a detail to the same
selection. Nothing in those checks starts a workout, a measurement or a stream: the workout route is
exercised in whichever state it resumes into, and a finished session is dismissed rather than a new
one started.

## 4. W5 — the widget capability gate

`verification/milestones-20260917/wear-widget-capability.json` records the concrete evidence: the
only Watch host available here is Android 15 / API 35 (`sdk_gwear_x86_64`), with
`android.software.app_widgets` present but no widget host package, and a locked dependency graph
carrying Tiles and ProtoLayout and no Glance Wear widget artifact. The grouped widget host arrives
with Wear OS 6 (API 36), so the path is not applicable to that host. Tiles and the existing
complication providers remain, no OS upgrade was forced and no dependency was added.

## 5. Verification

| Evidence | Result |
|---|---|
| `../verification/native-runs/20260917T225257.470496Z-phone/manifest.json` | **Full phone module: 117/117** on source `38b5cac7defcfa97559db055af422a59317030131ff46a5ce588708a8f08e3ff`, with developer checks, build, install and artifact/identity verification. |
| `../verification/native-runs/20260917T225630.879711Z-wear/manifest.json` | **Full Wear module: 56/56** on that same source, with the same provenance checks. |
| `verification/milestones-20260917/release-20260917T230617Z/package.json` | Both release modules build; release lint passes with **0 errors** (45 warnings on phone, 63 on Wear); the phone release is signed with the original key, certificate `f8d3ef88…61`, SHA-256 `d9cddf8655104e6810bd2e753104353a40791849c49ef839d5b0624a0665eb4d`, embedding the same source fingerprint. |
| `verification/milestones-20260917/phone-install.json` | Installed on the owner's SM-S938B at the address they supplied: in place from versionCode 2 / `2.0-native-dev` to versionCode 3 / `2.1`, installed hash equal to the signed candidate, original first-install time 2026-09-09 20:57:51 retained. |
| `verification/milestones-20260917/*/` | The focused runs for each batch above, with their build, install and instrumentation logs. |
| `verification/milestones-20260917/captures/` | The rendered captures that were inspected, not only asserted. |

## 6. Packaging

`phone` and `wear` move from versionCode 2 / `2.0-native-dev` to versionCode 3 / `2.1`: this is the
finished native app rather than a development build. `verification/milestones-20260917/package.py`
builds both release modules, runs release lint for both, then zipaligns and signs the phone release
with `../android/signing/orbit-local.p12` and refuses to continue unless the resulting certificate is
the one already on the phone, so the build upgrades it in place and keeps its data.

`verification/milestones-20260917/install.py` performs the installation itself. It refuses any device
that is not the authorized SM-S938B, backs the installed APK up first, upgrades with
`install --user 0 -r`, then verifies the installed hash and records the version and install times. It
never launches the app, changes a permission or clears data.

The owner supplied the wireless address, and the build is installed: `com.mani.orbit` moved from
versionCode 2 / `2.0-native-dev` to versionCode 3 / `2.1`, the installed hash equals the signed
candidate, and the original first-install time is unchanged, so the app data survived. The previous
APK is kept at `verification/milestones-20260917/phone-installed-before.apk`
(`0949788006f61045ac367d1061eadf9d149ad4658054cf2383af3038d6e673e4`). The app was not launched: the
first native launch against the owner's real data is theirs to make.

## 7. What is still open, and what each one needs

Everything below is blocked on hardware or on an account the owner controls. No local work remains
behind them.

| Gate | What it needs |
|---|---|
| R2 — paired Data Layer with two app versions | Either the Wear OS companion app on a paired phone emulator, which the Play Store only installs after a Google sign-in, or the physical Galaxy Watch. The transport, negotiation, queues and receipts are implemented and pass 68 phone and 18 Wear checks; what is missing is delivery over a real radio. |
| G1 / G2 / G3 — optics, readability, sustained quality | The physical S25: frame pacing, thermal headroom, battery cost and the owner's visual acceptance. |
| R1 — trace and frame cost | The same physical performance gate. |
| W2 / W4 — host typography, insets, Watch-face formats | A physical Watch and its real hosts. |
| W6 — haptic feel and paired command timing | A physical pair. |
| Samsung sensor arbitration, accuracy, screen-off cadence | The authorized Watch with developer-mode access. |
| First native launch against the owner's real data | The build is installed; the owner opening the app is what qualifies the migration. |

## 8. Boundaries

Every check ran on `emulator-5580` (HealthAutonomousApi36) and `emulator-5582` (HealthWearApi35). The
only physical action was the installation above, at the address the owner supplied: no launch, no
navigation, no workout, no measurement, no media command, no permission change and no clone. No
Watch was touched. `../dist/Orbit.apk`, the approved HTML reference, is unchanged.
No emulator data was wiped and the unrelated connected device was never addressed. Pre-edit copies of
every file changed in this batch are in `verification/milestones-20260917/before/`.

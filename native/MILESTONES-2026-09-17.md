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

## 9. The material, replaced — 18 September 2026

The owner pointed at their own BitChord and asked for Orbit's navigation bar, its cards and its glass
reflection to be live and real rather than the bounded edge-bend Orbit had been drawing. BitChord
gets that from **Kyant0/backdrop** (Apache-2.0), vendored as source; Orbit now vendors the same
library from that copy, under `phone/src/main/java/com/mani/orbit/backdrop`, and it compiles cleanly
against Orbit's newer Compose.

`Modifier.orbitFrost` keeps its signature and composes that pipeline instead: saturation, blur, a real
rounded-rectangle refraction with depth, a rim highlight that carries the light, and a cast shadow.
Its `engagement` parameter became a lift. A lifted surface bends about three times as much of what is
behind it, splits the light into colour at the rim past a third of the way up, widens that rim,
deepens its shadow and thins its own tint so more of the page shows through — the exact amounts
BitChord uses. The rim's light turns with the contact and returns to rest on release.

Everything is on it: the Explore bar and its travelling destination lens, the header buttons, the date
panel, the Health cards, the Home deck, the Watch-reading cards and the shared workout surface, so
the session cards, the week card and the activity tiles refract whatever the route is showing —
album artwork included. A filled control such as the Pause pill keeps its solid fill. Held, dragged, resized and folded
surfaces lift; a held card thins into glass instead of pinching. Because a card cannot sample the
recording it is drawn into, the page records a surface layer beneath the route content and Home
records the globe on its own layer for the deck to refract as it travels over it.

Two checks caught real things. The lens test asserted the interior could never change — it must now,
because a lifted surface thins its tint, so it asserts the thinning and still forbids a page-wide
warp beyond the shadow's reach. The rendering test found the readability tier had lost its draw
boundary, so a ticking timer redrew the surface under it; every tier ends in that boundary again, and
the measured invalidation counts are zero for all three.

`GLASS-OPTICS.md` records the contract and the amounts. `../THIRD-PARTY-NOTICES.md` records the
vendoring, and one thing worth the owner's attention: the integration file carries BitChord's
arrangement and tuning, and BitChord's own glass glue is adapted from Echo-Music, which is GPL-3.0.
No Echo or BitChord source was copied, but that file is the piece to review if Orbit is ever
published under terms incompatible with GPL-3.0.

## 10. The morphing-menu motion — 18 September 2026

The owner supplied `morphing-menu.zip`, a React navigation bar that compresses into an expanded list,
as the animation and interaction they want. Its motion model is now Orbit's, adapted where Orbit's
own shape differs.

Ported as specified:

- **The shell morph is two phases.** A deliberate open or close first compresses the shell out of its
  own footprint — toward a 280dp-wide, 32dp-tall pill, over 100ms on the reference's own easing — and
  only then springs to its destination and fills out again, bouncier opening than closing. A
  cancelled compression never reaches its spring, so a rapid reversal cannot resume a stale second
  phase; `ExploreIslandTest` drives that reversal on a paused clock and checks it.
- **The bar blurs away while the shell is compressed** — alpha to zero, 0.8 scale, 8dp blur — and
  returns as the shell fills out. It comes back rather than staying hidden, because unlike the
  reference's bar Orbit's is the anchor the shell grows from and carries the live workout time.
- **The rows cascade.** Each row in the expanded body arrives from 48dp below, unblurring from 4dp, a
  beat after the one above. The reference staggers this with fixed delays after its transition; Orbit's
  shell is also draggable, so the stagger lives in the progress domain instead — the rows cascade
  under a finger carrying the bar exactly as they do when it springs, and a reversal takes them back.
- **Press scaling**: an action presses to 0.96 over 100ms on the reference's easing.
- **Reduced motion** snaps all of it, as the reference does.

Not adopted, and why: the reference's drill-down panels and its More list. Orbit's launcher has three
destinations fixed by its own design rules — Body, Workouts and Health — so there is nothing to drill
into, and adding a level would change navigation rather than motion.

## 11. Physical qualification — 18 September 2026

The owner connected their Galaxy Watch Ultra, which closed most of what had been waiting on hardware.
Both apps are installed and signed with the same certificate, which is what lets the authenticated
Data Layer accept the pair. Evidence: `verification/milestones-20260917/hardware-qualification.json`
and the captures in `verification/milestones-20260917/watch/`.

| Gate | Result |
|---|---|
| Paired Data Layer (R2) | **Qualified.** With both apps installed, the Watch's Connection page reads "No pending readings · Synced with phone", and Sync now drains and returns to it. Real delivery over the paired radio. |
| Watch geometry and fonts (W2) | **Qualified.** The Today route renders on the real 480×480 round display — 225.9dp across, density 340 — with its heading, state line, both actions and the page indicator inside the circle. The layout tests bracket that at 192 and 228dp. |
| Watch-face surfaces (W4) | **Qualified.** The Tile provider and both complication providers are registered on the device against the real host permissions, beside Samsung's watch-face runtime and complication helper. |
| System edge Back (W3) | **Qualified.** With predictive back on, a real left-edge swipe out of History returns to the Orbit home Activity: the shared Back surface hands the gesture to the OS rather than competing with it. |
| Widget host (W5) | **Closed as not applicable.** The Watch runs Wear OS 6 (API 36), new enough for the grouped widget host, but does not declare `android.software.app_widgets` and carries no widget host package. |
| S25 frame pacing and thermal (G1/G3/R1) | **Qualified.** The full glass pipeline on the device: 1,470 frames, 3.27% janky, median 7ms, 90th 10ms, 95th 12ms, no missed vsync, AP at 51.9°C with thermal status 0. |
| First native launch against real data | **Qualified.** The app opens on the owner's own profile and imported Samsung history — Measurements reads 84.0 kg, latest 18 August 2026. Nothing was edited. |

Four things are still open, and each is open for a reason rather than for want of work:

1. **Two app versions over the paired Data Layer.** Reaching it means deliberately downgrading one
   real device below versionCode 3, which Android only allows by uninstalling — and that would
   destroy the owner's data. Not risked.
2. **Physical haptic feel and visual acceptance.** The owner's judgement, not a measurement.
3. **Samsung sensor arbitration, accuracy and screen-off cadence.** These need a deliberate
   measurement the owner starts while wearing the Watch; running one from here would write a
   fabricated session into their real history.
4. **Host expiry and fallback for a stale complication.** Depends on which watch face the owner puts
   the complication on, which is theirs to choose.

Nothing in the implementation is waiting on any of them.

## 12. The material pass — 18 September 2026

The owner sent a photograph of the Steps route with three complaints: the folded card was clipped,
the app's glass and animation were inconsistent across routes, and the Explore bar was not real
liquid glass. Sampling the live screen settled all three at once — the card was exactly `#15141A`
and the bar exactly `#28262E`, which are the two *readability fallback fills*. No glass was
rendering at all on a signed build on an S25 Ultra, and the two fallback colours were the two
shades they could see.

Three defects, each fixed at its source:

1. **The quality policy parked the app in the no-glass tier.** Three late frames dropped it from
   frost to readability, and full optics were gated on a thermal *headroom* forecast that Samsung
   does not provide, so the lens was unreachable on the target phone. Measured conditions now never
   reach readability: heat and frame pressure cost the refraction and leave the glass. Readability
   belongs to the owner's Reduce transparency preference, to a platform with no blur, and to their
   own battery saver. The policy also opens at the best supported tier instead of climbing to it
   after five seconds of evidence.
2. **Every surface decided its own look.** `orbitFrost` took a tint, an opacity and a legibility
   shield, and eight call sites passed eight different combinations. Those parameters are gone.
   There is one shade, one hairline, one fallback fill, one `orbitPanel` for panels and one
   `orbitControl` for everything that sits on them, and one motion vocabulary in `OrbitMotion.kt`.
   Panels lift; controls press. The flat panels that were never glass — the sleep summary and stage
   breakdown, the workout week, empty-day and section panels, the segmented selectors — are on the
   material now.
3. **The fold cut the card with a rectangle.** A folded card showed square corners the opened one
   never has. The fold is a window with the card's own corners, and both cut edges carry the same
   hairline as every other edge.

Removing the bar's opaque backing broke two accessibility checks, and they were right to break: a
chart passing under the bar dropped its caption to 3.6:1. The replacement is a multiplicative dim of
the sampled backdrop rather than a plate over it — 30% of the backdrop's brightness reaches every
surface, which tames a bright page, leaves a dark one alone, and keeps the structure the refraction
bends. That holds at least 5:1 over anything, pure white included, and the contrast preference adds
a backing on top for 7:1. The same measurement showed that lift was growing the blur faster than it
thinned the tint, so picking a surface up showed *less* of the page than leaving it at rest; lift no
longer touches the blur.

The owner then reported that the Explore island opened "like a couple of different states one after
another" and that the bar's own animation was too strong. Both came from the same place: the shell
compressed for 100ms *before* it travelled, so nothing moved during the first beat, and the bar left
under an 8dp blur. The gather now runs alongside the journey as squash and stretch on one travel, at
a third of its former depth; the bar leans into it instead of departing; the rows start at 12% of
the travel instead of 30% and overlap. The island's bespoke second rim is gone — the material's own
rim is the only rim on every surface.

## 13. The fold and the loaded spring — 18 September 2026

Three more reports from the owner, all about motion rather than colour:

**"When opening and closing cards or the explore it thickens then thins itself, breaking the
effect."** Lift was being driven by *travel*: the island read it from `abs(velocity)` and the deck
cards from the fold's own progress, so every open and close pumped the glass thicker and then
thinner again with no finger involved. Lift now means contact and nothing else — a finger pressing
or carrying a surface. A surface that is merely unfolding is the same piece of glass it was at rest.

**"When pressing the explore the card should start its animation process, like a spring hold and
then release and it animates and goes up."** The shell now gathers under the press and waits there
for as long as the finger stays, and the release lets it travel from that loaded pose. One contact
reads as one movement instead of a tap followed by a separate animation.

**"The opening and closing of the 4 cards is really buggy."** Two defects, both visible frame by
frame in a screen recording of the fold. The growing window was a hard edge sweeping down through
the card, so it sliced every line of text it passed through the middle; a travelling cut now feathers
over 26dp and only a cut that is standing still carries the material's hairline. And the cards behind
the front one were drawn as 30%-alpha ghosts of themselves, which read as grey slabs appearing from
nowhere; they are solid objects the whole way now, and the card above simply clips them until they
are out.

The sleep summary was the one panel padded on two sides instead of four, which pushed its two round
day controls into the panel's own top corner with nothing between them and the curve. It is padded
like every other panel now.

## 14. Two shades, one page — 18 September 2026

The owner's last report on the material was that the Explore bar and the deck cards were still two
different shades, and the measurement agreed: the folded card read `#19181C` and the bar `#131215`.

Neither surface was wrong about its own tint. A card cannot sample the recording it is drawn into,
so the deck samples the globe's recording while the floating bar samples the page's — and the
globe's recording stopped where the globe stopped. A folded deck sits *below* the globe, so every
folded card was sampling the edge of a recording that did not reach it instead of the page behind
it. The globe now records across the whole page, and the two surfaces measure `#151416` and
`#131215`: a drift of 2, which is the shared top-to-bottom sheen read at two different heights.

`HomeTest.theFoldedDeckAndTheFloatingBarAreOneShadeOverOnePage` holds that: it renders the real
folded deck and the real bar over one page and requires them within 4 of each other per channel.
Reverting the recording's bounds puts the drift back to 7 and the test fails, so it has teeth. The
folded deck clears its cards' semantics, so the deck itself carries the tag the test measures from.

The folded deck also picked up two fixes in the same pass. Its cards were stroking the material's
hairline even when the card above still covered them completely, which left a stray line lying
under the stack; a card with nothing visible now draws nothing at all. And the front card's fold
was a fixed 146dp that landed on the last row's baseline — it now comes from the card's own facts
block plus its padding, so the fold lands in the gap under the row at any text size.

The press-to-load then broke the choices drag, and the owner found it immediately: holding Health
and dragging to Workouts "bugs out". The handle's gather was firing on *any* contact inside the
island, so resting a finger on a row shrank the whole shell and slid those rows out from under that
same finger. Only the handle loads the spring now.

Neither of the existing drag tests could see it, and the reason is worth keeping: a gather is a
`graphicsLayer` scale, so every node keeps exactly the layout bounds it had while the pixels move.
`holdingAChoiceLeavesTheShellWhereTheFingerFoundIt` captures the handle while a finger simply rests
on a choice — before it travels, which is when the gather would happen — and compares that capture
with the resting one. Without the guard the handle's own capture comes back 632px wide instead of
678, and the test fails on the first assertion.

## 15. Sleep, the crash, the ring and the lag — 18 September 2026

**Why the 16th and 17th had no sleep.** Samsung Health had both nights; Orbit's import never reached
them. The heart decoder rejected a whole record over ordinary sensor noise — a series sample whose
timing ran past its own record — and one rejected record aborted the entire import, so every type
read after heart (sleep, nutrition, water, body, oxygen, exercise) stopped arriving. The catch-all
status line hid which record it was; the failure is now logged with its stack, and the log named
`SamsungRecordCodec.kt:38` at once. The heart decoder keeps readings by their start, sorts a series,
drops only samples that carry no reading, and widens a bound that sits on the wrong side of its own
average. And one unreadable record is now that record's problem: it is skipped, logged and counted,
and the import carries on. On the owner's phone the next import read past heart, skipped one malformed
oxygen record and two energy-score records, and finished — the 16th, the 17th and that morning's
sleep all appeared, with full stages.

**Why the app then crashed on launch.** Fixing the import let oxygen arrive for the first time, and
the watch stores oxygen a row per minute through every night. The projection sent every row raw to
the screen: roughly fifty megabytes of string built on the main thread, and an OutOfMemoryError on
every launch. The screens only ever use each day's low, high and latest reading, so that is all that
crosses now — one row per day. On the owner's data the whole payload is 4.1 MB (oxygen 347 rows),
and a check warns by type if it ever grows past 12 MB again.

**Live heart rate.** Heart now reaches the phone again (72 bpm on the owner's phone after the fix).
Samsung Health delivers the watch's heart rate on Samsung's own schedule, often most of an hour late,
so the phone now shows whichever is newer: Samsung's latest, or the latest valid reading Orbit's own
watch app has delivered, checked every five seconds while the app is open. On the owner's watch,
Orbit's app was delivering steps but no heart rate: its background heart collection needs the heart
rate and background health access granted to Orbit on the watch.

**The ring.** The owner replaced the dotted globe with a reference image: broad sheets of particles
folding round the number. The Home orb is now that — four closed sheets in 3D, each undulating in
radius and depth, breathing in width and twisting along its length, leaned back with perspective and
drawn additively, so the bright crests are simply where a sheet turns edge-on and its grains pile up.
The dot-matrix number, the swipe-for-metric and tap-for-period gestures and the swell on a new number
all carry over. `theRingKeepsTheNumbersCentreClearAndClosesOnItself` holds that no grain ever enters
the number's centre and that every sheet closes on itself without a seam.

**The lag.** The owner reported swiping up and down was still laggy, and it measured badly: 30–34% of
frames janky, 90th percentile 54–83 ms. Guessing was wrong once (the shadow re-rasterising was real but
small), so every step after that came from a trace of the phone's render thread:

1. The ring rendered as tens of thousands of individual point shapes — about 35 ms of rendering on
   every animated frame, idle or not. Every grain is now one quad in a single triangle mesh: a frame
   that took 46 ms to render takes 6.
2. Each deck card rendered itself offscreen, glass and all, on every frame of a swipe, only so its fold
   could feather. The feather now fades the card's content in a light layer that exists only while the
   fold moves, and the glass renders straight to screen with a crisp edge.
3. A shadow and a rim that animated with lift were re-rasterised as blurred masks every frame. The
   shadow is rasterised once at a fixed spread and deepens through its layer alpha; the rim, under a
   pixel wide, no longer carries a blur at all. The vendored library also no longer re-records a
   shadow when only its alpha changes.
4. A fresh draw lambda on every recomposition forced a full ring rebuild at the start and end of every
   swipe; the ring's draw block is stable now.

Deck swipes went from 90th percentile 54 ms and worst 78 ms to 19 ms and 35 ms; the Explore bar drag
sits at 90th percentile 22 ms. At idle the ring costs a few milliseconds a frame.

The test emulator segfaulted three times under software rendering while tests captured real content;
it now runs on the host GPU, where the full suite passes: 125 phone tests.

The owner found the first ring too cluttered, and its shrinking and growing "horror". Both were real
in the frames. Opening the deck scaled the ring to 76% and the number to 86%, so the number slid into
the ribbons; the ring was also scaled as a stored picture, which minified tens of thousands of fine
grains into a noisy blob; and it pulsed bigger and smaller on every swipe, tap and change of number.
The ring is now three thin dotted strands with room round the number, dim enough that overlaps glow
rather than burn white, with no glints and a slower drift. The ring and the number shrink as one
piece, and the ring is redrawn sharp at each size. Nothing pulses: a swipe turns the ring to the next
metric and a tap nudges it round.

## 16. A ring like the reference, and live heart rate and steps — 18 September 2026

**The ring.** The calm three-strand ring was calm, but the owner held it against the reference and it
looked nothing like it. What makes the reference read as volume rather than clutter is light: most of
each band faces the viewer and is a faint dotted mesh in deep indigo, and only where a band turns
edge-on does it catch the light as a bright lavender ridge. The ring is now four broad bands lit that
way — a rim light by how edge-on the surface is, a little key light from the upper left, additive
grains so a fold's own density brightens it, and a few glints only on lit folds. The number stays dot
matrix, and the ring and number still shrink as one piece, redrawn sharp at every size.

**What it cost, and what was fixed.** Measured on the owner's S25 Ultra with the render thread traced
(the phone was warm and charging at the time, so it ran at 60 Hz and the frost tier):

1. Every grain of a band's cross-section faces the same way, so the light is now worked out once per
   cross-section rather than once per grain.
2. The fold had the ring re-recorded on every frame of a deck swipe, copying its 2.5 MB of vertices
   each time. The ring is now recorded once per step of its flow, and a fold redraws that recording at
   its new size: the swipe's UI-thread work fell from 4.1 to 1.9 ms a frame.
3. The flow clock asked for a step every 1/30 s exactly; two 60 Hz frames come to a hair under that,
   so it waited for a third and the ring flowed at 20 frames a second. It now flows at 30.
4. The Home scroll frost, invisible until the open deck is scrolled, still rendered a full-width layer
   and a blur of the page on every frame the ring moved and every frame of a swipe. An invisible frost
   now draws nothing.

Deck swipes: legacy janky frames 7.4% → 2.7%, GPU time per frame p50 10.3 → 9.0 ms. The ring's idle
step costs 4.5 ms on the UI thread and about 8 ms of GPU, thirty times a second; the frames that open
the deck still sit near 14 ms of GPU, inside a 60 Hz frame but not a 120 Hz one.

**Live heart rate.** Orbit's phone now asks the watch for live heart rate while it is on screen. The
new `orbit_live_v1` family (see [PROTOCOL-COMPATIBILITY.md](PROTOCOL-COMPATIBILITY.md)) holds a
45-second lease on the watch, renewed every 20 seconds and released when Orbit leaves the screen. The
watch answers at once with its newest valid heart reading and what it can offer, flushes Health
Services so anything it has measured since its last batch is delivered, and while the lease holds
pushes each new valid heart reading the moment it is captured — every second or two while a live
recording runs on the watch. The phone no longer polls: journal commits and live pushes both update
today's heart rate the moment they land, and the reading only ever moves forward, whichever path
brings it. The Home heart card says whether the reading is Samsung Health's or the watch's, and if
Orbit on the watch lacks heart or background health access it says so ("Live from watch · Allow heart
access") instead of silently showing an hour-old number.

**Nobody presses record.** The owner, wearing the watch, asked that it simply happen. Opening Orbit
on the phone now starts the watch's live heart recording by itself — Android allowed the health
foreground service to start from the phone's message on the owner's Galaxy Watch Ultra (Wear OS 6) —
and it streams to the phone and records nothing. Leaving Orbit stops it within about five seconds
(the release message, or the lease lapsing within 45 s if that is lost); coming back restarts it in
about one. Stopping it on the watch keeps it stopped until the phone lets go. On the owner's phone the
Home heart rate read "106 bpm · live from your watch" and moved 106 → 102 → 103 without the watch being
touched.

**Live steps.** The owner then walked with the watch and left the phone behind: the watch counted,
the phone stood at 917. Samsung Health merges phone and watch steps but hears from the watch on
Samsung's schedule. The watch now pushes its own count since midnight with every answer and every
passive capture, and the phone adds the steps the watch has counted since Samsung last caught up to
Samsung's total ([WatchStepLead](phone/src/main/java/com/mani/orbit/WatchStepLead.kt)): any rise in
Samsung's total covers the oldest of those steps first, so carrying both devices counts nothing twice
and Samsung catching up never makes the number go back. One more fault hid behind it: a daily count
starts at midnight, before any clock correction since boot, so the watch flags it time-uncertain once
its clock has been nudged, and the live path had been discarding every step count for it. On the
owner's phone, walking with the phone left behind: 1,093 → 1,114 → 1,127 → 1,135 → 1,167 → 1,185 in
three minutes.

The heart card's "Readings · samples" fact is now **Resting**: each day's calmest hour, averaged over
the period.

Verified on the owner's phone and watch as above, and on the emulators: the wire format, lease bounds,
auto-start and decline rules, push rules, the step lead and forward-only ordering (phone 17/17 and
watch 8/8 targeted).

# Orbit × BitChord — Full Video UI, Interaction and Glass Audit

**Source:** `1000165779.mp4`<br>
**Recording reviewed:** `00:00.000–02:53.802`<br>
**Audit date:** 12 September 2026<br>
**Deliverable:** Video-grounded repair specification, not a redesign or a source-code audit.

> **Verdict:** Orbit already has a distinctive visual centre. What is missing is continuity: continuity of visible data, of navigation behaviour, of moving surfaces, and of the material used by controls. BitChord’s bottom navigation reads as a translucent object over content. Much of Orbit reads as grey containers with highlighted edges, while its music-focus controls introduce a third, largely opaque design language. Increasing blur alone will not close that gap.

## 1. Scope, evidence and confidence

The complete recording was inspected chronologically using half-second samples, with denser approximately 5–12 fps inspection sequences around transitions, controls and suspected failures. Selected frames were also inspected at the original resolution. This is full-duration visual coverage, not a claim that every encoded frame was individually inspected.

The file is 1080 × 2340, approximately 173.802 seconds, and contains 7,795 video frames. It has **no audio stream**. Its variable capture cadence and approximately 44.85 average encoded frames per second are **not measurements of either app’s rendering performance**. No GPU trace, live touch-event log, accessibility tree, source repository or installed APK was inspected for this audit.

All timecodes below refer to the supplied recording, not the phone’s clock. Transition timestamps are approximate; use the surrounding interval when reproducing them.

The implementation recommendations assume the previously described standalone Orbit Android WebView application. They do not assume that its current source matches an earlier repository revision. BitChord is a visual comparator here: its actual rendering implementation has not been reverse-engineered in this audit.

### Evidence labels

| Label | Meaning |
|---|---|
| **Observed defect** | The recording shows an undesirable visible result, such as loaded content disappearing. The underlying code cause can still be unknown. |
| **Observed UX gap** | A visible inconsistency, readability problem or confusing interaction. This may be intentional implementation, but it needs refinement. |
| **Verify on device** | A suspicious sequence without enough evidence to establish a persistent fault. Reproduce and instrument before changing unrelated code. |

**Priority:** P1 means address in the first repair pass because the issue affects trust, primary interaction or a defining experience. P2 means complete before considering the app visually coherent. P3 is lower-risk polish. A P1 visual issue is not a claim of a crash or data corruption.

### Preserve these product decisions

Keep the rotating dot-matrix orb, metric switching, expandable data deck, Explore launcher, existing Body/Workouts/Health destinations, workout workflow, and phone-music integration. Preserve sharp foreground artwork and readable data. Apply the material and motion improvements to these existing structures.

Do not replace the app with BitChord’s layout. Do not add decorative rainbow refraction, transparent magnifying bubbles, extra hero objects, glowing filler or a card for every number. Negative space is not automatically a defect; unexplained empty containers and disconnected controls are.

## 2. What the comparison actually demonstrates

### The glass difference

At approximately `00:33–00:40`, especially `00:37.5`, BitChord’s navigation contains broad, blurred colour from the content beneath it. The rail and selected segment feel related. The rim is restrained, while the icon and label remain visually separate from the material.

Orbit’s Explore bar, chart cards and Body selector are substantially more uniform, dark and edge-defined. In music focus, the selected Elapsed/Remaining segment becomes an almost solid off-white pill. The focus screen therefore does not simply have “less glass”; it uses a different control language altogether. **[Video: 00:07.5, 00:21, 00:37.5, 01:35.5; evidence E01, E09.]**

There is an important confound: BitChord places its navigation over colourful artwork; Orbit often places controls over almost black content. A correctly filtered black background will still look black. The recording establishes a **material mismatch**, not proof that Orbit has no functioning backdrop blur. Compare the same candidate surface over black, text and artwork before diagnosing the rendering pipeline.

| Dimension | BitChord in this recording | Orbit in this recording | Required direction |
|---|---|---|---|
| Backdrop | Visible, low-detail colour passing through the navigation | Mostly uniform dark fills; separate artwork blur in focus | Verify actual backdrop sampling and tune by surface role |
| Selected segment | Related to the surrounding rail | Dark bevelled pill in Body; off-white pill in focus | One shared segment component with controlled variants |
| Edges | Thin optical boundary with restrained highlight | Repeated bright outlines and raised-looking rims | Reduce the outline’s dominance; use a consistent edge treatment |
| Foreground | Navigation symbols remain sharp over blur | Some moving labels fade or overlap; dotted timer competes with artwork | Keep the foreground independently readable |
| Motion | Navigation material and selection appear coordinated | Deck, charts, artwork, labels and controls sometimes move in different phases | One transition owner per interaction |
| Hierarchy | Feed, player and navigation have distinct roles | Hero, stack, launcher and overlays sometimes compete for the same space | Explicit layer and occlusion rules |
| Consistency | A recognisable bottom-control family | Home, Body, history and focus feel like separate component sets | Shared geometry, typography, materials and state feedback |

**Do not treat the reference as flawless.** Around `00:34–00:38`, BitChord’s large and compact “Listen Now” headings visibly overlap. That is not a behaviour to copy. The useful reference is its material treatment and control coordination, not every frame of its UI.

### The three biggest differences in feel

**Objects versus layers.** Orbit’s moving parts frequently reveal their implementation as overlapping panels. A premium transition should preserve the identity of the object being moved, not expose several competing representations of it.

**Immediate meaning versus interpretation.** The reference navigation is consistently navigation. Orbit’s Explore control can first collapse detail and only then reveal destinations. The user should not have to infer what the same control means in the current layout.

**A coherent component family versus local styling.** Orbit’s dark Body pill, plain history slab, lavender workout action, warm gradient focus action and white timer pill could belong to different applications. Matching border radius alone will not unify them.

## 3. Full recording coverage

This chronology includes sections that work, not just defects. It also includes the final interaction attempts rather than stopping after the comparator.

| Recording interval | Interaction inspected | Findings / preservation notes |
|---|---|---|
| `00:00–00:05` | Steps hero and first deck expansion | Orb identity is clear. Intermediate chart/text overlap weakens the physical deck effect. F09. |
| `00:05–00:11` | Repeated deck expansion and reversal | The same layered/ghosted content appears during reversals. Floating footer needs an explicit relationship to the deck. F06, F09. |
| `00:11–00:15` | Explore opens; Body is selected | Existing compact launcher is worth keeping. Opening it competes spatially with the lower summary. F05–F06. |
| `00:15–00:24` | Body Weight/Fat/Muscle/Lean changes | Selected surface, label, orb and chart do not always transition together. Selected labels lose clarity mid-motion. F11–F12. |
| `00:24–00:31` | Further Body gestures, history ranges, system UI reveal | Preserve functioning metric changes. Standardise header/inset behaviour and chart readability. F08, F18–F19. |
| `00:31–00:41` | Android launcher and BitChord comparison | Main glass reference. Colourful underlay matters. Duplicate “Listen Now” heading is a reference defect, not a target. F14–F16. |
| `00:41–00:52` | Return to Body; metric/range changes and chart inspection | Chart scrubbing produces dates and values. Some transition phases fade/rebuild content. Back transition briefly overlays scenes. F11–F13. |
| `00:52–01:00` | Workouts overview, weekly chart and expanded history | Day selection works. Expanded history becomes a dense grey slab with weak group hierarchy. F20–F21. |
| `01:00–01:11` | History scrolling, user annotation and return | The red “ew” annotation is user/recorder markup, not an app defect. The underlying history presentation needs refinement. F20. |
| `01:11–01:18` | Strength setup and countdown | Large disconnected blank centre, unclear Music access status, optional-looking countdown interaction described ambiguously. F22–F23. |
| `01:18–01:31` | Active workout; first music-focus entry/exit | Session continues. Artwork, backing surface and foreground controls do not maintain one shared transformation. F10. |
| `01:31–01:50` | Repeated normal/focus transitions and timer-mode changes | Dotted clock is difficult over artwork, but it progresses. Focus introduces an opaque selector and different action colour. F15, F17, F24. |
| `01:50–02:06` | All details, pause/resume, focus and media interaction | Pause/resume is visible. Details rearranges the action hierarchy. Brief Buffering state needs device verification; audio cannot be assessed. F25–F27. |
| `02:06–02:11` | Finish, saved summary and updated workouts | Saved active/total/paused durations are internally plausible. Session count increases from 27 to 28. Preserve this path. |
| `02:11–02:17` | Health hub, then other home metrics | Health feels sparse. Demo readings and “Today” framing need consistent provenance/date context. F03, F28. |
| `02:17–02:24` | Intake details and scrolling | Comparison content vanishes inside an otherwise visible card for roughly two seconds. Strong confirmed defect. F01. |
| `02:24–02:37` | Meal chart inspection and repeated deck transitions | Dinner/Snack selections work. Displayed comparison delta does not reconcile with displayed rounded averages. F02, F04, F09, F19. |
| `02:37–02:43` | Steps, heart rate, sleep and chart interactions | Metric switching works overall; a brief empty main reading appears during a handoff. Preserve chart interactions while fixing state sequencing. F11. |
| `02:43–02:51` | Explore/deck stress interactions | Explore can behave as a deck-collapse step before becoming navigation; launcher overlaps summary content. F05–F06. |
| `02:51–02:53.8` | Final three-dot-control attempts | Touch indicators appear on/around the control without a visible menu in the available window. Some touches are near its boundary; reproduce before declaring the handler broken. F07. |

## 4. Repair register

There are **28 findings** below. They are not 28 proven backend bugs: the register deliberately separates visible defects, UX gaps and verification items.

| ID | Priority | Finding | Evidence class |
|---|---|---|---|
| F01 | P1 | Loaded period-comparison card loses its content | Observed defect |
| F02 | P2 | Displayed comparison arithmetic is inconsistent | Observed defect — presentation |
| F03 | P2 | Demo/date provenance is inconsistent across screens | Observed UX gap |
| F04 | P2 | Today and period-average labels are insufficiently distinct | Observed UX gap |
| F05 | P1 | Explore changes meaning depending on deck state | Observed UX gap |
| F06 | P1 | Launcher/deck overlap lacks a clear occlusion contract | Observed UX gap |
| F07 | P1 | Final overflow-button attempts show no response | Verify on device |
| F08 | P2 | System-bar, header and inset behaviour needs consistency | Observed UX gap + verification |
| F09 | P1 | Deck morph exposes overlapping content layers | Observed defect — transition |
| F10 | P1 | Music focus does not maintain a coherent shared surface | Observed defect — transition |
| F11 | P2 | Metric, selector and chart handoffs are out of phase | Observed UX gap |
| F12 | P2 | Travelling selection surface compromises label clarity | Observed UX gap |
| F13 | P2 | Scroll masks and floating controls cut into data presentation | Observed UX gap |
| F14 | P1 | No consistent, responsive frosted material system | Observed UX gap |
| F15 | P2 | Focus selector uses an unrelated opaque material | Observed UX gap |
| F16 | P2 | Borders and bevels dominate too many surfaces | Observed UX gap |
| F17 | P2 | Action colour and emphasis change across representations | Observed UX gap |
| F18 | P2 | Header, icon and type geometry vary by screen family | Observed UX gap |
| F19 | P2 | Chart secondary text and inspection hierarchy are too weak | Observed UX gap |
| F20 | P2 | Workout history is a visually heavy, weakly grouped slab | Observed UX gap |
| F21 | P2 | History duration and row-navigation affordances are ambiguous | Observed UX gap |
| F22 | P2 | Workout setup is spatially disconnected and status-unclear | Observed UX gap |
| F23 | P3 | Countdown instruction implies an unexplained action | Observed UX gap |
| F24 | P1 | Dotted workout timer loses legibility over artwork | Observed UX gap |
| F25 | P2 | All-details mode unnecessarily rearranges workout controls | Observed UX gap |
| F26 | P2 | Music and workout actions need clearer scope | Observed UX gap |
| F27 | P1 | Media buffering/play acknowledgement needs verification | Verify on device |
| F28 | P2 | Health hub looks incomplete despite existing destinations | Observed UX gap |

### F01 — Loaded period-comparison content disappears

**Evidence:** `02:19.5–02:24.5`, particularly `02:21.5` and `02:22.5`. **E02.**

The Intake comparison is populated, becomes an entirely empty rounded container while the surrounding charts remain visible, and subsequently repopulates. This persists across several samples and is not merely one halfway frame of a fade. There is no visible loading, empty-state or error explanation.

**Fix:** Keep an already-loaded comparison mounted and readable through scrolling and deck changes. Inspect visibility classes, animation cancellation, observation/virtualisation rules and metric-render handoffs. These are investigation targets, not established causes. Do not reset the card to transparent or empty just because it crosses a reveal threshold. If a calculation genuinely requires waiting, retain the previous clearly identified result until the replacement is ready, or show an explicit state.

**Acceptance:** Repeat the exact Intake scroll/expand/collapse sequence at least 20 times, including rapid reversal. The container must never be visible and unexplainedly empty when data already exists. Confirm headings and values appear as one coherent result, rather than a delayed heading after numbers.

### F02 — The visible comparison numbers do not reconcile

**Evidence:** `02:19.5` and `02:25.5`. Current daily average **2,078 kcal**, previous daily average **2,086 kcal**, difference **7 kcal lower**. **E08.**

The displayed integers differ by eight. This does not prove the underlying calculation is wrong: independently rounded averages and an independently rounded raw delta can produce this presentation. It nevertheless looks like a calculation error to the person reading the card.

**Fix:** Define one display-rounding policy. Either reconcile the delta with displayed values, expose enough precision to explain it, or clearly mark an approximate raw-data comparison. Keep full precision in the data model; do not “repair” stored measurements to make a screenshot add up.

**Acceptance:** Unit-test rounding boundaries, equal periods, negative/positive deltas and missing periods. Every displayed equation must be understandable at the displayed precision. A developer must be able to explain why 2,078 versus 2,086 would show seven before retaining that presentation.

### F03 — Demo provenance and date context are not carried consistently

**Evidence:** Body identifies demo data dated September 2025; home metrics use “Today” and “Mon 8 Sept”; workout history contains September 2026 sessions. See `00:15–00:31`, `00:59.5`, `02:13.5–02:25.5`. **E06, E09.**

This is not evidence that the phone’s clock is wrong. It is a context problem: demonstration readings are being presented within a product that also contains actual locally recorded workouts.

**Fix:** Give demonstration measurements a consistent, restrained provenance indicator and explicit sample date. Distinguish “sample day” from actual today. Carry that context into the orb, summary, charts and detailed page without adding repetitive warning banners. Do not make demo health data look live simply to make the UI feel finished.

**Acceptance:** From any home metric, a person can determine whether the number is demonstration, recorded locally or obtained from an integration. Navigating to workouts must not silently imply that every neighbouring health reading is equally live.

### F04 — Today’s total and a multi-day average need clearer labels

**Evidence:** Intake shows **1,979** in the orb and a later comparison with **2,078** as current daily average. `02:17–02:26`.

Those can both be correct. The problem is having to infer which interval each panel represents. “Current” and “previous” are particularly vague when the screen also says Today and has a seven-day chart.

**Fix:** Use explicit roles: “Today’s intake”, “2–8 Sept daily average”, and “Previous 7 days”, with the actual comparison dates available. Use “total” for a one-day total rather than borrowing average terminology. Ensure a tapped historical chart point does not silently change the comparison interval without relabelling it.

**Acceptance:** A screenshot containing the orb and comparison explains why their values differ without requiring an additional screen. Switching ranges updates both date bounds and aggregation labels together.

### F05 — Explore is overloaded as navigation and deck collapse

**Evidence:** Approximately `02:43–02:46`, with related behaviour earlier in the recording. **E05.**

With detail open, the footer interaction first collapses the deck; a subsequent interaction exposes Body, Workouts and Health. The visible Explore label has not clearly announced that its first job is now “collapse these details”.

**Fix:** Define a stable contract: tapping Explore opens or closes destinations. If opening it requires reducing the deck, perform that as part of the same coordinated transition. Do not consume one navigation tap as an unrelated prerequisite. A separate deck handle or gesture can own detail collapse. If a control deliberately changes function, its label and accessible name must change too.

**Acceptance:** From collapsed, expanded and partly moving deck states, one deliberate Explore tap produces the same understandable navigation result. Test fast re-taps and reverse gestures. The user must never need to guess whether a tap was missed or merely consumed by a hidden state rule.

### F06 — Launcher/deck overlap needs deliberate occlusion and input rules

**Evidence:** `00:12–00:14` and `02:45–02:53`. Expanded Explore covers the lower portion of the summary deck. **E05.**

Floating navigation may legitimately cover content. The current combination can leave summary rows visibly cut by another rounded surface, with several rear card edges competing behind it. This reads as accidental overlap rather than an intentional foreground layer.

**Fix:** Choose and implement one composition: either move the summary to a readable position, or clearly treat Explore as a temporary foreground surface with intentional background occlusion. Reserve sufficient scroll clearance for data in its settled state. Define whether outside taps dismiss the launcher and whether covered content is interactive. Remove inactive transition overlays from hit testing. Gesture propagation is a hypothesis to inspect, not a proven cause.

**Acceptance:** Exercise the cross-product of deck open/closed, Explore open/closed and metric switching. No settled state should leave essential summary values permanently half-covered. A tap on a destination must not also toggle the deck or inspect a hidden chart.

### F07 — Overflow-button response needs a targeted reproduction

**Evidence:** Approximately `02:52.5–02:53.8`. Touch markers appear on and around the top-right three-dot control, with no menu visible before the recording ends. **E05.**

Some touches are close to the button boundary, and the last attempt has a very short observation window. Therefore the evidence does not prove an entirely dead menu. It is still a high-priority verification because it occurs after several overlay/deck operations.

**Fix after reproduction:** Test deliberate centre taps with Explore closed, open and recently dismissed. Inspect the actual hit rectangle, pointer ownership, any transparent full-screen overlay, disabled state and menu mounting. Implement a visible pressed state and an appropriately sized target. If the action is intentionally unavailable, communicate that rather than leaving an apparently active control inert.

**Acceptance:** Centre and near-edge taps respond reliably after repeated navigation transitions. Record event receipt and resulting menu state. Do not close this finding with “could not see it in the video”; either reproduce/fix it or document a clean instrumented test.

### F08 — Header and system-inset behaviour needs one policy

**Evidence:** System bars reveal around `00:31` and during app switching; Orbit’s header/content positioning feels different across scenes. Some top content becomes crowded when system UI is visible.

System bars appearing after an edge gesture are not themselves an app bug. The issue to verify is whether the app maintains usable, intentional geometry when they appear and disappear.

**Fix:** Define the immersive/edge-to-edge policy at the Android host, then apply actual relevant insets consistently to the WebView content. Avoid hard-coded top offsets and double-applied padding. Keep back, overflow and bottom actions outside problematic interaction regions. Android’s guidance distinguishes system-bar, cutout and gesture insets; inspect the actual host rather than treating them as interchangeable. **[S4]**

**Acceptance:** Test hidden and revealed bars, app switch/return and keyboard appearance on the target phone. Titles and action targets remain accessible, with no unexplained jump, duplicate padding or footer trapped beneath system controls.

### F09 — Deck expansion exposes ghosted panels

**Evidence:** `00:01–00:04`, `00:05–00:10`, and repeated Intake/deck reversals later. **E03.**

During expansion, chart labels, lines and panel boundaries become visible beneath or through the summary before the deck has separated. The orb’s movement and the existence of rear cards are not the problem. The problem is readable content occupying contradictory layers at the same time.

**Fix:** Separate the animated surface from the content it reveals. Establish one deck progress value governing orb position, front-panel geometry, rear-shell exposure, content clipping and reveal timing. Rear shells may appear as a meaningful stack, but do not show their chart text before their readable region exists. Reversing should continue from the present geometry, not replay a new opening or closing sequence.

**Acceptance:** Inspect at normal speed and at intermediate progress points. No duplicated heading, chart grid behind unrelated statistics, one-frame unmasked content or stale floating shell. Reverse at roughly 25%, 50% and 75% progress without a snap.

### F10 — Music focus looks assembled from independently moving layers

**Evidence:** `01:23–01:29`, `01:30–01:34`, and further repetitions through approximately `01:50`. **E04.**

The artwork grows or shrinks separately from the blurred backing. Hard rectangular artwork edges appear during the move. Old workout statistics remain visible behind parts of the transition, while title, artist, seek controls and timer relocate at different phases. The result does not maintain a convincing identity from mini-player to focus surface.

**Fix:** Use one owner for the transition. Measure the actual mini-player artwork bounds and target bounds; coordinate crop, radius, position, backing exposure and foreground handoff. Permit only one readable representation of a shared label at a time. A deliberate opacity handoff is acceptable, but avoid spatially separated duplicates. Keep clear artwork and blurred backing as separate responsibilities. Cancel, reverse and clean up transition layers explicitly.

**Acceptance:** Enter/exit repeatedly, reverse mid-transition and repeat after pause/resume. The same artwork object should appear to travel between representations. No hard rectangle suddenly appears against the full-screen backing; no old metrics show through unintentionally; no invisible transition layer blocks subsequent controls.

### F11 — Metric state changes are visually out of phase

**Evidence:** Body switches around `00:16–00:19`, `00:23–00:29`, `00:43–00:45`; home metric handoff around `02:37–02:40`. **E09.**

The moving selector, selected label, main value and chart do not always present a single coherent transition. At one home handoff the orb’s central reading briefly clears. This is not evidence of a permanently wrong metric or missing dataset.

**Fix:** Commit selected metric, units, colour, dataset and range as one view state. Keep the old complete reading until the incoming one can be presented, or use a controlled paired transition. Do not clear a valid chart to prepare its replacement. During animation, avoid showing the new unit with the old number or a selected tab whose chart still reads as another metric.

**Acceptance:** Cycle forward/backward rapidly through every available metric. Every settled state matches its selector, unit and chart. Intermediate states remain readable and do not produce an unexplained empty hero.

### F12 — Selection labels should not disappear inside the travelling thumb

**Evidence:** Body selector interactions at `00:15–00:24` and `00:43–00:45`; timer selector while changing mode later.

The moving surface and label handoff can leave the selected area looking dim, blank or partially covered. This undermines the very navigation motion being copied from the reference.

**Fix:** Give the surface and labels independent layers. Move or deform the selection surface beneath sharp, consistently positioned labels. Keep selected/unselected contrast tied to an explicit state, with a short coherent transition. Do not stretch the text along with the pill or let a material overlay paint above it.

**Acceptance:** All labels are identifiable throughout drag and settle. Test tapping adjacent and non-adjacent options, rapid reversals and larger text. The thumb may move elastically; the words must not look submerged or smeared.

### F13 — Scroll masking and bottom clearance need refinement

**Evidence:** Body history inspection and Intake expansion/scrolling, especially `02:17–02:37`.

Charts pass beneath a strong dark upper boundary under the pinned hero. Some clipping is normal, but titles and first rows can look abruptly cut rather than naturally leaving a scroll viewport. Floating Explore also competes with the lower data region.

**Fix:** Establish a clear scroll viewport between hero/header and foreground controls. Place fades at that boundary, not across a settled card’s essential reading region. Account for the real height of the footer in scroll clearance. Avoid separate arbitrary masks on several ancestors. Keep the final chart, comparison and any tooltip fully reachable.

**Acceptance:** At each settled deck position, titles and values are either legibly visible or intentionally outside the viewport—not marooned in a permanent fade. Scroll to the last content and inspect it without needing to hide or fight navigation.

### F14 — Build one frosted material system, not more isolated blur effects

**Evidence:** Explore, Body selector, data cards and music-focus controls across the recording. Compare `00:37.5` with `00:07.5`, `00:21` and `01:35.5`. **E01.**

Orbit has dark surfaces and some softness, but it does not have a consistent responsive material. In many places the perimeter explains the object more strongly than the content behind it; elsewhere a completely different fill takes over.

**Fix:** First implement a controlled material test over the same underlay, then establish shared roles for floating navigation, interactive thumb, compact control and quiet data surface. Tune frost, tint, edge and shadow as a family. Over black, allow the material to be quiet rather than inventing coloured decoration. Over artwork, preserve broad environmental colour while suppressing detail beneath the controls.

**Acceptance:** The same component remains recognisably the same material over black, muted artwork and high-contrast content. Foreground text stays sharp. Approve Explore, one segmented control and one small button before propagating the system. See Section 6 for the implementation diagnostic.

### F15 — The focused timer selector is opaque and visually unrelated

**Evidence:** `01:31–01:50`, especially `01:35.5`. **E01, E07.**

Elapsed is selected with a nearly solid off-white capsule inside a dark rail. Body uses a much darker selected surface. The focus pill becomes a particularly conspicuous mismatch because it sits directly over artwork where a frosted response would be visible.

**Fix:** Use the shared segmented-control component. Keep a readability-protecting tint, but make the selected treatment belong to the surrounding frosted rail. Do not merely lower all opacity: that would make the label less readable. Support a deliberate high-contrast fallback, not accidental theme divergence.

**Acceptance:** Body and workout mode selectors look related without needing identical widths or content. In focus, selection remains obvious on bright and dark artwork without turning into a white sticker.

### F16 — Edge treatment is too repetitive and heavy

**Evidence:** Steps deck, Intake chart cards, Explore and Body selector throughout the recording.

Repeated bright upper rims, outlines and dark fills make surfaces look raised and moulded. When several are stacked, the outlines become the visual subject. BitChord’s rail gets more of its dimensionality from the underlying scene and a restrained boundary.

**Fix:** Define a small set of edge strengths. Keep chart/data surfaces quieter than floating controls. Use one subtle perimeter treatment, restrained directional light and only the shadow needed to explain elevation. Do not stack several borders and inset shadows to force “glass”. Retain rear deck edges only to communicate actual expandable layers.

**Acceptance:** In a grayscale screenshot, the first things noticed should be the metric and primary action—not three bright card rims. Adjacent components at the same elevation use the same edge logic.

### F17 — The same action changes its visual identity in focus

**Evidence:** Normal workout Pause is lavender; music focus introduces a warm salmon/orange treatment with a gradient. `01:18–02:06`.

Artwork tint can legitimately influence an ambient surface. It should not make the same workout action look like it has changed meaning or priority. The change also exaggerates the sense that focus is a separate app.

**Fix:** Define stable semantic action tokens across normal, focus, paused and details states. Keep artwork-derived colour restrained and separate from the meaning of Pause, Resume and Finish. An optical highlight is different from introducing a new decorative multicolour button theme.

**Acceptance:** Pause and Resume remain recognisable across representations and album artwork. Finish does not become more or less prominent simply because a track has different colours. Selection and disabled states remain clear without relying on hue alone.

### F18 — Header, typography and icon geometry need shared rules

**Evidence:** Home/metric headers, Body, Workouts, Strength setup and focus across `00:00–02:53`.

Back buttons shift between a minimal chevron and a large circular surface. Title sizes, spacing and optical alignment differ enough that moving between destinations feels like changing UI systems. Some supporting text is delicate while other views use large unstructured labels.

**Fix:** Define page-header variants by role, not by file: root metric, destination and active session. Share back-target size, icon stroke, title baseline and outer inset. Establish a compact type scale for hero reading, page title, section title, value, label and metadata. Keep dot-matrix type for the identity-bearing reading, not every informational layer.

**Acceptance:** Compare screenshots of every route side by side. Differences correspond to screen roles, not unexplained drift. Long titles, units and larger system text fit without displacing essential controls.

### F19 — Charts need stronger secondary hierarchy without becoming busier

**Evidence:** Steps, Body and Intake charts, including `00:46–00:49`, `02:24–02:37` and `02:39–02:43`.

Axis labels, dates, captions and selection annotations are often much weaker than the surrounding cards. The charts are interactive—Body dates and Intake meal values visibly update—but reading the result requires more visual effort than the primary orb.

**Fix:** Improve the contrast and size of necessary labels, reduce redundant grid emphasis, and make selection produce one clear date/value/unit readout. Keep the chart line and tooltip sharp above the material. Provide adequate hit regions around data points rather than requiring a precise touch on a tiny mark. Do not solve density by stripping away useful data.

**Acceptance:** Verify labels and selected readings on the phone at normal viewing distance, on bright and dark backdrops. Use measured contrast and target-size checks rather than claiming a precise failure from this compressed recording. Android recommends 48 × 48 dp touch targets; verify the equivalent rendered target in the WebView rather than confusing source-video pixels with dp. **[S6]**

### F20 — Expanded workout history reads as a heavy grey slab

**Evidence:** `00:58–01:07`, especially `00:59.5`. **E06.**

The history becomes a long, near-uniform panel with repeated rows, faint dividers and weak date headings. Its top scroll edge is abrupt. The large duration on the right has more authority than the activity/date structure needed to understand the list.

**Fix:** Retain a compact chronological list, but strengthen date grouping, distinguish activity from metadata, align duration consistently, and reduce the dominance of the enclosing fill. Introduce deliberate section spacing and a clean relationship to the fixed header. A full new card around every workout would add bulk without fixing hierarchy.

**Acceptance:** A long history is scannable by date, activity and duration without opening individual rows. Scroll at normal and fast speeds; the top boundary must not look like a square panel accidentally cut beneath a floating heading. Preserve position sensibly when leaving and returning.

### F21 — History durations and navigation affordances need clearer meaning

**Evidence:** `00:59.5–01:07`. Examples include a large `06:17` and a smaller `04:19`, plus a very small diagonal arrow.

Those represent different concepts, but both look like clock values. The arrow is visually weak, and the recording does not establish whether the full row is actionable or only the small icon.

**Fix:** Make start time and duration distinguishable through labels, grouping or an unambiguous duration format. Standardise short and hour-long sessions. Make the row a coherent navigation target with clear pressed feedback where details exist; use a consistent icon appropriate to the destination rather than a barely visible ambiguous arrow.

**Acceptance:** A new reader can distinguish “started at 04:19” from “lasted 6 min 17 sec”. Touching the row opens the expected details without relying on the tiny arrow. Test long activity names and sessions longer than an hour.

### F22 — Strength setup has disconnected spacing and unclear music status

**Evidence:** `01:11–01:15`. **E06.**

Configuration occupies the top, the primary start action sits at the bottom, and a large blank centre separates them. “Music access” is a faint secondary item even though music controls are available later. This does not prove permission failure; it shows insufficient status explanation.

**Fix:** Keep a calm setup screen but organise configuration, optional weight/energy explanation and session readiness into a coherent vertical group. Preserve a reachable bottom start action without making the screen feel unfinished. Make the music item state-aware: grant access, connected, select player or reconnect, as appropriate to the actual integration.

**Acceptance:** Setup explains what is ready, what is optional and what will be measured. Missing weight must not prevent a valid time-only workout. The music status must agree with the integration’s actual state and remain non-blocking where music is optional.

### F23 — “Touch the dots” is an unclear countdown instruction

**Evidence:** `01:15–01:18`.

The dotted 3–2–1 countdown proceeds automatically while suggesting that the user touch the dots. It is unclear whether touch is required, changes the countdown or is merely a visual interaction.

**Fix:** Remove the instruction if touch has no useful role, or describe the actual optional action accurately. Keep Cancel and the countdown’s start/end meaning clear. Preserve the existing dot-based visual identity rather than replacing a working countdown for stylistic reasons.

**Acceptance:** A person who does not touch the display knows the workout will start. A person who does touch it receives the documented result, without accidentally starting twice or cancelling through gesture ambiguity.

### F24 — The timer is progressing, but the dotted digits lose readability

**Evidence:** `01:31–01:46` and `01:59–02:05`. **E07, E11.**

Thin white dots sit over high-contrast details in the artwork. At smaller viewing sizes, the digit shapes are easy to misread. Native-resolution inspection shows `00:13` at approximately `01:31.5`, `00:17` at `01:35.5`, and subsequent remaining-time values progressing normally. **The recording does not establish a frozen timer.**

**Fix:** Give the timer a protected reading zone: a restrained local scrim, controlled neutral backing or sufficiently frosted support behind the digits. Tune dot spacing/weight if necessary while keeping the signature type. Do not blur the timer, blur the foreground artwork indiscriminately, or rewrite clock logic to address an optical problem.

**Acceptance:** Correctly distinguish 2/7, 0/9 and consecutive seconds over several busy, bright and dark artwork examples at normal phone size. The same time remains legible during entry/exit. Keep timer logic regression tests, but do not label an unproven timing failure as fixed.

### F25 — All-details mode changes the workout’s action hierarchy

**Evidence:** `01:50–01:57`. **E06.**

The details view introduces a different “In progress” presentation, repeats time information, and relocates the primary actions. A large lower area is unused. The user is inspecting the same session, but must reacquire the controls and hierarchy.

**Fix:** Treat details as a deeper view of the same session, retaining the identity of the header, main duration and action dock where possible. Group active, total and paused time with clear meaning. Keep state changes and save/finish feedback in stable positions. Do not remove useful raw session data merely to simplify the screen.

**Acceptance:** Open details while recording, paused and immediately after resuming. Pause/Resume/Finish remain predictable and the same underlying session continues. Returning restores the prior normal/focus representation without a layout jump or duplicate action.

### F26 — Music and workout controls need clearly separated scope

**Evidence:** Focus screen contains playback controls and a nearby button labelled only “Pause”. `01:31–02:06`.

Their visual grouping helps, but both are actions concerning something that is currently running. The more prominent warm Pause button can be interpreted as pausing playback rather than the workout.

**Fix:** Clarify ownership through grouping and explicit naming where needed: “Pause workout”/“Resume workout” versus music play/pause. Provide distinct accessible names for every action. Keep media state changes from altering workout state, and vice versa. Preserve a direct path to Finish without placing it in an ambiguous playback cluster.

**Acceptance:** A first-time user can predict which timer or player each control affects. Instrumented tests confirm music pause does not pause the workout and workout pause does not silently stop playback unless that is an explicit, separately chosen feature.

### F27 — Buffering/play acknowledgement needs runtime verification

**Evidence:** A brief “Buffering…” state appears around `02:00–02:01`; the view returns to “This phone”, with a play triangle and the position around `0:58`.

There is no audio in the file. A paused player can legitimately hold its position. The video cannot establish stuttering audio, failed playback or a broken media bridge. It does show a interaction worth checking because the result is not immediately self-explanatory.

**Fix after verification:** Compare requested action, reported media state and rendered control state. Distinguish loading, paused, playing, unavailable and error states; retain useful failure feedback long enough to understand it. Android exposes explicit playback states including buffering and error, but the actual bridge must be inspected before deciding where the mismatch occurs. **[S7]**

**Acceptance:** Test local/streamed playback, a slow connection, unavailable session and external playback changes. Capture media callbacks and an audio-enabled verification recording where permitted. The play/pause icon and progress must agree with actual playback, not merely the last button pressed.

### F28 — The Health hub lacks the finish of the other destinations

**Evidence:** Approximately `02:12–02:15`, especially `02:13.5`. **E09.**

The hub presents Body composition, Workouts and an SpO₂ reading, with substantial unused space. Existing routes are not framed as a complete, intentional overview, so the page reads as a placeholder between more developed screens.

**Fix:** Improve the organisation of what already exists: a concise overview, consistent row/surface treatment, clear destinations, and provenance/availability for measurements. Make absence of other connected data explicit where appropriate. Do not manufacture extra measurements or add ornamental cards to fill the screen.

**Acceptance:** The page communicates its purpose and the status of each available item. Every apparently actionable item has a verified destination or an explicit unavailable state. The current recording does not verify every Health route, so route completion requires direct testing.

## 5. Behaviour that works and must not be broken

The orb’s identity, basic metric switching, Body chart inspection, Intake meal selection, workout countdown, visible pause/resume and the save/history path all operate during the recording.

The saved workout at approximately `02:06.5` shows **00:47 active, 00:48 total, and 00:01 paused**. Shortly afterwards the overview changes from **27 to 28 sessions**, with the new Strength session visible. This is a coherent short-session result, not evidence of lost persistence. **[Video: 02:06–02:10; E10.]**

Elapsed/Remaining are two views of a continuing session. Samples include approximately **29:38** remaining at `01:39.5`, **29:34** at `01:43.5`, **29:19** at `01:59.5`, and **29:13** at `02:05.5`. Small discrepancies between independently rounded displayed seconds require precision-aware testing; they are not sufficient evidence of a broken session clock.

Other things not to misdiagnose:

- Lean mass includes muscle; the Body percentages are not mutually exclusive slices that must add to 100.
- Intake’s displayed 116 g protein, 228 g carbohydrate and 67 g fat are consistent with 1,979 kcal under the simple 4/4/9 calculation. This is not the same issue as F02’s comparison rounding.
- The red annotation over workout history and circular touch indicators belong to the recording interaction, not Orbit’s shipped visual system.
- A partly faded intermediate scene is not automatically a bug. The defects are contradictory overlap, lost readability or incorrect settled state—not the existence of animation.
- No crash, verified audio failure, measured thermal problem or proven frame-rate figure can be established from this recording.

## 6. Frosted-glass implementation specification

This is a proposed implementation direction, not an assertion about current CSS or BitChord’s internal renderer.

### 6.1 Diagnose the sample before tuning the effect

Build a temporary material test page inside the actual Orbit host. Show the same rail, selected segment and round button over three underlays: near-black, high-contrast text/shapes, and the same artwork crop used in focus. Move the underlay beneath a stationary surface.

The intended result is visible diffusion of detail beneath the surface, restrained transmission of large colour areas, and an independently crisp foreground. A static gradient that does not respond to the moving underlay is not sufficient evidence of live backdrop sampling. A quiet result over black is acceptable.

For the WebView implementation, inspect the computed `backdrop-filter`, fill alpha, ancestor opacity/filter/mask effects and clipping. Use background alpha rather than fading the whole control to obtain translucency. Do not apply `filter: blur(...)` to the element containing the labels. The CSS Filter Effects Level 2 draft describes backdrop filtering, semi-transparent surfaces and Backdrop Root restrictions; it also explicitly distinguishes those roots from ordinary stacking contexts. Treat it as a working draft and verify behaviour in the installed WebView. **[S1]**

A valid computed property alone is not proof that the intended scene is being sampled. Establish which rendered layer actually contains the underlay, particularly if native and web layers coexist. Inspect the current hierarchy rather than assuming all visible content belongs to one sampleable surface.

Use a debug-enabled build and Chrome DevTools to inspect the real WebView. The documented path is enabling WebView debugging in the host and inspecting it through `chrome://inspect`. Keep this diagnostic facility constrained to the intended development configuration. **[S2]**

### 6.2 Material roles

| Surface role | Intended treatment | Keep sharp / stable | Avoid |
|---|---|---|---|
| Explore rail and expanded launcher | Most clearly frosted persistent navigation surface; restrained environmental colour | Destination labels, icons and selected state | Uniform opaque slab; unreadable show-through |
| Body and timer selection thumb | Same material family as the rail, with a stronger selection edge/tint | Fixed label geometry and selected contrast | Off-white sticker in one screen, dark bevel in another |
| Back, overflow and collapse buttons | Compact control variant with predictable contrast | Icon weight, target size and pressed response | Oversized glowing rings; varying sizes without a role |
| Summary deck | Quiet dark material, enough separation to explain the stack | Statistics and units | Readable rear-card content leaking through the front |
| Chart/history surface | Low-distraction support; can be more opaque than navigation | Plot, axes, dates and inspection labels | Putting aggressive blur behind every chart |
| Mini-player | Compact foreground surface, visually related to navigation | Artwork thumbnail, metadata and playback actions | Duplicate text during morph; unrelated corner treatment |
| Music-focus background | Clear primary artwork with separate ambient backing | Artwork subject and main reading | Blurring the entire artwork to disguise poor composition |
| Workout action dock | Stable semantic controls over the focus background | Pause/Resume/Finish meaning | Artwork-driven changes in action meaning or hierarchy |

“One material family” does not mean every panel gets the same opacity, blur radius or elevation. It means the variants have an explainable relationship.

### 6.3 Layer contract

Use an explicit conceptual order: **scene → material/backing → optical edge → foreground content → interaction feedback**. These are responsibilities; they do not necessarily require five expensive separately composited elements.

The data belongs to the foreground, not inside the blur operation. The material should reveal the scene, not the implementation’s hidden duplicate content. Rear deck outlines represent depth; navigation edges represent interaction. They should not all compete equally.

Keep ornamental edge layers out of hit testing. Keep control hit areas stable as the visual shape moves. Avoid allowing a transition helper to become the permanent owner of an unrelated control’s events.

### 6.4 Tuning sequence

**First, geometry:** approve rail height, content inset, segment padding, corner curves and icon/text alignment before adding optical effects. A badly proportioned shape remains badly proportioned when blurred.

**Second, readability:** establish foreground contrast against the hardest artwork sample. Solve the timer’s protected reading zone before reducing surface opacity.

**Third, material response:** tune frost strength and neutral tint using the matched-underlay test. Use restrained scene-derived colour, not decorative gradients to simulate activity over an empty black background.

**Fourth, edge:** add the minimum boundary highlight needed to explain the object. Check that stacked surfaces do not create multiple equally bright outlines.

**Fifth, interaction:** tune press, selection and movement using the same approved geometry and material. Do not create new local variants to fix individual screenshots.

**Finally, performance:** profile the actual host. A renderer-specific expensive effect needs a deliberate fallback; that fallback must preserve layout and contrast rather than silently becoming another UI theme.

### 6.5 Approval test

Approve a single comparison board containing Explore collapsed/open, a two-option timer selector, a four-option Body selector, a round button, a summary card and a chart. Capture each over black and artwork in the real app.

Pass only when they look related, the foreground is readable, background response is visible where there is something to sample, and the animation does not reveal ghosts. Do not approve based on one static screenshot of a bright album cover.

## 7. Motion, gesture and state contracts

### 7.1 Separate state from animation progress

Track route, selected metric/range, deck state, Explore state, music-focus state, workout state and media state separately. A single loosely defined “expanded” flag should not implicitly mean different combinations of those concepts.

Then give each coordinated transition one progress owner. The deck can coordinate several visual properties without turning every property into an independent animation. The same applies to music focus. Data identity should not be reconstructed from whichever animation happened to finish last.

On interruption, retarget from the current presentation. Do not queue every tap until earlier animations finish. Ensure completion and cancellation both restore the correct clipping, visibility and hit-testing state.

### 7.2 Proposed interaction contract

| Action | Required meaning |
|---|---|
| Swipe the hero horizontally | Select another metric while preserving a coherent deck/navigation state |
| Expand/collapse the data deck | Change inspection depth for the current metric |
| Tap Explore | Open/close destinations, not silently perform only a prerequisite deck operation |
| Select Body, Workouts or Health | Navigate once; no underlying chart/deck action fires |
| Inspect a chart | Select a point within that chart; do not accidentally switch the whole metric |
| Open the mini-player | Change workout presentation to music focus; do not alter workout timing |
| Close music focus | Return to the prior session presentation through the same surface relationship |
| Pause/Resume workout | Affect the workout session, not merely the music |
| Play/Pause music | Affect media playback, not the workout clock |
| Back with an overlay open | Dismiss the appropriate foreground state before unexpected route departure |

### 7.3 Gesture ownership

Give each gesture an owner after a clear direction/intent threshold. Chart inspection, vertical detail scrolling and horizontal metric navigation must not all respond to the same drag. Preserve normal scrolling outside the custom interaction region.

For Pointer Events implementations, choose `touch-action` for the relevant region before a gesture starts and handle pointer cancellation/capture deliberately. Changing `touch-action` halfway through a gesture is not a reliable way to reassign that gesture. Do not blanket-disable all browser touch behaviour to hide conflicts. **[S5]**

### 7.4 Starting motion targets, not measured current values

As initial design targets, use prompt press feedback, short selection transitions, and a somewhat longer shared-surface morph. For example, trial approximately 100–160 ms for simple press settling, 180–260 ms for selection, and 280–420 ms for deck/focus movement. These are proposed tuning ranges, **not measurements from the video or universal standards**.

More important than the exact duration: no delayed second phase that feels unrelated, no unbounded wobble, no stretched labels, and a clean reversal from partial progress. Reduced-motion presentation must retain the same information and state change without relying on large spatial travel.

## 8. Performance and implementation investigation

The recording is sufficient to identify visible discontinuities. It is not sufficient to decide whether each comes from dropped frames, intended easing, delayed state updates, capture cadence or excessive rendering work.

### Required profiling pass

Record the exact interaction sequence in a representative on-device build. Inspect scripting, style/layout, paint and compositing during deck reversals, metric switches and music-focus entry/exit. Prefer transform/opacity for motion where they preserve the intended result, and verify paint-heavy effects rather than assuming they are inexpensive. Use `will-change` only where evidence supports it; blanket layer promotion is not a performance strategy. **[S3]**

Proposed project checks:

| Area | Inspect | Do not assume |
|---|---|---|
| Orb | Work while hidden, particle update cost, resize behaviour | The orb must be removed to make the app smooth |
| Glass | Number/area of live filtered surfaces; redundant nested effects | Increasing blur is free, or all blur is inherently unacceptable |
| Charts | Rebuild frequency, unnecessary redraws, blank handoff frames | A full redraw on every animation tick is required |
| Artwork | Decode timing, duplicate image layers, crop/radius handoff | Every perceived hitch is caused by the media bridge |
| DOM geometry | Repeated measurement/mutation during a transition | CSS transforms automatically solve all expensive work |
| Overlays | Unreleased nodes, timers, event listeners and pointer capture | An invisible node cannot intercept touches |
| Session clock | A single time authority across representations | The dot-matrix readability issue proves clock drift |
| Media | Actual callbacks versus requested state | A play icon with static progress proves failed playback |

For timing validation, the Android interval clock `elapsedRealtime()` is monotonic and includes deep sleep, unlike `uptimeMillis()`. Choose the appropriate authoritative clock and pause accounting for the application’s actual session model; do not drive session duration solely by counting UI callbacks. This is a regression guardrail, not a diagnosis of the current code. **[S8]**

Measure a performance baseline, then isolate material, chart, orb and transition work. Set a target appropriate to the display mode actually in use. Do not report the MP4’s average capture rate as either app’s FPS.

## 9. Implementation order and change boundaries

| Pass | Work | Evidence required before moving on |
|---|---|---|
| 1 — Reliability | F01, F05–F07; reproduce F27; preserve save/timing behaviour | No blank comparison; reliable navigation and hit testing; media result classified |
| 2 — Geometry/state | F03–F04, F08, F11–F13, F18 | Coherent metric state, clear dates, usable insets and scroll viewport |
| 3 — Shared transitions | F09–F10, including cancellation and reversal | Clean intermediate frames and reliable return from partial transitions |
| 4 — Shared material | F14–F17, F24 | Matched-underlay material board approved; timer readable over artwork |
| 5 — Screen completion | F19–F23, F25–F26, F28 | History/setup/details/Health feel like one app, with existing data preserved |
| 6 — Verification | Performance, accessibility and regression matrix | Device recording, traces/tests and an explicit unresolved-item list |

Implementation areas to locate in the **current** source: metric/deck renderer, launcher and input handlers, shared surface/selector styles, Body chart state, workout setup/history/details rendering, focus transition coordinator, Android host insets, session authority and media bridge. These are responsibilities, not claims that particular filenames or functions were inspected.

Keep fixes reviewable by domain. Do not perform a wholesale rewrite, import an unrelated native navigation implementation unchanged into the WebView, or combine a material refactor with silent changes to session storage and health-data provenance.

## 10. Regression and acceptance matrix

These are **tests to run**, not tests claimed to have passed during this audit.

| Test | Required outcome |
|---|---|
| Open/close Steps deck 20 times | No exposed rear-chart text, lost content or stuck geometry |
| Reverse deck at several partial positions | Continues from current position without reset or delayed queued action |
| Expand Intake, scroll comparison in/out, reverse | Previously loaded comparison never becomes an unexplained empty card |
| Compare displayed daily averages and delta | Visible precision/rounding policy reconciles the presentation |
| Change metric with deck collapsed and expanded | Label, value, units, chart and date agree |
| Cycle Body metrics rapidly | No new label with old units/data; no persistent empty chart |
| Change 7D/30D/3M/1Y where available | Correct range state and consistent provenance; unavailable data explicit |
| Drag Body and Intake charts | One owner per gesture; selected date/value visible and correct |
| Tap Explore from every deck state | Same understandable navigation action on the first tap |
| Tap destinations repeatedly during animation | One navigation; no background deck/chart activation |
| Tap outside Explore; use Back | Foreground state dismisses according to the documented contract |
| Tap overflow with Explore open/closed/recently closed | Reliable response; no stale overlay intercepts input |
| Enter and reverse music focus mid-transition | One coherent artwork surface and readable foreground handoff |
| Repeat focus entry/exit after chart navigation | No leftover layers, wrong scroll state or blocked controls |
| Change artwork during focus | No stale crop, unreadable clock or unexpected semantic recolouring |
| Inspect timer over bright/busy/dark artwork | Dot numerals unambiguous at normal phone size |
| Switch Elapsed/Remaining repeatedly | Same session authority; correct labels; no timing reset |
| Pause/resume from normal, focus and details | One session state updates every representation |
| Background/foreground and screen-off session | Duration/paused accounting follows the defined product contract |
| Finish a short paused session | Active/paused/total reconcile at defined precision; exactly one record |
| Return to workout history after save | New session and aggregates update; no duplication |
| Scroll long history and return from a detail | Readable groups, stable position, clear durations |
| Start without optional weight | Time-only session remains valid; unavailable energy is explained |
| Music access absent, connected, revoked | Accurate status and recovery route; workout remains usable |
| Media play with normal/slow/no network | Icon, progress, state and actual playback agree |
| Reveal system bars; switch apps and return | Header and bottom actions remain correctly placed and usable |
| Increase font/display scaling | Labels, units and primary actions do not clip or become inaccessible |
| TalkBack / accessibility navigation | Meaningful control names, selected states and reading order |
| Reduced-motion configuration | Same functionality without dependence on large morphing movement |
| Performance trace before/after material changes | No unexplained increase in sustained jank or excessive repainting |

The recording does not cover every route, permission state, long-running workout, device orientation, accessibility mode or disconnected integration. Those remain verification work, not grounds for inventing additional confirmed bugs.

## 11. Definition of done

The repair is complete when the blank comparison and ambiguous navigation behaviour are resolved, overflow/media questions are either fixed or cleared with evidence, and the app has a shared material/motion system across the existing product.

The final review must include:

1. A new on-device recording repeating this recording’s main sequence, including the last overflow attempts.
2. Before/after evidence for F01, F05/F06, F09, F10, F14/F15 and F24.
3. A test result for each relevant matrix row, with skipped or unavailable tests explicitly identified.
4. Confirmation that workout recording, pause accounting, save/history updates and actual media state were preserved.
5. A list of remaining limitations, rather than a blanket “all fixed”.

Do not call the work complete because the resting screenshots look better. The primary acceptance object is the **whole interaction**, including interruption, reversal, data updates and the next tap after an animation ends.

## 12. Evidence index

The Markdown is readable independently through its observations and timecodes. The companion ZIP includes the images below in `evidence/`. These are crops, resized frames and labelled comparison sheets from the supplied recording; no UI was repainted or redesigned.

| Evidence | File | Purpose |
|---|---|---|
| E01 | [E01_material_comparison.jpg](evidence/E01_material_comparison.jpg) | BitChord navigation versus Orbit Explore and focused selector |
| E02 | [E02_comparison_disappears.jpg](evidence/E02_comparison_disappears.jpg) | Populated → empty → restored period-comparison card |
| E03 | [E03_deck_transition.jpg](evidence/E03_deck_transition.jpg) | Deck geometry and content visibility at intermediate moments |
| E04 | [E04_music_transition.jpg](evidence/E04_music_transition.jpg) | Focus opening/closing sequence |
| E05 | [E05_explore_and_overflow.jpg](evidence/E05_explore_and_overflow.jpg) | Launcher/deck combinations and final control attempts |
| E06 | [E06_workout_layouts.jpg](evidence/E06_workout_layouts.jpg) | History, setup and all-details composition |
| E07 | [E07_timer_legibility.jpg](evidence/E07_timer_legibility.jpg) | Clock progression and difficult foreground contrast |
| E08 | [E08_rounding_and_periods.jpg](evidence/E08_rounding_and_periods.jpg) | Visible comparison-rounding inconsistency |
| E09 | [E09_body_and_health.jpg](evidence/E09_body_and_health.jpg) | Body control language and Health hub |
| E10 | [E10_session_saved.jpg](evidence/E10_session_saved.jpg) | Working save and updated history to preserve |
| E11 | [E11_native_timer_01m35s5.png](evidence/E11_native_timer_01m35s5.png) | Original-resolution check of the nonstandard dotted numeral shapes |

## 13. Technical references

Video observations are supported by the supplied recording and the evidence/timecodes above. These external primary references support implementation guidance only; none proves the present app uses a particular code path. Accessed 12 September 2026.

**[S1] CSS Working Group — Filter Effects Module Level 2.** Working editor’s draft, including backdrop filtering and Backdrop Root discussion; not a final consensus specification.<br>
`https://drafts.csswg.org/filter-effects-2/`

**[S2] Chrome for Developers — Remote debugging WebViews.** Inspecting the actual Android WebView rather than inferring computed styles from a video.<br>
`https://developer.chrome.com/docs/devtools/remote-debugging/webviews`

**[S3] web.dev — How to create high-performance CSS animations.** Rendering-stage inspection, transform/opacity and cautious layer promotion.<br>
`https://web.dev/articles/animations-guide`

**[S4] Android Developers — Display content edge-to-edge in views.** Host insets, system bars and control placement.<br>
`https://developer.android.com/develop/ui/views/layout/edge-to-edge`

**[S5] W3C — Pointer Events Level 3.** Pointer ownership, capture/cancellation and `touch-action` behaviour.<br>
`https://www.w3.org/TR/pointerevents3/`

**[S6] Android Developers — Make apps more accessible.** Touch-target and contrast guidance; evaluate rendered controls on the actual device.<br>
`https://developer.android.com/guide/topics/ui/accessibility/apps`

**[S7] Android Developers — PlaybackState.** Explicit playback states, including buffering and error.<br>
`https://developer.android.com/reference/android/media/session/PlaybackState`

**[S8] Android Developers — SystemClock.** Monotonic elapsed interval timing and deep-sleep behaviour.<br>
`https://developer.android.com/reference/android/os/SystemClock`

---

**Bottom line:** Preserve Orbit’s identity. Fix the disappearing content and navigation contract first, make each transition behave as one coherent object, then unify the frosted material and foreground readability. That addresses the actual gap shown by this recording without turning the project into a different app.

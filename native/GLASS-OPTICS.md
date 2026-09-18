# Retained backdrop lensing

G1 is implemented and locally verified. This extends the approved frosted navigation material; it does not replace Orbit's visual system or claim Apple's proprietary optical model. Physical S25 frame pacing, readability and user acceptance remain open.

## Rendering contract

`GlassBackdrop.kt` owns a retained scene and its actual drawing coordinates. `OrbitApp` still captures content separately from the floating Explore surface. Header controls sample the scene below them. The transient Explore choice samples the page, never the choice itself. The retained dependency graph is one-way; no extra page tree or CPU image capture is used in production.

`GlassCoordinates` takes coordinates at the drawing modifier's placement, after any preceding inset or transform. `LayoutCoordinates.transformFrom` maps the source into the destination's local draw space. Density, translation, rotation, scale and scrolling therefore affect the sampled region together. Placement invalidates drawing only if the matrix or size actually changes. This guard matters because reading coordinates can itself request a placement callback.

`GlassLens.kt` compiles one AGSL program per retained surface on API 33+. The program samples the real frosted scene and applies an inward displacement near the rounded rectangle's edge:

- A signed distance from the rounded boundary defines a band no wider than 12dp or one quarter of the smaller dimension.
- A squared sine profile makes displacement zero at both ends of that band. The center and outside remain undisplaced.
- Resting amplitude is 0.65dp. Existing contact engagement and finger proximity can increase it to at most 3.25dp.
- The existing 8dp frost and saturation remain. Overscan is 28dp, covering its three-sigma radius plus the maximum displacement before the rounded clip.
- The shader returns the sampled premultiplied color; no chromatic fringe or independent glyph transform is added.

The outer Explore material uses its existing drag/settling state. Header contact and the moving selection use their existing engagement/light state. No new gesture recognizer, target resizing or navigation transition was introduced. The choice still disappears on release/cancellation; it does not leave a second selected track.

`OrbitGlass.kt` draws the optical result, existing tint and stable local label backing first. Foreground labels/icons then draw normally above it. The three stages are actual backdrop, bounded material and sharp foreground. The decorative `HomeScrollFrost` band reuses the corrected coordinate mapping but does not gain lens distortion.

## Fallback and cost boundary

The approved frost remains on API 31–32 or if the AGSL compiler is unavailable. Older platforms and Reduce transparency use the opaque material. Reduce motion removes contact-driven optical modulation independently of opacity/contrast; the geometry and direct manipulation remain available.

The shader and frost compile once per material instance. A small RenderEffect wrapper is replaced only when bounds, corner, engagement or focus change because Android snapshots shader uniforms. Source content remains a retained graphics layer. There is no per-frame texture readback, duplicate scene construction or new dependency.

G3 now uses the runtime policy below. Local software-emulator timings do not qualify sustained optical cost on a physical S25.

## Runtime visual quality

`GlassQualityMonitor.kt` observes the visible phone window. `GlassQualityPolicy.kt` owns three appearances of the same material: optical, approved frost, and readability. No new gesture owner or page tree is introduced. `MainActivity` provides the chosen quality; the Watch retains its native renderer.

- Start at the best tier the platform supports. Opening cheap and climbing after five seconds of evidence meant the owner watched the material change under their hands, which is the opposite of the point.
- Android's actual total frame duration and deadline decide whether a frame was late, independently of refresh rate. Three consecutive misses cost the refraction. A normal change waits for release if a finger owns the window. Recovery requires five seconds of timely rendered frames and at least 30 samples; any miss, pause, or gap over half a second resets that evidence.
- **Measured conditions never reach readability.** That tier belongs to the owner's own Reduce transparency preference, to a platform with no blur, and to their own battery saver. Heat and frame pressure remove the lens and leave the glass. This is not a preference about performance: a surface that silently becomes a painted rectangle is a different app, and until 18 September 2026 that is what shipped — three late frames dropped a signed build on an S25 Ultra to flat `#28262E` and `#15141A` fills, which is what the owner photographed and called two different shades.
- Thermal headroom is a forecast, not the signal. A device that reports its thermal status but no headroom — Samsung among them — still earns full optics; without any thermal signal at all the refraction is held back. Requiring the forecast is why the lens never once ran on the owner's own phone.
- Query public headroom no more than once per ten seconds, preserving that rate limit across recreation. Use vendor-provided headroom thresholds where available and Android's documented severe normalization. Unsupported, stale or invalid readings remain unknown. Listeners and polling stop on pause and reattach on resume.
- Optical strength settles over 180ms without replacing the surface; Reduce motion makes that change immediate. Readability skips the source sampler and decorative scroll blur and suspends optional globe rotation. Labels, values, navigation, selection, recording controls and the saved rotation preference survive.
- Each drawn surface reports its actual tier and a closed reason enum through the existing private diagnostics. A transparency preference can therefore report readability even when the quality policy permits optics. Frames drawn under that cheaper override cannot qualify full optical recovery.

Three missed frames and the asymmetric recovery window are product stability rules, not a claimed universal safe temperature, frame duration or physical calibration. The controlled native render measurements establish which work each tier performs. Physical S25 pacing, thermal behavior and battery qualification remain required before calling the full optical tier sustainable on that hardware. No sensor cadence, sample, persistence operation, acknowledgement, transport setting or workout state depends on this policy.

## Invalidation ownership

| Change | Work that changes | Work that remains retained |
|---|---|---|
| A child timer/value changes | Its foreground text display list | Glass drawing, source capture and unrelated chart composition |
| Recorded source content changes or scrolls | The source display list and Android's dependent optical rendering | Static foreground text and the existing material object |
| Surface position/size or optical contact changes | Coordinate mapping and necessary material drawing/filter parameters | Unchanged source content and foreground typography |
| Quality/readability changes | The affected material and permitted decoration | Content, control geometry, gesture ownership and saved navigation |
| Health capture/commit/ACK | Existing acquisition, journal and transport paths | Independent of the visual quality policy |

The first controlled benchmark found two source rerecordings during the two measured timer ticks. `recordBackdrop` now owns a native draw boundary, so unrelated siblings cannot rerecord it. A tighter fixture then put the timer **inside** the glass, matching the production live bar. It exposed two unnecessary material redraws even after source isolation. `orbitFrost` now ends with a foreground draw boundary, fixing that shared path for the live bar, headers and transient selection. These are native retained display-list boundaries, not a second page or a forced bitmap cache.

`GlassRenderingTest` drives real Choreographer frames and collects actual Window.FrameMetrics on a separate handler. Nine three-second phases cover all tiers with clock-only, source-scroll and contact-drag changes; the first half-second of each phase is excluded. It records bounded total/deadline/GPU/layout metrics and independent composition/draw counters. No screenshots or readbacks occur in those measurement intervals. Clock-only phases legitimately render only the changed ticks, rather than forcing a continuous animation to inflate the sample count.

The fixture asserts no source recomposition, no unchanged-source rerecording on clock/drag phases, and no material redraw from child timer ticks in any tier. A source scroll can still cause native dependent GPU work even when the Kotlin material-draw counter remains zero. These counters identify unnecessary work; they do not prove zero GPU cost or whole-app physical performance.

## Local evidence

G3's [checkpoint](../verification/samsung-import/native-glass-quality/checkpoint.json) identifies source `e9d30079204dd5380459bc27759debd20cf7fe2197c00bc06e5b34fb60048c39`. Full phone verification `20260915T131157.749695Z-phone` passed **79/79**; full Watch verification `20260915T131505.950944Z-wear` passed **18/18**. Both release assemblies and phone lint pass. That checkpoint exposed 14 existing Watch notification/status/collection lint errors. The subsequent Watch repair resolves them and passes all 19 Watch tests plus both lint checks; its separate source identity and evidence are recorded in [MIGRATION.md](MIGRATION.md).

In the final nine-phase run, clock phases each recorded two text updates with **zero source compositions, source rerecordings or material redraws**. Optical/frost contact still drew the moving material, and source scrolling still redrew the source. Forced tier changes during a held Explore drag retained all label/control bounds and selected Health exactly once on release. All three captures were inspected. Native thermal signal, background stop, recreation and actual-tier file persistence checks pass. Physical S25 sustainability remains unqualified.

The first full run stalled in the contrast fixture's Espresso next-frame idle barrier after its content disappeared. A captured native stack identifies that test boundary. The fixture now observes the same public Android signals and composition callbacks directly, with bounded waits and the existing recreation hook; it does not alter production contrast behavior. The repaired full run passes, and both contrast settings and the thermal override are restored. Failed-run, draw-leak and repaired-run evidence remain in the G3 folder.

The exact source, APK identities and checks are in [checkpoint.json](../verification/samsung-import/native-lensing/checkpoint.json). The final full phone run `20260915T122704.265724Z-phone` passed **74/74**, including all developer checks. Release assembly and lint also passed.

`GlassLensTest` runs against the real native renderer on API 36:

- The controlled grid/text image changes at 241 edge pixels on engagement and 194 pixels when contact moves. Pixels outside the lens and its fixed interior remain unchanged within the one-channel tolerance. Release returns to the resting image with **zero** maximum channel difference.
- Twelve rendered source/contact positions refresh 36,242 interior background pixels. Foreground bounds and opaque glyph cores remain fixed.
- Four density/inset/rotation/scale combinations sample the correct real red/blue region. Four additional moves change only an already-visible graphics layer's translation, without recreating the material or changing its shader pose; all four still sample the correct region.
- Existing Explore, header and Home checks exercise interruption, reversal, cancellation, selection, updating backgrounds and four-card expansion. All eight motion/transparency/contrast combinations still pass. Moving-grid secondary-label contrast remains 5.10–8.85:1.

[Rendered samples and JSON](../verification/samsung-import/native-lensing/) include lossless idle, held, moved and scrolling captures. [motion.mp4](../verification/samsung-import/native-lensing/motion.mp4) records the actual emulator output, including the transformed-source checks and moving grid/text. `video-frames.json` identifies its 180 encoded frames. Activity transitions between test fixtures and long still intervals are included; the recording's average encoded frame rate is **not** a device performance measurement.

The unchanged-size idle navigation fixture was also compared with the G2 baseline: average absolute difference is 0.0136 per 8-bit channel, maximum 4. Its foreground/geometry remain visually consistent; this supports preserving the resting style, not final user approval.

No physical phone/Watch interaction or installation, production-data edit, distribution replacement, commit or push occurred. The development certificate is separate from the existing production app's certificate. The unsigned release is not a qualified in-place upgrade.

## Primary implementation references

- [Compose LayoutCoordinates](https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/LayoutCoordinates): source-to-destination transforms.
- [Compose graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers): retained layers and draw/foreground separation.
- [Using AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl): runtime shaders and RenderEffect inputs. The installed Android source also confirms snapshotting of shader uniforms.
- [Apple reference rules](../APPLE_RULES.md): the established transcript research and approved/rejected product decisions remain authoritative.

For G3, [Android's thermal guidance](https://developer.android.com/games/optimize/adpf/thermal) explicitly warns that some devices always report `NONE`, and that persistent unavailable headroom must not be treated as known capacity. [JankStats](https://developer.android.com/topic/performance/jankstats) is already installed and used for local frame diagnostics. No universal frame-time or temperature threshold has been invented from this emulator run.

## The vendored material — 18 September 2026

The bounded edge-bend Orbit rendered until now is replaced by the Kyant0/backdrop pipeline, vendored
as source under `phone/src/main/java/com/mani/orbit/backdrop` from the owner's BitChord copy. This
was the owner's instruction: BitChord's navigation bar is the material they want, and they asked for
the refraction to be live and real rather than an edge treatment.

`Modifier.orbitFrost` keeps its signature and now composes that library: saturation, blur, and a real
rounded-rectangle refraction with the depth term, then a rim highlight that carries the light and a
shadow the surface casts. Its `engagement` parameter became a **lift**, 0..1, read at draw time. A
lifted surface is a thicker piece of glass:

| At rest | Fully lifted |
|---|---|
| 12.6dp refraction height, 10.1dp amount | 40.3dp, 36.4dp |
| no dispersion | the rim splits the light into colour past a third of the way up |
| 0.5dp rim | 1.5dp |
| 24dp shadow at 10% | 38dp at 30% |
| tint at its full opacity | 60% of it, so more of the page shows through |

The blur is deliberately not on that list. Blur models frosting rather than thickness, and growing
it while the tint thins washed the page out faster than the thinner tint let it through — measurably,
in `GlassLensTest`: picking a surface up showed *less* of what was behind it than leaving it at rest,
which is backwards. Lift says "thicker" through the refraction, the rim, the dispersion, the shadow
and the tint.

`focus` turns the rim's light: the deflection is ±30° across the surface and scales with the lift, so
a released material returns to exactly the rim it had before the contact, wherever the finger left it.

Every effect runs at a third of the surface resolution, which is nine times fewer pixels through the
colour matrix, the blur and the refraction. The blur hides the upscale; all pixel-sized parameters are
pre-multiplied by the same factor.

### One material, one control, one motion

The pass on 18 September 2026 removed every local decision about the material, because the owner's
verdict on the previous state was that the app looked like two apps. `orbitFrost` no longer takes a
tint, an opacity or a legibility shield: there is one shade (`GlassTint` at 42%), one hairline, one
fallback fill, and a route that wants a panel calls `Modifier.orbitPanel`, which finds the page
recording itself. The permanent opaque backing the floating bar used to paint under its labels is
gone; a backing appears only under an explicit Increase contrast preference, because a plate that
hides the backdrop is not glass.

Legibility is a **multiplicative dim of the sampled backdrop**, not a plate over it. The material
keeps 30% of the backdrop's own brightness, so a bright page is tamed while a dark one is untouched
and the structure the refraction bends survives either way. Measured against the muted caption ink,
that holds at least 5:1 over anything a surface can be over, including pure white, while a chart
passing under the Explore bar still reads through it — the opaque backing it used to paint under its
labels bought the same number by hiding the backdrop, and only on that one surface. An explicit
Increase contrast preference adds a backing on top of the dim and reaches 7:1.

Panels are glass and controls are not, and that is a rule rather than an omission. A button sits on a
panel; glass samples the page *behind* the panel, so a glass button would show what the panel is
hiding and read as a hole punched through it. Every control — the Explore rows, the date stepper and
its actions, the workout buttons, the settings actions, the segmented selectors and the Explore
selection pill — wears `Modifier.orbitControl`: one fill, one rim, and one press.

**Lift is contact.** A surface is thicker glass while a finger is pressing or carrying it, and at
no other time. Reading it from travel instead — the island from its own velocity, the deck cards
from the fold's progress — made every open and close pump the material thicker and then thinner
again with no finger involved, which is what the owner saw as the effect breaking.

**A press loads the spring, and only on the handle.** The Explore shell gathers under a press on its
bar and waits there for as long as the contact lasts; the release lets it travel from that loaded
pose. One contact is one movement. A contact that lands on a row *inside* the shell never gathers
it: the gather is a scale, and scaling the shell under a finger slides the rows out from under it.

**A travelling cut feathers.** The Home deck's fold is a window, and while that window is moving it
fades its content over 26dp rather than ending in a hard edge — a sweeping hard edge sliced every
line of text it passed through the middle. Only a cut that is standing still carries the material's
hairline.

Motion is three named timings in `OrbitMotion.kt` and nothing else: `orbitSettle` for a surface
returning to rest, `orbitEngage` for a surface answering contact, and the reference's own 100ms press
to .96. Compose springs are normalised, so the same stiffness takes the same time whether a surface
travels the width of the screen or four dp — a small pill and a whole panel arrive together.

Panels lift and controls press. Surfaces on the material: the Explore island, the header buttons, the
date panel, the Health cards, the Home deck, the Watch-reading cards, the sleep summary and stage
breakdown, and the workout week, session and section panels. A card cannot sample the recording it is
drawn into, so the page records a separate surface layer beneath the route content, and Home records
the globe on its own layer for the deck to refract. The island samples the whole page, so the deck's
cards and charts genuinely bend as they pass under it.

The Home deck's fold is a window onto a card rather than a straight cut: it carries the card's own
corners and the same hairline on both cut edges. A rectangular clip left a folded card with square
corners the opened one never has, which is the clipping the owner reported.

Tiers are unchanged. Refraction needs a runtime shader (API 33), the blur needs a render effect
(API 31), and below that — or under the reduced-transparency preference — the surface is a solid fill
with the shared hairline. Every tier ends in a draw boundary, so a ticking timer inside a surface
never redraws the material around it; the measured invalidation counts below confirm that for all
three.

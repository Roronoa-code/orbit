# Liquid Glass interaction in Orbit

Research completed before implementation, 14 September 2026. Baseline: `6c40cee`.
The user's pasted Meet Liquid Glass transcript is reference material. The requested scope is all Orbit navigation and controls, with the existing frosted appearance retained.

## Primary evidence

- [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/), Dynamics 1:29–5:45, Adaptivity 6:00–10:30, Principles 10:31–18:53: light and motion form one system; touch immediately energizes and flexes it; quiet controls can temporarily lift into a lens. Illumination originates at the finger and reaches nearby glass. Shape changes retain a single control plane. Larger surfaces have more scattering, shadow and refraction. Reduced motion removes elasticity; reduced transparency increases frosting. These observations also appear in the transcript supplied by the user.
- [Get to know the new design system](https://developer.apple.com/videos/play/wwdc2025/356/), 6:16–13:28: preserve a surface's relationship to its source, group related controls, maintain concentric shapes and a legible content/control boundary. A single scroll-edge effect belongs at that boundary. Material should reinforce function rather than decorate everything.
- [Build a UIKit app with the new design](https://developer.apple.com/videos/play/wwdc2025/284/), 1:55, 7:19, 17:24, 19:15: tab accessories move into the bar; related toolbar items share a background. Switch/segmented-control thumbs acquire glass during interaction. Sliders stretch and preserve momentum. Interactive glass scales and bounces. Nearby shapes in one container merge as they approach; spacing controls proximity. Materializing the effect differs from fading the entire view.
- [Build a SwiftUI app with the new design](https://developer.apple.com/videos/play/wwdc2025/323/), 14:03 and 17:57–21:18: custom interactive glass scales, bounces and shimmers. Grouped glass shares a sampling region larger than its bounds; glass must not sample another glass layer. Stable effect identities connect expansion and reabsorption.
- [What's new in SwiftUI](https://developer.apple.com/videos/play/wwdc2025/256/), 1:22–4:20 and 8:17–9:06: toolbar controls morph through navigation, controls share the interaction language, and missing a frame deadline makes interaction feel slow. These are principles to reproduce, not APIs usable by this Android WebView.

Apple does **not** publish the exact touch delay, geometry curves, spring constants, velocity estimator, selection commit threshold or cancellation algorithm in these talks. The state machine and numeric tuning below are Orbit engineering decisions, not a claim to reproduce Apple's private implementation. No third-party recreation is used as behavioural evidence.

## Continuous state machine

One material retains its geometry, engagement and velocity throughout the gesture. States describe ownership; they do not start independent animations.

| State | Orbit response |
|---|---|
| Idle | Existing quiet fill, tint and shape; no running response frame loop. |
| Touch down | No hold timer. The first frame begins lift and light at the contact point, from the current displayed state. Hit targets and labels do not resize. |
| Hold / engaged | Engagement approaches one. The same material moves beneath the pressed item, bulges and exposes a frosted highlight and deeper edge/shadow. It stays still once the finger and material settle. |
| Drag | Finger position owns selector translation after scroll arbitration. Grabbing a distant unselected item retains the visible pose and closes only its initial gap with a critically damped spring. Filtered velocity controls bounded stretch and squash. |
| Approach / cross item | One indicator spans the measured slots. Overlap continuously illuminates the adjacent labels; a shared light field travels across the control enclosure. No new blob is created for another item. |
| Selection change | A candidate receives a thresholded haptic; existing semantic selection commits once on release. Switches retain native click/change semantics; sliders retain native continuous values. |
| Release | Retain the rendered pose, material strength and velocity. Retarget the same state toward the selected rest geometry; short bounded projection avoids a fling across distant items. |
| Settle | Geometry, light and lift relax together. A new touch redirects the current state. Cancellation returns to the committed value; hiding/detaching stops work and clears contact ownership. |

## Platform mapping and limits

`BlobTrack` already owns slot geometry, spring integration, overlap, touch capture, vertical-scroll arbitration and bounded release projection. Its previous selected-slot press shrank, distant holds were capped to a small lean away from the finger, held styling ended abruptly, it read layout during moves, and it wrote actual width/height every frame. Those are the shared boundaries changed here.

The response uses the existing analytic spring and cached pointer geometry. Continuous engagement drives the material's lift, deformation, travelling light and edge/shadow together. Selectors use fixed layout dimensions with transforms for their changing shape. Related controls share their existing backdrop; temporary highlights add no nested blur. Lens fields remain cached by geometry. Refraction is the existing bounded WebView approximation, not Apple's private adaptive lighting renderer: no per-frame bitmap generation, screen readback or blur-radius animation.

The same response applies to nested pickers, date controls, switches, media seeking/actions, buttons, disclosures, the live launcher, and the card deck's interaction surface. Existing live/deck/music gesture owners continue to control expansion; their persistent shells are retained. The live bar energizes its existing outer shell rather than creating a second capsule inside it. The main navigation's previous pressed background and pseudo-element are removed, so dragging leaves no stationary mark. Charts retain their precise native range input and inspection UI, and editable fields retain normal editing. The globe and Body dial keep their gestures without a glass overlay across their large touch areas. Lavender selection, typography, spacing, five-minute sleep blocks and health records remain unchanged.

## Acceptance ledger

Preview follow-up: the user approved the glass colour. Disclosure rows now keep a soft rounded press fill on their existing reading plane, without a square rim or spotlight. Explore consumes the captured mouse drag's terminal click before outside-click dismissal, so releasing above expanded cards leaves it open. These paths are covered in the two existing interaction checks; see the current release entry in `HANDOFF.md`.

| Requirement | Boundary | Evidence / status |
|---|---|---|
| Immediate held lift and continuous drag/release/regrab | Shared material + BlobTrack | `verification/liquid-interaction.cjs`: actual Chromium touch, 390/320; selected and distant unselected grabs, immediate/held drags, reversal and interruption |
| All control families, stable labels/targets | Shared control adapter + existing gesture owners | Same check covers main/deep navigation, pickers, date selection, switches, chart inspection, workout buttons and media; fixed labels/control geometry |
| No clipping, preserved frosting/selection | Shared material CSS | Held and dragged captures inspected at 390/320; `liquid-glass.cjs` checks actual diffusion/refraction pixels and unchanged material roles |
| No layout reads/map generation on selector move | Cached geometry + transforms | Instrumented 390/320 drags: 0 geometry reads, 0 layouts, 0 lens-map mutations. Short local samples around 16.6–16.8 ms/frame; not a phone performance claim |
| Cancellation, disabled/keyboard, accessibility | Shared response lifecycle | Touch cancel, blur, keyboard, reduced motion, forced colours and switch native values pass; no idle response frame work |
| Main deck/live/player and deeper navigation regression | Existing local checks | `check`, `body-history`, `interaction-followup`, `home-motion`, `workout-endpoints`, `sleep-apple`, `liquid-glass` passed. 18 player handoffs have no geometry delta; card heights remain fixed through open/close |
| Packaged source, one Owner app, installation only | Android build + explicit user-0 installation | Signed build `20260914-024037`; bundled JS/CSS matches source and excludes local fixtures. Installed with `--user 0 -r`; installed hash matches. Package dump confirms Owner 0 true, users 95/150 false. No phone testing |

Run the touch check with the existing local preview at port 8784 and Playwright available through the project's verification environment: `node verification/liquid-interaction.cjs`. Ignored captures and measurements live in `verification/samsung-import/liquid-interaction/`. `verification/blob-math.cjs` passes 239 assertions against the shipped spring/geometry functions. No new runtime dependency was added.

APK SHA256: `e8465d68b014de4af8890a22854e65f0b05a3fd962d95af3bcf8e32d7efda24a`. The pre-install APK was saved separately in `verification/samsung-import/phone-before-disclosure-explore-20260914-024037.apk` (SHA256 `b4be1f6c2f6bdc091527e2c6cbb2b44c3686388e009d89494f8673f853a62998`). Physical-phone appearance and motion remain for the user to assess.

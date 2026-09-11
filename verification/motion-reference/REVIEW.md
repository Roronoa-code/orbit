# Reference motion review

Source: `C:/Users/abdul/Downloads/original-aed5b541741027a19994476e5c864566.mp4`.
1600 × 1200, 60 fps, 602 decoded frames, 10.033333 seconds. Frame 1 is at 0 seconds; frame 602 is at 10.016667 seconds. Every decoded frame was extracted without sampling, then visually examined in order across sheets 01–31. Each sheet contains 20 labelled, uncropped frames, except the last sheet with 2. `frames.json` records the complete sequence; `frames/` retains the individual images. Phase descriptions below consolidate consecutive frames after that examination.

| Frames | Time | Observed motion |
|---|---|---|
| 1 | 0.000 | Cover collage showing several capsule states. This is distinct from the running demonstration. |
| 2–13 | 0.017–0.200 | Compact black capsule at the top of the glass phone. Camera movement changes the surrounding phone scale slightly. |
| 14–30 | 0.217–0.483 | Capsule widens symmetrically. A blue microphone bubble emerges on the right and separates; the avatar appears within the capsule on the left. |
| 31–90 | 0.500–1.483 | Wider capsule and microphone hold. The presentation camera continues a gentle move. |
| 91–94 | 1.500–1.550 | External microphone bubble contracts first. |
| 95–114 | 1.567–1.883 | Capsule widens and grows taller into a notification. Avatar persists and grows. Title emerges as space opens, then the subtitle; the blue chevron grows on the right. |
| 115–167 | 1.900–2.767 | Notification holds with a slight settling of the shell. |
| 168–180 | 2.783–2.983 | Chevron turns downward; avatar fades/contracts while the title shifts left. Shell grows downward from its upper region; a play circle starts appearing below the title. |
| 181–185 | 3.000–3.067 | Taller shell settles around the play control. |
| 186–215 | 3.083–3.567 | Playback track extends rightward from the circle. Waveform follows the track reveal; sender/time text appears afterward. |
| 216–247 | 3.583–4.100 | Player card holds with its complete grey waveform. |
| 248–260 | 4.117–4.317 | Play changes to pause; blue playback progress begins on the left. |
| 261–340 | 4.333–5.650 | Blue waveform progress advances while the card retains its shape. Some framing change belongs to the camera. |
| 341–360 | 5.667–5.983 | Shell grows farther downward before introducing the reply field. A faint circular seed appears at lower left and increases in size. |
| 361–367 | 6.000–6.100 | Reply seed grows into a larger circle; shell is already making room for the new row. |
| 368–400 | 6.117–6.650 | Circle stretches horizontally into a rounded reply field. Reply text is progressively exposed; the field approaches its final width. |
| 401–530 | 6.667–8.817 | Expanded reply/player card holds. Playback progress advances; camera framing gently changes. |
| 531–545 | 8.833–9.067 | Contents dim while the shell contracts in both dimensions. A small capsule becomes visible above the shrinking body. |
| 546–555 | 9.083–9.233 | The nearly empty body narrows and rounds below the upper capsule, with a small visible separation. |
| 556–566 | 9.250–9.417 | Remaining body contracts toward the upper capsule and merges into it. |
| 567–602 | 9.433–10.017 | Compact capsule settles and holds through the final frame. |

## Applied to Orbit

The reference supplies shell-first growth, delayed content revelation, persistent object identity and content-first disappearance on collapse. Orbit retains four individually useful cards, its existing curved frost material and its health content. Card height, position and inset vary continuously; graph revelation progresses left to right. The reference's purple background, microphone, player controls and camera moves are not part of Orbit's interface.

Kaito was inspected read-only at `C:/KAITO/apps/desktop/src/Presence.tsx`, `contactDynamics.ts`, `particleTravel.ts`, `App.tsx` and `styles.css`. Its presence survives layout changes and retargets from current position and velocity. Orbit uses the same exact critically damped spring equation for its layout and orb pose, retaining the existing SVG and all 646 particle nodes through opening, closing and reversals. This transfers the continuity principle into Orbit's existing renderer; Kaito's desktop GUI was not launched for this work.

The period selector is one moving violet pill. The chart marker and reading tooltip also animate between selections. The card surfaces are darker charcoal frost. Reduced motion goes directly to each final state, and motion loops stop after settling or when the document becomes hidden.

## Verification

- Existing data/interaction check passes, extended with exact spring timing, continuous reversal position and velocity, orb settling, staged reveal, scroll return and reduced-motion endpoint checks.
- `../motion-review.html` sampled 466 frames at 390 px and 474 at 320 px across opening, closing and two interrupted reversals. Both retained the same SVG and particle nodes and returned to the initial closed bounds. Minimum measured orb-to-selector gap was 10.29 px and 10.28 px respectively; neither had horizontal overflow. Sampling also builds static intermediate DOM/SVG frames for visual inspection. Its capture overhead means the frame timings are not a performance benchmark.
- Visual intermediate-frame review identified and corrected temporary orb crowding near the selector and a clipped chart tooltip.
- Live browser interaction covered period taps/keyboard changes, upward card drag, downward live-bar drag, keyboard chart selection and scrolling through all four expanded panels.
- This is an HTML revision. No new Android package was built or installed, and physical-device motion qualification remains separate.

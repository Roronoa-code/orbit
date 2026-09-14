# Apple principles for Orbit

Reference: the two user-supplied Apple transcripts, read in full on 14 September 2026: [Design foundations from idea to interface](https://developer.apple.com/videos/play/wwdc2025/359/) and [Get to know the new design system](https://developer.apple.com/videos/play/wwdc2025/356/). These rules translate their principles to Orbit's Android WebView; they are not an iOS layout specification. [LIQUID-INTERACTION.md](LIQUID-INTERACTION.md) covers the earlier official motion research and Orbit's material implementation.

## Reusable rules

1. Define the job before the layout. A screen must make its location, main action and next destinations clear. Give it a descriptive title, not branding or an unexplained menu.
2. Establish order through placement, grouping, spacing and typography first. One dominant reading or decision; supporting dates and units are quieter. Borders, colour and glass must not carry the whole hierarchy.
3. Separate persistent destinations from contextual actions. Explore is available while browsing. Workout setup and recording have a focused action area; their Back path restores browsing. Ongoing workouts remain accessible elsewhere through the same live accessory.
4. Show a useful summary first. Reveal original readings, explanations, connection instructions and advanced controls when requested. Empty, loading and failed states must remain visible and actionable; disclosure must not hide errors.
5. Keep relationships intact. A reading opens its detail; Back returns to its source and scroll position. Time selectors sit with their chart. Profile values belong in Settings and feed workouts automatically.
6. Group for the task: readings by category, workouts by week/day, settings by purpose. Use rows for compact comparisons; use a grid only when choices genuinely benefit from equal visual weight.
7. Reserve glass for the floating control plane. Reading surfaces remain quiet. Apply material to the control or shared shell, never duplicate it on labels or nested decorative layers. Preserve the approved frosted charcoal tint.
8. Use one soft edge at the meeting of scrolling content and pinned controls. No decorative fog elsewhere, stacked edge effects or hard cutoffs. Reserve enough scroll space to reveal the last item above navigation.
9. Build related shapes from consistent radii and insets. Round date selections remain circular; nested shapes fit their parent. Keep touch targets stable while the material moves.
10. Use a small type hierarchy: title, section, value, body, caption. Weight and line height matter with size. Long labels, narrow screens and larger text must remain readable. Lavender signals action/selection; sleep colours retain their data meaning and text labels.
11. Motion carries a relationship continuously. Reuse the existing shared spring/material engine for press, hold, drag, interruption and settling. Do not add independent cosmetic transitions or rebuild a moving component. Repeated input must still work.
12. Preserve semantic controls, focus, cancellation and accessible names. Support the in-app Reduce motion control and system material transparency/contrast preferences. Keep errors and missing measurements honest; never invent health data to complete a layout.

## Screen audit and application

| Screen / job | Notice first | Quieter or disclosed | Navigation / relationship | Applied decision |
| --- | --- | --- | --- | --- |
| Home: daily metric | Current reading and metric title | Date and supporting four panels | Orb gestures change metric/period; date control opens its source panel | Title/date form one group; Settings shares that row; redundant disabled Back disappears. Approved folding stack and glass retained. |
| Heart / intake / step summaries | Actual value, units and period | Trends, comparisons, record times | Direct category routes from Health open the existing summary and panels | Shared Home anatomy; no extra duplicate detail pages. |
| Health: find readings | Named categories and latest/daily values | Connection details; oxygen history | Reading rows open the relevant destination | Replaced duplicate Body/Workouts tiles with one scannable reading directory. Missing values stay explicit. |
| Body: composition and change | Dot reading and measurement selector | Coverage and calculation explanation | Range sits above history; notes disclose provenance | Removed the extra glass reading card; shared spacing and full-width range; notes below the chart. |
| Sleep: review the night | Time asleep and one date control | Original interval appears on inspection | Category selection and chart stay connected | Existing five-minute bands, colour bars and selected-row feedback retained; shared header edge/navigation and readable provenance added. |
| Workouts: start or review | Workout choices / selected week's totals | Last session and individual readings | Train/History stays local; week/day controls stay together | Compact equal choices; quieter surfaces; consistent captions; existing round dates and summary-to-detail flow retained. |
| Setup / countdown / live workout | Goal or timer, then Start/Pause/Finish | GPS/status, music when available | Focused task uses its own actions; Back restores Explore | Single task plane; no extra launcher over artwork/actions; saved profile weight remains automatic. |
| Saved / imported workout | Main session result | Time, movement, energy method and source | Back retains week/day and scroll; Explore available | Existing journal sections retained and spacing aligned; imported records stay read-only. |
| Settings: personal defaults | Profile and daily goal | Connection/history instructions and About | Profile form remains intact during background updates; source links return to originating page | Profile first; flat purpose groups; connected source controls collapse; unconnected setup expands; status remains in summary. |

Orbit keeps its dot globe, dot timer, charcoal/silver foundation, lavender action colour, real artwork and established held/dragged controls. Apple supplies principles, not replacement branding or copied example layouts.

## Verification contract

`verification/apple-structure.cjs` checks reading routes, nested return, the same persistent launcher, contextual workout mode, profile/goal saving, missing readings, scroll clearance, keyboard and contrast at 390/320 px. Existing Body, Sleep, workout, glass and Home motion checks cover their retained interactions. Build/package checks must match current source. Screenshots establish layout only; gesture checks establish behaviour. Automated checks use local fixtures; physical-phone testing requires current authorization. Installation and user acceptance are separate.

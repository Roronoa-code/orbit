# Command feedback and quiet states

W6 audit, 15 September 2026. This preserves the approved artwork, gestures, material and navigation. It changes the meaning and delivery of haptics, not the visual design.

## Findings and repair

The Watch previously inferred success from the last completed operation in retained UI state. That included phone-originated controls and could replay a completion after backgrounding. The error path did not publish distinct failure feedback. A terminal update could also end a pending Pause/Resume without reaching the requested phase. Health-card navigation used a success cue, and both resize directions used ToggleOn.

Watch-local requests now own transient feedback until completion. A confirmation requires the actual Health Services callback to reach the requested phase, followed by the existing durable journal commit and published state. An unexpected terminal phase produces an error/rejection. Passive readings, repeated Start and no-op controls cannot produce additional confirmations. Phone-originated requests clear Watch feedback ownership; the phone confirms only after its existing exact request/revision/phase checks observe the committed Watch update. Missing peers, rejection and timeout use failure feedback on the requesting phone.

Each process exposes a bounded, non-replaying event stream. UI collection runs only while resumed, and the Watch additionally stops collection in ambient mode. Returning, waking and recreating do not replay old feedback. No health values or identifiers travel in this event stream. Feedback is optional and cannot block recording, transport or persistence. A service-launch failure becomes a visible error rather than escaping the click handler.

## Current interaction map

| Context | Meaning |
|---|---|
| Buttons, page navigation, selector/chart boundaries | Input tick; no claim of saved success |
| Hold to rearrange a Health card | LongPress recognition, then boundary ticks while moving |
| Card expand/compact | ToggleOn/ToggleOff matching the new visible size; existing persistence error/rollback remains |
| Watch workout request | Pending until the authoritative callback and journal commit |
| Local Watch command completed | One Confirm or Reject on the interactive Watch |
| Phone control of Watch workout | One Confirm or Reject on the resumed phone; Watch stays quiet |
| Passive reading, ambient, background, return | No command confirmation |

Native semantic haptics retain the platform's device-specific behavior and system touch-feedback preference. No custom vibration pattern or settings override was added. See Android's [event feedback guidance](https://developer.android.com/develop/ui/views/haptics/haptic-feedback) and [semantic constants](https://developer.android.com/reference/android/view/HapticFeedbackConstants). The per-device ownership and persistence boundary are Orbit's application policy, extending the previously reconciled Apple research, not a claim about undocumented Apple internals.

The Watch uses native paging and fixed recorded numerals, without a synthetic heart beat or decorative perpetual animation. Phone card illustrations remain static, dot numerals render the supplied value, and the timer pattern changes only through interaction. Existing reduced-motion/readability preferences and native Watch system animation settings remain independent of feedback.

## Verification and limits

- Native feedback checks exercise success/rejection routing, background and ambient suppression, return without replay, and a real missing-peer controller failure.
- The existing real local Health Services workout test now checks a repeated Start confirms once, a stale local command rejects, displayed success is already durable, and remote Pause/Resume/Finish plus passive callbacks/recreation do not emit Watch confirmations.
- Two existing native Watch gesture/workout flows pass with system animator duration scale set to zero. The prior setting is restored exactly. Existing phone tests cover the app's reduced-motion preference.
- The checkpoint at [native-command-feedback](../verification/samsung-import/native-command-feedback/checkpoint.json) records final source, suite results and artifacts. Controlled phone feedback input is not paired transport proof.

Physical haptic strength/feel, timing over a real paired connection, OEM accessibility/rotary behavior and hardware acceptance remain open. Both physical devices and the original distribution are untouched.

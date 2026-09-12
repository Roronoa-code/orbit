# Workouts redesign — 13 September 2026

Scope: Workouts only, confirmed by the user. Existing Home motion and BitChord material work are included in the same batch. The screenshot of the two large containers is rejected.

## Research and choice

- [Apple Fitness: starting a workout](https://support.apple.com/en-gb/guide/iphone/iph8475d8510/ios) separates choosing a workout, setting its goal, live control and the saved summary. Borrow the clear stages, not Apple-only sensors or coaching.
- [Hevy calendar](https://www.hevyapp.com/features/gym-consistency/) marks days with training and opens the day's sessions. Borrow date-first inspection. Keep the user's requested week navigation instead of a long feed.
- [Samsung Health trackers](https://www.samsung.com/us/support/answer/ANS10001351/) distinguishes exercise and history. Its comprehensive metrics are useful; stacking large identical containers is what the user rejected here.
- [BitChord material source](https://github.com/Roronoa-code/BitChord/blob/70394304ee718d160cd25e41fbcbaef39c05b45c/app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt) remains the reference for translucent navigation. See THIRD-PARTY-NOTICES.md for the shader attribution and web adaptation limits.

Considered: (1) a calendar-led training diary, (2) a large single-activity carousel, (3) a compact Train / History hub. The diary adds work before starting; the carousel hides choices. Choose the hub: all four activities immediately visible, history gets its own coherent screen.

## Direction

One job per view. Train contains four compact activity tiles and a quiet weekly summary. History leads with active time, shows daily workload in one week strip, then opens individual records. Setup gives the goal room and keeps GPS and saved weight in short rows. The live view centres the dot timer with unboxed supporting readings; music still opens into full-screen artwork. Saved records use clear reading sections rather than repeated heavy rounded panels.

Keep Orbit's charcoal, lavender, dots, typography and glyphs. Glass belongs to the view switch and controls. Use spacing and hairlines for data. Activity tiles are actions, not containers for last-session metadata. Preserve 48px-class touch targets, labelled buttons, keyboard access, reduced motion and high contrast.

Motion: existing interruptible BlobTrack for the view switch; short content translation/fade; stable control sizes through music expansion. No new animation package or perpetual decorative loop. Check actual intermediate frames and final geometry.

Data: retain all four activities, open/time goals, countdown cancel, optional saved weight, GPS choice, active/paused/total time, route/speed/elevation, estimated energy and old time-only records. No invented sets, heart rate, routes or training scores. Short demo sessions display seconds instead of rounding to zero minutes.

Risk to test: repeated fast navigation, date/week boundaries, narrow widths, live controls with and without music, readable missing-data states, and the existing phone frame-time limitations. Screenshots establish composition only.

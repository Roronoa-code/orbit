# Orbit — standalone dashboard

The current build is installed on the Galaxy S25 Ultra as `com.mani.orbit`. Installation and phone checks completed around midnight 9–10 September 2026, Europe/London. The installed package matches `dist/Orbit.apk`; SHA256: `ccf45820fbe94d8e4d4a9fe416ed13dd6217221656493c73c1ef04a32097f9d5`. The existing 10,000-step goal was retained.

The cards now have level, rounded rectangular surfaces, two columns of larger values, clear labels and quieter charcoal frost. The folded rear cards are spaced 6 pixels apart. Black and charcoal remain the base colours, with purple highlights. All four panels and their charts remain available by swiping up.

The three-dot circle expands downward into its own inline surface for Options, goal, date and About. No extra circle or separate modal appears. Swiping down on the bottom bar opens Body composition, Workouts and Blood oxygen; selecting a circle expands the same surface to show its demonstration readings. Tapping the bar closes it. Swiping up opens the four cards; tapping the bar closes those cards. Android Back dismisses an inline surface before returning to today's Steps or exiting.

The globe retains its 646 silver points on a persistent canvas. Card and orb movement use fixed layout sizes, continuous spring motion and reversible transitions. The frost layer stays unscaled while its visible outline changes. Reduced motion uses immediate endpoints. Expanded charts keep their direct reading controls and native vertical scrolling.

Verification on this build:

- `node verification/check.cjs` passes four metrics across 30 dates and three periods, data totals, metric gestures, spring timing and reversals, scroll guards, touch-release handling, activity choices and inline surface behaviour.
- `verification/motion-review.html` passes at 390 and 320 CSS pixels, including all four compact two-column summaries, unclipped values, card movement, lower-orb hit testing, the last card, all inline forms, all three activities and closing during an opening transition. Minimum orb-to-period clearance was 11.67 pixels.
- Physical-phone captures confirm the revised folded and expanded cards, three-dot expansion, goal input with the numeric keyboard, saving the unchanged goal, downward swipe to the three choices, every activity detail, Android Back dismissal and applying the previous date. Evidence is in `verification/clear-cards-deployment/`.
- The final six-tap menu exercise recorded 494 rendered frames, 5.87% janky frames, a 10 ms median and a 16 ms 95th percentile. This short rendering sample does not establish zero stutter or broader device qualification. See `final-six-menu-taps.txt`.
- The installed APK, bundled build HTML and current source script were matched. See `verification/clear-cards-deployment/verification.json`.

All figures are synthetic, ending 8 September 2025. No sensors, accounts or network connection are used. Browser and Android goal settings are separate. This remains the standalone Orbit copy; it is not integrated into the Health app. Visual acceptance remains with the user.

Open `index.html` in a browser, or install `dist/Orbit.apk`. Swipe or tap the orb, or use its arrow keys, to select Steps, Heart rate, Sleep or Intake. Today, 7D and 30D select the period. Intake's 30D value is a daily average. The font and assets are embedded; retain `Manrope-OFL.txt` with redistributed copies.

Rebuild with `python android/build.py` using its installed Java 17 and Android SDK, or supply JAVA_HOME/ANDROID_HOME. No dependencies are downloaded. Preserve `android/signing/orbit-local.p12` for compatible updates. Earlier builds are retained automatically. The baseline before the card revision is `verification/before-clean-cards-20260909-235427/`; earlier inline and material baselines are also retained under `verification/`.

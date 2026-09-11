# Orbit — standalone Steps copy

Open `index.html` in a modern browser. The font, styles, graphics and demonstration data are embedded, so the HTML works offline. Keep `Manrope-OFL.txt` with redistributed copies. This is the separate HTML design requested for comparison; it does not install or replace the Android app or modify the original Signal preview.

Today, 7D and 30D change the totals and charts. The date button explores 30 demonstration days ending Monday, 8 September 2025, matching the reference. Additional earlier sample records support complete previous-period comparisons. Charts support keyboard selection and the arrow buttons open reading tables. Options contains the daily goal and rotation toggle; the system reduced-motion preference also stops rotation.

Goals are saved under a separate browser storage key. The browser verification changed the goal from 10,000 to 9,000 and confirmed it survived reloading. You can restore 10,000 through Steps options. A fresh browser starts at 10,000. Data is synthetic; there is no sensor or account connection. Phone packaging remains a later step as requested.

Verification: rendered desktop page inspected against the supplied reference; Today/7D/30D and keyboard switching, goal saving/reloading and pause toggle exercised in the browser. `node verification/check.cjs` passes all 30 dates across three periods, exact hourly totals, complete previous periods, reference averages, a single animation loop, and reduced-motion/hidden-page stops. Narrow-screen styles are present; physical-phone testing and full native date-picker interaction were not completed before the user took browser control.

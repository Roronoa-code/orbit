# Orbit profiling tools

Relative measurements in headless Chromium (Playwright 1.63, vendored in `node_modules`; Chromium 1243 lives in `%LOCALAPPDATA%\ms-playwright`). Phone geometry 390×844 @3×, 4× CPU throttle. Numbers are relative, not S25 Ultra frame times — compare before/after on the same machine.

Prerequisites: a static server rooted at `C:\HA\design-concepts` on `127.0.0.1:8784` (`python -m http.server 8784 --bind 127.0.0.1`) and
`NODE_PATH=<this folder>\node_modules;C:\Users\abdul\.claude\runtime\node\node_modules` (the second entry supplies `sharp` for `visualdiff.cjs` and the project's live-background check). Run the scripts from this folder.

| Script | What it reports |
| --- | --- |
| `measure2.cjs <label> [throttle] [variants]` | Per scenario (idle, orb taps, deck fold, deck scroll, live bar, page open): main-thread busy %, style/layout ms, raster ms, GPU composite ms, long frames. Variants switch frost layers off for attribution. Writes `measure2-<label>.json`. |
| `measure3.cjs` / `measure3b.cjs <label> [throttle] [variants] [scenarios]` | Same trace sums for the deck fold and live bar under single-feature-off CSS variants. Edit the `VARIANTS` map to test a hypothesis. |
| `measure4.cjs [throttle]` | Direct ms cost of `renderMetric`, `measureDeck`, `renderCharts`, `render`, `cyclePeriod`, `changeMetric`, `layoutDeck`, `layoutIsland`. |
| `measure5.cjs <deck|live|idle> [css]` | Blink invalidation counters and frequent trace events for one scenario. |
| `measure7.cjs <deck|live|idle> [css or js:<code>]` | Which DOM nodes paint images / repaint, grouped by node — the tool that found the shell repaint. `js:` runs code before the scenario (e.g. `js:orb.setPaused(true)`). |
| `probe.cjs` | Counts custom-property writes during a deck cycle and lists live animations. |
| `measure8.cjs <label>` | First deck drag after load, a repeat drag and a drag right after a period change, driven by real touch events: presented-frame gaps and the busiest trace slices in the first 250 ms. |
| `measure9.cjs <label>` | The same three drags with in-page timestamps: time from the first touchmove to the first moved frame, deck frame gaps, long tasks. |
| `measure10.cjs [throttle] [windowMs]` | Main-thread slice timeline from the first touchmove of each drag. |
| `measure11.cjs <label> [throttle] [url]` | Tap-open and flick-open, first and repeat, each in a fresh page: deck frame gaps, heavy lifecycle frames and when each image repaint happens (ms after input). The tool that located the stack repaint at tap start and swipe release. |
| `measure12.cjs <label> [throttle]` | The move between the workout view and the page-wide music view (real tap open, tap close, finger drag down) with a browser music fixture: frame gaps, heavy lifecycle frames, raster and image repaints. |
| `visualdiff.cjs [after]` | Screenshots folded / mid-fold / open / live-bar-open for a "before" tree served at `/_orbit_before_tmp/index.html` and the current tree, masks the orb canvas, and reports differing pixels; images go to `verification/perf-visual-diff/`. The optional argument is CSS for the current page, or `js:<code>` / `jsfile:<path>` to run there first (for example to restore an intentionally changed label so only the rest is compared). `ORBIT_HIDE_ORB=1` hides the orb canvas in both trees: its paused angle differs between runs, and once the globe moves in the mid/open states its dots leave the masked box. Build the before tree by copying the workspace's `*.js`, `*.css`, `index.html` into `C:\HA\design-concepts\_orbit_before_tmp` and overlaying the backup files. Delete the folder afterwards. |

Baseline results from 10–11 September are in `../perf-measurements/` and summarised in `../perf-result.json`.

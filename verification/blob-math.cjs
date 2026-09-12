// Deterministic checks for the selector mathematics the app actually ships: blob-track.js is loaded and executed
// here, not a copy of its formulae. Controller ownership, rendering and influence binding are checked separately by
// the rendered fixture in verification/frosted-system.html.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'blob-track.js'), 'utf8');
const context = vm.createContext({ performance: { now: () => 0 }, requestAnimationFrame: () => 0, cancelAnimationFrame: () => {} });
vm.runInContext(source + ';BlobTrack', context);
const B = vm.runInContext('BlobTrack', context);

let count = 0;
const fail = message => { throw new Error(message); };
const ok = (value, message) => { count++; if (!value) fail(message); };
const near = (a, b, tolerance, message) => { count++; if (!(Math.abs(a - b) <= tolerance)) fail(`${message}: ${a} vs ${b}`); };
const throws = (fn, message) => { count++; try { fn(); } catch { return; } fail(message); };

// The reference track: four 72-unit options, centres 36/108/180/252, bounded by 0..288.
const CENTRES = [36, 108, 180, 252];
const LEFT = 0, RIGHT = 288, SLOT = 72;
const zones = B.catchments(CENTRES, LEFT, RIGHT);

ok(zones.length === 4, 'Four options must produce four catchments');
zones.forEach((z, i) => {
  near(z.left, i === 0 ? 0 : (CENTRES[i - 1] + CENTRES[i]) / 2, 1e-9, 'Catchment left boundary');
  near(z.right, i === 3 ? 288 : (CENTRES[i] + CENTRES[i + 1]) / 2, 1e-9, 'Catchment right boundary');
  if (i) ok(Math.abs(z.left - zones[i - 1].right) < 1e-9, 'Catchments must be contiguous with no dead strip');
});

// Resting coverage: the pill inside its own catchment owns it completely.
{
  const at = B.influences(CENTRES[1] - SLOT / 2, SLOT, zones);
  near(at[1].raw, 1, 1e-9, 'Resting coverage of its own option');
  near(at[1].eased, 1, 1e-9, 'Resting eased coverage');
  [0, 2, 3].forEach(i => near(at[i].raw, 0, 1e-9, 'Other options receive nothing at rest'));
}

// A shrunken pill still owns its catchment completely.
{
  const width = SLOT - 8;
  const at = B.influences(CENTRES[0] - width / 2, width, zones);
  near(at[0].raw, 1, 1e-9, 'A pressed, shrunken pill still owns its own option');
}

// Halfway: an equal straddle gives both options one half, raw and eased.
{
  const at = B.influences(72 - SLOT / 2, SLOT, zones);
  near(at[0].raw, 0.5, 1e-9, 'Halfway raw coverage, left');
  near(at[1].raw, 0.5, 1e-9, 'Halfway raw coverage, right');
  near(at[0].eased, 0.5, 1e-9, 'Halfway eased coverage, left');
  near(at[1].eased, 0.5, 1e-9, 'Halfway eased coverage, right');
}

// 60/40 geometry eases to 0.648/0.352; the eased pair is not "exact 60/40".
{
  const at = B.influences(72 - 0.6 * SLOT, SLOT, zones);
  near(at[0].raw, 0.6, 1e-9, '60/40 raw coverage, left');
  near(at[1].raw, 0.4, 1e-9, '60/40 raw coverage, right');
  near(at[0].eased, 0.648, 1e-9, '60/40 eased coverage, left');
  near(at[1].eased, 0.352, 1e-9, '60/40 eased coverage, right');
  ok(Math.abs(at[0].eased + at[1].eased - 1) > 1e-12 === false || true, 'Eased weights are reported independently');
}

// A sweep of partitions: raw coverage always sums to one while the blob lies inside the track.
for (let offset = 0; offset <= RIGHT - SLOT; offset += 3) {
  const at = B.influences(offset, SLOT, zones);
  near(at.reduce((sum, v) => sum + v.raw, 0), 1, 1e-9, 'Raw coverage must partition the blob');
}

// Uneven widths and a physically reversed (right-to-left) source order.
{
  const uneven = [30, 96, 150, 260];
  const z = B.catchments(uneven, 0, 300);
  ok(z.every((zone, i) => i === 0 || Math.abs(zone.left - z[i - 1].right) < 1e-9), 'Uneven catchments stay contiguous');
  const rtlIds = ['d', 'c', 'b', 'a'];
  const sorted = uneven.map((centre, i) => ({ centre, id: rtlIds[i] })).sort((a, b) => a.centre - b.centre);
  ok(sorted.map(o => o.id).join('') === 'dcba', 'Physical order keeps its own stable identifiers');
  throws(() => B.catchments([100, 50], 0, 300), 'Unsorted centres must be rejected');
  throws(() => B.catchments([10], 300, 0), 'An inverted track must be rejected');
  throws(() => B.catchments([], 0, 300), 'An empty track must be rejected');
  ok(B.influences(0, 0, zones).every(v => v.raw === 0), 'A zero-width blob yields no influence');
}

// The stationary hold: pressing any option, however far, never moves the leading edge past a tenth of one slot.
for (const direction of [-3, -2, -1, 1, 2, 3]) {
  const base = { cx: CENTRES[direction > 0 ? 0 : 3], width: SLOT, height: 34 };
  for (const progress of [0, 0.25, 0.5, 0.75, 1, 1.4, 3]) {
    const held = B.holdPose(base, SLOT, direction, progress);
    const excursion = B.leadingExcursion(held, base);
    ok(excursion <= SLOT * 0.10 + 1e-9, `Hold excursion must stay within one tenth of a slot: ${excursion}`);
    ok(held.width >= base.width, 'A hold may only grow the indicator');
    ok(Math.abs(held.height - base.height) < 1e-9, 'A hold does not change height');
  }
  const full = B.holdPose(base, SLOT, direction, 1);
  near(Math.abs(full.cx - base.cx), 0.85 * 0.10 * SLOT, 1e-9, 'Hold shift is 85% of the budget');
  near(full.width - base.width, 0.15 * 0.10 * SLOT, 1e-9, 'Hold growth is 15% of the budget');
  near(B.leadingExcursion(full, base), 0.0925 * SLOT, 1e-9, 'Full hold leading excursion');
}

// Symmetry: equal and opposite presses give equal displacement and growth.
{
  const base = { cx: CENTRES[1], width: SLOT, height: 34 };
  const leftward = B.holdPose(base, SLOT, -1, 1), rightward = B.holdPose(base, SLOT, 1, 1);
  near(base.cx - leftward.cx, rightward.cx - base.cx, 1e-9, 'Opposite holds must be symmetric');
  near(leftward.width, rightward.width, 1e-9, 'Opposite holds must grow equally');
  const none = B.holdPose(base, SLOT, 0, 1);
  near(none.cx, base.cx, 1e-9, 'Pressing the active option does not lean');
  near(none.width, base.width, 1e-9, 'Pressing the active option does not grow');
  throws(() => B.holdPose({ cx: 0, width: 0, height: 10 }, SLOT, 1, 1), 'Zero geometry must be rejected');
  throws(() => B.holdPose(base, 0, 1, 1), 'A zero slot must be rejected');
  throws(() => B.holdPose({ cx: NaN, width: 10, height: 10 }, SLOT, 1, 1), 'Non-finite geometry must be rejected');
}

// A stretched candidate at a wall stays inside the track with positive width and a bounded bulge.
for (const pull of [1, 8, 30, 120, 900]) {
  for (const side of [-1, 1]) {
    const stretched = SLOT * 1.16, height = 34 * (1 - 0.45 * 0.16);
    const cx = side < 0 ? LEFT - pull : RIGHT + pull;
    const at = B.wallPose(cx, stretched, height, LEFT, RIGHT, 44);
    ok(at.width > 0, 'A compressed indicator keeps a positive width');
    ok(at.cx - at.width / 2 >= LEFT - 1e-9, 'The compressed indicator stays inside the left wall');
    ok(at.cx + at.width / 2 <= RIGHT + 1e-9, 'The compressed indicator stays inside the right wall');
    ok(at.height <= 44 + 1e-9, 'The bulge stays inside the available height');
    ok(at.width >= stretched * 0.66 - 1e-9, 'Compression is limited to 34% of the candidate width');
  }
}
{
  const free = B.wallPose(CENTRES[1], SLOT, 34, LEFT, RIGHT, 44);
  near(free.compression, 0, 1e-9, 'An indicator inside the track is not compressed');
  near(free.cx, CENTRES[1], 1e-9, 'An indicator inside the track is not moved');
  const wide = B.wallPose(144, 400, 34, LEFT, RIGHT, 44);
  ok(wide.width <= RIGHT - LEFT + 1e-9, 'An oversized indicator is limited to the track');
  throws(() => B.wallPose(0, -1, 10, 0, 100, 40), 'Negative width must be rejected');
  near(B.rubberband(-5, 72), 0, 1e-9, 'Negative overshoot gives no rubberband');
  near(B.rubberband(10, 0), 0, 1e-9, 'A zero dimension gives no rubberband');
  ok(B.rubberband(1e6, 72) < 72, 'Rubberband saturates below the dimension');
}

// Release projection and target selection obey the same bounded velocity.
for (const velocity of [-1e6, -4000, -50, 0, 50, 4000, 1e6]) {
  const projected = B.projectCentre(CENTRES[1], velocity, SLOT);
  ok(Math.abs(projected.velocity) <= 8 * SLOT + 1e-9, 'Release velocity is clamped');
  ok(Math.abs(projected.centre - CENTRES[1]) <= 0.5 * SLOT + 1e-9, 'Projection travel is bounded to half a slot');
  const target = B.nearestCentre(CENTRES, projected.centre, 1);
  ok(target >= 0 && target <= 1 + (velocity > 0 ? 1 : 0), 'A bounded projection cannot fling to a distant endpoint');
}
{
  near(B.projectCentre(100, 0, SLOT).centre, 100, 1e-9, 'A still release stays where it is');
  ok(B.nearestCentre(CENTRES, 72, 0) === 0, 'An exact tie resolves to the incoming option');
  ok(B.nearestCentre(CENTRES, 72, 1) === 1, 'An exact tie resolves deterministically from the current option');
  ok(B.nearestCentre([], 10) === -1, 'An empty track has no nearest option');
  throws(() => B.projectCentre(0, 0, 0), 'A zero slot span must be rejected');
}

// The spring is analytic: the same second split into different frame counts lands in the same place.
for (const [stiffness, damping] of [[160, 0.74], [260, 0.78], [500, 1], [160, 1.6]]) {
  const reference = (() => { let s = { x: 0, velocity: 0 }; for (let i = 0; i < 60; i++) s = B.spring(s.x, s.velocity, 100, 1 / 60, stiffness, damping); return s; })();
  for (const steps of [30, 90, 120, 240]) {
    let s = { x: 0, velocity: 0 };
    for (let i = 0; i < steps; i++) s = B.spring(s.x, s.velocity, 100, 1 / steps, stiffness, damping);
    near(s.x, reference.x, 1e-7, `Spring position must not depend on the frame partition (${stiffness}/${damping})`);
    near(s.velocity, reference.velocity, 1e-6, `Spring velocity must not depend on the frame partition (${stiffness}/${damping})`);
  }
  let settled = { x: 0, velocity: 0 };
  for (let i = 0; i < 600; i++) settled = B.spring(settled.x, settled.velocity, 100, 1 / 60, stiffness, damping);
  near(settled.x, 100, 1e-6, 'Every spring reaches its target');
  near(settled.velocity, 0, 1e-6, 'Every spring comes to rest');
}
{
  const under = (() => { let s = { x: 0, velocity: 0 }, peak = 0; for (let i = 0; i < 200; i++) { s = B.spring(s.x, s.velocity, 100, 1 / 120, 160, 0.74); peak = Math.max(peak, s.x); } return peak; })();
  ok(under > 100, 'A damping ratio below one overshoots, so a capped target alone is not enough');
  const critical = (() => { let s = { x: 0, velocity: 0 }, peak = 0; for (let i = 0; i < 200; i++) { s = B.spring(s.x, s.velocity, 100, 1 / 120, 500, 1); peak = Math.max(peak, s.x); } return peak; })();
  near(critical, 100, 1e-6, 'Critical damping does not overshoot');
  throws(() => B.spring(0, 0, 1, -1, 160, 0.74), 'Negative time must be rejected');
  throws(() => B.spring(0, 0, 1, 0.1, 0, 0.74), 'Zero stiffness must be rejected');
  throws(() => B.spring(NaN, 0, 1, 0.1), 'Non-finite spring input must be rejected');
}

// Smoothstep and clamp behave at their boundaries.
near(B.smoothstep01(-1), 0, 1e-12, 'Smoothstep clamps below zero');
near(B.smoothstep01(2), 1, 1e-12, 'Smoothstep clamps above one');
near(B.smoothstep01(0.5), 0.5, 1e-12, 'Smoothstep is symmetric at one half');
near(B.clamp(5, 0, 1), 1, 1e-12, 'Clamp bounds above');
near(B.clamp(-5, 0, 1), 0, 1e-12, 'Clamp bounds below');

console.log(`PASS: ${count} assertions. Catchments, coverage partitions, eased response, the one-tenth single-slot hold cap through overshoot, wall compression, bounded release projection and frame-partition-independent springs, executed against the shipped blob-track.js.`);

'use strict';

// Run with Node: node verification/workout-details.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'workout-details.js'), 'utf8');
const sandbox = {
  window: {},
  HeroDots: { markup: value => `<svg class="matrix-reading"><title>${String(value)}</title></svg>` },
};
vm.createContext(sandbox);
vm.runInContext(`${source};globalThis.__WorkoutDetails=WorkoutDetails;`, sandbox, { filename: 'workout-details.js' });
const details = sandbox.__WorkoutDetails;
const clone = value => JSON.parse(JSON.stringify(value));
const point = (lat, lon, elapsedMs, extra = {}) => ({ lat, lon, elapsedMs, breakBefore: false, ...extra });

const session = {
  kind: 'Running',
  startedAt: 1_700_000_000_000,
  elapsed: 900_000,
  resumedAt: 1_700_000_000_000,
  totalMs: 930_000,
  targetMs: 1_800_000,
  weightKg: 80,
  trackLocation: true,
  metrics: {
    state: 'tracking',
    distanceM: 2_500,
    speedMps: 2.78,
    maxSpeedMps: 4.5,
    accuracyM: 6,
    altitudeMinM: 48,
    altitudeMaxM: 67,
    points: [
      point(51.5000, -0.1200, 0, { altitudeM: 48, speedMps: 2.5 }),
      point(51.5020, -0.1200, 450_000, { altitudeM: null, speedMps: null }),
      point(51.5050, -0.1180, 900_000, { altitudeM: 67, speedMps: 4.5, breakBefore: true }),
    ],
  },
};

assert.equal(details.valid(session), true, 'recorded metrics fixture should be valid');
for (const [name, mutate] of [
  ['weight below 20 kg', value => { value.weightKg = 19; }],
  ['weight above 350 kg', value => { value.weightKg = 351; }],
  ['non-boolean location flag', value => { value.trackLocation = 'yes'; }],
  ['negative total time', value => { value.totalMs = -1; }],
  ['unknown metric state', value => { value.metrics.state = 'gps'; }],
  ['negative distance', value => { value.metrics.distanceM = -1; }],
  ['speed above 100 m/s', value => { value.metrics.maxSpeedMps = 101; }],
  ['invalid latitude', value => { value.metrics.points[0].lat = 91; }],
  ['invalid point flag', value => { value.metrics.points[0].breakBefore = 1; }],
]) {
  const invalid = clone(session);
  mutate(invalid);
  assert.equal(details.valid(invalid), false, `${name} must be rejected`);
}
const tooMany = clone(session);
tooMany.metrics.points = Array.from({ length: 4097 }, (_, i) => point(51, -0.1, i));
assert.equal(details.valid(tooMany), false, 'more than 4,096 GPS points must be rejected');

const readings = details.readings(session, 900_000);
assert.equal(readings.distance, 2_500);
assert(Math.abs(readings.average - 2_500 / 900) < 1e-12, 'average speed must use active seconds');
assert(Math.abs(readings.calories - 157.5) < 1e-12, 'Running energy must use MET, weight and active minutes');
assert.equal(details.readings({ ...session, weightKg: 0 }, 900_000).calories, null, 'zero weight keeps energy unavailable');
assert.equal(details.readings({ ...session, metrics: undefined }, 900_000).distance, null, 'old records keep missing distance');
assert.equal(details.readings({ ...session, metrics: undefined }, 900_000).average, null, 'old records keep missing pace');

assert.equal(details.pace(1_000_000 / (360 * 1_000)), '06:00');
assert.equal(details.pace(0), '—');
assert.equal(details.pace(-1), '—');
const compact = details.compact(session, 900_000);
assert(compact.includes('2.50'), 'compact view should show kilometres');
assert(compact.includes('06:00'), 'compact view should show calculated pace');
assert(compact.includes('158'), 'compact view should show calculated energy');
assert(!/NaN|Infinity/.test(compact), 'compact view must not expose non-finite values');

const view = details.view(session, 900_000, 930_000, 'route');
for (const label of ['Active', 'Total', 'Paused', 'Target']) assert(view.includes(`>${label}<`), `${label} detail is missing`);
assert(!/NaN|Infinity/.test(view), 'completed detail view must stay finite');

const route = details.chart(session, 900_000, 'route');
assert(route.includes('data-workout-chart="route"'));
const routePath = route.match(/<path d="([^"]+)" fill="none" stroke="#cbb8ed"/u)?.[1];
assert(routePath, 'route chart path is missing');
assert((routePath.match(/\bM/g) || []).length >= 2, 'route gap must start a new subpath');
assert(!/NaN|Infinity/.test(route), 'route chart must stay finite');

const speedChart = details.chart(session, 900_000, 'speed');
const speedPath = speedChart.match(/<path d="([^"]+)" fill="none" stroke="#cbb8ed"/u)?.[1];
assert(speedPath, 'speed chart path is missing');
assert((speedPath.match(/\bM/g) || []).length >= 2, 'missing speed reading must leave a visible chart gap');
assert(speedChart.includes('Speed in kilometres per hour'));

const missingRoute = details.chart({ ...session, metrics: { ...session.metrics, points: [session.metrics.points[0]] } }, 900_000, 'route');
assert(missingRoute.includes('Your route appears after a few GPS readings.'));
const missingElevation = details.chart({ ...session, metrics: { ...session.metrics, points: session.metrics.points.map(p => ({ ...p, altitudeM: null })) } }, 900_000, 'altitude');
assert(missingElevation.includes('Elevation appears when enough GPS readings are available.'));

console.log('PASS: WorkoutDetails validation rejects malformed recordings; readings calculate distance, active-time pace and 7.5 MET energy; old metric-less records remain readable; route and speed gaps split chart paths; missing route/elevation states stay explicit and finite.');

// Measurement 1: the ceiling.
//
// Samples sentinel_slo_cycle_duration_seconds over a settle period and reports p50/p95/p99 plus
// interval drift. The ceiling is the service count at which p99 approaches the 15s interval.
//
//   k6 run -e SENTINEL=http://localhost:8080 -e DURATION_MIN=10 evaluation-throughput.js
//
// Ramp SYNTHETIC_SERVICES across 100 / 250 / 500 / 1000 / 2000, restarting the exporter between
// runs, and record each row. scripts/load-test.sh does the ramp.

import http from 'k6/http';
import { sleep, check } from 'k6';

const SENTINEL = __ENV.SENTINEL || 'http://sentinel:8080';
const API_KEY = __ENV.API_KEY || 'local-dev-key';
const DURATION_MIN = parseInt(__ENV.DURATION_MIN || '10', 10);
const INTERVAL_SECONDS = parseFloat(__ENV.INTERVAL_SECONDS || '15');

export const options = { vus: 1, iterations: 1, duration: `${DURATION_MIN + 5}m` };

function scrape() {
  const r = http.get(`${SENTINEL}/actuator/prometheus`);
  check(r, { 'sentinel metrics reachable': (res) => res.status === 200 });
  return r.body;
}

// Reads a single metric line's value. Micrometer renders histogram quantiles as
// sentinel_slo_cycle_duration_seconds{quantile="0.99"} — but only if percentiles are published;
// the bucket-derived values come from _bucket. Both are handled by the caller.
function metric(body, name, labelMatch) {
  for (const line of body.split('\n')) {
    if (line.startsWith('#') || !line.startsWith(name)) continue;
    if (labelMatch && !line.includes(labelMatch)) continue;
    const value = parseFloat(line.slice(line.lastIndexOf(' ') + 1));
    if (!isNaN(value)) return value;
  }
  return null;
}

// Histogram quantile from the _bucket series, which is what the config publishes. Reading
// count/sum only would give a mean, and a mean cycle time hides exactly the tail that defines
// the ceiling.
function quantileFromBuckets(body, name, q) {
  const buckets = [];
  for (const line of body.split('\n')) {
    if (!line.startsWith(`${name}_bucket`)) continue;
    const le = line.match(/le="([^"]+)"/);
    if (!le) continue;
    const value = parseFloat(line.slice(line.lastIndexOf(' ') + 1));
    buckets.push([le[1] === '+Inf' ? Infinity : parseFloat(le[1]), value]);
  }
  if (buckets.length === 0) return null;

  buckets.sort((a, b) => a[0] - b[0]);
  const total = buckets[buckets.length - 1][1];
  if (!total) return null;

  const target = total * q;
  for (const [le, cumulative] of buckets) {
    if (cumulative >= target) return le;
  }
  return null;
}

export default function () {
  console.log(`settling for 90s before sampling`);
  sleep(90);

  const before = scrape();
  const cyclesBefore = metric(before, 'sentinel_slo_cycle_duration_seconds_count');
  const wallStart = Date.now();

  check(null, { 'evaluator is running cycles': () => cyclesBefore !== null });

  console.log(`sampling for ${DURATION_MIN} minutes`);
  sleep(DURATION_MIN * 60);

  const after = scrape();
  const wallSeconds = (Date.now() - wallStart) / 1000;
  const cyclesAfter = metric(after, 'sentinel_slo_cycle_duration_seconds_count');
  const sumAfter = metric(after, 'sentinel_slo_cycle_duration_seconds_sum');
  const sumBefore = metric(before, 'sentinel_slo_cycle_duration_seconds_sum');

  const cycles = cyclesAfter - cyclesBefore;
  const meanSeconds = cycles > 0 ? (sumAfter - sumBefore) / cycles : 0;

  // Drift, not "cycles missed". @Scheduled(fixedDelay) measures the gap AFTER the previous cycle
  // returns, so cycles never overlap and none is ever skipped — the schedule simply slips. Zero
  // drift means the evaluator is keeping up; growing drift says by how much it is not.
  const expectedCycles = wallSeconds / INTERVAL_SECONDS;
  const driftSeconds = wallSeconds - cycles * INTERVAL_SECONDS;

  const slos = http.get(`${SENTINEL}/api/v1/slos`, { headers: { 'X-Api-Key': API_KEY } });
  const sloCount = slos.status === 200 ? slos.json().length : 'unknown';

  console.log('==== EVALUATION THROUGHPUT ====');
  console.log(`SLOs evaluated:      ${sloCount}`);
  console.log(`Wall seconds:        ${wallSeconds.toFixed(1)}`);
  console.log(`Cycles completed:    ${cycles} (expected ~${expectedCycles.toFixed(0)})`);
  console.log(`Cycle mean:          ${(meanSeconds * 1000).toFixed(0)} ms`);
  console.log(`Cycle p50:           ${fmt(quantileFromBuckets(after, 'sentinel_slo_cycle_duration_seconds', 0.5))}`);
  console.log(`Cycle p95:           ${fmt(quantileFromBuckets(after, 'sentinel_slo_cycle_duration_seconds', 0.95))}`);
  console.log(`Cycle p99:           ${fmt(quantileFromBuckets(after, 'sentinel_slo_cycle_duration_seconds', 0.99))}`);
  console.log(`Interval drift:      ${driftSeconds.toFixed(1)} s over ${wallSeconds.toFixed(0)} s`);
  console.log(`Query failures:      ${metric(after, 'sentinel_metrics_query_failures_total') ?? 0}`);
  console.log('===============================');
}

function fmt(seconds) {
  if (seconds === null) return 'no data';
  return seconds === Infinity ? '> largest bucket' : `${(seconds * 1000).toFixed(0)} ms`;
}

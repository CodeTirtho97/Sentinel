// Measurement 5: correctness under duplication.
//
// Publishes 10,000 byte-identical copies of one breach and asserts exactly one incident and one
// timeline entry come out. Not a performance number — this is the evidence behind the idempotency
// claim, and the thing that makes "three layers of dedupe" more than an architecture diagram.
//
//   k6 run -e SENTINEL=http://localhost:8080 -e COUNT=10000 duplicate-replay.js
//
// Requires the `loadtest` Spring profile: /api/v1/loadtest/replay does not exist otherwise.

import http from 'k6/http';
import { sleep, check } from 'k6';

const SENTINEL = __ENV.SENTINEL || 'http://sentinel:8080';
const API_KEY = __ENV.API_KEY || 'local-dev-key';
const COUNT = parseInt(__ENV.COUNT || '10000', 10);
const SERVICE = __ENV.SERVICE || 'synth-c000-s4';

export const options = { vus: 1, iterations: 1, duration: '15m' };

const headers = { 'X-Api-Key': API_KEY };

function incidentsFor(service) {
  const r = http.get(`${SENTINEL}/api/v1/incidents?size=200`, { headers });
  if (r.status !== 200) return [];
  return r.json().filter((i) => i.correlationKey === service || (i.affectedServices || []).includes(service));
}

function metric(body, name) {
  for (const line of body.split('\n')) {
    if (line.startsWith('#') || !line.startsWith(name)) continue;
    const value = parseFloat(line.slice(line.lastIndexOf(' ') + 1));
    if (!isNaN(value)) return value;
  }
  return null;
}

export default function () {
  const before = incidentsFor(SERVICE).length;
  console.log(`baseline: ${before} incidents involving ${SERVICE}`);

  const t0 = Date.now();
  const replay = http.post(`${SENTINEL}/api/v1/loadtest/replay?count=${COUNT}&service=${SERVICE}`, null, {
    headers,
    timeout: '600s',
  });
  check(replay, { 'replay accepted': (r) => r.status === 200 });
  if (replay.status !== 200) {
    console.error(`replay failed: ${replay.status} ${replay.body}`);
    console.error('a 404 here means the `loadtest` Spring profile is not active');
    return;
  }

  const eventId = replay.json('eventId');
  console.log(`published ${COUNT} copies of ${eventId} in ${replay.json('publishMillis')}ms`);

  // Drain: wait until the consumer has caught up and the count has held steady. An assertion that
  // fired on the first reading would pass before the duplicates had even been delivered.
  let stableFor = 0;
  let lastCount = -1;
  const deadline = t0 + 10 * 60 * 1000;

  while (Date.now() < deadline && stableFor < 45) {
    sleep(5);
    const count = incidentsFor(SERVICE).length;
    if (count === lastCount) stableFor += 5;
    else {
      stableFor = 0;
      lastCount = count;
    }
  }

  const created = incidentsFor(SERVICE);
  const newIncidents = created.length - before;
  const drainSeconds = (Date.now() - t0) / 1000;

  // One breach detection, however many times delivered, is one timeline entry.
  let timelineEntries = 'n/a';
  if (created.length > 0) {
    const detail = http.get(`${SENTINEL}/api/v1/incidents/${created[0].id}`, { headers });
    if (detail.status === 200) {
      timelineEntries = detail.json('timeline').filter((e) => e.kind === 'BREACH').length;
    }
  }

  const metrics = http.get(`${SENTINEL}/actuator/prometheus`).body;

  console.log('==== DUPLICATE REPLAY ====');
  console.log(`Events replayed:       ${COUNT}`);
  console.log(`Incidents created:     ${newIncidents}   (expected 1)`);
  console.log(`Duplicate incidents:   ${Math.max(0, newIncidents - 1)}   (expected 0)`);
  console.log(`Breach timeline rows:  ${timelineEntries}   (expected 1)`);
  console.log(`Drain time:            ${drainSeconds.toFixed(1)} s`);
  console.log(`Dead-lettered:         ${metric(metrics, 'sentinel_consumer_dlt_total') ?? 0}   (expected 0)`);
  console.log('==========================');

  check(null, {
    'exactly one incident': () => newIncidents === 1,
    'exactly one breach timeline entry': () => timelineEntries === 1,
    'nothing dead-lettered': () => (metric(metrics, 'sentinel_consumer_dlt_total') ?? 0) === 0,
  });
}

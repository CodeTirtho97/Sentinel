// Measurements 2 and 3: end-to-end latency under load, and the alert-collapse ratio.
//
// Flips a fraction of synthetic chains into breach at a known instant, then measures how long the
// first and last incidents take to appear, and how many raw breaches those incidents absorbed.
//
//   k6 run -e SENTINEL=http://localhost:8080 -e EXPORTER=http://localhost:8089 -e FRACTION=0.3 breach-storm.js
//
// The collapse ratio is the product's value expressed as a number: raw breaches divided by
// incidents. Chains are five deep, so a correctly correlating system approaches 5:1 here — five
// services breaking together become one incident naming the leaf.

import http from 'k6/http';
import { sleep, check } from 'k6';

const SENTINEL = __ENV.SENTINEL || 'http://sentinel:8080';
const EXPORTER = __ENV.EXPORTER || 'http://synthetic-exporter:8080';
const API_KEY = __ENV.API_KEY || 'local-dev-key';
const FRACTION = parseFloat(__ENV.FRACTION || '0.3');
const WAIT_MIN = parseInt(__ENV.WAIT_MIN || '8', 10);

export const options = { vus: 1, iterations: 1, duration: `${WAIT_MIN + 10}m` };

const headers = { 'X-Api-Key': API_KEY };

function incidents() {
  const r = http.get(`${SENTINEL}/api/v1/incidents?size=200`, { headers });
  return r.status === 200 ? r.json() : [];
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
  // A clean baseline matters: incidents left over from a previous run would be counted as part of
  // this storm and inflate the collapse ratio's denominator.
  const before = incidents().length;
  console.log(`baseline: ${before} incidents already open`);

  console.log('settling 90s so the evaluator has healthy history');
  sleep(90);

  const t0 = Date.now();
  const flip = http.post(`${EXPORTER}/breach?fraction=${FRACTION}`);
  check(flip, { 'breach injected': (r) => r.status === 200 });
  const breachingServices = flip.json('breachingServices');
  console.log(`t0: ${breachingServices} services flipped into breach`);

  let firstIncidentMs = null;
  let lastCount = before;
  let stableFor = 0;
  const deadline = t0 + WAIT_MIN * 60 * 1000;

  // Poll until the incident count stops growing for a full minute — the storm has landed when it
  // stops arriving, not when the first one shows up.
  while (Date.now() < deadline) {
    sleep(5);
    const current = incidents();
    const count = current.length;

    if (firstIncidentMs === null && count > before) {
      firstIncidentMs = Date.now() - t0;
      console.log(`first incident after ${(firstIncidentMs / 1000).toFixed(1)}s`);
    }

    if (count === lastCount && count > before) {
      stableFor += 5;
      if (stableFor >= 60) break;
    } else {
      stableFor = 0;
      lastCount = count;
    }
  }

  const settleMs = Date.now() - t0;
  const finalIncidents = incidents();
  const opened = finalIncidents.filter((i) => i.state !== 'RESOLVED');

  // Raw breaches is the honest denominator: the number of alerts a naive system would have paged
  // for. It comes from the incidents' own breach counts, not from an assumption about the fleet.
  const rawBreaches = finalIncidents.reduce((sum, i) => sum + (i.breachCount || 0), 0);
  const incidentCount = finalIncidents.length - before;

  const metrics = http.get(`${SENTINEL}/actuator/prometheus`).body;

  console.log('==== BREACH STORM ====');
  console.log(`Services breaching:      ${breachingServices}`);
  console.log(`Time to first incident:  ${firstIncidentMs === null ? 'none' : (firstIncidentMs / 1000).toFixed(1) + ' s'}`);
  console.log(`Time to settle:          ${(settleMs / 1000).toFixed(1)} s`);
  console.log(`Incidents created:       ${incidentCount}`);
  console.log(`Raw breaches absorbed:   ${rawBreaches}`);
  console.log(`ALERT COLLAPSE RATIO:    ${incidentCount > 0 ? (rawBreaches / incidentCount).toFixed(2) : 'n/a'}:1`);
  console.log(`Still open:              ${opened.length}`);
  console.log(`Consumer DLT total:      ${metric(metrics, 'sentinel_consumer_dlt_total') ?? 0}`);
  console.log(`RCA fallbacks:           ${metric(metrics, 'sentinel_rca_fallbacks_total') ?? 0}`);
  console.log('======================');

  // Correlation is doing real work only if it collapsed anything at all. One incident per breach
  // means the component walk found nothing, and the headline number is a fiction.
  check(null, {
    'incidents were created': () => incidentCount > 0,
    'correlation collapsed multiple breaches per incident': () => rawBreaches > incidentCount,
    'nothing dead-lettered': () => (metric(metrics, 'sentinel_consumer_dlt_total') ?? 0) === 0,
  });
}

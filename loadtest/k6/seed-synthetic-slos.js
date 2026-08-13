// Creates two SLOs per synthetic service: one AVAILABILITY, one LATENCY.
//
// Run before any measurement. The evaluator only evaluates SLOs that exist, so an unseeded run
// measures an empty cycle and reports a wonderfully fast p99 that means nothing at all.
//
//   k6 run -e SENTINEL=http://localhost:8080 -e EXPORTER=http://localhost:8089 seed-synthetic-slos.js

import http from 'k6/http';
import { check } from 'k6';

const SENTINEL = __ENV.SENTINEL || 'http://sentinel:8080';
const EXPORTER = __ENV.EXPORTER || 'http://synthetic-exporter:8080';
const API_KEY = __ENV.API_KEY || 'local-dev-key';

// One iteration; the work is a batched fan-out inside it rather than a VU pool, because creating
// 4000 SLOs is a setup step and not the thing being measured.
export const options = { vus: 1, iterations: 1, duration: '10m' };

const headers = { 'Content-Type': 'application/json', 'X-Api-Key': API_KEY };

export default function () {
  const status = http.get(`${EXPORTER}/status`);
  check(status, { 'exporter reachable': (r) => r.status === 200 });
  const services = status.json('services');

  console.log(`seeding SLOs for ${services.length} synthetic services`);

  // Batched rather than one request at a time: 4000 sequential round trips takes minutes, and
  // this is setup, not measurement.
  const BATCH = 50;
  let created = 0;
  let failed = 0;

  for (let i = 0; i < services.length; i += BATCH) {
    const requests = [];
    for (const service of services.slice(i, i + BATCH)) {
      requests.push(['POST', `${SENTINEL}/api/v1/slos`, JSON.stringify({
        serviceName: service,
        type: 'AVAILABILITY',
        objective: 0.999,
        rollingWindow: 'P30D',
      }), { headers }]);
      requests.push(['POST', `${SENTINEL}/api/v1/slos`, JSON.stringify({
        serviceName: service,
        type: 'LATENCY',
        objective: 0.999,
        // Must match a configured Micrometer bucket, or creation is rejected with 400.
        latencyThresholdMs: 500,
        rollingWindow: 'P30D',
      }), { headers }]);
    }

    for (const response of http.batch(requests)) {
      // 409 means the SLO already exists, which is success for a re-run.
      if (response.status === 201 || response.status === 409) created++;
      else {
        failed++;
        if (failed <= 3) console.error(`seed failed: ${response.status} ${response.body}`);
      }
    }
  }

  console.log(`seeded ${created} SLOs, ${failed} failed`);
  check(null, { 'all SLOs seeded': () => failed === 0 });
}

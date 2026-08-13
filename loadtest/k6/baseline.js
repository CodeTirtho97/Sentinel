import http from 'k6/http';

// Comma-separated so one generator can drive several entry points. Every tree in the topology needs
// its own traffic: rate() over a window with no requests returns nothing, so an untrafficked service
// can never breach and breaking it looks like the platform ignoring a real failure.
const TARGETS = (__ENV.TARGETS || __ENV.TARGET || 'http://api-gateway:8080')
  .split(',')
  .map((t) => t.trim())
  .filter((t) => t.length > 0);

const RPS = parseInt(__ENV.RPS || '20', 10);

// Constant arrival rate, not constant VUs: when the fleet slows down under chaos the offered
// load must stay flat, otherwise the error ratio moves for the wrong reason.
export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: '720h',
      preAllocatedVUs: Math.max(20, RPS),
      maxVUs: Math.max(200, RPS * 10),
    },
  },
  // 5xx responses are the point of the exercise, so do not fail the run on them.
  thresholds: {},
  discardResponseBodies: true,
};

// Round-robin rather than random, so each entry point gets a predictable share of the offered rate
// and the error ratios stay comparable between trees.
let next = 0;

export default function () {
  const target = TARGETS[next % TARGETS.length];
  next += 1;
  http.get(`${target}/work`);
}

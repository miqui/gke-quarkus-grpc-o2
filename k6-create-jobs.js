import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { grpc, JOBS, rpc } from './k6-common.js';

// Submits jobs as fast as clients would (no workers): the CreateJob write path (insert + event in
// one transaction, type registry from the per-pod cache).
export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 10,
  duration: __ENV.DURATION || '10s',
  thresholds: {
    checks: ['rate>0.99'],             // gRPC has no http_req_failed; every call is checked
    grpc_req_duration: ['p(95)<500'],  // 95% of calls below 500ms
  },
};

// One id for the whole run (module-level code runs once per VU): ListJobs {labels: {run}} finds
// everything this run created.
export function setup() {
  return { run: `k6-create-jobs-${Date.now()}` };
}

export default function (data) {
  const res = rpc(`${JOBS}/CreateJob`, {
    name: `Load test job ${randomIntBetween(1, 1000000)}`,
    type: 'report.generate',
    spec: { template: 'monthly', parameters: { month: '2026-09', region: 'us' }, format: 'pdf' },
    labels: { source: 'k6', run: data.run },
    priority: randomIntBetween(0, 9),
  }, 'CreateJob');

  check(res, {
    'status is OK': (r) => r.status === grpc.StatusOK,
    'response has id': (r) => !!(r.message && r.message.id),
    'job is queued': (r) => !!r.message && r.message.state === 'JOB_STATE_QUEUED',
  });

  // Simulate think time between 100ms and 300ms using k6-utils
  sleep(randomIntBetween(1, 3) * 0.1);
}

import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { attemptsOf, createJob, grpc, JOBS, reasonOf, rpc, workerId, WORKERS } from './k6-common.js';

// Proves ClaimJob never hands one job to two workers (JobRepository.claim: FOR UPDATE SKIP LOCKED
// inside a single UPDATE). Setup queues JOBS demo.sleep jobs; VUS workers race to claim them and
// complete each one immediately.
//
// A double claim would show up twice over: the job would end with attempts > 1 (each claim
// increments it), and one of the two workers would get LEASE_NOT_HELD when completing. The
// invariant checked in teardown: every job SUCCEEDED with exactly one attempt, and no completion
// was refused. Run it against an otherwise idle demo.sleep queue.
const JOB_COUNT = __ENV.JOBS ? parseInt(__ENV.JOBS, 10) : 300;

export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 20,
  duration: __ENV.DURATION || '15s',
  setupTimeout: '120s',
  thresholds: {
    // Every claim and every completion must succeed (an empty claim is fine: the queue drained).
    checks: ['rate>0.99'],
    // Teardown: every job claimed exactly once.
    no_double_claims: ['rate==1'],
    lease_not_held: ['count==0'],
  },
};

const claims = new Counter('successful_claims');
const leaseNotHeld = new Counter('lease_not_held');
const noDoubleClaims = new Rate('no_double_claims');

export function setup() {
  const run = `k6-claim-contention-${Date.now()}`;
  for (let i = 0; i < JOB_COUNT; i++) {
    createJob({ name: `contention ${i}`, type: 'demo.sleep', spec: { seconds: 0 }, labels: { run } });
  }
  return { run };
}

const WORKER = workerId('k6-contention');

export default function () {
  const claimRes = rpc(`${WORKERS}/ClaimJob`, { workerId: WORKER, types: ['demo.sleep'], leaseSeconds: 60 }, 'ClaimJob');
  check(claimRes, { 'claim: status is OK': (r) => r.status === grpc.StatusOK });
  const job = claimRes.message && claimRes.message.job;
  if (!job) {
    sleep(0.2); // drained
    return;
  }
  claims.add(1);
  const doneRes = rpc(`${WORKERS}/CompleteJob`, { jobId: job.id, workerId: WORKER, result: { worker: WORKER } }, 'CompleteJob');
  if (reasonOf(doneRes) === 'LEASE_NOT_HELD') {
    leaseNotHeld.add(1);
  }
  check(doneRes, { 'complete: status is OK': (r) => r.status === grpc.StatusOK });
}

export function teardown(data) {
  let jobs = [];
  for (let offset = 0; ; offset += 200) {
    const page = rpc(`${JOBS}/ListJobs`, { labels: { run: data.run }, limit: 200, offset });
    if (page.status !== grpc.StatusOK) {
      throw new Error(`teardown: ListJobs failed, status ${page.status}`);
    }
    const items = page.message.items || [];
    jobs = jobs.concat(items);
    if (items.length < 200) break;
  }
  const succeededOnce = jobs.filter((j) => j.state === 'JOB_STATE_SUCCEEDED' && attemptsOf(j) === 1).length;
  const ok = jobs.length === JOB_COUNT && succeededOnce === JOB_COUNT;
  noDoubleClaims.add(ok);

  console.log(`\n${jobs.length} jobs queued, ${succeededOnce} succeeded with exactly one claim `
    + `(expected ${JOB_COUNT}). "successful_claims" in the summary below must also be ${JOB_COUNT}. `
    + 'If not every job was claimed before the run ended, raise DURATION or VUS.');

  for (const j of jobs) {
    if (j.state !== 'JOB_STATE_SUCCEEDED' && j.state !== 'JOB_STATE_FAILED' && j.state !== 'JOB_STATE_CANCELLED') {
      rpc(`${JOBS}/CancelJob`, { id: j.id });
    }
    rpc(`${JOBS}/DeleteJob`, { id: j.id });
  }
}

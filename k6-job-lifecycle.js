import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { attemptsOf, grpc, JOBS, rpc, versionOf, workerId, WORKERS } from './k6-common.js';

// Every VU plays client and worker: create a job, claim one, heartbeat, then complete it (80%) or
// fail it as retryable (20% - it is re-queued after a 5s backoff and claimed again later), read it
// back (a finished job is cached: miss, then hit), list its events and delete it.
//
// Claims take any ready demo.echo job, so a VU may well finish another VU's job - which is the
// point: workers don't own jobs, leases do. Run it against an otherwise idle demo.echo queue.
export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 10,
  duration: __ENV.DURATION || '10s',
  thresholds: {
    checks: ['rate>0.99'],
    grpc_req_duration: ['p(95)<500'],  // 95% of calls below 500ms
  },
};

const WORKER = workerId('k6-lifecycle');

export default function () {
  // Create
  const createRes = rpc(`${JOBS}/CreateJob`, {
    name: `Lifecycle job ${randomIntBetween(1, 1000000)}`,
    type: 'demo.echo',
    spec: { message: 'hello from k6-job-lifecycle.js' },
    labels: { source: 'k6' },
  }, 'CreateJob');
  check(createRes, { 'create: status is OK': (r) => r.status === grpc.StatusOK });

  // Claim (a lease) - empty only if every ready job is being held by other VUs right now.
  const claimRes = rpc(`${WORKERS}/ClaimJob`, { workerId: WORKER, types: ['demo.echo'] }, 'ClaimJob');
  check(claimRes, { 'claim: status is OK': (r) => r.status === grpc.StatusOK });
  const job = claimRes.message && claimRes.message.job;
  if (!job) {
    sleep(randomIntBetween(1, 3) * 0.1);
    return;
  }
  check(job, {
    'claim: job is running': (j) => j.state === 'JOB_STATE_RUNNING',
    'claim: leased to this worker': (j) => j.leaseOwner === WORKER,
  });

  // Heartbeat: extends the lease, no state change (version unchanged)
  const beatRes = rpc(`${WORKERS}/Heartbeat`, { jobId: job.id, workerId: WORKER, leaseSeconds: 60 }, 'Heartbeat');
  check(beatRes, {
    'heartbeat: status is OK': (r) => r.status === grpc.StatusOK,
    'heartbeat: version unchanged': (r) => !!r.message && versionOf(r.message) === versionOf(job),
  });

  if (Math.random() < 0.2) {
    const failRes = rpc(`${WORKERS}/FailJob`, {
      jobId: job.id, workerId: WORKER, message: 'simulated transient failure', retryable: true,
    }, 'FailJob');
    check(failRes, {
      'fail: status is OK': (r) => r.status === grpc.StatusOK,
      'fail: re-queued or out of attempts': (r) => !!r.message && (r.message.state === 'JOB_STATE_QUEUED'
        || (r.message.state === 'JOB_STATE_FAILED' && attemptsOf(r.message) >= r.message.maxAttempts)),
    });
  } else {
    const doneRes = rpc(`${WORKERS}/CompleteJob`, { jobId: job.id, workerId: WORKER, result: { echoed: job.spec } }, 'CompleteJob');
    check(doneRes, { 'complete: succeeded': (r) => r.status === grpc.StatusOK && r.message.state === 'JOB_STATE_SUCCEEDED' });

    // Read twice: the first fills the cache (terminal job), the second is a hit.
    for (const tag of ['GetJobMiss', 'GetJobHit']) {
      const getRes = rpc(`${JOBS}/GetJob`, { id: job.id }, tag);
      check(getRes, { 'read: succeeded': (r) => r.status === grpc.StatusOK && r.message.state === 'JOB_STATE_SUCCEEDED' });
    }

    const eventsRes = rpc(`${JOBS}/ListJobEvents`, { jobId: job.id }, 'ListJobEvents');
    check(eventsRes, { 'events: created, claimed, completed': (r) => r.status === grpc.StatusOK && r.message.items.length >= 3 });

    const deleteRes = rpc(`${JOBS}/DeleteJob`, { id: job.id }, 'DeleteJob');
    check(deleteRes, { 'delete: status is OK': (r) => r.status === grpc.StatusOK });
  }

  // Simulate think time between 100ms and 300ms using k6-utils
  sleep(randomIntBetween(1, 3) * 0.1);
}

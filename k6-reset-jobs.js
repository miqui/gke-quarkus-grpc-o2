import { grpc, JOBS, rpc } from './k6-common.js';

// Deletes EVERY job (cancel, then delete) so a GC experiment run starts against the same table
// sizes as the one before it, without needing database access (Cloud SQL is private-IP only).
// Only for a dedicated experiment cluster: it removes jobs from all queues and types.
//   CONFIRM_RESET=yes k6 run k6-reset-jobs.js
//
// setup() lists every id (pages by offset, nothing deleted yet); 32 VUs then each delete a strided
// share in parallel (a write workload leaves tens of thousands of jobs; one VU deleting them one by
// one took ~90 ms per job). teardown() sweeps up whatever is left, one job at a time, and fails if
// anything can still not be deleted.
const SHARES = 64;
export const options = {
  scenarios: {
    reset: { executor: 'per-vu-iterations', vus: 32, iterations: 2, maxDuration: '1h' },
  },
};

function confirm() {
  if (__ENV.CONFIRM_RESET !== 'yes') {
    throw new Error('refusing to delete every job: set CONFIRM_RESET=yes');
  }
}

function remove(id) {
  rpc(`${JOBS}/CancelJob`, { id }, 'CancelJob'); // rejected for a finished job: fine
  return rpc(`${JOBS}/DeleteJob`, { id }, 'DeleteJob').status === grpc.StatusOK;
}

export function setup() {
  confirm();
  const ids = [];
  for (let offset = 0; ; offset += 200) {
    const res = rpc(`${JOBS}/ListJobs`, { limit: 200, offset }, 'ListJobs');
    if (res.status !== grpc.StatusOK) {
      throw new Error(`ListJobs failed: ${JSON.stringify(res.error)}`);
    }
    const items = (res.message && res.message.items) || [];
    for (const job of items) ids.push(job.id);
    if (items.length < 200) break;
  }
  return { ids };
}

// 32 VUs x 2 iterations = 64 shares; __VU is 1-based and __ITER 0-based.
export default function (data) {
  const share = (__VU - 1) * 2 + __ITER;
  for (let i = share; i < data.ids.length; i += SHARES) remove(data.ids[i]);
}

export function teardown(data) {
  let deleted = 0;
  for (;;) {
    const res = rpc(`${JOBS}/ListJobs`, { limit: 200 }, 'ListJobs');
    if (res.status !== grpc.StatusOK) {
      throw new Error(`ListJobs failed: ${JSON.stringify(res.error)}`);
    }
    const items = (res.message && res.message.items) || [];
    if (items.length === 0) break;
    let progress = 0;
    for (const job of items) {
      if (remove(job.id)) {
        deleted++;
        progress++;
      }
    }
    if (progress === 0) {
      throw new Error(`no job in a page of ${items.length} could be deleted; stopping`);
    }
  }
  console.log(`deleted ${data.ids.length} jobs (${deleted} of them in the final sweep)`);
}

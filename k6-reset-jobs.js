import { grpc, JOBS, rpc } from './k6-common.js';

// Deletes EVERY job (cancel, then delete) so a GC experiment run starts against the same table
// sizes as the one before it, without needing database access (Cloud SQL is private-IP only).
// Only for a dedicated experiment cluster: it removes jobs from all queues and types.
//   CONFIRM_RESET=yes k6 run k6-reset-jobs.js
export const options = {
  scenarios: {
    reset: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '1h' },
  },
};

export default function () {
  if (__ENV.CONFIRM_RESET !== 'yes') {
    throw new Error('refusing to delete every job: set CONFIRM_RESET=yes');
  }
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
      rpc(`${JOBS}/CancelJob`, { id: job.id }, 'CancelJob'); // rejected for a finished job: fine
      const del = rpc(`${JOBS}/DeleteJob`, { id: job.id }, 'DeleteJob');
      if (del.status === grpc.StatusOK) {
        deleted++;
        progress++;
      }
    }
    if (progress === 0) {
      throw new Error(`no job in a page of ${items.length} could be deleted; stopping`);
    }
  }
  console.log(`deleted ${deleted} jobs`);
}

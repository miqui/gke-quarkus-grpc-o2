import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { createJob, grpc, JOBS, rpc, loadOptions } from './k6-common.js';

// Reads: ListJobs (unfiltered, by state, by label - the GIN index), GetJob, ListJobEvents and
// ListJobTypes against a small set of jobs created in setup.
export const options = loadOptions({
  thresholds: {
    checks: ['rate>0.99'],
    grpc_req_duration: ['p(95)<500'],  // 95% of calls below 500ms
  },
});

// Created once in setup and handed to every VU: module-level code runs once per VU, so a
// Date.now()-based id there would differ between VUs.
export function setup() {
  const run = `k6-list-jobs-${Date.now()}`;
  const ids = [];
  for (let i = 0; i < 10; i++) {
    ids.push(createJob({ name: `list fixture ${i}`, type: 'demo.echo', spec: { i }, labels: { run } }).id);
  }
  return { run, ids };
}

export default function (data) {
  const res = rpc(`${JOBS}/ListJobs`, { limit: 50 }, 'ListJobs');
  check(res, {
    'status is OK': (r) => r.status === grpc.StatusOK,
    'has at least one item': (r) => !!(r.message && r.message.items && r.message.items.length > 0),
    // int64 -> a decimal string in proto3 JSON
    'has totalCount': (r) => !!r.message && !Number.isNaN(parseInt(r.message.totalCount, 10)),
  });

  const byLabel = rpc(`${JOBS}/ListJobs`, { labels: { run: data.run }, limit: 5 }, 'ListJobsByLabel');
  check(byLabel, {
    'by label: status is OK': (r) => r.status === grpc.StatusOK,
    'by label: a page of at most 5': (r) => !!r.message && (r.message.items || []).length <= 5,
    'by label: all 10 counted': (r) => !!r.message && parseInt(r.message.totalCount, 10) === 10,
  });

  const byState = rpc(`${JOBS}/ListJobs`, { states: ['JOB_STATE_QUEUED', 'JOB_STATE_RUNNING'], limit: 20 }, 'ListJobsByState');
  check(byState, {
    'by state: only queued or running': (r) => r.status === grpc.StatusOK
      && (r.message.items || []).every((j) => j.state === 'JOB_STATE_QUEUED' || j.state === 'JOB_STATE_RUNNING'),
  });

  const id = data.ids[randomIntBetween(0, data.ids.length - 1)];
  const get = rpc(`${JOBS}/GetJob`, { id }, 'GetJob');
  check(get, { 'get: id matches': (r) => r.status === grpc.StatusOK && r.message.id === id });

  const events = rpc(`${JOBS}/ListJobEvents`, { jobId: id }, 'ListJobEvents');
  check(events, { 'events: has the creation event': (r) => r.status === grpc.StatusOK && r.message.items.length >= 1 });

  const types = rpc(`${JOBS}/ListJobTypes`, {}, 'ListJobTypes');
  check(types, { 'types: registry is not empty': (r) => r.status === grpc.StatusOK && r.message.items.length > 0 });

  // Simulate think time between 100ms and 300ms using k6-utils
  sleep(randomIntBetween(1, 3) * 0.1);
}

// Clean up: cancel, then delete (only finished jobs can be deleted).
export function teardown(data) {
  for (const id of data.ids) {
    rpc(`${JOBS}/CancelJob`, { id });
    rpc(`${JOBS}/DeleteJob`, { id });
  }
}

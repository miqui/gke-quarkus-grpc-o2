// Shared by the k6-*.js scenarios (imported, not run on its own).
//
// Target: GRPC_ADDR (default grpc.miqui.dev:443, TLS). For a local server set PLAINTEXT=true,
// e.g. GRPC_ADDR=localhost:9090 PLAINTEXT=true k6 run k6-create-jobs.js
//
// The service descriptors come from server reflection, so no .proto files are needed here.
// Responses are proto3 JSON: camelCase fields, int64 as strings (totalCount), enums as names
// (JOB_STATE_QUEUED), and fields at their default value omitted (a job at version 0 has no
// `version` key, a job with no attempts no `attempts` key) - see versionOf() / attemptsOf().
import grpc from 'k6/net/grpc';

export const GRPC_ADDR = __ENV.GRPC_ADDR || 'grpc.miqui.dev:443';
const PLAINTEXT = __ENV.PLAINTEXT === 'true';

export const JOBS = 'job.v1.JobService';
export const WORKERS = 'job.v1.WorkerService';

// One client per VU, connected on first use: connect() is only allowed in VU code (setup,
// default, teardown), not in the init context.
const client = new grpc.Client();
let connected = false;

export function rpc(method, request, tag) {
  if (!connected) {
    client.connect(GRPC_ADDR, { plaintext: PLAINTEXT, reflect: true, timeout: '10s' });
    connected = true;
  }
  return client.invoke(method, request, tag ? { tags: { name: tag } } : {});
}

export const versionOf = (job) => job.version || 0;
export const attemptsOf = (job) => job.attempts || 0;

// The google.rpc.ErrorInfo reason (BAD_USER_INPUT, NOT_FOUND, CONFLICT, LEASE_NOT_HELD, ...) of a
// failed call.
export function reasonOf(response) {
  const details = (response.error && response.error.details) || [];
  const info = details.find((d) => d['@type'] === 'type.googleapis.com/google.rpc.ErrorInfo');
  return info ? info.reason : undefined;
}

// The google.rpc.BadRequest field violations of a failed call.
export function violationsOf(response) {
  const details = (response.error && response.error.details) || [];
  const bad = details.find((d) => d['@type'] === 'type.googleapis.com/google.rpc.BadRequest');
  return bad ? bad.fieldViolations : [];
}

// A worker id unique to this VU and run (1-100 chars of [A-Za-z0-9._:-]).
export function workerId(prefix) {
  return `${prefix}-${__VU}-${Date.now()}`;
}

export function createJob(request, tag) {
  const res = rpc(`${JOBS}/CreateJob`, request, tag);
  if (res.status !== grpc.StatusOK) {
    throw new Error(`failed to create job, status ${res.status}, error ${JSON.stringify(res.error)}`);
  }
  return res.message;
}

export { grpc };

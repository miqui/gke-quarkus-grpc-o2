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

// Load shape, shared by the scenarios that the GC experiments drive (see pareto-frontier.md).
//
// Default (RATE unset): a closed model, VUS virtual users for DURATION - what these scripts always did.
//
// RATE=<iterations/s> switches to an open model (constant-arrival-rate): a slow server cannot
// lower the offered load, so p99 and CPU per request stay comparable between variants. The run is
// a WARMUP period (default 2m, not judged) followed by the measured DURATION (default 10m); the
// two are separate scenarios, so every metric is tagged scenario:warmup or scenario:measure.
//   PRE_VUS / MAX_VUS  VU pool for the arrival rate (default 50 / 200); a pool that is too small
//                      drops iterations, which fails the dropped_iterations threshold below.
//   P99_LIMIT_MS       if set, the run fails when the measured p99 of gRPC calls is above it (the SLO).
// Summary percentiles for the measured window are exported as grpc_req_duration{scenario:measure}
// (run with --summary-trend-stats 'avg,med,p(90),p(95),p(99),p(99.9),max').
export function loadOptions({ thresholds = {}, vus = 10, duration = '10s' } = {}) {
  if (!__ENV.RATE) {
    return {
      vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : vus,
      duration: __ENV.DURATION || duration,
      thresholds,
    };
  }
  const rate = parseInt(__ENV.RATE, 10);
  const warmup = __ENV.WARMUP || '2m';
  const scenario = (startTime, dur) => ({
    executor: 'constant-arrival-rate',
    rate,
    timeUnit: '1s',
    duration: dur,
    preAllocatedVUs: __ENV.PRE_VUS ? parseInt(__ENV.PRE_VUS, 10) : 50,
    maxVUs: __ENV.MAX_VUS ? parseInt(__ENV.MAX_VUS, 10) : 200,
    startTime,
  });
  const measured = {
    // A threshold on a tagged submetric is what makes k6 track and export it.
    'grpc_req_duration{scenario:measure}': [__ENV.P99_LIMIT_MS ? `p(99)<${__ENV.P99_LIMIT_MS}` : 'max>=0'],
    'dropped_iterations{scenario:measure}': ['count<1'],
  };
  return {
    scenarios: {
      warmup: scenario('0s', warmup),
      measure: scenario(warmup, __ENV.DURATION || '10m'),
    },
    thresholds: { ...thresholds, ...measured },
  };
}

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

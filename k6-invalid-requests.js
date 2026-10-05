import { check, sleep } from 'k6';
import { randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { createJob, grpc, JOBS, reasonOf, rpc, violationsOf, workerId, WORKERS } from './k6-common.js';

export const options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 10,
  duration: __ENV.DURATION || '10s',
  thresholds: {
    // Every call here is meant to fail. `checks` passing means the API returned the *correct*
    // status code and google.rpc error details (ErrorInfo reason, BadRequest violations) every time.
    checks: ['rate>0.99'],
    grpc_req_duration: ['p(95)<500'],  // 95% of calls below 500ms
  },
};

// One cancelled job: the target of the state-conflict and lease checks below.
export function setup() {
  const job = createJob({ name: 'k6-invalid-requests fixture', type: 'demo.echo', spec: {} });
  rpc(`${JOBS}/CancelJob`, { id: job.id });
  return { cancelledId: job.id };
}

export default function (data) {
  // Invalid create: blank name, unregistered type, no spec, bad label key, priority out of range
  // -> INVALID_ARGUMENT, BAD_USER_INPUT, one field violation per field.
  const invalidRes = rpc(`${JOBS}/CreateJob`,
    { name: ' ', type: 'no.such.type', labels: { 'Bad Key': 'x' }, priority: 10 }, 'CreateInvalidJob');
  check(invalidRes, {
    'invalid create: INVALID_ARGUMENT': (r) => r.status === grpc.StatusInvalidArgument,
    'invalid create: BAD_USER_INPUT': (r) => reasonOf(r) === 'BAD_USER_INPUT',
    'invalid create: all five fields reported': (r) => violationsOf(r).length === 5,
  });

  // Well-formed but non-existent id -> NOT_FOUND.
  const notFoundRes = rpc(`${JOBS}/GetJob`, { id: uuidv4() }, 'GetMissingJob');
  check(notFoundRes, {
    'not found: NOT_FOUND status': (r) => r.status === grpc.StatusNotFound,
    'not found: NOT_FOUND reason': (r) => reasonOf(r) === 'NOT_FOUND',
  });

  // Malformed id: not a UUID, so validation fails before any lookup - INVALID_ARGUMENT, not NOT_FOUND.
  const badIdRes = rpc(`${JOBS}/GetJob`, { id: `non-existent-${randomIntBetween(1, 1000000)}` }, 'GetJobBadId');
  check(badIdRes, {
    'bad id: INVALID_ARGUMENT': (r) => r.status === grpc.StatusInvalidArgument,
    'bad id: names the id field': (r) => violationsOf(r).some((v) => v.field === 'id'),
  });

  // A finished job can't be cancelled again -> FAILED_PRECONDITION, CONFLICT.
  const cancelRes = rpc(`${JOBS}/CancelJob`, { id: data.cancelledId }, 'CancelFinishedJob');
  check(cancelRes, {
    'cancel finished: FAILED_PRECONDITION': (r) => r.status === grpc.StatusFailedPrecondition,
    'cancel finished: CONFLICT': (r) => reasonOf(r) === 'CONFLICT',
  });

  // A worker reporting on a job it doesn't hold -> FAILED_PRECONDITION, LEASE_NOT_HELD.
  const leaseRes = rpc(`${WORKERS}/CompleteJob`, { jobId: data.cancelledId, workerId: workerId('k6-invalid') }, 'CompleteUnheldJob');
  check(leaseRes, {
    'not held: FAILED_PRECONDITION': (r) => r.status === grpc.StatusFailedPrecondition,
    'not held: LEASE_NOT_HELD': (r) => reasonOf(r) === 'LEASE_NOT_HELD',
  });

  // Oversized request (> 64 KiB inbound limit): rejected by gRPC before the service runs.
  const bigRes = rpc(`${JOBS}/CreateJob`, { name: 'big', type: 'demo.echo', spec: { blob: 'x'.repeat(70000) } }, 'CreateOversizedJob');
  check(bigRes, {
    'oversized: RESOURCE_EXHAUSTED': (r) => r.status === grpc.StatusResourceExhausted,
  });

  // Simulate think time between 100ms and 300ms using k6-utils
  sleep(randomIntBetween(1, 3) * 0.1);
}

export function teardown(data) {
  rpc(`${JOBS}/DeleteJob`, { id: data.cancelledId });
}

#!/usr/bin/env bash
# Smoke-tests the job-manager-api gRPC API end to end. Exits non-zero if any check fails.
# Usage: ./test-api.sh                                   (the public GKE endpoint, TLS)
#        GRPC_ADDR=localhost:9090 PLAINTEXT=true ./test-api.sh   (java -jar / docker compose --profile app, or a port-forward)
# Needs grpcurl and jq. The service descriptors come from server reflection, so no .proto is needed.
set -euo pipefail

GRPC_ADDR="${GRPC_ADDR:-grpc.miqui.dev:443}"
PLAINTEXT="${PLAINTEXT:-false}"

for tool in grpcurl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Error: $tool is required."; exit 1; }
done

OPTS=(-emit-defaults -max-time 15)   # -emit-defaults: a job at version 0 still prints "version": 0
[ "${PLAINTEXT}" = "true" ] && OPTS+=(-plaintext)

echo "=========================================================="
echo " Testing job-manager-api (Quarkus gRPC, Cloud SQL + Hazelcast)"
echo " Target: ${GRPC_ADDR} (plaintext=${PLAINTEXT})"
echo "=========================================================="

BODY=""      # response message as JSON ("" on error)
CODE=""      # gRPC status name: OK, InvalidArgument, NotFound, Aborted, ...
ERR=""       # grpcurl's error text (status, message, ErrorInfo/BadRequest details)
FAILURES=0

# call METHOD [JSON_REQUEST] - e.g. call job.v1.JobService/GetJob '{"id":"..."}'
# Sets CODE, BODY and ERR, and prints the exchange.
call() {
  local method="$1" data="${2:-}" out
  [ -n "${data}" ] || data='{}'
  if out=$(grpcurl "${OPTS[@]}" -d "${data}" "${GRPC_ADDR}" "${method}" 2>"${TMPERR}"); then
    CODE=OK; BODY="${out}"; ERR=""
  else
    BODY=""; ERR=$(cat "${TMPERR}")
    CODE=$(printf '%s\n' "${ERR}" | sed -n 's/^  Code: //p' | head -1)
    CODE="${CODE:-Unavailable}"   # transport failure: no gRPC status was returned
  fi
  echo "${method} -> ${CODE}"
  if [ -n "${BODY}" ]; then echo "${BODY}" | jq . 2>/dev/null || echo "${BODY}"; fi
  if [ -n "${ERR}" ]; then printf '%s\n' "${ERR}"; fi
}
TMPERR=$(mktemp); trap 'rm -f "${TMPERR}"' EXIT

# reason: the google.rpc.ErrorInfo reason from the last error (BAD_USER_INPUT, CONFLICT, ...)
reason() { printf '%s\n' "${ERR}" | sed -n 's/.*"reason": *"\([A-Z_]*\)".*/\1/p' | head -1; }
# violations: how many google.rpc.BadRequest field violations the last error carried
violations() { printf '%s\n' "${ERR}" | grep -c '"field":' || true; }

# expect DESCRIPTION EXPECTED ACTUAL
expect() {
  if [ "$2" = "$3" ]; then
    echo "  ok: $1"
  else
    echo "  FAIL: $1 (expected '$2', got '$3')"
    FAILURES=$((FAILURES + 1))
  fi
}

JOBS=job.v1.JobService
WRK=job.v1.WorkerService
WORKER="test-api-$(date +%s)"

echo -e "\n1. Reflection and health:"
SERVICES=$(grpcurl "${OPTS[@]}" "${GRPC_ADDR}" list 2>&1 || true)
echo "${SERVICES}"
expect "JobService is listed" 1 "$(echo "${SERVICES}" | grep -c "^${JOBS}$" || true)"
expect "WorkerService is listed" 1 "$(echo "${SERVICES}" | grep -c "^${WRK}$" || true)"
call grpc.health.v1.Health/Check
expect "health is SERVING" SERVING "$(echo "${BODY}" | jq -r .status)"

echo -e "\n2. Job types:"
call ${JOBS}/ListJobTypes
expect "list types is OK" OK "${CODE}"
expect "demo.echo is registered" 1 "$(echo "${BODY}" | jq '[.items[].name] | index("demo.echo") != null' | grep -c true || true)"

echo -e "\n3. Create a job (with an idempotency key):"
KEY="test-api-$(date +%s)"
call ${JOBS}/CreateJob "{\"name\":\"smoke test\",\"type\":\"demo.echo\",\"spec\":{\"message\":\"hello\"},\"labels\":{\"source\":\"test-api\"},\"priority\":9,\"idempotency_key\":\"${KEY}\"}"
expect "create is OK" OK "${CODE}"
JOB_ID=$(echo "${BODY}" | jq -r .id)
expect "new job is QUEUED" JOB_STATE_QUEUED "$(echo "${BODY}" | jq -r .state)"
expect "new job is at version 0" 0 "$(echo "${BODY}" | jq -r .version)"

echo -e "\n3b. Same idempotency key again (expecting the same job back):"
call ${JOBS}/CreateJob "{\"name\":\"retry\",\"type\":\"demo.echo\",\"spec\":{},\"idempotency_key\":\"${KEY}\"}"
expect "retry is OK" OK "${CODE}"
expect "same job id" "${JOB_ID}" "$(echo "${BODY}" | jq -r .id)"

echo -e "\n4. Create an invalid job (expecting INVALID_ARGUMENT / BAD_USER_INPUT with field violations):"
call ${JOBS}/CreateJob '{"name":"","type":"no.such.type","priority":10}'
expect "invalid create is InvalidArgument" InvalidArgument "${CODE}"
expect "reason is BAD_USER_INPUT" BAD_USER_INPUT "$(reason)"
expect "name, type, spec and priority are reported" 4 "$(violations)"

echo -e "\n4b. Reject a NUL character inside the spec before reaching PostgreSQL:"
call ${JOBS}/CreateJob '{"name":"nul","type":"demo.echo","spec":{"k":"bad\u0000value"}}'
expect "NUL input is InvalidArgument" InvalidArgument "${CODE}"
expect "spec violation is reported" 1 "$(violations)"

echo -e "\n5. Claim it as a worker (priority 9 is claimed first):"
call ${WRK}/ClaimJob "{\"worker_id\":\"${WORKER}\",\"types\":[\"demo.echo\"],\"lease_seconds\":60}"
expect "claim is OK" OK "${CODE}"
expect "claimed our job" "${JOB_ID}" "$(echo "${BODY}" | jq -r .job.id)"
expect "job is RUNNING" JOB_STATE_RUNNING "$(echo "${BODY}" | jq -r .job.state)"
expect "leased to this worker" "${WORKER}" "$(echo "${BODY}" | jq -r .job.lease_owner)"

echo -e "\n6. Heartbeat (extends the lease, no version bump):"
call ${WRK}/Heartbeat "{\"job_id\":\"${JOB_ID}\",\"worker_id\":\"${WORKER}\",\"lease_seconds\":120}"
expect "heartbeat is OK" OK "${CODE}"
expect "still version 1" 1 "$(echo "${BODY}" | jq -r .version)"

echo -e "\n6b. Heartbeat as another worker (expecting FAILED_PRECONDITION / LEASE_NOT_HELD):"
call ${WRK}/Heartbeat "{\"job_id\":\"${JOB_ID}\",\"worker_id\":\"someone-else\"}"
expect "foreign heartbeat is FailedPrecondition" FailedPrecondition "${CODE}"
expect "reason is LEASE_NOT_HELD" LEASE_NOT_HELD "$(reason)"

echo -e "\n7. Complete it with a result:"
call ${WRK}/CompleteJob "{\"job_id\":\"${JOB_ID}\",\"worker_id\":\"${WORKER}\",\"result\":{\"echoed\":\"hello\"}}"
expect "complete is OK" OK "${CODE}"
expect "job SUCCEEDED" JOB_STATE_SUCCEEDED "$(echo "${BODY}" | jq -r .state)"

echo -e "\n8. Read it back (a finished job is cached: miss, then hit):"
call ${JOBS}/GetJob "{\"id\":\"${JOB_ID}\"}"
expect "get is OK" OK "${CODE}"
expect "result is stored" hello "$(echo "${BODY}" | jq -r .result.echoed)"
call ${JOBS}/GetJob "{\"id\":\"${JOB_ID}\"}"
expect "second get is OK" OK "${CODE}"

echo -e "\n9. Its audit trail:"
call ${JOBS}/ListJobEvents "{\"job_id\":\"${JOB_ID}\"}"
expect "events are OK" OK "${CODE}"
expect "created -> claimed -> completed" "JOB_STATE_QUEUED JOB_STATE_RUNNING JOB_STATE_SUCCEEDED" "$(echo "${BODY}" | jq -r '[.items[].to_state] | join(" ")')"

echo -e "\n10. Cancel a finished job (expecting FAILED_PRECONDITION / CONFLICT):"
call ${JOBS}/CancelJob "{\"id\":\"${JOB_ID}\"}"
expect "cancel finished is FailedPrecondition" FailedPrecondition "${CODE}"
expect "reason is CONFLICT" CONFLICT "$(reason)"

echo -e "\n11. Cancel a queued job, with a stale version first (expecting ABORTED):"
call ${JOBS}/CreateJob '{"name":"to cancel","type":"demo.sleep","spec":{"seconds":1}}'
CANCEL_ID=$(echo "${BODY}" | jq -r .id)
call ${JOBS}/CancelJob "{\"id\":\"${CANCEL_ID}\",\"version\":5}"
expect "stale cancel is Aborted" Aborted "${CODE}"
call ${JOBS}/CancelJob "{\"id\":\"${CANCEL_ID}\",\"version\":0,\"reason\":\"smoke test\"}"
expect "cancel is OK" OK "${CODE}"
expect "job CANCELLED" JOB_STATE_CANCELLED "$(echo "${BODY}" | jq -r .state)"

echo -e "\n12. List jobs by label:"
call ${JOBS}/ListJobs '{"labels":{"source":"test-api"},"limit":50}'
expect "list is OK" OK "${CODE}"
expect "list has a numeric total_count" 1 "$(echo "${BODY}" | jq -r '(.total_count | tonumber) >= 1' | grep -c true || true)"

echo -e "\n13. Unknown id and malformed id:"
call ${JOBS}/GetJob '{"id":"00000000-0000-0000-0000-000000000000"}'
expect "unknown id is NotFound" NotFound "${CODE}"
expect "reason is NOT_FOUND" NOT_FOUND "$(reason)"
call ${JOBS}/GetJob '{"id":"not-a-uuid"}'
expect "malformed id is InvalidArgument" InvalidArgument "${CODE}"

echo -e "\n14. Clean up: delete both (finished) jobs:"
call ${JOBS}/DeleteJob "{\"id\":\"${JOB_ID}\"}"
expect "delete is OK" OK "${CODE}"
call ${JOBS}/DeleteJob "{\"id\":\"${CANCEL_ID}\"}"
expect "delete cancelled is OK" OK "${CODE}"

echo -e "\n=========================================================="
if [ "${FAILURES}" -eq 0 ]; then
  echo " API verification passed."
  echo "=========================================================="
else
  echo " API verification FAILED: ${FAILURES} check(s)."
  echo "=========================================================="
  exit 1
fi

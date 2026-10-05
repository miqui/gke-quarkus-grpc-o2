# API Examples

Example [`grpcurl`](https://github.com/fullstorydev/grpcurl) calls against the job-manager-api gRPC
API, ordered simple to complex. All of them target the public endpoint `grpc.miqui.dev:443` (TLS); for
a local run (`java -jar target/quarkus-app/quarkus-run.jar`, `docker compose --profile app up -d`, or
`./mvnw quarkus:dev`) use `localhost:9090` with `-plaintext`. The schema comes from server
reflection, so no `.proto` file is needed. See [API-DESIGN.md](API-DESIGN.md) for the job model, the
status codes, validation rules and error model. `./test-api.sh` runs most of these as an automated
smoke test.

Replace placeholder IDs (`<JOB_ID>`) with real ones from your own data - example 3 creates one.
Every example uses `jq` where it helps; drop `| jq` if you don't have it.

```bash
ADDR=grpc.miqui.dev:443          # local: ADDR=localhost:9090 and add -plaintext to every grpcurl
J=job.v1.JobService
W=job.v1.WorkerService
```

`grpcurl` prints the proto field names (`created_at`, `total_count`) and omits fields at their default
value (a job at `version` 0 has no `version` key, a queued job no `lease_owner`) unless you add
`-emit-defaults`. `int64` values such as `total_count` are strings in JSON; states are enum names
(`JOB_STATE_QUEUED`).

---

### 1. Discover the API

```bash
grpcurl $ADDR list                     # services
grpcurl $ADDR list $J                  # methods of one service
grpcurl $ADDR describe $J.CreateJob    # a method and its request/response types
grpcurl $ADDR describe job.v1.Job
```

```
grpc.health.v1.Health
grpc.reflection.v1.ServerReflection
job.v1.JobService
job.v1.WorkerService
```

### 2. Health and the job types the API accepts

```bash
grpcurl $ADDR grpc.health.v1.Health/Check
grpcurl $ADDR $J/ListJobTypes | jq -c '.items[] | {name, default_max_attempts, default_lease_seconds}'
```

```json
{ "status": "SERVING" }
```

```
{"name":"demo.echo","default_max_attempts":3,"default_lease_seconds":30}
{"name":"demo.sleep","default_max_attempts":3,"default_lease_seconds":30}
{"name":"email.send","default_max_attempts":5,"default_lease_seconds":60}
{"name":"report.generate","default_max_attempts":5,"default_lease_seconds":300}
```

New types are registered with SQL ([DB.md](DB.md#register-a-job-type)).

### 3. Submit a job

`spec` is any JSON object - the job's input, for the worker to interpret. `labels` are short
string pairs to find it again; `priority` 0-9 (higher is claimed first).

```bash
grpcurl -d '{
  "name": "Monthly finance report",
  "type": "report.generate",
  "spec": {"template": "monthly", "parameters": {"month": "2026-09"}, "format": "pdf"},
  "labels": {"team": "finance"},
  "priority": 5
}' $ADDR $J/CreateJob
```

```json
{
  "id": "e1e156b2-e5f3-4fb4-98a1-a5a95b2d662f",
  "name": "Monthly finance report",
  "type": "report.generate",
  "state": "JOB_STATE_QUEUED",
  "priority": 5,
  "spec": { "format": "pdf", "parameters": { "month": "2026-09" }, "template": "monthly" },
  "labels": { "team": "finance" },
  "max_attempts": 5,
  "run_after": "2026-10-05T02:48:08.532468Z",
  "created_at": "2026-10-05T02:48:08.532468Z",
  "updated_at": "2026-10-05T02:48:08.532468Z"
}
```

`max_attempts` came from the type (`report.generate`: 5). JSONB doesn't keep key order, so `spec`
comes back with its keys sorted.

### 4. Submit safely when retrying, or for later

```bash
# The same idempotency_key twice returns the job the first call created - nothing is duplicated.
grpcurl -d '{"name":"Welcome email","type":"email.send","spec":{"to":"ana@example.com","template":"welcome"},
             "idempotency_key":"signup-4711"}' $ADDR $J/CreateJob | jq -r .id
grpcurl -d '{"name":"Welcome email","type":"email.send","spec":{"to":"ana@example.com","template":"welcome"},
             "idempotency_key":"signup-4711"}' $ADDR $J/CreateJob | jq -r .id   # same id

# Not claimable before run_after (at most 30 days ahead).
grpcurl -d '{"name":"Nightly report","type":"report.generate","spec":{"template":"nightly"},
             "run_after":"2026-10-06T02:00:00Z","max_attempts":2}' $ADDR $J/CreateJob
```

### 5. Get a job, and its history

```bash
grpcurl -d '{"id":"<JOB_ID>"}' $ADDR $J/GetJob
grpcurl -d '{"job_id":"<JOB_ID>"}' $ADDR $J/ListJobEvents
```

```json
{
  "items": [
    { "id": "3361", "job_id": "e1e156b2-...", "to_state": "JOB_STATE_QUEUED", "actor": "api",
      "detail": {}, "at": "2026-10-05T02:48:08.532468Z" },
    { "id": "3362", "job_id": "e1e156b2-...", "from_state": "JOB_STATE_QUEUED", "to_state": "JOB_STATE_RUNNING",
      "actor": "worker:report-worker-1", "detail": { "attempt": 1 }, "at": "2026-10-05T02:48:09.101Z" },
    { "id": "3363", "job_id": "e1e156b2-...", "from_state": "JOB_STATE_RUNNING", "to_state": "JOB_STATE_SUCCEEDED",
      "actor": "worker:report-worker-1", "detail": {}, "at": "2026-10-05T02:48:11.420Z" }
  ],
  "total_count": "3"
}
```

A finished job is served from the Hazelcast cache after the first `GetJob`; a queued or running one
always comes from Postgres.

### 6. List and filter jobs

Newest first. Filters combine: any of `states`, exactly `type`, and *all* of `labels`.

```bash
grpcurl -d '{"limit":20}' $ADDR $J/ListJobs | jq '{total_count, ids: [.items[].id]}'
grpcurl -d '{"states":["JOB_STATE_QUEUED","JOB_STATE_RUNNING"]}' $ADDR $J/ListJobs | jq .total_count
grpcurl -d '{"type":"report.generate","labels":{"team":"finance"}}' $ADDR $J/ListJobs \
  | jq -c '.items[] | {id, name, state}'
grpcurl -d '{"states":["JOB_STATE_FAILED"],"limit":5}' $ADDR $J/ListJobs | jq -c '.items[] | {id, error}'
```

Page with `limit` (1-200, default 50) and `offset`; `total_count` is the number of matching jobs.

### 7. Work on a job as a worker: claim, heartbeat, complete

```bash
WORKER=report-worker-1

# Lease the next ready job of these types (highest priority, then oldest). Empty = nothing ready.
grpcurl -d "{\"worker_id\":\"$WORKER\",\"types\":[\"report.generate\"],\"lease_seconds\":300}" \
  $ADDR $W/ClaimJob | tee /tmp/claim.json | jq -c '.job | {id, state, attempts, lease_owner, lease_expires_at}'
JOB_ID=$(jq -r .job.id /tmp/claim.json)

# Keep the lease alive while working (counted from now; no state change).
grpcurl -d "{\"job_id\":\"$JOB_ID\",\"worker_id\":\"$WORKER\",\"lease_seconds\":300}" $ADDR $W/Heartbeat \
  | jq -r .lease_expires_at

# Done: store the result.
grpcurl -d "{\"job_id\":\"$JOB_ID\",\"worker_id\":\"$WORKER\",\"result\":{\"url\":\"gs://reports/2026-09.pdf\",\"pages\":12}}" \
  $ADDR $W/CompleteJob | jq -c '{state, result, finished_at}'
```

```
{"id":"e1e156b2-...","state":"JOB_STATE_RUNNING","attempts":1,"lease_owner":"report-worker-1","lease_expires_at":"2026-10-05T02:53:09Z"}
2026-10-05T02:53:10.218Z
{"state":"JOB_STATE_SUCCEEDED","result":{"pages":12,"url":"gs://reports/2026-09.pdf"},"finished_at":"2026-10-05T02:48:11.420Z"}
```

A worker that stops heartbeating loses the job when the lease expires: the reaper re-queues it (or
fails it, with no attempts left) within ~5 seconds, and the worker's next call is refused (example 10).

### 8. Report a failure

```bash
# Transient: re-queued if attempts remain, claimable again after a backoff (5s, 10s, 20s, ... max 5 min).
grpcurl -d "{\"job_id\":\"$JOB_ID\",\"worker_id\":\"$WORKER\",\"message\":\"SMTP timeout\",
             \"details\":{\"host\":\"smtp.example.com\"},\"retryable\":true}" $ADDR $W/FailJob \
  | jq -c '{state, attempts, run_after, error}'

# Permanent (or out of attempts): FAILED.
grpcurl -d "{\"job_id\":\"$JOB_ID\",\"worker_id\":\"$WORKER\",\"message\":\"template not found\"}" $ADDR $W/FailJob
```

```
{"state":"JOB_STATE_QUEUED","attempts":1,"run_after":"2026-10-05T02:50:16Z","error":{"message":"SMTP timeout","details":{"host":"smtp.example.com"},"retryable":true}}
```

### 9. Cancel and delete

```bash
# Cancel a queued or running job; with "version" only if it hasn't changed since you read it.
grpcurl -d '{"id":"<JOB_ID>","version":0,"reason":"no longer needed"}' $ADDR $J/CancelJob | jq -c '{state, version}'

# Only finished jobs (SUCCEEDED, FAILED, CANCELLED) can be deleted; their events go with them.
grpcurl -d '{"id":"<JOB_ID>"}' $ADDR $J/DeleteJob
```

### 10. Error responses

Every error carries a `google.rpc.ErrorInfo` (`reason` is the stable code to switch on) and, for bad
input, a `google.rpc.BadRequest` naming each invalid field:

```bash
grpcurl -d '{"name":"","type":"nope","spec":{}}' $ADDR $J/CreateJob
```

```
ERROR:
  Code: InvalidArgument
  Message: The request content was invalid or failed validation constraints.
  Details:
  1)	{ "@type": "type.googleapis.com/google.rpc.ErrorInfo", "domain": "job-manager-api.miqui.dev", "reason": "BAD_USER_INPUT" }
  2)	{ "@type": "type.googleapis.com/google.rpc.BadRequest", "field_violations": [
    	    { "field": "name", "description": "name is required and cannot be blank" },
    	    { "field": "type", "description": "type 'nope' is not a registered job type" } ] }
```

```bash
# NOT_FOUND - a well-formed id that doesn't exist
grpcurl -d '{"id":"00000000-0000-0000-0000-000000000000"}' $ADDR $J/GetJob

# INVALID_ARGUMENT / BAD_USER_INPUT - a malformed id (the violation names "id")
grpcurl -d '{"id":"not-a-uuid"}' $ADDR $J/GetJob

# INVALID_ARGUMENT / BAD_USER_INPUT - PostgreSQL can't store NUL, not even inside JSONB
grpcurl -d '{"name":"x","type":"demo.echo","spec":{"k":"bad\u0000value"}}' $ADDR $J/CreateJob

# FAILED_PRECONDITION / CONFLICT - cancelling a finished job, or deleting an unfinished one
grpcurl -d '{"id":"<FINISHED_JOB_ID>"}' $ADDR $J/CancelJob

# ABORTED / CONFLICT - cancelling with a version the job has moved past: refetch, then decide
grpcurl -d '{"id":"<JOB_ID>","version":0}' $ADDR $J/CancelJob

# FAILED_PRECONDITION / LEASE_NOT_HELD - a worker reporting on a job it doesn't hold (cancelled,
# re-queued after its lease expired, or held by someone else): stop working on it
grpcurl -d '{"job_id":"<JOB_ID>","worker_id":"someone-else"}' $ADDR $W/Heartbeat
```

```
ERROR:
  Code: FailedPrecondition
  Message: Worker 'someone-else' does not hold job 'e1e156b2-...' (the job is leased to another worker); stop working on it.
  Details:
  1)	{ "@type": "type.googleapis.com/google.rpc.ErrorInfo", "domain": "job-manager-api.miqui.dev", "reason": "LEASE_NOT_HELD" }
```

### 11. A minimal worker in one script

Claims `demo.echo` jobs until the queue is empty, echoing each job's `spec` back as its result.

```bash
#!/usr/bin/env bash
set -euo pipefail
ADDR=${ADDR:-grpc.miqui.dev:443}; W=job.v1.WorkerService; WORKER="echo-worker-$$"

while true; do
  JOB=$(grpcurl -d "{\"worker_id\":\"$WORKER\",\"types\":[\"demo.echo\"]}" "$ADDR" $W/ClaimJob | jq -c '.job // empty')
  [ -n "$JOB" ] || { echo "queue empty"; break; }
  ID=$(echo "$JOB" | jq -r .id)
  SPEC=$(echo "$JOB" | jq -c '.spec // {}')
  # ... real work goes here; call Heartbeat if it can outlast the lease (30s for demo.echo) ...
  if grpcurl -d "{\"job_id\":\"$ID\",\"worker_id\":\"$WORKER\",\"result\":{\"echo\":$SPEC}}" "$ADDR" $W/CompleteJob >/dev/null; then
    echo "done $ID"
  else
    echo "lost $ID (cancelled or lease expired)"   # LEASE_NOT_HELD: drop it, move on
  fi
done
```

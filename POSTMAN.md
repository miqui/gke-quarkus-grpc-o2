# Testing the API with Postman

Postman's gRPC requests can't be imported from a file: collection exports leave gRPC requests out, so
there is no collection JSON to load. This page is the recipe for building the collection by hand (about
10 minutes). Every message below was sent to `grpc.miqui.dev:443` with `grpcurl` on 2026-10-05 and
returned what the page says. The scripts follow Postman's gRPC sandbox API (`pm.response.statusCode`,
`pm.response.messages.idx(0).data`), but they were not run inside Postman itself.

For the same flow from a shell, see `test-api.sh` (grpcurl) and EXAMPLES.md.

## 1. Collection and variables

1. **New -> Collection**, name it `job-manager-api`.
2. On the collection, **Variables** tab:

   | Variable | Initial value |
   | :--- | :--- |
   | `host` | `grpc.miqui.dev:443` |
   | `workerId` | `postman-worker-1` |
   | `jobId` | (empty - set by CreateJob) |
   | `version` | `0` (set by CreateJob / GetJob) |
   | `cancelJobId` | (empty - set by "CreateJob (to cancel)") |

## 2. Adding a request (same steps for every request below)

1. In the collection: **Add request -> gRPC**.
2. URL: `{{host}}`, and turn **TLS on** (the lock icon left of the URL). The API is TLS-only on 443.
3. **Service definition -> Using server reflection**. Postman fetches both services
   (`job.v1.JobService`, `job.v1.WorkerService`) from the server; no `.proto` file needed.
   To work offline, import `src/main/proto/job/v1/job_service.proto` instead; it imports
   `google/rpc/error_details.proto`, so reflection is simpler.
4. Pick the method, paste the **Message**, paste the **Scripts -> After response** script if there
   is one, and **Save** into the collection with the request's name.

Field names can be snake_case (`max_attempts`) as below, or camelCase (`maxAttempts`); the server
accepts both. Status codes in scripts are numeric: `0` OK, `3` INVALID_ARGUMENT, `5` NOT_FOUND,
`9` FAILED_PRECONDITION, `10` ABORTED.

## 3. Happy path: create -> claim -> complete

Send these in order.

### 01 ListJobTypes - `job.v1.JobService/ListJobTypes`

```json
{}
```

Expect four types: `demo.echo`, `demo.sleep`, `report.generate`, `email.send`.

```js
pm.test("OK", () => pm.expect(pm.response.statusCode).to.equal(0));
pm.test("4 seeded types", () => pm.expect(pm.response.messages.idx(0).data.items.length).to.be.at.least(4));
```

### 02 CreateJob - `job.v1.JobService/CreateJob`

```json
{
  "name": "postman demo",
  "type": "demo.echo",
  "spec": { "message": "hello from postman" },
  "labels": { "source": "postman" },
  "priority": 9,
  "max_attempts": 3,
  "idempotency_key": "postman-{{$timestamp}}"
}
```

`priority: 9` (the maximum) puts this job first in line, so step 05 claims this job and not one
left over from a k6 run. Sending the same `idempotency_key` again returns the same job instead of a
second one; `{{$timestamp}}` makes each send a new job.

```js
const job = pm.response.messages.idx(0).data;
pm.test("OK", () => pm.expect(pm.response.statusCode).to.equal(0));
pm.test("QUEUED", () => pm.expect(job.state).to.equal("JOB_STATE_QUEUED"));
pm.collectionVariables.set("jobId", job.id);
pm.collectionVariables.set("version", job.version || 0);   // proto3 omits 0
```

### 03 GetJob - `job.v1.JobService/GetJob`

```json
{ "id": "{{jobId}}" }
```

```js
const job = pm.response.messages.idx(0).data;
pm.test("OK", () => pm.expect(pm.response.statusCode).to.equal(0));
pm.collectionVariables.set("version", job.version || 0);
```

### 04 ListJobs - `job.v1.JobService/ListJobs`

```json
{
  "limit": 10,
  "states": ["JOB_STATE_QUEUED"],
  "labels": { "source": "postman" }
}
```

Filters combine with AND; `labels` matches jobs that have *all* the given labels. `totalCount` is
the number of matches, not the page size.

### 05 ClaimJob - `job.v1.WorkerService/ClaimJob`

You're now playing the worker.

```json
{
  "worker_id": "{{workerId}}",
  "types": ["demo.echo"],
  "lease_seconds": 60
}
```

Returns the job, now `JOB_STATE_RUNNING` and leased to `{{workerId}}` for 60s. An empty response
(no `job`) means nothing was claimable.

```js
const res = pm.response.messages.idx(0).data;
pm.test("OK", () => pm.expect(pm.response.statusCode).to.equal(0));
pm.test("claimed our job", () => pm.expect(res.job && res.job.id).to.equal(pm.collectionVariables.get("jobId")));
```

### 06 Heartbeat - `job.v1.WorkerService/Heartbeat`

```json
{ "job_id": "{{jobId}}", "worker_id": "{{workerId}}", "lease_seconds": 60 }
```

Extends the lease. Leave a claimed job alone for more than `lease_seconds` and the lease reaper
puts it back in the queue (state QUEUED, `attempts` +1). After that, 06 and 07 fail with
`FAILED_PRECONDITION` / `LEASE_NOT_HELD`.

### 07 CompleteJob - `job.v1.WorkerService/CompleteJob`

```json
{
  "job_id": "{{jobId}}",
  "worker_id": "{{workerId}}",
  "result": { "echo": "hello from postman" }
}
```

```js
const job = pm.response.messages.idx(0).data;
pm.test("OK", () => pm.expect(pm.response.statusCode).to.equal(0));
pm.test("SUCCEEDED", () => pm.expect(job.state).to.equal("JOB_STATE_SUCCEEDED"));
```

### 08 ListJobEvents - `job.v1.JobService/ListJobEvents`

```json
{ "job_id": "{{jobId}}", "limit": 20 }
```

The audit trail: created -> RUNNING (claimed by `{{workerId}}`) -> SUCCEEDED.

### 09 GetJob again

Re-send **03 GetJob**. The job is finished now, so this read (and later ones) is served from the
Hazelcast cache. The response is the same either way; the difference shows in the trace (no
SQL spans), not in Postman.

### 10 DeleteJob - `job.v1.JobService/DeleteJob`

```json
{ "id": "{{jobId}}" }
```

Returns `{}`. Only finished jobs (SUCCEEDED / FAILED / CANCELLED) can be deleted.

## 4. Cancel, retry and errors

### 11 CreateJob (to cancel) - `job.v1.JobService/CreateJob`

```json
{ "name": "postman cancel demo", "type": "demo.sleep", "spec": { "seconds": 5 }, "labels": { "source": "postman" } }
```

```js
pm.collectionVariables.set("cancelJobId", pm.response.messages.idx(0).data.id);
```

### 12 CancelJob - `job.v1.JobService/CancelJob`

```json
{ "id": "{{cancelJobId}}", "version": 0, "reason": "no longer needed" }
```

`version` is optional optimistic locking: if the job changed since you read it, you get
`ABORTED` (10) instead of overwriting. Send it a second time to see `FAILED_PRECONDITION` (9),
reason `CONFLICT`: the job is already CANCELLED, a terminal state. Then delete it with **10 DeleteJob** using
`{{cancelJobId}}`.

### 13 FailJob (retryable) - `job.v1.WorkerService/FailJob`

Run **02 CreateJob** and **05 ClaimJob** again first, then:

```json
{
  "job_id": "{{jobId}}",
  "worker_id": "{{workerId}}",
  "message": "simulated failure",
  "details": { "step": "render" },
  "retryable": true
}
```

The job goes back to `JOB_STATE_QUEUED` with `attempts: 1` and a `run_after` a few seconds out
(backoff 5s, 10s, 20s, ... up to 300s). With `"retryable": false`, or once `max_attempts` is used
up, it ends in `JOB_STATE_FAILED`.

### Error examples

Each error carries a `google.rpc.ErrorInfo` (`reason`) and, for bad input, a
`google.rpc.BadRequest` listing every invalid field. Postman shows both under the response's
metadata/trailers.

| Request | Message | Expect |
| :--- | :--- | :--- |
| CreateJob | `{ "name": "", "type": "no.such.type", "spec": {} }` | `INVALID_ARGUMENT` (3), reason `BAD_USER_INPUT`, two field violations (`name`, `type`) |
| CreateJob | `{ "name": "x", "type": "demo.echo", "spec": {}, "priority": 42 }` | `INVALID_ARGUMENT`, `priority` must be 0-9 |
| GetJob | `{ "id": "not-a-uuid" }` | `INVALID_ARGUMENT`, `id must be a valid UUID` |
| GetJob | `{ "id": "00000000-0000-0000-0000-000000000000" }` | `NOT_FOUND` (5), reason `NOT_FOUND` |
| CompleteJob | `{ "job_id": "{{jobId}}", "worker_id": "someone-else" }` on a job you hold | `FAILED_PRECONDITION` (9), reason `LEASE_NOT_HELD` |
| DeleteJob | `{ "id": "{{jobId}}" }` on a QUEUED job | `FAILED_PRECONDITION` (9), reason `CONFLICT`: only finished jobs can be deleted |

## Notes

- **Shared queue.** Workers claim the highest-priority due job of the requested types. With other
  jobs queued (k6 leftovers, other users), a claim without `priority: 9` on your job can return
  someone else's. Step 05's test catches that.
- **Limits** (all enforced server-side, listed in API-DESIGN.md): `run_after` at most 30 days
  ahead, `spec`/`result` up to 32 KiB, 16 labels, `lease_seconds` 5-3600, `limit` 1-200.
- **Local server:** URL `localhost:9090` with TLS **off**.

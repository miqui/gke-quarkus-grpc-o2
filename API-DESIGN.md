# gRPC API Design

The design of the `job-manager-api` gRPC API: the job model, the conventions every RPC follows, and
the reasoning behind the parts that aren't obvious (leases, the state machine, optimistic locking,
the error model, the cache, and how the cost of a single request is bounded). The contract is
[`src/main/proto/job/v1/job_service.proto`](src/main/proto/job/v1/job_service.proto) and is the
source of truth for exact shapes; the server serves it through reflection, so
`grpcurl grpc.miqui.dev:443 describe job.v1.JobService` always shows the running version.
Copy-paste calls live in [EXAMPLES.md](EXAMPLES.md).

## The job model

A **job** is a unit of work described as JSON, submitted by a client and executed by an external
**worker**. The API manages jobs; it never runs them. Three shapes were considered:

| Option | Shape | Verdict |
| --- | --- | --- |
| **A. Generic envelope** | Typed metadata columns (name, type, state, priority, attempts, run_after, lease, version) + the payload as JSONB (`spec`, `result`, `error`, `labels`), an append-only `job_events` audit table and a `job_types` registry | **Chosen.** Any JSON workload fits; every column the API filters, sorts or guards on is typed and indexed |
| B. Kubernetes-Job style | `apiVersion/kind/metadata/spec/status`, spec = image, command, parallelism, backoffLimit; status written by an external controller | Ties the model to container execution; the API would only record what a controller does |
| C. Workflow / DAG | A job holds `steps[]` with `dependsOn`, one row per step | Most capable, roughly twice the API surface and state machine; can be layered on A later (a step is a job, `dependsOn` a column) |

### Lifecycle

```
CreateJob -> QUEUED --ClaimJob--> RUNNING --CompleteJob-------------> SUCCEEDED
               ^                     |  \---FailJob (final, or no attempts left)--> FAILED
               |                     |
               +---FailJob(retryable, attempts left; after a backoff)
               +---lease expired (attempts left; otherwise FAILED)
CancelJob: QUEUED or RUNNING -> CANCELLED.        SUCCEEDED, FAILED, CANCELLED are terminal.
```

Every state change writes a `job_events` row in the same transaction (`actor` = `api`,
`worker:<id>` or `system:lease-reaper`) and bumps `version`.

### Leases

`ClaimJob` hands a worker the next ready job of the types it asks for (highest `priority`, then
oldest `run_after`) together with a **lease**: `lease_owner` = the worker id, `lease_expires_at` =
now + `lease_seconds` (default: the type's `default_lease_seconds`). The claim is one statement:

```sql
WITH next AS (SELECT id FROM jobs WHERE state = 'QUEUED' AND run_after <= now() AND type = ANY(?)
              ORDER BY priority DESC, run_after, id LIMIT 1 FOR UPDATE SKIP LOCKED)
UPDATE jobs ... SET state = 'RUNNING', attempts = attempts + 1, lease_owner = ?, ... RETURNING ...
```

`SKIP LOCKED` makes concurrent claims take *different* rows instead of queueing behind one another,
so two workers never get the same job (`k6-claim-contention.js` and
`WorkerServiceTest.concurrentClaimsNeverGetTheSameJob` prove it).

While working, the worker calls `Heartbeat` to push the lease forward. If it stops (crash, network
partition, a job longer than its lease), the **lease reaper** (`service/LeaseReaper.java`, every
5s on every replica, `SKIP LOCKED` again) takes the job back: `QUEUED` if attempts remain,
otherwise `FAILED`, with `error = {"message": "lease expired", ...}`.

Every worker call after the claim (`Heartbeat`, `CompleteJob`, `FailJob`) locks the row and checks
that the job is still `RUNNING` **and leased to this worker**. If not - cancelled, re-queued after
the lease expired, or since claimed by someone else - it fails with `FAILED_PRECONDITION` /
`LEASE_NOT_HELD`, which tells the worker to drop the job. A worker that lost its lease can never
overwrite the outcome of the worker that holds it now.

### Retries

`FailJob(retryable: true)` re-queues the job if `attempts < max_attempts`, claimable again after an
exponential backoff: 5s, 10s, 20s, ... capped at 5 minutes. Otherwise (not retryable, or out of
attempts) the job is `FAILED`. The last failure stays in `error` even if a later attempt succeeds.

## Resources and RPCs

| RPC | Purpose | Success | Errors (gRPC status) |
| --- | --- | --- | --- |
| `JobService/CreateJob` | Submit `{name, type, spec, labels?, priority?, max_attempts?, run_after?, idempotency_key?}` | `Job` (`QUEUED`, `version` 0) | `INVALID_ARGUMENT` (incl. unregistered type) |
| `JobService/GetJob` | One job (cache-aside for finished jobs) | `Job` | `INVALID_ARGUMENT`, `NOT_FOUND` |
| `JobService/ListJobs` | Page of jobs, newest first, filtered by `states`, `type`, `labels` | `{items, total_count}` | `INVALID_ARGUMENT` |
| `JobService/CancelJob` | `QUEUED`/`RUNNING` -> `CANCELLED`, optional `version` guard and `reason` | `Job` | `INVALID_ARGUMENT`, `NOT_FOUND`, **`FAILED_PRECONDITION`** already finished, **`ABORTED`** stale version |
| `JobService/DeleteJob` | Delete a finished job and its events | `Empty` | `INVALID_ARGUMENT`, `NOT_FOUND`, **`FAILED_PRECONDITION`** not finished |
| `JobService/ListJobEvents` | A job's state changes, oldest first | `{items, total_count}` | `INVALID_ARGUMENT`, `NOT_FOUND` |
| `JobService/ListJobTypes` | The type registry | `{items}` | - |
| `WorkerService/ClaimJob` | Lease the next ready job of `types` | `{job?}` (absent = nothing ready) | `INVALID_ARGUMENT` |
| `WorkerService/Heartbeat` | Extend the lease (no state change, no version bump) | `Job` | `INVALID_ARGUMENT`, `NOT_FOUND`, **`FAILED_PRECONDITION`/`LEASE_NOT_HELD`** |
| `WorkerService/CompleteJob` | `RUNNING` -> `SUCCEEDED` with `result` | `Job` | same as Heartbeat |
| `WorkerService/FailJob` | `RUNNING` -> `QUEUED` (retry) or `FAILED`, with `message`, `details`, `retryable` | `Job` | same as Heartbeat |
| `grpc.health.v1.Health/Check` | Standard health check | `SERVING` / `NOT_SERVING` | - |
| `grpc.reflection.v1.ServerReflection` | Schema discovery for `grpcurl`, k6 | - | - |

All RPCs are unary. Kubelet probes and the load balancer's health check don't use gRPC: they hit
SmallRye Health on the Quarkus management interface (`:8081`, `/q/health/live` and
`/q/health/ready`), which is not routed publicly.

**Job types** (`job_types` table) are registered with SQL - see [DB.md](DB.md). `CreateJob` and
`ClaimJob` reject unregistered names; a type supplies the defaults for `max_attempts` and the lease
length. Seeded: `demo.echo`, `demo.sleep` (for tests), `report.generate`, `email.send` (examples).
The registry is read through a per-pod cache with a 60s expiry, so a new type is accepted within a
minute everywhere.

**Idempotency**: a `CreateJob` whose `idempotency_key` was already used returns the job the first
call created (status `OK`) and creates nothing - a client can safely retry a create whose response
it never saw. The key is unique across all jobs and is freed when that job is deleted.

## Conventions

- **Status codes are real.** Each outcome has its own gRPC status, so clients, load balancers and the
  `grpc_errors_total{grpc_status_code}` metric all see failures without parsing a body. The code says
  what kind of failure; the `ErrorInfo.reason` (below) is the stable, finer-grained code to switch on.
- **Bad input is `INVALID_ARGUMENT`**, one status across validation, a malformed UUID, an
  unregistered type and an out-of-range bound. A request larger than the server's 64 KiB limit is
  rejected by the transport with `RESOURCE_EXHAUSTED` before it reaches the service.
- **State conflicts are `FAILED_PRECONDITION`** (cancel a finished job, delete an unfinished one, a
  worker call without the lease) and a **stale version is `ABORTED`** (read again and retry).
- **JSON payloads** (`spec`, `result`, `error.details`, event `detail`) are
  `google.protobuf.Struct` - any JSON object - and are stored as PostgreSQL JSONB. Numbers in a
  `Struct` are doubles, so integers above 2^53 lose precision: send them as strings. JSONB doesn't
  keep key order or duplicate keys.
- **Labels** are `map<string, string>` (stored as JSONB, filtered with `labels @> {...}` on a GIN
  index): `ListJobs` returns jobs carrying *all* the given labels.
- **Enums** are `JobState` (`JOB_STATE_QUEUED`, ...); `JOB_STATE_UNSPECIFIED` is never a valid input.
- **Timestamps** are `google.protobuf.Timestamp` (RFC 3339 in JSON). **`int64`** values
  (`total_count`, event `id`) are strings in proto3 JSON, per the spec.
- **Field names.** The proto uses `snake_case`. The standard proto3 JSON mapping (what k6's
  `k6/net/grpc` and most gateways produce) is `lowerCamelCase`; `grpcurl` prints the proto names.
  Parsers accept either spelling on input. A field at its default value (for example `version: 0`) is
  omitted from JSON unless the client asks for defaults (`grpcurl -emit-defaults`).

## Validation

Hand-written in [`Violations.java`](src/main/java/dev/miqui/jobmanager/validation/Violations.java)
and called by the gRPC service classes. Every failing field is reported at once, not just the first.

| Field | Rule |
| --- | --- |
| `name` | required, trimmed, non-blank, at most 100 characters |
| `type` | required, a registered job type (`ListJobTypes`) |
| `spec` | required (a JSON object, `{}` for none), at most 32 KiB as compact JSON |
| `result`, `details` | optional, at most 32 KiB as compact JSON |
| `labels` | at most 16; keys 1-63 chars of `[a-z0-9._/-]` starting/ending alphanumeric; values at most 63 characters |
| `priority` | `0` to `9` (default `0`; higher is claimed first) |
| `max_attempts` | `1` to `20` (default: the type's) |
| `run_after` | a valid timestamp, at most 30 days ahead (default now; the past means now) |
| `idempotency_key` | optional, trimmed, at most 100 characters |
| `id`, `job_id` | canonical 8-4-4-4-12 UUID (`UUID.fromString` alone also accepts `1-2-3-4-5`) |
| `version` | `0` to 2,147,483,647 when present |
| `reason` (CancelJob) | optional, at most 500 characters |
| `message` (FailJob) | required, trimmed, non-blank, at most 1000 characters |
| `worker_id` | 1-100 characters of `[A-Za-z0-9._:-]` |
| `types` (ClaimJob) | 1 to 20 registered type names |
| `lease_seconds` | `5` to `3600` (default: the type's) |
| `limit` | `1` to `200` (default `50` when absent) |
| `offset` | `0` or more (default `0` when absent) |

Every text field, and every key and string value inside `spec`/`result`/`details`, rejects NUL
(U+0000) - PostgreSQL can store it in neither text nor JSONB - and JSON numbers must be finite (no
NaN/Infinity), with a field violation rather than allowing a database error to become `INTERNAL`.
Out-of-range values are rejected, never silently clamped: a client asking for `limit: 500` should
learn that its assumption is wrong.

## Error model

Every error is a [`google.rpc.Status`](https://cloud.google.com/apis/design/errors) sent in the
`grpc-status-details-bin` trailer (the gRPC "rich error model"), built in one place
([`GrpcErrorHandler`](src/main/java/dev/miqui/jobmanager/grpc/GrpcErrorHandler.java)). It carries:

- a **`google.rpc.ErrorInfo`** whose `reason` is the stable machine-readable code clients should
  switch on (the human `message` may change) and `domain` is `job-manager-api.miqui.dev`;
- for `INVALID_ARGUMENT` only, a **`google.rpc.BadRequest`** with one `FieldViolation {field,
  description}` per invalid field.

| `ErrorInfo.reason` | gRPC status | When |
| --- | --- | --- |
| `BAD_USER_INPUT` | `INVALID_ARGUMENT` | Validation failure, bad UUID, unregistered type, out-of-range value |
| `NOT_FOUND` | `NOT_FOUND` | The job doesn't exist |
| `CONFLICT` | `ABORTED`, `FAILED_PRECONDITION` | Stale `version` on CancelJob; cancelling a finished job or deleting an unfinished one |
| `LEASE_NOT_HELD` | `FAILED_PRECONDITION` | A worker call on a job it doesn't hold (cancelled, re-queued after lease expiry, claimed by another worker) - stop working on it |
| `INTERNAL_SERVER_ERROR` | `INTERNAL` | Anything unhandled. Logged with the trace id; the response never contains a stack trace or exception text |

A status the service didn't produce (for example `RESOURCE_EXHAUSTED` from an oversized message, or
`UNAVAILABLE` from the transport) carries no `ErrorInfo`; `grpc_errors_total{error_code}` records the
bare gRPC code for those. The proto imports `google/rpc/error_details.proto` without using it in any
field, purely so reflection also serves `ErrorInfo` and `BadRequest` and `grpcurl` can decode the
details instead of printing raw bytes. Clients in code read them with the standard helpers (for
example `StatusProto.fromThrowable` in Java, `status.FromError` + `Details()` in Go). See
[`k6-common.js`](k6-common.js) (`reasonOf`, `violationsOf`) for the same thing in k6.

Every RPC is timed (`grpc_server_milliseconds{rpc_service, rpc_method, grpc_status_code}`), every
error increments `grpc_errors_total{..., error_code}`, and every RPC writes one structured log line
(`RpcMetricsInterceptor`).

## Optimistic locking

`Job.version` starts at `0` and every state change adds one (heartbeats don't: they only move the
lease). `CancelJob` takes an optional `version`: when present, the job is cancelled only if it is
still at that version, otherwise `ABORTED` - "don't cancel it if it moved on since I looked" (for
example, a UI cancel button acting on a page that is a minute old). Without it, CancelJob cancels
whatever state the job is in, as long as it isn't finished.

Worker transitions don't need a client-supplied version: the lease is the guard. Each one runs as
`SELECT ... FOR UPDATE` (lock the row), check state and lease owner, then update, in one
transaction - so the reason for a refusal (`NOT_FOUND`, `LEASE_NOT_HELD`, `CONFLICT`, `ABORTED`) is
decided once, against the locked row, without a race.

## Caching

`GetJob` is cache-aside against a Hazelcast map `jobs` (`cache/HazelcastJobCache.java`), **for
finished jobs only** (`SUCCEEDED`, `FAILED`, `CANCELLED`). A finished job never changes again, so
there is nothing to invalidate except on delete:

- **Hit**: served from Hazelcast without a lock and without touching Postgres.
- **Miss**: read from Postgres. A job that is still `QUEUED`/`RUNNING` is returned as-is and not
  cached (its state changes on every claim, heartbeat and completion - caching it would mean an
  eviction on each). A finished job is re-read under the per-id Hazelcast lock and then cached.
- **DeleteJob** deletes and evicts under the same lock. A slow reader that read the job just before
  the delete therefore can't re-insert it afterwards: its fill re-reads under the lock and finds
  nothing (`JobServiceTest.aJobDeletedBetweenTheReadAndTheFillIsNotCached`).

Entries carry a 1h TTL (`app.hazelcast.ttl`) only to bound the member's memory. There is no near
cache (it can't be invalidated reliably across pods). Lists, events and worker RPCs don't use the
cache. The job type registry is cached separately, per pod (`quarkus-cache`, 60s).

The cache is **mandatory**: an unreachable Hazelcast member fails startup rather than running
without it, and a client that gave up reconnecting fails liveness so the container is restarted.

## Bounding the cost of a request

- **Pagination** - `limit` is capped at 200, so no list response is unbounded.
- **Payload sizes** - `spec`, `result` and error `details` are at most 32 KiB each as JSON, labels at
  most 16 short pairs.
- **Message size** - inbound messages over 64 KiB are rejected by the gRPC server
  (`quarkus.grpc.server.max-inbound-message-size`).
- **Connection pool** - each pod has at most 10 database connections (`DB_POOL_MAX`), so 6 pods stay
  well under Cloud SQL's `max_connections = 100`.
- **Indexes** (Flyway V3) - every `ListJobs` filter, the claim queue (a partial index on `QUEUED`
  rows only) and the lease reaper (a partial index on `RUNNING` rows) are index scans, so their cost
  doesn't grow with the history of finished jobs.

Offset pagination can shift under concurrent inserts/deletes; it is not a snapshot or a cursor
guarantee.

## Exposure

- **Reflection**: on, and public, like everything else - it is what lets `grpcurl` and k6 work
  without the `.proto`. Turn it off with `quarkus.grpc.server.enable-reflection-service=false`
  anywhere real.
- **Transport**: TLS terminates at the Google load balancer (`grpc.miqui.dev:443`, TLS 1.2+); the
  hop to the pods is cleartext HTTP/2 (h2c) inside the VPC.
- **Browsers**: native gRPC doesn't work from a browser (it needs gRPC-Web or a proxy), and this
  service has none, so there is no CORS configuration.
- **Authentication**: none. Anyone can submit, cancel, claim or complete jobs, and `worker_id` is
  self-declared. A real deployment would put authn/authz in front of it (a server interceptor
  validating a token in the call metadata, with workers authorized per job type) and rate limiting.

## Evolving the API

- Adding an optional response field, an optional request field, or a new RPC is backwards compatible.
  Never reuse or renumber a field number; `reserved` a removed one.
- `ErrorInfo.reason` values are part of the contract: add new ones, don't repurpose existing ones.
- Breaking changes (a renamed or retyped field, a changed status) would go in a new package
  (`job.v2`) served alongside `job.v1`.
- A new table or column is a new Flyway migration (`src/main/resources/db/migration/V<n>__*.sql`).
  Migrations run at every container start under Flyway's Postgres advisory lock, so each must be safe
  to run while the previous version's pods are still serving - add columns as nullable or with a
  default before the code that requires them ships.
- Natural next steps: a server-streaming `WatchJob` (instead of polling `GetJob`), JSON Schema
  validation of `spec` per type (a `schema` column on `job_types`), and job dependencies (option C
  above, on top of this model).

# Database Debug Queries

Sample SQL for poking at the database directly — useful when an API response looks wrong and you
want to check what's actually in Postgres, bypassing the API (and the Hazelcast cache) entirely.
`messagedb` belongs to job-manager-api (the name is kept from the message service it replaced: same
Cloud SQL instance, same user, new schema) and lives on a Cloud SQL for PostgreSQL instance
provisioned by Crossplane (see [README.md](README.md#architecture)).

Locally, `docker compose up -d` runs the same database as `postgres:16-alpine`:
`docker compose exec postgres psql -U message_app -d messagedb`.

## Connecting

The instance has **no public IP** - only its private IP inside the VPC - so there's nothing to
port-forward to directly, and the `default` namespace's NetworkPolicies only let the app and
postgres-exporter reach it. Use a throwaway pod in a namespace of its own (no NetworkPolicies, not
covered by the Kyverno enforce policies), and delete the namespace afterwards:

```bash
DB_IP=$(kubectl get secret cloudsql-connection -o jsonpath='{.data.privateIP}' | base64 -d)
kubectl create namespace db-shell

# psql prompts for the password - it never ends up in a pod spec that `view` users can read.
# Print it (then paste at the prompt) with:
#   kubectl get secret postgres-credentials -o jsonpath='{.data.DB_PASSWORD}' | base64 -d; echo
kubectl run psql -n db-shell --rm -it --restart=Never --image=postgres:16-alpine -- \
  psql "host=$DB_IP port=5432 dbname=messagedb user=message_app sslmode=require"

kubectl delete namespace db-shell
```

**DataGrip / other local clients** - relay through a pod, then port-forward to it:

```bash
kubectl run pg-relay -n db-shell --restart=Never --image=alpine/socat -- \
  tcp-listen:5432,fork,reuseaddr "tcp-connect:$DB_IP:5432"
kubectl port-forward -n db-shell pod/pg-relay 5433:5432
```

```
jdbc:postgresql://localhost:5433/messagedb?sslmode=require      # user message_app
```

(TLS is required - the instance is `ENCRYPTED_ONLY`. `sslmode=require` encrypts without verifying
the server certificate; the server CA is in the `cloudsql-connection` Secret for `verify-ca`.)

---

## job-manager-api (`messagedb`)

Tables (Flyway [`V3__jobs.sql`](src/main/resources/db/migration/V3__jobs.sql)): `jobs`, `job_events`,
`job_types`, plus Flyway's `flyway_schema_history`. JSON columns (`spec`, `labels`, `result`,
`error`, `detail`) are JSONB: query into them with `->`, `->>` and `@>`.

### 1. Job counts by state (what the `jobs{state}` gauge reports)

```sql
SELECT state, count(*) FROM jobs GROUP BY state ORDER BY state;
```

### 2. The claim queue, in the order ClaimJob takes it

```sql
SELECT id, type, priority, run_after, attempts, max_attempts, name
FROM jobs
WHERE state = 'QUEUED' AND run_after <= now()
ORDER BY priority DESC, run_after, id
LIMIT 20;
```

Jobs with `run_after` in the future (delayed, or waiting out a retry backoff) are queued but not
claimable yet: drop the `run_after <= now()` condition to see them too.

### 3. Running jobs and their leases (who holds what, and for how long)

```sql
SELECT id, type, lease_owner, lease_expires_at, lease_expires_at - now() AS lease_left, attempts
FROM jobs
WHERE state = 'RUNNING'
ORDER BY lease_expires_at;
```

A negative `lease_left` that doesn't go away within ~5s means the lease reaper isn't running
(no app pod up, or every pod is failing its scheduled job - check the logs for `LeaseReaper`).

### 4. One job with its full history

```sql
SELECT id, name, type, state, version, attempts, max_attempts, spec, result, error, labels,
       created_at, started_at, finished_at
FROM jobs WHERE id = '<JOB_ID>';

SELECT id, from_state, to_state, actor, detail, at
FROM job_events WHERE job_id = '<JOB_ID>' ORDER BY id;
```

### 5. Check a job's current `version` (debugging an `ABORTED` from `CancelJob`)

```sql
SELECT id, state, version, updated_at FROM jobs WHERE id = '<JOB_ID>';
```

`CancelJob` with `version` only applies while the row is still at that version. Every state change
(claim, complete, fail, cancel, lease expiry) bumps it; heartbeats don't.

### 6. Recent failures and why

```sql
SELECT id, type, attempts, max_attempts, state, error->>'message' AS message, error->'details' AS details, updated_at
FROM jobs
WHERE error IS NOT NULL
ORDER BY updated_at DESC
LIMIT 20;
```

`state = 'QUEUED'` with an error is a job waiting for its retry; `FAILED` is final.
`message = 'lease expired'` means the worker in `details.worker_id` stopped heartbeating.

### 7. Jobs by label (the same filter as `ListJobs {labels: ...}`, served by the GIN index)

```sql
SELECT id, name, state, labels FROM jobs WHERE labels @> '{"team": "finance"}' ORDER BY created_at DESC;
```

### 8. Throughput: jobs finished per minute, by outcome, over the last hour

```sql
SELECT date_trunc('minute', at) AS minute, to_state, count(*)
FROM job_events
WHERE to_state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND at > now() - interval '1 hour'
GROUP BY 1, 2 ORDER BY 1 DESC, 2;
```

### 9. Queue wait (claim time minus when the job became claimable), by type, last hour

```sql
SELECT j.type, count(*) AS claims,
       percentile_cont(0.5)  WITHIN GROUP (ORDER BY e.at - j.run_after) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY e.at - j.run_after) AS p95
FROM job_events e JOIN jobs j ON j.id = e.job_id
WHERE e.to_state = 'RUNNING' AND e.at > now() - interval '1 hour'
GROUP BY j.type;
```

(Approximate for retried jobs: `run_after` is the latest one. The `job_queue_wait` histogram in
Grafana is exact.)

### Register a job type

The API only reads `job_types`; new types are added with SQL. `CreateJob`/`ClaimJob` accept a new
type within a minute (the per-pod registry cache expires after 60s).

```sql
INSERT INTO job_types (name, description, default_max_attempts, default_lease_seconds)
VALUES ('invoice.render', 'Renders one invoice PDF (spec: invoice_id, locale).', 5, 120);
```

A type can't be deleted while jobs of that type exist (`jobs_type_fkey`). For anything permanent,
add the row in a new Flyway migration instead, so every environment gets it.

### Clean up finished jobs (housekeeping)

There is no automatic retention yet. Delete finished jobs older than 30 days (their events go with
them, `ON DELETE CASCADE`). Cached copies expire from Hazelcast within an hour (TTL), so a deleted
job can still be returned by `GetJob` until then - use `DeleteJob` through the API when that matters.

```sql
DELETE FROM jobs
WHERE state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at < now() - interval '30 days';
```

### Which migration is applied

```sql
SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank;
```

`3 | jobs` is the job schema. V1/V2 (the message service's `authors`/`messages`) stay in the history
so Flyway's checksum validation passes on a database that started out as the message service's; V3
drops those two tables. On a database created by the earlier Python service, the first row is the
`<< Flyway Baseline >>` at version 1 (`baseline-on-migrate`).

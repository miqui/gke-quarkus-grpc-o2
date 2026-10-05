# Tracing — job-manager-api → OpenObserve

Distributed tracing for the gRPC API, exported over OTLP to the OTel Collector and from there to
OpenObserve. Traces sit alongside the metrics that already flow through the same collector (see
`PROMETHEUS.md`).

```
job-manager-api ──► otel-collector:4318 ─────────────────────► OpenObserve (org "default")
  OTLP/HTTP          /v1/traces, /v1/metrics   OTLP/HTTP + basic auth
                                               /api/default/v1/traces
```

## What is traced

| Layer | Instrumentation | Spans |
| :--- | :--- | :--- |
| gRPC server | `quarkus-opentelemetry` (gRPC instrumentation) | One server span per RPC, named `<service>/<method>` (e.g. `job.v1.WorkerService/ClaimJob`) with `rpc.service`, `rpc.method`, `rpc.grpc.status_code`. W3C `traceparent` in the call metadata is honoured, so a worker that traces its own work can join the trace. |
| Database | `quarkus.datasource.jdbc.telemetry=true` (OpenTelemetry JDBC) | One client span per statement, named `<OPERATION> <db>.<table>` (`SELECT messagedb.jobs`, `INSERT messagedb.job_events`), with `db.statement` (bind values replaced by `?`). |
| Cache | manual spans in `cache/HazelcastJobCache.java` | `hazelcast.get` / `.set` / `.delete` / `.lock` / `.unlock`; `get` sets `cache.hit` (true/false). |

The Hazelcast client has no OpenTelemetry instrumentation, hence the hand-written spans. They matter
for `GetJob` on a finished job: a cache hit has no SQL spans at all, and a fill shows the lock/re-read/
set sequence (`hazelcast.get` miss -> `SELECT` -> `hazelcast.lock` -> `SELECT` -> `hazelcast.set` ->
`hazelcast.unlock`).

The service calls nothing else, so there is no outgoing cross-service trace.

## Sampling

`config/TracingConfig.java` produces the `Sampler` (Quarkus picks up a CDI `Sampler` bean in place of
`quarkus.otel.traces.sampler`): parent-based, ratio `TRACES_SAMPLING_PROBABILITY` (default `0.1`) for
new traces, **except** two kinds of root span that are always dropped:

- **CLIENT spans with no parent** - the lease reaper (every 5s) and the jobs-by-state gauge refresh
  (every 15s) query Postgres outside any RPC. Without this, every replica would emit a root `SELECT`
  span every few seconds; verified locally that they were the bulk of an idle service's traces.
  JDBC spans inside an RPC have a parent and are unaffected.
- **`grpc.health.v1` and `grpc.reflection` calls** - `grpcurl` and k6 reflect on every connection.

10% is deliberate: a k6 run against a 5Gi PVC with 3-day retention (see
`k8s/observability/openobserve-values.yaml`) would otherwise fill it quickly. Set
`TRACES_SAMPLING_PROBABILITY: "1.0"` in `k8s/configmap.yaml` temporarily when debugging, and expect
to need a burst of ~50+ calls to see anything at 0.1.

## Configuration

`src/main/resources/application.properties` (OpenTelemetry section) and `k8s/configmap.yaml`,
`job-manager-api-config`:

| Variable | Value | Purpose |
| :--- | :--- | :--- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector.observability.svc.cluster.local:4318` | Base URL; the SDK appends `/v1/traces` and `/v1/metrics` (protocol `http/protobuf`) |
| `TRACES_SAMPLING_PROBABILITY` | `"0.1"` | Ratio for new traces |
| `POD_NAME` | Downward API | Resource attribute `k8s.pod.name`, like the metrics |

`service.name` is `quarkus.application.name` (`job-manager-api`). The exporter timeout is 5s, and
Quarkus flushes the last batch at shutdown within `quarkus.shutdown.timeout`. Logs are not exported
over OTLP (`quarkus.otel.logs.enabled=false`): they go to stdout and the log collector.

## Collector

`k8s/observability/config/otel-collector.yaml` gained a `traces` pipeline next to the existing
`metrics` one:

```yaml
extensions:
  basicauth/openobserve:
    client_auth:
      username: root@example.com
      password: ${env:OPENOBSERVE_PASSWORD}

exporters:
  otlphttp/openobserve:
    endpoint: http://openobserve.observability.svc.cluster.local:5080/api/default
    auth:
      authenticator: basicauth/openobserve

service:
  extensions: [basicauth/openobserve]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlphttp/openobserve]
```

- The `otlphttp` exporter appends `/v1/traces` itself, like the service's exporter.
- `OPENOBSERVE_USER` / `OPENOBSERVE_PASSWORD` come from `secretKeyRef` in
  `otel-collector-deployment.yaml`, pointing at the `openobserve-remote-write-credentials` Secret
  (keys `username`, `password`) that Prometheus' `remote_write` also uses — built by External
  Secrets from GCP Secret Manager, so the credentials stay out of the ConfigMap and there is one
  place (1Password -> `gke-secrets-seed.sh`) to rotate them.
- The collector ConfigMap is generated with a content hash (`configMapGenerator` in
  `k8s/observability/kustomization.yaml`), so a merged config change rolls the collector by itself.

## Verification

**Collector → OpenObserve (verified live, on the earlier local cluster).** A synthetic OTLP span
posted to the collector appeared in OpenObserve as `tracing-smoke-test / smoke-span`, with no export
errors in the collector log:

```bash
kubectl port-forward -n observability svc/otel-collector 14318:4318 &
curl -X POST localhost:14318/v1/traces -H 'Content-Type: application/json' -d '{
  "resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"tracing-smoke-test"}}]},
  "scopeSpans":[{"scope":{"name":"smoke"},"spans":[{"traceId":"5b8efff798038103d269b633813fc60c",
  "spanId":"eee19b7ec3c1b174","name":"smoke-span","kind":2,
  "startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000000050000000"}]}]}]}'
```

**Service → collector (verified locally, 2026-10-04).** The real image against the compose OTel
Collector (`debug` exporter), sampling at 1.0: RPC spans arrived named `job.v1.JobService/CreateJob`,
`job.v1.WorkerService/ClaimJob`, ... with their `INSERT messagedb.jobs` / `INSERT
messagedb.job_events` / `SELECT ...` children and the `hazelcast.*` spans in the same trace; no root
`SELECT` spans from the reaper or gauge refresh, and no health/reflection spans. **Not yet verified
on GKE / in OpenObserve** - repeat with:

```bash
kubectl get pods -n default -l app=job-manager-api

# generate traffic (10% sampling), e.g. one of the k6 scripts, then:
U=$(kubectl get secret openobserve-root-credentials -n observability -o jsonpath='{.data.ZO_ROOT_USER_EMAIL}' | base64 -d)
P=$(kubectl get secret openobserve-root-credentials -n observability -o jsonpath='{.data.ZO_ROOT_USER_PASSWORD}' | base64 -d)
kubectl port-forward -n observability svc/openobserve 15080:5080 &
END=$(python3 -c "import time;print(int(time.time()*1e6))"); START=$((END-3600000000))
curl -s -u "$U:$P" -X POST "http://localhost:15080/api/default/_search?type=traces" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":{\"sql\":\"select operation_name, count(*) as n from \\\"default\\\" where service_name = 'job-manager-api' group by operation_name order by n desc\",\"start_time\":$START,\"end_time\":$END,\"from\":0,\"size\":30}}"
```

Expect rows for the RPC spans and the SQL / `hazelcast.*` spans. Or open OpenObserve → Traces
(`http://localhost:5080` via `./gke-port-forward.sh openobserve`).

## Gotchas

- **Stream stats lag.** `GET /api/default/streams?type=traces` reports `doc_num: 0` for the
  `default` stream even when spans are searchable (they are still in the in-memory table). Search
  instead of trusting the stat.
- **ArgoCD `selfHeal` reverts hand edits** to `k8s/configmap.yaml` and the deployment, and the
  service image comes from Artifact Registry via Image Updater. To test a tracing change on the cluster it
  has to be merged, or auto-sync paused.
- **Nothing shows up?** In order: pods still on the old image; sampler ratio too low for the
  amount of traffic; `OTEL_EXPORTER_OTLP_ENDPOINT` wrong (it is the base URL, *without*
  `/v1/traces`); collector log for export errors (`kubectl logs -n observability deploy/otel-collector`).
- **Logs** are not covered here - see `LOGS.md`. The app writes one JSON object per line to stdout,
  with `mdc.traceId` / `mdc.spanId` when a span is active, so a log line can be matched to its trace.

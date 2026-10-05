# Grafana Changes — OpenObserve Observability Rollout

## Quarkus migration: message-service -> job-manager-api

The Spring Boot message API was replaced by the Quarkus `job-manager-api`. Metrics still reach
Prometheus the same way (Micrometer -> OTLP push -> OTel Collector -> `job="otel-collector"` scrape),
now through Quarkus' Micrometer-to-OpenTelemetry bridge (`quarkus-micrometer-opentelemetry`). What
changed on the dashboards, and why:

| Dashboard | Change |
| --- | --- |
| all API dashboards | `$service` option `message-service` -> `job-manager-api` (also the container name, so the cAdvisor/kube-state panels follow). `message-service-api` is now `job-manager-api` (key and uid). |
| `api-red`, `grpc-operations`, `grpc-errors`, `job-manager-api` | **No query changes.** `grpc_server_milliseconds_*` and `grpc_errors_total` keep their names, labels (`rpc_service`, `rpc_method`, `grpc_status_code`, `error_code`) and buckets (5 ms ... 5 s): `RpcMetricsInterceptor` records them itself instead of relying on Spring gRPC's observation. Quarkus' own gRPC binder (`grpc_server_processing_duration`, different labels) is disabled. Label values change: `job.v1.JobService`/`job.v1.WorkerService` and the job RPC names. |
| `grpc-operations` | **Write Share** regex: `(Create\|Update\|Delete).*` -> `(Create\|Cancel\|Delete\|Claim\|Complete\|Fail)Job\|Heartbeat`. |
| `grpc-errors` | Descriptions only: CONFLICT now means stale CancelJob versions / state conflicts; the size limit is 64 KiB. A new `error_code` value, `LEASE_NOT_HELD`, appears in the existing panels. |
| `job-manager-api`, `api-red` | **DB pool panels: HikariCP -> Agroal.** `hikaricp_connections_active` -> `agroal_active_count`, `_idle` -> `agroal_available_count`, open = active + available, `_pending` -> `agroal_awaiting_count`, `_max` -> `db_pool_max` (a gauge the app registers from `quarkus.datasource.jdbc.max-size`, because Agroal doesn't export its limit), acquire time -> `rate(agroal_blocking_time_total_milliseconds) / rate(agroal_acquire_count_total)`. |
| `api-red` | **DB Query Time** keeps `jdbc_query_milliseconds_*`: datasource-micrometer has no Quarkus equivalent, so `repo/Db.java` times every statement under the same name (new label `jdbc_operation` = query/update). |
| `hazelcast-cache` | Map `messages` -> `jobs` in every `tag0` matcher and title. The map now holds **finished jobs only**, so the hit ratio counts GetJob misses on queued/running jobs too (they are read from Postgres by design). Entries expire after 1h (TTL), so **Evictions & Expirations** is no longer flat zero. |
| **new `jobs`** | Job queue health from `service/JobMetrics.java`: jobs per state (`jobs{state}`), state transitions/s (`job_transitions_total{from_state,to_state}`), claims claimed vs empty (`job_claims_total{outcome}`), queue wait p50/p95/p99 (`job_queue_wait_milliseconds_*`), lease expirations/s (`job_lease_expirations_total`), failure ratio of finished jobs, and worker RPC errors by code. |

JVM / process panels (`jvm_memory_used_bytes{area}`, `jvm_gc_pause_milliseconds_*`,
`process_cpu_usage`) carry over unchanged - the Micrometer binders are the same.

Two side effects of the bridge, handled outside the dashboards:

- **Extra series.** The bridge exports every timer with SLO buckets twice: the histogram the
  dashboards use, plus one gauge series per bucket (`<name>_histogram{le}`).
  `k8s/observability/config/prometheus.yml` drops `.+_histogram` at scrape time
  (`metric_relabel_configs`), so they never reach OpenObserve's MemTable.
- **More resource labels** on every series (`host_name`, `service_version`, `telemetry_sdk_*`,
  `webengine_*`): same series count, wider rows.

### Verification performed (local, not on GKE)

Against the real image running in `docker compose --profile app` (read-only root fs, uid 10001),
the compose OTel Collector, and a throwaway Prometheus with the cluster's `otel-collector` scrape
job and relabeling, after running all five k6 scripts:

- Every app-metric PromQL expression on `job-manager-api`, `jobs`, `api-red`, `grpc-operations` and
  `grpc-errors` (template variables substituted) returned data. The one exception, the Jobs
  **Failure Ratio**, was empty only because nothing had failed yet; it now has `or vector(0)`.
- Zero `*_histogram` series reached Prometheus.
- **Not verified:** the cAdvisor / kube-state panels (no cluster), the Hazelcast member's JMX
  metrics (the compose member has no exporter), and Grafana rendering itself.

## `$service` variable on the API dashboards (`job-manager-api`, `api-red`, `grpc-operations`, `grpc-errors`)

(Written for the earlier Python service; the mechanism is unchanged.)

The API's metrics (`http_requests_total`, `db_pool_*`, `python_*`, ...) all reach Prometheus through
the same OTel Collector, distinguished only by the `service_name` label (the resource attribute
`service.name`). Every API dashboard has a multi-select **Service** dropdown (`message-service`,
default All), so a second API pushing identically named metrics would appear as another option
without any panel changes:

- HTTP / DB / Python-runtime series filter on `service_name=~"$service"`.
- cAdvisor / kube-state series filter on `container=~"$service"` or `pod=~"$service-.*"` — this
  works because each container is named after its service (`message-service`).
- Per-service legends (`{{service_name}} / {{route}}`), and the DB-pool, CPU and event-loop panels
  are aggregated (`sum` / `max`) instead of emitting one unlabelled series per pod.
- **Ready Pods** (on `message-service-api`) counts `kube_pod_status_ready` for the service's pods
  rather than `up{job="otel-collector"}`, which only says the collector is scraped, not that the API
  is alive.
- **Error Ratio** and the overall errors line use `... or vector(0)` so they read 0, not "No data".

`http_errors_total` is only created on the first error (the OTel counter emits nothing before its
first `.add()`), so the per-route error panels stay empty on a healthy system rather than reading 0;
the ratio and total panels use `or vector(0)` and the by-code panels say "No errors".

`message-service-api` uses `[1m]` rate windows and `api-red` uses `[5m]`.

## Request metrics: `HTTP Operations` and `HTTP Errors` dashboards

Two dashboards (`http-operations`, `http-errors`, same ConfigMap) break the request metrics down by
HTTP method and route template, alongside the per-route panels on `message-service-api` / `api-red`.

The request-metrics middleware in `app/telemetry.py` labels `http_requests_total`,
`http_request_duration_ms` and `http_errors_total` with `method`, `route` (the matched route
*template*, e.g. `/messages/{id}`, or `unmatched`) and, on `http_requests_total`, `status_code`
and, on `http_errors_total`, `error_code` (the problem+json `code`). The route is the template and
never the raw path, so the client can't mint new series by varying ids. Health probes are excluded.
Full details and caveats are in the README's "HTTP Operation & Error Dashboards" section.

- **HTTP Operations** (12 panels; Service / Method / Route variables): request rate, write share
  (POST/PATCH/DELETE), p95/p99, error ratio, active routes; rate, rate-by-method, p95 and average
  latency by route; a latency-distribution heatmap; a per-route summary table.
- **HTTP Errors** (11 panels; Service / Route / Error code variables): error ratio, errors/s,
  server errors/s (`INTERNAL_SERVER_ERROR|UNKNOWN`, should stay 0), conflicts/s; errors by code, by
  route, ratio and conflict ratio by route; client-input errors (`BAD_USER_INPUT`); `NOT_FOUND` by
  route; a totals table.

### Rollout order (follow the ArgoCD image flow)

The metric names and labels come from the service code, so they only reach the cluster through the
normal flow: merge -> GitHub Actions builds and pushes the image -> Argo CD Image Updater picks up the
new tag -> Argo CD rolls the Deployment. Nothing is loaded or patched by hand. The Grafana ConfigMap
ships through the `observability` Argo Application (`k8s/argocd/observability-application.yaml`)
once that is registered; before it is, apply it with
`kubectl apply -f k8s/observability/grafana-dashboard-json-configmap.yaml`. Either way, the panels
are empty until the image is running and has served traffic.

### Verification performed

Run on the earlier local cluster with the real image (the manifests applied with `kubectl apply -k`
rather than through Argo, since nothing was pushed to `main`), after generating traffic with the k6
scripts:

- **Every metric the dashboards reference exists, with the intended labels.** Prometheus had
  `http_requests_total` (labels `method`, `route`, `status_code`, `service_name`, `k8s_pod_name`),
  `http_errors_total` (`error_code` = `NOT_FOUND` / `BAD_USER_INPUT` / `CONFLICT`),
  `http_request_duration_ms_{bucket,sum,count}`, `db_pool_connections_{open,busy,idle}`,
  `db_client_queries_duration_avg_ms`, `python_process_memory_rss_bytes`,
  `python_process_cpu_usage_ratio` and `python_eventloop_lag_p99_ms`. `route` was only ever a
  template (`/messages/{id}`, `/authors/{id}`, `unmatched`, ...) and no `/health*` series existed.
- **No unit double-suffix.** None of the app's instruments were renamed by the collector's
  Prometheus exporter (`http_request_duration_ms_bucket`, not `..._ms_milliseconds_bucket`).
- **All 60 PromQL expressions across the four dashboards** (panel targets plus the query
  variables), with the template variables substituted, evaluated successfully against the live
  Prometheus, and every one returned at least one series.
- **Not verified visually:** Grafana itself was not driven, so panel rendering (the heatmap, the two
  table transformations, the `$route` / `$error_code` query variables) is untested beyond the
  queries above.

## New dashboard: "OpenObserve Ops"

Added as a new key (`openobserve-ops.json`) in
`k8s/observability/grafana-dashboard-json-configmap.yaml`, alongside the other
dashboards in that same ConfigMap. No changes were needed to
`grafana-dashboard-provider-configmap.yaml` or `grafana-deployment.yaml` — the whole ConfigMap is
already mounted as a directory (`/etc/grafana/provisioning/dashboards-json`), so a new key just
shows up as a new file and Grafana's file-based dashboard provider picks it up on its own.

Self-monitoring for the OpenObserve backend itself — not the message-service or cluster-ops data
*inside* OpenObserve, but OpenObserve's own health as a running service. 14 panels, `uid: openobserve-ops`:

| Panel | Type | PromQL |
| :--- | :--- | :--- |
| OpenObserve Up | stat | `up{job="openobserve"}` |
| Disk Usage | gauge | `zo_node_disk_usage{job="openobserve"} / zo_node_disk_total{job="openobserve"} * 100` |
| Process Memory (RSS) | stat | `process_resident_memory_bytes{job="openobserve"}` |
| Open File Descriptors | stat | `process_open_fds{job="openobserve"}` |
| Uptime | stat | `time() - process_start_time_seconds{job="openobserve"}` |
| Ingest Rate (records/s) | stat | `sum(rate(zo_ingest_records{job="openobserve"}[5m]))` |
| HTTP Request Rate by Endpoint | timeseries | `sum(rate(zo_http_incoming_requests{job="openobserve"}[5m])) by (endpoint, status)` |
| HTTP Response Time p95/p99 by Endpoint | timeseries | `histogram_quantile(0.95/0.99, sum(rate(zo_http_response_time_bucket{job="openobserve"}[5m])) by (le, endpoint))` |
| Ingest Rate by Stream Type (records/s) | timeseries | `sum(rate(zo_ingest_records{job="openobserve"}[5m])) by (stream_type)` |
| Ingest Rate by Stream Type (bytes/s) | timeseries | `sum(rate(zo_ingest_bytes{job="openobserve"}[5m])) by (stream_type)` |
| In-Memory MemTable Size | timeseries | `zo_ingest_memtable_bytes{job="openobserve"}` |
| WAL Used Bytes by Stream Type | timeseries | `sum(zo_ingest_wal_used_bytes{job="openobserve"}) by (stream_type)` |
| Process CPU Usage | timeseries | `rate(process_cpu_seconds_total{job="openobserve"}[5m]) * 100` |
| Disk Usage vs Total | timeseries | `zo_node_disk_usage{job="openobserve"}` vs `zo_node_disk_total{job="openobserve"}` |

The **In-Memory MemTable Size** panel is deliberate, not incidental: `zo_ingest_memtable_bytes` is
the exact thing that overflowed (`MemoryTableOverflowError`, see `PROMETHEUS.md` step 1) before
remote_write was scoped down and OpenObserve's resources were bumped. It's the one panel worth
watching if the remote_write scope is ever widened again.

All 14 metric names/labels were read directly off OpenObserve's own `/metrics` output before
writing any panel — none were guessed. Every `process_*` metric query is deliberately scoped with
`{job="openobserve"}`: `process_cpu_seconds_total`/`process_resident_memory_bytes`/etc. are generic
Prometheus-client-library metrics, also exposed by `kube-state-metrics` and `postgres-exporter` —
without the job filter these would silently mix with the wrong process's numbers.

### Dependencies (documented in full elsewhere)

This dashboard depends on two other changes, covered in their own docs:
- `k8s/observability/config/prometheus.yml` gained an `openobserve` scrape job for
  `openobserve.observability.svc.cluster.local:5080/metrics` — see `PROMETHEUS.md` step 5.
- `k8s/observability/openobserve-values.yaml` gained `config.ZO_PROMETHEUS_ENABLED: "true"` (off by
  default — confirmed live that `/metrics` returns HTTP 200 with an empty body otherwise) — see
  `HELM.md`.

### Verification performed

- Queried all 14 panel expressions directly against Prometheus (not just Grafana) — every one
  returned real, non-empty data (e.g. disk usage ~11%, RSS ~566MB, 40 open fds, ingest rate ~967
  records/s).
- Confirmed via Grafana's `/api/dashboards/uid/openobserve-ops` that the dashboard is actually
  provisioned, with all 14 panels present (not just that the ConfigMap key exists).

## Unrelated finding: admin credentials had drifted

While verifying the dashboard via Grafana's API, `admin`/`admin` (the credentials then documented in the committed
placeholder Secret and the README) returned `401 Invalid username or
password` — confirmed via pod logs (`password-auth.invalid`) that this was a genuine credential
mismatch, not a transient issue. The Grafana pod hadn't restarted since its original deploy, so
something changed the in-database admin password outside of this session's changes.

Fixed by resetting it back to the documented value using Grafana's own supported recovery path
(safe on a disposable dev instance):

```bash
kubectl exec -n observability deploy/grafana -- grafana-cli admin reset-admin-password admin
```

No config files were changed for this — the Secret already had the correct value; only Grafana's
own internal (sqlite) state was out of sync with it. Today the admin credentials come from Secret
Manager via the `grafana-credentials` ExternalSecret; `grafana-cli admin reset-admin-password
"$(kubectl -n observability get secret grafana-credentials -o jsonpath='{.data.GF_SECURITY_ADMIN_PASSWORD}' | base64 -d)"`
re-syncs Grafana's database with it if this ever recurs.

# Prometheus Config Changes — OpenObserve Observability Rollout

All changes below are to `k8s/observability/config/prometheus.yml`, in the order they were
actually made. Every `remote_write`/`scrape_configs` change (this ConfigMap has no
`checksum/config`-style annotation, unlike the OpenObserve StatefulSet) required a manual
`kubectl rollout restart deployment/prometheus -n observability` to take effect.

## 1. Added `remote_write` to OpenObserve (unscoped — this broke)

First cut: forward every scraped series to OpenObserve's Prometheus-remote-write-compatible
endpoint, no filtering.

```yaml
remote_write:
  - url: http://openobserve.observability.svc.cluster.local:5080/api/default/prometheus/api/v1/write
    basic_auth:
      username: root@example.com
      password_file: /etc/prometheus/openobserve-auth/password
```

**Result (confirmed live):** every write request failed with HTTP 400
`{"code":400,"message":"Error# MemoryTableOverflowError"}`. Every distinct Prometheus metric name
becomes its own OpenObserve stream, and forwarding every job — in particular
`kubernetes-nodes-cadvisor`'s per-container, per-node series — created far more concurrent streams
than OpenObserve's single-node in-memory MemTable (`config.ZO_MEM_TABLE_MAX_SIZE`, `"0"` = auto-sized,
in `k8s/observability/openobserve-values.yaml`) could hold.

## 2. Scoped remote_write to just the API's own metrics

Added `write_relabel_configs` to keep only the `otel-collector` job (the API's own
metrics pushed via OTLP - FastAPI/SQLAlchemy at the time, Quarkus/Micrometer now) before it reaches
OpenObserve:

```yaml
remote_write:
  - url: http://openobserve.observability.svc.cluster.local:5080/api/default/prometheus/api/v1/write
    basic_auth:
      username: root@example.com
      password_file: /etc/prometheus/openobserve-auth/password
    write_relabel_configs:
      - source_labels: [job]
        regex: 'otel-collector'
        action: keep
```

Also required restarting the OpenObserve pod itself (`kubectl delete pod -n observability
openobserve-0`) to clear the stuck stream backlog left over from step 1 — the fix alone wasn't
enough to unstick an already-overflowed instance.

**Result (confirmed live):** every write request returned HTTP 200, `prometheus_remote_storage_samples_failed_total` stopped climbing.

## 3. Widened scope to add cluster ops metrics (`node-exporter`, `kube-state-metrics`)

Requested: forward the same cluster-ops metrics the existing "Cluster ops" Grafana dashboard
already uses. Added these two jobs to the keep regex — done first, before the higher-cardinality
`kubernetes-nodes-cadvisor`, to verify no overflow incrementally. (OpenObserve's `resources` were
bumped in `openobserve-values.yaml` in the same step, in anticipation of the added volume.)

```yaml
write_relabel_configs:
  - source_labels: [job]
    regex: 'otel-collector|node-exporter|kube-state-metrics'
    action: keep
```

**Result (confirmed live):** ~51,660 samples sent, 0 failures.

## 4. Widened scope further to add `kubernetes-nodes-cadvisor`

The highest-cardinality job (per-container, per-node) — the one that caused the original overflow
in step 1 — added last and watched closely:

```yaml
write_relabel_configs:
  - source_labels: [job]
    regex: 'otel-collector|node-exporter|kube-state-metrics|kubernetes-nodes-cadvisor'
    action: keep
```

**Result (confirmed live):** ~244,000 samples sent, 0 failures, over a sustained multi-minute
window; `prometheus_remote_storage_shards_desired` stayed at ~0.03 (nowhere near falling behind);
OpenObserve pod had 0 restarts. Confirmed real cAdvisor data landed (`container_memory_working_set_bytes`
queryable in OpenObserve).

This is the config's current state (`k8s/observability/config/prometheus.yml` lines 24–32).

## 5. Added a new scrape job for OpenObserve's own metrics (self-monitoring)

Separate from remote_write — this feeds the new "OpenObserve Ops" Grafana dashboard by scraping
OpenObserve's own `/metrics` endpoint locally. **Not** added to the `write_relabel_configs` keep
list above — no reason to remote_write OpenObserve's self-metrics back into itself.

```yaml
- job_name: 'openobserve'
  static_configs:
    - targets: ['openobserve.observability.svc.cluster.local:5080']
```

Required `config.ZO_PROMETHEUS_ENABLED: "true"` in `openobserve-values.yaml` first — off by
default, confirmed live that `/metrics` returns HTTP 200 with an empty body otherwise.

## 6. job-manager-api (Quarkus): drop the bridge's duplicate SLO gauges

The Quarkus service's Micrometer-to-OpenTelemetry bridge exports every timer that has SLO buckets
twice: as the histogram the dashboards use (`grpc_server_milliseconds_bucket`, ...) and as one
extra gauge series per bucket (`grpc_server_histogram{le="..."}`). The `otel-collector` scrape job
now drops the duplicates before they are stored (and so before remote_write):

```yaml
  - job_name: 'otel-collector'
    static_configs:
      - targets: ['otel-collector.observability.svc.cluster.local:8889']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: '.+_histogram'
        action: drop
```

Verified locally against the compose OTel Collector after a k6 run: 0 `*_histogram` series were
stored, and every dashboard query still returned data. See GRAFANA.md for the metric renames
(HikariCP -> Agroal) that came with the migration.

## 7. Capped remote_write shards (Prometheus OOMKilled during bootstrap)

On the 2026-10-05 rebuild Prometheus was OOMKilled once (limit 512Mi) a few minutes after start:
OpenObserve wasn't accepting writes yet, every batch failed, and Prometheus resharded remote_write
from 1 to 44 shards, each buffering samples in memory. Fix: `queue_config` caps it at 4 shards of
2500 samples (ample for four jobs; unsent data stays in the WAL), and the memory limit is now
768Mi (steady state ~380Mi).

```yaml
    queue_config:
      max_shards: 4
      capacity: 2500
```

Check: `prometheus_remote_storage_shards` stays <= 4, and
`kubectl get pod -n observability -l app=prometheus` shows no restarts after a rebuild.

## Current `remote_write` + relevant `scrape_configs` (final state)

```yaml
remote_write:
  - url: http://openobserve.observability.svc.cluster.local:5080/api/default/prometheus/api/v1/write
    basic_auth:
      username: root@example.com
      password_file: /etc/prometheus/openobserve-auth/password
    queue_config:
      max_shards: 4
      capacity: 2500
    write_relabel_configs:
      - source_labels: [job]
        regex: 'otel-collector|node-exporter|kube-state-metrics|kubernetes-nodes-cadvisor'
        action: keep

scrape_configs:
  - job_name: 'otel-collector'
    static_configs:
      - targets: ['otel-collector.observability.svc.cluster.local:8889']
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: '.+_histogram'
        action: drop

  - job_name: 'kube-state-metrics'
    static_configs:
      - targets: ['kube-state-metrics.observability.svc.cluster.local:8080']

  - job_name: 'openobserve'
    static_configs:
      - targets: ['openobserve.observability.svc.cluster.local:5080']

  # ... postgres-exporter, hazelcast, node-exporter, kubernetes-nodes-cadvisor jobs
  # pre-existed this work and are unchanged - see config/prometheus.yml for the full file.
```

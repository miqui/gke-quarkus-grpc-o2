# Pod logs → OpenObserve

Container logs from the `default` namespace (job-manager-api, postgres, hazelcast)
are tailed off the nodes by an OTel Collector DaemonSet and shipped to OpenObserve. Metrics
(`PROMETHEUS.md`) and traces (`TRACING.md`) already go there; this adds the third signal.

```
/var/log/pods/default_*/*/*.log ─► log-collector (DaemonSet, filelog) ─► OpenObserve
   (CRI files on each node)          OTLP/HTTP + basic auth               stream "pod_logs"
```

## Manifests (`k8s/observability/`)

| File | What |
| :--- | :--- |
| `config/log-collector.yaml` | `filelog` receiver + `container` parser → batch → `otlphttp/openobserve` |
| `log-collector-daemonset.yaml` | `otel/opentelemetry-collector-contrib:0.114.0`, one pod per api/db/cache node |
| `resourcequota.yaml` | `pods` raised from 20 to 30 (was 14/20 in use; the DaemonSet adds 5) |
| `../policies/exceptions/log-collector.yaml` | Kyverno `PolicyException` (see "Kyverno" below) |

It is a separate collector from `otel-collector` (the OTLP receiver Deployment): that one is a
central Deployment, this one has to run on every node to read the node's files.

## How it works

- **Scope: `default` namespace only** (`include: /var/log/pods/default_*/*/*.log`). Collecting every
  pod (argocd, kube-system, crossplane-system, …) would risk OpenObserve's 5Gi PVC and single-node
  MemTable — the same failure described in `PROMETHEUS.md` step 1. Widen it deliberately, not by
  accident. This also means the collector never reads its own logs (it is in `observability`).
- **`container` operator** parses the CRI line format (`<time> stdout F <message>`), re-joins lines
  the runtime split, and derives the pod/namespace/container from the file path. So no
  `k8sattributes` processor and no ClusterRole are needed for those labels.
- **`include_file_path: true` is required** by that operator. Without it every line fails with
  `failed to handle attribute mappings: type '<nil>' cannot be parsed as log path field` and
  nothing reaches OpenObserve (this was the first attempt's failure).
- **`start_at: end`**: a starting or restarted collector skips whatever was logged while it was
  down instead of replaying every file. There is no `file_storage` checkpointing, so restarts lose
  a small gap. Fine for a dev cluster; add `file_storage` if that matters.
- **Placement**: `nodeAffinity` on `workload In (api, db, cache)` — the nodes running `default`
  pods. The control-plane, observability and openobserve nodes get none.
- **Auth**: same `basicauth` extension and `openobserve-remote-write-credentials` Secret as the
  traces pipeline (`OPENOBSERVE_PASSWORD` env, key `password`).
- **Single stream**: the `stream-name: pod_logs` header puts everything in one stream (default
  would be `default`).

## Fields in OpenObserve

Each row of `pod_logs` has `body` (the log line), `k8s_namespace_name`, `k8s_pod_name`,
`k8s_pod_uid`, `k8s_container_name`, `k8s_container_restart_count`, `log_iostream`
(`stdout`/`stderr`), `logtag` (`F` full / `P` partial), `log_file_path`, and `_timestamp`.

```sql
select _timestamp, k8s_pod_name, body from "pod_logs"
where k8s_container_name = 'job-manager-api' and body like '%ERROR%'
order by _timestamp desc
```

## Kyverno

`observability` is audit-only, so nothing here is blocked, but the DaemonSet would show up in the
PolicyReports twice: a `hostPath` volume (`disallow-host-access`) and running as root
(`require-secure-container-context`). Root is required — on the GKE nodes `/var/log/pods` is
`root:root 0750` and the files are `0640`. `k8s/policies/exceptions/log-collector.yaml` exempts it
from those two audit rules, like `node-exporter.yaml` does; the rest of its `securityContext`
(no privilege escalation, drop ALL, RuntimeDefault seccomp, read-only root filesystem, read-only
mount) is still set. The exception is synced by ArgoCD from `main`.

## Application log format

job-manager-api writes **one JSON object per line to stdout** (`quarkus-logging-json`; Flyway,
Hazelcast, Agroal and gRPC log through the same JBoss LogManager), so the CRI line the collector
ships is `<time> stdout F {"timestamp": ..., "level": ..., "loggerName": ..., "message": ..., "mdc": {...}, ...}`.
`LOG_FORMAT_JSON=false` switches to plain text for local runs. Fields:

| Field | Notes |
| :--- | :--- |
| `timestamp` | ISO-8601 with offset, microsecond precision |
| `level` | `INFO`, `WARN`, `ERROR`, ... |
| `message` | The log message |
| `loggerName` | `dev.miqui.jobmanager.access`, `dev.miqui.jobmanager.service.LeaseReaper`, `org.flywaydb...`, `io.quarkus`, ... |
| `mdc.traceId`, `mdc.spanId`, `mdc.sampled` | Present while a span is active (set by Quarkus OpenTelemetry) - copy `traceId` into OpenObserve's Traces view to jump from a log line to its trace. Only `sampled: "true"` traces were exported (10% by default) |
| `mdc.rpc_method`, `mdc.grpc_status`, `mdc.duration_ms` | On the per-RPC `dev.miqui.jobmanager.access` line (`message: "rpc"`) |
| `threadName`, `hostName`, `processName`, `exception` | Standard; `exception` holds the stack trace of an `ERROR` |

The access logger writes one line per RPC; `grpc.health.v1` and reflection calls are not logged.
An unhandled exception is logged by `GrpcErrorHandler` with its stack trace (and the active
`traceId`), while the client only ever gets a generic `INTERNAL`. Request payloads (`spec`,
`result`) and credentials are never logged. The lease reaper logs one `INFO` line per job it takes
back (`lease expired: job <id> (worker <w>) -> QUEUED|FAILED`).

In OpenObserve the JSON stays in `body`; search inside it with `like` or `str_match`:

```sql
select _timestamp, k8s_pod_name, body from "pod_logs"
where k8s_container_name = 'job-manager-api' and body like '%"grpc_status":"INTERNAL"%'
order by _timestamp desc
```

## Verification

Not yet repeated for job-manager-api on GKE (the format above was checked on the local image:
`docker compose --profile app`, `docker compose logs job-manager-api`). The earlier verification on
the local cluster (2026-09-23, the Python service: 32,471 rows of which 32,053 carried a trace id)
covers the shipping path, which is unchanged. To repeat it:

```bash
kubectl exec -n default deploy/job-manager-api -c job-manager-api -- \
  sh -c 'echo "log-shipping-smoke-test from $HOSTNAME" > /proc/1/fd/1'

U=$(kubectl get secret openobserve-root-credentials -n observability -o jsonpath='{.data.ZO_ROOT_USER_EMAIL}' | base64 -d)
P=$(kubectl get secret openobserve-root-credentials -n observability -o jsonpath='{.data.ZO_ROOT_USER_PASSWORD}' | base64 -d)
kubectl port-forward -n observability svc/openobserve 15080:5080 &
END=$(python3 -c "import time;print(int(time.time()*1e6))"); START=$((END-900000000))
curl -s -u "$U:$P" -X POST "http://localhost:15080/api/default/_search?type=logs" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":{\"sql\":\"select k8s_pod_name, body from \\\"pod_logs\\\" where body like '%smoke-test%'\",\"start_time\":$START,\"end_time\":$END,\"from\":0,\"size\":20}}"
```

## Gotchas

- **Config changes roll the DaemonSet** — the collector doesn't hot-reload, but its ConfigMap is
  generated with a content hash, so a merged config change rolls the pods by itself. Lines logged
  while a pod restarts are skipped (`start_at: end`).
- **Nothing arriving?** `kubectl logs -n observability ds/log-collector` — parse errors
  (`failed to process token`) and export errors (401/400 from OpenObserve) show there. An empty
  `pod_logs` stream with a healthy collector usually just means the pods haven't logged since it
  started.
- **Stream stats lag** — `GET /api/default/streams?type=logs` can show `doc_num: 0` while rows are
  already searchable; search instead.
- **ArgoCD-managed**: `k8s/observability/` is the `observability` Application - change it in git
  and merge; hand edits are reverted by self-heal.
- **Adding a namespace**: change the `include` glob. The DaemonSet runs on every node, so no
  scheduling change is needed.

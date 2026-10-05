# kubectl Commands — OpenObserve Observability Rollout

`kubectl` commands used while adding OpenObserve observability for the API (first the message REST API, now job-manager-api),
grouped by phase.

## Cluster access and platform status (GKE)

```bash
gcloud container clusters get-credentials dev-cluster --zone=us-central1-a --project=k8s-dev-412419
kubectl config current-context          # gke_k8s-dev-412419_us-central1-a_dev-cluster
kubectl get nodes -o wide

# Everything is an Argo CD Application (see ARGOCD.md)
kubectl get applications -n argocd -o custom-columns='NAME:.metadata.name,WAVE:.metadata.annotations.argocd\.argoproj\.io/sync-wave,SYNC:.status.sync.status,HEALTH:.status.health.status'

# Cloud SQL via Crossplane
kubectl get postgresinstance messagedb                  # INSTANCE / PRIVATE-IP columns
kubectl get managed                                     # every Crossplane managed resource
kubectl get providers.pkg.crossplane.io,functions.pkg.crossplane.io
kubectl describe databaseinstances.sql.gcp.m.upbound.io # provisioning errors show up here

# Secrets from Secret Manager
kubectl get externalsecrets -A                          # STATUS should be SecretSynced
kubectl get clustersecretstore gcp-secret-manager

# Public entry point
kubectl get gateway api -o wide                         # PROGRAMMED=True, ADDRESS=api-ip
kubectl describe httproute api
kubectl get healthcheckpolicy,gcpgatewaypolicy

# Workloads
kubectl rollout status deployment/job-manager-api --timeout=180s
kubectl get pods -o wide
kubectl get pods -n observability -o wide
kubectl get networkpolicy -A
```

## Tools (port-forward only)

```bash
./gke-port-forward.sh                   # all of them; or e.g. ./gke-port-forward.sh grafana
kubectl port-forward -n observability svc/prometheus 9090:9090   # the same, by hand
```

## Manual verification (Prometheus remote_write → OpenObserve)

```bash
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query=prometheus_remote_storage_succeeded_samples_total'
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query=prometheus_remote_storage_samples_failed_total'
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query=up{job="otel-collector"}'
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query=prometheus_remote_storage_pending_samples'
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/metrics'
kubectl exec -n observability deploy/prometheus -- wget -qO- 'http://localhost:9090/api/v1/query?query={__name__=~"prometheus_remote_storage.*"}'
kubectl port-forward -n observability svc/prometheus 9090:9090
kubectl logs -n observability deploy/prometheus --tail=100
kubectl logs -n observability deploy/prometheus --tail=200
kubectl exec -n observability deploy/prometheus -- cat /etc/prometheus/prometheus.yml
kubectl exec -n observability deploy/prometheus -- ls -la /etc/prometheus/openobserve-auth/
kubectl exec -n observability deploy/prometheus -- cat /etc/prometheus/openobserve-auth/password
```

## Diagnosing the OpenObserve `MemoryTableOverflowError`

```bash
kubectl logs -n observability openobserve-0 --tail=100
kubectl top pod -n observability openobserve-0
kubectl get pod -n observability openobserve-0 -o jsonpath='{.status.containerStatuses[0].restartCount}'
kubectl describe pod -n observability openobserve-0
kubectl logs -n observability openobserve-0 --tail=300
kubectl logs -n observability openobserve-0 --tail=30
kubectl logs -n observability openobserve-0
kubectl exec -n observability openobserve-0 -- df -h /data
kubectl exec -n observability openobserve-0 -- du -sh /data/*
```

## Applying the fix (scoped `write_relabel_configs`) and recovering

```bash
kubectl apply -k /Users/miqui/development/k8s-fastapi-o2/k8s/observability/
kubectl rollout restart deployment/prometheus -n observability
kubectl rollout status deployment/prometheus -n observability --timeout=90s
kubectl delete pod -n observability openobserve-0
kubectl rollout status statefulset/openobserve -n observability --timeout=120s
kubectl port-forward -n observability svc/prometheus 9090:9090
```

## Final verification

```bash
kubectl port-forward -n observability svc/openobserve 5080:5080
kubectl get pods -n observability -o wide
```

## Diagnosing and fixing Prometheus probe timeouts (context deadline exceeded)

```bash
# Inspect pod events and probe failures
kubectl describe pod -n observability -l app=prometheus
kubectl describe -n observability pod/<pod-name>

# Check Prometheus resource usage during scrape and remote-write loads
kubectl top pod -n observability -l app=prometheus

# Apply updated deployment with increased probe timeouts (timeoutSeconds: 3, failureThreshold: 3)
kubectl apply -f k8s/observability/prometheus-deployment.yaml
# or via Kustomize:
kubectl apply -k k8s/observability/

# Monitor rollout
kubectl rollout status deployment/prometheus -n observability

# Verify new pod status and active probe settings
kubectl get pods -n observability -l app=prometheus -o wide
kubectl describe pod -n observability -l app=prometheus
```

## Diagnosing Grafana vs Headlamp pod count mismatch (48 vs 50)

```bash
kubectl config current-context
kubectl get pods --all-namespaces --no-headers | wc -l
kubectl get pods --all-namespaces -o wide
```

Turned out both counts were correct: `kubectl`/Headlamp count all 50 pod objects, while Grafana's
panel filters on `phase="Running"`, excluding 2 `Completed` Job pods (50 − 2 = 48). Same idea
applies on GKE: `Completed`/`Succeeded` pods (hook Jobs, Trivy scan Jobs) are in one count, not the other.

## Diagnosing `argocd-repo-server` liveness probe failures

Symptom: `Liveness probe failed: Get "http://<ip>:8084/healthz?full=true": context deadline exceeded`
and a few restarts (exit code 0). The pod was otherwise healthy (about 2m CPU / 38Mi, no resource limits).

```bash
kubectl get pod -n argocd -l app.kubernetes.io/name=argocd-repo-server -o wide   # RESTARTS column
kubectl describe pod -n argocd -l app.kubernetes.io/name=argocd-repo-server       # probe settings, Last State, Events
kubectl get events -n argocd --sort-by=.lastTimestamp
kubectl top pod -n argocd
```

Reading the repo-server logs (JSON; address the Deployment so it survives pod renames):

```bash
kubectl logs -n argocd deploy/argocd-repo-server
kubectl logs -n argocd deploy/argocd-repo-server -f --tail=50
kubectl logs -n argocd deploy/argocd-repo-server --previous                       # before a liveness restart
kubectl logs -n argocd deploy/argocd-repo-server --since=1h
kubectl logs -n argocd deploy/argocd-repo-server | grep '"level":"error"'
kubectl logs -n argocd deploy/argocd-repo-server | grep -v 'grpc.health.v1.Health' # drop health-check noise
kubectl logs -n argocd deploy/argocd-repo-server | grep healthcheck               # the probe-side failures
kubectl logs -n argocd deploy/argocd-repo-server --since=1h | jq -r '[.time,.level,.msg] | @tsv'
```

The tell-tale line is `Error serving health check request ... context canceled` with
`"duration":5004874461`: the health check normally answers in about 1ms, and here it ran into the
probe's `timeoutSeconds: 5`. That points at a stall around the process (CPU or memory contention on
the node), not at repo-server being slow. This was seen on the earlier, heavily packed local cluster;
on GKE check node pressure first:

```bash
kubectl top nodes
kubectl describe node <node> | grep -A8 'Allocated resources'
```

If it recurs, give `argocd-repo-server` a CPU/memory request (and, if needed, a looser liveness
probe) through `repoServer.resources` / `repoServer.livenessProbe` in `k8s/argocd/argocd-values.yaml`
- Argo CD manages its own chart, so a hand `kubectl patch` would just be reverted.

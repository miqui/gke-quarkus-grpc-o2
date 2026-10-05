# ArgoCD — commands & troubleshooting

Day-to-day ArgoCD commands for this repo's GKE dev cluster. Everything is `kubectl`-based: the
`argocd` CLI isn't installed here (an optional CLI section is at the end). For the design — what
ArgoCD owns and why — see the "Continuous Deployment with ArgoCD" section of `README.md`; for
`argocd-repo-server` liveness-probe failures see `KUBECTL.md`.

Commands marked ✅ were run against this cluster while writing this; the rest are standard ArgoCD /
kubectl usage that I did not run here.

## What's in the cluster

Everything is an Application, created by the `root` Application (app of apps) from
`k8s/argocd/apps/`; `gke-bootstrap.sh` only Helm-installs Argo CD and applies
`k8s/argocd/root-application.yaml`.

| Application | Wave | Owns |
| :--- | :--- | :--- |
| `root` | - | every file in `k8s/argocd/apps/` |
| `argocd` | -4 | Argo CD itself (chart `argo/argo-cd` + `k8s/argocd/argocd-values.yaml`); never prunes |
| `external-secrets` | -4 | External Secrets Operator (chart + `k8s/external-secrets/values.yaml`) |
| `crossplane` | -4 | Crossplane core (chart + `k8s/crossplane/values.yaml`) |
| `kyverno` | -3 | Kyverno (chart + `k8s/kyverno/kyverno-values.yaml`) |
| `platform` | -3 | `k8s/platform/`: ClusterSecretStore, Crossplane GCP SQL provider/functions/ClusterProviderConfig, `PostgresInstance` XRD + Composition |
| `kyverno-policies` | -2 | `k8s/policies/` |
| `observability` | -1 | `k8s/observability/` (OTel Collector, Prometheus, Grafana + dashboards, kube-state-metrics, node-exporter, log collector, ExternalSecrets, NetworkPolicies, the namespace) |
| `headlamp` | -1 | chart + `k8s/headlamp/headlamp-values.yaml` + `k8s/headlamp/manifests/` |
| `trivy-operator` | -1 | chart + `k8s/trivy-operator/trivy-operator-values.yaml` - see [TRIVY.md](TRIVY.md) |
| `argocd-image-updater` | -1 | chart + `k8s/argocd/image-updater-values.yaml` + the `ImageUpdater` CR in `k8s/argocd/image-updater/` |
| `openobserve` | 0 | chart + `k8s/observability/openobserve-values.yaml` |
| `job-manager-api` | 1 | `k8s/`: `PostgresInstance`, job-manager-api, Hazelcast, postgres-exporter, Gateway, NetworkPolicies, quota |

All track `main` with `automated: {prune: true, selfHeal: true}` (except `argocd`: no prune). UI:
`./gke-port-forward.sh argocd` -> `https://localhost:8081`.

Three `argocd-cm` customizations in `argocd-values.yaml` matter for this layout: the Application
health check (without it sync waves wouldn't wait for a child to be Healthy), Crossplane health checks
(so Cloud SQL provisioning shows as Progressing, not Healthy), and `controller.diff.server.side`
(client-side diff misreads the big server-side-applied CRDs as permanently OutOfSync).

## Status

```bash
kubectl get applications -n argocd                                                       # ✅ sync + health at a glance

# one Application: sync status, health, deployed commit, last operation
kubectl get application job-manager-api -n argocd \
  -o jsonpath='{.status.sync.status} {.status.health.status} rev={.status.sync.revision} {.status.operationState.phase}{"\n"}'   # ✅

# per-resource status (spot the OutOfSync / Degraded one)
kubectl get application job-manager-api -n argocd \
  -o jsonpath='{range .status.resources[*]}{.kind}/{.name} {.status} {.health.status}{"\n"}{end}'                              # ✅

# errors (ComparisonError, SyncError, ...) - empty output means none
kubectl get application job-manager-api -n argocd -o jsonpath='{.status.conditions}{"\n"}'                          # ✅

# last deployments: id, commit, time
kubectl get application job-manager-api -n argocd \
  -o jsonpath='{range .status.history[-3:]}{.id} {.revision} {.deployedAt}{"\n"}{end}'                                        # ✅

kubectl describe application job-manager-api -n argocd                          # everything above plus events
```

Compare the deployed commit with `main`: `git rev-parse origin/main` vs `.status.sync.revision`. A
merge is only live once these match — ArgoCD polls git roughly every 3 minutes, so right after a
merge it is normal for the Application to still show the previous commit.

**Health `Progressing`** right after a merge is usually just a rolling update in flight
(`kubectl rollout status deploy/job-manager-api`); it goes back to `Healthy` on its own.

## Forcing a refresh or a sync (without the CLI)

Both of these **modify the live Application object**, so they are worth doing on purpose rather than
by reflex; waiting for the next poll is always the no-touch option.

```bash
# Re-poll git now (ArgoCD removes the annotation itself once handled). "hard" also drops the
# manifest cache - use it if a refresh keeps returning stale state.
kubectl annotate application kyverno-policies -n argocd argocd.argoproj.io/refresh=normal --overwrite
kubectl annotate application kyverno-policies -n argocd argocd.argoproj.io/refresh=hard --overwrite

# Trigger a sync operation (what the UI's "Sync" button does)
kubectl patch application job-manager-api -n argocd --type merge \
  -p '{"operation":{"sync":{"prune":true}}}'
```

With `automated` sync on, a refresh alone is normally enough: once ArgoCD sees the new commit it
syncs by itself.

## Image Updater

CI pushes `us-central1-docker.pkg.dev/k8s-dev-412419/springboot-grpc-o2/job-manager-api:<yyyymmddHHMMSS>-<sha7>`;
Image Updater picks the newest tag (`alphabetical` strategy) roughly every 2 minutes and writes it
into the Application — **no git commit is made**. It reads Artifact Registry with a short-lived
token from the GKE metadata server (Workload Identity, `argocd-image-updater` GSA, read-only); the
root Application ignores `/spec/source/kustomize` on `job-manager-api` so it doesn't revert the update.

```bash
# the tag each service is currently pinned to (the override lives on the Application, not in git)
kubectl get application job-manager-api -n argocd \
  -o jsonpath='{.spec.source.kustomize.images}{"\n"}'                                     # ✅

kubectl get imageupdater -n argocd                                                        # ✅ READY, LAST CHECKED
kubectl logs -n argocd deploy/argocd-image-updater-controller --tail=20                   # ✅
kubectl logs -n argocd deploy/argocd-image-updater-controller | grep 'images_updated'     # ✅ per-cycle summary
```

A healthy idle cycle logs `images_considered=1 images_skipped=0 images_updated=0 errors=0`. To
confirm a rollout picked up a build, compare the tag above with the pods':

```bash
kubectl get pods -n default -l app=job-manager-api \
  -o jsonpath='{range .items[*]}{.metadata.name} {.spec.containers[0].image}{"\n"}{end}'  # ✅
```

Image tags end in the short SHA of the commit that was built, so `…-3755170` is the build of merge
commit `3755170`.

## Pausing auto-sync (local testing only)

`selfHeal` reverts hand edits to anything ArgoCD owns within minutes, and Image Updater keeps
moving the image tags. To test a locally built image or a hand-patched ConfigMap you must pause
auto-sync first — and remember to restore it.

```bash
# pause: remove the automated block (manual sync still works)
kubectl patch application job-manager-api -n argocd --type json \
  -p '[{"op":"remove","path":"/spec/syncPolicy/automated"}]'

# restore
kubectl patch application job-manager-api -n argocd --type merge \
  -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'
```

While paused, `kubectl get application …` will show `OutOfSync` for anything you changed by hand.

## Drift you should expect

**Secrets don't drift.** Git holds `ExternalSecret`s, not Secrets; External Secrets owns the actual
Secrets (built from GCP Secret Manager), so there's no placeholder for a sync to write back.

**The image tag** on `job-manager-api` differs from git on purpose (Image Updater's override); the root
Application ignores that field.

**Hash-suffixed ConfigMaps.** `prometheus-config-<hash>`, `otel-collector-config-<hash>` and
`log-collector-config-<hash>` are generated by kustomize (`configMapGenerator` in
`k8s/observability/kustomization.yaml`, sources in `k8s/observability/config/`). Editing one of those files
changes the hash, which changes the pod template and rolls the workload - those three components read
their config once at startup and have no reload, so a plain ConfigMap update would leave the old config
running. After a sync Argo prunes the previous hash-named ConfigMap. Prometheus keeps its TSDB in the
container filesystem (no volume), so each such roll drops its local history; the series that are
`remote_write`n to OpenObserve are unaffected. The Grafana ConfigMaps are plain manifests - Grafana re-scans
its dashboard directory every 30s, so no restart is needed.

**Hand edits get reverted.** With `selfHeal` on, `kubectl apply`-ing an observability manifest from an
unmerged branch is undone on the next reconcile. Change it in git and merge instead.

**The `observability` namespace is never pruned** (`argocd.argoproj.io/sync-options: Prune=false` in
`k8s/observability/namespace.yaml`): deleting the namespace would take OpenObserve's PVC with it.

**Adding a new Application**: add a file to `k8s/argocd/apps/` (with a sync-wave annotation) and
merge - the root Application picks it up. Nothing is applied by hand.

## Rolling back

With `automated` sync enabled ArgoCD refuses `argocd app rollback`. The GitOps way is to
`git revert` the bad merge and let ArgoCD sync `main`. For an emergency image rollback, pause
auto-sync (above) and set the previous tag from `.status.history`, or revert and merge — Image
Updater will otherwise move the tag forward again.

## Troubleshooting

**`ComparisonError` / sync status `Unknown`.** ArgoCD can't render the manifests, so it can't say
whether anything is in sync. Seen live on `kyverno-policies` (2026-09-21):

```
Failed to load target state: failed to generate manifest for source 1 of 1: rpc error:
code = Unavailable desc = dns: A record lookup error: lookup argocd-repo-server on 10.96.0.10:53:
dial udp 10.96.0.10:53: i/o timeout
```

That is the application-controller failing to reach `argocd-repo-server` — the repo-server had just
been restarted by its liveness probe (5 restarts at the time). It is transient: it clears on the
next reconcile once the repo-server is back, and the fix for the restarts themselves is in
`KUBECTL.md` ("Diagnosing `argocd-repo-server` liveness probe failures").

```bash
kubectl get pod -n argocd -l app.kubernetes.io/name=argocd-repo-server        # ✅ RESTARTS column
kubectl logs -n argocd deploy/argocd-repo-server --since=10m | grep '"level":"error"'   # ✅
kubectl logs -n argocd statefulset/argocd-application-controller --since=10m            # sync/compare decisions
kubectl get events -n argocd --sort-by=.lastTimestamp
```

**Merged but nothing changed.** In order: is `.status.sync.revision` the merge commit yet (poll
delay, or a `ComparisonError` above)? Is the file actually referenced - listed in its directory's
`kustomization.yaml`, or in a directory an Application syncs? For a new image: did CI push it, and
does the Image Updater log show `images_updated=1`?

**A wave is stuck.** `kubectl get applications -n argocd`: the root Application only starts wave N+1
once every wave-N Application is Healthy. `job-manager-api` staying Progressing for ~15 minutes on a fresh
cluster is Cloud SQL being created (`kubectl get postgresinstance,managed`).

**Stuck `Progressing` / `Degraded`.** Find the resource with the per-resource query above, then
debug that resource (`kubectl describe`, `kubectl get events`). A Deployment rejected by Kyverno in
`default` shows up here as a sync error naming the policy.

**Sync fails on a policy.** The enforce policies deny non-compliant workloads in `default` when
ArgoCD applies them — see the "Working with the policies" section of `README.md` and `KYVERNO.md`.

## Login

```bash
./gke-port-forward.sh argocd       # https://localhost:8081 (self-signed cert), user admin, password:
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
```

(ArgoCD's docs recommend deleting `argocd-initial-admin-secret` after you change the password.)

## Optional: the `argocd` CLI

Not installed here, and none of the commands below were run. With the port-forward above running:

```bash
argocd login localhost:8081 --insecure --grpc-web --username admin   # --insecure: self-signed cert

argocd app list
argocd app get job-manager-api
argocd app diff job-manager-api          # live vs git
argocd app sync job-manager-api
argocd app history job-manager-api
argocd app set job-manager-api --sync-policy none      # pause auto-sync
```

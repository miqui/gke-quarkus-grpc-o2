# Helm charts — how they're installed and changed

Every third-party component is an upstream Helm chart **rendered by Argo CD**, not installed with
`helm install`. Each Application in `k8s/argocd/apps/` pairs the chart (pinned `targetRevision`)
with a values file from this repo (the `$values` multi-source pattern), so changing a value or
bumping a version is a merged commit. There are no Helm releases in the cluster for these - `helm
list -A` shows only `argocd`, the one chart `gke-bootstrap.sh` installs with Helm before Argo CD
exists to take it over.

| Component | Chart | Version | Values | Application |
| --- | --- | --- | --- | --- |
| Argo CD | `argo/argo-cd` | 10.9.2 (v3.5.3) | `k8s/argocd/argocd-values.yaml` | `argocd` |
| Argo CD Image Updater | `argo/argocd-image-updater` | 1.3.1 (v1.3.0) | `k8s/argocd/image-updater-values.yaml` | `argocd-image-updater` |
| External Secrets | `external-secrets/external-secrets` | 2.11.0 | `k8s/external-secrets/values.yaml` | `external-secrets` |
| Crossplane | `crossplane-stable/crossplane` | 2.4.2 | `k8s/crossplane/values.yaml` | `crossplane` |
| Kyverno | `kyverno/kyverno` | 3.9.1 (v1.19.1) | `k8s/kyverno/kyverno-values.yaml` | `kyverno` |
| OpenObserve | `openobserve/openobserve-standalone` | 0.92.2 | `k8s/observability/openobserve-values.yaml` | `openobserve` |
| Headlamp | `headlamp/headlamp` | 0.45.0 | `k8s/headlamp/headlamp-values.yaml` | `headlamp` |
| Trivy Operator | `aqua/trivy-operator` | 0.36.0 | `k8s/trivy-operator/trivy-operator-values.yaml` | `trivy-operator` |

Repositories: `argo` https://argoproj.github.io/argo-helm, `external-secrets`
https://charts.external-secrets.io, `crossplane-stable` https://charts.crossplane.io/stable,
`kyverno` https://kyverno.github.io/kyverno/, `openobserve` https://charts.openobserve.ai,
`headlamp` https://kubernetes-sigs.github.io/headlamp/, `aqua`
https://aquasecurity.github.io/helm-charts/.

## Changing values or versions

1. Edit the values file (or `targetRevision` in the Application) on a branch.
2. Render locally to check it, with the same chart version:
   ```bash
   helm repo add openobserve https://charts.openobserve.ai && helm repo update openobserve
   helm template openobserve openobserve/openobserve-standalone --version 0.92.2 \
     -n observability -f k8s/observability/openobserve-values.yaml | less
   helm show values openobserve/openobserve-standalone --version 0.92.2   # what can be set
   ```
3. Merge. Argo CD re-renders and applies it; charts that hash their config into the pod template
   (OpenObserve's `checksum/config`, for example) roll their pods on their own.

Bumping Argo CD's own chart: change `targetRevision` in `k8s/argocd/apps/argocd.yaml` **and**
`ARGOCD_CHART_VERSION` in `gke-bootstrap.sh`, so fresh clusters start on the same version.

## History worth keeping

These OpenObserve values were each introduced to fix something observed live, and are why the
values file looks the way it does:

- Retention cap (`config.ZO_COMPACT_DATA_RETENTION_DAYS: "3"`, down from the chart's 3650-day default)
- Resource bump (`resources.requests`/`resources.limits`, before widening remote_write to cluster ops metrics)
- Self-metrics enabled (`config.ZO_PROMETHEUS_ENABLED: "true"`, for the OpenObserve Ops Grafana dashboard)
- Root credentials from a Secret (`auth.existingRootUserSecret`, filled by External Secrets) instead
  of values passed at install time

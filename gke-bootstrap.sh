#!/usr/bin/env bash
#
# gke-bootstrap.sh — install the in-cluster platform on the cluster gke-deploy.sh created.
#
# The whole platform is GitOps: this script only
#   1. installs Argo CD with Helm (the one thing Argo can't install itself), then
#   2. applies the root Application (k8s/argocd/root-application.yaml), which syncs every other
#      Application from k8s/argocd/apps/ - Argo CD itself, External Secrets, Crossplane, Kyverno,
#      the policies, observability, OpenObserve, Headlamp, Trivy, Image Updater and the app, in
#      sync-wave order - and
#   3. waits for all of it to be Synced + Healthy, then checks the public endpoint.
# No secret passes through this script: in-cluster credentials come from GCP Secret Manager via
# External Secrets (seeded by gke-secrets-seed.sh).
#
# Usage:
#   PROJECT_ID=k8s-dev-412419 ./gke-bootstrap.sh
#
# Prerequisites: gke-deploy.sh has run; gke-secrets-seed.sh has run at least once; this repo is
# pushed to GITHUB_REPO's main branch and is public (Argo CD reads it anonymously).
set -euo pipefail
trap 'echo "ERROR: failed at line $LINENO (exit $?)" >&2' ERR
cd "$(dirname "$0")"

PROJECT_ID="${PROJECT_ID:?PROJECT_ID is required, e.g. PROJECT_ID=k8s-dev-412419 ./gke-bootstrap.sh}"
ZONE="${ZONE:-us-central1-a}"
CLUSTER="${CLUSTER:-dev-cluster}"
GITHUB_REPO="${GITHUB_REPO:-miqui/gke-quarkus-grpc-o2}"
API_HOST="${API_HOST:-grpc.miqui.dev}"
# Must match k8s/argocd/apps/argocd.yaml, which takes over this release after bootstrap.
ARGOCD_CHART_VERSION="10.9.2"
SYNC_TIMEOUT_MIN="${SYNC_TIMEOUT_MIN:-40}"

REQUIRED_SECRETS=(postgres-app-password grafana-admin-user grafana-admin-password
                  openobserve-root-email openobserve-root-password)

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

for tool in gcloud kubectl helm git curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool not found" >&2; exit 1; }
done

# ---- 0. Preflight -----------------------------------------------------------
log "Preflight"
EXPECTED_CONTEXT="gke_${PROJECT_ID}_${ZONE}_${CLUSTER}"
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || true)
if [[ "$CURRENT_CONTEXT" != "$EXPECTED_CONTEXT" ]]; then
  echo "    kubectl context is '${CURRENT_CONTEXT:-none}', switching to $EXPECTED_CONTEXT"
  gcloud container clusters get-credentials "$CLUSTER" --zone="$ZONE" --project="$PROJECT_ID"
fi
kubectl get --raw /readyz >/dev/null || {
  echo "ERROR: cluster API unreachable - has your public IP changed? (see gke-deploy.sh summary)" >&2; exit 1;
}
echo "    cluster: $EXPECTED_CONTEXT"

# The manifests in k8s/ hard-code the project (Workload Identity annotations, registry path,
# Crossplane config); refuse to bootstrap a cluster in a different one.
if ! grep -q "$PROJECT_ID" k8s/platform/crossplane-provider-config.yaml; then
  echo "ERROR: k8s/ manifests are not for project $PROJECT_ID (grep for k8s-dev-412419 to retarget)" >&2
  exit 1
fi

missing=""
for s in "${REQUIRED_SECRETS[@]}"; do
  gcloud secrets describe "$s" --project="$PROJECT_ID" &>/dev/null || missing="$missing $s"
done
if [[ -n "$missing" ]]; then
  echo "ERROR: missing in Secret Manager:$missing" >&2
  echo "       run: op run --env-file=.env -- env PROJECT_ID=$PROJECT_ID ./gke-secrets-seed.sh" >&2
  exit 1
fi
echo "    Secret Manager: ${#REQUIRED_SECRETS[@]} secrets present"

git ls-remote --exit-code "https://github.com/${GITHUB_REPO}.git" main >/dev/null 2>&1 || {
  echo "ERROR: https://github.com/${GITHUB_REPO}.git (main) is not publicly readable - Argo CD" >&2
  echo "       syncs from it anonymously. Push this repo first." >&2
  exit 1
}
echo "    git: github.com/${GITHUB_REPO} main is readable"
LOCAL_UNPUSHED=$(git log --oneline origin/main..HEAD 2>/dev/null | wc -l | tr -d ' ' || echo 0)
[[ "${LOCAL_UNPUSHED:-0}" == "0" ]] || \
  echo "    WARNING: $LOCAL_UNPUSHED local commit(s) not on origin/main - Argo CD won't see them"

# ---- 1. Argo CD -----------------------------------------------------------------
log "Installing Argo CD (chart argo/argo-cd $ARGOCD_CHART_VERSION)"
if ! helm repo list 2>/dev/null | grep -q '^argo[[:space:]]'; then
  helm repo add argo https://argoproj.github.io/argo-helm
fi
helm repo update argo >/dev/null
helm upgrade --install argocd argo/argo-cd \
  --version "$ARGOCD_CHART_VERSION" \
  --namespace argocd --create-namespace \
  -f k8s/argocd/argocd-values.yaml \
  --wait --timeout 10m

# ---- 2. Root Application ------------------------------------------------------------
log "Applying root Application (app of apps: k8s/argocd/apps/)"
kubectl apply -f k8s/argocd/root-application.yaml

# ---- 3. Wait for everything -----------------------------------------------------------
# Cloud SQL provisioning dominates: ~10-15 min after the job-manager-api wave starts.
log "Waiting for all Applications to be Synced + Healthy (timeout ${SYNC_TIMEOUT_MIN}m)"
deadline=$(( $(date +%s) + SYNC_TIMEOUT_MIN * 60 ))
while :; do
  status=$(kubectl get applications -n argocd \
    -o custom-columns='NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status' \
    --no-headers 2>/dev/null || true)
  total=$(grep -c . <<<"$status" || true)
  done_count=$(grep -cE '\sSynced\s+Healthy$' <<<"$status" || true)
  # root + one per file in k8s/argocd/apps/
  app_files=(k8s/argocd/apps/*.yaml)
  expected=$(( ${#app_files[@]} + 1 ))
  printf '    %s  %s/%s Synced+Healthy\n' "$(date +%H:%M:%S)" "$done_count" "$expected"
  if [[ "$total" -ge "$expected" && "$done_count" -eq "$total" ]]; then
    break
  fi
  if [[ $(date +%s) -ge $deadline ]]; then
    echo "ERROR: timed out. Current state:" >&2
    echo "$status" >&2
    echo "Inspect: kubectl get applications -n argocd; kubectl get postgresinstance,managed -A" >&2
    exit 1
  fi
  sleep 30
done
echo "$status" | sed 's/^/    /'

# ---- 4. Public endpoint ---------------------------------------------------------------------
# grpc.health.v1.Health/Check with plain curl (no grpcurl needed): an empty request is a 5-byte
# gRPC frame of zeros, and grpc-status: 0 arrives in the HTTP/2 trailers, which -D prints.
grpc_health_ok() {
  printf '\0\0\0\0\0' | curl -sS --http2 --max-time 10 -o /dev/null -D - -X POST \
    -H 'content-type: application/grpc' -H 'te: trailers' --data-binary @- \
    "https://${API_HOST}/grpc.health.v1.Health/Check" 2>/dev/null | grep -qi '^grpc-status: 0'
}

log "Checking gRPC health at ${API_HOST}:443"
kubectl wait gateway/grpc -n default --for=condition=Programmed --timeout=15m >/dev/null
ok=0
for _ in $(seq 1 30); do
  if grpc_health_ok; then ok=1; break; fi
  sleep 20
done
if [[ "$ok" == 1 ]]; then
  echo "    ${API_HOST}:443 is serving"
else
  echo "    WARNING: not answering yet. A fresh load balancer can take ~10 min; also check the"
  echo "    Cloudflare A record and cert state:"
  echo "      gcloud certificate-manager certificates describe grpc-cert --format='value(managed.state)'"
fi

cat <<EOF

$(printf '\033[1;32mDone.\033[0m') Platform is synced from github.com/${GITHUB_REPO} (main).

  Public API:   ${API_HOST}:443 (gRPC over TLS; reflection on)
                grpcurl ${API_HOST}:443 list
  Tools (port-forward only):  ./gke-port-forward.sh
    Argo CD admin password:
      kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
    Headlamp (read-only) token:
      kubectl create token headlamp -n headlamp --duration=1h
EOF

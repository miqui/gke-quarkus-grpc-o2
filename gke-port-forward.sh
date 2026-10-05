#!/usr/bin/env bash
#
# gke-port-forward.sh — the way into the platform's tools. None of them has an Ingress, Gateway
# route or LoadBalancer; `kubectl port-forward` tunnels through the GKE API server, which only
# accepts the operator's IP (master authorized networks) with a valid Google identity, and binds
# on 127.0.0.1 only.
#
# Usage:
#   ./gke-port-forward.sh                 # all tools
#   ./gke-port-forward.sh grafana argocd  # just these
# Ctrl-C stops every tunnel. Tunnels that drop (pod restart, idle timeout) are re-opened.
set -euo pipefail

# name | namespace | service | local port | service port | URL scheme
TOOLS=(
  "argocd|argocd|argocd-server|8081|443|https"
  "grafana|observability|grafana|3000|3000|http"
  "prometheus|observability|prometheus|9090|9090|http"
  "openobserve|observability|openobserve|5080|5080|http"
  "headlamp|headlamp|headlamp|4466|80|http"
  "polaris|polaris|polaris-dashboard|8082|80|http"
)

command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found" >&2; exit 1; }
kubectl get --raw /readyz >/dev/null 2>&1 || {
  echo "ERROR: cluster API unreachable (wrong context, or your public IP changed?)" >&2; exit 1;
}

selected=("$@")
wanted() {
  [[ ${#selected[@]} -eq 0 ]] && return 0
  local s; for s in "${selected[@]}"; do [[ "$s" == "$1" ]] && return 0; done; return 1
}

pids=()
# Each tunnel is a retry-loop subshell with kubectl as its child: stop both.
cleanup() {
  local p
  for p in ${pids[@]+"${pids[@]}"}; do
    pkill -P "$p" 2>/dev/null || true
    kill "$p" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

forward() { # forward <ns> <svc> <local> <remote>: keep one tunnel alive until the script exits
  while :; do
    kubectl port-forward -n "$1" "svc/$2" --address 127.0.0.1 "$3:$4" >/dev/null 2>&1 || true
    sleep 2
  done
}

echo "Tunnels (127.0.0.1 only):"
for entry in "${TOOLS[@]}"; do
  IFS='|' read -r name ns svc lport rport scheme <<<"$entry"
  wanted "$name" || continue
  if ! kubectl get svc -n "$ns" "$svc" >/dev/null 2>&1; then
    printf '  %-12s not installed (svc %s/%s missing), skipped\n' "$name" "$ns" "$svc"
    continue
  fi
  forward "$ns" "$svc" "$lport" "$rport" &
  pids+=("$!")
  printf '  %-12s %s://localhost:%s\n' "$name" "$scheme" "$lport"
done
[[ ${#pids[@]} -gt 0 ]] || { echo "nothing to forward"; exit 1; }

cat <<'EOF'

Logins:
  argocd       admin / kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
               (self-signed cert - accept it in the browser)
  grafana      kubectl -n observability get secret grafana-credentials -o jsonpath='{.data.GF_SECURITY_ADMIN_PASSWORD}' | base64 -d
  openobserve  kubectl -n observability get secret openobserve-root-credentials -o jsonpath='{.data.ZO_ROOT_USER_EMAIL}' | base64 -d
               gcloud secrets versions access latest --secret=openobserve-root-password   (password)
  headlamp     kubectl create token headlamp -n headlamp --duration=1h      (read-only)
  polaris      none - read-only report, reachable only through this tunnel

Ctrl-C to stop.
EOF
wait

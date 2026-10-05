#!/usr/bin/env bash
#
# gke-secrets-seed.sh — copy the platform's secrets from 1Password into GCP Secret Manager.
#
# Secret Manager is what the cluster reads (External Secrets Operator, via Workload Identity -
# see k8s/external-secrets/ and the ExternalSecrets next to each consumer). 1Password stays the
# place humans edit them; run this once, and again after rotating anything in 1Password.
# Secrets live outside the cluster, so they survive gke-teardown.sh / gke-deploy.sh cycles.
#
# Usage:
#   op run --env-file=.env -- env PROJECT_ID=k8s-dev-412419 ./gke-secrets-seed.sh
#
# Idempotent: a new secret version is only added when the value actually changed.
set -euo pipefail
trap 'echo "ERROR: failed at line $LINENO (exit $?)" >&2' ERR

PROJECT_ID="${PROJECT_ID:?PROJECT_ID is required}"
ESO_SA="external-secrets@${PROJECT_ID}.iam.gserviceaccount.com"

# <Secret Manager secret id>=<environment variable (resolved by `op run` from .env)>
SECRETS=(
  "postgres-app-password=DB_PASSWORD"
  "grafana-admin-user=GF_SECURITY_ADMIN_USER"
  "grafana-admin-password=GF_SECURITY_ADMIN_PASSWORD"
  "openobserve-root-email=ZO_ROOT_USER_EMAIL"
  "openobserve-root-password=ZO_ROOT_USER_PASSWORD"
)

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

command -v gcloud >/dev/null 2>&1 || { echo "gcloud CLI not found" >&2; exit 1; }

missing=0
for pair in "${SECRETS[@]}"; do
  var="${pair#*=}"
  value="${!var:-}"
  if [[ -z "$value" || "$value" == op://* ]]; then
    echo "ERROR: $var is empty or unresolved - run through: op run --env-file=.env -- ..." >&2
    missing=1
  fi
done
[[ "$missing" -eq 0 ]] || exit 1

gcloud iam service-accounts describe "$ESO_SA" --project="$PROJECT_ID" &>/dev/null || {
  echo "ERROR: $ESO_SA not found - run gke-deploy.sh first" >&2; exit 1;
}

for pair in "${SECRETS[@]}"; do
  id="${pair%%=*}"
  var="${pair#*=}"
  value="${!var}"
  log "$id  (from \$$var)"

  if ! gcloud secrets describe "$id" --project="$PROJECT_ID" &>/dev/null; then
    gcloud secrets create "$id" --project="$PROJECT_ID" --replication-policy=automatic \
      --labels=managed-by=gke-secrets-seed >/dev/null
    echo "    created"
  fi

  current=$(gcloud secrets versions access latest --secret="$id" --project="$PROJECT_ID" 2>/dev/null || true)
  if [[ "$current" == "$value" ]]; then
    echo "    unchanged"
  else
    printf '%s' "$value" | gcloud secrets versions add "$id" --project="$PROJECT_ID" --data-file=- >/dev/null
    echo "    new version added"
  fi

  # Least privilege: ESO can read exactly these secrets, nothing else in the project.
  gcloud secrets add-iam-policy-binding "$id" --project="$PROJECT_ID" \
    --member="serviceAccount:$ESO_SA" --role=roles/secretmanager.secretAccessor >/dev/null
done

cat <<EOF

$(printf '\033[1;32mDone.\033[0m') ${#SECRETS[@]} secrets are in Secret Manager.
External Secrets refreshes in-cluster copies within its refreshInterval (1h); to pick up a
rotation now: kubectl annotate externalsecret -A --all force-sync=\$(date +%s) --overwrite
(then restart the consuming Deployment - env vars are only read at startup).
EOF

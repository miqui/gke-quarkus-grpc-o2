#!/usr/bin/env bash
# Runs the Polaris best-practice checks against this repo's own manifests (k8s/ and
# k8s/observability/), before merge - the same checks and exemptions the in-cluster Polaris
# dashboard uses (k8s/polaris/polaris-values.yaml), rendered from the same chart version
# (k8s/argocd/apps/polaris.yaml). CI: .github/workflows/policy-check.yml.
#
# Report only: prints the score and failed checks, and never fails the build. Kyverno
# (check-policies.sh) is what blocks; Polaris adds the reliability/efficiency checks (probes,
# replicas, PDBs, pull policy, ...) that Kyverno doesn't cover. To make it blocking later, add
# --set-exit-code-on-danger or --set-exit-code-below-score to the audit call.
#
# Needs `kubectl`, `helm` and the `polaris` CLI; run from anywhere.
set -euo pipefail
cd "$(dirname "$0")"

for tool in kubectl helm polaris; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Error: $tool is required."; exit 1; }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# The chart version the cluster runs: the targetRevision right after `chart: polaris`.
chart_version="$(sed -n '/chart: polaris$/{n;s/.*targetRevision: *//p;}' k8s/argocd/apps/polaris.yaml)"
[ -n "$chart_version" ] || { echo "Error: can't read the Polaris chart version from k8s/argocd/apps/polaris.yaml."; exit 1; }

# The dashboard's config.yaml, as the chart renders it from our values (exemptions + merge flag).
# Run from the empty temp dir: helm prefers a local `./polaris` (file or directory) over --repo, and
# the Polaris binary is often right there in the working directory.
(cd "$tmp" && helm template polaris polaris --repo https://charts.fairwinds.com/stable \
    --version "$chart_version" -f "$OLDPWD/k8s/polaris/polaris-values.yaml" \
    --show-only templates/configmap.yaml) \
  | kubectl label --local -f - render=only -o jsonpath='{.data.config\.yaml}' > "$tmp/config.yaml"

mkdir "$tmp/manifests"
kubectl kustomize k8s > "$tmp/manifests/app.yaml"
kubectl kustomize k8s/observability > "$tmp/manifests/observability.yaml"

polaris audit --audit-path "$tmp/manifests" --config "$tmp/config.yaml" --merge-config \
  --format pretty --color=false --only-show-failed-tests | tee "$tmp/report.txt"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo '### Polaris audit (informational)'; echo '```'; cat "$tmp/report.txt"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi

# kubectl Commands — Debugging Kyverno Policies and Audits

A cheat sheet for answering "why was this denied?", "why is this flagged?" and "is Kyverno even
working?" on the GKE dev cluster. Design and rationale live in the README's
[Policy as Code with Kyverno](README.md#policy-as-code-with-kyverno) section; this file is only
commands. The commands were first run against the earlier local cluster; they're plain `kubectl`
and apply unchanged on GKE.

Needs `kubectl`, and `jq` for the report queries. Policy objects are cluster-scoped
(`ValidatingPolicy`); exceptions live in the `kyverno` namespace.

Names to know: each rule exists as `<rule>-enforce` (Deny, `default` namespace) and `<rule>-audit`
(Audit, `observability`/`headlamp`); `disallow-latest-tag` and `restrict-cluster-admin-bindings`
are audit-only and have no suffix.

## Is it working? (start here)

```bash
kubectl get pods -n kyverno                               # 4 controllers Running (+ a Completed migrate job)
helm list -n kyverno                                      # release + chart version (3.9.1 = Kyverno v1.19.1)
kubectl get validatingpolicy -o custom-columns=NAME:.metadata.name,ACTIONS:.spec.validationActions,FAILPOLICY:.spec.failurePolicy,READY:.status.conditionStatus.ready
kubectl get applications -n argocd                        # kyverno-policies should be Synced / Healthy
```

`READY` should be `true` for every policy and `ACTIONS` should read `[Deny]` only on the `-enforce`
ones. `FAILPOLICY` `<none>` means the default (`Fail`); the audit policies show `Ignore`.

## Why was my apply/pod denied?

```bash
# Server-side dry-run runs the real admission webhooks without creating anything.
kubectl apply --dry-run=server -f my-manifest.yaml
kubectl run probe --image=nginx --dry-run=server -n default
```

Read the error's *source*:

| Error text | Who denied it |
| --- | --- |
| `admission webhook "vpol.validate.kyverno.svc-..." denied the request: Policy <name> failed: <message>` | Kyverno. `<name>` is the policy, `<message>` the rule that failed; several policies can fail at once |
| `pods "x" is forbidden: failed quota: ...: must specify limits.cpu` | The namespace ResourceQuota, **not** Kyverno |

When a Deployment is denied it is rejected at `kubectl apply` / Argo sync (autogen), not later as
ReplicaSet events. A pod created by a controller can still show up as a failed create:

```bash
kubectl get events -A --field-selector reason=FailedCreate
kubectl describe rs <replicaset> -n <ns>                  # events at the bottom
kubectl get events -A | grep -i policy                    # policy-related events, any namespace
```

## What is flagged, and why? (policy reports)

Audit results and skipped/exempted resources land in `PolicyReport` (namespaced) and
`ClusterPolicyReport` (cluster-scoped).

```bash
kubectl get policyreport -A                               # PASS/FAIL/WARN/ERROR/SKIP per resource
kubectl get clusterpolicyreport                           # cluster-scoped resources (e.g. ClusterRoleBindings)

# Totals by policy and result
kubectl get policyreport,clusterpolicyreport -A -o json \
  | jq -r '[.items[].results[]?] | group_by(.policy + " " + .result) | .[] | "\(.[0].policy)\t\(.[0].result)\t\(length)"' | column -t

# Every failure: policy / namespace / resource
kubectl get policyreport,clusterpolicyreport -A -o json \
  | jq -r '.items[] | . as $r | .results[]? | select(.result=="fail") | "\(.policy)\t\($r.metadata.namespace // "-")\t\($r.scope.kind)/\($r.scope.name)"' | sort -u | column -t

# The message behind one resource's failure
kubectl get policyreport -n observability -o json \
  | jq -r '.items[] | select(.scope.name=="grafana" and .scope.kind=="Deployment") | .results[] | select(.result=="fail") | "\(.policy): \(.message)"'

# Everything Kyverno says about one resource, any result (skip = matched a PolicyException)
kubectl get policyreport -n observability -o json \
  | jq -r '.items[] | select(.scope.name=="node-exporter" and .scope.kind=="DaemonSet") | .results[] | "\(.result)\t\(.policy)"' | sort | column -t

# Only reports that have failures (FAIL is column 6 with -A)
kubectl get policyreport -A --no-headers | awk '$6>0 {print $1,$3,$4,"fail="$6}'
```

Results are re-evaluated when a resource changes and on a background rescan about every 15 minutes
(`--resyncPeriod=15m`). A failure is not an alert that expires: it clears when the resource is fixed
or deleted, or the policy is deleted (Kyverno removes stale reports lazily — it can take minutes).

## Exceptions

```bash
# Use the FULL resource name. The short name resolves to the deprecated kyverno.io API and prints
# "No resources found" even when exceptions exist.
kubectl get policyexceptions.policies.kyverno.io -A
kubectl get policyexceptions.policies.kyverno.io node-exporter -n kyverno -o yaml
```

An exception only takes effect if its `policyRefs` use the **suffixed** policy name
(`require-secure-container-context-audit`, not `require-secure-container-context`) and it sits in
the `kyverno` namespace. A working exception shows as `skip` in the resource's report (see above).

## Policy internals

```bash
kubectl get validatingpolicy <name> -o yaml
kubectl get validatingpolicy <name> -o jsonpath='{range .status.conditionStatus.conditions[*]}{.type}={.status} ({.message}){"\n"}{end}'
```

Healthy is `WebhookConfigured=True` and `RBACPermissionsGranted=True (Policy is ready for
reporting.)`. The `ready` status is only recomputed when the policy object is reconciled, so after
fixing something (RBAC, a Helm value) nudge it and re-check:

```bash
kubectl annotate validatingpolicy <name> debug/touched="$(date +%s)" --overwrite
kubectl annotate validatingpolicy <name> debug/touched-   # remove it: Argo's selfHeal does not
```

## Kyverno's own health

```bash
# Controller logs (colour codes stripped). Components: admission, background, reports, cleanup.
kubectl logs -n kyverno -l app.kubernetes.io/component=admission-controller --tail=200 | sed 's/\x1b\[[0-9;]*m//g' | grep -i <policy-or-resource>
kubectl logs -n kyverno deploy/kyverno-reports-controller --since=30m | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'missing RBAC|forbidden'

# Can each controller read a kind it must scan? Only "reports" needs clusterrolebindings here -
# background/cleanup answering "no" is expected.
for sa in admission background reports cleanup; do
  printf '%-11s ' $sa; kubectl auth can-i list clusterrolebindings.rbac.authorization.k8s.io --as=system:serviceaccount:kyverno:kyverno-$sa-controller
done

# Which webhook covers which namespaces, and does it fail open or closed?
kubectl get validatingwebhookconfiguration kyverno-resource-validating-webhook-cfg -o json \
  | jq -r '.webhooks[] | "\(.name)\t\(.failurePolicy)\t\([.namespaceSelector.matchExpressions[] | select(.operator=="In") | .values | join(",")] | first // "cluster-scoped")"' | column -t

kubectl get validatingwebhookconfigurations,mutatingwebhookconfigurations | grep -i kyverno
helm get values kyverno -n kyverno                        # what k8s/kyverno/kyverno-values.yaml actually deployed
```

Expected: `-fail-` webhooks are the enforce policies (namespace `default`), `-ignore-` ones the audit
policies. Startup logs from the first seconds (lease/TLS-secret errors) are noise.

## GitOps (Argo CD)

```bash
kubectl get application kyverno-policies -n argocd -o jsonpath='sync={.status.sync.status} health={.status.health.status} rev={.status.sync.revision}{"\n"}'
kubectl get application kyverno-policies -n argocd -o json | jq -r '.status.resources[] | select(.status!="Synced") | "\(.kind)/\(.name) \(.status)"'
kubectl annotate application kyverno-policies -n argocd argocd.argoproj.io/refresh=hard --overwrite   # re-read git now
```

`selfHeal` is on: a policy edited or deleted by hand is put back within seconds, so to change a
policy for real, change git. Extra annotations you add are *not* removed.

## Offline (no cluster)

```bash
./check-policies.sh                                       # what CI runs; needs kubectl + the kyverno CLI
kubectl kustomize k8s/policies                            # exactly what Argo will apply

# Run one policy set by hand against one set of manifests (real .yaml files - see the warning below)
d=$(mktemp -d)
kubectl kustomize k8s > $d/app.yaml
kubectl kustomize k8s/policies/overlays/enforce-default > $d/enforce.yaml   # or overlays/audit-other, audit-only
kubectl kustomize k8s/policies/exceptions > $d/exceptions.yaml
kyverno apply $d/enforce.yaml --resource $d/app.yaml --exception $d/exceptions.yaml
```

**Always read the `Applying N policy rule(s)` line.** `N = 0` means nothing was checked, and the
CLI still exits 0. That happens when policies and exceptions share one file, and when the policies
come from a process substitution or pipe (`<(...)`, `/dev/stdin`) instead of a real `.yaml` file.
`--audit-warn` also does not tell Deny from Audit for these policy types — see
[check-policies.sh](check-policies.sh) for why enforce and audit run separately.

## Quick symptom table

| Symptom | Check |
| --- | --- |
| Apply rejected | `--dry-run=server` and read the `Policy ... failed` text; confirm it is Kyverno, not a quota |
| Policy `READY false` | status conditions above; then RBAC (`can-i`), then touch the policy |
| No `ClusterPolicyReport` results | on a fresh policy, existing cluster-scoped resources are only scanned on the ~15 min resync (first result took ~12 min); if it stays empty, reports-controller logs for `missing RBAC` and `can-i` for `reports` |
| Exception has no effect | full resource name, `-enforce`/`-audit` suffix in `policyRefs`, `kyverno` namespace |
| Fixed a violation but it still shows | wait for the ~15 min rescan, or recreate the pod |
| Pods can't be created in `default` at all | are Kyverno pods running? (enforce policies fail closed) |
| Argo shows the policies OutOfSync | per-resource status query above; hard refresh |
| `kyverno apply` says `Applying 0 policy rule(s)` | nothing was checked - use real `.yaml` files and separate `--exception` (Offline section) |

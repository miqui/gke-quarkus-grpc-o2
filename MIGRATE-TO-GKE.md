# Migrating from kind to GKE — decisions, problems and fixes

A record of moving this project from a local kind cluster to a dev GKE cluster in project
`k8s-dev-412419` (2026-09-26): what was decided, and every problem hit during the first real
deployment, with its symptom, cause and fix. Operating instructions live in
[README.md](README.md#deployment-to-gke) (including the rebuild runbook); this file is the history
behind them.

## Decisions

| Area | Before (kind) | After (GKE) | Why |
| --- | --- | --- | --- |
| Cluster | 7-node kind cluster, workloads pinned to labelled nodes | Zonal GKE Standard, `e2-standard-2` x 3-5, private nodes, control plane locked to operator IP, Dataplane V2, Workload Identity, Gateway API, Shielded Nodes, dedicated node SA | Dev cluster rebuilt often; no node pinning needed on real nodes |
| Install flow | `deploy-kind.sh` (referenced in docs, never committed) running Helm + `kubectl apply` | `gke-deploy.sh` (GCP) + `gke-bootstrap.sh` (Helm-installs Argo CD, applies one root Application); **everything else is an Argo CD Application** (app of apps, sync waves) | One GitOps path that resembles production |
| Public entry | ingress-nginx, `*.localhost` | GKE Gateway (global external ALB) on `https://api.miqui.dev`, Google-managed cert (Certificate Manager, DNS authorization), HTTP -> HTTPS, TLS 1.2+ | ingress-nginx is retired; real TLS on a real domain (DNS at Cloudflare) |
| Tools | ArgoCD, Grafana, OpenObserve, Headlamp on `*.localhost` Ingresses | **Port-forward only** (`gke-port-forward.sh`), via the IP-locked, IAM-authenticated API server | Keep every tool off the internet; Headlamp and Prometheus especially |
| Headlamp RBAC | `cluster-admin` | `view` + a read-only cluster-scoped role; no Secrets/exec/writes | Least privilege for the riskiest UI |
| Database | Postgres StatefulSet in-cluster | Cloud SQL for PostgreSQL 16 via a Crossplane Composition (`PostgresInstance` XR): private IP only, TLS required, **no backups / no deletion protection** (dev), unique instance name per rebuild | Managed DB as IaC; Cloud SQL blocks name reuse for ~a week |
| Secrets | Placeholder Secrets in git, overwritten by the deploy script from 1Password, plus Argo CD `ignoreDifferences` | 1Password -> `gke-secrets-seed.sh` -> GCP Secret Manager -> External Secrets Operator (Workload Identity) | No secret values in git or passed through scripts |
| Images | Docker Hub, pushed with a stored token; multi-arch | Artifact Registry, pushed **keylessly** (GitHub OIDC -> Workload Identity Federation, `main` only); amd64 only | No long-lived registry credentials |
| Image Updater | Docker Hub read token | Artifact Registry token from the GKE metadata server (Workload Identity) via an auth script | No stored credential |
| Network | none | Default-deny NetworkPolicies in `default` (both directions), `observability`, `headlamp` and `polaris` (ingress) | Enforced by Dataplane V2 |
| Best-practice report (added 2026-09-27) | none | **Polaris** dashboard (own namespace, port-forward only, chart RBAC: `view` + get/list nodes/RBAC, no Secrets), no Polaris webhook; report-only `polaris-audit.sh` in CI with the same config | Scores what's running, third-party charts included; Kyverno stays the only admission controller. Chosen over Kubevious (unmaintained since 2023, `*/*` read incl. Secrets, telemetry on by default) |

Kept across teardowns: Artifact Registry + images, certificate/map/DNS authorization, Secret
Manager secrets, service accounts, the GitHub Workload Identity pool (`gke-teardown.sh --purge`
removes them).

## Problems and fixes

Numbered in the order they were hit. "Commit" is the fix in this repo's history.

### 1. `deploy-kind.sh` / `teardown-kind.sh` didn't exist

- **Symptom**: README and docs described a `deploy-kind.sh` that installed Argo CD, Kyverno,
  OpenObserve, Headlamp and injected secrets - but it wasn't in the repository.
- **Fix**: nothing to port; the in-cluster install was designed from scratch as GitOps
  (`gke-bootstrap.sh` + `k8s/argocd/apps/`). Commit `e659ef7`.

### 2. Webhook firewall rule: no GKE "master" firewall rule to copy

- **Symptom**: `gke-deploy.sh` warned `could not find GKE's control-plane firewall rule`.
- **Cause**: the script expected a VPC-peering-style private cluster, where the control plane reaches
  nodes from a dedicated range opened only for 443/10250 (so webhooks on 9443 - Kyverno,
  Crossplane - need an extra rule). Current GKE private clusters are **PSC-based**: the control
  plane reaches webhooks through the in-cluster konnectivity agents over the pod network, which
  GKE's own `-all` rule already allows.
- **Fix**: the step now detects this and skips the rule ("PSC-based cluster ... no extra rule
  needed"), and still creates it on older peering-based clusters. Commit `314f222`.

### 3. First CI run failed at "Authenticate to Google Cloud"

- **Symptom**: `google-github-actions/auth failed with: the GitHub Action workflow must specify
  exactly one of "workload_identity_provider" or "credentials_json"`.
- **Cause**: the run started (on the first push) seconds before the `GCP_WIF_PROVIDER` /
  `GCP_CI_SA` repository variables were set; variables are resolved when the run starts.
- **Fix**: `gh run rerun`. Setting the variables is now prerequisite 4 in the README runbook, before
  the first push that triggers CI.

### 4. `platform` sync: "no endpoints available for service external-secrets-webhook"

- **Symptom**: the `platform` Application's first sync failed creating the `ClusterSecretStore`:
  `failed calling webhook "validate.clustersecretstore.external-secrets.io" ... no endpoints
  available`.
- **Cause**: wave -3 began while External Secrets' webhook pod was still starting.
- **Fix**: none needed - `platform` has `retry` configured and succeeded on the next attempt.

### 5. Argo CD showed large CRDs as permanently `OutOfSync`

- **Symptom**: `external-secrets`, `kyverno` and `argocd-image-updater` stayed `OutOfSync` on their
  CRDs after successful syncs; `kubectl diff --server-side` against the rendered CRD was empty.
- **Cause**: Argo CD's default client-side diff misreads CRDs applied with server-side apply.
- **Fix**: `controller.diff.server.side: "true"` in `k8s/argocd/argocd-values.yaml` (Argo CD manages
  its own chart, so pushing the change applied it). Commit `8bb4241`.

### 6. DNS blocked by the `default` NetworkPolicy (NodeLocal DNSCache)

- **Symptom**: Hazelcast's init container: `curl: (6) Could not resolve host: repo1.maven.org`.
- **Cause**: GKE runs **NodeLocal DNSCache**. The `allow-dns-egress` policy only allowed the
  `kube-dns` pods.
  - First attempt (commit `314f222`): allow the documented link-local address `169.254.20.10/32`
    - **did not work**.
  - Real cause: on **Dataplane V2**, `node-local-dns` runs as ordinary pods (pod IPs such as
    `10.4.2.2`, label `k8s-app: node-local-dns`) that receive lookups sent to the kube-dns Service.
- **Fix**: allow egress to pods labelled `k8s-app: node-local-dns` in `kube-system` (plus
  `kube-dns`), port 53 UDP/TCP. Verified live before pushing. Commit `ac31735`.

### 7. Cloudflare records created as proxied (orange cloud)

- **Symptom**: `dig api.miqui.dev` returned `104.21.28.97` / `172.67.145.82` (Cloudflare) instead of
  the api-ip; `_acme-challenge.api` returned Cloudflare A records instead of the CNAME.
- **Cause**: Cloudflare proxies new records by default.
- **Fix**: both records switched to **DNS only**. Documented in the runbook's "Known snags".

### 8. Certificate stuck in `PROVISIONING` after DNS was fixed

- **Symptom**: `managed.state: PROVISIONING`; `authorizationAttemptInfo: state FAILED,
  failureReason CONFIG, issues [CNAME_MISMATCH]`, attempt time **before** the DNS fix, and no new
  attempt 20+ minutes after it.
- **Cause**: Google's first validation ran while the CNAME was missing/proxied; its retry backoff
  can run for hours.
- **Fix**: recreate the certificate against the **same** DNS authorization (so the CNAME value, and
  Cloudflare, stay unchanged): delete the cert-map entry and certificate, recreate both. The exact
  commands are in the README runbook's "Known snags".

### 9. Headlamp: `nodes is forbidden`

- **Symptom**: `User "system:serviceaccount:headlamp:headlamp" cannot list resource "nodes" in API
  group "" at the cluster scope`.
- **Cause**: the built-in `view` ClusterRole only covers namespaced resources.
- **Fix**: `k8s/headlamp/manifests/cluster-read-rbac.yaml` - get/list/watch on nodes, PVs,
  namespaces, storage, CRDs, webhooks, RBAC objects and metrics. Verified with `kubectl auth can-i
  --as`: nodes/PVs/CRDs **yes**; Secrets, `pods/exec`, delete **no**. Commit `e367136`.
- **Follow-up**: Headlamp's Gateway pages then failed with `httproutes.gateway.networking.k8s.io is
  forbidden ... at the cluster scope` - `view` doesn't cover Gateway API CRDs either. Added
  get/list/watch on `gateway.networking.k8s.io` (GatewayClasses, Gateways, *Routes,
  ReferenceGrants, BackendTLSPolicies) and GKE's `networking.gke.io` gateway policies to the same
  ClusterRole.

### 10. Hazelcast killed during startup (exit 143)

- **Symptom**: `Startup probe failed ... context deadline exceeded`, container terminated with 143.
- **Cause**: JVM + JMX agent startup under the 500m CPU limit takes ~50-60 s on an e2 node; the
  probe had the default 1 s timeout and a 65 s budget.
- **Fix**: `timeoutSeconds: 3`, `initialDelaySeconds: 10`, `failureThreshold: 24` (about 2 minutes).
  Commit `e367136`.

### 11. Hazelcast never became Ready (Kubernetes API discovery blocked)

- **Symptom**: readiness `503` for minutes, then restarts; logs: `Auto-detection selected discovery
  strategy: HazelcastKubernetesDiscoveryStrategyFactory`, repeated `Couldn't connect to the
  service`, then `Failure in executing REST call ... KubernetesClient.endpoints`.
- **Cause**: Hazelcast auto-detects Kubernetes and uses the Kubernetes API to find peer members; the
  default-deny egress policy blocks the API server. (kind had no NetworkPolicies, so it never
  showed.)
- **Fix**: `HZ_NETWORK_JOIN_AUTODETECTION_ENABLED=false` - it's a single standalone member with
  nothing to discover. Chosen over granting it API access. After the fix it was Ready in ~20 s with
  0 restarts. Commit `ab8e78f`.

### 12. API returned 500 on cache operations while pods stayed Ready (app bug)

- **Symptom**: `test-api.sh` went from 12/12 passing to 8 failures (`500`); pod logs:
  `hazelcast.errors.HazelcastClientNotActiveError: Client is not active`, preceded by `Unable to get
  live cluster connection, cluster connect timeout (20.00s) is reached ... shutting down the client`.
- **Cause**: the cluster autoscaler removed a node (`ScaleDown ... deleting pod for node scale
  down`), evicting the single Hazelcast member. It was away longer than the client's
  `cluster_connect_timeout` (20 s), so the Python client **shut itself down permanently**. The
  pods kept passing their probes, so nothing restarted them. kind nodes never go away, which is why
  this never appeared before.
- **Fix** (app): `GET /health/liveness` returns `503` when the app is ready but `cache.connected` is
  false, so the kubelet restarts the container and it reconnects. `cache.connected` is a local
  lifecycle flag, not a network call, so a *slow* Hazelcast still can't fail the probe; it isn't
  checked before startup completes or during shutdown. Two tests added (47 pass). Commit `c240798`.

### 13. `kubectl get managed`: "the server doesn't have a resource type"

- **Symptom**: seen once, right after the Crossplane provider installed its CRDs.
- **Cause**: kubectl's discovery cache was older than the new CRDs.
- **Fix**: none needed - the next call worked (`databaseinstances`, `databases`, `users` all carry
  the `managed` category).

### 14. Teardown: VPC deletion blocked by a leftover network endpoint group

- **Symptom**: `gke-teardown.sh` failed at its last step: `The network resource '.../dev-vpc' is
  already being used by '.../networkEndpointGroups/k8s1-9b8fc8cc-default-message-service-8080-...'`.
- **Cause**: container-native load balancing creates a zonal NEG per Service port. Deleting the
  Gateway removes the load balancer, but the NEG is garbage-collected by an in-cluster controller;
  the cluster was deleted before that happened, orphaning it.
- **Fix**: deleted by hand this time; `gke-teardown.sh` now deletes every NEG in the VPC after the
  cluster is gone.

### 15. Teardown: PVC persistent disks survive cluster deletion

- **Symptom**: found only by validating after the teardown: two unattached 5 GB disks
  (`pvc-...`, labelled `goog-k8s-cluster-name: dev-cluster`) - OpenObserve's and the Trivy
  server's volumes - still existing, and billing, with no cluster.
- **Cause**: deleting a GKE cluster doesn't delete the persistent disks behind its PVCs.
- **Fix**: deleted by hand this time; `gke-teardown.sh` now deletes unattached disks labelled with
  the cluster's name after the cluster is gone.

### 16. CI: `polaris-audit.sh` rendered the Polaris binary instead of the chart

- **Symptom**: the first PR run of `policy-check.yml` failed in the new Polaris step: `WARN local
  chart found in current working directory. repository url ignored chart=polaris` and `Error: file
  '.../polaris' does not appear to be a gzipped archive`.
- **Cause**: the install step unpacked the `polaris` CLI into the checkout, and
  `helm template polaris polaris --repo ...` prefers a local `./polaris` over `--repo`.
- **Fix**: the install step works in a temp dir, and the script runs `helm template` from its own
  empty temp dir. Commit `b37da53`.

### 17. Polaris dashboard in `ImagePullBackOff` (`not found`)

- **Symptom**: on the 2026-09-27 rebuild the `polaris` Application stayed `Progressing`; the pod
  failed with `us-docker.pkg.dev/fairwinds-ops/oss/polaris:10.2.4: not found`.
- **Cause**: Fairwinds tags images with a leading `v` (`v10.2.4`). The chart adds the `v` to its
  appVersion default, but an explicit `image.tag` is used verbatim. None of the local checks
  (`helm template`, `kyverno apply`) resolve tags against the registry.
- **Fix**: `image.tag: "v10.2.4"`. Commit `aa53878` (PR #3).

### 18. Polaris: GKE namespaces and name-based false positives in the report

- **Symptom**: the first live report showed 8 "danger" findings for GKE's own
  `gke-managed-networking-dra-driver` DaemonSet, and `sensitiveConfigmapContent` /
  `sensitiveContainerEnvVar` dangers on the `openobserve` and `trivy-operator-config` ConfigMaps
  and the `crossplane` Deployment.
- **Cause**: the exemption list was written from documentation, not from the namespaces a GKE 1.35
  cluster actually has (`gke-managed-networking-dra-driver`, `gke-managed-volumepopulator` were
  missing). The sensitive-content checks match on key *names*; the flagged keys were header names,
  feature flags, empty defaults and the names of Secrets - no secret values (checked by key name
  and length only).
- **Fix**: both namespaces exempted; the three objects exempted from that one rule each (see the
  comments in `k8s/polaris/polaris-values.yaml`).

### 19. Teardown: cluster delete rejected during an autoscaler resize

- **Symptom**: `gke-teardown.sh` (2026-09-27) failed at the cluster step: `Cluster is running
  incompatible operation operation-...` - a `RESIZE_CLUSTER` started a minute earlier.
- **Cause**: deleting the Gateway, Cloud SQL and the Argo CD-managed workloads emptied nodes, and
  cluster-autoscaler began scaling down; GKE allows one cluster operation at a time.
- **Fix**: re-ran after the resize finished (the script is idempotent); `gke-teardown.sh` now waits
  (up to 20 min) for any running operation on the cluster before deleting it.

### 20. Teardown: PVC disks missed - no cluster-name label on GKE 1.35.6

- **Symptom**: after a "successful" teardown, the leftover check found two unattached 5 GB
  `pvc-...` disks (`data-openobserve-0`, `data-trivy-server-0`) - the exact case problem 15 was
  meant to cover; the script's step had printed nothing to delete.
- **Cause**: on this GKE version the PD CSI driver's disks carry **no labels**, so the
  `labels.goog-k8s-cluster-name=dev-cluster` filter matched nothing. The PVC is only recorded in
  the disk's `description` (`"storage.gke.io/created-by":"pd.csi.storage.gke.io"`).
- **Fix**: deleted by hand; the script now matches unattached disks in the cluster's zone that
  have either the label or a `pvc-` name plus the PD CSI description. The new filter was checked
  for syntax only (no disks were left to match) - verify it on the next teardown.

## Verified on the live cluster

| What | Result |
| --- | --- |
| `gke-deploy.sh` | Cluster, network, PSA, registry, WIF, service accounts, IP, SSL policy, certificate - exit 0 |
| `gke-secrets-seed.sh` | 5 secrets in Secret Manager, ESO granted per secret |
| `gke-bootstrap.sh` | All 13 Applications Synced + Healthy through the sync waves, exit 0 |
| CI | lint + types + tests + **keyless** push to Artifact Registry |
| Image Updater | Read Artifact Registry via Workload Identity; set `fastapi-o2` to the CI tag without a git commit; the root Application didn't revert it |
| External Secrets | All ExternalSecrets `SecretSynced` from Secret Manager |
| Crossplane | `PostgresInstance` -> Cloud SQL `messagedb-7f1cf2ff6e77`, private IP `10.16.0.3`, database + user; migrations ran over TLS |
| API | `test-api.sh` 12/12 through a port-forward, against Cloud SQL + Hazelcast (before problem 12, and again after its fix - image `20260926183719-c240798`, rolled out by Image Updater) |
| Gateway | `Programmed=True` on `34.149.147.124`; HTTP answers `301 -> https://` |
| Tools | Argo CD, Grafana, Prometheus, OpenObserve, Headlamp all 200 through `gke-port-forward.sh` |
| Kyverno | Enforce set passes on `k8s/`; self-test fixture rejected (locally and in CI) |
| `gke-teardown.sh` | Gateway, Cloud SQL (via Crossplane), cluster, PSA, IP, SSL policy, firewall, NAT, router, subnet removed in order; failed at the VPC on problems 14-15 (fixed in the script, leftovers cleaned by hand). Afterwards: no cluster, SQL instance, forwarding rule, address, NEG, disk, router or VPC left; registry + images, certificate, secrets, service accounts and WIF pool kept |

### Rebuild of 2026-09-27 (Polaris)

| What | Result |
| --- | --- |
| `gke-deploy.sh` | exit 0; Cloudflare `A` record upserted via the API token; certificate `api-cert` already `ACTIVE` |
| `gke-bootstrap.sh` | All 14 Applications Synced + Healthy (Polaris after problem 17) |
| Polaris | Application Synced/Healthy; dashboard 200 on `localhost:8082` via `gke-port-forward.sh`; Kyverno PolicyReports in `polaris` 4/4 pass; `kubectl auth can-i` for its ServiceAccount: Secrets `no`, `pods/exec` `no`, `clusterrolebindings` list `yes` (needed by the RBAC checks) |
| Polaris report | 1381/1810 checks passing (76%) before the problem 18 exemptions. Real dangers: Argo CD's exec/attach RBAC and `cluster-admin` binding (upstream chart), privilege escalation / run-as-root in `observability`, Headlamp and Trivy |
| CI | `policy-check.yml` runs the Polaris audit (report only); `k8s/` scores 80 |
| HTTPS on `api.miqui.dev` | **Not confirmed**: DNS -> `107.178.246.124`, Gateway `Programmed`/`GatewayHealthy`, certificate `ACTIVE`, but no answer yet ~9 min after the Gateway was created when the rebuild was cut short |
| `gke-teardown.sh` | Failed once on problem 19, succeeded on re-run. Cloudflare `A` record removed via the API. NEG cleanup: nothing left (problem 14 fix works). Disk cleanup: missed 2 disks (problem 20). After manual cleanup: no cluster, SQL, instance, disk, NEG, address, forwarding rule, router or VPC; kept: `api-images`, 5 secrets, `api-cert`, service accounts, WIF pool `github` |

## Open items

- **Polaris findings to work through**: harden the `observability` workloads (the same gap Kyverno
  audits), and review Argo CD's exec/attach and `cluster-admin` rights (upstream defaults).

- **HTTPS on `api.miqui.dev`**: certificate now `ACTIVE`, but a working HTTPS response still hasn't
  been seen - on the next build, allow 10-15 min after the Gateway is `Programmed` and run
  `./test-api.sh`.
- **Rotate exposed credentials**: the Argo CD initial admin password and the Grafana/OpenObserve
  passwords were printed during this session - change the Argo CD one in the UI (then delete
  `argocd-initial-admin-secret`), and the others in 1Password followed by `gke-secrets-seed.sh`.
- **Single-replica Hazelcast vs. the autoscaler**: every node scale-down that evicts it causes a
  cache outage (the API now recovers, problem 12). Options if it matters: a
  `cluster-autoscaler.kubernetes.io/safe-to-evict: "false"` annotation on the pod, or more members.
- **API authentication**: none yet - the API and `/docs` are public.
- **Hazelcast's JMX agent** is downloaded from Maven Central on every start, which is the only
  reason the `default` namespace allows egress to the internet (port 443); baking the jar into an
  image would remove that rule.
- **Next teardown**: confirm the problem 20 disk filter deletes the `pvc-` disks and the problem 19
  wait works (NEG cleanup is confirmed).

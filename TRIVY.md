# Trivy Operator — Vulnerability & Misconfiguration Scanning

[Trivy Operator](https://github.com/aquasecurity/trivy-operator) scans the cluster continuously and
stores the results as Kubernetes objects: every workload's images are scanned for CVEs and
baked-in secrets, and workloads, Services, Ingresses, Roles etc. are checked for misconfigurations.
Summary counts are exported as Prometheus metrics and drawn by the **Trivy Security** Grafana
dashboard (<http://localhost:3000/d/trivy-security> via `./gke-port-forward.sh`). Every command below was run against the
live cluster.

## How it's installed

| Piece | Where |
| --- | --- |
| Argo CD Application `trivy-operator` | `k8s/argocd/apps/trivy-operator.yaml` (sync wave -1) — upstream Helm chart `aqua/trivy-operator` **0.36.0** (operator v0.34.0, Trivy 0.74.0) |
| Chart values | `k8s/trivy-operator/trivy-operator-values.yaml`, pulled from `main` via a `$values` ref |
| Registration | the `root` Application (app of apps), like every other component |
| Metrics scrape | job `trivy-operator` in `k8s/observability/config/prometheus.yml` |
| Dashboard | `trivy-security.json` in `k8s/observability/grafana-dashboard-json-configmap.yaml` |

Everything runs in the `trivy-system` namespace (created by Argo, `CreateNamespace=true`): the
operator, the `trivy-server` StatefulSet, and the scan jobs, scheduled wherever there's room. The
`node-collector` job runs on each node it inspects.

Like every component here, Argo owns this chart end to end: bump `targetRevision` or edit the
values file, merge, and Argo rolls it out.

### Choices worth knowing

- **Trivy server mode** (`operator.builtInTrivyServer: true`). The `trivy-server-0` pod downloads
  the vulnerability DB once (onto its 5Gi PVC) and scan jobs query it, instead of every job pulling
  the ~80MB DB itself. Databases, all via `mirror.gcr.io`: `aquasec/trivy-db` (CVEs),
  `aquasec/trivy-java-db` (JAR → Maven mapping) and `aquasec/trivy-checks:1` (the Rego checks behind
  the misconfiguration/RBAC/compliance reports). Override them with `trivy.dbRegistry`/`dbRepository`,
  `trivy.javaDb*` and `policiesBundle.*` for a mirror.
- **`scanJobsConcurrentLimit: 3`** (chart default 10) so the first full-cluster pass doesn't starve
  the API pods on a 3-node cluster. That first pass takes a few minutes.
- **`excludeNamespaces: kube-system,gke-managed-system,gke-managed-cim,gmp-public`**: GKE's own
  images are noise (and not ours to fix). The cost is that `InfraAssessmentReports` for pods in
  `kube-system` are never produced (GKE's control plane isn't in the cluster anyway). Node checks still run as `ClusterInfraAssessmentReports`, but the operator exports no
  metric for them, so they're kubectl-only (below).
- **Default low-cardinality metrics.** The per-CVE-ID metric (`metricsVulnIdEnabled`) and the
  `*Info` metrics are off, so the dashboard shows counts, not CVE IDs. Use the reports for detail.
- **Not remote_written to OpenObserve.** The `trivy-operator` job is scraped by Prometheus only,
  not added to the `remote_write` keep list (see `PROMETHEUS.md` for the MemTable overflow history).
- **Kyverno** policies only cover `default`, `observability` and `headlamp`, so nothing in
  `trivy-system` needs an image allowlist entry or exception.

## Is it working? (start here)

```bash
kubectl get applications -n argocd trivy-operator      # Synced / Healthy
kubectl get pods -n trivy-system                        # trivy-operator + trivy-server-0 Running; scan-* / node-collector-* Completed
kubectl get vulnerabilityreports -A --no-headers | wc -l   # grows during the first few minutes
```

In Prometheus (`kubectl port-forward -n observability svc/prometheus 9090:9090`, then
<http://localhost:9090/targets>) the `trivy-operator` target should be `UP`. It scrapes `trivy-operator.trivy-system.svc.cluster.local:8080`: the chart's
Service is headless, so its port-80 mapping never applies and the pod port is used directly.

## Reading the reports

```bash
# CVEs per container image (one report per workload container), with severity counts
kubectl get vulnerabilityreports -n default -o wide

# The actual Critical/High CVEs in one report: severity, CVE, package, installed, fixed-in
kubectl get vulnerabilityreport -n default <report-name> -o json | jq -r \
  '.report.vulnerabilities[] | select(.severity=="CRITICAL" or .severity=="HIGH")
   | [.severity, .vulnerabilityID, .resource, .installedVersion, .fixedVersion] | @tsv'

# Secrets baked into image layers
kubectl get exposedsecretreports -A -o wide

# Misconfigurations per resource (workloads, Services, Ingresses, PVCs, ...)
kubectl get configauditreports -n default -o wide
kubectl get configauditreport -n default <report-name> -o json | jq -r \
  '.report.checks[] | select(.success==false) | [.severity, .checkID, .title] | @tsv'

# RBAC: which role a report is about, and its finding counts
kubectl get rbacassessmentreports -A -o custom-columns=NS:.metadata.namespace,REPORT:.metadata.name,ROLE:.metadata.labels.trivy-operator\\.resource\\.name,CRIT:.report.summary.criticalCount,HIGH:.report.summary.highCount
kubectl get clusterrbacassessmentreports

# Node checks (kubelet config, file permissions) - no metric, kubectl only
kubectl get clusterinfraassessmentreports -o wide

# Compliance specs: CIS 1.23, NSA 1.0, PSS baseline/restricted
kubectl get clustercompliancereports
```

Report names are `<kind>-<name>[-<container>]`, e.g.
`replicaset-job-manager-api-<hash>-job-manager-api`. Very long names are replaced by a hash
(`role-554cf6fccd`). The `trivy-operator.resource.*` labels on the report always hold the real
kind/name/namespace. Reports are re-created when the workload's pod template changes (new image
tag), and after `operator.scannerReportTTL` (24h), which picks up vulnerability DB updates.

To force a re-scan of one workload, delete its report: `kubectl delete vulnerabilityreport -n
<ns> <report-name>`.

## The dashboard

**Trivy Security** (`uid: trivy-security`). Filters: **Namespace** and **Severity**. The stat
tiles are fixed to one severity each, and the two tables always show all four severities as columns.

| Panel | Source metric |
| --- | --- |
| Critical / High / Medium / Low tiles | `trivy_image_vulnerabilities` (per workload container, so an image used by two workloads counts twice) |
| Exposed secrets | `trivy_image_exposedsecrets` |
| Failed config checks (Crit+High) | `trivy_resource_configaudits` |
| Vulnerabilities by namespace / over time | `trivy_image_vulnerabilities` by namespace/severity |
| Most vulnerable images | `max by (image)` of the above (deduped by image), plus a workload count |
| Misconfigurations by resource | `trivy_resource_configaudits` by resource |
| RBAC findings by severity | `trivy_role_rbacassessments` + `trivy_clusterrole_clusterrbacassessments` |
| Cluster compliance | `trivy_cluster_compliance` (Pass/Fail control counts per spec) |

**Cluster compliance shows 0 / 0** until the first scheduled run: specs are evaluated on
`compliance.cron` (chart default `0 */6 * * *`, UTC), not at install.

## Status and limits

- **Private image access.** The API image lives in Artifact Registry. Scan jobs run as KSA
  `trivy-system/trivy-operator`; `gke-deploy.sh` grants that Workload Identity principal
  `roles/artifactregistry.reader` on the repo, and Trivy uses the pod's token from the GKE
  metadata server. Without the grant every `job-manager-api` scan fails with
  `DENIED: Permission 'artifactregistry.repositories.downloadArtifacts' denied` and there is no
  VulnerabilityReport for the API.
- **Accepted risk: 3 High CVEs in the API image (reviewed 2026-10-05).** CVE-2026-68494,
  CVE-2026-89407 and CVE-2026-89425 are denial-of-service bugs in a copy of jackson-core 2.17.2
  that Hazelcast bundles inside its client jar (`hazelcast-5.5.0.jar`). The app's own
  jackson-core is 2.21.7, which has the fixes. A Maven version override can't replace a copy
  bundled inside another jar, and the only newer client, 5.7.0, is worse: it bundles
  jackson-core and jackson-databind 2.21.2 plus Jackson 3.1.2, for 13 Highs instead of 3
  (both jars scanned locally with Trivy 0.74.0). So the client stays on 5.5.0, and the findings
  stay visible in the VulnerabilityReport and on the dashboard. They are not suppressed in
  Trivy, so they can't be forgotten. **Revisit** when Hazelcast ships a client that bundles
  jackson-core >= 2.21.7 (and, if it bundles them, jackson-databind >= 2.21.7 and Jackson 3
  >= 3.1.7). Check with:

  ```bash
  curl -s https://repo1.maven.org/maven2/com/hazelcast/hazelcast/maven-metadata.xml | grep -oE "<version>5\.[0-9.]+</version>" | tail -3
  curl -sfLo hz.jar https://repo1.maven.org/maven2/com/hazelcast/hazelcast/<v>/hazelcast-<v>.jar && mkdir -p hz && mv hz.jar hz/
  docker run --rm -v "$PWD/hz:/scan" aquasec/trivy:0.74.0 rootfs --quiet --scanners vuln --severity HIGH,CRITICAL /scan
  ```
- **OpenObserve is not scanned** (`trivyOperator.excludeImages: "o2cr.ai/openobserve/*"`): its
  registry redirects to public ECR, which refuses Trivy's pull, so the job only ever failed.

- Counts only on the dashboard. Enabling `operator.metricsVulnIdEnabled` adds one series per CVE
  per workload (thousands here), which would need its own cardinality check.
- Findings don't block anything: Trivy reports, Kyverno enforces. Gating deploys on scan results
  (for example a Kyverno policy on `VulnerabilityReport` counts, or a Trivy step in CI) is not set up.
- No alerting rules yet. The metrics are there for an alert on new Critical CVEs.

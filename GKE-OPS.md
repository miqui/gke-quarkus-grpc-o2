# GKE Operations Notes

Short explanations of cluster events that look alarming but aren't. The cluster is zonal,
`e2-standard-2` workers, node autoscaling 3..5 (`gke-deploy.sh`).

## "Cluster Autoscaler skipped scaling up default-pool" for `node-collector-*`

Seen as a `NotTriggerScaleUp` note, e.g.:

> The pod node-collector-6cdc7f778f-265nh has a nodeSelector or nodeAffinity block configured...
> Because the default-pool node pool does not carry the matching labels... the Cluster Autoscaler
> skipped scaling up this pool.

**Benign - no action needed.**

- `node-collector` is Trivy Operator's per-node scan job ([TRIVY.md](TRIVY.md)). The operator pins
  each one to a single node with a `kubernetes.io/hostname` nodeSelector.
- A pod pinned to one node cannot run on any other, and a new node has a different hostname, so
  adding nodes never helps. The autoscaler correctly reports this and skips the scale-up.
- It typically appears when the target node is being removed. The first scan pass scans every
  workload at once and can briefly add a node; once idle, the autoscaler scales it back down
  (`ScaleDown ... marked the node as toBeDeleted/unschedulable`). A collector job pinned to that
  node becomes unschedulable and is deleted with it.
- The `NodeRegistrationCheckerDidNotRunChecks` warning on such a node is noise; its message says
  the node was ready and registered.

### Verify

```bash
kubectl get nodes                                             # one node Ready,SchedulingDisabled = scale-down in progress
kubectl get events -A --sort-by=.lastTimestamp | grep -E "node-collector|ScaleDown"
kubectl get pods -A --field-selector=status.phase=Pending     # expect none
kubectl get pods -n trivy-system                              # operator + trivy-server-0 Running
```

If no `node-collector-*` job shows up after the next scan cycle, check
`kubectl logs deploy/trivy-operator -n trivy-system`.

## gcloud checks for a fresh cluster

Read-only commands used to validate a rebuild (`gke-deploy.sh` + `gke-bootstrap.sh`). Project
`k8s-dev-412419`, zone `us-central1-a`, cluster `dev-cluster`.

```bash
export PROJECT=k8s-dev-412419 ZONE=us-central1-a CLUSTER=dev-cluster

# Before a rebuild: confirm the account, project, and that nothing is left over
gcloud auth list
gcloud config get-value project
gcloud container clusters list --project $PROJECT             # empty = fresh start
gcloud sql instances list --project $PROJECT                  # empty = no leftover Cloud SQL

# CI prerequisites (shared WIF provider, this project's CI service account)
gcloud projects describe $PROJECT --format='value(projectNumber)'
gcloud iam workload-identity-pools providers describe github-oidc \
  --workload-identity-pool=github --location=global --project $PROJECT --format='value(name,state)'
gcloud iam service-accounts describe ci-springboot-grpc-o2@$PROJECT.iam.gserviceaccount.com \
  --format='value(email)'

# Secrets External Secrets reads (seeded by gke-secrets-seed.sh)
gcloud secrets list --project $PROJECT --format='value(name)'

# Image pushed by CI (Argo CD Image Updater picks the highest tag)
gcloud artifacts docker images list \
  us-central1-docker.pkg.dev/$PROJECT/springboot-grpc-o2/job-manager-api \
  --include-tags --format='value(tags,createTime)'

# Node pool: machine type, boot disk, initial size; and the actual per-node disks
gcloud container node-pools describe default-pool --cluster $CLUSTER --zone $ZONE --project $PROJECT \
  --format='value(config.diskSizeGb,config.diskType,config.machineType,initialNodeCount)'
gcloud compute disks list --project $PROJECT --filter="name~gke-$CLUSTER" \
  --format='table(name,sizeGb,type.basename())'

# Public edge: certificate state and the API's static IP
gcloud certificate-manager certificates describe grpc-cert --format='value(managed.state)'
gcloud compute addresses describe grpc-ip --global --format='value(address)'
```

## Why the cluster runs 4 nodes, not 3

Observed on the 2026-10-05 rebuild: `TriggeredScaleUp ... 3->4 (max: 5)` caused by a Trivy
`scan-vulnerabilityreport-*` pod. The autoscaler works on **requests**, not usage: with the full
platform synced, the three `e2-standard-2` nodes (~1.93 CPU allocatable each) were 83-93%
CPU-requested, so the scan pod fit nowhere. Total requests (~6.5 CPU) exceed three nodes, so the
fourth node stays after the scans finish. To get back to 3, lower platform CPU requests or limit
Trivy's concurrent scan jobs.

```bash
kubectl get events -A --field-selector reason=TriggeredScaleUp \
  -o custom-columns='T:.lastTimestamp,NS:.metadata.namespace,OBJ:.involvedObject.name,MSG:.message'
kubectl describe nodes | grep -A8 "Allocated resources" | grep -E "cpu|memory"
```

## Symptoms seen on the first rebuild (2026-10-05) and their fixes

### API pods `Init:CreateContainerConfigError` for ~10 minutes after bootstrap

`secret "postgres-credentials" not found`, then `couldn't find key privateIP in Secret
default/cloudsql-connection`. **Benign:** Crossplane is still creating Cloud SQL, and the pods
start on their own once the instance and its connection secret exist. `postgres-exporter` shows
the same error for the same reason. A one-off Kyverno `disallow-latest-tag` audit event on the
first pods is also expected: they start from `:latest` until Image Updater pins the CI tag.

```bash
kubectl get postgresinstance -A                 # READY False -> True when Cloud SQL is up
```

### Trivy: no VulnerabilityReport for the `job-manager-api` container

Scan jobs failed with `DENIED: Permission 'artifactregistry.repositories.downloadArtifacts' denied
on resource ... repositories/springboot-grpc-o2`. Scan jobs authenticate with the Workload
Identity token of KSA `trivy-system/trivy-operator`, and that principal had no access to the repo.

**Fix:** `gke-deploy.sh` grants it `roles/artifactregistry.reader` on the repo. On an existing
cluster, run the same binding by hand:

```bash
gcloud artifacts repositories add-iam-policy-binding springboot-grpc-o2 --location=us-central1 --project=k8s-dev-412419 --role=roles/artifactregistry.reader --member="principal://iam.googleapis.com/projects/220906294299/locations/global/workloadIdentityPools/k8s-dev-412419.svc.id.goog/subject/ns/trivy-system/sa/trivy-operator"
```

Keep it on one line: a terminal that wraps a long command can add line breaks when you copy it,
and the shell then runs the pieces as separate commands (`argument --member --role: Must be
specified`).

The next attempt got past the registry and was **OOMKilled**
(`"container":"job-manager-api","status.reason":"OOMKilled"` in the operator log): the Quarkus
fast-jar is ~150 jars, and the 500M chart default isn't enough to analyse them.
**Fix:** `trivy.resources.limits.memory: 1Gi` in `k8s/trivy-operator/trivy-operator-values.yaml`,
with the request left at 100M so scheduling is unchanged.

**Force a rescan.** Reports are labelled with the **ReplicaSet** name, not the Deployment
name, so `-l trivy-operator.resource.name=job-manager-api` matches nothing:

```bash
RS=$(kubectl get rs -n default -l app=job-manager-api -o jsonpath='{.items[?(@.status.replicas>0)].metadata.name}')
kubectl delete vulnerabilityreports -n default -l trivy-operator.resource.name=$RS
kubectl get vulnerabilityreports -n default -o wide | grep job-manager-api   # expect a row for the job-manager-api container
kubectl logs -n trivy-system deploy/trivy-operator --since=10m | grep '"container":"job-manager-api"'   # errors, if any
```

### Trivy: OpenObserve scan jobs fail on every attempt

`unable to find the specified image "o2cr.ai/openobserve/openobserve:v0.92.2" ... GET
https://public.ecr.aws/v2/zinclabs/openobserve/manifests/v0.92.2: DENIED: Not Authorized`.
`o2cr.ai` redirects to public ECR, which refuses Trivy's pull (the kubelet pulls fine).
**Fix:** we don't scan it: `trivyOperator.excludeImages: "o2cr.ai/openobserve/*"`.

### Prometheus OOMKilled once during bootstrap

`Last State: Terminated, Reason: OOMKilled` on the prometheus pod, preceded in its log by
`Failed to send batch, retrying` and `Remote storage resharding from=1 to=44`. OpenObserve wasn't
accepting writes yet, so remote_write scaled up to 44 shards, each buffering samples in memory,
and Prometheus went past its 512Mi limit. **Fix:** `queue_config` caps remote_write at 4 shards
x 2500 samples (`k8s/observability/config/prometheus.yml`), and the memory limit is now 768Mi.
See PROMETHEUS.md section 7.

```bash
kubectl get pod -n observability -l app=prometheus          # RESTARTS 0
kubectl describe pod -n observability -l app=prometheus | grep -A3 "Last State"
```

### Argo CD `observability` stuck OutOfSync after a Prometheus config change

Message: `one or more objects failed to apply, reason: configmaps "prometheus-config-<old-hash>"
not found. Retrying attempt #N`. The Prometheus config is a `configMapGenerator` ConfigMap, so
every change gives it a new name. The new ConfigMap and Deployment were applied fine and
Prometheus was running the new config. But the self-heal sync had listed the *old* ConfigMap
among its resources, and once that was pruned, every retry failed on it. A hard refresh doesn't
clear it, because the running operation keeps its resource list.

**Fix:** terminate the operation, then start a normal sync, in the Argo CD UI (*Terminate*, then
*Sync*) or:

```bash
kubectl patch application observability -n argocd --type merge -p '{"status":{"operationState":{"phase":"Terminating"}}}'
kubectl patch application observability -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{"revision":"main","prune":true}}}'
kubectl get application observability -n argocd   # Synced / Healthy
```

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

# Review Sections

## Completed work

All four confirmed defects from the original gRPC API review are fixed:

- PR #2: stale embedded authors in cached messages and cache-fill races after message updates/deletes.
- PR #3: unbounded author-message responses and NUL input reaching PostgreSQL.
- PR #4: repository-owned JaCoCo, SpotBugs, and PMD quality gates run on PRs to `main`; making them merge-blocking requires applying `quality/main-protection.json`.

## Remaining review and improvement areas

These are review scopes and improvement opportunities, **not confirmed defects**.
The order below is the recommended review sequence.

| Order | Section | Focus |
| --- | --- | --- |
| 1 | **GKE infrastructure** | Bootstrap/deploy/teardown safety, networking, Gateway/TLS, IAM, probes, autoscaling, quotas, rollout behavior |
| 2 | **GitOps/platform** | Argo CD reconciliation, image updates and rollback, Crossplane provisioning, External Secrets, resource ownership |
| 3 | **Data/cache operations** | Migrations, indexes, connection pooling, recovery requirements, Hazelcast capacity and failure handling |
| 4 | **Observability** | Metrics/traces/log completeness, useful dashboards and alerts, cardinality, collector failures, retention costs |
| 5 | **Delivery/tooling** | Docker reproducibility, Compose, image-publishing workflow, policy checks, scanners, smoke/load-test scripts |
| 6 | **API test depth/resilience** | Improve real cache-adapter coverage; exercise lock contention, disconnects, timeouts, and concurrent mutations under load |
| 7 | **Security hardening** | Authentication/authorization, endpoint exposure, secrets, RBAC, network policies, supply-chain controls |

**Recommended next: GKE infrastructure**, followed by GitOps/platform.

## Deployment follow-through

Merged code does not confirm deployment or client adoption. Verify:

- The initial cache-locking cutover: stop old replicas and clear the Hazelcast `messages` map before starting replicas using the locking protocol.
- Clients needing all author messages follow the pagination continuation introduced in PR #3.

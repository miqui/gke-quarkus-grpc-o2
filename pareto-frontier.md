# Pareto Frontier Experiments

Plan for exploring **Pareto frontiers of runtime configurations** of `job-manager-api`: which JVM
garbage-collection settings, and which Quarkus build modes (JVM, JVM with startup caches, native),
are *non-dominated* when trading tail latency against CPU cost, memory footprint and startup time.
Nothing here is implemented yet; this records the ideas, the chosen first experiments, how the work
is organised, and what the project would need to change to take part.

Adapted from the Spring Boot service's plan
([gke-springboot-grpc-o2/pareto-frontier.md](https://github.com/miqui/gke-springboot-grpc-o2/blob/main/pareto-frontier.md)).
The method, the experiment backlog and the comparability rules carry over. Names, files, workload
and metrics are this repo's, and the native-mode material is new.

## Goal and decisions

**Goal: pick the production GC configuration for `job-manager-api`.** Reusing the harness for other
services is a possible follow-up, not a requirement, so the overlays and `gc-run.sh` may hard-code
this service's names and paths.

**Cost axis: CPU-seconds per 1k requests.** The HPA scales on CPU at 70% of a 250m request, and
anti-affinity puts one service pod on each node, so GC CPU turns into replicas and then into nodes.
Memory is a fit constraint, not a cost axis: no OOM kills, a working set below 80% of the limit,
and a limit that fits the namespace quota. Experiment 2 reports it as "the smallest limit that
meets the SLO".

**SLO: derived from the baseline, fixed before experiment 1.** No production target exists yet, so
the SLO means "no regression against today's configuration":

1. Calibrate the load: on the baseline overlay (1 replica, no HPA), find the arrival rate at which
   the pod uses about 175m CPU (70% of the 250m request, the point where the HPA would add a
   replica).
2. Measure the baseline 3 times at that rate. Take the median p99 of the 3 runs.
3. The SLO is: p99 at most **1.10 x that baseline median**, error rate under 1%, at the same
   arrival rate, judged on the median of 3 runs per variant.
4. Write the calibrated rate and the resulting p99 limit into the first results file and do not
   change them afterwards.

Limitation: this SLO only says a variant is no worse than today. If a real latency target appears
later, re-judge the existing results against it rather than re-running them.

## Why this service is a good test bed

- **The current GC is implicit.** No GC flag is set anywhere. The only JVM options are
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (plus tmpdir and log manager) in
  `JAVA_TOOL_OPTIONS` (`Dockerfile`), with a 768Mi memory limit and a 1500m CPU limit
  (`k8s/deployment.yaml`). The 1500m limit rounds up to 2 active processors, but with under ~1792MB
  of memory JDK 21 still treats the container as a client-class machine and selects **SerialGC**.
  Confirm with `kubectl exec deploy/job-manager-api -- jcmd 1 VM.flags | tr ' ' '\n' | grep 'Use.*GC'`.
  Making this visible is already a finding.
- **GC cost turns into replicas.** The HPA (`k8s/hpa.yaml`, 3-6 replicas) scales on CPU at 70% of
  a **250m request**, so GC CPU directly changes how many pods the service needs.
- **The handlers run on virtual threads.** `JobGrpcService` and `WorkerGrpcService` are
  `@RunOnVirtualThread`; a parked virtual thread's stack lives on the heap as stack chunks, so the
  collector sees more (and more short-lived) objects than with platform threads. This makes the
  collector choice matter more than it did for the Spring Boot service.
- **Quarkus offers more than one way to build the same code.** The same source can ship as a
  fast-jar on the JVM (today), a fast-jar with an AppCDS / AOT cache, or a native executable.
  Each is a different point on the startup / footprint / throughput trade-off, which is exactly
  what a frontier shows.
- **The tooling already exists:** k6 gRPC scenarios (`k6-*.js`), Prometheus/Grafana with
  Micrometer JVM metrics (bridged to OTel), short-lived GKE clusters, and Argo CD for rolling out
  each variant.

## Objectives (pick 2-3 per frontier)

| Axis | Metric | Source |
| --- | --- | --- |
| Tail latency | p99 / p99.9 of gRPC calls | k6 summary (`--summary-export`) |
| Throughput | sustained RPS at < 1% errors | k6 |
| CPU cost | CPU-seconds per 1k requests | `container_cpu_usage_seconds_total` / request count |
| Memory footprint | container working set; smallest limit without OOM | `container_memory_working_set_bytes` |
| GC pauses | max pause, total pause time; p99 pause from the GC log | `jvm_gc_pause_milliseconds_{sum,count,max}` (divide `_sum` by 1000 for seconds), `-Xlog:gc*` |
| Startup | time from main-container start to Ready | pod `Ready` condition minus the `job-manager-api` container's `startedAt` |
| Image (native only) | image size, build time | Artifact Registry, CI job duration |

A configuration is on the frontier if no other configuration is at least as good on every chosen
axis and strictly better on one.

Notes on the metrics in this repo:

- **The GC pause timer has no buckets.** The JVM binder's `jvm.gc.pause` timer is not built with
  `serviceLevelObjectives(...)` like the gRPC timers, so Prometheus only has `_count`, `_sum` and
  `_max`. Pause percentiles come from parsing `-Xlog:gc*` output, not from Prometheus.
- **Metrics are pushed, not scraped from the pod.** The app pushes OTLP to the OTel Collector every
  15s (`quarkus.otel.metric.export.interval`) and Prometheus scrapes the collector. Keep
  measurement windows much longer than 15s.
- **Prometheus has no PVC.** Its data is lost on restart, so the run script must write its scrape to
  a results file at the end of every run rather than relying on querying later.

## Experiment backlog

Listed cheap to ambitious. 1-7 are the original GC plan; 8-10 are Quarkus-specific.

**Run order for the production-GC goal:**

| Step | Experiment | Note |
| --- | --- | --- |
| 1 | 1 Collector shootout | After the baseline calibration in "Goal and decisions" |
| 2 | 2 Memory-limit sweep | Top 2-3 collectors from step 1 only |
| 3 | 6 Workload sensitivity | Finalists under read-heavy and write-heavy load |
| 4 | 3 CPU limit sweep | Only if a concurrent collector is still a finalist, or the CPU limits may change |
| 5 | 4 Fleet-level frontier | Confirms the finalist with the HPA on |
| 6 | 5 Collector knobs | Only if the winner misses the SLO |
| optional | 9 Startup caches, 10 JDK 21 vs 25 | 9 matters if HPA scale-up speed does; 10 only if a JDK bump is planned |
| later | 8 Native, 7 Automated search | Large build work; only if footprint or startup becomes the goal, or a reusable harness is wanted |

1. **Collector shootout** at fixed resources (768Mi, 250m/1500m): Serial (implicit and explicit),
   Parallel, G1, generational ZGC (`-XX:+UseZGC -XX:+ZGenerational` on JDK 21), Shenandoah.
   Frontier: p99 vs CPU-seconds per 1k requests.
2. **Memory-limit sweep** per collector: limit in {512, 768, 1024, 1536}Mi x `MaxRAMPercentage`
   in {50, 65, 75, 85}. Frontier: footprint vs p99, i.e. "the smallest pod that meets the SLO".
   This also answers the open question in `k8s/deployment.yaml` ("Quarkus needs less than the
   Spring Boot service did; kept as-is until load tests say otherwise"), so add 384Mi as a sweep
   point. The namespace quota (16Gi of limits) covers one 1536Mi replica plus Hazelcast.
3. **CPU limit x concurrent collectors:** sweep the CPU limit 500m-2000m and
   `-XX:ActiveProcessorCount`. Concurrent collectors (G1/ZGC/Shenandoah) need spare cores and can
   lose to Serial when throttled. The virtual-thread carrier pool is also sized from the processor
   count, so record it.
4. **Fleet-level frontier:** HPA enabled. Total cluster CPU and memory vs p99, e.g. 3 large G1 pods
   vs 6 small Serial pods (or many small native pods, see 8).
5. **Knobs within one collector:** G1 `MaxGCPauseMillis`, ZGC `SoftMaxHeapSize` / uncommit,
   Parallel `GCTimeRatio`. Each knob traces its own curve. Optional extra dimension: virtual vs
   platform threads for the gRPC handlers (heap-resident stacks vs fixed thread stacks).
6. **Workload sensitivity:** the same variants under `k6-list-jobs.js` (read-heavy, served partly
   from Hazelcast and the Caffeine `job-types` cache) and `k6-create-jobs.js` (write-heavy),
   with `k6-job-lifecycle.js` as the mixed default. Shows how the frontier moves with the workload.
   `k6-claim-contention.js` is excluded: it measures database row locking, not the JVM.
7. **Automated search:** a driver (e.g. Python + Optuna multi-objective / NSGA-II) proposes flags,
   rolls them out, runs k6, scrapes Prometheus and keeps the non-dominated set.
8. **Native series.** The same code built as a native executable, as its own series on the chart.
   In native mode the collector is chosen **at build time** (`--gc=serial` default; `--gc=epsilon`
   only as a bounded "no GC cost" reference run; `--gc=G1` needs Oracle GraalVM, not Mandrel), so
   each native collector is a separate image. Heap sizing is at run time (`-Xmx`, `-Xmn`), not
   `MaxRAMPercentage`. Sweep the memory limit lower than for the JVM, e.g. {128, 192, 256, 384}Mi.
   Expected shape: much better startup and footprint, lower peak throughput without JIT/PGO.
9. **JVM startup caches.** Quarkus can build an AppCDS archive
   (`quarkus.package.jar.appcds.enabled=true`) into the image, and on JDK 25 a Leyden AOT cache.
   Same GC variants, different startup and warm-up curve. This sits between "plain JVM" and
   "native" on the startup axis without giving up the JIT.
10. **JDK 21 vs JDK 25.** Generational ZGC is the only ZGC mode from JDK 24, and JDK 25 adds
    compact object headers (`-XX:+UseCompactObjectHeaders`), which shrink every object by ~4 bytes.
    Both move the footprint axis. Needs a JDK bump of the build and runtime images, so it comes
    after 1-2 have a stable baseline.

## How the work is organised

### `main` is the baseline

`main` is not modified for experiments. It defines the baseline configuration running today
(implicit Serial, 768Mi, 250m/1500m, JVM fast-jar). The baseline overlay keeps those settings but
uses one replica with the HPA removed (`main` runs 3-6 replicas), matching the capacity of
experiments 1-3. Tag `main` before the first run (`gc-baseline-v1`) so every result can cite the
exact commit it was measured against.

### One branch for the harness, not one per variant

GC settings are runtime configuration, not code: every JVM variant runs the **same image**. A
branch per variant would require re-pointing Argo CD's `targetRevision` for each run. The branches
would drift from each other, and any unrelated change on one of them would contaminate the
comparison. Instead, all variants live side by side on a single branch (`exp/gc-pareto`), which
merges through a PR like any other work:

```
k8s/experiments/gc/
  baseline/            # main's settings; one replica and no HPA to match variants
  serial-explicit/
  parallel/
  g1/
  zgc/
  shenandoah/
  mem-512-p75/ ...     # experiment 2 sweep points
  native-serial/ ...   # experiment 8: different image, see "Native mode" below
experiments/gc/results/<variant>-<timestamp>.json
scripts/gc-run.sh
scripts/gc-frontier.py
```

Each overlay uses `../../..` (the `k8s/` base) as its resource and patches only:

- **`JDK_JAVA_OPTIONS`** in the `env:` of the `job-manager-api` container, e.g.
  `-XX:+UseZGC -XX:+ZGenerational -Xlog:gc*:stdout:time,uptime,level,tags`. The image's entrypoint
  is a plain `java -jar /app/quarkus-run.jar` (not Quarkus' `run-java.sh` image, so not
  `JAVA_OPTS_APPEND`), and the `java` launcher reads `JDK_JAVA_OPTIONS` in addition to the
  Dockerfile's `JAVA_TOOL_OPTIONS`. The heap percentage and OOM behaviour stay in place and no image
  rebuild is needed. (For experiment 2 the overlay also sets `-XX:MaxRAMPercentage`; launcher
  options win over `JAVA_TOOL_OPTIONS`.) The GC log goes to stdout as plain text, next to the JSON
  application logs; the run script separates them by prefix.
- **`resources`** of the `job-manager-api` container, for the memory and CPU sweeps.
- For experiments 1-3: **`replicas: 1`** and the HPA removed, so autoscaling doesn't hide the GC
  effect.

Set the flags in the container's `env:`, not in `job-manager-api-config`. That ConfigMap is a plain
resource (no `configMapGenerator` hash suffix, no reloader) loaded with `envFrom`, so editing it
syncs in Argo CD but does not restart the pods, and the run would measure the previous flags. A
change to the pod template's `env:` forces a rollout.

Hazelcast (`k8s/hazelcast-deployment.yaml`) is a separate JVM pod and is **not** under test: no
overlay touches it.

### Run script (`scripts/gc-run.sh <variant>`)

1. Point the `job-manager-api` Application's `spec.source.targetRevision` at the experiment branch
   (`exp/gc-pareto`) and its `spec.source.path` at `k8s/experiments/gc/<variant>`. The Application
   tracks `main` and `path: k8s`, where the overlays don't exist, so the path alone is not enough.
2. Reset the data: truncate the jobs tables (or restore a fixed seed) so each run starts against
   the same table sizes. Lifecycle and create runs add rows, and without a reset later variants run
   against a bigger table.
3. Wait for the rollout and for readiness, and record the startup time measured from the main
   container's start (the two init containers wait for Cloud SQL and Hazelcast, and that wait has
   nothing to do with the variant).
4. Warm up: 2 minutes of load, discarded (JIT and heap sizing settle; native has no JIT but its
   heap still grows).
5. Measure: a fixed k6 scenario at a fixed arrival rate (`constant-arrival-rate`, so slower
   variants can't reduce the offered load), 10 minutes.
6. Scrape Prometheus for the window and write `experiments/gc/results/<variant>-<ts>.json` with the
   variant, git SHA, image digest, node name, Hazelcast pod and node, k6 summary, metrics, Cloud SQL
   latency for the window (postgres-exporter / Agroal pool wait) and the parsed GC log.
7. Repeat each variant at least 3 times, in shuffled order across variants. Report the median and
   spread; frontier points are medians.

`scripts/gc-frontier.py` reads all result files, computes the non-dominated set for the chosen axes
and plots it, with the baseline highlighted.

### Comparability rules

- **Same image for every JVM run.** Pause Argo CD Image Updater during a sweep so a new build can't
  roll out mid-experiment. Pinning a digest in the overlay is not an equivalent alternative: Image
  Updater writes its override into the `job-manager-api` Application's `spec.source.kustomize`,
  which can supersede the overlay's pin. Native variants are separate images by design (experiment 8);
  build each once and record its digest.
- **Argo CD self-heal:** the root Application (`k8s/argocd/root-application.yaml`) self-heals its
  child Applications and only ignores `/spec/source/kustomize`. It would therefore revert a change
  to `/spec/source/path`. The root Application tracks `main`, so an `ignoreDifferences` edit on the
  experiment branch has no effect. Either add `/spec/source/path` (and `/spec/source/targetRevision`)
  to the root Application's `ignoreDifferences` on `main`, or turn off auto-sync on the root app for
  the duration of a sweep.
- **Fixed load source:** run k6 from the same machine and network each time, or better, as an
  in-cluster Job, so the public Gateway (`grpc.miqui.dev`) and home network don't add noise to p99.
  The k6 scripts currently use `vus`/`duration`; they need a `constant-arrival-rate` option first.
- **Cloud SQL is shared and external.** Every call does JDBC against Cloud SQL (pool max 10,
  `DB_POOL_MAX`). Record database latency for each run so a slow database isn't blamed on the GC,
  and keep the pool size fixed across variants.
- **Hazelcast held constant:** same resources and node placement for every run; note its pod in the
  result.
- **Same node type** (`e2-standard-2`), with one service pod per node (anti-affinity is already
  set). Note the node in the result.
- **Trace sampling fixed** at the configmap's 10% (`TRACES_SAMPLING_PROBABILITY`); span creation
  allocates, so changing it between runs changes the GC load.

## Native mode: what the project would need

None of this exists yet. It is the work needed before experiment 8 can run, in rough order.

| Area | Today | Needed for native | Risk |
| --- | --- | --- | --- |
| Build | `Dockerfile` builds a fast-jar on `eclipse-temurin:21` | A `Dockerfile.native` (or `-Dnative` CI job) using the Mandrel builder image, runtime on a UBI micro or distroless base; `--gc` as a build arg | native-image needs several GB of RAM and minutes of build time; check CI runner limits |
| Image naming | one image, `job-manager-api`, tracked by Image Updater | a separate repository (e.g. `job-manager-api-native`) so Image Updater never rolls a native image into the JVM Application | low |
| Hazelcast client | plain `com.hazelcast:hazelcast` 5.5 client, built in `CacheConfig` | either the Quarkiverse Hazelcast client extension or reflection/serialization config generated with the native-image tracing agent | **highest**: the plain client relies on reflection and service loading; verify extension support for 5.5 |
| Protobuf JSON | `Json` uses `protobuf-java-util` `JsonFormat` for `spec`/`result` | verify in a native integration test | medium |
| Flyway, Agroal, PG JDBC, gRPC, Health, Scheduler, Cache | Quarkus extensions | supported in native by the extensions | low |
| Virtual threads | `@RunOnVirtualThread` | supported in GraalVM/Mandrel for JDK 21 | low; check pinning under load is no worse |
| Metrics | Micrometer JVM binder via OTel bridge | works, but native reports fewer `jvm_*` series (GC metrics limited); rely on container metrics and k6 | low |
| JVM options | `JAVA_TOOL_OPTIONS` in the Dockerfile | ignored by a native executable; heap via `-Xmx`/`-Xmn` args in the overlay; check `-XX:+ExitOnOutOfMemoryError` support | medium |
| Tests | `@QuarkusTest` (`JobServiceTest`, `WorkerServiceTest`, `PlatformTest`) | `@QuarkusIntegrationTest` subclasses run against the native binary under `-Dnative`, plus failsafe config | low |
| Policies | Kyverno, Trivy scan the JVM image | the new base image must pass the same policies (non-root uid 10001, read-only root FS, CVE scan) | low |
| Probes | startup budget 125s for a cold JVM | unchanged (it doesn't affect the measurement); can be tightened later if native is adopted | none |

The JVM-mode Quarkus features (experiment 9) are much cheaper: AppCDS is a build property and an
extra step in the existing `Dockerfile`, and needs no code changes.

## Cross-framework frontier

With the Spring Boot service measured the same way, Spring Boot (JVM) vs Quarkus (JVM) vs Quarkus
(JVM + AppCDS) vs Quarkus (native) can be plotted on one chart. Caveat: the two services no longer
have the same API (messages vs jobs), so compare per-request CPU and memory for the closest
operations (create, get, list), not overall numbers.

## Next steps

1. Tag `main` as `gc-baseline-v1`, verify the implicit SerialGC on a running pod.
2. Add a `constant-arrival-rate` option to the k6 scripts (`k6-common.js`) and a data-reset step.
3. On `exp/gc-pareto`: the overlays for experiment 1 and `gc-run.sh`. On `main`: the root-app
   `ignoreDifferences` change.
4. Calibrate the baseline (see "Goal and decisions"): find the arrival rate, run it 3 times, and
   record the rate and the p99 limit. Agree on the load scenario and durations at the same time.
5. Run experiment 1, then experiment 2. Write up the frontiers here or in a results document.
6. In parallel: a native-build spike (Hazelcast client first, since it decides whether experiment 8
   is cheap or expensive), and the AppCDS build option for experiment 9.

# Experiment 2 (memory-limit sweep): Grafana observations during repeat 1

Taken 2026-10-07 at about 14:05 EDT, while repeat 1 of the reduced sweep was running (serial-384 in
progress). Time range 13:00 EDT to now. Charts were captured from the cluster's Grafana through a
port-forward, from the "job-manager-api API" and "API RED & Saturation" dashboards.

Plan: `pareto-frontier.md` (experiment 2). Results so far: `experiments/gc/results/mem-*.json`.
Experiment 1 summary: `experiment-1-collector-shootout.md`.

## Screenshots

| File | Dashboard |
| --- | --- |
| `results/grafana/exp2-pass1-job-manager-api.png` | job-manager-api API: request rate, average latency, JVM memory by pod, errors |
| `results/grafana/exp2-pass1-api-red.png` | API RED & Saturation: p99, container memory vs limit, CPU throttling, pod health |

## Reading the charts against the runs

Each run changes the pod template, so each run has its own pod name (`job-manager-api-<hash>-<suffix>`)
and its own line in the per-pod panels.

| Time (EDT) | Run | Pod hash |
| --- | --- | --- |
| 13:07-13:21 | parallel-384 | `bcf4457d` |
| 13:21-13:24 | gap: Image Updater had come back, so 3 runs failed their pre-run check and the cluster returned to main (3 replicas + HPA, the `6865977bf7` pods) | |
| 13:24-13:38 | parallel-512 | `5f85b4f9d` |
| 13:38-13:54 | serial-512 | `6cf5f88464` |
| 13:54- | serial-384 | `5b74754b76` |

## Results of repeat 1 (one run per point; 75 ms k6 p99 limit)

| Point | k6 p99 | Server p99 | CPU-s / 1k req | Peak working set | Max GC pause | Node | SLO |
| --- | --- | --- | --- | --- | --- | --- | --- |
| parallel-384 | 80.2 ms | 19.3 ms | 2.61 | 275 MiB | 314 ms | cx1n | fail |
| parallel-512 | 92.6 ms | 24.4 ms | 3.38 | 274 MiB | 328 ms | q29m | fail |
| serial-512 | 76.1 ms | 20.9 ms | 2.70 | 270 MiB | 22 ms | cx1n | fail |
| serial-384 | in progress | | | | | | |

The 768Mi points are the experiment 1 runs (MaxRAMPercentage is 75 there too): Serial 62.9 ms and
Parallel 58.6 ms median k6 p99.

## Findings

1. **GC is not the cost driver.** GC pause share is at most about 0.45% of wall time (worst:
   parallel-512). The dashboard's "GC Pause Time Share" reads 0.22% for the latest run.
2. **The heap is a small part of the pod.** Heap in use is about 25-60 MiB. Non-heap (metaspace, code
   cache) is about 110-128 MiB, per the JVM Memory by Pod panel. The container working set is
   255-275 MiB, so about 80-100 MiB sits outside what the JVM reports (thread stacks, direct buffers).
   `MaxRAMPercentage=75` is not binding at 384Mi (heap cap about 288M). The smallest pod that works is
   set by the non-heap footprint, not the heap.
3. **All three finished points fit in memory** (working set 270-275 MiB against 384 and 512Mi, no
   restarts) **but failed the p99 limit.** The failure is latency, not memory. This is unlike
   experiment 1 at 768Mi (58.6-62.9 ms), and the ordering is not monotonic in memory: parallel-512 is
   worse than parallel-384. That points to run-to-run or environment noise more than a memory effect.
4. **Node noise is the leading explanation for parallel-512.** It ran on a different node (`q29m`) than the
   other two (`cx1n`). Node CPU on one node reached about 67% from 13:31 to 13:36 (the parallel-512 run)
   while the others sat at 19-35%. The run also has the highest CPU (243m), a non-zero CPU throttling
   fraction, and the server-side p99 bumps in the Latency Percentiles panel (to about 38 ms around
   13:25-13:30 and 13:38). Not proven: the node-CPU series were unlabelled in the query.
5. **CPU throttling is a start-up effect only.** Throttling spikes to 25-45% in the first minutes of each
   new pod (JIT compilation) and falls to about 0. That is inside the excluded 2-minute warm-up.
6. **Clean otherwise:** zero errors, zero restarts, a flat 72 req/s during each measured window,
   DB pool steady.

## Consequences for the plan

- Repeat 2 pins the pod to one node (`cx1n`), to remove the node effect. The first repeat is unpinned
  and mixes two nodes; keep it separate when summarising.
- The sweep has no failing point yet, so the "smallest pod that meets the SLO" is not found. A lower
  memory point (256 or 320Mi) is needed to find where it breaks, probably from non-heap memory or an
  OOM kill.
- The dashboards have no marker for run boundaries or variant names. Use the pod hash table above, or
  the `meta.timestamp` in each result file.

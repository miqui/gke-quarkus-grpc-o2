# Grafana screenshots: what each one shows

All screenshots are in `results/grafana/`. They were captured from the cluster's Grafana (through a
port-forward) with headless Chrome, at the end of a run or at an interesting moment. Every
screenshot is one of two dashboards, and most come as a pair per run.

Times are EDT (the UTC `Z` timestamps in the file names are 4 hours ahead). A run's file name carries
the timestamp of its result file in `results/`, which is written when the run ends.

## The two dashboards

### `...-job-manager-api.png`: "job-manager-api API" (the short one)

| Panel | What it tells you |
| --- | --- |
| Ready Pods | Always 1 during an experiment (experiments pin 1 replica, no HPA). Main runs 3 to 6. |
| DB Pool Connections | open / busy / idle connections of the Hikari pool. Busy stays near 0: the database is not the bottleneck. |
| Process CPU Usage | CPU of the hottest pod, as a share of its CPU. The gauge is a snapshot, not an average of the run. |
| GC Pause Time Share | Share of wall time spent in GC pauses. 0.04% to 0.25% in these runs: GC pauses are not what limits latency. |
| Request Rate by Method | Requests per second for each gRPC method. Shows the workload shape and the gaps between runs. |
| Average Request Latency by Method | Mean latency per method (ms). Much lower than the p99 on the other dashboard. |
| JVM Memory by Pod | Heap (sawtooth: allocation and collection) and non-heap (Metaspace, code cache, steady line) per pod. One pair of lines per pod, and every run is a new pod name. |
| Error Rate by Method | Empty or "No data" means no errors. |

### `...-api-red.png`: "API RED & Saturation" (the long one)

Rate, errors and duration (RED), plus saturation of the container.

| Panel | What it tells you |
| --- | --- |
| Request Rate, p99 Latency, Error Ratio, DB Pool Busy | Four headline numbers. The p99 here is the server's, measured inside the API, so it is lower than the k6 p99 that the SLO uses (about 10 to 13 ms against 60 to 80 ms). |
| Request Rate by Method, Request Rate vs Error Rate | Same load shape as above, with the error line (flat at 0). |
| Latency Percentiles, Latency by Method (p95) | Server-side latency over time. Spikes at the start of a run are JIT warm-up and the data reset. |
| DB Connection Pool Utilization, DB Query Time | Pool use and the average query (about 1 to 1.5 ms) and connection-acquire time. |
| CPU Throttling Ratio | Share of CPU periods the container was throttled. It should be 0 during the measured window. High values at the start of a run are JVM start-up and warm-up. |
| Container Memory vs Limit | Working set of each pod against the limit line (768Mi for experiments 1 and 6, 384/512Mi in experiment 2). The gap is the headroom. |
| Pod Health | Restarts (15 min) and ready pods. A restart would mean an OOM kill or a crash. |

Reading tip: each run's pod has its own hash in the legends (`job-manager-api-<replicaset hash>-<suffix>`).
A rollout between runs shows as one pod's lines ending and another's starting, and as a dip to zero in
the request rate.

## Experiment 6 (workload sensitivity)

Notes: `experiment-6-workload-sensitivity.md`. Each screenshot covers a 25 minute window ending about 2
minutes after the run's result file, so it also shows the end of the previous run, the rollout and the
data reset. The measured window itself is the 10 flat minutes after the 2 minute warm-up.
Only the Serial baseline runs of the list workload and the create workload were captured as
they happened, plus the create sweep runs. The list sweep has no screenshots.

### Read-heavy (list, 14/s)

| Files (`exp6-list-wl-serial-...`) | Run | What to look at |
| --- | --- | --- |
| `20261007T203521Z-*` (16:35) | Baseline run 1 (SLO source) | The first measured run. Its Prometheus data was empty, so the CPU and server p99 numbers are missing from the results; the screenshot is the only place to see this run's server side. |
| `20261007T204753Z-*` (16:47) | Baseline run 2 (SLO source) | The cleanest picture of the list workload. `ListJobs` at about 42 req/s and `ListJobTypes` at about 14 req/s (84 requests/s in total) run flat for 10 minutes with 0 errors. Average latency is 3 to 4 ms. The dips at about 16:36 and 16:47 are the rollout and reset between runs. Heap is a regular sawtooth between about 35 and 75 MiB with non-heap at about 112 MiB. GC pause share 0.25%. The container working set sits at about 260 to 290 MiB, far under the 768Mi limit. CPU throttling is 0 once the warm-up ends. |
| `20261007T210027Z-*` (17:00) | Baseline run 3 (SLO source) | Same shape as run 2. Used to confirm that the three baseline runs agree (k6 p99 71.1, 59.0 and 68.4 ms). |

The first minutes of the first screenshot of each pair show higher latency (up to about 250 ms) and some CPU throttling.
That is the tail of the earlier activity (the data reset and the rollout), outside the measured window.

### Write-heavy (create, 40/s)

| Files (`exp6-create-wl-serial-...` and `exp6-create-wl-parallel-...`) | Run | What to look at |
| --- | --- | --- |
| `serial-20261007T211259Z-*`, `serial-20261007T212855Z-*` (17:12, 17:28) | Baseline runs 1 and 2 (SLO source) | `CreateJob` at a flat 40 req/s. Average latency is a few ms. |
| `serial-20261007T214448Z-*` (17:44) | Baseline run 3 (SLO source) | Third run that sets the 86 ms limit. |
| `serial-20261007T220048Z-*`, `serial-20261007T223520Z-*` (18:00, 18:35) | Sweep, Serial | Compare with the Parallel runs: the shapes are the same, which matches the 2% CPU difference. |
| `parallel-20261007T221739Z-*`, `parallel-20261007T225230Z-*`, `parallel-20261007T230826Z-*` (18:17, 18:52, 19:08) | Sweep, Parallel | In the 18:17 pair the heap sawtooth peaks at about 75 MiB and the non-heap line climbs from about 100 to about 120 MiB during the run. GC pause share is 0.036%. |

In the create screenshots there is a gap in `CreateJob` of about 4 minutes between two runs. During it
a method (the reset's `DeleteJob` calls) spikes to about 180 req/s while the parallel reset deletes
the roughly 28,800 jobs of the previous run. Average latency of that method reaches about 120 ms. This
is harness traffic, not workload: it is outside every measured window.

## Experiment 2 (memory-limit sweep, 384Mi and 512Mi)

Notes: `experiment-2-grafana-notes.md`, which has the pod-hash and run table and the explanation of the
lost run.

| File | What it shows |
| --- | --- |
| `exp2-pass1-job-manager-api.png`, `exp2-pass1-api-red.png` | Taken at about 14:05 during repeat 1 (unpinned nodes). Shows the first runs, the gap where Image Updater came back and 3 runs failed their pre-run check, and the lost serial-384 run. |
| `exp2-overview-both-repeats-job-manager-api.png`, `exp2-overview-both-repeats-api-red.png` | 13:00 to 15:15: all 8 runs of both repeats on one time axis. Each load phase is a plateau at about 70 req/s. Memory-limit line steps between 768Mi (main) and 384/512Mi (experiment pods). CPU throttling spikes sit at the start of each run (start-up and JIT); restarts stay at 0 and no pod nears its limit. This is the "no memory point fails down to 384Mi" evidence. |
| `exp2-mem-serial-384-p75-20261007T182729Z-*` (14:27) | Repeat 2, Serial at 384Mi (k6 p99 75.2 ms). |
| `exp2-mem-parallel-512-p75-20261007T184255Z-*` (14:42) | Repeat 2, Parallel at 512Mi (68.6 ms). |
| `exp2-mem-parallel-384-p75-20261007T185837Z-*` (14:58) | Repeat 2, Parallel at 384Mi (70.0 ms). |
| `exp2-mem-serial-512-p75-20261007T191421Z-*` (15:14) | Repeat 2, Serial at 512Mi (78.6 ms). |

Repeat 2 was pinned to one node. Compare it with the repeat 1 values in the notes: the p99 of the same
variant moved by about 20 ms between nodes, which is larger than any difference between the two GC
collectors.

## What none of the screenshots show

- The k6 p99 that the SLO uses. It comes from the k6 summary in the result files, not from Grafana.
- Experiment 1 runs and the experiment 2 low-memory runs (320/256Mi): none were captured.
- The measured window alone: each screenshot includes warm-up, reset and rollout, so read the numbers
  from the result files and use the screenshots for shape and context.

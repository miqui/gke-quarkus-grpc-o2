# Experiment 6: does the best GC collector depend on the workload?

Run 2026-10-07, 16:19 to 21:00 EDT, on the `dev-cluster` (3 x e2-standard-2), against `grpc.miqui.dev:443`.
Plan: `pareto-frontier.md` (experiment 6). Earlier results: `experiment-1-collector-shootout.md`
(mixed workload) and `experiment-2-grafana-notes.md`.

## Question

Experiment 1 compared the GC collectors under one mixed workload. Serial and Parallel were the only
frontier points. Does that change if the traffic is read-heavy or write-heavy?

Only Serial and Parallel were run, because the other three GC collectors were dominated in
experiment 1 (16 to 25% more CPU).

## Method

- Overlays `experiments/gc/overlays/wl-serial` and `wl-parallel`: 1 replica, no HPA, 768Mi memory,
  pinned to node `...-cx1n` (experiment 2 showed that the node alone can move p99 by about 20 ms).
- Two workloads, open model (constant arrival rate, 2 min warm-up, 10 min measured):
  - read-heavy: `k6-list-jobs.js` at 14/s (84 requests/s, about 2.1 CPU-s per 1k requests)
  - write-heavy: `k6-create-jobs.js` at 40/s (about 2.7 CPU-s per 1k requests)
- Rates came from a calibration (`results/exp6/calibration/`): they put the pod at a moderate load
  with no CPU throttling.
- Data was reset (every job deleted) before each run, by the parallel `k6-reset-jobs.js`.
- SLO, per workload and fixed before the sweep: k6 p99 <= 1.10 x the median of 3 `wl-serial`
  baseline runs, and errors < 1%. Limits: list 76 ms, create 86 ms (`results/exp6/*/slo.json`).
- Sweep: 3 runs of each GC collector per workload, order shuffled.
- Cost axis: CPU-seconds per 1k requests. Latency axis: k6 p99, with server p99 (Prometheus) as a
  second view. Frontier tables: `results/exp6/<workload>/frontier-*.txt` and `.svg`.

## Results

| Workload | Rate | SLO limit | Serial: CPU-s/1k, k6 p99 (runs) | Parallel: CPU-s/1k, k6 p99 (runs) |
| --- | --- | --- | --- | --- |
| Read-heavy (list) | 14/s | 76 ms | 2.113 (2.094-2.348), 66.6 ms (5) | 2.179 (2.043-2.288), 68.9 ms (3) |
| Write-heavy (create) | 40/s | 86 ms | 2.714 (2.628-2.812), 73.2 ms (6) | 2.767 (2.758-2.786), 73.4 ms (3) |

- Every run of both GC collectors passed the SLO, with no errors and no CPU throttling.
- On the k6 p99 axis Serial is the only frontier point in both workloads.
- On the server p99 axis (create) both GC collectors are on the frontier: Parallel 12.2 ms against
  Serial 12.9 ms, for about 2% more CPU. For list only Serial is.
- GC pause time is a small share of CPU in both workloads (about 12 to 24 ms of pause per CPU-second)
  and working set peaks at about 275 to 345 MiB of 768Mi.

## What this means

- The answer does not flip with the workload. Serial stays the cheapest option that meets the SLO.
- The gaps are small (2 to 3% CPU) and the run-to-run ranges overlap (Serial list 2.094-2.348 and
  Parallel list 2.043-2.288), so this is "no reason to prefer Parallel", not "Serial is better".
- Experiment 1 and this one agree on the direction. Neither shows a case for G1, ZGC or Shenandoah.

## Deviations and caveats

- The Serial counts include the 3 baseline runs that set the SLO (6 for create, 5 for list), against
  3 for Parallel. Same as experiment 1.
- The create workload ran at 40/s, about 40% of the planned CPU target (175m). Higher rates made the
  data set (and the reset between runs) too large. The write path is therefore tested at a moderate,
  not a high, load.
- The first list baseline run has no Prometheus data (an empty query), so it has no CPU or server
  p99 figures. Its k6 p99 (71.1 ms) was still used for the SLO.
- That also made the driver's SLO derivation crash, so the list sweep was skipped and rerun after the
  create sweep. The SLO was derived from the k6 p99 of all 3 baseline runs.
- One list Serial run was lost to a `kubectl` API timeout (`dial tcp ...: operation timed out`)
  right after the rollout. It was replaced by a make-up run (`wl-serial-20261008T005115Z`), so
  the list sweep has 3 Serial and 3 Parallel runs.
- Run timestamps in file names are UTC, so `20261008T0...Z` is the evening of 2026-10-07 EDT.
- Grafana screenshots of the Serial baseline runs are in `results/grafana/exp6-*`.
  The watcher stopped when the create sweep ended, so the list sweep has none.

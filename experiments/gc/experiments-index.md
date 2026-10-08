# All experiments: status and where to read the results

Goal: pick the production GC configuration for `job-manager-api`, on a Pareto frontier of p99 latency
(k6, SLO limit set from the baseline) against CPU-seconds per 1k requests, with memory as a fit
constraint. The plan and the full backlog are in [`pareto-frontier.md`](../../pareto-frontier.md).
Harness and layout: [`README.md`](README.md).

## Summary

| # | Experiment | Status | Date | Result in one line | Read |
| --- | --- | --- | --- | --- | --- |
| 1 | GC collector shootout | Done | 2026-10-06/07 | Serial (the current default) and Parallel are the frontier; G1, ZGC and Shenandoah cost 16 to 25% more CPU. SLO limit 75 ms. | [`experiment-1-collector-shootout.md`](experiment-1-collector-shootout.md) |
| 2 | Memory-limit sweep | Reduced version done; low points aborted | 2026-10-07 | No variant fails down to 384Mi (working set about 255 to 290 MiB). Serial at 256Mi restarted; 320Mi and Parallel at 320/256Mi have no data. Node choice moved p99 by about 20 ms. | [`experiment-2-grafana-notes.md`](experiment-2-grafana-notes.md), `results/mem-*.json`, `results/exp2-low-aborted/` (local, uncommitted) |
| 3 | CPU limit x concurrent collectors | Skipped | | Only relevant if a concurrent GC collector were a finalist; experiment 1 ruled them out. | |
| 4 | Fleet-level frontier (HPA on) | Not run | | Deferred. | |
| 5 | Knobs within one collector | Not run | | Only needed if the winner misses the SLO; it does not. | |
| 6 | Workload sensitivity | Done | 2026-10-07 | The answer does not flip: Serial is the only frontier point on k6 p99 for the read-heavy (list, 14/s) and write-heavy (create, 40/s) workloads, 2 to 3% less CPU than Parallel. | [`experiment-6-workload-sensitivity.md`](experiment-6-workload-sensitivity.md), `results/exp6/` |
| 7 | Automated search | Not run | | Large; only for a reusable harness. | |
| 8 | Native series | Not run | | Large build work; only if footprint or startup becomes the goal. | |
| 9 | JVM startup caches (AppCDS, Leyden) | Not run | | Optional; matters if HPA scale-up speed does. | |
| 10 | JDK 21 vs JDK 25 | Not run | | Optional; only if a JDK bump is planned. | |

## What the finished experiments say together

- Serial stays the choice: it is on the frontier in experiments 1 and 6, it is the cheapest in CPU,
  and the gap to Parallel is small (2 to 4%) and within run-to-run noise.
- The concurrent GC collectors (G1, ZGC, Shenandoah) are dominated at this size, and ZGC and
  Shenandoah need about 750 MiB of 768Mi.
- Memory is not the constraint: the working set is about 255 to 345 MiB, and 384Mi held in
  experiment 2. The cluster is CPU-bound, so shrinking memory has little dollar value today.

## Supporting files

| File | What |
| --- | --- |
| [`grafana-screenshots.md`](grafana-screenshots.md) | What each Grafana screenshot in `results/grafana/` shows (experiments 2 and 6) |
| `results/exp6/calibration/` | The runs that chose the rates for experiment 6 |
| `results/exp6/<list|create>/` | Result files, `slo.json` and the frontier tables and SVGs per workload |
| `results/grafana/` | Screenshots, named after the run |

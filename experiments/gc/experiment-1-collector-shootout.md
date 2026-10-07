# Experiment 1: collector shootout (summary)

Run 2026-10-07, 00:00-04:33 EDT, on the live `dev-cluster` (GKE Standard, e2-standard-2 nodes).
Raw results: `experiments/gc/results/` (one JSON per run, `slo.json`, `frontier-*.txt|svg`, `raw/`).
Plan and rationale: `pareto-frontier.md`.

## Setup

| Item | Value |
| --- | --- |
| Question | Which GC configuration should `job-manager-api` run in production? |
| Pod | 1 replica, no HPA, 768Mi / 250m request, 1500m limit, JDK 21, 2 CPUs, heap max 576M |
| Load | `k6-job-lifecycle.js`, constant arrival 10 req/s (calibrated to ~175m CPU, 70% of the request) via `grpc.miqui.dev:443` from the operator's machine |
| Window | 2 min warm-up (excluded), 10 min run, measured over the inner ~560 s |
| Cost axis | CPU-seconds per 1k requests (lower is better) |
| Latency axis | p99, client-side (k6) and server-side (Prometheus) |
| SLO | k6 p99 <= 1.10 x median of 3 baseline runs (67.8 ms) = **75 ms**; errors < 1%; no dropped iterations |
| Protocol | 3 repeats per variant, shuffled order, median reported. Baseline has 6 runs (3 SLO runs + 3 sweep runs) |
| Image | Same digest in all 18 runs |

## Results (median per variant)

| Variant | Runs | CPU-s / 1k req | k6 p99 | Server p99 | Server p99.9 | GC pauses (count) | Max pause | Total pause | Peak working set | SLO | Frontier |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Serial (baseline, JDK default) | 6 | **2.58** | 62.9 ms | 18.8 ms | 30.7 ms | 154 | 112 ms | 1536 ms | 301 MiB | pass | yes |
| Parallel | 3 | 2.68 | **58.6 ms** | **18.4 ms** | **24.7 ms** | 228 | 182 ms | 1499 ms | 294 MiB | pass | yes |
| G1 | 3 | 3.00 | 65.5 ms | 21.4 ms | 44.0 ms | 273 | 78 ms | 3866 ms | 255 MiB | pass | no |
| Shenandoah | 3 | 3.18 | 62.5 ms | 20.9 ms | 25.0 ms | 28 | 2.8 ms | 21 ms | 760 MiB | pass | no |
| ZGC (generational) | 3 | 3.23 | 62.5 ms | 21.4 ms | 33.6 ms | 63 | 1.4 ms | 9 ms | 746 MiB | pass | no |

Range across runs (min-max), CPU-s/1k and k6 p99: Serial 2.40-3.06 and 61.7-71.0 ms; Parallel 2.62-2.84 and
55.2-60.1 ms; G1 2.96-3.03 and 65.1-66.9 ms; Shenandoah 3.17-3.54 and 60.3-67.4 ms; ZGC 3.16-3.24 and 59.5-66.3 ms.

All variants: no CPU throttling, 0% errors, ~72 achieved req/s of the lifecycle script (10 iterations/s), startup 13-16 s.

## Frontier

Axes (minimized): CPU-s per 1k requests, p99. The same two variants are non-dominated on both the
k6 p99 and the server p99 axis.

- **On the frontier:** Serial (cheapest) and Parallel (lowest latency).
- **Dominated:** G1, ZGC and Shenandoah. They cost 16-25% more CPU than Serial and have no latency advantage.

## What it means

1. **Keep the default (Serial) unless p99 matters more than ~4% CPU.** Parallel trades 4% more CPU for ~7%
   lower k6 p99, but the server-side p99 is nearly the same (18.4 vs 18.8 ms), so the gap is probably
   client-side noise.
2. **The result is close.** The Serial and Parallel ranges overlap on both axes. Serial's own k6 p99
   ranged 64-71 ms against the 75 ms limit.
3. **Concurrent collectors don't pay off at this size** (2 CPUs, 576M heap, 10 req/s). ZGC and Shenandoah
   do cut max pauses to 1-3 ms, but pauses weren't what drove p99.
4. **ZGC and Shenandoah nearly fill the pod.** Peak working set is ~750 MiB against the 768Mi limit, so they
   have no headroom (an OOM-kill risk), whereas Serial, Parallel and G1 stay at 255-300 MiB. This is the
   most relevant input to the memory-limit sweep (experiment 2).
5. **Pause length isn't the latency driver.** Parallel has the longest pauses (182 ms max) and the lowest
   k6 p99; GC pauses are rare enough at 10 req/s not to reach p99.

## Caveats

- k6 ran from the operator's home network, so client p99 includes network noise. Server-side p99 is the
  steadier signal.
- 3 repeats per variant; differences of a few ms or a few percent are within noise.
- Only one workload (`k6-job-lifecycle.js`) at one rate. Experiment 6 (read/write-heavy) is not run.
- k6 `context deadline exceeded` bursts appeared during warm-up in some runs (excluded from the window); the cause is unknown.
- `gc_log.collector` is null in the result files (cosmetic); `meta.jvm_gc_flags` has the collector.

## Not run

Experiment 2 (memory-limit sweep) and everything after it in the run order. Next candidates: Serial and
Parallel at 384/512/768Mi, to find the smallest pod that meets the SLO.

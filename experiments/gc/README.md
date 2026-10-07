# GC experiments

Harness for the Pareto-frontier experiments planned in [`pareto-frontier.md`](../../pareto-frontier.md):
which JVM collector (and later memory/CPU settings) is the best production choice for
`job-manager-api`. Everything here is local files; nothing runs until it is pointed at an
experiment cluster.

## Layout

| Path | What |
| --- | --- |
| `overlays/<variant>/` | A Kustomize overlay over `k8s/`: one replica, no HPA, and the variant's `JDK_JAVA_OPTIONS` in the container `env:` |
| `overlays/_common/` | The shared Component (one replica, HPA removed) |
| `queries.json` | PromQL read for each measured window. Names are unverified until the first live run: fix them here |
| `results/` | One `<variant>-<timestamp>.json` per run, plus `raw/` (k6 summary and GC log) |
| `../../scripts/gc-run.sh` | One run: point Argo CD at the overlay, wait, reset data, warm up, measure, collect |
| `../../scripts/gc-sweep.sh` | Several variants, repeated, shuffled; hands the cluster back to `main` at the end |
| `../../scripts/gc-collect.py` | Builds the result file from k6, Prometheus and the GC log |
| `../../scripts/gc-frontier.py` | Median per variant, the non-dominated set, a table and an SVG |
| `../../k6-*.js` | `RATE` switches the scenarios to a constant arrival rate with a warm-up; `k6-reset-jobs.js` clears the data |

The overlays live outside `k8s/` on purpose: Kustomize refuses an overlay nested under the base it
extends ("cycle detected").

## Variants (experiment 1)

`baseline` (today: implicit SerialGC, plus the GC log), `serial-explicit`, `parallel`, `g1`, `zgc`
(generational), `shenandoah`. All of them add the same `-Xlog:gc*` line so the logs are comparable.

## Checking without a cluster

```
kubectl kustomize experiments/gc/overlays/g1            # what Argo CD would apply
DRY_RUN=1 RATE=40 scripts/gc-run.sh g1                  # the plan for a run
python3 -I -m unittest discover -s scripts -p 'test_gc_*.py'
k6 inspect -e RATE=40 -e P99_LIMIT_MS=180 k6-job-lifecycle.js
```

## Running (needs a live experiment cluster)

1. Pause Argo CD Image Updater (`kubectl -n argocd scale deploy/<image-updater> --replicas=0`).
2. Calibrate the baseline: find the `RATE` at which one baseline pod uses about 175m CPU, run the
   baseline 3 times at it, and set `P99_LIMIT_MS` to 1.10x the median p99 (see "Goal and decisions"
   in `pareto-frontier.md`). Do not change either afterwards.
3. `RATE=<n> P99_LIMIT_MS=<ms> REPEATS=3 scripts/gc-sweep.sh baseline serial-explicit parallel g1 zgc shenandoah`
4. `scripts/gc-frontier.py experiments/gc/results --svg experiments/gc/frontier.svg`

`gc-run.sh` pauses the root Application's auto-sync (its self-heal would revert the path change) and
`--restore` / the end of `gc-sweep.sh` turns it back on. It runs k6 from the operator's machine; an
in-cluster k6 Job would remove home-network noise from p99 and is still to do.

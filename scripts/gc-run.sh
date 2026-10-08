#!/usr/bin/env bash
#
# gc-run.sh — one GC-experiment run (see pareto-frontier.md, "Run script").
#
#   RATE=40 P99_LIMIT_MS=180 scripts/gc-run.sh g1      # run the overlay experiments/gc/overlays/g1
#   scripts/gc-run.sh --restore                        # end of a sweep: hand the cluster back to main
#   DRY_RUN=1 RATE=40 scripts/gc-run.sh g1             # render the overlay and print the plan; no cluster calls
#
# What a run does:
#   1. points the job-manager-api Argo CD Application at the overlay on $BRANCH (the root app's
#      auto-sync is paused first, or its self-heal would put the Application back; `--restore` undoes it)
#   2. waits until the pod runs the overlay's JDK_JAVA_OPTIONS with one replica and no HPA, and checks
#      the JVM really selected the collector (PrintFlagsFinal in the pod)
#   3. resets the data (k6-reset-jobs.js deletes every job), warms up, measures with a fixed arrival rate
#   4. reads Prometheus for the measured window and writes experiments/gc/results/<variant>-<ts>.json
#
# Needs: kubectl (context = the experiment cluster), k6, jq, curl, python3. It changes the cluster, so
# run it only against a dedicated experiment cluster, and pause Argo CD Image Updater first.
#
# Environment (defaults in brackets):
#   RATE            arrival rate, iterations/s (required) — the calibrated rate from "Goal and decisions"
#   P99_LIMIT_MS    SLO: fail the measured window if p99 is above this []
#   WARMUP, DURATION  k6 durations [2m, 10m]; K6_SCRIPT [k6-job-lifecycle.js]; GRPC_ADDR [grpc.miqui.dev:443]
#   BRANCH          git branch the overlays are on [main]
#   RESULTS_DIR     [experiments/gc/results]
#   ALLOW_IMAGE_UPDATER=1  skip the Image Updater check (you take responsibility for the image staying fixed)
#   DRY_RUN=1       no cluster calls
set -euo pipefail

cd "$(dirname "$0")/.."

ARGO_NS=argocd
APP=job-manager-api
ROOT_APP=root
APP_NS=default
BRANCH="${BRANCH:-main}"
RESULTS_DIR="${RESULTS_DIR:-experiments/gc/results}"
K6_SCRIPT="${K6_SCRIPT:-k6-job-lifecycle.js}"
GRPC_ADDR="${GRPC_ADDR:-grpc.miqui.dev:443}"
WARMUP="${WARMUP:-2m}"
DURATION="${DURATION:-10m}"
P99_LIMIT_MS="${P99_LIMIT_MS:-}"
PROM_LOCAL_PORT="${PROM_LOCAL_PORT:-19090}"
MARGIN_S=20        # trimmed from each end of the measured window (k6 setup, scenario start-up)
PROM_WAIT_S=45     # OTLP push (15s) + Prometheus scrape (15s) + slack, before querying
ROLLOUT_TIMEOUT_S=900
DRY_RUN="${DRY_RUN:-0}"

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# "90s" / "2m" / "1h" -> seconds
seconds() {
  case "$1" in
    *s) echo "${1%s}" ;;
    *m) echo $(( ${1%m} * 60 )) ;;
    *h) echo $(( ${1%h} * 3600 )) ;;
    *) die "unsupported duration '$1' (use e.g. 30s, 2m, 1h)" ;;
  esac
}

restore() {
  log "Handing the cluster back to main"
  kubectl -n "$ARGO_NS" patch application "$APP" --type merge \
    -p '{"spec":{"source":{"targetRevision":"main","path":"k8s"}}}'
  kubectl -n "$ARGO_NS" patch application "$ROOT_APP" --type merge \
    -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'
  # Image Updater's own Application self-heals (it would scale the controller back up mid-run), so
  # pausing it takes its auto-sync off; turning that back on also brings the controller back to 1.
  kubectl -n "$ARGO_NS" patch application argocd-image-updater --type merge \
    -p '{"spec":{"syncPolicy":{"automated":{"prune":true,"selfHeal":true}}}}'
  kubectl -n "$ARGO_NS" annotate application "$APP" argocd.argoproj.io/refresh=hard --overwrite
  echo "root auto-sync restored; $APP tracks main/k8s again (3 replicas + HPA return)"
}

if [[ "${1:-}" == "--restore" ]]; then
  restore
  exit 0
fi

VARIANT="${1:-}"
[[ -n "$VARIANT" && "$VARIANT" != -* ]] || die "usage: RATE=<n> $0 <variant> | $0 --restore"
OVERLAY="experiments/gc/overlays/$VARIANT"
[[ -f "$OVERLAY/kustomization.yaml" ]] || die "no overlay $OVERLAY (variants: $(ls experiments/gc/overlays | grep -v '^_' | tr '\n' ' '))"
[[ "${RATE:-}" =~ ^[0-9]+$ ]] || die "RATE must be a whole number of iterations per second"
WARMUP_S=$(seconds "$WARMUP")
DURATION_S=$(seconds "$DURATION")
(( DURATION_S > 2 * MARGIN_S + 30 )) || die "DURATION too short to measure (need > $((2 * MARGIN_S + 30))s)"

for tool in kubectl k6 jq curl python3; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool not found"
done

# What the overlay should put in the pod: render it locally, no cluster needed.
RENDERED=$(kubectl kustomize "$OVERLAY") || die "kubectl kustomize $OVERLAY failed"
EXPECTED_OPTS=$(printf '%s\n' "$RENDERED" | awk '/name: JDK_JAVA_OPTIONS/ { getline; sub(/^ *value: */, ""); gsub(/"/, ""); print; exit }')
[[ -n "$EXPECTED_OPTS" ]] || die "$OVERLAY does not set JDK_JAVA_OPTIONS"
# Overlays that differ only in memory (experiment 2) have the same JDK options, so the wait below must
# also compare the memory limit, or it would see the previous run's pod as "already rolled out".
EXPECTED_MEM=$(printf '%s\n' "$RENDERED" | python3 -I -c '
import sys, yaml
for d in yaml.safe_load_all(sys.stdin):
    if d and d.get("kind") == "Deployment" and d["metadata"]["name"] == "job-manager-api":
        for c in d["spec"]["template"]["spec"]["containers"]:
            if c["name"] == "job-manager-api":
                print(c["resources"]["limits"]["memory"])')
[[ -n "$EXPECTED_MEM" ]] || die "could not read the memory limit from the rendered $OVERLAY"
WINDOW_OFFSET=$(( WARMUP_S + MARGIN_S ))
WINDOW_S=$(( DURATION_S - 2 * MARGIN_S ))

echo "variant:        $VARIANT ($OVERLAY on $BRANCH)"
echo "JDK options:    $EXPECTED_OPTS"
echo "memory limit:   $EXPECTED_MEM"
echo "load:           $K6_SCRIPT at ${RATE}/s against $GRPC_ADDR, warm-up $WARMUP, measure $DURATION"
echo "SLO p99 limit:  ${P99_LIMIT_MS:-none}"
echo "window:         measured seconds $WINDOW_OFFSET..$((WINDOW_OFFSET + WINDOW_S)) of the k6 run (${WINDOW_S}s)"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY_RUN=1: stopping before any cluster call"
  exit 0
fi

kubectl get --raw /readyz >/dev/null 2>&1 || die "cluster API unreachable (wrong context, or your public IP changed?)"
echo "kubectl context: $(kubectl config current-context)"

# ---- 0. the image must not change under the run -----------------------------------------------
if [[ "${ALLOW_IMAGE_UPDATER:-0}" != "1" ]]; then
  running=$(kubectl -n "$ARGO_NS" get deploy -o json \
    | jq -r '.items[] | select(.metadata.name | test("image-updater")) | select((.status.readyReplicas // 0) > 0) | .metadata.name')
  [[ -z "$running" ]] || die "Argo CD Image Updater is running ($running); pause it so a new build can't roll out mid-run:
  kubectl -n $ARGO_NS patch application argocd-image-updater --type merge -p '{\"spec\":{\"syncPolicy\":{\"automated\":null}}}'
  kubectl -n $ARGO_NS scale deploy/$running --replicas=0
(its Application self-heals, so the patch must come first; '$0 --restore' undoes both)
or set ALLOW_IMAGE_UPDATER=1"
fi

# ---- 1. point the Application at the overlay --------------------------------------------------
log "Pausing root auto-sync and pointing $APP at $OVERLAY ($BRANCH)"
kubectl -n "$ARGO_NS" patch application "$ROOT_APP" --type merge -p '{"spec":{"syncPolicy":{"automated":null}}}'
kubectl -n "$ARGO_NS" patch application "$APP" --type merge \
  -p "{\"spec\":{\"source\":{\"targetRevision\":\"$BRANCH\",\"path\":\"$OVERLAY\"}}}"
kubectl -n "$ARGO_NS" annotate application "$APP" argocd.argoproj.io/refresh=hard --overwrite
echo "REMEMBER: run '$0 --restore' when the sweep is over (the cluster stays on the experiment until then)"

# ---- 2. wait for the pod to carry the overlay -------------------------------------------------
log "Waiting for the rollout of the overlay's settings"
deadline=$(( $(date +%s) + ROLLOUT_TIMEOUT_S ))
while :; do
  current=$(kubectl -n "$APP_NS" get deploy "$APP" -o json | jq -r --arg c "$APP" \
    '[(.spec.replicas | tostring), ((.spec.template.spec.containers[] | select(.name == $c) | .env[]? | select(.name == "JDK_JAVA_OPTIONS") | .value) // ""), (.spec.template.spec.containers[] | select(.name == $c) | .resources.limits.memory)] | join("|")')
  hpa=$(kubectl -n "$APP_NS" get hpa "$APP" -o name 2>/dev/null || true)
  [[ "$current" == "1|$EXPECTED_OPTS|$EXPECTED_MEM" && -z "$hpa" ]] && break
  (( $(date +%s) < deadline )) || die "timed out: deployment has '$current' (hpa: ${hpa:-none}), want '1|$EXPECTED_OPTS|$EXPECTED_MEM' - check the Application in Argo CD"
  sleep 10
done
if ! kubectl -n "$APP_NS" rollout status "deploy/$APP" --timeout="${READY_TIMEOUT_S:-300}s"; then
  # A pod that never gets Ready usually means the JVM does not fit the memory limit (it exits on
  # OutOfMemoryError, or the kernel kills it). Record that as a result ("does not fit"), not a crash.
  bad=$(kubectl -n "$APP_NS" get pods -l "app=$APP" -o json | jq -c --arg c "$APP" '
    [.items[].status.containerStatuses[]? | select(.name == $c)
     | select((.restartCount > 0) or (.state.waiting.reason // "" | test("CrashLoop|OOM")) or (.lastState.terminated.reason // "" | test("OOM")))
     | {restarts: .restartCount, waiting: (.state.waiting.reason // null), last_exit: (.lastState.terminated // null)}] | first // empty')
  [[ -n "$bad" ]] || die "rollout did not finish and the pod is not crash-looping - check the Application in Argo CD"
  MEM_LIMIT=$(kubectl -n "$APP_NS" get deploy "$APP" -o json | jq -r --arg c "$APP" '.spec.template.spec.containers[] | select(.name == $c) | .resources.limits.memory')
  TS=$(date -u +%Y%m%dT%H%M%SZ)
  mkdir -p "$RESULTS_DIR"
  jq -n --arg variant "$VARIANT" --arg overlay "$OVERLAY" --arg ts "$TS" --arg opts "$EXPECTED_OPTS" --arg mem "$MEM_LIMIT" --argjson bad "$bad" '
    { variant: $variant, valid: false, slo_pass: false, fits: false,
      meta: { timestamp: $ts, overlay: $overlay, jdk_java_options: $opts, memory_limit: $mem, crash: $bad } }' > "$RESULTS_DIR/$VARIANT-$TS.json"
  log "$VARIANT does not fit its memory limit ($MEM_LIMIT): the pod never became Ready ($bad)"
  echo "wrote $RESULTS_DIR/$VARIANT-$TS.json (fits=false)"
  exit 0
fi
while :; do   # the old pod must be gone, or its load and logs would mix into the window
  pods=$(kubectl -n "$APP_NS" get pods -l "app=$APP" -o json | jq -r '[.items[] | select(.metadata.deletionTimestamp == null)] | length')
  total=$(kubectl -n "$APP_NS" get pods -l "app=$APP" -o json | jq -r '.items | length')
  [[ "$pods" == "1" && "$total" == "1" ]] && break
  (( $(date +%s) < deadline )) || die "expected exactly one $APP pod, found $total ($pods not terminating)"
  sleep 5
done
POD=$(kubectl -n "$APP_NS" get pods -l "app=$APP" -o jsonpath='{.items[0].metadata.name}')
echo "pod: $POD"

# ---- 3. what is really running ----------------------------------------------------------------
POD_JSON=$(kubectl -n "$APP_NS" get pod "$POD" -o json)
NODE=$(jq -r '.spec.nodeName' <<<"$POD_JSON")
[[ "$(jq -r --arg c "$APP" '.spec.containers[] | select(.name == $c) | .resources.limits.memory' <<<"$POD_JSON")" == "$EXPECTED_MEM" ]] \
  || die "pod $POD has a different memory limit than the overlay's $EXPECTED_MEM (a rollout was still in progress?)"
IMAGE_ID=$(jq -r --arg c "$APP" '.status.containerStatuses[] | select(.name == $c) | .imageID' <<<"$POD_JSON")
MEM_LIMIT=$(jq -r --arg c "$APP" '.spec.containers[] | select(.name == $c) | .resources.limits.memory' <<<"$POD_JSON")
STARTUP_S=$(jq -r --arg c "$APP" '
  (.status.containerStatuses[] | select(.name == $c) | .state.running.startedAt) as $started
  | (.status.conditions[] | select(.type == "Ready") | .lastTransitionTime) as $ready
  | (($ready | fromdateiso8601) - ($started | fromdateiso8601))' <<<"$POD_JSON")
HZ_POD=$(kubectl -n "$APP_NS" get pods -l app=hazelcast -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
HZ_NODE=$(kubectl -n "$APP_NS" get pods -l app=hazelcast -o jsonpath='{.items[0].spec.nodeName}' 2>/dev/null || true)
# The JRE image has no jcmd, and the kubelet rotates container logs, so a long-lived pod no longer
# has the "Using <collector>" line from its start. Ask a second JVM in the same container (same
# environment, same cgroup, so the same ergonomics) which collector it ends up with. An overlay
# without a collector flag (baseline) is expected to get SerialGC.
case "$EXPECTED_OPTS" in
  *UseSerialGC*) WANT_GC="UseSerialGC" ;;
  *UseParallelGC*) WANT_GC="UseParallelGC" ;;
  *UseG1GC*) WANT_GC="UseG1GC" ;;
  *UseZGC*) WANT_GC="UseZGC" ;;
  *UseShenandoahGC*) WANT_GC="UseShenandoahGC" ;;
  *) WANT_GC="UseSerialGC" ;;
esac
FLAGS=$(kubectl -n "$APP_NS" exec "$POD" -c "$APP" -- java -XX:+PrintFlagsFinal -version 2>/dev/null \
  | awk '$2 ~ /^(Use(Serial|Parallel|G1|Z|Shenandoah)GC|ZGenerational)$/ { print $2 "=" $4 }') || true
[[ -n "$FLAGS" ]] || die "could not read the JVM's final flags from the pod (java -XX:+PrintFlagsFinal failed)"
GC_REPORTED=$(tr ' ' '\n' <<<"$FLAGS" | awk -F= '$2 == "true" && $1 ~ /GC$/ { printf "%s ", $1 }' | sed 's/ $//')
[[ "$GC_REPORTED" == "$WANT_GC" ]] || die "the JVM selected '${GC_REPORTED:-nothing}' but this overlay should give '$WANT_GC'"
if [[ "$EXPECTED_OPTS" == *ZGenerational* ]]; then
  grep -q 'ZGenerational=true' <<<"$FLAGS" || die "generational ZGC was requested but ZGenerational is not true"
fi
echo "JVM selected: $GC_REPORTED"
echo "startup (container start -> Ready): ${STARTUP_S}s on $NODE"

# ---- 4. reset, warm up, measure ---------------------------------------------------------------
tmp=$(mktemp -d)
pf_pid=""
log_pid=""
cleanup() {
  [[ -z "$pf_pid" ]] || kill "$pf_pid" 2>/dev/null || true
  [[ -z "$log_pid" ]] || kill "$log_pid" 2>/dev/null || true
  rm -rf "$tmp"
}
trap cleanup EXIT

log "Resetting data (every job is deleted)"
CONFIRM_RESET=yes k6 run --quiet -e "GRPC_ADDR=$GRPC_ADDR" k6-reset-jobs.js

kubectl -n observability port-forward svc/prometheus "$PROM_LOCAL_PORT:9090" --address 127.0.0.1 >/dev/null 2>&1 &
pf_pid=$!
for _ in $(seq 1 30); do
  curl -fs "http://127.0.0.1:$PROM_LOCAL_PORT/-/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://127.0.0.1:$PROM_LOCAL_PORT/-/ready" >/dev/null 2>&1 || die "Prometheus not reachable through the port-forward"

log "Running $K6_SCRIPT: warm-up $WARMUP, then measuring $DURATION at ${RATE}/s"
k6_args=(run --quiet --summary-export "$tmp/k6.json" --summary-trend-stats 'avg,med,p(90),p(95),p(99),p(99.9),max'
  -e "GRPC_ADDR=$GRPC_ADDR" -e "RATE=$RATE" -e "WARMUP=$WARMUP" -e "DURATION=$DURATION")
[[ -z "$P99_LIMIT_MS" ]] || k6_args+=(-e "P99_LIMIT_MS=$P99_LIMIT_MS")
# Stream the pod's log for the whole run: the kubelet rotates container logs at ~10Mi and the
# per-request JSON logs fill that quickly, so reading it afterwards could miss the GC lines.
kubectl -n "$APP_NS" logs -f "$POD" -c "$APP" --since=5s > "$tmp/pod.full" 2>/dev/null &
log_pid=$!
T0=$(date +%s)
K6_RC=0
k6 "${k6_args[@]}" "$K6_SCRIPT" || K6_RC=$?
# 99 = a threshold was crossed (a result, not a failure of the run); anything else is a broken run
(( K6_RC == 0 || K6_RC == 99 )) || die "k6 failed with exit code $K6_RC"
WIN_START=$(( T0 + WINDOW_OFFSET ))
WIN_END=$(( WIN_START + WINDOW_S ))

# ---- 5. collect -------------------------------------------------------------------------------
now=$(date +%s)
(( now >= WIN_END + PROM_WAIT_S )) || { log "Waiting for the last samples to reach Prometheus"; sleep $(( WIN_END + PROM_WAIT_S - now )); }

log "Reading Prometheus for the ${WINDOW_S}s window"
mkdir -p "$tmp/prom"
while IFS=$'\t' read -r name query; do
  query=${query//\{W\}/${WINDOW_S}s}
  curl -fsG "http://127.0.0.1:$PROM_LOCAL_PORT/api/v1/query" \
    --data-urlencode "query=$query" --data-urlencode "time=$WIN_END" -o "$tmp/prom/$name.json" \
    || echo "WARNING: query $name failed" >&2
done < <(jq -r 'to_entries[] | select(.key | startswith("_") | not) | [.key, .value] | @tsv' experiments/gc/queries.json)

kill "$log_pid" 2>/dev/null || true
log_pid=""
grep '^\[' "$tmp/pod.full" > "$tmp/pod.log" || true

RESTARTS=$(kubectl -n "$APP_NS" get pod "$POD" -o json | jq -r --arg c "$APP" '.status.containerStatuses[] | select(.name == $c) | .restartCount')
TS=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$RESULTS_DIR/raw"
jq -n \
  --arg variant "$VARIANT" --arg overlay "$OVERLAY" --arg branch "$BRANCH" --arg sha "$(git rev-parse HEAD)" \
  --arg opts "$EXPECTED_OPTS" --arg gc "$GC_REPORTED" --arg pod "$POD" --arg node "$NODE" --arg image "$IMAGE_ID" \
  --arg hz_pod "$HZ_POD" --arg hz_node "$HZ_NODE" --arg startup "$STARTUP_S" --arg mem "$MEM_LIMIT" --arg restarts "$RESTARTS" \
  --arg script "$K6_SCRIPT" --arg addr "$GRPC_ADDR" --arg rate "$RATE" --arg warmup "$WARMUP" --arg duration "$DURATION" \
  --arg limit "$P99_LIMIT_MS" --arg rc "$K6_RC" --arg ts "$TS" '
  { timestamp: $ts, overlay: $overlay, branch: $branch, git_sha: $sha, jdk_java_options: $opts, jvm_gc_flags: $gc,
    pod: $pod, node: $node, image_id: $image, hazelcast_pod: $hz_pod, hazelcast_node: $hz_node,
    startup_seconds: ($startup | tonumber? // null), memory_limit: $mem, restart_count: ($restarts | tonumber? // null),
    load: { script: $script, grpc_addr: $addr, rate: ($rate | tonumber), warmup: $warmup, duration: $duration,
            p99_limit_ms: ($limit | tonumber? // null), k6_exit_code: ($rc | tonumber), source: "operator machine" } }' > "$tmp/meta.json"

OUT="$RESULTS_DIR/$VARIANT-$TS.json"
python3 -I scripts/gc-collect.py --variant "$VARIANT" --k6-summary "$tmp/k6.json" --prom-dir "$tmp/prom" \
  --gc-log "$tmp/pod.log" --meta "$tmp/meta.json" --window-start "$WIN_START" --window-end "$WIN_END" --out "$OUT"
cp "$tmp/k6.json" "$RESULTS_DIR/raw/$VARIANT-$TS.k6.json"
grep '^\[' "$tmp/pod.log" > "$RESULTS_DIR/raw/$VARIANT-$TS.gc.log" || true

echo
echo "Done. Next run, or '$0 --restore' to hand the cluster back to main."

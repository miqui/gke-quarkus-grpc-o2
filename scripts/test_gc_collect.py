import importlib.util
import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

spec = importlib.util.spec_from_file_location("gc_collect", Path(__file__).with_name("gc-collect.py"))
gc_collect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc_collect)

T0 = datetime.strptime("2026-10-06T10:00:00.000+0000", gc_collect.TIME_FORMAT).timestamp()


def prom(value):
    return {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {}, "value": [1, str(value)]}]}}


def summary(p99=250.0, thresholds=None, dropped=0):
    return {"metrics": {
        "grpc_req_duration{scenario:measure}": {"med": 40.0, "p(95)": 120.0, "p(99)": p99, "p(99.9)": 400.0,
                                                 "max": 500.0, "thresholds": thresholds or {}},
        "dropped_iterations{scenario:measure}": {"count": dropped, "rate": 0},
        "checks": {"passes": 10, "fails": 0, "value": 1},
    }}


G1_LOG = "\n".join([
    '{"timestamp":"2026-10-06T10:00:05Z","message":"app log, not a GC line"}',
    "[2026-10-06T09:59:00.000+0000][1.0s][info][gc] Using G1",
    "[2026-10-06T09:59:30.000+0000][30.0s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 20M->5M(64M) 99.000ms",
    "[2026-10-06T10:00:10.000+0000][70.0s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 25M->6M(64M) 3.000ms",
    "[2026-10-06T10:00:20.000+0000][80.0s][info][gc,phases] GC(3) Pause Cleanup 1.000ms",
    "[2026-10-06T10:00:20.000+0000][80.0s][info][gc] GC(3) Pause Cleanup 1.000ms",
    "[2026-10-06T10:00:30.000+0000][90.0s][info][gc,heap] GC(3) Eden regions: 1->0(2)",
    "[2026-10-06T10:05:00.000+0000][370.0s][info][gc] GC(9) Pause Young (Normal) (G1 Evacuation Pause) 25M->6M(64M) 77.000ms",
])


class GcCollectTest(unittest.TestCase):
    def test_percentile_is_nearest_rank(self):
        self.assertEqual(gc_collect.percentile([1, 2, 3, 4], 50), 2)
        self.assertEqual(gc_collect.percentile([1, 2, 3, 4], 99), 4)
        self.assertEqual(gc_collect.percentile([7], 99), 7)

    def test_pauses_inside_window_only_and_deduplicated(self):
        stats = gc_collect.gc_pauses(G1_LOG, T0, T0 + 60)
        self.assertEqual(stats["collector"], "G1")
        self.assertEqual(stats["pause_count"], 2)  # 3.0 and 1.0 (once); 99 and 77 are outside
        self.assertEqual(stats["pause_total_ms"], 4.0)
        self.assertEqual(stats["pause_max_ms"], 3.0)

    def test_no_pauses_has_no_stats(self):
        stats = gc_collect.gc_pauses("", T0, T0 + 60)
        self.assertEqual(stats, {"collector": None, "pause_count": 0})

    def test_prom_value_handles_missing_empty_and_nan(self):
        with tempfile.TemporaryDirectory() as d:
            good, empty, nan = (Path(d) / n for n in ("good.json", "empty.json", "nan.json"))
            good.write_text(json.dumps(prom(12.5)))
            empty.write_text(json.dumps({"data": {"result": []}}))
            nan.write_text(json.dumps(prom("NaN")))
            self.assertEqual(gc_collect.prom_value(good), 12.5)
            self.assertIsNone(gc_collect.prom_value(empty))
            self.assertIsNone(gc_collect.prom_value(nan))
            self.assertIsNone(gc_collect.prom_value(Path(d) / "absent.json"))

    def test_k6_threshold_true_means_crossed(self):
        failed = gc_collect.k6_stats(summary(thresholds={"p(99)<300": True}))
        self.assertEqual(failed["failed_thresholds"], ["p(99)<300"])
        self.assertEqual(gc_collect.k6_stats(summary(thresholds={"p(99)<300": False}))["failed_thresholds"], [])

    def test_build_derives_cost_and_judges_the_slo(self):
        with tempfile.TemporaryDirectory() as d:
            for name, value in {"cpu_seconds": 120, "server_requests": 60000, "server_errors": 60,
                                "cpu_periods": 1000, "cpu_throttled_periods": 100,
                                "working_set_max_bytes": 536870912}.items():
                (Path(d) / f"{name}.json").write_text(json.dumps(prom(value)))
            ok = gc_collect.build("g1", summary(), d, G1_LOG, {"sha": "abc"}, T0, T0 + 60)
            self.assertEqual(ok["derived"]["cpu_seconds_per_1k_requests"], 2.0)
            self.assertEqual(ok["derived"]["error_rate"], 0.001)
            self.assertEqual(ok["derived"]["throttled_fraction"], 0.1)
            self.assertEqual(ok["derived"]["working_set_max_mib"], 512.0)
            self.assertEqual(ok["derived"]["achieved_rps"], 1000.0)
            self.assertTrue(ok["valid"] and ok["slo_pass"])
            slow = gc_collect.build("g1", summary(thresholds={"p(99)<300": True}), d, G1_LOG, {}, T0, T0 + 60)
            self.assertTrue(slow["valid"])
            self.assertFalse(slow["slo_pass"])
            dropped = gc_collect.build("g1", summary(dropped=5), d, G1_LOG, {}, T0, T0 + 60)
            self.assertFalse(dropped["valid"])
            self.assertFalse(dropped["slo_pass"])

    def test_run_without_a_measure_window_is_invalid(self):
        with tempfile.TemporaryDirectory() as d:
            result = gc_collect.build("g1", {"metrics": {}}, d, "", {}, T0, T0 + 60)
            self.assertFalse(result["valid"])


if __name__ == "__main__":
    unittest.main()

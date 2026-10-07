import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("gc_frontier", Path(__file__).with_name("gc-frontier.py"))
gc_frontier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc_frontier)

AXES = ["k6.p99_ms", "derived.cpu_seconds_per_1k_requests"]


def run(variant, p99, cpu, valid=True, slo=True):
    return {"variant": variant, "valid": valid, "slo_pass": slo,
            "k6": {"p99_ms": p99}, "derived": {"cpu_seconds_per_1k_requests": cpu}}


class GcFrontierTest(unittest.TestCase):
    def test_dominates_needs_strict_improvement_somewhere(self):
        self.assertTrue(gc_frontier.dominates([1, 1], [2, 2]))
        self.assertTrue(gc_frontier.dominates([1, 2], [2, 2]))
        self.assertFalse(gc_frontier.dominates([2, 2], [2, 2]))
        self.assertFalse(gc_frontier.dominates([1, 3], [2, 2]))

    def test_frontier_drops_dominated_points_and_keeps_ties(self):
        points = gc_frontier.summarise([
            run("serial", 200, 1.0), run("zgc", 80, 2.0), run("g1", 220, 1.5), run("twin", 80, 2.0)], AXES)
        names = {p["variant"] for p in gc_frontier.frontier(points)}
        self.assertEqual(names, {"serial", "zgc", "twin"})  # g1 is dominated by serial

    def test_point_is_the_median_of_valid_runs_with_spread(self):
        points = gc_frontier.summarise([run("g1", 100, 1.0), run("g1", 300, 3.0), run("g1", 200, 2.0),
                                        run("g1", 9999, 99.0, valid=False)], AXES)
        self.assertEqual(len(points), 1)
        self.assertEqual(points[0]["runs"], 3)
        self.assertEqual(points[0]["values"], [200, 2.0])
        self.assertEqual(points[0]["spread"], [(100, 300), (1.0, 3.0)])

    def test_slo_needs_a_strict_majority_of_runs(self):
        two_of_three = gc_frontier.summarise([run("a", 1, 1, slo=True), run("a", 1, 1, slo=True), run("a", 1, 1, slo=False)], AXES)
        one_of_two = gc_frontier.summarise([run("b", 1, 1, slo=True), run("b", 1, 1, slo=False)], AXES)
        self.assertTrue(two_of_three[0]["slo_pass"])
        self.assertFalse(one_of_two[0]["slo_pass"])

    def test_results_missing_an_axis_are_ignored(self):
        broken = {"variant": "x", "valid": True, "k6": {"p99_ms": 1}, "derived": {}}
        self.assertEqual(gc_frontier.summarise([broken], AXES), [])

    def test_cli_prints_a_table_and_writes_the_svg(self):
        with tempfile.TemporaryDirectory() as d:
            for i, r in enumerate([run("serial", 200, 1.0), run("zgc", 80, 2.0), run("g1", 220, 1.5)]):
                (Path(d) / f"r{i}.json").write_text(json.dumps(r))
            (Path(d) / "junk.json").write_text("not json")
            out = Path(d) / "f.svg"
            self.assertEqual(gc_frontier.main([d, "--svg", str(out)]), 0)
            text = out.read_text()
            self.assertTrue(text.startswith("<svg"))
            self.assertIn("zgc", text)
            self.assertEqual(gc_frontier.main([str(Path(d) / "empty-nonexistent")]), 1)


if __name__ == "__main__":
    unittest.main()

import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("quality_summary", Path(__file__).with_name("quality-summary.py"))
quality_summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality_summary)


class QualitySummaryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        (self.base / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
            '<quality.coverage.line.minimum>0.93</quality.coverage.line.minimum>'
            '<quality.coverage.branch.minimum>0.85</quality.coverage.branch.minimum>'
            '</properties></project>'
        )

    def report(self, name, text):
        path = self.base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def passing_reports(self):
        self.report("target/site/jacoco/jacoco.xml",
                    '<report><counter type="LINE" covered="94" missed="6"/>'
                    '<counter type="BRANCH" covered="87" missed="13"/></report>')
        self.report("target/spotbugsXml.xml", '<BugCollection><Errors errors="0" missingClasses="0"/></BugCollection>')
        self.report("target/pmd.xml", '<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0"/>')

    def test_passing_reports_show_measured_coverage_and_policy(self):
        self.passing_reports()
        summary, valid = quality_summary.summarize(self.base, "success")
        self.assertTrue(valid)
        self.assertIn("94.00% (94/100)", summary)
        self.assertIn(">= 93%", summary)
        self.assertIn("| SpotBugs high/medium findings | 0 | 0 |", summary)

    def test_missing_reports_cannot_turn_success_into_a_false_pass(self):
        summary, valid = quality_summary.summarize(self.base, "success")
        self.assertFalse(valid)
        self.assertIn("Not generated", summary)

    def test_failed_verification_can_still_publish_partial_results(self):
        summary, valid = quality_summary.summarize(self.base, "failure")
        self.assertTrue(valid)
        self.assertIn("**failure**", summary)
        self.assertIn("Not generated", summary)

    def test_violations_and_analysis_errors_cannot_pass(self):
        self.passing_reports()
        self.report("target/spotbugsXml.xml", '<BugCollection><Errors errors="1" missingClasses="0"/></BugCollection>')
        self.report("target/pmd.xml",
                    '<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0">'
                    '<file><violation rule="BrokenNullCheck"/></file></pmd>')
        summary, valid = quality_summary.summarize(self.base, "success")
        self.assertFalse(valid)
        self.assertIn("analysis errors", summary)
        self.assertIn("| PMD violations | 1 | 0 |", summary)

    def test_spotbugs_medium_confidence_findings_fail_but_low_do_not(self):
        self.passing_reports()
        for priority, expected in ((2, False), (3, True)):
            with self.subTest(priority=priority):
                self.report("target/spotbugsXml.xml",
                            f'<BugCollection><BugInstance priority="{priority}"/>'
                            '<Errors errors="0" missingClasses="0"/></BugCollection>')
                _, valid = quality_summary.summarize(self.base, "success")
                self.assertEqual(expected, valid)

    def test_missing_classes_and_pmd_processing_errors_fail(self):
        self.passing_reports()
        self.report("target/spotbugsXml.xml",
                    '<BugCollection><Errors errors="0" missingClasses="1"/></BugCollection>')
        _, valid = quality_summary.summarize(self.base, "success")
        self.assertFalse(valid)
        self.passing_reports()
        self.report("target/pmd.xml",
                    '<pmd xmlns="http://pmd.sourceforge.net/report/2.0.0">'
                    '<error filename="Source.java" msg="Could not parse"/></pmd>')
        _, valid = quality_summary.summarize(self.base, "success")
        self.assertFalse(valid)

    def test_coverage_below_the_floor_cannot_pass(self):
        self.passing_reports()
        self.report("target/site/jacoco/jacoco.xml",
                    '<report><counter type="LINE" covered="92" missed="8"/>'
                    '<counter type="BRANCH" covered="87" missed="13"/></report>')
        _, valid = quality_summary.summarize(self.base, "success")
        self.assertFalse(valid)

    def test_empty_or_malformed_coverage_is_an_explicit_error(self):
        for report in ("<report/>", '<report><counter type="LINE" covered="0" missed="0"/></report>',
                       '<not-a-report><counter type="LINE" covered="94" missed="6"/>'
                       '<counter type="BRANCH" covered="87" missed="13"/></not-a-report>'):
            with self.subTest(report=report):
                self.report("target/site/jacoco/jacoco.xml", report)
                with self.assertRaises(ValueError):
                    quality_summary.summarize(self.base, "success")

    def test_invalid_analyzer_reports_cannot_pass(self):
        for name, report in (("target/spotbugsXml.xml", "<BugCollection/>"),
                             ("target/pmd.xml", "<not-a-report/>")):
            with self.subTest(name=name):
                self.passing_reports()
                self.report(name, report)
                with self.assertRaises(ValueError):
                    quality_summary.summarize(self.base, "success")


if __name__ == "__main__":
    unittest.main()

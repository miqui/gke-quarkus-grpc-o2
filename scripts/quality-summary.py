#!/usr/bin/env python3
"""Render repository-owned quality reports without uploading them to an external service."""

import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def summarize(base, outcome):
    rows = []
    complete = True
    pom = ET.parse(base / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    coverage = base / "target/site/jacoco/jacoco.xml"
    if coverage.is_file():
        report = ET.parse(coverage).getroot()
        if report.tag != "report":
            raise ValueError("Invalid JaCoCo report")
        for kind, property_name in (("LINE", "line"), ("BRANCH", "branch")):
            counter = report.find(f"counter[@type='{kind}']")
            if counter is None:
                raise ValueError(f"JaCoCo report is missing its {kind} counter")
            covered = int(counter.attrib["covered"])
            total = covered + int(counter.attrib["missed"])
            if total == 0:
                raise ValueError(f"JaCoCo report has no API {kind.lower()}s")
            minimum = float(pom.find(
                f"m:properties/m:quality.coverage.{property_name}.minimum", namespace
            ).text)
            ratio = covered / total
            rows.append((f"{kind.title()} coverage", f"{ratio:.2%} ({covered}/{total})", f">= {minimum:.0%}"))
            complete = complete and ratio >= minimum
    else:
        rows.append(("Coverage", "Not generated", "Required"))
        complete = False

    for label, path in (
        ("SpotBugs high/medium findings", "target/spotbugsXml.xml"),
        ("PMD violations", "target/pmd.xml"),
    ):
        file = base / path
        if file.is_file():
            report = ET.parse(file).getroot()
            if label.startswith("SpotBugs"):
                if report.tag != "BugCollection" or report.find("Errors") is None:
                    raise ValueError("Invalid or incomplete SpotBugs report")
                count = sum(int(bug.attrib["priority"]) <= 2 for bug in report.findall(".//BugInstance"))
                has_errors = any(
                    int(error.attrib["errors"]) > 0 or int(error.attrib["missingClasses"]) > 0
                    for error in report.findall(".//Errors")
                )
            else:
                if report.tag != "{http://pmd.sourceforge.net/report/2.0.0}pmd":
                    raise ValueError("Invalid PMD report")
                count = len(report.findall(".//{*}violation"))
                has_errors = bool(report.findall(".//{*}error") + report.findall(".//{*}configerror"))
            rows.append((label, f"{count}" + (" (analysis errors)" if has_errors else ""), "0"))
            complete = complete and count == 0 and not has_errors
        else:
            rows.append((label, "Not generated", "0"))
            complete = False

    lines = ["## API quality gates", "", f"Maven verification: **{outcome}**", "",
             "| Signal | Result | Gate |", "|---|---|---|"]
    lines.extend(f"| {label} | {value} | {gate} |" for label, value, gate in rows)
    lines.extend(["", "Scope: handwritten grpc, service, repo, cache, validation, and error packages.",
                  "Generated protobuf/stub code is excluded. Policies are versioned in pom.xml and quality/."])
    return "\n".join(lines), outcome != "success" or complete


if __name__ == "__main__":
    summary, valid = summarize(Path.cwd(), os.environ.get("VERIFY_OUTCOME", "not run"))
    print(summary)
    if not valid:
        print("Successful Maven verification is missing complete, passing quality reports.", file=sys.stderr)
        sys.exit(1)

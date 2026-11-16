#!/usr/bin/env python3
"""Fail CI on missing reports, lost test execution, skipped tests or lint errors."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def evaluate(project: Path, minimum_tests: int) -> dict:
    problems: list[str] = []
    unit = dict(tests=0, failures=0, errors=0, skipped=0, reportFiles=0)
    lint: dict[str, dict] = {}
    reports: list[dict] = []

    def parse(path: Path) -> ET.Element | None:
        relative = path.relative_to(project).as_posix()
        if not path.is_file():
            problems.append(f"Missing report: {relative}")
            return None
        data = path.read_bytes()
        reports.append(dict(path=relative, sha256=hashlib.sha256(data).hexdigest()))
        try:
            return ET.fromstring(data)
        except ET.ParseError:
            problems.append(f"Malformed XML: {relative}")
            return None

    paths = sorted((project / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not paths:
        problems.append("No Debug JUnit XML reports were generated")
    for path in paths:
        suite = parse(path)
        if suite is None:
            continue
        relative = path.relative_to(project).as_posix()
        if suite.tag != "testsuite":
            problems.append(f"Unexpected JUnit report root: {relative}")
            continue
        try:
            counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            if any(value < 0 for value in counts.values()):
                raise ValueError("Negative result count")
        except (KeyError, ValueError):
            problems.append(f"Invalid JUnit result counts: {relative}")
            continue
        cases = suite.findall("testcase")
        if len(cases) != counts["tests"]:
            problems.append(f"JUnit declared count differs from test cases: {relative}")
        if any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
            problems.append(f"JUnit contains a failed, errored or skipped test case: {relative}")
        for key, value in counts.items():
            unit[key] += value
        unit["reportFiles"] += 1
    if unit["tests"] < minimum_tests:
        problems.append(f"Expected at least {minimum_tests} unit tests; found {unit['tests']}")
    if any(unit[key] for key in ("failures", "errors", "skipped")):
        problems.append("Unit test failures, errors and skipped tests must all be zero")

    for variant in ("debug", "release"):
        path = project / f"app/build/reports/lint-results-{variant}.xml"
        tree = parse(path)
        if tree is None:
            continue
        if tree.tag != "issues":
            problems.append(f"Unexpected {variant} lint report root")
            continue
        issues = tree.findall("issue")
        errors = sum(issue.get("severity") in ("Error", "Fatal") for issue in issues)
        warnings = sum(issue.get("severity") == "Warning" for issue in issues)
        lint[variant] = dict(errors=errors, warnings=warnings, issues=len(issues))
        if errors:
            problems.append(f"{variant.title()} lint contains {errors} errors")

    return dict(passed=not problems, sourceCommit=os.environ.get("GITHUB_SHA"),
                minimumUnitTests=minimum_tests, unit=unit, lint=lint, reports=reports,
                problems=problems, formalAcceptancePassed=False,
                scope="JVM unit tests and lint; device, audio and cloud acceptance remain separate")


def markdown(result: dict) -> str:
    unit = result["unit"]
    rows = ["## Android CI results", "",
            f"Report checks: **{'passed' if result['passed'] else 'failed'}**", "",
            "| Check | Result |", "|---|---|",
            f"| JVM unit tests | {unit['tests']} tests; {unit['failures']} failures, {unit['errors']} errors, {unit['skipped']} skipped |"]
    for variant in ("debug", "release"):
        counts = result["lint"].get(variant)
        value = f"{counts['errors']} errors, {counts['warnings']} warnings" if counts else "Report missing or invalid"
        rows.append(f"| {variant.title()} lint | {value} |")
    rows += ["", "Warnings remain visible. These checks do not replace device, recording or real cloud-service acceptance."]
    if result["problems"]:
        rows += [""] + [f"- {problem}" for problem in result["problems"]]
    return "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path("."))
    parser.add_argument("--minimum-tests", type=int, default=44)
    parser.add_argument("--output", type=Path, default=Path("ci-reports/check-summary.json"))
    args = parser.parse_args()
    if args.minimum_tests < 1:
        parser.error("--minimum-tests must be positive")
    result = evaluate(args.project.resolve(), args.minimum_tests)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    summary = markdown(result)
    print(summary, end="")
    destination = os.environ.get("GITHUB_STEP_SUMMARY")
    if destination:
        with Path(destination).open("a", encoding="utf-8") as stream:
            stream.write(summary)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())

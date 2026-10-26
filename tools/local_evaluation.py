#!/usr/bin/env python3
"""Collect reproducible local evidence. No device or online-model results are inferred."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import io
from pathlib import Path
import subprocess
import unittest
import xml.etree.ElementTree as ET


def junit(folder):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    suites = {}
    files = sorted(folder.glob("TEST-*.xml"))
    if not files:
        raise ValueError(f"Missing JUnit results: {folder}")
    for path in files:
        if " " in path.name:
            raise ValueError("Duplicate generated JUnit result; clean the build")
        node = ET.parse(path).getroot()
        if node.attrib["name"] in suites:
            raise ValueError("Duplicate test suite")
        suites[node.attrib["name"]] = int(node.attrib["tests"])
        for key in totals:
            totals[key] += int(node.attrib.get(key, "0"))
    if any(totals[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Tests failed, errored or were skipped")
    return {**totals, "suites": suites}


def artifact(path, root):
    return {"artifact": str(path.relative_to(root)), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def collect(root, signer=None, include_tools=False):
    core = junit(root / "core/build/test-results/test")
    android = junit(root / "app/build/test-results/testDebugUnitTest")
    issues = ET.parse(root / "app/build/reports/lint-results-debug.xml").getroot().findall("issue")
    severity = Counter(issue.attrib["severity"] for issue in issues)
    if severity["Error"] or severity["Fatal"]:
        raise ValueError("Lint contains errors")
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    signature = "NOT_RUN"
    if signer:
        subprocess.run([str(signer), "verify", str(apk)], check=True, capture_output=True, timeout=30)
        signature = "PASSED"
    tools = {"status": "NOT_RUN", "tests": 0}
    if include_tools:
        suite = unittest.TestLoader().discover(str(root / "tools/tests"))
        result = unittest.TextTestRunner(stream=io.StringIO()).run(suite)
        if not result.wasSuccessful() or result.skipped:
            raise ValueError("Evaluation tool tests failed or were skipped")
        tools = {"status": "PASSED", "tests": result.testsRun, "failures": 0, "errors": 0, "skipped": 0}
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, text=True, capture_output=True, check=True).stdout.strip()
    dirty = bool(subprocess.run(["git", "status", "--porcelain"], cwd=root, text=True, capture_output=True, check=True).stdout.strip())
    return {
        "recordedAtUTC": datetime.now(timezone.utc).isoformat(),
        "scope": "LOCAL_JUNIT_ROBOLECTRIC_SYNTHETIC_SERVICES_GUI_PROVIDER_AND_HTTPS_CONNECTIONS",
        "coreTests": core, "androidUiTests": android,
        "toolsTests": tools, "totalTests": core["tests"] + android["tests"] + tools["tests"],
        "source": {"head": revision, "workingTreeDirty": dirty},
        "androidBuild": {**artifact(apk, root), "assembleDebug": "PASSED", "signatureVerification": signature},
        "lint": {"severities": dict(severity), "issues": dict(Counter(issue.attrib["id"] for issue in issues))},
        "renders": [{**artifact(path, root), "source": "SYNTHETIC_ROBOLECTRIC_NATIVE"} for path in sorted((root / "app/build/reports/overlay").glob("*.png"))],
        "deviceEvaluation": "NOT_RUN_LOCAL_EVIDENCE_ONLY",
        "onlineModelEvaluation": "NOT_RUN",
        "performanceEvaluation": "NOT_RUN_NO_DEVICE_METRICS",
        "ci": "Consult the commit-specific GitHub Actions result",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument("--include-tools", action="store_true")
    args = parser.parse_args()
    report = collect(args.root.resolve(), args.apksigner, args.include_tools)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Local evidence: {report['totalTests']} tests; device/online/performance remain NOT_RUN")


if __name__ == "__main__":
    main()

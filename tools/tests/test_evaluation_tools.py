import importlib.util
import json
from datetime import date, datetime
from email.parser import BytesParser
from pathlib import Path
import tempfile
import unittest
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


demo = load("prepare_demo")
logs = load("summarize_events")
evidence = load("local_evaluation")


class DemoTest(unittest.TestCase):
    def fixture(self):
        return json.loads((ROOT / "evaluation/fixtures/mail-scenarios.json").read_text(encoding="utf-8"))

    def test_emails_conflict_and_expected_values_are_prepared_without_delivery(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            result = demo.prepare(self.fixture(), output)
            self.assertEqual(3, len(list(output.glob("*.eml"))))
            message = BytesParser().parsebytes((output / "interview-a.eml").read_bytes())
            self.assertIn("2026年10月27日", message.get_payload(decode=True).decode(message.get_content_charset()))
            self.assertIn(b"DTSTART:20261027T021500Z", (output / "conflict.ics").read_bytes())
            self.assertEqual(result, json.loads((output / "expected.json").read_text()))
            with self.assertRaises(FileExistsError):
                demo.prepare(self.fixture(), output)

    def test_date_shift_recomputes_sydney_dst_from_beijing_wall_time(self):
        with tempfile.TemporaryDirectory() as folder:
            result = demo.prepare(self.fixture(), Path(folder), date(2027, 6, 1))
            start = datetime.fromisoformat(result["mails"][0]["expectedStart"])
            self.assertEqual(12, start.hour)
            self.assertEqual(10, start.astimezone(ZoneInfo("Asia/Shanghai")).hour)
            self.assertIn("2027年6月1日", result["mails"][0]["body"])
            self.assertIn("+10:00", result["existingEvent"]["start"])
    def test_fixture_ids_cannot_escape_output_or_overwrite_another_mail(self):
        for bad in ("../escape", "interview-a"):
            fixture = self.fixture()
            fixture["mails"][1]["id"] = bad
            with tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    demo.prepare(fixture, Path(folder))
                self.assertEqual([], list(Path(folder).iterdir()))


class LogTest(unittest.TestCase):
    def report(self, rows):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows))
            return logs.summarize(path)

    def event(self, now, kind, **kwargs):
        return {"monotonicMillis": now, "event": {"kind": kind, "sessionId": "s", "epoch": 0, **kwargs}}

    def test_actions_models_and_verified_results_have_separate_meanings(self):
        report = self.report([self.event(0, "RUN_STARTED"), self.event(5, "RESULT_UNKNOWN", actionId="key"),
            self.event(10, "ACTION_SETTLED", elapsedMillis=5),
            self.event(20, "MODEL_SUCCEEDED", elapsedMillis=8), self.event(30, "RESULT_VERIFIED", actionId="key"),
            self.event(40, "RESULT_VERIFIED", actionId="key")])
        self.assertEqual(1, report["recordedResults"]["uniqueVerified"])
        self.assertEqual(0, report["recordedResults"]["uniqueUnknown"])
        self.assertEqual(8, report["modelMillis"]["p50"])
        self.assertEqual("NOT_INFERRED_FROM_ACTION_LOGS", report["verifiedTaskSuccess"])

    def test_invalid_nan_boolean_negative_or_missing_result_keys_are_rejected(self):
        for row in (self.event(float("nan"), "X"), self.event(True, "X"), self.event(1, "X", epoch=-1),
                    self.event(1, "MODEL_SUCCEEDED", elapsedMillis=-1), self.event(1, "RESULT_VERIFIED")):
            with self.assertRaises(ValueError):
                self.report([row])

    def test_clock_reset_does_not_fabricate_cross_boot_completion_time(self):
        report = self.report([self.event(100, "X"), self.event(200, "X"), self.event(0, "X"), self.event(50, "X")])
        self.assertEqual({"segments": 2, "observedMillis": 150, "note": report["timeline"]["note"]}, report["timeline"])


class EvidenceTest(unittest.TestCase):
    def test_failing_or_skipped_junit_cannot_be_reported_as_passed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "TEST-test.xml").write_text('<testsuite name="test" tests="1" failures="0" skipped="1"/>')
            with self.assertRaises(ValueError):
                evidence.junit(path)

    def test_missing_and_duplicate_test_results_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            with self.assertRaises(ValueError):
                evidence.junit(path)
            (path / "TEST-test 2.xml").write_text('<testsuite name="test" tests="1"/>')
            with self.assertRaises(ValueError):
                evidence.junit(path)


if __name__ == "__main__":
    unittest.main()

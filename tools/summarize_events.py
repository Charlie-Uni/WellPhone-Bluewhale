#!/usr/bin/env python3
"""Summarize structured logs. Action settlement is not verified task success."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * fraction
    low = int(index)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (index - low)


def summarize(path):
    counts = Counter()
    durations = []
    models = []
    results = {}
    segments = []
    start = previous = None
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        event = row["event"]
        now = row["monotonicMillis"]
        epoch = event["epoch"]
        if (type(now) not in (int, float) or not math.isfinite(now) or now < 0 or
                type(epoch) is not int or epoch < 0 or not isinstance(event["kind"], str) or
                not isinstance(event["sessionId"], str)):
            raise ValueError(f"Invalid event at line {number}")
        if previous is not None and now < previous:
            segments.append(previous - start)
            start = now
        if start is None:
            start = now
        previous = now
        counts[event["kind"]] += 1
        duration = event.get("elapsedMillis")
        if duration is not None:
            if type(duration) not in (int, float) or not math.isfinite(duration) or duration < 0:
                raise ValueError(f"Negative duration at line {number}")
            if event["kind"] == "ACTION_SETTLED":
                durations.append(duration)
            if event["kind"] == "MODEL_SUCCEEDED":
                models.append(duration)
        if event["kind"] in ("RESULT_VERIFIED", "RESULT_UNKNOWN"):
            key = event.get("actionId")
            if not isinstance(key, str) or not key:
                raise ValueError(f"Missing result key at line {number}")
            item = (event["sessionId"], key)
            previous_result = results.get(item)
            if previous_result is None or epoch >= previous_result[0]:
                results[item] = (epoch, event["kind"])
    if previous is not None:
        segments.append(previous - start)
    return {"events": dict(counts), "settledActionMillis": {
        "count": len(durations), "p50": percentile(durations, .5), "p95": percentile(durations, .95)},
        "modelMillis": {"count": len(models), "p50": percentile(models, .5), "p95": percentile(models, .95)},
        "recordedResults": {"uniqueVerified": sum(value[1] == "RESULT_VERIFIED" for value in results.values()),
                            "uniqueUnknown": sum(value[1] == "RESULT_UNKNOWN" for value in results.values()),
                            "scope": "LATEST_RESULT_PER_SESSION_ITEM_AND_CONTROL_EPOCH",
                            "evidence": "REPORTED_BY_WORKFLOW_REOPEN_VERIFIER_NOT_INDEPENDENT_APP_PROOF"},
        "timeline": {"segments": len(segments), "observedMillis": sum(segments),
                     "note": "Clock resets split segments; this is log coverage, not task completion time"},
        "verifiedTaskSuccess": "NOT_INFERRED_FROM_ACTION_LOGS"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.events), ensure_ascii=False, indent=2))

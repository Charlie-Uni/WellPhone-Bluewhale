#!/usr/bin/env python3
"""Summarize structured logs. Action settlement is not verified task success."""
import argparse
from collections import Counter
import json
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
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        event = row["event"]
        if not isinstance(row["monotonicMillis"], (int, float)) or event["epoch"] < 0:
            raise ValueError(f"Invalid event at line {number}")
        counts[event["kind"]] += 1
        duration = event.get("elapsedMillis")
        if event["kind"] == "ACTION_SETTLED" and duration is not None:
            if duration < 0:
                raise ValueError(f"Negative duration at line {number}")
            durations.append(duration)
    return {"events": dict(counts), "settledActionMillis": {
        "count": len(durations), "p50": percentile(durations, .5), "p95": percentile(durations, .95)},
        "verifiedTaskSuccess": "NOT_INFERRED_FROM_ACTION_LOGS"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.events), ensure_ascii=False, indent=2))

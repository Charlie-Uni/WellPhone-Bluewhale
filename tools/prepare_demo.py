#!/usr/bin/env python3
"""Prepare synthetic .eml/.ics inputs. Never sends mail or writes to an account."""
import argparse
from copy import deepcopy
from datetime import datetime, date, timedelta, timezone
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import format_datetime
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo


def prepare(fixture, output, start_date=None):
    data = deepcopy(fixture)
    ids = [mail["id"] for mail in data["mails"]]
    if not ids or len(ids) > 5 or len(set(ids)) != len(ids) or any(not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", value) for value in ids):
        raise ValueError("Mail ids must be unique safe file names; at most five inputs")
    source = ZoneInfo(data["sourceZone"])
    target = ZoneInfo(data["targetZone"])
    first = datetime.fromisoformat(data["mails"][0]["expectedStart"]).astimezone(source).date()
    shift = (start_date - first).days if start_date else 0

    def move(raw, zone):
        original = datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(source)
        local = original.replace(tzinfo=None) + timedelta(days=shift)
        instants = {local.replace(tzinfo=source, fold=fold).astimezone(timezone.utc) for fold in (0, 1)
                    if local.replace(tzinfo=source, fold=fold).astimezone(timezone.utc).astimezone(source).replace(tzinfo=None) == local}
        if len(instants) != 1:
            raise ValueError("Ambiguous or nonexistent source local time")
        return instants.pop().astimezone(zone)

    for mail in data["mails"]:
        def move_date(match):
            value = date(*map(int, match.groups())) + timedelta(days=shift)
            return f"{value.year}年{value.month}月{value.day}日"
        mail["body"] = re.sub(r"(\d{4})年(\d{1,2})月(\d{1,2})日", move_date, mail["body"])
        for key in ("expectedStart", "expectedEnd", "expectedDeadline", "expectedReminder"):
            if key in mail:
                mail[key] = move(mail[key], target).isoformat()
        mail["sentAt"] = move(mail["sentAt"], timezone.utc).isoformat()
    for key in ("start", "end"):
        data["existingEvent"][key] = move(data["existingEvent"][key], target).isoformat()

    output.mkdir(parents=True, exist_ok=True)
    paths = [output / f"{mail['id']}.eml" for mail in data["mails"]]
    paths += [output / "conflict.ics", output / "expected.json"]
    if any(path.exists() for path in paths):
        raise FileExistsError("Use an empty output directory; existing inputs are preserved")
    for mail, path in zip(data["mails"], paths):
        message = EmailMessage(policy=SMTP)
        message["From"] = mail["sender"]
        message["To"] = "candidate@example.test"
        message["Subject"] = mail["subject"]
        message["Date"] = format_datetime(datetime.fromisoformat(mail["sentAt"]))
        message["Message-ID"] = f"<{mail['id']}-{shift}@octopus.example.test>"
        message.set_content(mail["body"])
        path.write_bytes(message.as_bytes())
    event = data["existingEvent"]
    def utc(raw):
        return datetime.fromisoformat(raw).astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    # ASCII name keeps each ICS content line within the 75-octet folding limit.
    calendar = "\r\n".join(["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Octopus//Synthetic Demo//EN",
        "BEGIN:VEVENT", f"UID:octopus-conflict-{shift}@example.test", "DTSTAMP:20261023T000000Z",
        f"DTSTART:{utc(event['start'])}", f"DTEND:{utc(event['end'])}", "SUMMARY:Octopus demo existing event",
        "END:VEVENT", "END:VCALENDAR", ""])
    (output / "conflict.ics").write_bytes(calendar.encode("utf-8"))
    (output / "expected.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1] / "evaluation/fixtures/mail-scenarios.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--start-date", type=date.fromisoformat, help="Source-zone date of the first interview, YYYY-MM-DD")
    args = parser.parse_args()
    data = prepare(json.loads(args.fixture.read_text(encoding="utf-8")), args.output, args.start_date)
    print(f"Prepared {len(data['mails'])} SYNTHETIC emails, one conflict and expected.json; no account accessed")


if __name__ == "__main__":
    main()

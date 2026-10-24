#!/usr/bin/env python3
"""Read-only device inventory. Does not enable permissions or certify AC01."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "acceptance": "AC01",
              "status": "PENDING", "behaviorVerified": False}

    def run(parts):
        result = subprocess.run([args.adb, *parts], capture_output=True, text=True, timeout=15, check=True)
        return result.stdout.strip()

    try:
        devices = [line.split() for line in run(["devices"]).splitlines()[1:] if line.strip()]
        online = [parts[0] for parts in devices if len(parts) >= 2 and parts[1] == "device"]
        if args.serial:
            if args.serial not in online:
                raise RuntimeError("Requested device is not authorized/online")
            serial = args.serial
        elif len(online) == 1:
            serial = online[0]
        else:
            raise RuntimeError("Connect exactly one authorized phone or specify --serial")
        prefix = ["-s", serial, "shell"]
        report["device"] = {
            key: run([*prefix, "getprop", prop]) for key, prop in {
                "manufacturer": "ro.product.manufacturer", "model": "ro.product.model",
                "sdk": "ro.build.version.sdk", "build": "ro.build.fingerprint"}.items()
        }
        report["shellIdentity"] = run([*prefix, "id"])
        report["apkMinSdkSatisfied"] = int(report["device"]["sdk"]) >= 34
        report["isolationFlagsVerified"] = False
        report["displaySize"] = run([*prefix, "wm", "size"])
        report["density"] = run([*prefix, "wm", "density"])
        report["appVersions"] = {}
        for package in ("com.google.android.gm", "com.google.android.calendar", "com.octopus.agent"):
            dump = run([*prefix, "dumpsys", "package", package])
            match = re.search(r"versionName=([^\s]+)", dump)
            report["appVersions"][package] = match.group(1) if match else "NOT_INSTALLED_OR_UNREADABLE"
        report["status"] = "INVENTORY_ONLY"
        report["manualChecks"] = ["Shizuku UserService UID", "effective display flags",
                                  "foreground Chinese composing", "window binding", "time fields", "audio"]
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        report["status"] = "BLOCKED"
        # No raw dumpsys or stderr persisted.
        report["reason"] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{report['status']}: {args.output}")
    return 2 if report["status"] == "BLOCKED" else 0


if __name__ == "__main__":
    sys.exit(main())

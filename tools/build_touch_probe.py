#!/usr/bin/env python3
"""Build the emulator-only adapter probe. A failed build never publishes an old jar."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def build(sdk, output, android_jar=None, javac=None):
    if output.exists():
        raise FileExistsError("Use a fresh output path; existing artifacts are preserved")
    if output.suffix != ".jar":
        raise ValueError("Output must be a .jar")
    source = Path(__file__).resolve().parent / "android/DisplayTouchProbe.java"
    android = android_jar or sdk / "platforms/android-35/android.jar"
    compiler = javac or (str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else "javac")
    with tempfile.TemporaryDirectory(prefix="octopus-touch-probe-") as folder:
        root = Path(folder)
        classes = root / "classes"
        classes.mkdir()
        subprocess.run([str(compiler), "--release", "8", "-cp", str(android), "-d", str(classes), str(source)],
                       check=True, timeout=60)
        artifact = root / "probe.jar"
        subprocess.run([str(sdk / "build-tools/35.0.0/d8"), "--lib", str(android), "--min-api", "34",
                        "--output", str(artifact), str(classes / "DisplayTouchProbe.class")], check=True, timeout=60)
        with zipfile.ZipFile(artifact) as archive:
            if "classes.dex" not in archive.namelist() or archive.testzip() is not None:
                raise ValueError("Invalid probe archive")
        data = artifact.read_bytes()
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("xb") as target:
            target.write(data)
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, help="Optional non-synced official API 35 stub")
    parser.add_argument("--javac", type=Path)
    args = parser.parse_args()
    digest = build(args.sdk.resolve(), args.output, args.android_jar, args.javac)
    print(f"EMULATOR_ONLY probe built: {args.output}; sha256={digest}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Render a small stratified set of qualifying HEICs on the attached TV."""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

from run_tv_batch import ACTIVITY, PACKAGE, REMOTE_SAMPLE, PRIVATE_SAMPLE, select_samples


def adb(*args: str, timeout: float = 30) -> str:
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"ADB command failed ({args[0]})")
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mount_root", type=Path, help="root of the read-only SMB mount")
    parser.add_argument("--count", type=int, default=6, help="number of preview images (default: 6)")
    parser.add_argument("--seconds", type=int, default=12, help="seconds to show each image")
    args = parser.parse_args()
    if args.count < 1 or args.seconds < 1:
        parser.error("count and seconds must be positive")

    root = args.mount_root / "Photos Library.photoslibrary" / "originals"
    if not root.is_dir():
        parser.error("mount root must contain Photos Library.photoslibrary/originals")
    apk = Path(__file__).parent / "android/build/outputs/apk/debug/heif-probe-debug.apk"
    if not apk.is_file():
        parser.error("build the probe first with :heif-probe:assembleDebug")

    samples, examined = select_samples(root, args.count)
    print(f"preview_set={len(samples)} metadata_checked={examined}", flush=True)
    adb("install", "-r", str(apk))
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    try:
        for index, (path, width, height, group) in enumerate(samples, 1):
            pushed = subprocess.run(["adb", "push", str(path), REMOTE_SAMPLE], capture_output=True, text=True, timeout=120)
            if pushed.returncode != 0:
                raise RuntimeError(f"staging failed for preview {index}; source detail suppressed")
            adb("shell", "run-as", PACKAGE, "cp", REMOTE_SAMPLE, PRIVATE_SAMPLE)
            adb("shell", "unlink", REMOTE_SAMPLE)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", ACTIVITY, "--ei", "preview_index", str(index))
            print(f"displaying={index}/{len(samples)} category={group} dimensions={width}x{height}", flush=True)
            time.sleep(args.seconds)
        # Keep the last rendered bitmap on screen while removing the staged source file.
        subprocess.run(["adb", "shell", "run-as", PACKAGE, "unlink", PRIVATE_SAMPLE], capture_output=True, text=True, timeout=10)
        print("preview complete; last decoded image remains on screen", flush=True)
        return 0
    finally:
        subprocess.run(["adb", "shell", "unlink", REMOTE_SAMPLE], capture_output=True, text=True, timeout=10)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"preview failed: {error.__class__.__name__}", file=sys.stderr)
        sys.exit(2)

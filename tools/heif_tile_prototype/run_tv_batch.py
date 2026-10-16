#!/usr/bin/env python3
"""Decode a privacy-safe, stratified HEIC sample on the attached TV.

The input tree must be an explicitly read-only SMB mount. This script keeps file paths
in memory, stages one image at a time, and prints only sample numbers and decode metrics.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.googletv.heiftileprobe"
ACTIVITY = f"{PACKAGE}/.HeifTileProbeActivity"
REMOTE_SAMPLE = "/data/local/tmp/heif-batch-sample.heic"
PRIVATE_SAMPLE = "files/sample.heic"
RESULT_FILE = "files/batch-results.tsv"
TARGETS = {"landscape": 717, "portrait": 682, "wide": 139}


def command(args: list[str], *, timeout: float = 30) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def inventory_by_directory(root: Path) -> list[list[Path]]:
    directories = sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith("."))
    result: list[list[Path]] = []
    for directory in directories:
        paths: list[Path] = []
        for current, names, files in os.walk(directory):
            names[:] = sorted(name for name in names if not name.startswith("."))
            for name in sorted(files):
                if name.startswith(".") or Path(name).suffix.lower() not in (".heic", ".heif"):
                    continue
                paths.append(Path(current) / name)
        result.append(paths)
    return result


def round_robin(paths_by_directory: list[list[Path]]):
    longest = max((len(paths) for paths in paths_by_directory), default=0)
    for offset in range(longest):
        for paths in paths_by_directory:
            if offset < len(paths):
                yield paths[offset]


def read_primary_dimensions(path: Path) -> tuple[int, int] | None:
    try:
        result = command(["heif-info", str(path)], timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    matches = re.findall(r"^\s*image:\s*(\d+)x(\d+).*?$", result.stdout, re.MULTILINE)
    if not matches:
        return None
    primary = re.search(
        r"^\s*image:\s*(\d+)x(\d+).*?\bprimary\b",
        result.stdout,
        re.MULTILINE,
    )
    pair = primary.groups() if primary else matches[0]
    return int(pair[0]), int(pair[1])


def category(width: int, height: int) -> str:
    if (width, height) == (5712, 4284):
        return "landscape"
    if (width, height) == (4284, 5712):
        return "portrait"
    return "wide"


def quotas(count: int) -> dict[str, int]:
    total = sum(TARGETS.values())
    raw = {name: count * size / total for name, size in TARGETS.items()}
    result = {name: math.floor(value) for name, value in raw.items()}
    remainder = count - sum(result.values())
    order = sorted(TARGETS, key=lambda name: (raw[name] - result[name], TARGETS[name]), reverse=True)
    for name in order[:remainder]:
        result[name] += 1
    return result


def select_samples(root: Path, count: int) -> tuple[list[tuple[Path, int, int, str]], int]:
    paths_by_directory = inventory_by_directory(root)
    wanted = quotas(count)
    selected: dict[str, list[tuple[Path, int, int, str]]] = {name: [] for name in wanted}
    examined = 0
    for path in round_robin(paths_by_directory):
        examined += 1
        dims = read_primary_dimensions(path)
        if dims is None or dims[0] * dims[1] < 24_000_000:
            continue
        group = category(*dims)
        if len(selected[group]) < wanted[group]:
            selected[group].append((path, *dims, group))
        if all(len(selected[name]) == wanted[name] for name in wanted):
            break
        if examined % 100 == 0:
            found = sum(len(group) for group in selected.values())
            print(f"metadata checked={examined}; qualifying samples selected={found}/{count}", flush=True)

    samples = [sample for name in wanted for sample in selected[name]]
    if len(samples) != count:
        found = ", ".join(f"{name}={len(selected[name])}" for name in wanted)
        raise RuntimeError(f"could not fill requested category quotas ({found})")
    return samples, examined


def adb(*args: str, timeout: float = 30) -> str:
    result = command(["adb", *args], timeout=timeout)
    if result.returncode != 0:
        # Do not echo stderr: adb may include a private source path in diagnostics.
        raise RuntimeError(f"ADB command failed ({args[0]})")
    return result.stdout.strip()


def read_results() -> dict[int, tuple[int, str]]:
    result = command(["adb", "shell", "run-as", PACKAGE, "cat", RESULT_FILE], timeout=15)
    if result.returncode != 0:
        return {}
    parsed: dict[int, tuple[int, str]] = {}
    for line in result.stdout.splitlines():
        fields = line.split("\t", 2)
        if len(fields) == 3 and fields[0].isdigit() and fields[1].isdigit():
            parsed[int(fields[0])] = (int(fields[1]), fields[2])
    return parsed


def run_batch(samples: list[tuple[Path, int, int, str]], apk: Path) -> tuple[int, int, list[int]]:
    adb("install", "-r", str(apk))
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    passed = failed = 0
    elapsed_values: list[int] = []
    try:
        for index, (path, width, height, group) in enumerate(samples, 1):
            pushed = command(["adb", "push", str(path), REMOTE_SAMPLE], timeout=120)
            if pushed.returncode != 0:
                raise RuntimeError(f"staging failed for sample {index}; source detail suppressed")
            adb("shell", "run-as", PACKAGE, "cp", REMOTE_SAMPLE, PRIVATE_SAMPLE)
            adb("shell", "unlink", REMOTE_SAMPLE)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", ACTIVITY, "--ei", "probe_index", str(index))

            deadline = time.monotonic() + 90
            row = None
            while time.monotonic() < deadline:
                row = read_results().get(index)
                if row is not None:
                    break
                time.sleep(1)
            if row is None:
                failed += 1
                print(f"sample={index}/{len(samples)} FAIL timeout category={group} dimensions={width}x{height}", flush=True)
                command(["adb", "shell", "am", "force-stop", PACKAGE], timeout=10)
                continue

            elapsed_ms, outcome = row
            elapsed_values.append(elapsed_ms)
            is_success = outcome.startswith("PASS ")
            if is_success:
                passed += 1
            else:
                failed += 1
            print(
                f"sample={index}/{len(samples)} {'PASS' if is_success else 'FAIL'} "
                f"category={group} dimensions={width}x{height} elapsed_ms={elapsed_ms}",
                flush=True,
            )
    finally:
        command(["adb", "shell", "unlink", REMOTE_SAMPLE], timeout=10)
        command(["adb", "shell", "run-as", PACKAGE, "unlink", PRIVATE_SAMPLE], timeout=10)
        command(["adb", "uninstall", PACKAGE], timeout=30)
        command(["adb", "shell", "am", "start", "-n", "com.googletv.screensaver/.MainActivity"], timeout=15)
    return passed, failed, elapsed_values


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mount_root", type=Path, help="root of the read-only SMB mount")
    parser.add_argument("--count", type=int, default=100, help="number of qualifying samples (default: 100)")
    args = parser.parse_args()
    if args.count < 1 or args.count > sum(TARGETS.values()):
        parser.error("count must be between 1 and the known qualifying library count")

    root = args.mount_root / "Photos Library.photoslibrary" / "originals"
    if not root.is_dir():
        parser.error("mount root must contain Photos Library.photoslibrary/originals")
    apk = Path(__file__).parent / "android/build/outputs/apk/debug/heif-probe-debug.apk"
    if not apk.is_file():
        parser.error("build the probe first with :heif-probe:assembleDebug")

    print(f"Selecting {args.count} qualifying samples across dimension groups…", flush=True)
    samples, examined = select_samples(root, args.count)
    selected_counts = {name: sum(1 for sample in samples if sample[3] == name) for name in TARGETS}
    print(f"selected={len(samples)} metadata_checked={examined} groups={selected_counts}", flush=True)

    passed, failed, durations = run_batch(samples, apk)
    if durations:
        ordered = sorted(durations)
        median = ordered[len(ordered) // 2]
        p95 = ordered[min(len(ordered) - 1, math.ceil(len(ordered) * 0.95) - 1)]
        print(f"summary: passed={passed} failed={failed} median_ms={median} p95_ms={p95} max_ms={max(ordered)}")
    else:
        print(f"summary: passed={passed} failed={failed} no completed decodes")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        # Keep diagnostic output private-path-free.
        print(f"batch aborted: {error.__class__.__name__}", file=sys.stderr)
        sys.exit(2)

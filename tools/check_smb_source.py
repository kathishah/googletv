#!/usr/bin/env python3
"""Read-only SMB source check. Never accepts or prints passwords or filenames."""

import argparse
import os
import re
import socket
import subprocess
from pathlib import Path

PHOTO_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".heic", ".heif", ".tif", ".tiff", ".webp",
    ".dng", ".raw", ".cr2", ".cr3", ".nef", ".nrw", ".arw", ".orf",
    ".rw2", ".raf", ".pef", ".srw",
}
VIDEO_EXTENSIONS = {
    ".mov", ".mp4", ".m4v", ".avi", ".mts", ".m2ts", ".3gp", ".mpg",
    ".mpeg", ".wmv", ".mkv", ".dv",
}
SMB_SHARE_TYPES = r"Disk|Pipe|Printer|Comm|IPC"


def check_host(host: str) -> bool:
    try:
        with socket.create_connection((host, 445), timeout=5):
            print("SMB TCP port 445: reachable")
        return True
    except OSError as error:
        print(f"SMB TCP port 445: unavailable ({type(error).__name__})")
        return False


def run_smbutil(arguments: list[str]) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            ["smbutil", *arguments],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"  unavailable ({type(error).__name__})")
        return None


def list_share_endpoints(host: str, *, mode: str = "current session") -> None:
    print(f"SMB share endpoints ({mode}):")
    options = ["-N"]
    if mode == "guest only":
        options = ["-f", "-N", "-g"]
    elif mode == "anonymous only":
        options = ["-f", "-N", "-a"]
    result = run_smbutil(["view", *options, f"//{host}"])
    if result is None:
        return
    if result.returncode == 0:
        endpoints = re.findall(
            rf"^[ \t]*([^ \t\r\n]+)[ \t]+({SMB_SHARE_TYPES})\b",
            result.stdout,
            flags=re.MULTILINE | re.IGNORECASE,
        )
        if endpoints:
            for share, share_type in endpoints:
                print(f"  //{host}/{share} ({share_type})")
        else:
            print("  listing accepted; no share endpoints found")
            return
        print("  listing accepted")
    elif "auth" in result.stderr.lower() or result.returncode == 77:
        print("  rejected (authentication required or this access mode is disabled)")
    else:
        print(f"  unavailable (exit {result.returncode})")


def check_server_identity(host: str) -> None:
    print("SMB server identity (current session):")
    result = run_smbutil(["identity", "-N", f"//{host}"])
    if result is None:
        return
    if result.returncode == 0 and result.stdout.strip():
        for line in result.stdout.splitlines():
            if line.strip():
                print(f"  {line.strip()}")
    elif result.returncode == 0:
        print("  identity query accepted; server returned no identity text")
    else:
        print(f"  unavailable (exit {result.returncode})")


def check_library(mount: Path, library: str) -> bool:
    root = mount / library
    originals = root / "originals"
    if not mount.is_dir():
        print("Share mount: absent; mount the SMB share, then rerun")
        return False
    print("Share mount: present")
    if not root.is_dir() or not originals.is_dir():
        print("Photos library originals path: unavailable")
        return False
    print("Photos library originals path: present")

    try:
        with os.scandir(originals) as entries:
            buckets = [entry.path for entry in entries if entry.is_dir(follow_symlinks=False)]
        print(f"Originals subdirectories: {len(buckets)}")
        pending = [str(originals)]
        photo_formats: dict[str, int] = {}
        video_formats: dict[str, int] = {}
        photos = videos = other_files = readable = scan_errors = 0
        while pending:
            directory = pending.pop()
            try:
                with os.scandir(directory) as entries:
                    for entry in entries:
                        try:
                            if entry.is_dir(follow_symlinks=False):
                                pending.append(entry.path)
                                continue
                            if not entry.is_file(follow_symlinks=False):
                                continue
                        except OSError:
                            scan_errors += 1
                            continue

                        suffix = Path(entry.name).suffix.lower()
                        if suffix in PHOTO_EXTENSIONS:
                            photos += 1
                            photo_formats[suffix] = photo_formats.get(suffix, 0) + 1
                            if readable < 3:
                                try:
                                    with open(entry.path, "rb") as stream:
                                        if stream.read(16):
                                            readable += 1
                                except OSError:
                                    pass
                        elif suffix in VIDEO_EXTENSIONS:
                            videos += 1
                            video_formats[suffix] = video_formats.get(suffix, 0) + 1
                        else:
                            other_files += 1
            except OSError:
                scan_errors += 1

        print(f"Photo files in originals (extension-based): {photos}")
        print(f"Video files in originals (extension-based): {videos}")
        print(f"Other files in originals: {other_files}")
        print(
            "Photo formats: "
            + (", ".join(f"{ext} {count}" for ext, count in sorted(photo_formats.items())) or "none")
        )
        print(
            "Video formats: "
            + (", ".join(f"{ext} {count}" for ext, count in sorted(video_formats.items())) or "none")
        )
        print(f"Sample image files readable: {readable}")
        if scan_errors:
            print(f"Inventory scan errors: {scan_errors}")
        return (readable > 0 or videos > 0) and scan_errors == 0
    except OSError as error:
        print(f"Sample read: failed ({type(error).__name__})")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="Mac mini IP address or hostname")
    parser.add_argument("--share", default="Photos", help="mounted SMB share name")
    parser.add_argument(
        "--mount-path",
        type=Path,
        help="path where the SMB share is mounted (default: /Volumes/<share>)",
    )
    parser.add_argument(
        "--library", default="Photos Library.photoslibrary", help="library folder inside share"
    )
    args = parser.parse_args()

    list_share_endpoints(args.host)
    check_server_identity(args.host)
    list_share_endpoints(args.host, mode="guest only")
    list_share_endpoints(args.host, mode="anonymous only")
    if not check_host(args.host):
        return 1
    mount_path = args.mount_path or (Path("/Volumes") / args.share)
    return 0 if check_library(mount_path, args.library) else 2


if __name__ == "__main__":
    raise SystemExit(main())

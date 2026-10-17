#!/usr/bin/env python3
"""Read-only local probe of shared-album media paths in a Photos library.

Run this on the Mac that has the library mounted locally. It does not copy,
export, rename, or modify photos, and it does not write to the Photos database.
By default, it samples up to N distinct shared albums per raw Photos
participant-role code and checks one referenced asset per album. Use
--album-title to check every referenced asset in a specific shared album.
Readable path counts describe existing files at database references; they do
not measure Save to Library/import completion or background progress.
"""

from __future__ import annotations

import argparse
import random
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path, PurePosixPath


def connect_read_only(database: Path) -> sqlite3.Connection:
    # mode=ro prevents database writes; query_only is an additional guard.
    uri = database.as_uri() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=15)
    connection.execute("PRAGMA query_only = ON")
    return connection


def relative_reference(directory: str, filename: str) -> Path:
    """Normalize private DB path components without allowing traversal."""
    directory = directory.replace("\\", "/").strip()
    filename = filename.replace("\\", "/").strip()
    marker = "Photos Library.photoslibrary/"
    if marker in directory:
        directory = directory.split(marker, 1)[1]

    parts = [part for part in PurePosixPath(directory).parts if part not in ("/", "", ".")]
    file_parts = [part for part in PurePosixPath(filename).parts if part not in ("/", "", ".")]
    if ".." in parts or ".." in file_parts:
        raise ValueError("database reference contains parent traversal")
    return Path(*parts, *file_parts)


def candidate_paths(library: Path, relative: Path) -> list[tuple[str, Path]]:
    package_name = library.name
    parts = relative.parts
    if parts and parts[0] == package_name:
        relative = Path(*parts[1:])

    roots = [
        ("package-relative", library),
        ("originals", library / "originals"),
        ("cloudsharing-data", library / "scopes" / "cloudsharing" / "data"),
        ("cloudsharing-resources", library / "scopes" / "cloudsharing" / "resources"),
        ("cpl", library / "resources" / "cpl"),
    ]
    candidates: list[tuple[str, Path]] = []
    seen: set[Path] = set()
    for label, root in roots:
        path = (root / relative).resolve(strict=False)
        if path == library or library in path.parents:
            if path not in seen:
                candidates.append((label, path))
                seen.add(path)
    return candidates


def probe_reference(library: Path, directory: str, filename: str) -> tuple[bool, str, bool]:
    relative = relative_reference(directory, filename)
    parent_found = False
    for label, path in candidate_paths(library, relative):
        if path.parent.is_dir():
            parent_found = True
        if path.is_file():
            try:
                with path.open("rb") as stream:
                    readable = bool(stream.read(16))
            except OSError:
                readable = False
            return parent_found, label, readable
    return parent_found, "none", False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "library",
        type=Path,
        help="local path to Photos Library.photoslibrary on this Mac",
    )
    parser.add_argument(
        "--albums-per-role",
        type=int,
        default=20,
        help="maximum distinct shared albums to sample for each role code (default: 20)",
    )
    parser.add_argument(
        "--album-title",
        help="check every asset reference in the shared album with this exact title",
    )
    parser.add_argument("--seed", type=int, default=20261016, help="repeatable sample seed")
    args = parser.parse_args()

    library = args.library.expanduser().resolve()
    database = library / "database" / "Photos.sqlite"
    if not library.is_dir():
        parser.error("library package not found at the supplied local path")
    if not database.is_file():
        parser.error("database/Photos.sqlite was not found inside the library")
    if args.albums_per_role < 1:
        parser.error("--albums-per-role must be at least 1")

    try:
        connection = connect_read_only(database)
    except sqlite3.Error as error:
        print(f"Could not open the database read-only: {error}", file=sys.stderr)
        print("Close Photos and rerun; the database and WAL must be readable.", file=sys.stderr)
        return 2

    try:
        rows = connection.execute(
            """
            SELECT s.Z_PK, s.ZTITLE, s.ZCURRENTPARTICIPANTROLE,
                   s.ZASSETCOUNT, s.ZPHOTOSCOUNT, s.ZVIDEOSCOUNT,
                   a.ZDIRECTORY, a.ZFILENAME
            FROM ZSHARE AS s
            JOIN ZASSET AS a ON a.ZCOLLECTIONSHARE = s.Z_PK
            ORDER BY s.Z_PK, a.Z_PK
            """
        ).fetchall()
    except sqlite3.Error as error:
        print(f"Could not query the expected shared-album fields: {error}", file=sys.stderr)
        print("Photos may have changed its private database schema.", file=sys.stderr)
        return 2
    finally:
        connection.close()

    # One asset reference per share group keeps the sample spread across albums.
    groups: dict[object, dict[str, object]] = {}
    for share_id, title, role, asset_count, photo_count, video_count, directory, filename in rows:
        group = groups.setdefault(
            share_id,
            {
                "title": title or "(untitled shared album)",
                "role": role,
                "asset_count": asset_count,
                "photo_count": photo_count,
                "video_count": video_count,
                "refs": [],
            },
        )
        group["refs"].append((directory, filename))

    by_role: dict[object, list[tuple[object, dict[str, object]]]] = defaultdict(list)
    for share_id, group in groups.items():
        by_role[group["role"]].append((share_id, group))

    # Record shared DB paths without printing them. Different albums can point
    # to the same file, so an album's match count is not necessarily a count of
    # distinct media or newly imported items.
    path_owners: dict[Path, set[object]] = defaultdict(set)
    for share_id, group in groups.items():
        for directory, filename in group["refs"]:
            if not directory or not filename:
                continue
            try:
                path_owners[relative_reference(str(directory), str(filename))].add(share_id)
            except ValueError:
                continue

    rng = random.Random(args.seed)
    print(f"Library: {library}")
    print("Database access: read-only; no photo files are copied or changed")
    print(f"Shared albums with asset references: {len(groups)}")

    if args.album_title:
        matches = [
            group
            for group in groups.values()
            if str(group["title"]).casefold() == args.album_title.strip().casefold()
        ]
        if not matches:
            print(f"No shared album matched the exact title: {args.album_title!r}")
            return 1

        for album in matches:
            current_share_ids = {
                share_id for share_id, group in groups.items() if group is album
            }
            refs = album["refs"]
            with_path = [(directory, filename) for directory, filename in refs if directory and filename]
            parent_count = file_count = readable_count = 0
            location_counts: dict[str, int] = defaultdict(int)
            matched_db_paths: set[Path] = set()
            readable_db_paths: set[Path] = set()
            for directory, filename in with_path:
                try:
                    parent_exists, location, readable = probe_reference(
                        library, str(directory), str(filename)
                    )
                    db_path = relative_reference(str(directory), str(filename))
                except (OSError, ValueError):
                    parent_exists, location, readable = False, "invalid-reference", False
                    db_path = None
                parent_count += int(parent_exists)
                if location != "none":
                    file_count += 1
                    location_counts[location] += 1
                    if db_path is not None:
                        matched_db_paths.add(db_path)
                readable_count += int(readable)
                if readable and db_path is not None:
                    readable_db_paths.add(db_path)

            shared_paths = {
                path
                for path in matched_db_paths
                if len(path_owners.get(path, set())) > 1
            }
            other_share_ids = {
                share_id
                for path in shared_paths
                for share_id in path_owners[path]
                if share_id not in current_share_ids
            }
            other_titles = sorted(
                {
                    str(groups[share_id]["title"])
                    for share_id in other_share_ids
                },
                key=str.casefold,
            )

            print(f"\nAlbum: {album['title']} | role code={album['role']!r}")
            print(
                f"Database counts: assets={album['asset_count']!r}, "
                f"photos={album['photo_count']!r}, videos={album['video_count']!r}"
            )
            print(
                f"Asset rows={len(refs)} | rows with directory+filename={len(with_path)} | "
                f"candidate parent exists={parent_count} | exact files found={file_count} | "
                f"readable={readable_count} | unique matched DB paths={len(matched_db_paths)} | "
                f"unique readable DB paths={len(readable_db_paths)}"
            )
            print(f"Matched rows by location: {dict(location_counts) if location_counts else 'none'}")
            print(
                f"Matched DB paths also referenced by other shared albums: {len(shared_paths)}"
                + (f" ({', '.join(other_titles)})" if other_titles else "")
            )
        print("Readable counts test file access at DB references; they do not indicate import status or progress.")
        print("Role codes are private Photos database values; match album titles to the Photos UI.")
        return 0

    total_tested = 0
    total_resolved = 0
    for role in sorted(by_role, key=lambda value: (value is None, str(value))):
        albums = by_role[role]
        rng.shuffle(albums)
        sample = albums[: args.albums_per_role]
        print(f"\nRole code {role!r}: {len(albums)} albums; sampling {len(sample)}")
        resolved_for_role = 0
        for _, album in sorted(sample, key=lambda item: str(item[1]["title"]).casefold()):
            references = [ref for ref in album["refs"] if ref[0] and ref[1]]
            if not references:
                print(f"  {album['title']} | no directory+filename references")
                total_tested += 1
                continue
            directory, filename = rng.choice(references)
            try:
                parent_exists, location, readable = probe_reference(
                    library, str(directory), str(filename)
                )
            except (OSError, ValueError):
                parent_exists, location, readable = False, "invalid-reference", False
            status = "readable" if readable else "not resolved"
            print(
                f"  {album['title']} | asset refs={len(album['refs'])} | "
                f"parent exists={parent_exists} | location={location} | {status}"
            )
            total_tested += 1
            if readable:
                resolved_for_role += 1
                total_resolved += 1
        print(f"  Resolved in sample: {resolved_for_role}/{len(sample)}")

    print(f"\nOverall: {total_resolved}/{total_tested} sampled references readable")
    print("Role codes are private Photos database values; match the printed album titles to the Photos UI.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Validate a Nexus immutable Linux release archive or installed directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tarfile
from pathlib import Path, PurePosixPath


EXPECTED_PLATFORM = "linux-x86_64"
EXPECTED_JAVA_MAJOR = 21
EXPECTED_PLAYWRIGHT = "1.63.0"


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def validate_relative_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if ("\\" in value or ":" in value or path.is_absolute() or not path.parts
            or any(part in ("", ".", "..") for part in path.parts)):
        fail(f"unsafe archive path: {value}")
    return path


def validate_archive(archive: Path, release_name: str, extract_to: Path | None) -> Path | None:
    if not archive.is_file():
        fail(f"release archive is missing: {archive}")

    seen: set[str] = set()
    with tarfile.open(archive, "r:gz") as bundle:
        members = bundle.getmembers()
        if not members:
            fail("release archive is empty")

        for member in members:
            path = validate_relative_path(member.name)
            if path.parts[0] != release_name:
                fail(f"archive member is outside the release root: {member.name}")
            normalized = str(path)
            if normalized in seen:
                fail(f"duplicate archive member: {member.name}")
            seen.add(normalized)
            if not (member.isfile() or member.isdir()):
                fail(f"links and special files are forbidden: {member.name}")

        if extract_to is None:
            return None

        extract_to.mkdir(parents=True, exist_ok=False)
        for member in members:
            relative = validate_relative_path(member.name)
            destination = extract_to.joinpath(*relative.parts)
            if member.isdir():
                destination.mkdir(parents=True, exist_ok=True)
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            source = bundle.extractfile(member)
            if source is None:
                fail(f"could not read archive member: {member.name}")
            with source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)

    return extract_to / release_name


def validate_directory(root: Path, release_name: str, allow_dirty: bool = False) -> dict[str, object]:
    if root.is_symlink():
        fail("release root must be a real directory")
    root = root.resolve(strict=True)
    if root.name != release_name:
        fail(f"release directory name mismatch: expected={release_name} actual={root.name}")

    manifest_path = root / "release-info.json"
    if not manifest_path.is_file():
        fail("release-info.json is missing")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        fail(f"could not read release-info.json: {exception}")

    required = {
        "version": release_name,
        "platform": EXPECTED_PLATFORM,
        "javaMajorVersion": EXPECTED_JAVA_MAJOR,
        "playwrightVersion": EXPECTED_PLAYWRIGHT,
    }
    if not allow_dirty:
        required["gitWorkingTreeDirty"] = False
    for key, expected in required.items():
        actual = manifest.get(key)
        if actual != expected:
            fail(f"manifest {key} mismatch: expected={expected!r} actual={actual!r}")

    commit = manifest.get("gitCommit")
    if not isinstance(commit, str) or len(commit) != 40 or any(c not in "0123456789abcdefABCDEF" for c in commit):
        fail("manifest gitCommit is not a full hexadecimal commit id")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) < 2:
        fail("manifest artifacts are missing")

    expected: dict[str, dict[str, object]] = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            fail("manifest contains an invalid artifact record")
        relative = artifact.get("path")
        if not isinstance(relative, str):
            fail("manifest artifact path is invalid")
        path = validate_relative_path(relative)
        if relative != "server/nexus-server.jar" and not relative.startswith("console/dist/"):
            fail(f"manifest contains a forbidden artifact path: {relative}")
        if relative in expected:
            fail(f"manifest contains a duplicate artifact path: {relative}")
        if not isinstance(artifact.get("length"), int) or artifact["length"] < 0:
            fail(f"manifest length is invalid: {relative}")
        expected[relative] = artifact

    if not {'server/nexus-server.jar', 'console/dist/index.html'} <= set(expected):
        fail('release must contain both the server JAR and console index.html')

    if any(path.is_symlink() for path in root.rglob("*")):
        fail("release directory contains a symlink")
    actual_files = {
        path.relative_to(root).as_posix(): path
        for path in root.rglob("*")
        if path.is_file()
    }
    if set(actual_files) != set(expected) | {"release-info.json"}:
        missing = sorted((set(expected) | {"release-info.json"}) - set(actual_files))
        extra = sorted(set(actual_files) - (set(expected) | {"release-info.json"}))
        fail(f"release file set mismatch: missing={missing} extra={extra}")

    for relative, artifact in expected.items():
        path = actual_files[relative]
        if path.stat().st_size != artifact["length"]:
            fail(f"artifact length mismatch: {relative}")
        expected_hash = str(artifact.get("sha256", "")).upper()
        if sha256(path) != expected_hash:
            fail(f"artifact SHA-256 mismatch: {relative}")

    result = {
        "release": release_name,
        "gitCommit": commit,
        "gitWorkingTreeDirty": manifest.get("gitWorkingTreeDirty"),
        "playwrightVersion": EXPECTED_PLAYWRIGHT,
        "artifactCount": len(expected),
    }
    print(json.dumps(result, separators=(",", ":")))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--archive", type=Path)
    source.add_argument("--directory", type=Path)
    parser.add_argument("--release", required=True)
    parser.add_argument("--extract-to", type=Path)
    parser.add_argument("--allow-dirty", action="store_true",
                        help="only for integrity checking the known historical V8 release")
    args = parser.parse_args()
    if not re.fullmatch(r"V[0-9]+(?:\.[0-9]+)*__[a-z0-9-]+", args.release):
        fail("invalid release name")
    if args.allow_dirty and (args.archive is not None or args.release != "V2026.10.22.1__ubuntu-initial"):
        fail("--allow-dirty is restricted to the installed historical V8 directory")

    if args.directory is not None:
        if args.extract_to is not None:
            fail("--extract-to is only valid with --archive")
        validate_directory(args.directory, args.release, args.allow_dirty)
        return

    extracted = validate_archive(args.archive, args.release, args.extract_to)
    if extracted is None:
        with tarfile.open(args.archive, "r:gz") as bundle:
            manifest_member = bundle.getmember(f"{args.release}/release-info.json")
            stream = bundle.extractfile(manifest_member)
            if stream is None:
                fail("release-info.json could not be read from the archive")
            manifest = json.load(stream)
        if manifest.get("version") != args.release or manifest.get("gitWorkingTreeDirty") is not False:
            fail("archive manifest version or clean-tree claim is invalid")
        print(json.dumps({"release": args.release, "archiveValidated": True}, separators=(",", ":")))
        return
    validate_directory(extracted, args.release)


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, tarfile.TarError, ValueError) as exception:
        fail(str(exception))

#!/usr/bin/env python3
"""Re-checks ModelCatalog's pinned sizes and SHA-256 digests against upstream.

ModelCatalog.kt is the trust root for every model Sarothi downloads: the
downloader refuses a file whose digest does not match, so a wrong pin means the
model can never be installed, and a silently changed upstream file means users
would be told to re-download forever.

Two modes, because they answer different questions:

  --offline   Validate the catalogue against itself. No network. Catches a
              malformed digest, a duplicate id, a SHA256_PINNED entry with no
              digest, a size that cannot be right. Runs in CI on every push.

  --remote    Compare each pin against the Hugging Face API's published size and
              LFS object id (which *is* the SHA-256 of the file). Detects real
              drift upstream. Needs network, so it is opt-in.

Usage:
    python3 scripts/verify_model_catalog.py --offline
    python3 scripts/verify_model_catalog.py --remote
    python3 scripts/verify_model_catalog.py --remote --repo LiquidAI/LFM2.5-350M-GGUF
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "core" / "src" / "main" / "java" / "com" / "ngi" / "sarothi" / "core" / "model" / "ModelCatalog.kt"

HF_API = "https://huggingface.co/api/models/{repo}/tree/main{sub}"
USER_AGENT = "sarothi-catalog-verifier/1.0 (build integrity check)"

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_BLOB_RE = re.compile(r"^[0-9a-f]{40}$")


@dataclass
class Entry:
    """One CatalogModel literal, as parsed from Kotlin source."""

    constant: str
    model_id: str = ""
    file_name: str = ""
    size_bytes: int = 0
    policy: str = ""
    sha256: str | None = None
    git_blob_sha1: str | None = None
    repo: str | None = None
    path_in_repo: str | None = None
    required: bool = False
    line: int = 0
    problems: list[str] = field(default_factory=list)


# ------------------------------------------------------------------- parsing

# Each catalogue entry is `val NAME = CatalogModel( ... )`. Splitting on the
# `val ... = CatalogModel(` boundary keeps fields from adjacent entries from
# bleeding into one another, which a flat regex over the whole file would do.
ENTRY_START = re.compile(r"^\s*val\s+([A-Z0-9_]+)\s*=\s*CatalogModel\(", re.MULTILINE)


def _field(body: str, name: str) -> str | None:
    """Value of `name = <literal>`, handling Kotlin's `_` digit separators."""
    m = re.search(rf'\b{name}\s*=\s*("(?:[^"\\]|\\.)*"|[\w.\-]+L?|true|false|null)', body)
    if not m:
        return None
    value = m.group(1)
    if value.startswith('"'):
        return value[1:-1]
    if value.endswith("L"):
        value = value[:-1]
    return value


def parse_catalog(text: str) -> list[Entry]:
    entries: list[Entry] = []
    starts = list(ENTRY_START.finditer(text))
    for index, match in enumerate(starts):
        begin = match.end()
        end = starts[index + 1].start() if index + 1 < len(starts) else len(text)
        body = text[begin:end]
        line = text[: match.start()].count("\n") + 1

        entry = Entry(constant=match.group(1), line=line)
        entry.model_id = _field(body, "id") or ""
        entry.file_name = _field(body, "fileName") or ""

        raw_size = _field(body, "sizeBytes")
        if raw_size:
            entry.size_bytes = int(raw_size.replace("_", ""))

        entry.policy = _field(body, "checksumPolicy") or ""
        entry.policy = entry.policy.replace("ChecksumPolicy.", "")
        entry.sha256 = _field(body, "sha256")
        entry.git_blob_sha1 = _field(body, "gitBlobSha1")
        entry.required = (_field(body, "required") or "false") == "true"

        # hfSources("owner/repo", "path/in/repo") is the only source constructor
        # the catalogue uses; both sources it returns point at the same file.
        # The trailing comma is optional because the multi-line entries carry one
        # and the single-line ones do not.
        src = re.search(r'hfSources\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,?\s*\)', body)
        if src:
            entry.repo, entry.path_in_repo = src.group(1), src.group(2)
        entries.append(entry)
    return entries


# ------------------------------------------------------------- offline checks

def check_offline(entries: list[Entry]) -> list[str]:
    """Self-consistency. Everything here is provable without a network."""
    problems: list[str] = []

    if not entries:
        return ["ModelCatalog.kt parsed to zero entries — the parser is out of date "
                "with the file's structure, so this script is proving nothing."]

    seen_ids: dict[str, str] = {}
    seen_files: dict[str, str] = {}

    for e in entries:
        where = f"line {e.line} ({e.constant})"

        if not e.model_id:
            problems.append(f"{where}: no `id` field parsed")
        elif e.model_id in seen_ids:
            problems.append(f"{where}: duplicate id '{e.model_id}' — also at {seen_ids[e.model_id]}. "
                            f"ModelDownloader and the manifest key on this id.")
        else:
            seen_ids[e.model_id] = where

        if not e.file_name:
            problems.append(f"{where}: no `fileName` field parsed")
        elif e.file_name in seen_files:
            problems.append(f"{where}: duplicate fileName '{e.file_name}' — also at "
                            f"{seen_files[e.file_name]}. Two catalogue entries would "
                            f"write to the same vault path.")
        else:
            seen_files[e.file_name] = where

        # A pinned policy with no digest is the dangerous case: the downloader
        # would have nothing to compare against and would accept any bytes.
        if e.policy == "SHA256_PINNED":
            if not e.sha256:
                problems.append(f"{where}: checksumPolicy=SHA256_PINNED but no sha256 — "
                                f"nothing would be verified")
            elif not SHA256_RE.match(e.sha256):
                problems.append(f"{where}: sha256 '{e.sha256}' is not 64 lowercase hex chars")
        elif e.policy == "GIT_BLOB_SHA1_PINNED":
            if not e.git_blob_sha1:
                problems.append(f"{where}: checksumPolicy=GIT_BLOB_SHA1_PINNED but no gitBlobSha1")
            elif not GIT_BLOB_RE.match(e.git_blob_sha1):
                problems.append(f"{where}: gitBlobSha1 '{e.git_blob_sha1}' is not 40 lowercase hex chars")
        elif e.policy not in ("SIZE_ONLY", ""):
            problems.append(f"{where}: unknown checksumPolicy '{e.policy}'")

        if e.size_bytes <= 0:
            problems.append(f"{where}: sizeBytes={e.size_bytes} — a catalogue entry must pin a "
                            f"positive size so a truncated download is rejected before hashing")
        elif e.size_bytes < 1024:
            problems.append(f"{where}: sizeBytes={e.size_bytes} is implausibly small for a model artifact")

        if not e.repo or not e.path_in_repo:
            problems.append(f"{where}: no hfSources(repo, path) — the verifier cannot locate "
                            f"upstream metadata for this entry")
        else:
            if e.path_in_repo.split("/")[-1] != e.file_name:
                problems.append(f"{where}: path_in_repo '{e.path_in_repo}' does not end with "
                                f"fileName '{e.file_name}'")
            if e.repo.count("/") != 1:
                problems.append(f"{where}: repo '{e.repo}' is not in owner/name form")

    return problems


# -------------------------------------------------------------- remote checks

def fetch_json(url: str, timeout: int = 30):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def check_remote(entries: list[Entry], only_repo: str | None, timeout: int) -> tuple[list[str], int]:
    problems: list[str] = []
    checked = 0
    # One API call per directory, not per file: several catalogue entries live in
    # the same repo folder.
    cache: dict[str, dict[str, dict]] = {}

    for e in entries:
        if not e.repo or not e.path_in_repo:
            continue
        if only_repo and e.repo != only_repo:
            continue

        directory = "/" + e.path_in_repo.rsplit("/", 1)[0] if "/" in e.path_in_repo else ""
        cache_key = f"{e.repo}{directory}"
        if cache_key not in cache:
            url = HF_API.format(repo=e.repo, sub=directory)
            try:
                listing = fetch_json(url, timeout)
            except urllib.error.HTTPError as exc:
                problems.append(f"{e.constant}: HTTP {exc.code} from {url} — the repository or "
                                f"path may have moved, in which case every user's download fails")
                cache[cache_key] = {}
                continue
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                problems.append(f"{e.constant}: could not reach {url}: {exc}")
                cache[cache_key] = {}
                continue
            cache[cache_key] = {item["path"]: item for item in listing if isinstance(item, dict)}

        remote = cache[cache_key].get(e.path_in_repo)
        if remote is None:
            problems.append(f"{e.constant}: '{e.path_in_repo}' is not in {e.repo}{directory} — "
                            f"upstream no longer publishes the pinned file")
            continue

        checked += 1
        remote_size = remote.get("size")
        if remote_size is not None and int(remote_size) != e.size_bytes:
            problems.append(
                f"{e.constant}: size drifted — catalogue pins {e.size_bytes}, "
                f"{e.repo}/{e.path_in_repo} now reports {remote_size}. "
                f"Update ModelCatalog.kt only after confirming the new file is the intended one."
            )

        # For Git-LFS backed files the `lfs.oid` is the SHA-256 of the blob.
        lfs = remote.get("lfs") or {}
        remote_oid = lfs.get("oid")
        if e.sha256 and remote_oid:
            if remote_oid.lower() != e.sha256.lower():
                problems.append(
                    f"{e.constant}: SHA-256 drifted — catalogue pins {e.sha256}, "
                    f"upstream LFS oid is {remote_oid}. Downloads would fail verification."
                )
        elif e.sha256 and not remote_oid:
            problems.append(f"{e.constant}: upstream exposes no LFS oid for '{e.path_in_repo}', "
                            f"so the pinned SHA-256 could not be compared")

    return problems, checked


# ----------------------------------------------------------------------- main

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--offline", action="store_true",
                       help="validate the catalogue against itself (no network)")
    group.add_argument("--remote", action="store_true",
                       help="compare every pin against the Hugging Face API")
    parser.add_argument("--repo", help="with --remote, check only this owner/name")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--json", action="store_true", help="emit machine-readable results")
    args = parser.parse_args(argv[1:])

    if not CATALOG.is_file():
        print(f"cannot find {CATALOG}", file=sys.stderr)
        return 2

    entries = parse_catalog(CATALOG.read_text(encoding="utf-8"))

    problems = check_offline(entries)
    checked = 0
    if args.remote:
        # A malformed pin is worth reporting even when the network is up, so the
        # offline checks always run first.
        remote_problems, checked = check_remote(entries, args.repo, args.timeout)
        problems += remote_problems

    if args.json:
        print(json.dumps({
            "mode": "remote" if args.remote else "offline",
            "entries_parsed": len(entries),
            "entries_checked_upstream": checked,
            "problems": problems,
        }, indent=2))
    else:
        mode = "remote (Hugging Face API)" if args.remote else "offline (self-consistency)"
        print(f"ModelCatalog verification — {mode}")
        print(f"  parsed {len(entries)} catalogue entries from {CATALOG.relative_to(ROOT)}")
        if args.remote:
            print(f"  compared {checked} against upstream metadata")
        print()
        for e in entries:
            pin = e.sha256 or e.git_blob_sha1 or "(size only)"
            print(f"  {e.model_id:<42} {e.size_bytes:>12,} B  {pin[:16]}{'…' if len(pin) > 16 else ''}"
                  f"{'  [required]' if e.required else ''}")
        print()
        if problems:
            print(f"FAILED — {len(problems)} problem(s):")
            for p in problems:
                print(f"  ✗ {p}")
        else:
            print(f"OK — no problems found ({mode})")

    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

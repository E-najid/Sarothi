#!/usr/bin/env python3
"""Turn Android lint XML reports into something a reviewer can actually read.

The lint job uploads its HTML and XML reports as artifacts, but artifacts are served
from blob storage that tooling frequently cannot reach -- the same wall that made
Gradle failures unreadable until `report_build_failure.py` started mining the log. So
this does the equivalent for lint: it parses the XML and re-emits every issue as a
workflow-command annotation, which comes back over the ordinary REST API, plus a
Markdown digest grouped by check id for the step summary.

Lint fails the build on any issue with severity=Error, and a lint failure with no
readable detail is indistinguishable from a misconfigured lint task. Naming the check
ids is what makes the difference actionable: `NewApi` means something different from
`MissingPermission`, and both mean something different from lint itself crashing.

Usage:
    report_lint.py [REPORT.xml ...] [-o digest.md]

If no report paths are given it globs the conventional Gradle output locations. A
missing report is reported as such rather than treated as "no issues", because that is
precisely the case where lint did not run.
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

# Lint writes file paths relative to the module, so the same id can appear under
# several roots. Keeping the module prefix makes the annotation point at one file.
DEFAULT_GLOBS = [
    "core/build/reports/lint-results-*.xml",
    "plugins/build/reports/lint-results-*.xml",
    "app/build/reports/lint-results-*.xml",
    "*/build/reports/lint-results-debug.xml",
]

# Only these severities fail the build, so only these are worth an error annotation.
# Everything else is a warning: visible, but not the reason the job went red.
FAILING = {"Error", "Fatal"}

MAX_ANNOTATIONS = 80
DIGEST_PER_ID = 6

# A non-failing check that fires only a handful of times is usually the interesting one
# -- StaticFieldLeak, BatteryLife and InlinedApi each point at a specific line worth
# reading, whereas GradleDependency firing 33 times is a version-catalogue conversation.
# Anything at or below this count is annotated with its file and line; above it, the id
# is folded into the aggregate annotation. Without this the rare findings were invisible:
# the aggregate said "StaticFieldLeak×1" and nothing said where.
ANNOTATE_WARNINGS_AT_OR_BELOW = 6


def module_of(report: Path) -> str:
    """`core/build/reports/lint-results-debug.xml` -> `core`."""
    parts = report.parts
    return parts[0] if parts and parts[0] not in (".", "build") else "."


def parse(report: Path) -> tuple[list[dict], list[str]]:
    """Return (issues, parse_errors) for one lint XML report."""
    issues: list[dict] = []
    problems: list[str] = []
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as exc:
        return issues, [f"{report}: not parseable as XML ({exc})"]
    except OSError as exc:
        return issues, [f"{report}: could not be read ({exc})"]

    module = module_of(report)
    for issue in root.findall("issue"):
        location = issue.find("location")
        path = (location.get("file") if location is not None else None) or ""
        # Lint emits absolute runner paths; strip to the module-relative form so the
        # annotation lands on a file the reviewer can open.
        if path and os.path.isabs(path):
            for marker in (f"/{module}/src/", "/src/"):
                at = path.find(marker)
                if at >= 0:
                    path = path[at + 1:]
                    break
        line = (location.get("line") if location is not None else None) or ""
        issues.append({
            "id": issue.get("id") or "Unknown",
            "severity": issue.get("severity") or "Unknown",
            "message": (issue.get("message") or "").strip(),
            "category": issue.get("category") or "",
            "file": path,
            "line": line,
            "module": module,
            "explanation": (issue.get("explanation") or "").strip(),
            "url": issue.get("url") or "",
        })
    return issues, problems


def emit_annotations(issues: list[dict]) -> int:
    """Print workflow commands. Returns how many were emitted."""
    # `::error file=..,line=..::text` becomes a check-run annotation, which is the one
    # CI diagnostic readable without blob storage. Percent-encoding follows the
    # workflow-command spec: % -> %25, \r -> %0D, \n -> %0A.
    def enc(text: str) -> str:
        return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    failing = [i for i in issues if i["severity"] in FAILING]
    other = [i for i in issues if i["severity"] not in FAILING]
    emitted = 0
    for issue in failing[:MAX_ANNOTATIONS]:
        # Built as a joined list: concatenating `file=..` with a `,line=..` fragment
        # produced `file=..,,line=..`, which the runner parses as an empty field.
        parts = []
        if issue["file"]:
            parts.append(f"file={enc(issue['file'])}")
        if issue["line"].isdigit():
            parts.append(f"line={issue['line']}")
        meta = f" {','.join(parts)}" if parts else ""
        print(f"::error{meta}::lint {issue['id']}: {enc(issue['message'])}")
        emitted += 1
    # Warnings are split by frequency. The rare ones get their own annotation with file
    # and line, because that is where an actual defect hides; the frequent ones are
    # aggregated, because a lint run can produce hundreds and annotations past the first
    # screen would bury the errors that matter.
    if other:
        by_id = Counter(i["id"] for i in other)
        rare = [i for i in other if by_id[i["id"]] <= ANNOTATE_WARNINGS_AT_OR_BELOW]
        bulk = [i for i in other if by_id[i["id"]] > ANNOTATE_WARNINGS_AT_OR_BELOW]
        for issue in rare[:MAX_ANNOTATIONS]:
            parts = []
            if issue["file"]:
                parts.append(f"file={enc(issue['file'])}")
            if issue["line"].isdigit():
                parts.append(f"line={issue['line']}")
            meta = f" {','.join(parts)}" if parts else ""
            print(f"::warning{meta}::lint {issue['id']}: {enc(issue['message'])}")
            emitted += 1
        if bulk:
            counts = Counter(i["id"] for i in bulk)
            summary = ", ".join(f"{k}×{v}" for k, v in counts.most_common(12))
            print(f"::warning::lint reported {len(bulk)} further non-failing issue(s), "
                  f"too frequent to list individually: {enc(summary)}")
            emitted += 1
    if len(failing) > MAX_ANNOTATIONS:
        print(f"::warning::lint: {len(failing) - MAX_ANNOTATIONS} further error(s) not "
              f"annotated; see the digest below")
        emitted += 1
    return emitted


def digest(issues: list[dict], reports: list[Path], problems: list[str]) -> str:
    out: list[str] = ["## Android lint", ""]
    failing = [i for i in issues if i["severity"] in FAILING]
    other = [i for i in issues if i["severity"] not in FAILING]

    out.append(f"Parsed {len(reports)} report(s): "
               + ", ".join(f"`{r}`" for r in reports))
    out.append("")
    out.append(f"**{len(failing)} failing issue(s)** (lint aborts the build on these) "
               f"and {len(other)} non-failing.")
    out.append("")

    if problems:
        out.append("### Problems reading the reports")
        out.append("")
        for problem in problems:
            out.append(f"- `{problem}`")
        out.append("")

    if not issues and not problems:
        out.append("_No issues were recorded in the reports._")
        out.append("")
        return "\n".join(out)

    by_id: dict[str, list[dict]] = defaultdict(list)
    for issue in failing:
        by_id[issue["id"]].append(issue)

    # Failing checks first, most frequent first: that ordering points at whichever
    # single rule is responsible for the red job.
    for check_id, items in sorted(by_id.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        first = items[0]
        out.append(f"### `{check_id}` — {len(items)} error(s)")
        out.append("")
        if first["category"]:
            out.append(f"_{first['category']}_")
            out.append("")
        for item in items[:DIGEST_PER_ID]:
            at = f":{item['line']}" if item["line"].isdigit() else ""
            out.append(f"- `{item['file']}{at}` — {item['message']}")
        if len(items) > DIGEST_PER_ID:
            out.append(f"- … {len(items) - DIGEST_PER_ID} more")
        out.append("")
        if first["explanation"]:
            out.append(f"> {first['explanation'][:400]}")
            out.append("")

    if other:
        counts = Counter(i["id"] for i in other)
        out.append("<details><summary>Non-failing issues by check id</summary>")
        out.append("")
        for check_id, count in counts.most_common():
            out.append(f"- `{check_id}` × {count}")
        out.append("")
        out.append("</details>")
        out.append("")

    return "\n".join(out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reports", nargs="*", help="lint XML reports to parse")
    parser.add_argument("-o", "--output", help="also write the Markdown digest here")
    parser.add_argument("--no-annotations", action="store_true",
                        help="do not print workflow commands (for local runs)")
    args = parser.parse_args(argv[1:])

    paths: list[Path] = []
    if args.reports:
        for raw in args.reports:
            found = sorted(glob.glob(raw))
            paths.extend(Path(f) for f in found) or paths.append(Path(raw))
    else:
        for pattern in DEFAULT_GLOBS:
            paths.extend(Path(f) for f in sorted(glob.glob(pattern)))
    # De-duplicate while keeping order: the default globs overlap.
    seen: set[str] = set()
    reports = [p for p in paths if not (str(p) in seen or seen.add(str(p)))]

    problems: list[str] = []
    missing = [p for p in reports if not p.is_file()]
    for path in missing:
        problems.append(f"{path}: not found")
    reports = [p for p in reports if p.is_file()]

    if not reports and not args.reports:
        problems.append("no lint XML report was produced -- lint did not run, or it "
                        "failed before writing one. That is not the same as a clean "
                        "lint run, and the job log is the only place left to look.")

    issues: list[dict] = []
    for report in reports:
        found, parse_problems = parse(report)
        issues.extend(found)
        problems.extend(parse_problems)

    if not args.no_annotations:
        if problems:
            for problem in problems:
                print(f"::error::lint report: {problem}")
        emit_annotations(issues)

    markdown = digest(issues, reports, problems)
    print(markdown)
    if args.output:
        Path(args.output).write_text(markdown, encoding="utf-8")

    failing = sum(1 for i in issues if i["severity"] in FAILING)
    print(f"lint_issues={len(issues)} failing={failing} reports={len(reports)} "
          f"problems={len(problems)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

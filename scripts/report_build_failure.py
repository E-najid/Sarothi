#!/usr/bin/env python3
"""Extracts the parts of a failed Gradle log that a reviewer actually needs.

A Gradle build failure on CI produces thousands of lines: daemon chatter, task
graphs, dependency resolution, stack traces. The signal is usually twenty lines
buried in the middle. This script mines a captured log for

  * the failing task(s)                       `> Task :core:compileDebugKotlin FAILED`
  * Kotlin compiler errors                    `e: file:///…/Foo.kt:12:34 unresolved reference: bar`
  * Kotlin compiler warnings (counted, not listed)
  * the `What went wrong` / `Try` / `Exception` block Gradle prints at the end
  * Java/Kotlin stack frames for non-compile failures (AGP bugs, OOM, missing SDK)

and writes a Markdown report grouped by file, with the most-referenced files
first, because that is the order in which they should be fixed.

Usage:
    gradle … 2>&1 | tee build.log
    python3 scripts/report_build_failure.py build.log -o failure-report.md

Exit status is deliberately always 0 when the log was read: this runs in an
`if: failure()` step, and failing again would mask the original error.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# `e: file:///home/runner/work/Sarothi/Sarothi/core/src/…/Foo.kt:123:45 message`
# Kotlin also emits the older form `e: /path/Foo.kt:123:45 message`.
# `.+` rather than `[^\s:]+` for the path: a Windows checkout is `D:/a/owner/repo/…`,
# and a character class excluding `:` would stop at the drive letter. Greedy matching
# backtracks to the final `:line:col`, which is what Kotlin actually emits.
KOTLIN_ERROR = re.compile(
    r"^e:\s+(?:file://)?(?P<path>.+?\.kts?):(?P<line>\d+):(?P<col>\d+)\s*(?P<msg>.*)$"
)
KOTLIN_WARNING = re.compile(r"^w:\s+(?:file://)?(.+?\.kts?):(\d+):(\d+)\s*(.*)$")
JAVA_ERROR = re.compile(
    r"^(?P<path>.+?\.java):(?P<line>\d+):\s*error:\s*(?P<msg>.*)$"
)
FAILED_TASK = re.compile(r"^>\s+Task\s+(?P<task>:\S+)\s+FAILED\s*$")
WHAT_WENT_WRONG = re.compile(r"^\*\s+What went wrong:")
GRADLE_FAILURE_BANNER = re.compile(r"^FAILURE:\s+Build (failed|completed with an exception)")
STACK_FRAME = re.compile(r"^\s+at\s+([\w.$]+\([^)]*\)|[\w.$]+\.[\w<>$]+)")
CAUSED_BY = re.compile(r"^Caused by:\s+(.*)$")
EXECUTION_FAILED = re.compile(r"^>\s+(.*)$")
UNRESOLVED = re.compile(r"unresolved reference[:\s]+'?([\w.]+)'?", re.IGNORECASE)


# GitHub Actions checks out to /home/runner/work/<repo>/<repo>/ (and D:/a/<repo>/<repo>/
# on Windows). The doubled directory makes a naive find() strip only the outer one,
# so match the whole prefix instead. Overridable for local runs.
# Linux/macOS runners check out under .../work/<repo>/<repo>/, Windows runners under
# D:/a/<repo>/<repo>/. Both double the repository name.
RUNNER_PREFIX = re.compile(r"^(?:[A-Za-z]:)?[\\/].*?[\\/](?:work|a)[\\/][^\\/]+[\\/][^\\/]+[\\/]")
LOCAL_PREFIX = re.compile(r"^.*?[\\/]Sarothi[\\/]")

_strip_override: str | None = None


def set_strip_prefix(prefix: str | None) -> None:
    global _strip_override
    _strip_override = prefix


def shorten(path: str) -> str:
    """Reduce an absolute source path to something readable and repo-relative."""
    if _strip_override:
        index = path.rfind(_strip_override)
        if index >= 0:
            return path[index + len(_strip_override):]
        return path
    for pattern in (RUNNER_PREFIX, LOCAL_PREFIX):
        if m := pattern.match(path):
            return path[m.end():]
    return path


def mine(log_text: str) -> dict:
    lines = log_text.splitlines()

    kotlin_errors: list[tuple[str, int, int, str]] = []
    kotlin_warnings = 0
    java_errors: list[tuple[str, int, str]] = []
    failed_tasks: list[str] = []
    what_went_wrong: list[str] = []
    caused_by: list[str] = []
    stack_frames: list[str] = []

    index = 0
    while index < len(lines):
        line = lines[index]

        if m := KOTLIN_ERROR.match(line):
            kotlin_errors.append((shorten(m["path"]), int(m["line"]), int(m["col"]), m["msg"].strip()))
        elif KOTLIN_WARNING.match(line):
            kotlin_warnings += 1
        elif m := JAVA_ERROR.match(line):
            java_errors.append((shorten(m["path"]), int(m["line"]), m["msg"].strip()))
        elif m := FAILED_TASK.match(line):
            task = m["task"]
            if task not in failed_tasks:
                failed_tasks.append(task)
        elif WHAT_WENT_WRONG.match(line):
            # Gradle prints the cause indented on the following lines until a
            # blank line or the next `*` section header.
            block: list[str] = []
            index += 1
            while index < len(lines):
                current = lines[index]
                if current.startswith("* ") or GRADLE_FAILURE_BANNER.match(current):
                    break
                if current.strip():
                    block.append(current.strip())
                elif block:
                    break
                index += 1
            what_went_wrong.extend(block)
        elif m := CAUSED_BY.match(line):
            if m.group(1) not in caused_by:
                caused_by.append(m.group(1))
        elif STACK_FRAME.match(line):
            if len(stack_frames) < 15:
                stack_frames.append(line.strip())

        index += 1

    return {
        "kotlin_errors": kotlin_errors,
        "kotlin_warnings": kotlin_warnings,
        "java_errors": java_errors,
        "failed_tasks": failed_tasks,
        "what_went_wrong": what_went_wrong,
        "caused_by": caused_by,
        "stack_frames": stack_frames,
        "line_count": len(lines),
    }


def render(report: dict, log_name: str) -> str:
    out: list[str] = []
    ke = report["kotlin_errors"]
    je = report["java_errors"]

    out.append("## Build failure")
    out.append("")
    out.append(f"Mined from `{log_name}` ({report['line_count']:,} lines).")
    out.append("")

    if report["failed_tasks"]:
        out.append("**Failed task(s):** " + ", ".join(f"`{t}`" for t in report["failed_tasks"]))
        out.append("")

    if ke:
        by_file: dict[str, list[tuple[int, int, str]]] = defaultdict(list)
        for path, line, col, msg in ke:
            by_file[path].append((line, col, msg))

        out.append(f"### {len(ke)} Kotlin compiler error(s) in {len(by_file)} file(s)")
        out.append("")
        # Most errors first: that file is usually the one whose mistake cascades.
        for path, items in sorted(by_file.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            out.append(f"<details><summary><code>{path}</code> — {len(items)} error(s)</summary>")
            out.append("")
            out.append("| Line | Error |")
            out.append("|---|---|")
            for line, _col, msg in sorted(items)[:60]:
                # Escaped outside the f-string: a backslash is not allowed inside an
                # f-string expression before Python 3.12, and a `|` in a compiler
                # message would otherwise break the Markdown table.
                escaped = msg.replace("|", "\\|")
                out.append(f"| {line} | `{escaped}` |")
            if len(items) > 60:
                out.append(f"| … | {len(items) - 60} more |")
            out.append("")
            out.append("</details>")
            out.append("")

        # The single most common error class, because fixing one symbol often
        # clears dozens of downstream errors.
        refs = Counter(m.group(1) for _p, _l, _c, msg in ke
                       if (m := UNRESOLVED.search(msg)))
        if refs:
            out.append("**Most-referenced unresolved symbols** (fix these first — they "
                       "usually cascade):")
            out.append("")
            for symbol, count in refs.most_common(10):
                out.append(f"- `{symbol}` × {count}")
            out.append("")

    if je:
        out.append(f"### {len(je)} Java compiler error(s)")
        out.append("")
        for path, line, msg in je[:40]:
            out.append(f"- `{path}:{line}` — {msg}")
        out.append("")

    if not ke and not je:
        out.append("### No compiler errors found in the log")
        out.append("")
        out.append("The failure is not a Kotlin/Java compile error — it is a "
                   "configuration, dependency-resolution, SDK or plugin problem. "
                   "Gradle's own explanation follows.")
        out.append("")

    if report["what_went_wrong"]:
        out.append("### What went wrong (Gradle)")
        out.append("")
        out.append("```")
        out.extend(report["what_went_wrong"][:40])
        out.append("```")
        out.append("")

    if report["caused_by"]:
        out.append("### Caused by")
        out.append("")
        for cause in report["caused_by"][:10]:
            out.append(f"- `{cause}`")
        out.append("")

    if report["stack_frames"] and not ke:
        out.append("<details><summary>Stack frames</summary>")
        out.append("")
        out.append("```")
        out.extend(report["stack_frames"])
        out.append("```")
        out.append("")
        out.append("</details>")
        out.append("")

    if report["kotlin_warnings"]:
        out.append(f"_{report['kotlin_warnings']} compiler warning(s) also emitted; "
                   f"not listed._")
        out.append("")

    return "\n".join(out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log", nargs="?", default="build.log", help="captured Gradle log")
    parser.add_argument("-o", "--output", help="write Markdown here as well as stdout")
    parser.add_argument("--stats", action="store_true", help="print counts only")
    parser.add_argument("--strip-prefix",
                        help="strip everything up to and including this substring from "
                             "source paths (default: auto-detect the CI checkout prefix)")
    args = parser.parse_args(argv[1:])

    set_strip_prefix(args.strip_prefix)

    path = Path(args.log)
    if not path.is_file():
        print(f"report_build_failure: no log at {path}", file=sys.stderr)
        print("(the build step must capture Gradle output with `| tee build.log`)")
        return 0

    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"report_build_failure: could not read {path}: {exc}", file=sys.stderr)
        return 0

    report = mine(text)

    if args.stats:
        print(f"kotlin_errors={len(report['kotlin_errors'])} "
              f"java_errors={len(report['java_errors'])} "
              f"kotlin_warnings={report['kotlin_warnings']} "
              f"failed_tasks={len(report['failed_tasks'])} "
              f"files={len({p for p, *_ in report['kotlin_errors']})}")
        return 0

    markdown = render(report, path.name)
    print(markdown)
    if args.output:
        Path(args.output).write_text(markdown, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

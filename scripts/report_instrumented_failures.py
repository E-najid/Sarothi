#!/usr/bin/env python3
"""Turn instrumentation failures into something a reviewer can read where they already are.

`connectedAndroidTest` leaves its JUnit XML under
`<module>/build/outputs/androidTest-results/connected/<device>/`, and the job uploads
that directory as an artifact -- which puts it on blob storage that tooling frequently
cannot reach. That is the same wall `report_build_failure.py` and `report_lint.py` exist
to get around, and a device failure is the one kind of failure nobody can reproduce by
reading a log: it happened on an emulator that no longer exists.

So this mines the results and re-emits every failed test as a workflow-command
annotation, with the file and line of the `@Test` method attached when the source can be
found. A red device run then says which behaviour broke, in the PR's own check list,
instead of saying "the emulator step failed".

Usage:
    report_instrumented_failures.py [RESULT.xml ...] [-o digest.md] [--no-annotations]

If no paths are given it globs the conventional locations. Producing no results at all is
reported as an error rather than treated as "no failures": an emulator that never booted
and a device suite that all passed look identical otherwise.
"""

from __future__ import annotations

import argparse
import glob
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_GLOBS = [
    "*/build/outputs/androidTest-results/**/*.xml",
]

MAX_ANNOTATIONS = 25
MAX_MESSAGE = 400

# `classname` in instrumentation results is the fully-qualified test class, which maps
# straight onto the source layout of an androidTest source set.
SOURCE_GLOB = "*/src/androidTest/java/{path}.kt"


def enc(text: str) -> str:
    """Percent-encode for the workflow-command spec: % -> %25, CR -> %0D, LF -> %0A."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def suites_in(path: Path) -> tuple[list[ET.Element], str | None]:
    """The <testsuite> elements of one file, plus a problem string if it is unreadable."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as failure:
        return [], f"{path}: could not be parsed ({failure})"
    if root.tag == "testsuite":
        return [root], None
    # Instrumentation results are sometimes wrapped in <testsuites>; reading the counts off
    # the wrapper instead would report a clean run for a device that failed everything.
    return list(root.iter("testsuite")), None


def parse(path: Path) -> tuple[list[dict], int, int, str | None]:
    """Returns (failures, tests_run, skipped, problem)."""
    suites, problem = suites_in(path)
    failures: list[dict] = []
    tests = skipped = 0

    for suite in suites:
        tests += int(suite.get("tests", 0))
        skipped += int(suite.get("skipped", 0))
        for case in suite.iter("testcase"):
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            if failure is None:
                continue
            failures.append({
                "module": path.parts[0] if len(path.parts) > 1 else "",
                "classname": case.get("classname") or suite.get("name") or "",
                "name": case.get("name") or "(unnamed)",
                "kind": failure.tag,
                "type": failure.get("type") or "",
                "message": (failure.get("message") or "").strip(),
                "stack": (failure.text or "").strip(),
            })
    return failures, tests, skipped, problem


def locate(case: dict) -> tuple[str, int]:
    """Best-effort source location of the failing @Test method."""
    dotted = case["classname"].replace(".", "/")
    matches = sorted(glob.glob(SOURCE_GLOB.format(path=dotted)))
    if not matches:
        return "", 0
    source = Path(matches[0])
    # The runner reports a Kotlin suspend test as `name`, and a parameterised one as
    # `name[param]`; the method itself is what the reader wants to open.
    method = re.split(r"[\[(]", case["name"])[0]
    try:
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            if re.search(rf"\bfun\s+{re.escape(method)}\s*\(", line):
                return str(source), number
    except OSError:
        return str(source), 0
    return str(source), 0


def first_frames(stack: str) -> str:
    """The part of a stack trace that is about Sarothi rather than about the test runner."""
    lines = [line.strip() for line in stack.splitlines() if line.strip()]
    ours = [line for line in lines if "com.ngi.sarothi" in line]
    return "\n".join((ours or lines)[:4])


def emit_annotations(failures: list[dict]) -> int:
    for case in failures[:MAX_ANNOTATIONS]:
        source, line = locate(case)
        parts = []
        if source:
            parts.append(f"file={enc(source)}")
            if line:
                parts.append(f"line={line}")
        meta = f" {','.join(parts)}" if parts else ""
        detail = case["message"] or case["type"] or first_frames(case["stack"])
        detail = detail[:MAX_MESSAGE]
        title = f"{case['classname'].rsplit('.', 1)[-1]}.{case['name']}"
        print(f"::error{meta} title={enc(title)}::instrumentation {case['kind']}: {enc(detail)}")
    if len(failures) > MAX_ANNOTATIONS:
        print(
            f"::warning::instrumentation: {len(failures) - MAX_ANNOTATIONS} further failure(s) "
            "not annotated; see the digest or the uploaded results"
        )
    return min(len(failures), MAX_ANNOTATIONS)


def digest(failures: list[dict], results: list[Path], problems: list[str],
           tests: int, skipped: int) -> str:
    out = ["### Instrumentation tests", ""]
    if problems:
        out += [f"**{len(problems)} problem(s) reading the results:**", ""]
        out += [f"- {problem}" for problem in problems]
        out.append("")
    if not results:
        out += [
            "No instrumentation results were produced. The emulator did not boot, the "
            "suites did not run, or the results are not where AGP puts them -- which is "
            "not the same as a clean run.",
        ]
        return "\n".join(out)

    out += [f"**{tests}** executed on the device, **{len(failures)}** failed, **{skipped}** skipped.", ""]
    if not failures:
        return "\n".join(out)

    by_module: dict[str, list[dict]] = {}
    for case in failures:
        by_module.setdefault(case["module"] or "(unknown module)", []).append(case)
    for module, cases in sorted(by_module.items()):
        out += [f"`:{module}` — {len(cases)} failure(s):", ""]
        for case in cases:
            out.append(f"- **{case['classname'].rsplit('.', 1)[-1]}.{case['name']}**")
            detail = case["message"] or case["type"]
            if detail:
                out.append(f"  - {detail[:MAX_MESSAGE]}")
            frames = first_frames(case["stack"])
            if frames:
                out.append("  ```")
                out += [f"  {frame}" for frame in frames.splitlines()]
                out.append("  ```")
        out.append("")
    return "\n".join(out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("results", nargs="*", help="instrumentation XML results to parse")
    parser.add_argument("-o", "--output", help="also write the Markdown digest here")
    parser.add_argument("--no-annotations", action="store_true",
                        help="do not print workflow commands (for local runs)")
    args = parser.parse_args(argv[1:])

    paths: list[Path] = []
    if args.results:
        for raw in args.results:
            found = sorted(glob.glob(raw, recursive=True))
            paths.extend(Path(f) for f in found)
            if not found:
                paths.append(Path(raw))
    else:
        for pattern in DEFAULT_GLOBS:
            paths.extend(Path(f) for f in sorted(glob.glob(pattern, recursive=True)))

    seen: set[str] = set()
    results = [p for p in paths if p.is_file() and not (str(p) in seen or seen.add(str(p)))]

    problems: list[str] = [f"{p}: not found" for p in paths if not p.is_file()]
    if not results and not args.results:
        problems.append(
            "no instrumentation result file was produced -- the emulator did not boot or "
            "no device test ran. That is not the same as a passing device suite."
        )

    failures: list[dict] = []
    tests = skipped = 0
    for result in results:
        found, ran, skipped_here, problem = parse(result)
        failures.extend(found)
        tests += ran
        skipped += skipped_here
        if problem:
            problems.append(problem)

    if not args.no_annotations:
        for problem in problems:
            print(f"::error::instrumentation results: {problem}")
        emit_annotations(failures)

    markdown = digest(failures, results, problems, tests, skipped)
    print(markdown)
    if args.output:
        Path(args.output).write_text(markdown + "\n", encoding="utf-8")

    print(
        f"instrumented_tests={tests} failures={len(failures)} skipped={skipped} "
        f"results={len(results)} problems={len(problems)}",
        file=sys.stderr,
    )
    # Reporting is best-effort: the Gradle step already decided whether the job is red,
    # and a second failure here would only bury the first one.
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

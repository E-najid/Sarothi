#!/usr/bin/env python3
"""Count the JVM unit tests Gradle actually executed.

Gradle reports success for a test task that matched no sources at all. That is the
correct answer to the question it was asked and the wrong answer to the question a
reviewer reads out of a green tick: "the tests pass" is then true of a repository
containing none. This script mines the JUnit XML each run leaves behind so the claim
can be checked against something.

Usage:
    python3 scripts/count_unit_tests.py [--require-nonzero] [root ...]

Prints "<total> <failed> <skipped>" on stdout. Exits 1 with --require-nonzero when
no test ran, and 2 when a result file cannot be parsed, so the caller can tell "no
tests" apart from "the counting broke".
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_ROOTS = ("core", "plugins", "app")


def collect(roots: list[str]) -> tuple[int, int, int, list[str]]:
    """Sums the per-class result files under each module's test-results directory."""
    total = failed = skipped = 0
    unparsable: list[str] = []

    for root in roots:
        if not os.path.isdir(root):
            continue
        pattern = os.path.join(root, "build", "test-results", "**", "TEST-*.xml")
        for path in sorted(glob.glob(pattern, recursive=True)):
            try:
                suite = ET.parse(path).getroot()
            except (ET.ParseError, OSError):
                # A truncated file means the JVM died mid-suite. Reporting a guessed
                # number would hide that, so it is surfaced instead.
                unparsable.append(path)
                continue
            total += int(suite.get("tests", 0))
            failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))

    return total, failed, skipped, unparsable


def main(argv: list[str]) -> int:
    require_nonzero = "--require-nonzero" in argv
    roots = [a for a in argv if not a.startswith("-")] or list(DEFAULT_ROOTS)

    total, failed, skipped, unparsable = collect(roots)
    print(f"{total} {failed} {skipped}")

    for path in unparsable:
        print(f"could not parse result file: {path}", file=sys.stderr)
    if unparsable:
        return 2

    if require_nonzero and total == 0:
        print(
            "the test tasks succeeded without executing a single test; "
            "an empty suite must not be reported as passing",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

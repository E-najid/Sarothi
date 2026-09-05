#!/usr/bin/env python3
"""Count the tests Gradle actually executed.

Gradle reports success for a test task that matched no sources at all. That is the
correct answer to the question it was asked and the wrong answer to the question a
reviewer reads out of a green tick: "the tests pass" is then true of a repository
containing none. This script mines the JUnit XML each run leaves behind so the claim
can be checked against something.

The same argument applies to `connectedAndroidTest`, which succeeds when no device was
attached to nothing at all, so --connected points the search at the instrumentation
results instead. A device run that executed no test is just as much a false green as a
JVM run that executed none.

Usage:
    python3 scripts/count_unit_tests.py [--require-nonzero] [--connected] [root ...]

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

# JVM suites: one file per test class.
UNIT_RESULTS = os.path.join("build", "test-results", "**", "TEST-*.xml")
# Instrumentation suites: AGP nests them under the device they ran on, and names the
# file after the device rather than the class, so the pattern is looser.
CONNECTED_RESULTS = os.path.join("build", "outputs", "androidTest-results", "**", "*.xml")


def suites_in(path: str) -> list[ET.Element]:
    """The <testsuite> elements of one result file.

    The JVM layout writes one suite per file. Instrumentation results are sometimes
    wrapped in a <testsuites> element instead, and reading the attributes off that
    wrapper would report zero tests for a run that executed dozens.
    """
    root = ET.parse(path).getroot()
    if root.tag == "testsuite":
        return [root]
    return [child for child in root.iter("testsuite")]


def collect(roots: list[str], results_glob: str = UNIT_RESULTS) -> tuple[int, int, int, list[str]]:
    """Sums the per-suite result files under each module's results directory."""
    total = failed = skipped = 0
    unparsable: list[str] = []

    for root in roots:
        if not os.path.isdir(root):
            continue
        pattern = os.path.join(root, results_glob)
        for path in sorted(glob.glob(pattern, recursive=True)):
            try:
                suites = suites_in(path)
            except (ET.ParseError, OSError):
                # A truncated file means the process died mid-suite. Reporting a guessed
                # number would hide that, so it is surfaced instead.
                unparsable.append(path)
                continue
            if not suites:
                # An XML file with no suite in it is not a result this can count, and
                # silently treating it as zero would hide a layout change.
                unparsable.append(path)
                continue
            for suite in suites:
                total += int(suite.get("tests", 0))
                failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
                skipped += int(suite.get("skipped", 0))

    return total, failed, skipped, unparsable


def main(argv: list[str]) -> int:
    require_nonzero = "--require-nonzero" in argv
    connected = "--connected" in argv
    roots = [a for a in argv if not a.startswith("-")] or list(DEFAULT_ROOTS)

    total, failed, skipped, unparsable = collect(
        roots,
        CONNECTED_RESULTS if connected else UNIT_RESULTS,
    )
    print(f"{total} {failed} {skipped}")

    for path in unparsable:
        print(f"could not parse result file: {path}", file=sys.stderr)
    if unparsable:
        return 2

    if require_nonzero and total == 0:
        kind = "instrumentation" if connected else "unit test"
        print(
            f"the {kind} tasks succeeded without executing a single test; "
            "an empty suite must not be reported as passing",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

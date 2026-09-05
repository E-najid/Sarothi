#!/usr/bin/env python3
"""Explain a failed `instrumented` job in the one channel that outlives the runner.

Raw job logs and artifacts live on blob storage that tooling frequently cannot reach, and a
device failure is the one kind that cannot be reconstructed afterwards: the emulator that
produced it no longer exists. This script reads the teed Gradle output and turns the ways
this job can fail into specific, separate reports, emitted as check annotations -- which are
readable from the check-run API without the log.

Three failures look alike from the exit code and are nothing alike to fix:

  * the `androidTest` sources did not compile. The build job can never catch this, because
    `assembleDebug` and `testDebugUnitTest` do not compile this source set at all, so the
    first compiler to see it runs twenty minutes into an emulator boot. Every Kotlin `e:`
    line becomes an annotation carrying its file and line.
  * the emulator never became usable -- no KVM, a boot timeout, an image that would not
    install. No test ran, which is not the same as a test passing.
  * the tests ran and some failed. That is `report_instrumented_failures.py`'s job, from the
    instrumentation XML, and this script says so rather than guessing at test names from a
    log.

A reporter, not a gate: it exits non-zero only when it cannot do its own work.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# Kotlin: "e: file:///home/runner/work/Sarothi/Sarothi/core/src/.../X.kt:12:34 unresolved
# reference: onNode", and the older "e: /path/X.kt: (12, 34): unresolved reference".
KOTLIN_ERROR = re.compile(
    r"^e:\s+(?:file://)?(?P<path>[^:]+\.kts?):\s*(?:\((?P<line2>\d+),\s*\d+\)|(?P<line>\d+):\d+)\s*(?P<message>.*)$"
)
# javac: "/path/X.java:12: error: cannot find symbol"
JAVA_ERROR = re.compile(r"^(?P<path>[^\s:]+\.java):(?P<line>\d+):\s*error:\s*(?P<message>.*)$")
TASK_FAILED = re.compile(r"^>\s*Task\s+(?P<task>:\S+)\s+FAILED\s*$")
EXECUTION_FAILED = re.compile(r"Execution failed for task '(?P<task>[^']+)'")
# The runner's workspace, so absolute paths can become repo-relative ones.
WORKSPACE = re.compile(r"^.*?/work/[^/]+/[^/]+/")

EMULATOR_MARKERS = (
    "PANIC",
    "emulator: ERROR",
    "ERROR: x86_64 emulation currently requires hardware acceleration",
    "Device offline",
    "device offline",
    "no devices/emulators found",
    "adb: no devices",
    "error: no devices/emulators found",
    "Timed out waiting for emulator",
    "Waiting for emulator",
    "INSTALL_FAILED",
    "avdmanager",
    "KVM permission",
    "/dev/kvm",
)

MAX_ANNOTATIONS_DEFAULT = 40


def relative(path: str) -> str:
    """Repo-relative if this is a runner path, otherwise the tail that identifies the file."""
    stripped = WORKSPACE.sub("", path)
    if stripped != path:
        return stripped
    candidate = Path(path)
    for part in candidate.parts:
        if part in {"core", "app", "plugins", "scripts", ".github"}:
            index = candidate.parts.index(part)
            return "/".join(candidate.parts[index:])
    return path


EMULATOR_MARKERS = (
    "PANIC",
    "ERROR",
    "error:",
    "Error:",
    "failed",
    "Failed",
    "cannot",
    "Cannot",
    "not found",
    "No such",
    "denied",
    "KVM",
    "acceleration",
    "qemu",
    "WARNING: ",
)


def mine_emulator_log(path: Path, limit: int = 25) -> list[str]:
    """Put the emulator's own account of a failed boot into the annotations.

    When the device never comes up there is no Gradle output, no instrumentation XML and no
    test name to report -- the only witness is what the emulator binary printed, and it is
    discarded with the runner. So the lines that look like a reason go out as annotations,
    which are readable from the check-run API without the job log.
    """
    if not path.is_file():
        annotate("no emulator output", f"{path} was not produced, so the emulator never started")
        return [f"`{path}` was not produced: the emulator never started."]

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    reasons = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped in reasons:
            continue
        if any(marker in stripped for marker in EMULATOR_MARKERS):
            reasons.append(stripped)

    if not reasons:
        annotate(
            "the emulator printed nothing that looks like a reason",
            f"{len(lines)} line(s) of output, none matching an error marker; the tail is "
            f"'{lines[-1][:200] if lines else ''}'",
        )
        return [
            f"`{path}` holds {len(lines)} line(s), none of which looks like a reason.",
            "",
            "```",
            *(line[:200] for line in lines[-12:]),
            "```",
        ]

    for reason in reasons[:limit]:
        annotate("emulator", reason[:300])
    if len(reasons) > limit:
        annotate("emulator", f"{len(reasons) - limit} further error line(s) in {path}")

    digest = [
        f"**The emulator did not become usable.** {len(reasons)} line(s) of its own output "
        "look like the reason:",
        "",
        "```",
        *(reason[:200] for reason in reasons[:limit]),
        "```",
    ]
    return digest


def classify(lines: list[str]) -> tuple[list[dict], list[str], list[str]]:
    """Returns (compile errors, failed tasks, emulator lines) found in the log."""
    errors: list[dict] = []
    seen: set[tuple[str, int, str]] = set()
    tasks: list[str] = []
    emulator: list[str] = []

    for raw in lines:
        line = raw.rstrip()

        for pattern in (KOTLIN_ERROR, JAVA_ERROR):
            match = pattern.match(line.strip())
            if not match:
                continue
            path = relative(match.group("path"))
            number = int(match.group("line") or match.group("line2") or 0)
            message = (match.group("message") or "").strip().lstrip(":").strip() or "compilation error"
            key = (path, number, message)
            if key not in seen:
                seen.add(key)
                errors.append({"path": path, "line": number, "message": message})
            break

        task = TASK_FAILED.match(line)
        if task:
            tasks.append(task.group("task"))
            continue
        failed = EXECUTION_FAILED.search(line)
        if failed and failed.group("task") not in tasks:
            tasks.append(failed.group("task"))
            continue

        if any(marker in line for marker in EMULATOR_MARKERS):
            stripped = line.strip()
            if stripped and stripped not in emulator:
                emulator.append(stripped)

    return errors, tasks, emulator


def annotate(level: str, message: str, path: str | None = None, line: int | None = None) -> None:
    properties = [f"title={level}"]
    if path:
        properties.append(f"file={path}")
    if line:
        properties.append(f"line={line}")
    # Newlines would end the annotation command early.
    text = " ".join(message.split())
    print(f"::error {','.join(properties)}::{text}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--log", default="instrumented.log", help="teed Gradle output")
    parser.add_argument(
        "--emulator-log",
        default=None,
        help="the emulator's own output, mined when the device never became usable",
    )
    parser.add_argument("-o", "--output", default=None, help="where to write a markdown digest")
    parser.add_argument(
        "--max",
        type=int,
        default=MAX_ANNOTATIONS_DEFAULT,
        help="cap on compile-error annotations, so one broken file cannot bury the summary",
    )
    args = parser.parse_args(argv)

    log = Path(args.log)
    digest: list[str] = []

    if args.emulator_log:
        digest += mine_emulator_log(Path(args.emulator_log))

    if not log.is_file():
        message = (
            f"no {args.log} was captured, so the job failed before Gradle ran -- SDK install, "
            "AVD creation, or the emulator never finished booting"
        )
        annotate("instrumented job failed with no Gradle output", message)
        digest += ["", "### Instrumentation tests", "", f"The job failed before Gradle ran: {message}."]
        write_digest(args.output, digest)
        return 0

    lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
    errors, tasks, emulator = classify(lines)

    digest += ["### Instrumentation tests", ""]

    if errors:
        digest += [
            f"**The device-test sources did not compile: {len(errors)} error(s).** No test ran.",
            "",
            "| File | Line | Error |",
            "|---|---|---|",
        ]
        for error in errors[: args.max]:
            annotate(
                "androidTest did not compile",
                error["message"],
                path=error["path"],
                line=error["line"],
            )
            digest.append(f"| `{error['path']}` | {error['line']} | {error['message']} |")
        if len(errors) > args.max:
            digest.append(f"| … | | {len(errors) - args.max} more in the job log |")
        digest += [
            "",
            "The build job cannot catch these: `assembleDebug` and `testDebugUnitTest` never "
            "compile an `androidTest` source set, so this job's compiler is the first to see it.",
        ]
    elif emulator:
        first = emulator[0][:200]
        annotate(
            "the emulator was not usable",
            f"no test ran: {first}",
        )
        digest += [
            "**The emulator never became usable, so no test ran.** That is not a passing "
            "device suite.",
            "",
            "```",
        ]
        digest += [line[:200] for line in emulator[:12]]
        digest.append("```")
    else:
        xml_found = any(Path(p).exists() for p in (
            "core/build/outputs/androidTest-results",
            "app/build/outputs/androidTest-results",
        ))
        if xml_found:
            digest += [
                "The suites ran and at least one test failed, or the counting step refused the "
                "result. `report_instrumented_failures.py` names the tests; the annotations it "
                "emits carry the file and line of each failing `@Test`.",
            ]
        else:
            annotate(
                "instrumented job failed without a recognisable cause",
                f"tasks reported failing: {', '.join(tasks) if tasks else 'none'}; "
                f"no compile error and no emulator marker in {args.log}",
            )
            digest += [
                f"No compile error and no emulator problem in `{args.log}`"
                + (f"; failing tasks: {', '.join(tasks)}." if tasks else "."),
            ]

    if tasks and errors:
        digest += ["", f"Failing Gradle tasks: {', '.join(tasks)}."]

    write_digest(args.output, digest)
    return 0


def write_digest(output: str | None, digest: list[str]) -> None:
    text = "\n".join(digest) + "\n"
    if output:
        Path(output).write_text(text, encoding="utf-8")
    print(text, file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

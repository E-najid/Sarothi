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
# The runner's workspace, so absolute paths can become repo-relative ones. The anchored
# form is for a string that IS a path; the inline form is for a sentence that CONTAINS
# one, where stripping from the start of the line would take the sentence with it.
WORKSPACE = re.compile(r"^(?:file://)?/[\w./+-]*?/work/[^/]+/[^/]+/")
WORKSPACE_INLINE = re.compile(r"(?:file://)?/[\w./+-]*?/work/[^/]+/[^/]+/")

# Two lists, because two different claims are being made. DEVICE_UNUSABLE is the only
# evidence that justifies saying no test ran; EMULATOR_MARKERS is wider and merely decides
# which lines of the emulator's own output are worth showing.
#
# These used to be one list, defined twice, and the second definition -- containing "ERROR",
# "failed", "cannot" and "WARNING: " -- shadowed the first. Almost every line of a working
# emulator's output matches one of those, so a boot that succeeded and a suite that ran were
# reported as "the emulator never became usable, so no test ran", and the actual cause was
# left in a log nobody could reach. `[EmulatorConsole]: Failed to start Emulator console for
# 5554` is exactly that: noise from a device that went on to work over adb.
DEVICE_UNUSABLE = (
    "PANIC",
    "no devices/emulators found",
    "adb: no devices",
    "Device offline",
    "device offline",
    "requires hardware acceleration",
    "KVM permission",
    "Timed out waiting for emulator",
    "INSTALL_FAILED",
    "Could not install",
    "Unable to install",
    "emulator: ERROR:",
    "There is no Android Virtual Device",
)

EMULATOR_MARKERS = (
    "PANIC",
    "emulator: ERROR",
    "ERROR: x86_64 emulation currently requires hardware acceleration",
    "Device offline",
    "no devices/emulators found",
    "adb: no devices",
    "Timed out waiting for emulator",
    "INSTALL_FAILED",
    "KVM permission",
    "/dev/kvm",
    "qemu:",
    "cannot",
    "Cannot",
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


# AGP prints a connected-test failure as "<class> > <method>[<device>] FAILED", then the
# assertion or exception on the following indented lines.
TEST_FAILED = re.compile(
    r"^(?P<name>[\w.$]+\s+>\s+[^\[]+?)(?:\[(?P<device>[^\]]+)\])?\s+FAILED\s*$"
)
WHAT_WENT_WRONG = re.compile(r"^\*\s+What went wrong:")
WHAT_WENT_WRONG_END = re.compile(r"^\*\s+(Try:|Exception is:|Get more help)")
RESULT_DIRS = (
    "core/build/outputs/androidTest-results",
    "app/build/outputs/androidTest-results",
    "plugins/build/outputs/androidTest-results",
)


def classify(lines: list[str]) -> dict:
    """Everything in a Gradle log that explains why the job failed, kept apart.

    These are kept as separate findings rather than one list of scary lines because they are
    not interchangeable: a compile error means no test ran, a FAILED test means the code is
    wrong, and "What went wrong" is Gradle's own one-paragraph answer. Reporting them in that
    order is what stops an emulator warning from standing in for a cause.
    """
    errors: list[dict] = []
    seen: set[tuple[str, int, str]] = set()
    tasks: list[str] = []
    emulator: list[str] = []
    test_failures: list[dict] = []
    went_wrong: list[str] = []
    collecting = False

    for raw in lines:
        line = raw.rstrip()
        stripped = line.strip()

        for pattern in (KOTLIN_ERROR, JAVA_ERROR):
            match = pattern.match(stripped)
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

        if WHAT_WENT_WRONG.match(stripped):
            collecting = True
            continue
        if collecting:
            if WHAT_WENT_WRONG_END.match(stripped) or len(went_wrong) > 24:
                collecting = False
            elif stripped:
                went_wrong.append(WORKSPACE_INLINE.sub("", stripped))
            continue

        task = TASK_FAILED.match(line)
        if task:
            tasks.append(task.group("task"))
            continue
        failed = EXECUTION_FAILED.search(line)
        if failed and failed.group("task") not in tasks:
            tasks.append(failed.group("task"))
            continue

        failure = TEST_FAILED.match(stripped)
        if failure:
            test_failures.append({"name": failure.group("name").strip(), "reason": ""})
            continue
        # The reason for the failure just named: AGP indents the assertion and its stack.
        if test_failures and not test_failures[-1]["reason"] and raw[:1].isspace() and stripped:
            test_failures[-1]["reason"] = WORKSPACE_INLINE.sub("", stripped)[:400]

        if any(marker in line for marker in EMULATOR_MARKERS):
            if stripped and stripped not in emulator:
                emulator.append(stripped)

    return {
        "errors": errors,
        "tasks": tasks,
        "emulator": emulator,
        "test_failures": test_failures,
        "went_wrong": went_wrong,
        "hard_device_failure": any(
            any(marker in line for marker in DEVICE_UNUSABLE) for line in emulator
        ),
    }


def result_summary() -> str | None:
    """What the instrumentation XML says, independent of Gradle's exit status.

    connectedAndroidTest exits zero when there was no device and zero when nothing matched,
    so the count has to come from the files. It is reported per module because a total cannot
    distinguish "67 ran and 1 failed" from "42 ran in one module and the other never started",
    and the second is a device problem wearing the first one's clothes.
    """
    per_module = []
    tests = failures = skipped = 0
    for directory in RESULT_DIRS:
        root = Path(directory)
        if not root.is_dir():
            continue
        module = root.parts[0]
        files = sorted(root.rglob("*.xml"))
        module_tests = module_failures = module_skipped = 0
        for path in files:
            text = path.read_text(encoding="utf-8", errors="replace")
            for tag in re.finditer(r"<testsuite\b[^>]*>", text):
                attributes = tag.group(0)

                def number(name: str) -> int:
                    found = re.search(r'\b%s="(\d+)"' % name, attributes)
                    return int(found.group(1)) if found else 0

                module_tests += number("tests")
                module_failures += number("failures") + number("errors")
                module_skipped += number("skipped")
        per_module.append(
            f"{module}: {module_tests} testcase(s), {module_failures} failure(s), "
            f"{module_skipped} skipped, {len(files)} file(s)"
        )
        tests += module_tests
        failures += module_failures
        skipped += module_skipped

    if not per_module:
        return None
    total = f"{len(per_module)} module(s) wrote instrumentation results: " + "; ".join(per_module)
    if len(per_module) > 1:
        total += f". Total {tests} testcase(s), {failures} failure(s), {skipped} skipped"
    return total


def annotate(title: str, message: str, path: str | None = None, line: int | None = None,
             level: str = "error") -> None:
    properties = [f"title={title}"]
    if path:
        properties.append(f"file={path}")
    if line:
        properties.append(f"line={line}")
    # Newlines would end the annotation command early.
    text = " ".join(message.split())
    print(f"::{level} {','.join(properties)}::{text[:700]}")


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
        help="cap on annotations per finding, so one broken file cannot bury the summary",
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
    found = classify(lines)
    errors = found["errors"]
    tasks = found["tasks"]
    emulator = found["emulator"]
    test_failures = found["test_failures"]
    went_wrong = found["went_wrong"]

    digest += ["### Instrumentation tests", ""]

    results = result_summary()
    if results:
        annotate("instrumentation results on the runner", results, level="notice")
        digest += [results, ""]

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
    elif test_failures:
        digest += [
            f"**{len(test_failures)} device test(s) failed.** The emulator was usable and the "
            "code compiled; these assertions are the result.",
            "",
            "| Test | Why |",
            "|---|---|",
        ]
        for failure in test_failures[: args.max]:
            reason = failure["reason"] or "no reason was printed after the FAILED line"
            annotate("device test failed", f"{failure['name']} -- {reason}")
            digest.append(f"| `{failure['name']}` | {reason[:200]} |")
        if len(test_failures) > args.max:
            digest.append(f"| … | {len(test_failures) - args.max} more in the job log |")
    elif found["hard_device_failure"] or not results:
        # No compile error and no test outcome: the device is the suspect, but only say so
        # when the evidence is a hard marker or there is genuinely no result to contradict it.
        first = emulator[0][:200] if emulator else "no emulator line in the Gradle output"
        annotate(
            "no test ran",
            f"no compile error and no test outcome in {args.log}; the strongest device-side "
            f"line is: {first}",
        )
        digest += [
            "**No test produced an outcome.** That is not a passing device suite.",
            "",
            "```",
            *(line[:200] for line in emulator[:12]),
            "```",
        ]
    else:
        annotate(
            "the suites reported no failure, yet the job failed",
            f"{results}; failing tasks: {', '.join(tasks) or 'none named'}",
        )
        digest += [
            f"The instrumentation results record no failure ({results}), so the job failed "
            "outside the tests: a Gradle task, the counting step, or the teardown.",
        ]

    # Gradle's own answer is the one paragraph that is always worth reading, whatever the
    # classification above decided, so it goes out separately rather than being one of the
    # branches.
    if went_wrong:
        annotate("gradle: what went wrong", " | ".join(went_wrong[:6]))
        digest += ["", "```", *(line[:200] for line in went_wrong[:12]), "```"]
    if tasks:
        annotate("gradle task failed", ", ".join(tasks))
        digest += ["", f"Failing Gradle tasks: {', '.join(tasks)}."]
    if emulator and test_failures:
        # Present, but not the cause: the tests ran and named themselves.
        annotate("emulator output, for context only", " | ".join(emulator[:4]), level="warning")

    if not (errors or test_failures or went_wrong or tasks or emulator):
        tail = [line.strip() for line in lines if line.strip()][-12:]
        for entry in tail:
            annotate("tail of the gradle output", entry[:300], level="warning")
        digest += ["", "Nothing in the log matched a known failure shape. The tail:", "", "```", *tail, "```"]

    write_digest(args.output, digest)
    return 0


def write_digest(output: str | None, digest: list[str]) -> None:
    text = "\n".join(digest) + "\n"
    if output:
        Path(output).write_text(text, encoding="utf-8")
    print(text, file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

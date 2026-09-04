#!/usr/bin/env python3
"""Brace/paren balance checker for Kotlin sources.

Sarothi's CI cannot run a full Android build in every environment, so this is the
cheap structural gate: it strips comments, string literals (including triple
quoted raw strings and escapes) and char literals, then verifies that every
block and call delimiter balances.

It does NOT parse Kotlin. It catches the class of typo that actually breaks a
build silently late -- an unclosed class, function or lambda -- and nothing more.

Usage:
    python3 scripts/check_kotlin_braces.py [paths...]

With no paths it walks the repository for .kt and .kts files.
"""

from __future__ import annotations

import sys
from pathlib import Path

OPENERS = "{[("
CLOSERS = "}])"
PAIRS = {")": "(", "]": "[", "}": "{"}


def strip_source(text: str) -> str:
    """Returns source with comments and literals replaced by spaces of equal length."""
    out = list(text)
    i = 0
    n = len(text)

    def blank(start: int, end: int) -> None:
        for index in range(start, min(end, n)):
            if out[index] != "\n":
                out[index] = " "

    while i < n:
        char = text[i]

        # line comment
        if char == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            end = n if end == -1 else end
            blank(i, end)
            i = end
            continue

        # block comment (Kotlin allows nesting)
        if char == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 0
            j = i
            while j < n:
                if text[j] == "/" and j + 1 < n and text[j + 1] == "*":
                    depth += 1
                    j += 2
                    continue
                if text[j] == "*" and j + 1 < n and text[j + 1] == "/":
                    depth -= 1
                    j += 2
                    if depth == 0:
                        break
                    continue
                j += 1
            blank(i, j)
            i = j
            continue

        # triple quoted raw string: no escapes, but ${...} interpolation is kept
        # so braces inside an interpolation still have to balance
        if char == '"' and text[i : i + 3] == '"""':
            j = i + 3
            kept: list[tuple[int, int]] = []
            while j < n:
                if text[j] == "$" and j + 1 < n and text[j + 1] == "{":
                    depth = 1
                    start = j
                    j += 2
                    while j < n and depth:
                        if text[j] == "{":
                            depth += 1
                        elif text[j] == "}":
                            depth -= 1
                        j += 1
                    kept.append((start, j))
                    continue
                if text[j : j + 3] == '"""':
                    j += 3
                    break
                j += 1
            for index in range(i, min(j, n)):
                out[index] = " "
            for start, end in kept:
                for index in range(start, min(end, n)):
                    out[index] = text[index]
            i = j
            continue

        # ordinary string literal
        if char == '"':
            j = i + 1
            kept = []
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    # unterminated; stop so the error is reported as a balance problem
                    break
                if text[j] == "$" and j + 1 < n and text[j + 1] == "{":
                    depth = 1
                    start = j
                    j += 2
                    while j < n and depth:
                        if text[j] == "{":
                            depth += 1
                        elif text[j] == "}":
                            depth -= 1
                        j += 1
                    kept.append((start, j))
                    continue
                j += 1
            for index in range(i, min(j, n)):
                out[index] = " "
            for start, end in kept:
                for index in range(start, min(end, n)):
                    out[index] = text[index]
            i = j
            continue

        # char literal
        if char == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            blank(i, j)
            i = j
            continue

        i += 1

    return "".join(out)


def check(path: Path) -> list[str]:
    text = strip_source(path.read_text(encoding="utf-8", errors="replace"))
    stack: list[tuple[str, int]] = []
    problems: list[str] = []

    for index, char in enumerate(text):
        if char in OPENERS:
            stack.append((char, index))
        elif char in CLOSERS:
            if not stack:
                line = text.count("\n", 0, index) + 1
                problems.append(f"{path}:{line}: unmatched '{char}'")
                continue
            opener, opener_index = stack.pop()
            if opener != PAIRS[char]:
                line = text.count("\n", 0, index) + 1
                opened_line = text.count("\n", 0, opener_index) + 1
                problems.append(
                    f"{path}:{line}: '{char}' closes '{opener}' opened at line {opened_line}"
                )

    for opener, opener_index in stack:
        line = text.count("\n", 0, opener_index) + 1
        problems.append(f"{path}:{line}: unclosed '{opener}'")

    return problems


def main(argv: list[str]) -> int:
    if len(argv) > 1:
        roots = [Path(a) for a in argv[1:]]
    else:
        roots = [Path(".")]

    files: list[Path] = []
    for root in roots:
        if root.is_file():
            files.append(root)
        else:
            files.extend(sorted(p for p in root.rglob("*.kt")))
            files.extend(sorted(p for p in root.rglob("*.kts")))

    files = [f for f in files if not any(part in {".git", "build", "third_party"} for part in f.parts)]

    failures = 0
    for path in files:
        problems = check(path)
        if problems:
            failures += 1
            for problem in problems:
                print(problem)

    print(f"checked {len(files)} file(s); {failures} with unbalanced delimiters")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

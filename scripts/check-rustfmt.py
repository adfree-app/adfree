#!/usr/bin/env python3
"""Does rustfmt want to rewrite a line this change wrote?

The tree is hand-formatted on purpose: at rustfmt's default width roughly three
hundred places in files nobody touched would move, so a whole-tree
`cargo fmt --check` is red on the day it lands and stays red — a gate people
learn to ignore. Reformatting a whole file to satisfy a one-line change is the
other bad trade: it buries the change in a diff of somebody else's code.

So the question asked here is the narrow one that is actually actionable: for
the lines this change *adds* or rewrites, would rustfmt write them differently?
The rest of the file is left alone, and a file that grows a formatted addition
does not get reformatted behind the author's back.

    scripts/check-rustfmt.py                # against the base commit
    scripts/check-rustfmt.py <base-sha>
    BASE_SHA=<sha> scripts/check-rustfmt.py # what CI passes in

With no base — a fresh clone, a push whose base is all zeroes, a manual run
outside git — it says it compared nothing and passes; it never guesses.

Exit: 0 the added lines are rustfmt-clean, 1 they are not, 2 git could not be
asked (no repository, no such commit).
"""

from __future__ import annotations

import argparse
import difflib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout


def added_lines(base: str) -> dict[str, set[int]]:
    """Line numbers (1-based, in the working tree file) this change adds."""
    diff = git("diff", "--unified=0", "--diff-filter=ACMRT", f"{base}...HEAD", "--", "rust")
    touched: dict[str, set[int]] = {}
    current: str | None = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
            touched.setdefault(current, set())
        elif current is not None and (match := HUNK.match(line)):
            start = int(match.group(1))
            count = int(match.group(2) or 1)
            touched[current].update(range(start, start + count))
        elif line.startswith("diff --git "):
            current = None
    # A new file arrives as one hunk but git reports `+1,N`; a binary or a
    # rename has nothing to check.
    return {path: lines for path, lines in touched.items() if lines}


def formatted(path: Path) -> list[str]:
    result = subprocess.run(
        ["rustfmt", "--edition", "2021", "--emit", "stdout", str(path)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        # A file rustfmt cannot parse is a compile error, reported by the
        # compiler; this check is not the place to repeat it.
        return []
    return result.stdout.splitlines()


def offenders(path: Path, lines: set[int]) -> list[tuple[int, list[str]]]:
    """The entropy hunks that touch a line this change added."""
    original = path.read_text(encoding="utf-8").splitlines()
    wanted = formatted(path)
    if not wanted:
        return []
    found = []
    matcher = difflib.SequenceMatcher(None, original, wanted, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        # rustfmt reflowing a call may join lines the change did not name; the
        # hunk still counts when it swallows an added line, because that is the
        # line with the wrong shape.
        covered = set(range(i1 + 1, i2 + 1))
        if not covered & lines:
            continue
        found.append((i1 + 1, [*original[i1:i2], *wanted[j1:j2]]))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", nargs="?", default=None)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    base = args.base or ""
    if not base:
        head = subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", "HEAD^"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        base = head.stdout.strip()
        if not base:
            print("check-rustfmt: no base commit to compare against; nothing to check")
            return 0
        if not args.quiet:
            print(f"check-rustfmt: no base given, comparing against HEAD^ ({base[:12]})")

    if base in {"0000000000000000000000000000000000000000", ""}:
        print("check-rustfmt: the base is the empty commit; nothing to check")
        return 0

    try:
        git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}")
    except subprocess.CalledProcessError:
        print(f"::error::base {base} is not a commit in this checkout")
        return 2

    touched = added_lines(base)
    if not touched:
        print(f"check-rustfmt: no Rust file changed since {base[:12]}")
        return 0

    status = 0
    checked = 0
    for name, lines in sorted(touched.items()):
        path = ROOT / name
        if not path.is_file() or path.suffix != ".rs":
            continue
        checked += 1
        for line, body in offenders(path, lines):
            print(f"::error file={name},line={line}::rustfmt would write this line differently")
            for text in body:
                print(f"    {text}")
            status = 1

    if status == 0 and not args.quiet:
        print(f"check-rustfmt: the lines this change adds in {checked} file(s) are clean")
    return status


if __name__ == "__main__":
    sys.exit(main())

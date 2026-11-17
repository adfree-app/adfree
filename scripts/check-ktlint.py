#!/usr/bin/env python3
"""Would ktlint rewrite a line this change wrote?

The Kotlin tree is not ktlint's output: run over the whole tree, the standard
rule set reports a few hundred places in files nobody touched, so a whole-tree
`ktlint` gate would be red on the day it lands and stay red — the kind of gate
people learn to skip. Reformatting a whole file to satisfy a one-line change is
the other bad trade: it buries the change in a diff of somebody else's code.

So, like `check-rustfmt.py` does for Rust, the question here is the narrow one
that is actually actionable: for the lines this change *adds* or rewrites,
would ktlint write them differently? Ktlint reports a line and a column, so
only the reports that land on an added line count.

    scripts/check-ktlint.py                # against the base commit
    scripts/check-ktlint.py <base-sha>
    BASE_SHA=<sha> scripts/check-ktlint.py # what CI passes in

The ktlint jar is fetched once from Maven Central into `.cache/ktlint/` (CI
caches that directory); `KTLINT_JAR=/path/to/ktlint.jar` points it at a local
copy instead.

With no base — a fresh clone, a push whose base is all zeroes, a manual run
outside git — it says it compared nothing and passes; it never guesses.

Exit: 0 the added lines are ktlint-clean, 1 they are not, 2 git or Java or the
jar could not be used.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
# `<path>:<line>:<column>: <message> (<rule>)`
VIOLATION = re.compile(r"^(?P<path>.+?):(?P<line>\d+):(?P<column>\d+): (?P<message>.+)$")
KTLINT_VERSION = "1.8.0"
KTLINT_URL = (
    "https://repo1.maven.org/maven2/com/pinterest/ktlint/ktlint-cli/"
    f"{KTLINT_VERSION}/ktlint-cli-{KTLINT_VERSION}-all.jar"
)
KOTLIN_SUFFIXES = {".kt", ".kts"}


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout


def added_lines(base: str) -> dict[str, set[int]]:
    """Line numbers (1-based, in the working tree file) this change adds."""
    diff = git(
        "diff",
        "--unified=0",
        "--diff-filter=ACMRT",
        f"{base}...HEAD",
        "--",
        "*.kt",
        "*.kts",
    )
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


def ktlint_jar() -> Path:
    override = os.environ.get("KTLINT_JAR", "").strip()
    if override:
        jar = Path(override).resolve()
        if not jar.is_file():
            raise SystemExit(f"check-ktlint: KTLINT_JAR is not a file: {jar}")
        return jar

    jar = ROOT / ".cache" / "ktlint" / f"ktlint-cli-{KTLINT_VERSION}-all.jar"
    if jar.is_file():
        return jar

    jar.parent.mkdir(parents=True, exist_ok=True)
    partial = jar.with_suffix(".part")
    print(f"check-ktlint: fetching ktlint {KTLINT_VERSION}")
    try:
        urllib.request.urlretrieve(KTLINT_URL, partial)
    except Exception as error:  # noqa: BLE001 - any download failure means no check
        partial.unlink(missing_ok=True)
        raise SystemExit(f"check-ktlint: could not fetch {KTLINT_URL}: {error}")
    partial.replace(jar)
    return jar


def violations(jar: Path, path: Path) -> dict[int, list[str]]:
    """ktlint's reports for one file, keyed by line."""
    result = subprocess.run(
        [
            "java",
            "-jar",
            str(jar),
            "--log-level=error",
            "--relative",
            str(path.relative_to(ROOT)),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    found: dict[int, list[str]] = {}
    for line in result.stdout.splitlines():
        match = VIOLATION.match(line)
        if match and match.group("path").endswith(path.name):
            found.setdefault(int(match.group("line")), []).append(match.group("message"))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", nargs="?", default=None)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if not shutil.which("java"):
        print("check-ktlint: java is not on PATH; nothing to run ktlint with")
        return 2

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
            print("check-ktlint: no base commit to compare against; nothing to check")
            return 0
        if not args.quiet:
            print(f"check-ktlint: no base given, comparing against HEAD^ ({base[:12]})")

    if base in {"0000000000000000000000000000000000000000", ""}:
        print("check-ktlint: the base is the empty commit; nothing to check")
        return 0

    try:
        git("rev-parse", "--verify", "--quiet", f"{base}^{{commit}}")
    except subprocess.CalledProcessError:
        print(f"::error::base {base} is not a commit in this checkout")
        return 2

    touched = added_lines(base)
    if not touched:
        print(f"check-ktlint: no Kotlin file changed since {base[:12]}")
        return 0

    to_check = [
        (name, lines)
        for name, lines in sorted(touched.items())
        if Path(name).suffix in KOTLIN_SUFFIXES and (ROOT / name).is_file()
    ]
    if not to_check:
        print(f"check-ktlint: no Kotlin file with added lines since {base[:12]}")
        return 0

    jar = ktlint_jar()
    status = 0
    for name, lines in to_check:
        for line, messages in sorted(violations(jar, ROOT / name).items()):
            if line not in lines:
                continue
            status = 1
            for message in messages:
                print(f"::error file={name},line={line}::ktlint: {message}")

    if status == 0 and not args.quiet:
        print(f"check-ktlint: the lines this change adds in {len(to_check)} file(s) are clean")
    return status


if __name__ == "__main__":
    sys.exit(main())

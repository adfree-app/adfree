#!/usr/bin/env python3
"""Do the string constants and the string resources still describe each other?

Every user-facing word is reached through a constant in `Strings.kt` and stored
in an XML file per language. Three things can quietly go wrong, and none of them
fails a compile:

  * a constant with no string behind it — the screen shows its own key,
    `dns_rules_user_title`, to the user;
  * a string nobody names — dead weight that survives every cleanup;
  * a language that is missing a string the default language has — the row falls
    back to English (or to the key, if the fallback list is wrong).

The first two are errors: they are always somebody's mistake. The third is
reported as a warning, because a translation legitimately lands later than the
code that needs it, and a red build over a missing Vietnamese word would teach
people to stop translating.

    scripts/check-l10n.py            # check
    scripts/check-l10n.py --quiet    # only what is wrong

Exit: 0 everything agrees (warnings allowed), 1 a real inconsistency.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STRINGS_KT = ROOT / "shared/src/main/kotlin/git/shin/adfree/l10n/Strings.kt"
RESOURCES = ROOT / "shared/src/main/resources/l10n"
DEFAULT_LOCALE = "strings-default.xml"

CONSTANT = re.compile(r'const val (\w+) = "([^"]+)"')


def keys_of(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {element.get("name") for element in root if element.get("name")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="say only what is wrong")
    args = parser.parse_args()

    def note(message: str) -> None:
        if not args.quiet:
            print(message)

    # A missing file is a different failure from a disagreement inside it, and
    # worth its own exit: the check should not report "no problems" when it
    # never read anything.
    for required in (STRINGS_KT, RESOURCES / DEFAULT_LOCALE):
        if not required.is_file():
            print(f"::error file={required}::missing")
            return 1

    constants = CONSTANT.findall(STRINGS_KT.read_text(encoding="utf-8"))
    named = {value for _, value in constants}
    default = keys_of(RESOURCES / DEFAULT_LOCALE)

    status = 0

    for constant, value in constants:
        if value not in default:
            print(
                f"::error file={STRINGS_KT}::Strings.{constant} = \"{value}\" "
                f"has no entry in {DEFAULT_LOCALE}"
            )
            status = 1
    unnamed = sorted(default - named)
    for key in unnamed:
        print(f"::error file={RESOURCES / DEFAULT_LOCALE}::{key} is in no Strings constant")

    for locale_file in sorted(RESOURCES.glob("strings-*.xml")):
        if locale_file.name == DEFAULT_LOCALE:
            continue
        missing = sorted(default - keys_of(locale_file))
        if missing:
            shown = ", ".join(missing[:8]) + (", …" if len(missing) > 8 else "")
            print(f"::warning file={locale_file}::{len(missing)} untranslated: {shown}")

    if status == 0:
        note(
            f"check-l10n: {len(constants)} constants, {len(default)} strings in "
            f"{DEFAULT_LOCALE}, they agree"
        )
    return status


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Add or replace an Eclipse-mieux p2 repository location in a target file.

The location is identified by its repository id, so re-running with the same
id replaces the previous entry instead of appending a duplicate.

Locations use slicer mode like the rest of the SDK target (Tycho rejects
mixed modes), so list every needed unit, not just the root features.
"""

from pathlib import Path
import re
import sys


def main() -> None:
    if len(sys.argv) < 5:
        raise SystemExit(
            "usage: add-target-location.py TARGET_FILE REPOSITORY_ID"
            " REPOSITORY_URL UNIT_ID=VERSION..."
        )

    target_path = Path(sys.argv[1])
    repository_id = sys.argv[2]
    repository_url = sys.argv[3]
    units = [unit.split("=", 1) for unit in sys.argv[4:]]
    target = target_path.read_text(encoding="utf-8")

    marker = f'repository id="{repository_id}"'

    def remove_previous(location: re.Match[str]) -> str:
        return "" if marker in location.group(0) else location.group(0)

    target = re.sub(
        r"(?ms)^[ \t]*<location\b[^>]*?(?<!/)>.*?^[ \t]*</location>[ \t]*\n?",
        remove_previous,
        target,
    )

    unit_lines = "".join(
        f'      <unit id="{unit_id}" version="{version}"/>\n'
        for unit_id, version in units
    )
    location = f'''    <location includeAllPlatforms="true" includeConfigurePhase="false"\
 includeMode="slicer" includeSource="true" type="InstallableUnit">
{unit_lines}      <repository id="{repository_id}" location="{repository_url}"/>
    </location>
'''
    closing = "  </locations>"
    if closing not in target:
        raise SystemExit(f"No locations closing tag in {target_path}")
    target = target.replace(closing, location + closing, 1)
    target_path.write_text(target, encoding="utf-8")


if __name__ == "__main__":
    main()

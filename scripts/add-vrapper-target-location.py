#!/usr/bin/env python3
"""Add or replace the generated Vrapper repository in an Eclipse target file."""

from pathlib import Path
import re
import sys


MARKER = 'repository id="eclipse_mieux_vrapper"'


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: add-vrapper-target-location.py TARGET_FILE REPOSITORY_DIR VERSION"
        )

    target_path = Path(sys.argv[1])
    repository = Path(sys.argv[2]).resolve()
    version = sys.argv[3]
    target = target_path.read_text(encoding="utf-8")

    def remove_previous(location: re.Match[str]) -> str:
        return "" if MARKER in location.group(0) else location.group(0)

    target = re.sub(
        r"(?ms)^[ \t]*<location\b[^>]*?(?<!/)>.*?^[ \t]*</location>[ \t]*\n?",
        remove_previous,
        target,
    )

    location = f'''    <location includeAllPlatforms="true" includeConfigurePhase="false"\
 includeMode="slicer" includeSource="true" type="InstallableUnit">
      <unit id="net.sourceforge.vrapper.feature.feature.group" version="{version}"/>
      <repository id="eclipse_mieux_vrapper" location="file://{repository}"/>
    </location>
'''
    closing = "  </locations>"
    if closing not in target:
        raise SystemExit(f"No locations closing tag in {target_path}")
    target = target.replace(closing, location + closing, 1)
    target_path.write_text(target, encoding="utf-8")


if __name__ == "__main__":
    main()

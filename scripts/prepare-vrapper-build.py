#!/usr/bin/env python3
"""Prepare a pristine Vrapper checkout for the local, source-only build.

The Vrapper checkout is intentionally kept untouched in the parent repository.
This script only edits a temporary archive made from the pinned submodule
commit, removing optional integrations whose old target repositories are not
needed for the base Vrapper feature.
"""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def remove_xml_children(parent: ET.Element, predicate) -> None:

    for child in list(parent):
        if predicate(child):
            parent.remove(child)


def prepare_target(root: Path) -> None:

    target_path = root / "releng/net.sourceforge.vrapper.releng.target/net.sourceforge.vrapper.releng.target.target"
    tree = ET.parse(target_path)
    target = tree.getroot()
    locations = target.find("locations")
    if locations is None:
        raise RuntimeError(f"No locations element in {target_path}")

    obsolete_repositories = {"eclipse_cdt", "eclipse_pydev"}

    def should_remove(location: ET.Element) -> bool:

        repositories = {
            child.attrib.get("id")
            for child in location
            if child.tag == "repository"
        }
        # Maven locations in the upstream target are for tests and optional
        # integrations.  The base feature needs only the Eclipse and Orbit
        # installable-unit locations.
        return not repositories or bool(repositories & obsolete_repositories)

    remove_xml_children(locations, should_remove)
    tree.write(target_path, encoding="UTF-8", xml_declaration=True)


def prepare_category(root: Path) -> None:

    category_path = root / "releng/net.sourceforge.vrapper.releng.update-site/category.xml"
    tree = ET.parse(category_path)
    site = tree.getroot()
    remove_xml_children(
        site,
        lambda child: child.tag == "feature"
        and child.attrib.get("id") != "net.sourceforge.vrapper.feature",
    )
    remove_xml_children(
        site,
        lambda child: child.tag == "category-def"
        and child.attrib.get("name") != "Vrapper",
    )
    tree.write(category_path, encoding="UTF-8", xml_declaration=True)


def prepare_pom(root: Path) -> None:

    pom_path = root / "pom.xml"
    pom = pom_path.read_text(encoding="utf-8")
    # The upstream build asks Tycho for a JavaSE-17 toolchain.  The parent
    # build already supplies a newer JDK and Tycho can target the manifest's
    # execution environment directly, so avoid requiring another toolchain.
    pom = re.sub(r"\s*<useJDK>BREE</useJDK>", "", pom)
    pom = re.sub(r"\s*<strictCompilerTarget>true</strictCompilerTarget>", "", pom)
    # This requirement exists only for Vrapper's Windows password-provider
    # tests, which are not part of the base feature build.
    pom = re.sub(
        r"\s*<extraRequirements\b[^>]*>.*?</extraRequirements>",
        "",
        pom,
        flags=re.DOTALL,
    )
    pom_path.write_text(pom, encoding="utf-8")


def main() -> None:

    if len(sys.argv) != 2:
        raise SystemExit("usage: prepare-vrapper-build.py TEMP_VRAPPER_ROOT")
    root = Path(sys.argv[1]).resolve()
    prepare_pom(root)
    prepare_target(root)
    prepare_category(root)


if __name__ == "__main__":
    main()

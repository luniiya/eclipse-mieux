#!/usr/bin/env python3
"""Print the dependency closure of some p2 units as UNIT_ID=VERSION lines.

Tycho only allows one include mode per target file, and the SDK target uses
"slicer", which follows feature includes but not ordinary requirements. So a
feature pulled from a big repository (e.g. a simultaneous release) arrives
without its third-party dependencies. This resolves those dependencies
against the repository's own metadata so every needed unit can be listed in
the target location explicitly.

Units whose bundle is built from source in the aggregator (any
Bundle-SymbolicName found under SOURCE_DIR) are left out: the reactor
provides newer versions of those.

usage: resolve-p2-closure.py REPOSITORY_URL SOURCE_DIR UNIT_ID=VERSION...
"""

from pathlib import Path
import lzma
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

# The product is only materialized for linux/gtk/x86_64 by default (see
# build.sh); anything filtered to another platform is not needed.
HOST = {"osgi.os": "linux", "osgi.ws": "gtk", "osgi.arch": "x86_64"}


def parse_version(text):
    parts = text.split(".", 3)
    numbers = [int(p) for p in parts[:3]] + [0] * (3 - len(parts[:3]))
    return (*numbers, parts[3] if len(parts) > 3 else "")


def in_range(version, spec):
    spec = spec.strip()
    if spec[0] not in "[(":
        return version >= parse_version(spec)
    low, high = spec[1:-1].split(",")
    low, high = parse_version(low.strip()), parse_version(high.strip())
    return (
        (version >= low if spec[0] == "[" else version > low)
        and (version <= high if spec[-1] == "]" else version < high)
    )


def filter_matches(text):
    # Only plain conjunctions like (&(osgi.os=win32)(osgi.arch=x86_64)) are
    # judged; anything with | or ! is kept, which at worst adds an unused unit.
    if not text or "|" in text or "!" in text:
        return True
    return all(
        HOST[key] == value
        for key, value in re.findall(r"\((osgi\.(?:os|ws|arch))=([^)]*)\)", text)
    )


def load_units(repository_url):
    with urllib.request.urlopen(f"{repository_url}/content.xml.xz") as response:
        root = ET.fromstring(lzma.decompress(response.read()))
    units = {}
    providers = {}
    for unit in root.iter("unit"):
        key = (unit.get("id"), unit.get("version"))
        units[key] = unit
        for provided in unit.iter("provided"):
            providers.setdefault(
                (provided.get("namespace"), provided.get("name")), []
            ).append((parse_version(provided.get("version")), key))
    return units, providers


def reactor_bundles(source_dir):
    names = set()
    # followlinks: the aggregator's eclipse.platform is a symlink to this repo.
    manifests = (
        Path(directory, "MANIFEST.MF")
        for directory, subdirs, files in os.walk(source_dir, followlinks=True)
        if directory.endswith("META-INF") and "MANIFEST.MF" in files
        and "/target/" not in directory and "/.git/" not in directory
    )
    for manifest in manifests:
        try:
            text = manifest.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        match = re.search(r"^Bundle-SymbolicName:\s*([^;\s]+)", text, re.M)
        if match:
            names.add(match.group(1))
    return names


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    repository_url, source_dir = sys.argv[1].rstrip("/"), sys.argv[2]
    roots = [tuple(unit.split("=", 1)) for unit in sys.argv[3:]]

    units, providers = load_units(repository_url)
    reactor = reactor_bundles(source_dir)

    def is_reactor(key):
        unit_id = key[0]
        base = re.sub(r"\.(source|feature\.group|feature\.jar)$", "", unit_id)
        return unit_id in reactor or base in reactor

    seen = set()
    pending = list(roots)
    while pending:
        key = pending.pop()
        if key in seen:
            continue
        seen.add(key)
        unit = units.get(key)
        if unit is None:
            raise SystemExit(f"{key[0]} {key[1]} is not in {repository_url}")
        for required in unit.iter("required"):
            if required.get("optional") == "true" or required.get("greedy") == "false":
                continue
            filter_element = required.find("filter")
            if filter_element is not None and not filter_matches(filter_element.text):
                continue
            namespace, name = required.get("namespace"), required.get("name")
            if namespace not in ("org.eclipse.equinox.p2.iu", "osgi.bundle", "java.package"):
                continue
            candidates = [
                (version, provider)
                for version, provider in providers.get((namespace, name), [])
                if in_range(version, required.get("range", "0.0.0"))
            ]
            if not candidates:
                continue  # satisfied by the JRE or another target location
            provider = max(candidates)[1]
            if is_reactor(provider):
                continue
            pending.append(provider)

    for unit_id, version in sorted(seen):
        if unit_id.endswith(".feature.jar") or unit_id.startswith("a.jre.") \
                or is_reactor((unit_id, version)):
            continue
        print(f"{unit_id}={version}")


if __name__ == "__main__":
    main()

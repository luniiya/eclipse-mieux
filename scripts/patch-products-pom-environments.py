#!/usr/bin/env python3
"""Idempotently patch the aggregator's products/pom.xml so the product
modules it parents (eclipse-platform, eclipse-sdk, equinox-launcher,
equinox-starterkit) build/materialize/archive only this host's platform by
default, instead of all 8 environments eclipse-platform-parent's
target-platform-configuration lists.

Why here and not in eclipse-platform-parent directly: that parent's
<environments> list is inherited by EVERY reactor module, including
org.eclipse.equinox.executable, which genuinely needs all platforms'
launcher fragments resolvable in ITS OWN target platform regardless of what
it's packaging (narrowing it there breaks package-feature - already learned
that the hard way, see scripts/build.sh). products/pom.xml is NOT a parent
of equinox.executable, only of the product modules that actually
materialize/archive per-environment output - so overriding
target-platform-configuration's <environments> there (with
combine.self="override" so it fully replaces rather than merges with the
inherited list) narrows exactly the wasteful part without touching
equinox.executable's own resolution at all.

Called from scripts/build.sh against the disposable aggregator checkout -
this file is never committed there, so this patch step re-applies on every
fresh clone. Safe to re-run: no-ops if already patched (checked by the
caller via `grep -q full-platform-matrix`, and again here for safety).

Usage: patch-products-pom-environments.py <path-to-products/pom.xml>
"""
import re
import sys

FULL_PLATFORM_ENVIRONMENTS = [
    ("linux", "gtk", "x86_64"),
    ("linux", "gtk", "ppc64le"),
    ("linux", "gtk", "riscv64"),
    ("linux", "gtk", "aarch64"),
    ("win32", "win32", "x86_64"),
    ("win32", "win32", "aarch64"),
    ("macosx", "cocoa", "x86_64"),
    ("macosx", "cocoa", "aarch64"),
]

HOST_ENVIRONMENT = ("linux", "gtk", "x86_64")


def environments_block(envs, indent):
    lines = []
    for os_, ws, arch in envs:
        lines.append(f'{indent}\t<environment>')
        lines.append(f'{indent}\t\t<os>{os_}</os>')
        lines.append(f'{indent}\t\t<ws>{ws}</ws>')
        lines.append(f'{indent}\t\t<arch>{arch}</arch>')
        lines.append(f'{indent}\t</environment>')
    return "\n".join(lines)


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    path = sys.argv[1]

    with open(path, encoding="utf-8") as f:
        content = f.read()

    if "full-platform-matrix" in content:
        # Already patched.
        return

    single_env_plugin = (
        "\t<build>\n"
        "\t\t<!-- Default: only materialize/archive THIS host's platform.\n"
        "\t\t     Pass -Pfull-platform-matrix (scripts/build.sh flag: all platforms)\n"
        "\t\t     to restore the full 8-platform matrix. Scoped here rather than\n"
        "\t\t     in eclipse-platform-parent because org.eclipse.equinox.executable\n"
        "\t\t     needs ALL platforms resolvable in its own target platform\n"
        "\t\t     regardless of packaging target, and it does NOT inherit from\n"
        "\t\t     this pom - only these product modules do. -->\n"
        "\t\t<plugins>\n"
        "\t\t\t<plugin>\n"
        "\t\t\t\t<groupId>org.eclipse.tycho</groupId>\n"
        "\t\t\t\t<artifactId>target-platform-configuration</artifactId>\n"
        "\t\t\t\t<configuration>\n"
        '\t\t\t\t\t<environments combine.self="override">\n'
        f"{environments_block([HOST_ENVIRONMENT], indent='\t\t\t\t\t')}\n"
        "\t\t\t\t\t</environments>\n"
        "\t\t\t\t</configuration>\n"
        "\t\t\t</plugin>\n"
        "\t\t</plugins>\n"
        "\t\t<pluginManagement>"
    )

    content, n = re.subn(
        r"\t<build>\n\t\t<pluginManagement>",
        lambda _m: single_env_plugin,
        content,
        count=1,
    )
    if n != 1:
        sys.exit(
            "error: failed to find '<build>\\n\\t\\t<pluginManagement>' anchor "
            f"in {path} - file structure may have changed upstream"
        )

    full_matrix_profile = (
        "\t\t<profile>\n"
        "\t\t\t<id>full-platform-matrix</id>\n"
        "\t\t\t<build>\n"
        "\t\t\t\t<plugins>\n"
        "\t\t\t\t\t<plugin>\n"
        "\t\t\t\t\t\t<groupId>org.eclipse.tycho</groupId>\n"
        "\t\t\t\t\t\t<artifactId>target-platform-configuration</artifactId>\n"
        "\t\t\t\t\t\t<configuration>\n"
        '\t\t\t\t\t\t\t<environments combine.self="override">\n'
        f"{environments_block(FULL_PLATFORM_ENVIRONMENTS, indent='\t\t\t\t\t\t\t')}\n"
        "\t\t\t\t\t\t\t</environments>\n"
        "\t\t\t\t\t\t</configuration>\n"
        "\t\t\t\t\t</plugin>\n"
        "\t\t\t\t</plugins>\n"
        "\t\t\t</build>\n"
        "\t\t</profile>\n"
        "\t</profiles>"
    )

    content, n = re.subn(
        r"\t</profiles>",
        lambda _m: full_matrix_profile,
        content,
        count=1,
    )
    if n != 1:
        sys.exit(
            f"error: failed to find '</profiles>' anchor in {path} - "
            "file structure may have changed upstream"
        )

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


if __name__ == "__main__":
    main()

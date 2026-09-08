@AGENTS.md

# Eclipse-mieux

A fork of the Eclipse Platform (this repo = upstream `eclipse.platform`) —
required for uni coursework alongside Modelio ([[Modelio-mieux]]), and
frankly kind of miserable to use stock. Goal: build it from source, then fix
bugs and add features on top instead of just tolerating it.

## Build & install

```bash
scripts/install.sh              # build + install + (re)create app launcher
scripts/install.sh --no-build   # reinstall only, skip the rebuild
scripts/build.sh                # build only, don't touch the install
```

**This repo alone does not build a runnable IDE** — it's only the platform
*component* repo (runtime/resources/debug/ua/ant/team + the `org.eclipse.sdk`
feature): it compiles bundles, not a materialized product. A real Eclipse SDK
is assembled by the separate `eclipse.platform.releng.aggregator` project,
which stitches this repo together with jdt/pde/swt/ui/equinox as git
submodules. So `scripts/build.sh`:

1. Clones the aggregator as a **sibling checkout**, `../eclipse-platform-aggregator`
   (first run only — `--recurse-submodules` pulls jdt/pde/swt/ui/equinox too;
   expect several GB and a long clone).
2. Replaces the aggregator's `eclipse.platform` submodule directory with a
   **symlink to this repo**, so the build compiles your local edits here
   instead of an unrelated separate clone. This intentionally breaks that
   directory's git-submodule-ness inside the aggregator — that's fine, we
   never commit anything inside the aggregator checkout, only build there.
3. Runs `mvn clean verify --threads 1C -DskipTests` from the aggregator root
   (upstream quotes ~10-20 min without tests; can be longer here, don't
   assume a short timeout).

`scripts/common.sh` pins the toolchain: JDK 25 (`/usr/lib/jvm/java-25-openjdk`,
already installed) and the system Maven (`>= 3.9.12`, already installed —
no download/bootstrap needed here, unlike [[Modelio-mieux]]'s script).

Materialized output: `../eclipse-platform-aggregator/products/eclipse-sdk/target/products/org.eclipse.sdk.ide/linux/gtk/x86_64/eclipse/`
(the full SDK product — JDT/PDE included — not the bare `eclipse-platform`
product). Install target: `~/.local/opt/eclipse-mieux/`, symlinked as
`~/.local/bin/eclipse-mieux` (on PATH), with a `.desktop` launcher entry
regenerated at `~/.local/share/applications/eclipse-mieux.desktop` on every
install.

Run `eclipse-mieux` to launch the GUI directly.

## Source layout (this repo, within the aggregator)

- `runtime/` — core runtime, jobs, expressions, content types
- `resources/` — workspace, filesystem, project management
- `debug/` — debug framework and UI, external tools, launch configurations
- `team/` — version control framework (CVS examples)
- `ua/` — user assistance: help system, cheatsheets, tips
- `ant/` — Ant integration and UI
- `terminal/` — terminal view
- `platform/org.eclipse.sdk` — the SDK feature/branding (product id
  `org.eclipse.sdk.ide`, icons, splash, about text)

The rest of what actually ends up in the built IDE (JDT, PDE, SWT, the
workbench UI) lives in sibling repos under the aggregator, not here — if a
bug turns out to be in Java editing, debugging-UI-in-JDT, or the workbench
itself rather than platform/runtime/resources, it's in one of those other
submodules, not this checkout.

## Environment notes

- No passwordless sudo — don't shell out to `sudo`; scripts are designed to
  need none for the normal build/install path (JDK 25 and Maven are already
  installed system-wide).
- User's shell is fish; `~/.local/bin` is confirmed on PATH.
- Desktop environment is Hyprland — `.desktop` entries go through
  `~/.local/share/applications/` for launcher (rofi/wofi/fuzzel-style) pickup.
- The aggregator clone is heavy (jdt/pde/swt/ui/equinox as submodules) and
  the build is slow and can be fragile — don't be surprised if a fresh
  aggregator checkout needs troubleshooting network/version issues the first
  time through.

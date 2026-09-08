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
- `vim/org.eclipse.mieux.vim` — native vim mode for text editors (modal
  editing, status-bar mode indicator), started via `org.eclipse.ui.startup`
- `theming/org.eclipse.mieux.theme` — see "Theming" section below

The rest of what actually ends up in the built IDE (JDT, PDE, SWT, the
workbench UI) lives in sibling repos under the aggregator, not here — if a
bug turns out to be in Java editing, debugging-UI-in-JDT, or the workbench
itself rather than platform/runtime/resources, it's in one of those other
submodules, not this checkout.

## MCP server (agent UI automation) — planned/in progress

Goal: let an AI agent drive the running IDE **as text, not pixels** — no
screenshots, ever. The agent asks for a snapshot of a window and gets back a
structured tree of widgets (menus, buttons, checkboxes, text fields, trees,
tables) with enough identity to act on them directly.

New top-level module `mcp/` (peer to `runtime/`, `debug/`, `ua/`):

- **`org.eclipse.mieux.mcp.server`** — the bundle, split so most of it has no
  SWT/Workbench dependency:
  - `protocol/` — JSON-RPC 2.0 + MCP messages (`initialize`, `tools/list`,
    `tools/call`). Pure Java.
  - `registry/` — `Tool` interface (name, description, JSON schema,
    `execute(args)`) + `ToolRegistry`. Pure Java.
  - `transport/` — local HTTP+JSON-RPC listener via JDK's built-in
    `com.sun.net.httpserver` (no new external dep to drag through Tycho).
    Bound to `127.0.0.1` only, random port, per-launch random bearer token
    written to a local file — this is real control-surface power, so it's
    gated by at least that much.
  - `ui/` — the SWT-facing part, depends on `org.eclipse.swt`,
    `org.eclipse.ui`, `org.eclipse.jface` (same cross-submodule dependency
    pattern `debug/org.eclipse.debug.ui` already uses — SWT/Workbench source
    lives in the aggregator's sibling submodules, not this repo, but bundles
    here can depend on them same as always).
    - **Widget tree walker**: walks `Display` → `Shell[]` → `Control` tree,
      plus menu bars/context menus, into a JSON node tree (role, label,
      enabled/visible/checked/value/selection, children). Every widget in a
      snapshot gets an **ephemeral numeric id**, scoped to that snapshot —
      snapshot, then act using those ids; a fresh snapshot invalidates old
      ones. Avoids stale-widget-reference bugs.
    - **Action tools**, all marshalled onto the SWT UI thread via
      `Display.syncExec` (mandatory — SWT is single-threaded): `ui_click`,
      `ui_set_text`, `ui_set_checked`, `ui_select` (combo/list/tree/table),
      `ui_expand` (tree nodes), `ui_invoke_menu`. Plus `ui_list_windows` and
      `ui_snapshot(shell_id, maxDepth)`.
    - `IStartup` extension boots the HTTP server on workbench start
      (auto-start by default — loopback + token makes that low-risk).
  - v1 widget scope: **menus & toolbar, dialogs & wizards, trees & tables**.
    Editor text content and full launch/debug control are explicitly out of
    scope for v1.

- **`org.eclipse.mieux.mcp.server.tests`** — plain JUnit, no display: JSON-RPC
  parsing/error codes, tool dispatch, fake tools. Fast, no aggregator needed.
- **`org.eclipse.mieux.mcp.server.ui.tests`** — real JUnit-plugin tests with
  an actual `Display`/`Shell` built in-test, asserting snapshots match and
  that click/set-text/toggle/select really mutate real widgets.

**Testing reality**: the `ui`/`ui.tests` bundles depend on SWT/Workbench,
which only exist once assembled by the aggregator — so exercising them means
the `scripts/build.sh` flow (slow, first run clones the aggregator). The
`protocol`/`registry` part has no such dependency and is built/tested
standalone and fast. `ui.tests` needs a display like other UI tests here
(Xvfb locally, per the existing "Missing Dependencies"/testing notes in
AGENTS.md).

## Theming

Custom E4 CSS workbench theme, `org.eclipse.mieux.theme.lilac` ("Mieux
Lilac"): pastel lilac/white palette, Cantarell UI font, JetBrainsMono Nerd
Font Mono as the default editor font, rounded-top editor/view tabs. Set as
the default via the `cssTheme` product property in both
`platform/org.eclipse.sdk/plugin.xml` and
`platform/org.eclipse.platform/plugin.xml`; switchable at runtime via
Window > Preferences > General > Appearance.

- The E4 CSS engine (`org.eclipse.e4.ui.css.swt.theme`, lives in the `ui`
  submodule, not this repo) has **no `border-radius` property** — confirmed
  by grepping its source. `theming/org.eclipse.mieux.theme`'s
  `css/mieux-lilac.css` `@import`s the platform's own
  `e4_default_gtk.css` and overrides just the palette-carrying selectors
  (trim/toolbar/view backgrounds, tab fill/keyline/outline colors, fonts)
  rather than rebuilding the whole stylesheet.
- Rounded tab corners come from `RoundedTabRenderer`
  (`theming/org.eclipse.mieux.theme/src/.../RoundedTabRenderer.java`), a
  `CTabRendering` subclass registered via the CSS `swt-tab-renderer`
  property. `CTabRendering`'s own tab-fill/outline colors are
  package-private, so rather than reimplement tab painting (text, icon,
  close button, dirty-indicator, hot/inactive alpha blending) the renderer
  clips the paint area to a rounded-top `Path` and delegates to
  `super.draw(...)` for everything else — corners outside the path just get
  cut away.
- Out of scope for this pass: the toolbar/editor icon language (lives in the
  `ui`/`jdt`/`pde` submodules, not this checkout) and anything at the
  desktop/window-manager level (rounded window chrome, custom title bar,
  widgets) — that's Hyprland/eww territory, not something SWT/E4 can draw.

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

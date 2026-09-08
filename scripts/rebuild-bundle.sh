#!/usr/bin/env bash
# Fast inner-loop rebuild: recompiles ONE bundle (and whatever it depends on
# in the reactor) instead of the whole aggregator, then hot-patches the
# already-installed product's plugins/ directory with the new jar.
#
# This is NOT a replacement for scripts/install.sh:
#   - use install.sh (full build) after structural changes: new bundle,
#     MANIFEST.MF/feature changes, version bumps, or the very first install.
#   - use this for "I changed a .java file in a bundle that's already
#     installed, let me see it work" loops.
#
# For actual day-to-day development, self-hosting beats even this: import
# the bundle project into the built Eclipse and Run As > Eclipse Application.
# That recompiles via JDT's incremental compiler with no Maven step at all.
# Use this script when you want a quick CLI-only check instead.
#
# Usage: scripts/rebuild-bundle.sh <bundle-path-relative-to-this-repo>
#   e.g. scripts/rebuild-bundle.sh runtime/bundles/org.eclipse.core.runtime
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

BUNDLE_REL="${1:?Usage: $0 <bundle-path-relative-to-repo-root>}"
BUNDLE_REL="${BUNDLE_REL%/}"
BUNDLE_DIR="${REPO_ROOT}/${BUNDLE_REL}"
[[ -d "${BUNDLE_DIR}" ]] || { echo "error: no such directory: ${BUNDLE_DIR}" >&2; exit 1; }

setup_env

if [[ ! -L "${AGGREGATOR_DIR}/eclipse.platform" ]]; then
    echo "error: aggregator not set up yet - run scripts/install.sh (full build) at least once first." >&2
    exit 1
fi

INSTALL_DIR="${HOME}/.local/opt/eclipse-mieux"
if [[ ! -d "${INSTALL_DIR}/plugins" ]]; then
    echo "error: nothing installed at ${INSTALL_DIR} yet - run scripts/install.sh at least once first." >&2
    exit 1
fi

MODULE_PATH="eclipse.platform/${BUNDLE_REL}"

echo "==> Rebuilding just ${MODULE_PATH} (and whatever it depends on in-reactor)"
cd "${AGGREGATOR_DIR}"
mvn install -o -DskipTests -pl "${MODULE_PATH}" -am

BUILT_JAR="$(find "${BUNDLE_DIR}/target" -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' | head -n1)"
if [[ -z "${BUILT_JAR}" ]]; then
    echo "error: no jar produced under ${BUNDLE_DIR}/target - check the build output above." >&2
    exit 1
fi

BUNDLE_SYMBOLIC_NAME="$(basename "${BUNDLE_DIR}")"
TARGET_PLUGIN="$(find "${INSTALL_DIR}/plugins" -maxdepth 1 -name "${BUNDLE_SYMBOLIC_NAME}_*.jar" | head -n1)"

if [[ -z "${TARGET_PLUGIN}" ]]; then
    echo "error: ${BUNDLE_SYMBOLIC_NAME} isn't an installed plugin under ${INSTALL_DIR}/plugins" >&2
    echo "       (it may be a test bundle, or install.sh needs a re-run)" >&2
    exit 1
fi

echo "==> Hot-patching ${TARGET_PLUGIN##*/}"
cp "${BUILT_JAR}" "${TARGET_PLUGIN}"

echo "==> Done. Launch with: eclipse-mieux -clean"
echo "    (-clean forces OSGi to notice the replaced jar; drop it on later normal launches)"

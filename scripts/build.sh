#!/usr/bin/env bash
# Builds a real, runnable Eclipse SDK from source, with THIS repo's changes
# baked in.
#
# This repo alone can't produce an IDE - it only builds bundles. The actual
# product is assembled by eclipse.platform.releng.aggregator, which combines
# this repo (as its "eclipse.platform" submodule) with jdt/pde/swt/ui/equinox.
# So this script:
#   1. clones the aggregator as a sibling checkout (first run only - this
#      pulls jdt/pde/swt/ui/equinox too, it's a big, slow clone)
#   2. replaces the aggregator's eclipse.platform submodule dir with a
#      symlink to THIS repo, so the build compiles your local edits instead
#      of a separate, unrelated clone
#   3. runs the Tycho build that materializes the product
#
# Usage: scripts/build.sh [--with-tests] [-- <extra mvn args>]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

WITH_TESTS=0
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-tests) WITH_TESTS=1; shift ;;
        --) shift; EXTRA_ARGS+=("$@"); break ;;
        *) EXTRA_ARGS+=("$1"); shift ;;
    esac
done

setup_env

if [[ ! -d "${AGGREGATOR_DIR}/.git" ]]; then
    echo "==> Cloning aggregator to ${AGGREGATOR_DIR}"
    echo "    (this also pulls jdt/pde/swt/ui/equinox as submodules - expect several GB and a while)"
    git clone --recurse-submodules "${AGGREGATOR_URL}" "${AGGREGATOR_DIR}"
else
    echo "==> Aggregator already present at ${AGGREGATOR_DIR}, leaving its other submodules as-is"
    echo "    (to refresh jdt/pde/swt/ui/equinox: git -C ${AGGREGATOR_DIR} submodule update --remote)"
fi

# Wire the aggregator's eclipse.platform submodule to THIS checkout. This
# intentionally breaks that directory's git-submodule-ness - that's fine,
# we never commit inside the aggregator, we only build there.
PLATFORM_LINK="${AGGREGATOR_DIR}/eclipse.platform"
if [[ -L "${PLATFORM_LINK}" && "$(readlink -f "${PLATFORM_LINK}")" == "${REPO_ROOT}" ]]; then
    : # already wired up to this repo
else
    if [[ -e "${PLATFORM_LINK}" || -L "${PLATFORM_LINK}" ]]; then
        echo "==> Replacing ${PLATFORM_LINK} with a symlink to ${REPO_ROOT}"
        git -C "${AGGREGATOR_DIR}" submodule deinit -f eclipse.platform >/dev/null 2>&1 || true
        rm -rf "${PLATFORM_LINK}"
    fi
    ln -s "${REPO_ROOT}" "${PLATFORM_LINK}"
fi

echo "==> JAVA_HOME=${JAVA_HOME}"
echo "==> MAVEN_OPTS=${MAVEN_OPTS}"
cd "${AGGREGATOR_DIR}"

# NOTE: we tried narrowing target-platform-configuration's <environments> to
# just linux/gtk/x86_64 to skip other platforms' downloads - don't do that,
# org.eclipse.equinox.executable's package-feature step needs ALL platforms'
# launcher fragments present in the resolved target platform regardless of
# which one we're packaging for. The earlier download timeouts were network
# congestion (12 cores x --threads 1C hammering download.eclipse.org at
# once), not a real unreachability - --threads below is tuned down instead.

# eclipse.platform's pom.xml resolves its parent via relativePath
# (../eclipse-platform-parent). That's correct for a normal git-submodule
# checkout, but our eclipse.platform is a symlink OUT of the aggregator tree,
# so Maven resolves relativePath against its real (canonical) location and
# gets the wrong answer. Maven falls back to the local repo when relativePath
# fails - so pre-install the prereq POMs there first, same as upstream CI's
# "deploy parent pom" stage does (for the same underlying reason).
echo "==> Pre-installing eclipse-platform-parent + prereqs to the local Maven repo"
mvn -f eclipse-platform-parent/pom.xml clean install -q
mvn -f eclipse.platform.releng.prereqs.sdk/pom.xml clean install -q

MVN_ARGS=(clean verify --threads 4)
if [[ "${WITH_TESTS}" -eq 0 ]]; then
    MVN_ARGS+=(-DskipTests)
fi

echo "==> Building in ${AGGREGATOR_DIR}"
echo "    (upstream quotes ~10-20 min without tests; first run / this machine may take longer)"
echo "==> mvn ${MVN_ARGS[*]} ${EXTRA_ARGS[*]:-}"

exec mvn "${MVN_ARGS[@]}" "${EXTRA_ARGS[@]}"

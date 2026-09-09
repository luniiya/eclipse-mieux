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

# Compiling our custom bundles (theme, vim, mcp) into the reactor is NOT
# enough to make them show up in the running IDE - Tycho's p2-director only
# ships what the *product definition* actually references, and sdk.product
# lives in this aggregator's eclipse.platform.releng submodule, not in our
# repo. Same disposable-checkout problem as the symlink above: patch it in
# every time, idempotently, rather than relying on a one-off manual edit
# that a fresh clone would silently drop.
#
# sdk.product is type="features" (useFeatures=true) - Tycho silently IGNORES
# a bare <plugins> list on a feature-based product ("The bundles specified
# in the product definition are ignored; verify the value of the 'type' or
# 'useFeatures' attribute" - learned that the hard way). So our bundles are
# wrapped in platform/org.eclipse.mieux.feature (feature.xml listing
# org.eclipse.mieux.theme + org.eclipse.mieux.vim) and it's THAT feature id
# that goes in <features> here, not the bundles directly.
SDK_PRODUCT="${AGGREGATOR_DIR}/products/eclipse-sdk/sdk.product"
if [[ -f "${SDK_PRODUCT}" ]] && ! grep -q "org.eclipse.mieux.feature" "${SDK_PRODUCT}"; then
    echo "==> Wiring org.eclipse.mieux.feature into ${SDK_PRODUCT}"
    sed -i 's#<feature id="org.eclipse.terminal.feature" installMode="root"/>#<feature id="org.eclipse.terminal.feature" installMode="root"/>\n      <feature id="org.eclipse.mieux.feature" installMode="root"/>#' "${SDK_PRODUCT}"
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

MVN_BASE_ARGS=(clean verify)
if [[ "${WITH_TESTS}" -eq 0 ]]; then
    MVN_BASE_ARGS+=(-DskipTests)
fi

echo "==> Building in ${AGGREGATOR_DIR}"
echo "    (upstream quotes ~10-20 min without tests; first run / this machine may take longer)"

# Tycho's parallel reactor build (--threads N) has a known flaky race: one
# thread finishes packaging a bundle's jar just as another thread (e.g.
# validating a downstream test-bundle's classpath) opens that same jar -
# and can catch it mid-write, reading it as an empty/truncated zip. It's a
# build-tool scheduling bug (upstream Tycho, not our code, not javac), and
# it doesn't reproduce reliably - so retry with less parallelism only when
# we recognize that exact failure signature. A genuine compile/test failure
# gets reported immediately instead, not retried into obscurity.
BUILD_LOG="$(mktemp)"
trap 'rm -f "${BUILD_LOG}"' EXIT

is_known_parallel_race() {
    grep -q "zip file is empty" "${BUILD_LOG}"
}

for threads in 8 4 2 1; do
    echo "==> mvn ${MVN_BASE_ARGS[*]} --threads ${threads} ${EXTRA_ARGS[*]:-}"
    if mvn "${MVN_BASE_ARGS[@]}" --threads "${threads}" "${EXTRA_ARGS[@]}" 2>&1 | tee "${BUILD_LOG}"; then
        exit 0
    fi
    if ! is_known_parallel_race; then
        echo "==> Build failed - doesn't look like the known Tycho parallel-build jar race, not retrying (see output above)." >&2
        exit 1
    fi
    echo "==> Hit Tycho's known parallel-build jar race (empty zip mid-write) at --threads ${threads} - retrying with less parallelism..." >&2
done

echo "==> Still hitting the race even single-threaded - that's unexpected, see output above." >&2
exit 1

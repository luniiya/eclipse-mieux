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
# Usage: scripts/build.sh [--with-tests] [--all-platforms] [-- <extra mvn args>]
#
# By default, the products/ build (eclipse-sdk et al.) only materializes and
# archives THIS host's platform (linux/gtk/x86_64) - not all 8 supported
# platforms. For local dev/testing that's the only one you can even run, so
# building+signing+archiving the other 7 every time is pure waste. Pass
# --all-platforms to restore the full matrix (what CI should use when it
# actually does a product build - see the products/pom.xml patch below).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

WITH_TESTS=0
ALL_PLATFORMS=0
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --with-tests) WITH_TESTS=1; shift ;;
        --all-platforms) ALL_PLATFORMS=1; shift ;;
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

# Build Vrapper as its own Tycho reactor. It is a GPLv3 project and remains a
# separate git submodule; none of its source is merged into this reactor.
VRAPPER_SOURCE="${REPO_ROOT}/vim/vrapper"
VRAPPER_REPOSITORY="${VRAPPER_SOURCE}/target/repository"
if [[ ! -f "${VRAPPER_SOURCE}/.git" && ! -d "${VRAPPER_SOURCE}/.git" ]]; then
    echo "error: Vrapper submodule is not initialized at ${VRAPPER_SOURCE}" >&2
    echo "       Run: git submodule update --init vim/vrapper" >&2
    exit 1
fi

VRAPPER_COMMIT="$(git -C "${VRAPPER_SOURCE}" rev-parse HEAD)"
VRAPPER_STAMP="${VRAPPER_REPOSITORY}/.eclipse-mieux-vrapper-commit"
if [[ -f "${VRAPPER_STAMP}" ]] && [[ "$(<"${VRAPPER_STAMP}")" == "${VRAPPER_COMMIT}" ]] && [[ -f "${VRAPPER_REPOSITORY}/LICENSE.md" ]]; then
    echo "==> Vrapper p2 repository already built at ${VRAPPER_COMMIT}"
else
    VRAPPER_BUILD_DIR="$(mktemp -d)"
    trap 'rm -rf "${VRAPPER_BUILD_DIR}"' EXIT
    echo "==> Building Vrapper ${VRAPPER_COMMIT} in its independent reactor"
    git -C "${VRAPPER_SOURCE}" archive --format=tar HEAD | tar -xf - -C "${VRAPPER_BUILD_DIR}"
    python3 "${SCRIPT_DIR}/prepare-vrapper-build.py" "${VRAPPER_BUILD_DIR}"
    VRAPPER_JAVA_HOME="${VRAPPER_JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
    if [[ ! -x "${VRAPPER_JAVA_HOME}/bin/java" ]]; then
        VRAPPER_JAVA_HOME="${JAVA_HOME}"
    fi
    (
        cd "${VRAPPER_BUILD_DIR}"
        export JAVA_HOME="${VRAPPER_JAVA_HOME}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
        mvn clean verify -DskipTests -Dtycho.test.skip=true \
            -pl plugins/net.sourceforge.vrapper.core,plugins/net.sourceforge.vrapper.eclipse,features/net.sourceforge.vrapper.feature,releng/net.sourceforge.vrapper.releng.update-site \
            -am
    )
    rm -rf "${VRAPPER_REPOSITORY}"
    mkdir -p "${VRAPPER_REPOSITORY}"
    cp -a "${VRAPPER_BUILD_DIR}/releng/net.sourceforge.vrapper.releng.update-site/target/repository/." "${VRAPPER_REPOSITORY}/"
    cp "${VRAPPER_SOURCE}/LICENSE.md" "${VRAPPER_REPOSITORY}/LICENSE.md"
    printf '%s\n' "${VRAPPER_COMMIT}" > "${VRAPPER_STAMP}"
    rm -rf "${VRAPPER_BUILD_DIR}"
    trap - EXIT
fi

# sdk.product's repository list is consumed by the p2 director, but it is not
# a source for the product module's Tycho target platform. Add the generated
# Vrapper feature to the aggregator target definition as well, using the exact
# version emitted by Vrapper's p2 metadata. The target file lives in the
# disposable sibling aggregator and is patched idempotently on every build.
VRAPPER_FEATURE_VERSION="$(xz -dc "${VRAPPER_REPOSITORY}/content.xml.xz" \
    | sed -n "s/.*<unit id='net\.sourceforge\.vrapper\.feature\.feature\.group' version='\([^']*\)'.*/\1/p" \
    | head -n 1)"
if [[ -z "${VRAPPER_FEATURE_VERSION}" ]]; then
    echo "error: could not determine Vrapper feature version from ${VRAPPER_REPOSITORY}" >&2
    exit 1
fi
VRAPPER_TARGET="${AGGREGATOR_DIR}/eclipse.platform.releng.prereqs.sdk/eclipse-sdk-prereqs.target"
if [[ ! -f "${VRAPPER_TARGET}" ]]; then
    echo "error: expected SDK target definition is missing: ${VRAPPER_TARGET}" >&2
    exit 1
fi
python3 "${SCRIPT_DIR}/add-vrapper-target-location.py" \
    "${VRAPPER_TARGET}" "${VRAPPER_REPOSITORY}" "${VRAPPER_FEATURE_VERSION}"

# Compiling our custom bundles (theme, mcp) into the reactor is NOT
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
# org.eclipse.mieux.theme) and it's THAT feature id that goes in <features>
# here, not the bundles directly. Vrapper is supplied by the local p2
# repository built above, so its feature is listed separately below.
SDK_PRODUCT="${AGGREGATOR_DIR}/products/eclipse-sdk/sdk.product"
if [[ -f "${SDK_PRODUCT}" ]]; then
    if ! grep -q "org.eclipse.mieux.feature" "${SDK_PRODUCT}"; then
        echo "==> Wiring org.eclipse.mieux.feature into ${SDK_PRODUCT}"
        sed -i 's#<feature id="org.eclipse.terminal.feature" installMode="root"/>#<feature id="org.eclipse.terminal.feature" installMode="root"/>\n      <feature id="org.eclipse.mieux.feature" installMode="root"/>#' "${SDK_PRODUCT}"
    fi
    if ! grep -q "net.sourceforge.vrapper.feature" "${SDK_PRODUCT}"; then
        echo "==> Wiring Vrapper into ${SDK_PRODUCT}"
        sed -i 's#<feature id="org.eclipse.mieux.feature" installMode="root"/>#<feature id="org.eclipse.mieux.feature" installMode="root"/>\n      <feature id="net.sourceforge.vrapper.feature" installMode="root"/>#' "${SDK_PRODUCT}"
    fi
    if ! grep -q "Eclipse-mieux Vrapper" "${SDK_PRODUCT}"; then
        echo "==> Adding the local Vrapper p2 repository to ${SDK_PRODUCT}"
        sed -i "s#   </repositories>#      <repository location=\"file://${VRAPPER_REPOSITORY}\" name=\"Eclipse-mieux Vrapper\" enabled=\"true\" />\n   </repositories>#" "${SDK_PRODUCT}"
    fi
    if ! grep -q 'plugin id="net.sourceforge.vrapper.eclipse"' "${SDK_PRODUCT}"; then
        echo "==> Enabling Vrapper startup in ${SDK_PRODUCT}"
        sed -i 's#      <plugin id="org.eclipse.core.runtime" autoStart="true" startLevel="4" />#      <plugin id="org.eclipse.core.runtime" autoStart="true" startLevel="4" />\n      <plugin id="net.sourceforge.vrapper.eclipse" autoStart="true" startLevel="4" />#' "${SDK_PRODUCT}"
    fi
fi

# Scope products/ (eclipse-platform, eclipse-sdk, equinox-launcher,
# equinox-starterkit) to build/materialize/archive THIS host's platform only
# by default, instead of all 8 environments target-platform-configuration
# lists in eclipse-platform-parent. That parent list is inherited by every
# reactor module including org.eclipse.equinox.executable, which genuinely
# needs ALL platforms' launcher fragments resolvable in ITS OWN target
# platform regardless of what's being packaged - narrowing it there breaks
# package-feature (see the note above, learned that the hard way already).
# products/pom.xml is NOT a parent of equinox.executable though - only of
# the product modules that actually materialize/archive per-environment - so
# overriding target-platform-configuration's <environments> there (with
# combine.self="override" so it fully replaces rather than appends to the
# inherited list) narrows exactly the wasteful part without touching
# equinox.executable's own resolution at all.
#
# --all-platforms (adds -Pfull-platform-matrix) restores the full 8-platform
# matrix for whenever a real release/CI build needs it.
PRODUCTS_POM="${AGGREGATOR_DIR}/products/pom.xml"
if [[ -f "${PRODUCTS_POM}" ]] && ! grep -q "full-platform-matrix" "${PRODUCTS_POM}"; then
    echo "==> Scoping ${PRODUCTS_POM} to build the host platform only by default (see full-platform-matrix profile)"
    python3 "${SCRIPT_DIR}/patch-products-pom-environments.py" "${PRODUCTS_POM}"
fi

if [[ "${ALL_PLATFORMS}" -eq 1 ]]; then
    EXTRA_ARGS+=(-Pfull-platform-matrix)
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
    grep -q "zip file is empty" "${BUILD_LOG}" || \
        grep -Eq "org\.eclipse\.sdk\.feature\.group.*could not be found" "${BUILD_LOG}" || \
        grep -Eq "local-artifacts\.properties.*is missing|Unexpected build result of MavenProject" "${BUILD_LOG}"
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

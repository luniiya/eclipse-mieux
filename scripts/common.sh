#!/usr/bin/env bash
# Shared setup for the Eclipse Platform build/install scripts.
# Pins JDK 25 and uses the system Maven (aggregator requires >= 3.9.12).
#
# Meant to be sourced, not executed directly.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# This repo (eclipse.platform) is only a component repo - it builds bundles,
# not a runnable IDE. A real Eclipse SDK is assembled by the separate
# eclipse.platform.releng.aggregator project, which stitches this repo
# together with jdt/pde/swt/ui/equinox. We keep it as a sibling checkout.
AGGREGATOR_DIR="${AGGREGATOR_DIR:-$(cd "${REPO_ROOT}/.." && pwd)/eclipse-platform-aggregator}"
AGGREGATOR_URL="https://github.com/eclipse-platform/eclipse.platform.releng.aggregator.git"

JAVA25_HOME="/usr/lib/jvm/java-25-openjdk"

# True if $1 is a JAVA_HOME for a JDK 25+.
java_home_is_25_plus() {
    local jh="$1" ver
    [[ -x "${jh}/bin/java" ]] || return 1
    ver="$("${jh}/bin/java" -version 2>&1 | head -n1 | grep -oE '"[0-9]+' | tr -d '"')"
    [[ -n "${ver}" ]] && (( ver >= 25 ))
}

setup_java() {
    if [[ -n "${JAVA_HOME:-}" ]] && java_home_is_25_plus "${JAVA_HOME}"; then
        : # already set up for JDK 25+ (e.g. CI's actions/setup-java) - use as-is
    elif java_home_is_25_plus "${JAVA25_HOME}"; then
        export JAVA_HOME="${JAVA25_HOME}"
    else
        echo "error: no JDK 25+ found (checked \$JAVA_HOME and ${JAVA25_HOME}). The aggregator build requires JDK 25+." >&2
        echo "       Install it with: sudo pacman -S jdk25-openjdk (or export JAVA_HOME)" >&2
        exit 1
    fi
    export PATH="${JAVA_HOME}/bin:${PATH}"
}

check_maven() {
    if ! command -v mvn >/dev/null 2>&1; then
        echo "error: system 'mvn' not found. Install Apache Maven >= 3.9.12." >&2
        echo "       e.g.: sudo pacman -S maven" >&2
        exit 1
    fi
}

# Sets up JAVA_HOME, PATH and MAVEN_OPTS for the build.
setup_env() {
    setup_java
    check_maven
    export MAVEN_OPTS="${MAVEN_OPTS:--Xmx4g}"
}

#!/usr/bin/env bash
# Builds (unless --no-build) and installs the Eclipse SDK into ~/.local/opt,
# then symlinks the launcher into ~/.local/bin so `eclipse-mieux` is on PATH.
#
# This is the "rebuild and reinstall" entry point: just re-run this script
# any time the source changes.
#
# Usage: scripts/install.sh [--no-build] [--with-tests]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

DO_BUILD=1
BUILD_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --no-build) DO_BUILD=0 ;;
        *) BUILD_ARGS+=("$arg") ;;
    esac
done

if [[ "${DO_BUILD}" -eq 1 ]]; then
    "${SCRIPT_DIR}/build.sh" "${BUILD_ARGS[@]}"
fi

INSTALL_DIR="${HOME}/.local/opt/eclipse-mieux"
BIN_LINK="${HOME}/.local/bin/eclipse-mieux"

# Find the materialized product's launcher binary rather than assuming the
# exact os/ws/arch directory layout tycho-p2-director produces. We build the
# full "eclipse-sdk" product (JDT/PDE included), not the bare "eclipse-platform".
LAUNCHER="$(find "${AGGREGATOR_DIR}/products/eclipse-sdk/target/products" \
    -type f -name eclipse -perm -u+x 2>/dev/null | head -n1)"

if [[ -z "${LAUNCHER}" ]]; then
    echo "error: could not find a built 'eclipse' launcher under" >&2
    echo "       ${AGGREGATOR_DIR}/products/eclipse-sdk/target/products" >&2
    echo "       (did the build actually finish materializing the product?)" >&2
    exit 1
fi
SRC_DIR="$(dirname "${LAUNCHER}")"

echo "==> Installing $(basename "${SRC_DIR}") to ${INSTALL_DIR}"
rm -rf "${INSTALL_DIR}"
mkdir -p "$(dirname "${INSTALL_DIR}")"
cp -a "${SRC_DIR}" "${INSTALL_DIR}"

mkdir -p "$(dirname "${BIN_LINK}")"
ln -sf "${INSTALL_DIR}/eclipse" "${BIN_LINK}"

# Materialized Eclipse products don't always ship a .xpm at the top level;
# fall back to an icon straight from this repo's source tree if not.
ICON="${INSTALL_DIR}/icon.xpm"
[[ -f "${ICON}" ]] || ICON="${REPO_ROOT}/platform/org.eclipse.sdk/eclipse256.png"

DESKTOP_DIR="${HOME}/.local/share/applications"
DESKTOP_FILE="${DESKTOP_DIR}/eclipse-mieux.desktop"
mkdir -p "${DESKTOP_DIR}"
cat > "${DESKTOP_FILE}" <<EOF
[Desktop Entry]
Type=Application
Name=Eclipse
Comment=Eclipse Platform SDK (Eclipse-mieux fork)
Exec=${BIN_LINK}
Icon=${ICON}
Terminal=false
StartupNotify=true
Categories=Development;IDE;
Keywords=Java;IDE;development;
EOF
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${DESKTOP_DIR}" >/dev/null 2>&1 || true
fi

echo "==> Installed."
echo "==> Run with: eclipse-mieux"
echo "==> App launcher entry: ${DESKTOP_FILE}"
if ! command -v eclipse-mieux >/dev/null 2>&1; then
    echo "    Note: ${HOME}/.local/bin isn't on PATH in this shell session."
    echo "    Open a new terminal, or add it to your shell rc."
fi

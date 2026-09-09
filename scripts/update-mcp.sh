#!/usr/bin/env bash
# Fast development loop for MCP-only changes.
# Builds the two runtime MCP bundles and replaces them in the already-built
# SDK product. The IDE must be restarted after this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

INSTALL_DIR="${HOME}/.local/opt/eclipse-mieux"
BUNDLES_INFO="${INSTALL_DIR}/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

setup_env

if [[ ! -d "${AGGREGATOR_DIR}/.git" ]]; then
	echo "error: aggregator checkout is missing: ${AGGREGATOR_DIR}" >&2
	echo "       Run scripts/install.sh once to create the full SDK build." >&2
	exit 1
fi
if [[ ! -x "${INSTALL_DIR}/eclipse" || ! -f "${BUNDLES_INFO}" ]]; then
	echo "error: installed Eclipse Mieux product is missing at ${INSTALL_DIR}" >&2
	echo "       Run scripts/install.sh once before using this fast path." >&2
	exit 1
fi

PLATFORM_LINK="${AGGREGATOR_DIR}/eclipse.platform"
if [[ ! -L "${PLATFORM_LINK}" || "$(readlink -f "${PLATFORM_LINK}")" != "${REPO_ROOT}" ]]; then
	echo "error: aggregator is not wired to this checkout at ${PLATFORM_LINK}" >&2
	echo "       Run scripts/install.sh once to wire the source tree." >&2
	exit 1
fi

# The parent is needed because this repository is symlinked into the
# aggregator and therefore cannot resolve its parent through relativePath.
mvn -f "${AGGREGATOR_DIR}/eclipse-platform-parent/pom.xml" install -q

echo "==> Building only the MCP runtime and UI bundles"
mvn -pl mcp/org.eclipse.mieux.mcp.server,mcp/org.eclipse.mieux.mcp.server.ui -am \
	verify -DskipTests -Dtycho.test.skip=true

update_bundle() {
	local symbolic_name="$1"
	local target_jar="$2"
	local version bundle_name installed_jar old_path

	version="$(unzip -p "${target_jar}" META-INF/MANIFEST.MF \
		| sed -n 's/^Bundle-Version: //p' | tr -d '\r' | head -n1)"
	if [[ -z "${version}" ]]; then
		echo "error: no Bundle-Version found in ${target_jar}" >&2
		exit 1
	fi
	bundle_name="${symbolic_name}_${version}.jar"
	installed_jar="${INSTALL_DIR}/plugins/${bundle_name}"

	old_path="$(awk -F, -v id="${symbolic_name}" '$1 == id { print $3; exit }' "${BUNDLES_INFO}")"
	if [[ -n "${old_path}" && "${INSTALL_DIR}/${old_path}" != "${installed_jar}" ]]; then
		rm -f "${INSTALL_DIR}/${old_path}"
	fi
	cp "${target_jar}" "${installed_jar}"

	awk -F, -v OFS=, -v id="${symbolic_name}" -v new_version="${version}" \
		-v new_path="plugins/${bundle_name}" \
		'$1 == id {$2 = new_version; $3 = new_path} {print}' \
		"${BUNDLES_INFO}" > "${BUNDLES_INFO}.tmp"
	mv "${BUNDLES_INFO}.tmp" "${BUNDLES_INFO}"
	echo "    ${symbolic_name} ${version}"
}

update_bundle org.eclipse.mieux.mcp.server \
	"${REPO_ROOT}/mcp/org.eclipse.mieux.mcp.server/target/org.eclipse.mieux.mcp.server-1.0.0-SNAPSHOT.jar"
update_bundle org.eclipse.mieux.mcp.server.ui \
	"${REPO_ROOT}/mcp/org.eclipse.mieux.mcp.server.ui/target/org.eclipse.mieux.mcp.server.ui-1.0.0-SNAPSHOT.jar"

echo "==> MCP bundles updated in ${INSTALL_DIR}"
echo "==> Restart Eclipse Mieux to load them"

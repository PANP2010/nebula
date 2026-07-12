#!/usr/bin/env bash
# =============================================================================
# print-coverage.sh — headless @NebulaRW coverage snapshot (P1.5.2).
#
# Emits the AnnotationCoverageCli JSON document to stdout. Anything the CLI
# writes to stderr (Gradle startup noise, JVM warnings, etc.) stays on stderr
# so the JSON output is parsable without filtering.
#
# Usage:
#   ./scripts/print-coverage.sh                  # default MC version (1.21.4)
#   ./scripts/print-coverage.sh 1.21.5           # explicit version
#   MC_VERSION=1.21.5 ./scripts/print-coverage.sh
#
# Exit codes:
#   0  snapshot printed
#   1  Gradle/Java invocation failed (or produced no output)
#   2  invalid arguments
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

mc_version="${1:-${MC_VERSION:-1.21.4}}"
if [[ ! "${mc_version}" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
    echo "[print-coverage] invalid minecraft_version: ${mc_version}" >&2
    exit 2
fi

# Use the Gradle wrapper if present, else fall back to a system `gradle`. CI
# always provides the wrapper; the fallback keeps the script usable locally
# when a developer has run `gradle wrapper` at least once.
if [[ -x "${PROJECT_ROOT}/gradlew" ]]; then
    GRADLE_CMD=("${PROJECT_ROOT}/gradlew")
else
    GRADLE_CMD=(gradle)
fi

# Build the runtime classpath the CLI needs; `--quiet` strips Gradle's progress
# bar so the only stdout content is the JSON document. -PmcVersion=... is
# forwarded to the printCoverage task so the snapshot records the right
# Minecraft version on its own without CLI arg-parsing divergence.
tmp_json="$(mktemp)"
trap 'rm -f "${tmp_json}"' EXIT

"${GRADLE_CMD[@]}" :nebula-folia-adapter:printCoverage \
    -PmcVersion="${mc_version}" \
    --quiet --console=plain --no-daemon \
    > "${tmp_json}" 2>/dev/null \
    || {
        echo "[print-coverage] Gradle invocation failed for ${mc_version}" >&2
        exit 1
    }

if [[ ! -s "${tmp_json}" ]]; then
    echo "[print-coverage] Gradle produced no output (empty snapshot)" >&2
    exit 1
fi

# Cheap shape check — the regression script keys on these fields. A failure
# here is more informative than letting the diff script choke downstream.
for field in minecraft_version total_bridge_methods annotated_methods coverage_ratio subsystems methods; do
    if ! jq -e ".${field}" "${tmp_json}" >/dev/null 2>&1; then
        echo "[print-coverage] snapshot missing required field: ${field}" >&2
        exit 1
    fi
done

cat "${tmp_json}"

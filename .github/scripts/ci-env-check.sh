#!/usr/bin/env bash
# =============================================================================
# .github/scripts/ci-env-check.sh
# -----------------------------------------------------------------------------
# Pre-flight check for the Nebula Regression CI job. Verifies:
#   1. The expected JDK is the active `java` on PATH (>= the requested major).
#   2. Free RAM is above a sane threshold for a Gradle build (default 2 GiB).
#   3. Free disk on the Gradle user home / workspace is above 4 GiB.
#   4. Required CLI tools (`git`, `unzip`, `curl`) are present.
#
# Usage:
#   bash .github/scripts/ci-env-check.sh <required-major-jdk> [min-mem-mib]
#
# Exit codes:
#   0  environment is acceptable
#   1  missing required tool
#   2  JDK version too low
#   3  free memory below threshold
#   4  free disk below threshold
#   5  JAVA_HOME / java binary missing
# =============================================================================

set -euo pipefail

EXPECTED_MAJOR="${1:-21}"
MIN_MEM_MIB="${2:-2048}"
MIN_DISK_MIB="${DISK_MIN_MIB:-4096}"

log()  { printf '[ci-env-check] %s\n' "$*"; }
fail() { printf '[ci-env-check] ERROR: %s\n' "$*" >&2; exit "$1"; }

# -----------------------------------------------------------------------------
# 1. Required CLI tools.
# -----------------------------------------------------------------------------
for tool in java javac git curl unzip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        fail 1 "required tool not on PATH: $tool"
    fi
done
log "tools: git $(command -v git) java $(command -v java)"

# -----------------------------------------------------------------------------
# 2. JDK major version. Compare against the requested EXPECTED_MAJOR.
# -----------------------------------------------------------------------------
if [ ! -x "$(command -v java)" ]; then
    fail 5 "no java binary on PATH"
fi

DETECTED_MAJOR="$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'[. "]' '/^[[:space:]]*java.specification.version[[:space:]]*=/ {print $4; exit}')"

# Some JDKs print "java.specification.version = 21", some print "21.0.1". Strip.
DETECTED_MAJOR="${DETECTED_MAJOR%%.*}"

if [ -z "$DETECTED_MAJOR" ]; then
    # Fallback: parse `java -version` first line, e.g. "openjdk version "21.0.5" 2025-10-21".
    DETECTED_MAJOR="$(java -version 2>&1 \
        | awk -F\" '/version[[:space:]]*"/ {print $2; exit}' \
        | awk -F. '{ if ($1 == 1) print $2; else print $1 }')"
fi

if [ -z "$DETECTED_MAJOR" ] || ! [[ "$DETECTED_MAJOR" =~ ^[0-9]+$ ]]; then
    fail 5 "could not determine active JDK major version"
fi

log "detected JDK $DETECTED_MAJOR (required >= $EXPECTED_MAJOR)"

if [ "$DETECTED_MAJOR" -lt "$EXPECTED_MAJOR" ]; then
    fail 2 "JDK $DETECTED_MAJOR < required $EXPECTED_MAJOR — check actions/setup-java distribution/version"
fi

# -----------------------------------------------------------------------------
# 3. Free RAM. Prefer /proc/meminfo on Linux, fall back to vm_stat on macOS
#    (the script shouldn't run there but is harmless).
# -----------------------------------------------------------------------------
free_mem_mib=0
if [ -r /proc/meminfo ]; then
    free_mem_mib=$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)
elif command -v vm_stat >/dev/null 2>&1; then
    # Approximate: pages free + inactive * 4096 / 1MiB.
    page_size=$(vm_stat | awk '/page size of/ {print $8}')
    free_mem_mib=$(vm_stat | awk -v ps="$page_size" '
        /Pages free/      {free=$3}
        /Pages inactive/  {inact=$3}
        END {
            gsub(/\./, "", free); gsub(/\./, "", inact);
            printf "%d", (free + inact) * ps / 1048576
        }')
fi

if [ "${free_mem_mib:-0}" -lt "$MIN_MEM_MIB" ]; then
    fail 3 "free memory ${free_mem_mib} MiB < required ${MIN_MEM_MIB} MiB — pick a larger runner"
fi
log "free memory: ${free_mem_mib} MiB (required >= ${MIN_MEM_MIB} MiB)"

# -----------------------------------------------------------------------------
# 4. Free disk on the workspace. Use the filesystem that holds PWD.
# -----------------------------------------------------------------------------
workspace_dir="${GITHUB_WORKSPACE:-$PWD}"
free_disk_mib=$(df -Pm "$workspace_dir" | awk 'NR==2 {print $4}')
free_disk_mib="${free_disk_mib:-0}"

if [ "$free_disk_mib" -lt "$MIN_DISK_MIB" ]; then
    fail 4 "free disk ${free_disk_mib} MiB on ${workspace_dir} < required ${MIN_DISK_MIB} MiB"
fi
log "free disk:   ${free_disk_mib} MiB on ${workspace_dir} (required >= ${MIN_DISK_MIB} MiB)"

# -----------------------------------------------------------------------------
# 5. Sanity: JAVA_HOME, if set, points at a directory containing bin/java.
# -----------------------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ]; then
    if [ ! -x "${JAVA_HOME}/bin/java" ]; then
        fail 5 "JAVA_HOME=${JAVA_HOME} does not contain bin/java"
    fi
    log "JAVA_HOME=${JAVA_HOME} OK"
else
    log "JAVA_HOME is not set (relying on PATH)"
fi

log "environment OK"
#!/usr/bin/env bash
# =============================================================================
# generate-coverage-baseline.sh — refresh the per-version baseline file.
#
# Run this on the main branch (locally or via the workflow_dispatch trigger in
# nebula-ci-annotation.yml with update_baseline=true) when a new Minecraft
# version drops and the bridge annotations have been re-verified against it.
#
# The script:
#   1. Captures a fresh snapshot via scripts/print-coverage.sh
#   2. Writes it to .github/annotation-baseline/<version>-coverage.json
#   3. (with --commit) commits and pushes the result
#
# Usage:
#   ./scripts/generate-coverage-baseline.sh 1.21.5
#   ./scripts/generate-coverage-baseline.sh 1.21.5 --commit
#   ./scripts/generate-coverage-baseline.sh          # default MC version
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

mc_version="${1:-1.21.4}"
do_commit="false"
if [[ "${2:-}" == "--commit" || "${1:-}" == "--commit" ]]; then
    do_commit="true"
    # If the first positional arg was --commit, fall back to the default MC.
    [[ "${1:-}" == "--commit" ]] && mc_version="1.21.4"
fi

if [[ ! "${mc_version}" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
    echo "[generate-baseline] invalid minecraft_version: ${mc_version}" >&2
    exit 2
fi

baseline_dir="${PROJECT_ROOT}/.github/annotation-baseline"
mkdir -p "${baseline_dir}"
target="${baseline_dir}/${mc_version}-coverage.json"

echo "[generate-baseline] capturing snapshot for ${mc_version}…" >&2
MC_VERSION="${mc_version}" \
    bash "${SCRIPT_DIR}/print-coverage.sh" "${mc_version}" \
    > "${target}.tmp" || {
        echo "[generate-baseline] snapshot failed; aborting" >&2
        rm -f "${target}.tmp"
        exit 1
    }

# Prettify the JSON for readable diffs in PRs.
if ! jq '.' "${target}.tmp" > "${target}"; then
    echo "[generate-baseline] jq prettify failed; writing compact JSON" >&2
    mv "${target}.tmp" "${target}"
fi
rm -f "${target}.tmp"

# Emit a short human-readable summary so the operator can eyeball the file.
jq -r '
    "  schema_version:      " + (.schema_version|tostring),
    "  minecraft_version:   " + .minecraft_version,
    "  total_bridge_methods: " + (.total_bridge_methods|tostring),
    "  annotated_methods:   " + (.annotated_methods|tostring),
    "  coverage_ratio:      " + (.coverage_ratio|tostring),
    "  subsystems:",
    (.subsystems[] | "    - " + .name + ": " + (.annotated|tostring) + "/" + (.total|tostring)
        + "  (" + (.coverage_ratio|tostring) + ")")
' "${target}"

echo "[generate-baseline] wrote ${target}" >&2

if [[ "${do_commit}" == "true" ]]; then
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        echo "[generate-baseline] --commit requested but cwd is not a git repo" >&2
        exit 1
    fi
    git add "${target}"
    if git diff --cached --quiet; then
        echo "[generate-baseline] no changes to commit" >&2
        exit 0
    fi
    git -c user.name="nebula-baseline-bot" \
        -c user.email="nebula-baseline-bot@users.noreply.github.com" \
        commit -m "ci(annotation-baseline): refresh ${mc_version} snapshot"
    echo "[generate-baseline] committed (push skipped — run 'git push' manually)" >&2
fi

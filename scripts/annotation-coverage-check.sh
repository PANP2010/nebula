#!/usr/bin/env bash
# =============================================================================
# annotation-coverage-check.sh — regression gate for @NebulaRW coverage.
#
# Compares the current coverage snapshot against the per-version baseline
# and reports:
#   - methods added   (new coverage opportunity, no action required)
#   - methods removed (regression risk: previously-annotated method is gone
#                       OR no longer annotated → re-annotate before merge)
#   - methods whose @NebulaRW shape changed (signature/contract drift)
#   - subsystem-level coverage drops
#   - overall coverage vs the 5%/year decay budget (patch-002 §C.5)
#
# Usage:
#   annotation-coverage-check.sh <baseline.json> <current.json>
#
# Exit codes:
#   0  no regression (or delta is non-regressive)
#   1  regression detected AND FAIL_ON_REGRESSION is "true"
#   2  bad arguments / malformed input
#
# Env:
#   FAIL_ON_REGRESSION   "true" turns a regression into a hard failure.
#                        Default is "false" so PR runs surface the delta
#                        as a comment without blocking merge.
#   DECAY_BUDGET_PCT     max allowed coverage drop in percent (default 5).
#   MC_VERSION          human-readable label echoed in the report header.
# =============================================================================
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <baseline.json> <current.json>" >&2
    exit 2
fi

baseline_path="$1"
current_path="$2"

if [[ ! -f "${baseline_path}" ]]; then
    echo "[coverage-check] baseline file not found: ${baseline_path}" >&2
    exit 2
fi
if [[ ! -f "${current_path}" ]]; then
    echo "[coverage-check] current snapshot not found: ${current_path}" >&2
    exit 2
fi

for f in "${baseline_path}" "${current_path}"; do
    for field in minecraft_version total_bridge_methods annotated_methods coverage_ratio subsystems methods; do
        if ! jq -e ".${field}" "${f}" >/dev/null 2>&1; then
            echo "[coverage-check] ${f} missing field: ${field}" >&2
            exit 2
        fi
    done
done

FAIL_ON_REGRESSION="${FAIL_ON_REGRESSION:-false}"
DECAY_BUDGET_PCT="${DECAY_BUDGET_PCT:-5}"
MC_VERSION="${MC_VERSION:-$(jq -r .minecraft_version "${current_path}")}"

# Headline numbers
base_total=$(jq -r .total_bridge_methods "${baseline_path}")
base_annot=$(jq -r .annotated_methods    "${baseline_path}")
base_ratio=$(jq -r .coverage_ratio       "${baseline_path}")
cur_total=$( jq -r .total_bridge_methods "${current_path}")
cur_annot=$( jq -r .annotated_methods    "${current_path}")
cur_ratio=$( jq -r .coverage_ratio       "${current_path}")

# ---------------------------------------------------------------------------
# 1. Per-method diff
#    Identity key = "<class-simple>.<method>". Same identifier the dashboard
#    prints and the same shape the patch-002 example uses.
# ---------------------------------------------------------------------------
mapfile -t added_methods     < <(jq -r '
    .methods | map({key: (.class + "." + .method), v: .}) | from_entries
    as $cur
    | input | .methods | map({key: (.class + "." + .method), v: .}) | from_entries
    as $base
    | $cur | to_entries | map(select(.key as $k | $base | has($k) | not))
    | .[].value | "  + " + .class + "." + .method
' "${current_path}" "${baseline_path}" 2>/dev/null || true)

mapfile -t removed_methods   < <(jq -r '
    .methods | map({key: (.class + "." + .method), v: .}) | from_entries
    as $cur
    | input | .methods | map({key: (.class + "." + .method), v: .}) | from_entries
    as $base
    | $base | to_entries | map(select(.key as $k | $cur | has($k) | not))
    | .[].value | "  - " + .class + "." + .method
' "${current_path}" "${baseline_path}" 2>/dev/null || true)

# Methods whose annotated-flag OR verifiedAt changed. Both indicate a
# contract drift — either the @NebulaRW got dropped, or its verifiedAt
# version was bumped without the annotation being re-confirmed.
mapfile -t changed_methods   < <(jq -r '
    def key: .class + "." + .method;
    .methods | map({(key): {a: .annotated, v: .verified_at}}) | add as $cur
    | input | .methods | map({(key): {a: .annotated, v: .verified_at}}) | add as $base
    | $cur | keys[] | . as $k | select($base[$k] != null) |
        select($cur[$k] != $base[$k]) |
        "  ~ " + $k + "  (annotated " + ($base[$k].a|tostring) + "→" + ($cur[$k].a|tostring) +
        ", verifiedAt " + ($base[$k].v // "null") + "→" + ($cur[$k].v // "null") + ")"
' "${current_path}" "${baseline_path}" 2>/dev/null || true)

# A removed annotation (no longer @NebulaRW) is the most dangerous form of
# drift. Surface those explicitly so reviewers can't miss them.
mapfile -t lost_annotations  < <(jq -r '
    def key: .class + "." + .method;
    .methods | map({(key): {a: .annotated, v: .verified_at}}) | add as $cur
    | input | .methods | map({(key): {a: .annotated, v: .verified_at}}) | add as $base
    | $base | to_entries[] |
        select(.value.a == true) |
        select($cur[.key].a == false) |
        "  ! " + .key + "  (lost @NebulaRW — re-annotate required)"
' "${current_path}" "${baseline_path}" 2>/dev/null || true)

# ---------------------------------------------------------------------------
# 2. Per-subsystem diff. Any subsystem whose ratio dropped by more than the
#    decay budget is flagged.
# ---------------------------------------------------------------------------
mapfile -t subsystem_deltas  < <(jq -r '
    .subsystems | map({(.name): .coverage_ratio}) | add as $cur
    | input | .subsystems | map({(.name): .coverage_ratio}) | add as $base
    | $base | keys[] | . as $name |
        ((($cur[$name] // 0) - ($base[$name] // 0)) * 100 | . * 100 | round / 100) as $delta |
        "  " + $name + ": " + (($base[$name] // 0)|tostring) + " → " + (($cur[$name] // 0)|tostring) +
        "  (Δ " + ($delta|tostring) + " pp)"
' "${current_path}" "${baseline_path}" 2>/dev/null || true)

# Subsystems whose delta is more negative than -DECAY_BUDGET_PCT percentage
# points → regression.
regressing_subsystems=$(jq -r '
    .subsystems | map({(.name): .coverage_ratio}) | add as $cur
    | input | .subsystems | map({(.name): .coverage_ratio}) | add as $base
    | $base | keys[] | . as $name |
        ((($cur[$name] // 0) - ($base[$name] // 0)) * 100) as $delta |
        select($delta < (-1 * env.DECAY_BUDGET_PCT | tonumber)) |
        $name
' --argjson DECAY_BUDGET_PCT "${DECAY_BUDGET_PCT}" "${current_path}" "${baseline_path}" 2>/dev/null || true)

# ---------------------------------------------------------------------------
# 3. Overall ratio vs decay budget. The patch's decay goal is <5%/year, so
#    the current ratio must stay within `DECAY_BUDGET_PCT` pp of the baseline.
# ---------------------------------------------------------------------------
ratio_delta_pp=$(awk -v b="${base_ratio}" -v c="${cur_ratio}" \
    'BEGIN{printf "%.2f", (c - b) * 100}')
overall_regression="false"
if awk -v d="${ratio_delta_pp}" -v b="${DECAY_BUDGET_PCT}" \
    'BEGIN{exit !(d < -b)}'; then
    overall_regression="true"
fi

# ---------------------------------------------------------------------------
# 4. Verdict.
# ---------------------------------------------------------------------------
regression_detected="false"
reasons=()
if [[ "${overall_regression}" == "true" ]]; then
    regression_detected="true"
    reasons+=("overall coverage Δ${ratio_delta_pp}pp exceeds -${DECAY_BUDGET_PCT}pp budget")
fi
if [[ -n "${regressing_subsystems}" ]]; then
    regression_detected="true"
    reasons+=("subsystem(s) regressed beyond budget: $(echo "${regressing_subsystems}" | tr '\n' ',' | sed 's/,$//')")
fi
if [[ ${#lost_annotations[@]} -gt 0 ]]; then
    regression_detected="true"
    reasons+=("lost @NebulaRW on ${#lost_annotations[@]} method(s)")
fi

# ---------------------------------------------------------------------------
# 5. Report.
# ---------------------------------------------------------------------------
{
    echo "## Annotation coverage delta — ${MC_VERSION}"
    echo
    echo "| metric              | baseline | current | Δ        |"
    echo "|---------------------|----------|---------|----------|"
    printf "| bridge methods      | %8s | %7s | %+8s |\n" "${base_total}" "${cur_total}" "$((cur_total - base_total))"
    printf "| annotated methods   | %8s | %7s | %+8s |\n" "${base_annot}" "${cur_annot}" "$((cur_annot - base_annot))"
    printf "| coverage ratio      | %8s | %7s | %+8spp |\n" "${base_ratio}" "${cur_ratio}" "${ratio_delta_pp}"
    echo
    echo "### Per-subsystem"
    if [[ ${#subsystem_deltas[@]} -gt 0 ]]; then
        printf '%s\n' "${subsystem_deltas[@]}"
    else
        echo "  (no subsystems)"
    fi
    echo
    echo "### Methods added"
    if [[ ${#added_methods[@]} -gt 0 ]]; then
        printf '%s\n' "${added_methods[@]}"
    else
        echo "  (none)"
    fi
    echo
    echo "### Methods removed"
    if [[ ${#removed_methods[@]} -gt 0 ]]; then
        printf '%s\n' "${removed_methods[@]}"
    else
        echo "  (none)"
    fi
    echo
    echo "### Methods with changed @NebulaRW shape"
    if [[ ${#changed_methods[@]} -gt 0 ]]; then
        printf '%s\n' "${changed_methods[@]}"
    else
        echo "  (none)"
    fi
    if [[ ${#lost_annotations[@]} -gt 0 ]]; then
        echo
        echo "### Lost annotations (must re-annotate)"
        printf '%s\n' "${lost_annotations[@]}"
    fi
    echo
    echo "### Verdict"
    if [[ "${regression_detected}" == "true" ]]; then
        echo "  ❌ REGRESSION"
        for r in "${reasons[@]}"; do
            echo "    - ${r}"
        done
    else
        echo "  ✅ no regression"
    fi
    echo
    echo "_decay_budget=${DECAY_BUDGET_PCT}pp; FAIL_ON_REGRESSION=${FAIL_ON_REGRESSION}_"
} | tee /dev/stderr   # tee so the report shows on both stdout AND the CI log

if [[ "${regression_detected}" == "true" && "${FAIL_ON_REGRESSION}" == "true" ]]; then
    exit 1
fi
exit 0

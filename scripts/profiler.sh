#!/bin/bash
# Phase -1 Profiling Automation Script
# Usage: ./scripts/profiler.sh <start|stop|snapshot|convert|top|help> <pid> [options]

set -e

PROF_DIR="${HOME}/nebula-profiling"
mkdir -p "$PROF_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FLAMEGRAPH_FILE="$PROF_DIR/flamegraph_${TIMESTAMP}.svg"
HOT_METHODS_FILE="$PROF_DIR/hot_methods_${TIMESTAMP}.txt"
SUMMARY_FILE="$PROF_DIR/summary_${TIMESTAMP}.txt"

# Detect OS for process check
is_linux() { [[ "$(uname)" == "Linux" ]]; }
is_macos() { [[ "$(uname)" == "Darwin" ]]; }

pid_exists() {
    local pid="$1"
    if is_linux && [[ -d "/proc/$pid" ]]; then return 0
    elif is_macos && kill -0 "$pid" 2>/dev/null; then return 0
    fi
    return 1
}

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_pid() {
    local pid="$1"
    if [[ -z "$pid" ]]; then
        log_error "PID is required"
        exit 1
    fi
    if ! pid_exists "$pid"; then
        log_error "Invalid PID: $pid"
        exit 1
    fi
}

case "$1" in
    start)
        PID="$2"
        check_pid "$PID"

        OPTS=""
        while [[ $# -gt 1 ]]; do
            case "$2" in
                -e) shift; OPTS+="-e $1 ";;
                -i) shift; OPTS+="-i $1 ";;
                -f) shift; OPTS+="-f $1 ";;
                *) log_warn "Unknown option: $2"; break;;
            esac
            shift
        done

        log_info "Starting CPU profiling for PID: $PID"
        log_info "Data will be saved to: $PROF_DIR"
        echo "./async-profiler.sh start $PID $OPTS"
        ;;

    stop)
        PID="$2"
        check_pid "$PID"
        log_info "Stopping profiler..."
        echo "./async-profiler.sh stop $PID"
        ;;

    snapshot)
        PID="$2"
        check_pid "$PID"

        SNAPSHOT_FILE="$PROF_DIR/snapshot_${TIMESTAMP}.bin"
        log_info "Taking snapshot..."
        echo "./async-profiler.sh snapshot $PID > $SNAPSHOT_FILE"
        ;;

    convert)
        PID="$2"
        check_pid "$PID"

        SNAPSHOT_FILE="$PROF_DIR/snapshot_${TIMESTAMP}.bin"

        log_info "Generating flame graph and reports..."
        echo "./async-profiler.sh snapshot $PID > $SNAPSHOT_FILE"
        echo "./async-profiler.sh convert $PID --svg $FLAMEGRAPH_FILE"
        echo "./async-profiler.sh top $PID -n 500 > $HOT_METHODS_FILE"
        echo "./async-profiler.sh summary $PID > $SUMMARY_FILE"
        ;;

    top)
        PID="$2"
        check_pid "$PID"

        echo "./async-profiler.sh top $PID -n 500 | tee $HOT_METHODS_FILE"
        ;;

    n)
        # Calculate N (methods for 80% CPU)
        if [[ -z "$2" ]]; then
            log_error "Usage: $0 n <hot_methods_file>"
            exit 1
        fi
        FILE="$2"
        if [[ ! -f "$FILE" ]]; then
            log_error "File not found: $FILE"
            exit 1
        fi

        log_info "Calculating N (methods for 80% CPU)..."
        N=$(awk 'NR>1 && $2 ~ /^[0-9.]+%/ {gsub(/%/,"",$2); sum+=$2; if(sum<=80) print NR; if(sum>80) exit}' "$FILE" | tail -1)
        echo "N = $N"
        ;;

    help)
        echo "Phase -1 Profiling Automation Script"
        echo ""
        echo "Usage: $0 <command> <pid> [options]"
        echo ""
        echo "Commands:"
        echo "  start              - Start CPU profiling"
        echo "  stop               - Stop profiling"
        echo "  snapshot           - Take a profiler snapshot"
        echo "  convert            - Generate flame graph and reports"
        echo "  top                - Show top methods"
        echo "  n <file>           - Calculate N (methods for 80% CPU)"
        echo "  help               - Show this help"
        echo ""
        echo "Examples:"
        echo "  $0 start 12345 -e cpu -i 1ms"
        echo "  $0 stop 12345"
        echo "  $0 convert 12345"
        echo "  $0 n hot_methods.txt"
        echo ""
        echo "Output location: $PROF_DIR"
        ;;

    *)
        log_error "Unknown command: $1"
        echo "Run '$0 help' for usage"
        exit 1
        ;;
esac

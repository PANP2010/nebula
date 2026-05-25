#!/bin/bash
# Folia Server Deployment Script for Nebula Phase -1
# Usage: ./deploy-folia.sh [version]

set -e

FOLIA_VERSION="${1:-1.21.4}"
INSTALL_DIR="${HOME}/nebula-folia-server"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check for Java 25
check_java() {
    log_info "Checking Java version..."

    # Try to find Java 25
    JAVA25_PATHS=(
        "/Library/Java/JavaVirtualMachines/jdk-25.jdk"
        "/opt/homebrew/opt/openjdk@25"
        "/usr/lib/jvm/java-25-*"
        "${HOME}/.jdks/jdk-25*"
    )

    for path in "${JAVA25_PATHS[@]}"; do
        if ls "$path" 2>/dev/null | grep -q "jdk"; then
            export JAVA_HOME="$(ls -d "$path"/Contents/Home 2>/dev/null || ls -d "$path"/*/Contents/Home 2>/dev/null || echo "$path")"
            if [[ -f "$JAVA_HOME/bin/java" ]]; then
                log_info "Found Java 25 at: $JAVA_HOME"
                return 0
            fi
        fi
    done

    log_warn "Java 25 not found."
    echo ""
    echo "Please install Java 25 manually:"
    echo "  Option 1: Download from https://adoptium.net/temurin/25/"
    echo "    - Download OpenJDK 25 LTS (macOS x64)"
    echo "    - Extract to /Library/Java/JavaVirtualMachines/jdk-25.jdk"
    echo ""
    echo "  Option 2: Use Homebrew (if available)"
    echo "    - brew install openjdk@25"
    echo ""
    echo "After installation, re-run this script."
    echo ""

    return 1
}

# Download Folia
download_folia() {
    local version="$1"
    local jar_file="$INSTALL_DIR/folia-${version}.jar"

    if [[ -f "$jar_file" ]]; then
        log_info "Folia already downloaded: $jar_file"
        echo "$jar_file"
        return 0
    fi

    log_info "Downloading Folia $version..."

    # Folia download URL (PaperMC)
    local download_url="https://api.papermc.io/v2/projects/folia/versions/${version}/builds/latest/downloads/folia-${version}-latest.jar"

    if curl -L -o "$jar_file" "$download_url" 2>/dev/null; then
        log_info "Downloaded: $jar_file"
        echo "$jar_file"
    else
        log_error "Failed to download Folia."
        echo ""
        echo "Please download manually:"
        echo "  1. Go to: https://papermc.io/downloads/folia"
        echo "  2. Download version $version"
        echo "  3. Place JAR in: $INSTALL_DIR/"
        echo ""
        return 1
    fi
}

# Create eula.txt
accept_eula() {
    echo "eula=true" > "$INSTALL_DIR/eula.txt"
    log_info "EULA accepted"
}

# Create server.properties
create_properties() {
    cat > "$INSTALL_DIR/server.properties" << 'EOF'
# Nebula Phase -1 Test Server Configuration
max-tick-time=60000
view-distance=10
simulation-distance=8
spawn-limits=40
chunk-gc.tick-load-avg=2
mob-spawn-range=4
nerf-spawner-mobs=false
player-idle-timeout=0
time-offset=0
force-gamemode=false
allow-nether=true
allow-flight=false
enable-query=false
enable-rcon=false
allow-resources=false
level-name=world
level-seed=
game-mode=survival
hardcore=false
white-list=false
spawn-protection=16
entity-broadcast-range-percentage=100
rcon.port=25575
server-port=25565
debug=false
server-ip=
spawn-npcs=true
spawn-animals=true
online-mode=true
allow-spawning=true
spawn-monsters=true
generate-structures=true
max-players=10
motd=Nebula Phase -1 Test Server
EOF
    log_info "Created server.properties"
}

# Create startup script
create_startup_script() {
    cat > "$INSTALL_DIR/start.sh" << 'STARTSCRIPT'
#!/bin/bash
# Nebula Folia Startup Script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Find Java 25
JAVA25_PATHS=(
    "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
    "${HOME}/.jdks/jdk-25*"
)

for path in "${JAVA25_PATHS[@]}"; do
    if [[ -f "$path/bin/java" ]]; then
        export JAVA_HOME="$path"
        break
    fi
done

if [[ -z "$JAVA_HOME" || ! -f "$JAVA_HOME/bin/java" ]]; then
    echo "ERROR: Java 25 not found"
    echo "Install from: https://adoptium.net/temurin/25/"
    exit 1
fi

echo "Using Java: $JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# Find Folia JAR
FOLIA_JAR=$(ls "$SCRIPT_DIR"/folia-*.jar 2>/dev/null | head -1)
if [[ -z "$FOLIA_JAR" ]]; then
    echo "ERROR: No Folia JAR found in $SCRIPT_DIR"
    echo "Download from: https://papermc.io/downloads/folia"
    exit 1
fi

# Nebula Agent JAR
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NEBULA_AGENT="$PROJECT_DIR/nebula-agent/build/libs/nebula-agent-0.1.0.jar"
if [[ -f "$NEBULA_AGENT" ]]; then
    AGENT_OPTS="-javaagent:$NEBULA_AGENT"
    echo "Attaching Nebula Agent: $NEBULA_AGENT"
fi

# JVM flags
JVM_OPTS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:MaxTenuringThreshold=1"

mkdir -p logs

echo "Starting Folia..."
exec "$JAVA_HOME/bin/java" $JVM_OPTS $AGENT_OPTS -jar "$FOLIA_JAR" --nogui
STARTSCRIPT

    chmod +x "$INSTALL_DIR/start.sh"
    log_info "Created startup script: $INSTALL_DIR/start.sh"
}

# Build Nebula Agent
build_nebula_agent() {
    local project_dir="$(cd "$INSTALL_DIR/../.." && pwd)"
    local agent_jar="$project_dir/nebula-agent/build/libs/nebula-agent-0.1.0.jar"

    if [[ -f "$agent_jar" ]]; then
        log_info "Nebula Agent already built: $agent_jar"
        return 0
    fi

    log_info "Building Nebula Agent..."
    cd "$project_dir"

    if ./gradlew :nebula-agent:build -x test 2>/dev/null; then
        log_info "Nebula Agent built successfully"
    else
        log_warn "Failed to build Nebula Agent. Will start without agent."
    fi
}

# Main
main() {
    echo "========================================"
    echo "  Nebula Folia Deployment Script"
    echo "========================================"
    echo ""

    log_info "Installing Folia $FOLIA_VERSION to: $INSTALL_DIR"
    mkdir -p "$INSTALL_DIR"

    # Check Java
    if ! check_java; then
        exit 1
    fi

    # Download Folia
    if ! download_folia "$FOLIA_VERSION"; then
        exit 1
    fi

    # Accept EULA
    accept_eula

    # Create config
    create_properties

    # Create startup script
    create_startup_script

    # Build agent
    build_nebula_agent

    echo ""
    echo "========================================"
    log_info "Deployment complete!"
    echo "========================================"
    echo ""
    echo "Next steps:"
    echo "  1. cd $INSTALL_DIR"
    echo "  2. ./start.sh"
    echo "  3. Wait for server to generate world"
    echo "  4. Press Ctrl+C to stop"
    echo ""
}

main "$@"

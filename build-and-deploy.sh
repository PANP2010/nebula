#!/bin/bash
# Nebula Build and Deploy Script
# Builds the plugin and agent, then deploys to test server

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Nebula Build & Deploy Script                            ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
echo

# Configuration
JAVA_21=/usr/lib/jvm/java-21-openjdk-amd64
JAVA_25=/usr/lib/jvm/java-25-openjdk-amd64
PROJECT_ROOT=/home/kuli/nebula
BUILD_OUTPUT=~/.gradle/nebula-server-build/nebula-server
TEST_SERVER=$PROJECT_ROOT/folia-test-server

# Step 1: Clean build
echo -e "${YELLOW}[1/6] Cleaning previous build...${NC}"
JAVA_HOME=$JAVA_21 ./gradlew clean --console=plain
echo -e "${GREEN}✓ Clean complete${NC}"
echo

# Step 2: Build all modules
echo -e "${YELLOW}[2/6] Building all modules...${NC}"
JAVA_HOME=$JAVA_21 ./gradlew build --console=plain
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Build successful${NC}"
else
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
echo

# Step 3: Build shadow jar (plugin)
echo -e "${YELLOW}[3/6] Building shadow jar (deployable plugin)...${NC}"
JAVA_HOME=$JAVA_21 ./gradlew :nebula-plugin:shadowJar --console=plain
if [ $? -eq 0 ]; then
    PLUGIN_JAR=$(find $BUILD_OUTPUT/nebula-plugin/libs/ -name "nebula-plugin-*-all.jar" | head -1)
    if [ -z "$PLUGIN_JAR" ]; then
        # Fallback: find any non-sources jar
        PLUGIN_JAR=$(find $BUILD_OUTPUT/nebula-plugin/libs/ -name "nebula-plugin-*.jar" ! -name "*-sources.jar" | head -1)
    fi
    PLUGIN_SIZE=$(du -h "$PLUGIN_JAR" | cut -f1)
    echo -e "${GREEN}✓ Shadow jar built: $PLUGIN_SIZE${NC}"
else
    echo -e "${RED}✗ Shadow jar build failed${NC}"
    exit 1
fi
echo

# Step 4: Build agent
echo -e "${YELLOW}[4/6] Building agent jar...${NC}"
JAVA_HOME=$JAVA_21 ./gradlew :nebula-agent:jar --console=plain
if [ $? -eq 0 ]; then
    AGENT_JAR=$(find $BUILD_OUTPUT/nebula-agent/libs/ -name "nebula-agent-*.jar" | head -1)
    AGENT_SIZE=$(du -h "$AGENT_JAR" | cut -f1)
    echo -e "${GREEN}✓ Agent jar built: $AGENT_SIZE${NC}"
else
    echo -e "${RED}✗ Agent jar build failed${NC}"
    exit 1
fi
echo

# Step 5: Create test server directories if needed
echo -e "${YELLOW}[5/6] Preparing test server directories...${NC}"
mkdir -p $TEST_SERVER/plugins
mkdir -p $TEST_SERVER/logs
echo -e "${GREEN}✓ Directories ready${NC}"
echo

# Step 6: Deploy to test server
echo -e "${YELLOW}[6/6] Deploying to test server...${NC}"
if [ -f "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" $TEST_SERVER/plugins/
    echo -e "${GREEN}✓ Plugin deployed: plugins/$(basename $PLUGIN_JAR)${NC}"
else
    echo -e "${RED}✗ Plugin jar not found${NC}"
    exit 1
fi

if [ -f "$AGENT_JAR" ]; then
    cp "$AGENT_JAR" $TEST_SERVER/nebula-agent.jar
    echo -e "${GREEN}✓ Agent deployed: nebula-agent.jar${NC}"
else
    echo -e "${RED}✗ Agent jar not found${NC}"
    exit 1
fi
echo

# Summary
echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Build Summary                                            ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
echo -e "Plugin: ${GREEN}$PLUGIN_SIZE${NC} → ${BLUE}$TEST_SERVER/plugins/${NC}"
echo -e "Agent:  ${GREEN}$AGENT_SIZE${NC} → ${BLUE}$TEST_SERVER/nebula-agent.jar${NC}"
echo
echo -e "${GREEN}✓ Build and deployment complete!${NC}"
echo
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Create start script: ./create-start-script.sh"
echo "2. Start server: cd $TEST_SERVER && ./start.sh"
echo "3. Watch logs: tail -f $TEST_SERVER/logs/latest.log"
echo "4. Test with RCON: rcon -H localhost -p 25576 -P nebulatest"

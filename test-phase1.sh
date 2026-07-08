#!/bin/bash
# Phase 1 Integration Testing Script
# Tests B1/B2/B3 fixes according to docs/INTEGRATION_TESTING_GUIDE.md

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

TEST_SERVER=/home/kuli/nebula/folia-test-server
LOG_FILE=$TEST_SERVER/logs/latest.log

echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║  Nebula Phase 1 Integration Testing                      ║${NC}"
echo -e "${BLUE}║  Testing B1/B2/B3 fixes on real Folia server             ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
echo

# Check if server is running
if pgrep -f "folia.*server.jar" > /dev/null; then
    echo -e "${GREEN}✓ Folia server is running${NC}"
else
    echo -e "${RED}✗ Server is not running${NC}"
    echo "Please start the server first:"
    echo "  cd $TEST_SERVER && ./start.sh"
    exit 1
fi

# Wait for server to fully start
echo -e "${YELLOW}Waiting for server to fully initialize...${NC}"
sleep 5

echo
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Test 1: Plugin Loads Successfully${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo

# Check for success indicators in log
echo -e "${YELLOW}Checking server logs for Nebula plugin initialization...${NC}"
echo

if grep -q "Nebula plugin enabling" $LOG_FILE; then
    echo -e "${GREEN}✓ Plugin enabled${NC}"
else
    echo -e "${RED}✗ Plugin not enabled${NC}"
    exit 1
fi

if grep -q "Folia runtime: true" $LOG_FILE; then
    echo -e "${GREEN}✓ Folia runtime detected${NC}"
else
    echo -e "${YELLOW}⚠ Folia runtime not detected (may be Paper/Spigot)${NC}"
fi

if grep -q "Retransformed.*redstone classes" $LOG_FILE; then
    echo -e "${GREEN}✓ Agent retransform succeeded${NC}"
else
    echo -e "${RED}✗ Agent retransform failed or not attempted${NC}"
fi

if grep -q "NeighborUpdateHooks sentinel verified: hooks are active" $LOG_FILE; then
    echo -e "${GREEN}✓ Hook sentinel verification passed${NC}"
else
    echo -e "${RED}✗ Hook sentinel verification failed${NC}"
    echo -e "${YELLOW}  → Agent hooks may not be active (B3 blocker)${NC}"
fi

if grep -q "Initial sync scan complete" $LOG_FILE; then
    COMPONENT_COUNT=$(grep "Initial sync scan complete" $LOG_FILE | tail -1 | grep -oP '\d+(?= redstone components)')
    echo -e "${GREEN}✓ Initial sync scan completed: $COMPONENT_COUNT components${NC}"
else
    echo -e "${YELLOW}⚠ Initial sync scan not found in logs${NC}"
fi

if grep -q "RedstoneTickHook lifecycle driver registered" $LOG_FILE; then
    echo -e "${GREEN}✓ Lifecycle driver registered (B1 fix)${NC}"
else
    echo -e "${RED}✗ Lifecycle driver not registered (B1 fix missing)${NC}"
fi

if grep -q "Registered RedstoneEventListener" $LOG_FILE; then
    echo -e "${GREEN}✓ RedstoneEventListener registered${NC}"
else
    echo -e "${YELLOW}⚠ RedstoneEventListener not registered${NC}"
fi

echo
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Test 2: RedstoneTickHook Lifecycle Active${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo

# Check if lifecycle methods are being called
if grep -q "beginTick called" $LOG_FILE 2>/dev/null; then
    echo -e "${GREEN}✓ beginTick() is being called${NC}"
else
    echo -e "${YELLOW}⚠ No beginTick() logs found (add diagnostic logging if needed)${NC}"
fi

if grep -q "endTick called" $LOG_FILE 2>/dev/null; then
    echo -e "${GREEN}✓ endTick() is being called${NC}"
else
    echo -e "${YELLOW}⚠ No endTick() logs found (add diagnostic logging if needed)${NC}"
fi

echo
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Test 4: DAG Execution Status${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo

# Check for DAG execution logs
if grep -q "DAG tick:" $LOG_FILE; then
    DAG_COUNT=$(grep -c "DAG tick:" $LOG_FILE)
    echo -e "${GREEN}✓ DAG execution detected! Found $DAG_COUNT tick(s)${NC}"
    echo
    echo -e "${YELLOW}Recent DAG ticks:${NC}"
    grep "DAG tick:" $LOG_FILE | tail -5
    echo
    echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SUCCESS! DAG is executing on real Folia server!${NC}"
    echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
else
    echo -e "${YELLOW}⚠ No DAG execution logs found yet${NC}"
    echo "  This is expected if no redstone has been placed yet."
    echo "  Try placing redstone with RCON:"
    echo "    rcon -H localhost -p 25576 -P nebulatest"
    echo "    > setblock 0 72 0 minecraft:redstone_wire"
fi

echo
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Summary${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo

# Count successes
PASS_COUNT=0
TOTAL_CHECKS=7

grep -q "Nebula plugin enabling" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "Folia runtime: true" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "Retransformed.*redstone classes" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "NeighborUpdateHooks sentinel verified" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "Initial sync scan complete" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "RedstoneTickHook lifecycle driver registered" $LOG_FILE && ((PASS_COUNT++)) || true
grep -q "Registered RedstoneEventListener" $LOG_FILE && ((PASS_COUNT++)) || true

echo -e "Checks passed: ${GREEN}$PASS_COUNT${NC}/${TOTAL_CHECKS}"
echo

if [ $PASS_COUNT -ge 5 ]; then
    echo -e "${GREEN}✓ Core initialization successful!${NC}"
    echo
    echo -e "${YELLOW}Next steps:${NC}"
    echo "1. Place redstone to test DAG execution:"
    echo "   rcon -H localhost -p 25576 -P nebulatest"
    echo "   > setblock 0 72 0 minecraft:lever"
    echo "   > setblock 1 72 0 minecraft:redstone_wire"
    echo
    echo "2. Watch for DAG execution:"
    echo "   tail -f $LOG_FILE | grep 'DAG tick'"
    echo
    echo "3. Run full integration tests:"
    echo "   See docs/INTEGRATION_TESTING_GUIDE.md"
else
    echo -e "${RED}✗ Some initialization checks failed${NC}"
    echo "Review the logs above and check:"
    echo "- Agent is properly loaded (-javaagent flag)"
    echo "- Java 25 is being used"
    echo "- Plugin is compatible with Folia version"
fi

echo
echo -e "${BLUE}Log file: ${NC}$LOG_FILE"
echo -e "${BLUE}Full logs: ${NC}tail -f $LOG_FILE"

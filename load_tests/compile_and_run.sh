#!/bin/bash

# Simple test runner for LoadTestSuite without Maven
# Uses Java 21's built-in capabilities

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}==========================================================${NC}"
echo -e "${BLUE}HTTP Server Load Testing Suite${NC}"
echo -e "${BLUE}==========================================================${NC}"
echo ""

# Check Java version
echo "Checking Java environment..."
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[^"]*')
echo "Java version: $JAVA_VERSION"

if [[ ! $JAVA_VERSION =~ ^21 ]]; then
    echo -e "${RED}Error: Java 21+ required (found: $JAVA_VERSION)${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Java 21 detected${NC}"
echo ""

# Check for test dependencies
echo "Checking test dependencies..."

# Look for junit in jar files
if find "$PROJECT_DIR" -name "*.jar" -path "*junit*" 2>/dev/null | grep -q .; then
    echo -e "${GREEN}✓ JUnit found${NC}"
else
    echo -e "${RED}Warning: JUnit dependencies not found${NC}"
    echo "Run 'mvn dependency:copy-dependencies' first if available"
fi

echo ""
echo "LoadTestSuite can be run in several ways:"
echo ""
echo "1. With Maven (if installed):"
echo "   mvn clean test -Dtest=LoadTestSuite"
echo ""
echo "2. In your IDE (IntelliJ IDEA, Eclipse, VS Code):"
echo "   Right-click on LoadTestSuite.java -> Run Tests"
echo ""
echo "3. Check the README for detailed instructions:"
echo "   cat $SCRIPT_DIR/README.md"
echo ""
echo "Key test scenarios:"
echo "  • Concurrent connections (1K, 5K, 10K)"
echo "  • Sustained throughput (100 clients, 30+ seconds)"
echo "  • Cache performance (95%+ hit rate)"
echo "  • Mixed workload (small, medium files)"
echo "  • Spike traffic handling"
echo "  • Keep-alive efficiency"
echo ""

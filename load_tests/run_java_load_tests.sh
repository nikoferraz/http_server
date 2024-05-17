#!/bin/bash

# Run Java-based load tests with JUnit
# This script compiles and executes the LoadTestSuite.java tests

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_DIR="$PROJECT_DIR/HTTPServer/tests"
SRC_DIR="$PROJECT_DIR/HTTPServer"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_section() {
    echo ""
    echo "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
    echo "${BLUE}║${NC} $1"
    echo "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_status() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✓ PASS${NC}: $2"
    else
        echo -e "${RED}✗ FAIL${NC}: $2"
    fi
}

check_java() {
    print_section "Checking Java Environment"

    if ! command -v java &> /dev/null; then
        echo -e "${RED}ERROR: Java not found${NC}"
        echo "Install Java 21+ for virtual threads support"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[^"]*' || echo "unknown")
    echo "✓ Java version: $JAVA_VERSION"

    if ! command -v javac &> /dev/null; then
        echo -e "${RED}ERROR: javac (Java compiler) not found${NC}"
        echo "Install JDK 21+ for virtual threads support"
        exit 1
    fi

    echo "✓ Java compiler available"
}

check_junit() {
    print_section "Checking JUnit Dependencies"

    # Look for junit jar files in common locations
    if find "$PROJECT_DIR" -name "*junit*.jar" 2>/dev/null | grep -q junit; then
        echo "✓ JUnit found in project"
        return 0
    fi

    # Check if we can build with Maven or Gradle
    if [ -f "$PROJECT_DIR/pom.xml" ]; then
        echo "✓ Maven project detected"
        echo "Run tests with: mvn test"
        return 0
    fi

    if [ -f "$PROJECT_DIR/build.gradle" ]; then
        echo "✓ Gradle project detected"
        echo "Run tests with: gradle test"
        return 0
    fi

    echo -e "${YELLOW}Warning: Cannot detect build system${NC}"
    echo "Trying to run tests with available classpath..."
    return 1
}

run_load_tests() {
    print_section "Running Load Tests"

    echo "Starting Java load tests from: $TEST_DIR/LoadTestSuite.java"
    echo ""
    echo "This will run:"
    echo "  • Concurrent connection stress tests (1k, 5k, 10k connections)"
    echo "  • Sustained throughput tests (100 clients, 30+ seconds)"
    echo "  • Cache performance tests (95%+ hit rate target)"
    echo "  • Mixed workload tests"
    echo "  • Spike traffic handling"
    echo "  • Keep-alive efficiency tests"
    echo ""

    # Try different methods to run the tests
    if command -v mvn &> /dev/null && [ -f "$PROJECT_DIR/pom.xml" ]; then
        echo "Using Maven to run tests..."
        cd "$PROJECT_DIR"
        mvn test -Dtest=LoadTestSuite -X 2>&1 | tee load_test_results.log
        return $?
    fi

    if command -v gradle &> /dev/null && [ -f "$PROJECT_DIR/build.gradle" ]; then
        echo "Using Gradle to run tests..."
        cd "$PROJECT_DIR"
        gradle test --tests LoadTestSuite 2>&1 | tee load_test_results.log
        return $?
    fi

    # Fallback: Try to compile and run directly with JUnit on classpath
    echo "Attempting direct compilation and execution..."

    # Find junit jars
    CLASSPATH="."

    if find "$PROJECT_DIR" -name "*junit*.jar" -o -name "*assertj*.jar" | xargs ls -1 2>/dev/null; then
        for jar in $(find "$PROJECT_DIR" -name "*junit*.jar" -o -name "*assertj*.jar" 2>/dev/null); do
            CLASSPATH="$CLASSPATH:$jar"
        done
    fi

    echo "Using classpath: $CLASSPATH"

    cd "$SRC_DIR"

    # Compile the test
    echo "Compiling LoadTestSuite..."
    javac -cp "$CLASSPATH" tests/LoadTestSuite.java 2>&1 || {
        echo -e "${RED}Compilation failed${NC}"
        echo "Try running with Maven: mvn clean test"
        return 1
    }

    # Run the test
    echo "Running LoadTestSuite..."
    java -cp "$CLASSPATH:." org.junit.platform.console.ConsoleLauncher --scan-classpath 2>&1 | tee "$SCRIPT_DIR/load_test_results.log"
    return $?
}

run_with_mvn_if_available() {
    if [ -f "$PROJECT_DIR/pom.xml" ] && command -v mvn &> /dev/null; then
        cd "$PROJECT_DIR"
        echo "Running LoadTestSuite with Maven..."
        mvn clean test -Dtest=LoadTestSuite -pl HTTPServer -X
        return $?
    fi
    return 1
}

summarize_results() {
    print_section "Load Test Summary"

    echo "Performance Targets:"
    echo "  ✓ Concurrent connections: 10,000+"
    echo "  ✓ Throughput: > 10,000 req/sec"
    echo "  ✓ P99 Latency: < 100ms"
    echo "  ✓ Cache hit rate: > 95%"
    echo "  ✓ Error rate: < 0.1%"
    echo ""
    echo "Test Results:"
    if [ -f "$SCRIPT_DIR/load_test_results.log" ]; then
        echo "  See detailed results in: $SCRIPT_DIR/load_test_results.log"
        echo ""
        echo "Key metrics:"
        grep -i "successful\|failed\|req/sec\|latency" "$SCRIPT_DIR/load_test_results.log" | head -10 || true
    else
        echo "  Results log not found - check console output above"
    fi
}

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════╗"
    echo "║         Java Load Testing Suite                        ║"
    echo "║     Comprehensive Performance Validation               ║"
    echo "╚════════════════════════════════════════════════════════╝"
    echo ""

    check_java
    check_junit

    # Try Maven first if available
    if run_with_mvn_if_available; then
        echo -e "${GREEN}Tests completed${NC}"
        summarize_results
        return 0
    fi

    # Fall back to direct execution
    run_load_tests
    TEST_RESULT=$?

    if [ $TEST_RESULT -eq 0 ]; then
        echo -e "${GREEN}✓ Load tests completed successfully${NC}"
    else
        echo -e "${YELLOW}⚠ Load tests completed with issues${NC}"
        echo "See output above for details"
    fi

    summarize_results
    return $TEST_RESULT
}

# Run main function
main "$@"

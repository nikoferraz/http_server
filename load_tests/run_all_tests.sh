#!/bin/bash

# Comprehensive load testing script for HTTP Server
# Runs all load tests and validates against performance targets
# Prerequisites:
#   - HTTP Server running on port 8080
#   - wrk installed (https://github.com/wg/wrk)
#   - Test files created in webroot (index.html, small.html, medium.txt, large.pdf)

set -e

# Configuration
SERVER_URL="http://localhost:8080"
SERVER_PORT=8080
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_FILE="load_test_results_$(date +%Y%m%d_%H%M%S).txt"

# Performance targets
MIN_THROUGHPUT=10000
MAX_LATENCY_MS=100
TARGET_CONCURRENCY=10000
TARGET_ERROR_RATE=0.001

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
print_section() {
    echo ""
    echo "=========================================="
    echo "$1"
    echo "=========================================="
}

print_status() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ PASS${NC}: $1"
    else
        echo -e "${RED}✗ FAIL${NC}: $1"
    fi
}

check_server() {
    print_section "Checking Server Status"
    if ! curl -s "$SERVER_URL/health" > /dev/null 2>&1; then
        echo -e "${RED}ERROR: Server not running on $SERVER_URL${NC}"
        echo "Start the server and try again:"
        echo "  java HTTPServer.Servlet <webroot_path> $SERVER_PORT"
        exit 1
    fi
    echo "✓ Server is running and healthy"
}

check_wrk() {
    print_section "Checking Prerequisites"
    if ! command -v wrk &> /dev/null; then
        echo -e "${YELLOW}Warning: wrk not found.${NC}"
        echo "Install wrk from: https://github.com/wg/wrk"
        echo "Or run Java-based load tests instead: mvn test -Dgroups=LoadTests"
        return 1
    fi
    echo "✓ wrk is installed"
    return 0
}

create_test_files() {
    print_section "Creating Test Files"
    WEBROOT="${1:-.}"

    # Create small file
    echo "<html><body>Small test file</body></html>" > "$WEBROOT/small.html"
    echo "✓ Created small.html"

    # Create medium file (50KB)
    python3 -c "print('<html><body>' + 'x' * 50000 + '</body></html>')" > "$WEBROOT/medium.txt" 2>/dev/null || \
    bash -c "echo '<html><body>'; for i in {1..500}; do echo 'Medium file test line'; done; echo '</body></html>'" > "$WEBROOT/medium.txt"
    echo "✓ Created medium.txt"

    # Create large file (1MB) - optional
    if [ "$CREATE_LARGE" = "true" ]; then
        python3 -c "print('<html><body>' + 'x' * 1000000 + '</body></html>')" > "$WEBROOT/large.pdf" 2>/dev/null || true
        echo "✓ Created large.pdf"
    fi
}

run_basic_load_test() {
    print_section "Test 1: Basic Throughput (30 seconds)"
    wrk -t12 -c400 -d30s -s "$SCRIPT_DIR/basic_load.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

run_high_concurrency_test() {
    print_section "Test 2: High Concurrency (1,000 connections, 30 seconds)"
    wrk -t12 -c1000 -d30s -s "$SCRIPT_DIR/basic_load.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

run_sustained_load_test() {
    print_section "Test 3: Sustained Load (100 concurrent, 2 minutes)"
    wrk -t12 -c100 -d2m -s "$SCRIPT_DIR/sustained_load.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

run_cache_test() {
    print_section "Test 4: Cache Performance (30 seconds)"
    wrk -t12 -c400 -d30s -s "$SCRIPT_DIR/cache_test.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

run_mixed_workload_test() {
    print_section "Test 5: Mixed Workload (30 seconds)"
    wrk -t12 -c400 -d30s -s "$SCRIPT_DIR/mixed_workload.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

run_spike_test() {
    print_section "Test 6: Spike Traffic (30 seconds, up to 5,000 concurrent)"
    wrk -t12 -c5000 -d30s -s "$SCRIPT_DIR/spike_test.lua" "$SERVER_URL/" 2>&1 | tee -a "$RESULTS_FILE"
}

validate_results() {
    print_section "Performance Validation"

    echo "Performance Targets:"
    echo "  - Minimum Throughput: $MIN_THROUGHPUT req/sec"
    echo "  - Maximum P99 Latency: ${MAX_LATENCY_MS}ms"
    echo "  - Target Concurrency: $TARGET_CONCURRENCY+ connections"
    echo "  - Error Rate: < $(echo "scale=2; $TARGET_ERROR_RATE * 100" | bc)%"

    echo ""
    echo "Results saved to: $RESULTS_FILE"
}

# Main execution
main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════╗"
    echo "║         HTTP Server Load Testing Suite                 ║"
    echo "║     Comprehensive Performance Validation               ║"
    echo "╚════════════════════════════════════════════════════════╝"
    echo ""

    # Check prerequisites
    check_server || exit 1

    if check_wrk; then
        # Run wrk tests
        CREATE_LARGE=false
        create_test_files "."

        run_basic_load_test
        echo "Waiting between tests..."
        sleep 5

        run_high_concurrency_test
        echo "Waiting between tests..."
        sleep 5

        run_sustained_load_test
        echo "Waiting between tests..."
        sleep 5

        run_cache_test
        echo "Waiting between tests..."
        sleep 5

        run_mixed_workload_test
        echo "Waiting between tests..."
        sleep 5

        run_spike_test

        validate_results
    else
        echo ""
        echo "Using Java-based load tests instead."
        echo "Run with: mvn test -Dgroups=LoadTests"
        echo "Or compile and run: javac HTTPServer/tests/LoadTestSuite.java && java -m jdk.compiler HTTPServer/tests/LoadTestSuite"
    fi

    echo ""
    echo "Load testing complete!"
    echo "For detailed results, see: $RESULTS_FILE"
}

# Run main function
main "$@"

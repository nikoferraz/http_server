#!/bin/bash

# Quick 1-hour soak test runner for rapid validation
# Useful for CI/CD pipelines and regression testing

set -e

CONCURRENT_CLIENTS=${1:-50}
REQUESTS_PER_SEC=${2:-500}

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_OPTS="-Xmx1g -Xms512m"

echo "=========================================="
echo "HTTP Server Quick Soak Test (1 Hour)"
echo "=========================================="
echo "Concurrent clients: $CONCURRENT_CLIENTS"
echo "Target RPS: $REQUESTS_PER_SEC"
echo ""

# Compile test classes if needed
echo "Compiling quick soak test..."
cd "$PROJECT_DIR"

if ! javac -d . HTTPServer/tests/QuickSoakTest.java 2>/dev/null; then
    echo "Compilation failed. Ensuring dependencies..."
    javac -cp . -d . HTTPServer/tests/QuickSoakTest.java 2>/dev/null || {
        echo "Error: Failed to compile QuickSoakTest.java"
        exit 1
    }
fi

echo "Starting 1-hour quick soak test..."
echo ""

# Run in foreground so we can see output
java $JAVA_OPTS HTTPServer.tests.QuickSoakTest "$CONCURRENT_CLIENTS" "$REQUESTS_PER_SEC"

EXITCODE=$?

echo ""
echo "=========================================="
echo "Quick soak test completed!"
echo "=========================================="
echo ""
echo "Analyzing results..."
python3 "$PROJECT_DIR/analyze_soak_test.py" quick_soak_test_metrics.csv quick_soak_test_results.txt 2>/dev/null || true

echo ""
echo "Results summary:"
if [ -f quick_soak_test_results.txt ]; then
    head -30 quick_soak_test_results.txt
fi

exit $EXITCODE

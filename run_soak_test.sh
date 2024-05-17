#!/bin/bash

# 24-hour soak test runner
# Detects memory leaks, resource exhaustion, and stability issues

set -e

DURATION_HOURS=${1:-24}
CONCURRENT_CLIENTS=${2:-100}
REQUESTS_PER_SEC=${3:-1000}

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_OPTS="-Xmx2g -Xms1g"

echo "=========================================="
echo "HTTP Server 24-Hour Soak Test"
echo "=========================================="
echo "Duration: $DURATION_HOURS hours"
echo "Concurrent clients: $CONCURRENT_CLIENTS"
echo "Target RPS: $REQUESTS_PER_SEC"
echo ""

# Compile test classes if needed
echo "Compiling soak test..."
cd "$PROJECT_DIR"

if ! javac -d . HTTPServer/tests/SoakTest.java 2>/dev/null; then
    echo "Compilation failed. Ensuring dependencies..."
    # Attempt to compile with standard options
    javac -cp . -d . HTTPServer/tests/SoakTest.java 2>/dev/null || {
        echo "Error: Failed to compile SoakTest.java"
        exit 1
    }
fi

echo "Starting 24-hour soak test..."
echo "PID will be displayed below (for monitoring/stopping):"
echo ""

# Run in background with nohup to survive terminal disconnect
nohup java $JAVA_OPTS HTTPServer.tests.SoakTest "$DURATION_HOURS" "$CONCURRENT_CLIENTS" "$REQUESTS_PER_SEC" \
    > soak_test_output.log 2>&1 &

SOAK_PID=$!
echo "Soak test PID: $SOAK_PID"
echo ""
echo "Log files:"
echo "  Output: soak_test_output.log"
echo "  Metrics: soak_test_metrics.csv"
echo "  Results: soak_test_results.txt"
echo ""
echo "Monitoring commands:"
echo "  tail -f soak_test_output.log          # Watch in real-time"
echo "  watch -n 30 tail -20 soak_test_output.log  # Update every 30 seconds"
echo ""
echo "To stop the test (graceful):"
echo "  kill $SOAK_PID"
echo ""
echo "To analyze results after completion:"
echo "  python3 analyze_soak_test.py"
echo ""

# Wait for completion
wait $SOAK_PID 2>/dev/null || true

echo ""
echo "=========================================="
echo "Soak test completed!"
echo "=========================================="
echo ""
echo "Analyzing results..."
python3 "$PROJECT_DIR/analyze_soak_test.py" 2>/dev/null || true

echo ""
echo "Results summary:"
if [ -f soak_test_results.txt ]; then
    head -30 soak_test_results.txt
fi

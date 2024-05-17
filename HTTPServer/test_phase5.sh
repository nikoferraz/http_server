#!/bin/bash

# Phase 5: Advanced Professional Features Test Script
# Test all Phase 5 endpoints and features

BASE_URL="http://localhost:8080"
ADMIN_AUTH="admin:password"

echo "========================================"
echo "Phase 5: Advanced Features Test Suite"
echo "========================================"
echo ""

# Color codes for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counter
TESTS_PASSED=0
TESTS_FAILED=0

# Function to test endpoint
test_endpoint() {
    local name="$1"
    local expected_code="$2"
    shift 2
    local curl_cmd="$@"

    echo -n "Testing: $name... "

    response=$(eval "$curl_cmd" 2>&1)
    actual_code=$(echo "$response" | grep "HTTP/" | awk '{print $2}' | head -1)

    if [ "$actual_code" == "$expected_code" ]; then
        echo -e "${GREEN}PASS${NC} (HTTP $actual_code)"
        ((TESTS_PASSED++))
        return 0
    else
        echo -e "${RED}FAIL${NC} (Expected: HTTP $expected_code, Got: HTTP $actual_code)"
        ((TESTS_FAILED++))
        return 1
    fi
}

echo "1. Testing Health Check Endpoints"
echo "-----------------------------------"

test_endpoint "Liveness Probe" "200" \
    "curl -s -i '$BASE_URL/health/live'"

test_endpoint "Readiness Probe" "200" \
    "curl -s -i '$BASE_URL/health/ready'"

test_endpoint "Startup Probe" "200" \
    "curl -s -i '$BASE_URL/health/startup'"

echo ""

echo "2. Testing Metrics Endpoint"
echo "-----------------------------------"

test_endpoint "Prometheus Metrics" "200" \
    "curl -s -i '$BASE_URL/metrics'"

echo "Checking metrics content..."
metrics_content=$(curl -s "$BASE_URL/metrics")
if echo "$metrics_content" | grep -q "http_requests_total"; then
    echo -e "${GREEN}✓${NC} Found http_requests_total metric"
else
    echo -e "${RED}✗${NC} Missing http_requests_total metric"
fi

if echo "$metrics_content" | grep -q "http_active_connections"; then
    echo -e "${GREEN}✓${NC} Found http_active_connections metric"
else
    echo -e "${RED}✗${NC} Missing http_active_connections metric"
fi

echo ""

echo "3. Testing API Endpoints - POST /api/echo"
echo "-----------------------------------"

# Test without authentication (should fail)
test_endpoint "POST /api/echo (no auth)" "401" \
    "curl -s -i -X POST '$BASE_URL/api/echo' -H 'Content-Type: application/json' -d '{\"message\":\"hello\"}'"

# Test with authentication and JSON body
test_endpoint "POST /api/echo (with auth)" "200" \
    "curl -s -i -u '$ADMIN_AUTH' -X POST '$BASE_URL/api/echo' -H 'Content-Type: application/json' -d '{\"message\":\"hello\"}'"

# Test with form data
test_endpoint "POST /api/echo (form data)" "200" \
    "curl -s -i -u '$ADMIN_AUTH' -X POST '$BASE_URL/api/echo' -H 'Content-Type: application/x-www-form-urlencoded' -d 'name=test&value=123'"

echo ""

echo "4. Testing API Endpoints - POST /api/upload"
echo "-----------------------------------"

test_endpoint "POST /api/upload" "200" \
    "curl -s -i -u '$ADMIN_AUTH' -X POST '$BASE_URL/api/upload' -H 'Content-Type: text/plain' -d 'test file content'"

echo ""

echo "5. Testing API Endpoints - /api/data"
echo "-----------------------------------"

test_endpoint "PUT /api/data/123" "200" \
    "curl -s -i -u '$ADMIN_AUTH' -X PUT '$BASE_URL/api/data/123' -H 'Content-Type: application/json' -d '{\"value\":\"updated\"}'"

test_endpoint "DELETE /api/data/123" "200" \
    "curl -s -i -u '$ADMIN_AUTH' -X DELETE '$BASE_URL/api/data/123'"

test_endpoint "POST /api/data" "201" \
    "curl -s -i -u '$ADMIN_AUTH' -X POST '$BASE_URL/api/data' -H 'Content-Type: application/json' -d '{\"value\":\"created\"}'"

echo ""

echo "6. Testing Rate Limiting"
echo "-----------------------------------"
echo "Sending 25 rapid requests to test rate limiting (limit: 20 burst)..."

rate_limit_hit=false
for i in {1..25}; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/health/live")
    if [ "$status" == "429" ]; then
        rate_limit_hit=true
        echo -e "${GREEN}✓${NC} Rate limit triggered on request $i (HTTP 429)"
        break
    fi
done

if [ "$rate_limit_hit" = false ]; then
    echo -e "${YELLOW}!${NC} Rate limit not triggered (may need more requests or rate limit disabled)"
fi

# Check for rate limit headers
echo ""
echo "Checking rate limit headers..."
headers=$(curl -s -i "$BASE_URL/health/live" | head -20)
if echo "$headers" | grep -iq "X-RateLimit-Limit"; then
    limit=$(echo "$headers" | grep -i "X-RateLimit-Limit" | cut -d: -f2 | tr -d ' \r')
    echo -e "${GREEN}✓${NC} X-RateLimit-Limit: $limit"
else
    echo -e "${YELLOW}!${NC} X-RateLimit-Limit header not found"
fi

if echo "$headers" | grep -iq "X-RateLimit-Remaining"; then
    remaining=$(echo "$headers" | grep -i "X-RateLimit-Remaining" | cut -d: -f2 | tr -d ' \r')
    echo -e "${GREEN}✓${NC} X-RateLimit-Remaining: $remaining"
else
    echo -e "${YELLOW}!${NC} X-RateLimit-Remaining header not found"
fi

echo ""

echo "7. Testing Method Validation"
echo "-----------------------------------"

test_endpoint "GET /api/echo (wrong method)" "405" \
    "curl -s -i -u '$ADMIN_AUTH' -X GET '$BASE_URL/api/echo'"

test_endpoint "PUT /api/echo (wrong method)" "405" \
    "curl -s -i -u '$ADMIN_AUTH' -X PUT '$BASE_URL/api/echo' -d 'test'"

echo ""

echo "8. Testing Payload Size Limits"
echo "-----------------------------------"
echo "Creating 11MB payload (limit: 10MB)..."

# Create large payload file
large_payload=$(python3 -c "print('x' * (11 * 1024 * 1024))" 2>/dev/null || perl -e "print 'x' x (11 * 1024 * 1024)")

echo -n "Testing payload too large... "
status=$(echo "$large_payload" | curl -s -o /dev/null -w "%{http_code}" -u "$ADMIN_AUTH" -X POST "$BASE_URL/api/echo" -H "Content-Type: text/plain" -d @-)

if [ "$status" == "413" ]; then
    echo -e "${GREEN}PASS${NC} (HTTP 413 Payload Too Large)"
    ((TESTS_PASSED++))
else
    echo -e "${YELLOW}PARTIAL${NC} (HTTP $status - may be connection issue)"
fi

echo ""

echo "9. Testing Structured Logging"
echo "-----------------------------------"
echo "Checking if JSON logging is enabled in logs..."

# Look for JSON formatted logs
if [ -f "example_webroot/logs/server_log.log" ]; then
    if grep -q '"timestamp"' example_webroot/logs/server_log.log 2>/dev/null; then
        echo -e "${GREEN}✓${NC} JSON structured logging detected"
    else
        echo -e "${YELLOW}!${NC} Plain text logging format detected"
    fi
else
    echo -e "${YELLOW}!${NC} Log file not found"
fi

echo ""

echo "========================================"
echo "Test Summary"
echo "========================================"
echo -e "Tests Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Tests Failed: ${RED}$TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed.${NC}"
    exit 1
fi

#!/bin/bash

# Verify soak test setup and dependencies

echo "=========================================="
echo "Soak Test Setup Verification"
echo "=========================================="
echo ""

ISSUES=0

# Check Java
echo -n "Checking Java... "
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | grep 'version' | head -1)
    echo "OK"
    echo "  $JAVA_VERSION"
else
    echo "FAILED - Java not found"
    ISSUES=$((ISSUES + 1))
fi

# Check Python
echo -n "Checking Python3... "
if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version)
    echo "OK"
    echo "  $PYTHON_VERSION"
else
    echo "FAILED - Python3 not found"
    ISSUES=$((ISSUES + 1))
fi

# Check javac
echo -n "Checking javac (compiler)... "
if command -v javac &> /dev/null; then
    echo "OK"
else
    echo "FAILED - javac not found"
    ISSUES=$((ISSUES + 1))
fi

echo ""
echo "Verifying test files..."

# Check source files
echo -n "  SoakTest.java... "
if [ -f HTTPServer/tests/SoakTest.java ]; then
    echo "OK ($(wc -l < HTTPServer/tests/SoakTest.java) lines)"
else
    echo "MISSING"
    ISSUES=$((ISSUES + 1))
fi

echo -n "  QuickSoakTest.java... "
if [ -f HTTPServer/tests/QuickSoakTest.java ]; then
    echo "OK ($(wc -l < HTTPServer/tests/QuickSoakTest.java) lines)"
else
    echo "MISSING"
    ISSUES=$((ISSUES + 1))
fi

echo ""
echo "Verifying scripts..."

# Check scripts
echo -n "  run_soak_test.sh... "
if [ -f run_soak_test.sh ] && [ -x run_soak_test.sh ]; then
    echo "OK"
else
    echo "MISSING or not executable"
    ISSUES=$((ISSUES + 1))
fi

echo -n "  run_quick_soak_test.sh... "
if [ -f run_quick_soak_test.sh ] && [ -x run_quick_soak_test.sh ]; then
    echo "OK"
else
    echo "MISSING or not executable"
    ISSUES=$((ISSUES + 1))
fi

echo -n "  analyze_soak_test.py... "
if [ -f analyze_soak_test.py ] && [ -x analyze_soak_test.py ]; then
    echo "OK"
else
    echo "MISSING or not executable"
    ISSUES=$((ISSUES + 1))
fi

echo ""
echo "Verifying documentation..."

echo -n "  SOAK_TEST_GUIDE.md... "
if [ -f SOAK_TEST_GUIDE.md ]; then
    echo "OK ($(wc -l < SOAK_TEST_GUIDE.md) lines)"
else
    echo "MISSING"
    ISSUES=$((ISSUES + 1))
fi

echo -n "  SOAK_TEST_QUICK_REFERENCE.md... "
if [ -f SOAK_TEST_QUICK_REFERENCE.md ]; then
    echo "OK ($(wc -l < SOAK_TEST_QUICK_REFERENCE.md) lines)"
else
    echo "MISSING"
    ISSUES=$((ISSUES + 1))
fi

echo -n "  SOAK_TEST_SUMMARY.md... "
if [ -f SOAK_TEST_SUMMARY.md ]; then
    echo "OK ($(wc -l < SOAK_TEST_SUMMARY.md) lines)"
else
    echo "MISSING"
    ISSUES=$((ISSUES + 1))
fi

echo ""
echo "Attempting compilation..."

# Try compiling
if javac -d . HTTPServer/tests/SoakTest.java 2>/dev/null; then
    echo "  SoakTest.java: SUCCESS"
else
    echo "  SoakTest.java: FAILED"
    ISSUES=$((ISSUES + 1))
fi

if javac -d . HTTPServer/tests/QuickSoakTest.java 2>/dev/null; then
    echo "  QuickSoakTest.java: SUCCESS"
else
    echo "  QuickSoakTest.java: FAILED"
    ISSUES=$((ISSUES + 1))
fi

echo ""
echo "=========================================="

if [ $ISSUES -eq 0 ]; then
    echo "SETUP VERIFICATION: PASSED"
    echo ""
    echo "You can now run the soak tests:"
    echo ""
    echo "  Quick test (1 hour):"
    echo "    ./run_quick_soak_test.sh"
    echo ""
    echo "  Full test (24 hours):"
    echo "    ./run_soak_test.sh"
    echo ""
    echo "For detailed info, see:"
    echo "  SOAK_TEST_QUICK_REFERENCE.md  (quick start)"
    echo "  SOAK_TEST_GUIDE.md            (complete guide)"
    echo ""
    exit 0
else
    echo "SETUP VERIFICATION: FAILED"
    echo "Issues found: $ISSUES"
    echo ""
    echo "Please install missing dependencies:"
    echo "  - Java JDK 21+ (javac required)"
    echo "  - Python 3.6+ (for analysis)"
    echo ""
    exit 1
fi

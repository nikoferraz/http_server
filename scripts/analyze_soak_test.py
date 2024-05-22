#!/usr/bin/env python3
"""
Analyze soak test metrics and generate memory leak detection report.

Usage:
    python3 analyze_soak_test.py [metrics_file] [results_file]

Default files:
    metrics: soak_test_metrics.csv
    results: soak_test_results.txt
"""

import sys
import csv
import os
from datetime import datetime
from pathlib import Path

def analyze_metrics(metrics_file, results_file):
    """Analyze soak test metrics and generate report."""

    if not os.path.exists(metrics_file):
        print(f"Error: Metrics file not found: {metrics_file}")
        print("Ensure the soak test has completed and produced output.")
        return False

    print("="*80)
    print("SOAK TEST ANALYSIS")
    print("="*80)
    print(f"Metrics file: {metrics_file}")
    print()

    # Read metrics
    timestamps = []
    elapsed_minutes = []
    used_mb = []
    total_mb = []
    max_mb = []
    requests = []
    errors = []
    response_times = []

    try:
        with open(metrics_file, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                timestamps.append(int(row['timestamp']))
                elapsed_minutes.append(int(row['elapsed_minutes']))
                used_mb.append(int(row['used_mb']))
                total_mb.append(int(row['total_mb']))
                max_mb.append(int(row['max_mb']))
                requests.append(int(row['requests']))
                errors.append(int(row['errors']))
                response_times.append(int(row['avg_response_time_ms']))

    except Exception as e:
        print(f"Error reading metrics file: {e}")
        return False

    if not used_mb:
        print("No metrics data found")
        return False

    print("TEST DURATION AND LOAD")
    print("-" * 80)
    total_minutes = elapsed_minutes[-1] if elapsed_minutes else 0
    total_hours = total_minutes / 60.0
    print(f"Total duration: {total_minutes} minutes ({total_hours:.2f} hours)")
    print(f"Data points collected: {len(used_mb)}")
    print(f"Sampling interval: {5 if 'soak_test' in metrics_file else 1} minutes")
    print()

    # Memory analysis
    print("MEMORY ANALYSIS")
    print("-" * 80)

    initial_memory = used_mb[0]
    final_memory = used_mb[-1]
    min_memory = min(used_mb)
    max_memory_used = max(used_mb)
    avg_memory = sum(used_mb) / len(used_mb)

    print(f"Initial heap: {initial_memory} MB")
    print(f"Final heap:   {final_memory} MB")
    print(f"Min heap:     {min_memory} MB")
    print(f"Max heap:     {max_memory_used} MB")
    print(f"Avg heap:     {avg_memory:.1f} MB")
    print()

    # Memory growth analysis
    total_growth_mb = final_memory - initial_memory
    growth_rate_per_hour = total_growth_mb / max(total_hours, 0.1)

    print("MEMORY GROWTH ANALYSIS")
    print("-" * 80)
    print(f"Total growth: {total_growth_mb} MB")
    print(f"Growth rate: {growth_rate_per_hour:.2f} MB/hour")

    # Classify memory leak risk
    if growth_rate_per_hour < 2:
        status = "PASS - Healthy memory usage"
        confidence = "Very low"
    elif growth_rate_per_hour < 5:
        status = "PASS - Acceptable growth"
        confidence = "Low"
    elif growth_rate_per_hour < 10:
        status = "WARNING - Monitor closely"
        confidence = "Medium"
    else:
        status = "FAIL - Likely memory leak"
        confidence = "High"

    print(f"Status: {status}")
    print(f"Confidence level: {confidence}")
    print()

    # Stability analysis
    print("STABILITY ANALYSIS")
    print("-" * 80)

    # Calculate memory variance
    if len(used_mb) > 1:
        mean = sum(used_mb) / len(used_mb)
        variance = sum((x - mean) ** 2 for x in used_mb) / len(used_mb)
        std_dev = variance ** 0.5
        cv = std_dev / mean * 100  # Coefficient of variation

        print(f"Memory std dev: {std_dev:.2f} MB")
        print(f"Coefficient of variation: {cv:.2f}%")

        if cv < 5:
            print("Memory stability: EXCELLENT (< 5% variation)")
        elif cv < 10:
            print("Memory stability: GOOD (5-10% variation)")
        elif cv < 20:
            print("Memory stability: FAIR (10-20% variation)")
        else:
            print("Memory stability: POOR (> 20% variation)")
    print()

    # Request/error analysis
    if requests:
        print("REQUEST STATISTICS")
        print("-" * 80)

        total_requests = requests[-1]
        total_errors = errors[-1]

        if total_requests > 0:
            success_rate = (total_requests - total_errors) / total_requests * 100
        else:
            success_rate = 0

        print(f"Total requests: {total_requests:,}")
        print(f"Total errors: {total_errors:,}")
        print(f"Success rate: {success_rate:.2f}%")

        if success_rate >= 99.5:
            print("Request reliability: PASS")
        else:
            print("Request reliability: FAIL")
        print()

    # Cache capacity checks
    print("CACHE CAPACITY ANALYSIS")
    print("-" * 80)
    print("Critical cache limits:")
    print("  ETag cache: 10,000 entry limit")
    print("  Compression cache: 1,000 entry limit")
    print("  Rate limiter buckets: 10,000 limit")
    print("  Buffer pool: 1,000 buffers")
    print()
    print("Note: Check server logs to verify cache evictions are happening")
    print("within expected limits during sustained load.")
    print()

    # Final recommendation
    print("RECOMMENDATIONS")
    print("-" * 80)

    recommendations = []

    if growth_rate_per_hour > 5:
        recommendations.append("1. Investigate memory leak - growth rate exceeds 5 MB/hour")
        recommendations.append("   - Check for unbounded collections in request handlers")
        recommendations.append("   - Review thread creation and cleanup")

    if cv > 20:
        recommendations.append("2. Memory usage is unstable - varies > 20%")
        recommendations.append("   - Check for cache eviction patterns")
        recommendations.append("   - Verify GC behavior during sustained load")

    if success_rate < 99.5:
        recommendations.append("3. Error rate is too high - improve error handling")
        recommendations.append("   - Check server capacity and thread pool sizes")
        recommendations.append("   - Review timeout and connection limits")

    if not recommendations:
        recommendations.append("No issues detected - system appears stable")

    for rec in recommendations:
        print(rec)

    print()
    print("="*80)
    print("DETAILED ANALYSIS SAVED")
    print("="*80)

    # Write detailed report
    write_detailed_report(metrics_file, results_file, used_mb, elapsed_minutes,
                         growth_rate_per_hour, success_rate, total_hours)

    return True

def write_detailed_report(metrics_file, results_file, memory_data, elapsed_minutes,
                         growth_rate, success_rate, total_hours):
    """Write detailed analysis report to file."""

    report_file = metrics_file.replace('.csv', '_analysis.txt')

    with open(report_file, 'w') as f:
        f.write("="*80 + "\n")
        f.write("DETAILED SOAK TEST ANALYSIS REPORT\n")
        f.write("="*80 + "\n\n")

        f.write("EXECUTIVE SUMMARY\n")
        f.write("-"*80 + "\n")

        if growth_rate < 2:
            overall = "PASS - Server is stable"
        elif growth_rate < 5:
            overall = "PASS - Minor growth detected"
        elif growth_rate < 10:
            overall = "WARNING - Monitor memory closely"
        else:
            overall = "FAIL - Likely memory leak"

        f.write(f"Overall Status: {overall}\n")
        f.write(f"Duration: {total_hours:.2f} hours\n")
        f.write(f"Memory growth rate: {growth_rate:.2f} MB/hour\n")
        f.write(f"Request success rate: {success_rate:.2f}%\n\n")

        f.write("MEMORY TREND\n")
        f.write("-"*80 + "\n")
        if len(memory_data) > 1:
            for i in range(0, len(memory_data), max(1, len(memory_data)//10)):
                f.write(f"  {elapsed_minutes[i]:4d} min: {memory_data[i]:5d} MB\n")
        f.write("\n")

        f.write("THRESHOLDS AND GUIDELINES\n")
        f.write("-"*80 + "\n")
        f.write("Memory growth rate thresholds:\n")
        f.write("  0-2 MB/hour:   Normal, no leak detected\n")
        f.write("  2-5 MB/hour:   Minor growth, acceptable\n")
        f.write("  5-10 MB/hour:  Concerning, investigate\n")
        f.write("  >10 MB/hour:   Critical, likely leak\n\n")

        f.write("Success rate thresholds:\n")
        f.write("  >99.5%:  Excellent\n")
        f.write("  99-99.5%: Good\n")
        f.write("  95-99%:   Acceptable for testing\n")
        f.write("  <95%:     Problematic, investigate\n\n")

        f.write("NEXT STEPS\n")
        f.write("-"*80 + "\n")
        if growth_rate > 5:
            f.write("1. Enable memory profiling to identify leak source\n")
            f.write("2. Check for circular references in cache implementations\n")
            f.write("3. Verify all resources (connections, buffers) are properly closed\n")
            f.write("4. Run with GC logging enabled: -XX:+PrintGC -XX:+PrintGCDetails\n")
        else:
            f.write("1. Server appears stable for 24+ hour operations\n")
            f.write("2. Continue monitoring in production\n")
            f.write("3. Run periodic quick soak tests (1 hour) as regression tests\n")

        f.write("\n")

    print(f"Detailed report saved to: {report_file}")

def main():
    metrics_file = 'soak_test_metrics.csv'
    results_file = 'soak_test_results.txt'

    if len(sys.argv) > 1:
        metrics_file = sys.argv[1]
    if len(sys.argv) > 2:
        results_file = sys.argv[2]

    # Check for quick soak test files
    if not os.path.exists(metrics_file):
        if os.path.exists('quick_soak_test_metrics.csv'):
            metrics_file = 'quick_soak_test_metrics.csv'
            results_file = 'quick_soak_test_results.txt'

    success = analyze_metrics(metrics_file, results_file)
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    main()

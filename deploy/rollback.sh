#!/bin/bash

###############################################################################
# Emergency Rollback Script for HTTP Server
#
# This script performs an immediate rollback to the stable (v1) version
# if critical issues are detected during canary deployment.
#
# Usage: ./rollback.sh [--force] [--preserve-logs]
###############################################################################

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NAMESPACE="http-server"
FORCE_ROLLBACK=false
PRESERVE_LOGS=true
DRY_RUN=false

###############################################################################
# Utility Functions
###############################################################################

log_info() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} INFO: $*"
}

log_success() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} SUCCESS: $*"
}

log_warn() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} WARNING: $*"
}

log_error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} ERROR: $*"
}

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

OPTIONS:
    --force               Force rollback without confirmation
    --dry-run             Show what would be rolled back without making changes
    --preserve-logs       Keep canary logs for analysis (default: true)
    --help                Show this help message

EXAMPLES:
    # Interactive rollback with confirmation
    ./rollback.sh

    # Emergency rollback without confirmation
    ./rollback.sh --force

    # Dry run to see rollback plan
    ./rollback.sh --dry-run

    # Force rollback and delete logs
    ./rollback.sh --force --preserve-logs=false
EOF
    exit 0
}

###############################################################################
# Pre-rollback Checks
###############################################################################

check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl not found. Please install kubectl."
        exit 1
    fi

    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster."
        exit 1
    fi

    if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
        log_error "Namespace $NAMESPACE does not exist."
        exit 1
    fi

    log_success "Prerequisites met"
}

check_stable_readiness() {
    log_info "Verifying stable deployment is available..."

    if ! kubectl get deployment/http-server-stable -n "$NAMESPACE" &> /dev/null; then
        log_error "Stable deployment not found. Cannot rollback."
        exit 1
    fi

    local ready_replicas=$(kubectl get deployment/http-server-stable \
        -n "$NAMESPACE" \
        -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")

    if [[ "$ready_replicas" -lt 1 ]]; then
        log_warn "Stable deployment has no ready replicas. Attempting rollback anyway."
        sleep 5
    else
        log_success "Stable deployment ready with $ready_replicas replicas"
    fi
}

collect_canary_diagnostics() {
    local diagnostics_dir="${PROJECT_DIR}/diagnostics/$(date +'%Y%m%d_%H%M%S')"

    log_info "Collecting canary diagnostics to $diagnostics_dir..."

    mkdir -p "$diagnostics_dir"

    # Collect pod status
    kubectl get pods -n "$NAMESPACE" -l track=canary \
        > "$diagnostics_dir/pod_status.txt" 2>&1 || true

    # Collect events
    kubectl get events -n "$NAMESPACE" \
        > "$diagnostics_dir/events.txt" 2>&1 || true

    # Collect logs
    kubectl logs deployment/http-server-canary -n "$NAMESPACE" \
        > "$diagnostics_dir/canary.log" 2>&1 || true

    # Collect metrics if available
    if kubectl get pods -n "$NAMESPACE" -l app=prometheus &> /dev/null; then
        log_info "Exporting Prometheus metrics..."
        kubectl exec -n "$NAMESPACE" \
            deployment/prometheus -- \
            curl -s 'http://localhost:9090/api/v1/query?query=http_requests_total' \
            > "$diagnostics_dir/metrics.json" 2>&1 || true
    fi

    # Collect deployment details
    kubectl describe deployment/http-server-canary -n "$NAMESPACE" \
        > "$diagnostics_dir/deployment_describe.txt" 2>&1 || true

    log_success "Diagnostics collected to $diagnostics_dir"
    echo "$diagnostics_dir"
}

###############################################################################
# Rollback Functions
###############################################################################

stop_canary() {
    log_info "Stopping canary deployment..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] kubectl delete deployment/http-server-canary -n $NAMESPACE"
        return 0
    fi

    if kubectl delete deployment/http-server-canary -n "$NAMESPACE" --ignore-not-found=true; then
        log_success "Canary deployment deleted"
    else
        log_error "Failed to delete canary deployment"
        return 1
    fi
}

scale_stable() {
    log_info "Scaling stable deployment to full capacity..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] kubectl scale deployment/http-server-stable --replicas=3 -n $NAMESPACE"
        return 0
    fi

    kubectl scale deployment/http-server-stable \
        --replicas=3 \
        -n "$NAMESPACE"

    log_info "Waiting for stable deployment to be ready..."
    kubectl rollout status deployment/http-server-stable \
        -n "$NAMESPACE" --timeout=5m

    log_success "Stable deployment ready"
}

reset_nginx_config() {
    log_info "Resetting nginx traffic routing to 100% stable..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would reset nginx canary weight to 0%"
        return 0
    fi

    kubectl patch configmap nginx-config \
        -n "$NAMESPACE" \
        -p '{"data":{"canary_weight":"0"}}' || true

    log_success "Nginx config reset"
}

verify_rollback() {
    log_info "Verifying rollback success..."

    # Check stable pods
    local ready=$(kubectl get deployment/http-server-stable \
        -n "$NAMESPACE" \
        -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")

    if [[ "$ready" -lt 1 ]]; then
        log_error "Stable deployment not ready after rollback"
        return 1
    fi

    # Test health endpoint
    local pod=$(kubectl get pods -n "$NAMESPACE" \
        -l app=http-server,track=stable \
        -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

    if [[ -z "$pod" ]]; then
        log_error "No stable pod found"
        return 1
    fi

    if kubectl exec -n "$NAMESPACE" "$pod" -- \
        curl -s -f http://localhost:8080/health/ready &> /dev/null; then
        log_success "Stable version health check passed"
        return 0
    else
        log_warn "Stable version health check inconclusive"
        return 0
    fi
}

###############################################################################
# Analysis and Reporting
###############################################################################

generate_rollback_report() {
    local report_file="${PROJECT_DIR}/rollback_report_$(date +'%Y%m%d_%H%M%S').md"

    log_info "Generating rollback report..."

    cat > "$report_file" << 'EOF'
# Emergency Rollback Report

## Timeline
- **Rollback Initiated:** $(date)
- **Duration:** $duration seconds

## Canary Deployment Status
- Stable deployment ready: YES
- Canary deleted: YES
- Traffic routing reset: YES

## Investigation Required

Please investigate the following:

1. **Canary Logs**
   - Check `/diagnostics/` for full logs and metrics
   - Look for exceptions and error patterns
   - Review GC logs for heap size issues

2. **Performance Metrics**
   - Compare p99 latency between v1 and v2
   - Check cache hit rates and effectiveness
   - Review CPU and memory growth

3. **Configuration**
   - Verify JVM settings are appropriate
   - Check cache size limits
   - Review thread pool configuration

4. **Application Issues**
   - Check for memory leaks in new code
   - Review cache implementation
   - Verify graceful shutdown behavior

## Remediation Steps

1. Analyze collected diagnostics
2. Identify root cause
3. Fix and rebuild Docker image
4. Run load tests in staging
5. Retry canary deployment

## Contacts

- On-Call Engineer: [name]
- SRE Team: [slack channel]
- Incident Channel: [slack channel]

---
**Report Generated:** $(date)
EOF

    log_success "Rollback report generated: $report_file"
}

###############################################################################
# Main Rollback Workflow
###############################################################################

main() {
    log_error ""
    log_error "╔════════════════════════════════════════╗"
    log_error "║   EMERGENCY ROLLBACK INITIATED         ║"
    log_error "║   Rolling back to v1-stable            ║"
    log_error "╚════════════════════════════════════════╝"
    log_error ""

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --force)
                FORCE_ROLLBACK=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --preserve-logs)
                if [[ $2 == "false" ]]; then
                    PRESERVE_LOGS=false
                fi
                shift 2
                ;;
            --help)
                usage
                ;;
            *)
                log_error "Unknown option: $1"
                usage
                ;;
        esac
    done

    log_info "Dry-Run: $DRY_RUN"
    log_info "Force Rollback: $FORCE_ROLLBACK"
    log_info "Preserve Logs: $PRESERVE_LOGS"

    # Confirmation
    if [[ "$FORCE_ROLLBACK" != true && "$DRY_RUN" != true ]]; then
        log_warn "This will rollback the canary deployment to v1-stable"
        read -p "Are you sure? (type 'yes' to confirm): " confirm
        if [[ "$confirm" != "yes" ]]; then
            log_info "Rollback cancelled"
            exit 0
        fi
    fi

    # Pre-rollback checks
    check_prerequisites
    check_stable_readiness

    local start_time=$(date +%s)

    # Collect diagnostics before deletion
    if [[ "$PRESERVE_LOGS" == true ]]; then
        local diag_dir=$(collect_canary_diagnostics)
    fi

    # Execute rollback
    stop_canary || exit 1
    reset_nginx_config || exit 1
    scale_stable || exit 1

    # Verify rollback
    if verify_rollback; then
        local end_time=$(date +%s)
        local duration=$((end_time - start_time))

        log_success ""
        log_success "╔════════════════════════════════════════╗"
        log_success "║   ROLLBACK COMPLETE                    ║"
        log_success "║   All traffic on v1-stable             ║"
        log_success "╚════════════════════════════════════════╝"
        log_success ""
        log_success "Rollback completed in ${duration}s"

        if [[ "$PRESERVE_LOGS" == true ]]; then
            log_info "Diagnostics saved to: $diag_dir"
            generate_rollback_report
        fi
    else
        log_error "Rollback verification failed"
        log_error "Manual intervention may be required"
        exit 1
    fi
}

# Run main function
main "$@"

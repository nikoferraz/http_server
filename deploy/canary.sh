#!/bin/bash

###############################################################################
# Canary Deployment Script for HTTP Server v2-optimized
#
# This script orchestrates a safe, phased rollout of the optimized HTTP server
# with continuous monitoring and automatic rollback on failure.
#
# Usage: ./canary.sh [--dry-run] [--skip-monitoring] [--registry REGISTRY]
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
REGISTRY="${REGISTRY:-your-registry}"
DOCKER_IMAGE_V2="${REGISTRY}/http-server:v2-optimized"
DRY_RUN=false
SKIP_MONITORING=false
ROLLBACK_ON_ERROR=true

# Deployment phases
PHASE_1_DURATION=1800        # 30 minutes
PHASE_2_DURATION=3600        # 1 hour
PHASE_3_DURATION=10800       # 3 hours
MONITORING_INTERVAL=30       # Check metrics every 30 seconds

# Thresholds for rollback
ERROR_RATE_THRESHOLD=0.005   # 0.5%
LATENCY_INCREASE_THRESHOLD=0.20  # 20% increase
MEMORY_GROWTH_THRESHOLD=52428800 # 50MB per 15 minutes

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
    --dry-run              Show what would be deployed without making changes
    --skip-monitoring      Skip continuous monitoring (for testing)
    --registry REGISTRY    Docker registry URL (default: your-registry)
    --help                 Show this help message

EXAMPLES:
    # Full canary deployment with monitoring
    ./canary.sh

    # Dry run to see deployment plan
    ./canary.sh --dry-run

    # Deploy with custom registry
    ./canary.sh --registry docker.io/mycompany
EOF
    exit 0
}

###############################################################################
# Pre-deployment Checks
###############################################################################

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check kubectl
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl not found. Please install kubectl."
        exit 1
    fi

    # Check docker (for image building)
    if ! command -v docker &> /dev/null; then
        log_warn "docker not found. Assuming image is already built and pushed."
    fi

    # Check kubernetes connection
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster. Check kubeconfig."
        exit 1
    fi

    # Check namespace exists
    if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
        log_info "Creating namespace: $NAMESPACE"
        kubectl create namespace "$NAMESPACE"
    fi

    log_success "All prerequisites met"
}

validate_deployment() {
    log_info "Validating deployment manifests..."

    for manifest in "$PROJECT_DIR"/k8s/*.yaml; do
        if ! kubectl apply --dry-run=client -f "$manifest" &> /dev/null; then
            log_error "Invalid manifest: $manifest"
            exit 1
        fi
    done

    log_success "All manifests validated"
}

###############################################################################
# Docker Image Building
###############################################################################

build_and_push_image() {
    local image=$1

    log_info "Building Docker image: $image"

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would build: docker build -t $image ."
        return
    fi

    if ! command -v docker &> /dev/null; then
        log_warn "Docker not found. Assuming image is pre-built."
        return
    fi

    cd "$PROJECT_DIR"

    if docker build -t "$image" . ; then
        log_success "Image built successfully"
    else
        log_error "Failed to build image"
        exit 1
    fi

    log_info "Pushing image to registry..."
    if docker push "$image" ; then
        log_success "Image pushed successfully"
    else
        log_error "Failed to push image"
        exit 1
    fi
}

###############################################################################
# Deployment Functions
###############################################################################

deploy_stable() {
    log_info "Deploying stable version (v1)..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would deploy stable with:"
        log_info "[DRY-RUN] kubectl apply -f k8s/namespace.yaml"
        log_info "[DRY-RUN] kubectl apply -f k8s/configmap.yaml"
        log_info "[DRY-RUN] kubectl apply -f k8s/deployment-stable.yaml"
        return
    fi

    kubectl apply -f "$PROJECT_DIR"/k8s/namespace.yaml
    kubectl apply -f "$PROJECT_DIR"/k8s/configmap.yaml
    kubectl apply -f "$PROJECT_DIR"/k8s/deployment-stable.yaml

    # Wait for stable deployment to be ready
    log_info "Waiting for stable deployment to be ready..."
    kubectl rollout status deployment/http-server-stable \
        -n "$NAMESPACE" --timeout=5m

    log_success "Stable deployment ready"
}

deploy_canary() {
    local replicas=${1:-1}

    log_info "Deploying canary version (v2-optimized) with $replicas replicas..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] kubectl apply -f k8s/deployment-canary.yaml"
        log_info "[DRY-RUN] kubectl scale deployment/http-server-canary --replicas=$replicas -n $NAMESPACE"
        return
    fi

    kubectl apply -f "$PROJECT_DIR"/k8s/deployment-canary.yaml

    # Scale canary deployment
    kubectl scale deployment/http-server-canary \
        --replicas="$replicas" \
        -n "$NAMESPACE"

    # Wait for canary to be ready
    log_info "Waiting for canary deployment to be ready..."
    kubectl rollout status deployment/http-server-canary \
        -n "$NAMESPACE" --timeout=5m

    log_success "Canary deployment ready with $replicas replicas"
}

update_traffic_weight() {
    local weight=$1

    log_info "Updating canary traffic weight to ${weight}%..."

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would update nginx config with canary weight: ${weight}%"
        return
    fi

    # Update the ConfigMap with new weight
    kubectl patch configmap nginx-config \
        -n "$NAMESPACE" \
        -p "{\"data\":{\"canary_weight\":\"$weight\"}}" || true

    log_success "Traffic weight updated to ${weight}%"
}

###############################################################################
# Monitoring and Validation
###############################################################################

get_metric() {
    local query=$1

    kubectl exec -n "$NAMESPACE" \
        -it deployment/prometheus -- \
        sh -c "curl -s 'http://localhost:9090/api/v1/query?query=$query' | jq '.data.result[0].value[1]' 2>/dev/null" || echo "0"
}

check_canary_health() {
    local phase=$1

    log_info "Checking canary health (Phase $phase)..."

    # Check pod status
    local ready_replicas=$(kubectl get deployment/http-server-canary \
        -n "$NAMESPACE" \
        -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")

    if [[ "$ready_replicas" -lt 1 ]]; then
        log_error "No ready canary replicas. Deployment failed."
        return 1
    fi

    log_info "Canary pods ready: $ready_replicas"

    # Get sample metrics from prometheus
    if [[ "$SKIP_MONITORING" == true ]]; then
        log_warn "Monitoring skipped. Assuming canary is healthy."
        return 0
    fi

    # Check error rate
    local error_rate=$(get_metric 'rate(http_requests_total{version="v2-optimized",status=~"5.."}[5m])')
    log_info "Error rate: $error_rate"

    if (( $(echo "$error_rate > 0.005" | bc -l 2>/dev/null || echo 0) )); then
        log_error "Error rate exceeds threshold: $error_rate > 0.005"
        return 1
    fi

    log_success "Canary health check passed"
    return 0
}

monitor_canary_phase() {
    local phase=$1
    local duration=$2

    log_info "Monitoring Phase $phase for ${duration}s..."

    local start_time=$(date +%s)
    local errors=0

    while true; do
        local current_time=$(date +%s)
        local elapsed=$((current_time - start_time))

        if [[ $elapsed -ge $duration ]]; then
            log_success "Phase $phase monitoring complete"
            return 0
        fi

        if ! check_canary_health "$phase"; then
            errors=$((errors + 1))
            log_error "Health check failed (attempt $errors)"

            if [[ $errors -ge 2 ]]; then
                log_error "Canary failed health checks. Initiating rollback."
                return 1
            fi
        else
            errors=0
        fi

        # Show progress
        local remaining=$((duration - elapsed))
        log_info "Phase $phase progress: $elapsed/$duration seconds (${remaining}s remaining)"

        sleep "$MONITORING_INTERVAL"
    done
}

###############################################################################
# Rollback Functions
###############################################################################

rollback_canary() {
    log_error "ROLLING BACK CANARY DEPLOYMENT"

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would rollback:"
        log_info "[DRY-RUN] kubectl delete deployment/http-server-canary -n $NAMESPACE"
        return 0
    fi

    # Immediately delete canary
    kubectl delete deployment/http-server-canary -n "$NAMESPACE" --ignore-not-found=true

    # Reset traffic weight to 0%
    kubectl patch configmap nginx-config \
        -n "$NAMESPACE" \
        -p '{"data":{"canary_weight":"0"}}' || true

    log_success "Rollback complete. All traffic on stable (v1)"
}

###############################################################################
# Main Canary Deployment Workflow
###############################################################################

phase_1_canary() {
    log_info "===== PHASE 1: CANARY (1%, 30 min) ====="

    deploy_canary 1

    if ! monitor_canary_phase 1 "$PHASE_1_DURATION"; then
        rollback_canary
        return 1
    fi

    log_success "Phase 1 complete. Ready for Phase 2."
    return 0
}

phase_2_extended_canary() {
    log_info "===== PHASE 2: EXTENDED CANARY (5%, 1 hour) ====="

    # Scale up to 3 replicas
    kubectl scale deployment/http-server-canary \
        --replicas=3 \
        -n "$NAMESPACE"

    # Update traffic to 5%
    update_traffic_weight 5

    if ! monitor_canary_phase 2 "$PHASE_2_DURATION"; then
        rollback_canary
        return 1
    fi

    log_success "Phase 2 complete. Ready for Phase 3."
    return 0
}

phase_3_progressive_rollout() {
    log_info "===== PHASE 3: PROGRESSIVE ROLLOUT (25%, 3 hours) ====="

    # Scale up to 5 replicas
    kubectl scale deployment/http-server-canary \
        --replicas=5 \
        -n "$NAMESPACE"

    # Update traffic to 25%
    update_traffic_weight 25

    if ! monitor_canary_phase 3 "$PHASE_3_DURATION"; then
        rollback_canary
        return 1
    fi

    log_success "Phase 3 complete. Ready for full rollout."
    return 0
}

phase_4_full_rollout() {
    log_info "===== PHASE 4: FULL ROLLOUT (100%) ====="

    if [[ "$DRY_RUN" == true ]]; then
        log_info "[DRY-RUN] Would fully rollout to v2-optimized"
        return 0
    fi

    # Update traffic to 100%
    update_traffic_weight 100

    # Scale down stable
    kubectl scale deployment/http-server-stable \
        --replicas=0 \
        -n "$NAMESPACE"

    log_success "Full rollout complete. All traffic on v2-optimized."
    return 0
}

###############################################################################
# Main Entry Point
###############################################################################

main() {
    log_info "Starting canary deployment for HTTP Server v2-optimized"
    log_info "Registry: $REGISTRY"
    log_info "Dry-Run: $DRY_RUN"
    log_info "Skip Monitoring: $SKIP_MONITORING"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            --skip-monitoring)
                SKIP_MONITORING=true
                shift
                ;;
            --registry)
                REGISTRY=$2
                DOCKER_IMAGE_V2="${REGISTRY}/http-server:v2-optimized"
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

    # Pre-deployment checks
    check_prerequisites
    validate_deployment

    # Build and push image
    build_and_push_image "$DOCKER_IMAGE_V2"

    # Deploy stable if not already running
    if ! kubectl get deployment/http-server-stable -n "$NAMESPACE" &> /dev/null; then
        deploy_stable
    else
        log_info "Stable deployment already exists"
    fi

    # Execute canary deployment phases
    phase_1_canary || exit 1
    phase_2_extended_canary || exit 1
    phase_3_progressive_rollout || exit 1

    # Confirm before full rollout
    log_warn "About to perform full rollout to v2-optimized"
    if [[ "$DRY_RUN" != true ]]; then
        read -p "Continue with full rollout? (yes/no): " confirm
        if [[ "$confirm" != "yes" ]]; then
            log_info "Rollout cancelled by user"
            exit 0
        fi
    fi

    phase_4_full_rollout || exit 1

    log_success "===== CANARY DEPLOYMENT COMPLETE ====="
    log_info "Performing post-deployment validation..."
    log_info "Monitor dashboard: https://grafana.example.com/d/http-server"
    log_info "Keep stable version ready for 24 hours for fast rollback"
}

# Run main function
main "$@"

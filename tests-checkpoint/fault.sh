#!/bin/bash
#
# Stress-test the custom Load Balancer to trigger AutoScaler scale-out.
#
# Usage:
#   bash tests-checkpoint/stress-test.sh
#   CNV_HOST=<lb-dns>:8080 bash tests-checkpoint/stress-test.sh

SCRIPT_DIR="$(dirname "$(realpath "$0")")"
source "$SCRIPT_DIR/../scripts/config.sh"

if [ -n "${CNV_HOST:-}" ]; then
    HOST="$CNV_HOST"
else
    LB_FILE="$SCRIPT_DIR/../scripts/lb/lbinstance.dns"

    if [ ! -f "$LB_FILE" ]; then
        echo "ERROR: Could not find LB instance DNS."
        echo "Expected file: $LB_FILE"
        echo "Run scripts/lb/launch-lb-vm.sh first."
        exit 1
    fi

    LB_DNS=$(tr -d '\n\r' < "$LB_FILE")

    if [ -z "$LB_DNS" ]; then
        echo "ERROR: LB DNS file exists but is empty:"
        echo "  $LB_FILE"
        exit 1
    fi

    if ! host "$LB_DNS" >/dev/null 2>&1; then
        echo "ERROR: LB DNS does not resolve:"
        echo "  $LB_DNS"
        echo "The file may be stale. Relaunch the load balancer VM."
        exit 1
    fi

    HOST="${LB_DNS}:8080"
    echo "Auto-detected LB host: $HOST"
fi

DURATION="${DURATION:-120}"   # seconds to keep firing (4 min — enough to see scale-up)
WAVE_INTERVAL="${WAVE_INTERVAL:-30}"  # seconds between waves — keep many requests in-flight


fire_wave() {
    ## XL
    curl -s "http://$HOST/grayscott?size=2048&maxIterations=12000&f=0.120&k=0.065&stopOnExtinction=true&seedMode=center" -o /dev/null &  
    curl -s "http://$HOST/grayscott?size=2048&maxIterations=12000&f=0.120&k=0.065&stopOnExtinction=true&seedMode=center" -o /dev/null &  
}

END_TIME=$(( $(date +%s) + DURATION ))
WAVE=1


fire_wave

echo "Check worker count in the LB log for '[AutoScaler] Scale-up triggered' messages."

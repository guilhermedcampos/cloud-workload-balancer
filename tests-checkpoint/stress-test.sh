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
WAVE_INTERVAL="${WAVE_INTERVAL:-15}"  # seconds between waves — keep many requests in-flight

echo "Stress test → $HOST for ${DURATION}s, wave every ${WAVE_INTERVAL}s"
echo "Watch the LB log in another terminal:"
echo "  ssh -i \$AWS_EC2_SSH_KEYPAR_PATH ec2-user@\$(cat scripts/lb/lbinstance.dns) 'tail -f /home/ec2-user/loadbalancer.log'"
echo ""

fire_wave() {
        # XS-0
    curl -s "http://$HOST/grayscott?size=128&maxIterations=100&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center" -o /dev/null &

    # XS
    curl -s "http://$HOST/grayscott?size=256&maxIterations=100&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center" -o /dev/null &

    # S-
    curl -s "http://$HOST/grayscott?size=256&maxIterations=300&f=0.040&k=0.060&stopOnExtinction=true&seedMode=ring" -o /dev/null &

    # S
    curl -s "http://$HOST/grayscott?size=512&maxIterations=500&f=0.040&k=0.060&stopOnExtinction=false&seedMode=ring" -o /dev/null &

    # S+
    curl -s "http://$HOST/grayscott?size=512&maxIterations=1000&f=0.050&k=0.060&stopOnExtinction=false&seedMode=stripe" -o /dev/null &

    # M-
    curl -s "http://$HOST/grayscott?size=768&maxIterations=1500&f=0.055&k=0.061&stopOnExtinction=false&seedMode=stripe" -o /dev/null &

    # M
    curl -s "http://$HOST/grayscott?size=1024&maxIterations=2000&f=0.060&k=0.061&stopOnExtinction=false&seedMode=stripe" -o /dev/null &

    # M+
    curl -s "http://$HOST/grayscott?size=1024&maxIterations=5000&f=0.070&k=0.062&stopOnExtinction=false&seedMode=ring" -o /dev/null &

    # L
    curl -s "http://$HOST/grayscott?size=1536&maxIterations=8000&f=0.090&k=0.063&stopOnExtinction=false&seedMode=center" -o /dev/null &

    # XL
    curl -s "http://$HOST/grayscott?size=2048&maxIterations=12000&f=0.120&k=0.065&stopOnExtinction=true&seedMode=center" -o /dev/null &  
}

END_TIME=$(( $(date +%s) + DURATION ))
WAVE=1

while [ "$(date +%s)" -lt "$END_TIME" ]; do
    echo "[wave $WAVE] Firing 8 requests at $(date +%H:%M:%S)"
    fire_wave
    WAVE=$(( WAVE + 1 ))
    sleep "$WAVE_INTERVAL"
done

echo "Waiting for last wave to complete..."
wait
echo ""
echo "Stress test complete."
echo "Check worker count in the LB log for '[AutoScaler] Scale-up triggered' messages."

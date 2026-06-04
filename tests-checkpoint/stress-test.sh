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
    LB_DNS=$(cat "$SCRIPT_DIR/../scripts/lb/lbinstance.dns" 2>/dev/null)
    if [ -z "$LB_DNS" ]; then
        echo "ERROR: Could not find LB instance DNS. Run scripts/lb/launch-lb-vm.sh first."
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
    curl -s "http://$HOST/fractals?w=4000&h=2000&iterations=1000" -o /dev/null &
    curl -s "http://$HOST/fractals?w=3000&h=3000&iterations=800"  -o /dev/null &
    curl -s "http://$HOST/fractals?w=5000&h=2500&iterations=1200" -o /dev/null &
    curl -s "http://$HOST/fractals?w=2000&h=2000&iterations=2000" -o /dev/null &


    curl -s "http://$HOST/grayscott?size=512&maxIter=5000&f=0.055&k=0.062&seedMode=random" -o /dev/null &
    curl -s "http://$HOST/grayscott?size=256&maxIter=8000&f=0.035&k=0.065&seedMode=random" -o /dev/null &
    curl -s "http://$HOST/grayscott?size=400&maxIter=6000&f=0.045&k=0.060&seedMode=random" -o /dev/null &

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

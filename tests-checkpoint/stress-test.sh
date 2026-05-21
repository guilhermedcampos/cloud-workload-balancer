#!/bin/bash
#
# Lower CloudWatch alarm thresholds and hammer all workloads in parallel
# to trigger the Auto Scaling Group scale-out.
#
# Usage (CNV_HOST is auto-detected from the ELB, or override manually):
#   bash tests-checkpoint/stress-test.sh
#   CNV_HOST=<custom-host>:80 bash tests-checkpoint/stress-test.sh

SCRIPT_DIR="$(dirname "$(realpath "$0")")"
source "$SCRIPT_DIR/../scripts/config.sh"

if [ -n "$CNV_HOST" ]; then
    HOST="$CNV_HOST"
else
    ELB_DNS=$(aws elb describe-load-balancers \
        --load-balancer-names CNV-LoadBalancer \
        --query "LoadBalancerDescriptions[0].DNSName" \
        --output text 2>/dev/null)
    if [ -z "$ELB_DNS" ] || [ "$ELB_DNS" = "None" ]; then
        echo "ERROR: Could not find CNV-LoadBalancer. Is the deployment running?"
        exit 1
    fi
    HOST="${ELB_DNS}:80"
    echo "Auto-detected ELB host: $HOST"
fi
ROUNDS="${ROUNDS:-5}"       # how many full test rounds to fire
PARALLEL="${PARALLEL:-4}"   # concurrent requests per round

# ── 1. Lower alarm thresholds so scale-out triggers around 5% CPU ────────────

SCALE_OUT_ARN=$(aws autoscaling describe-policies \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-names CNV-ScaleOut \
    --query "ScalingPolicies[0].PolicyARN" --output text)

SCALE_IN_ARN=$(aws autoscaling describe-policies \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-names CNV-ScaleIn \
    --query "ScalingPolicies[0].PolicyARN" --output text)

echo "Updating CNV-HighCPU threshold → 5% (scale-out)"
aws cloudwatch put-metric-alarm \
    --alarm-name CNV-HighCPU \
    --metric-name CPUUtilization \
    --namespace AWS/EC2 \
    --statistic Average \
    --period 60 \
    --threshold 5 \
    --comparison-operator GreaterThanThreshold \
    --evaluation-periods 1 \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --alarm-actions "$SCALE_OUT_ARN"

echo "Updating CNV-LowCPU threshold → 2% (scale-in)"
aws cloudwatch put-metric-alarm \
    --alarm-name CNV-LowCPU \
    --metric-name CPUUtilization \
    --namespace AWS/EC2 \
    --statistic Average \
    --period 60 \
    --threshold 2 \
    --comparison-operator LessThanThreshold \
    --evaluation-periods 2 \
    --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
    --alarm-actions "$SCALE_IN_ARN"

echo "Alarms updated. Starting stress test against $HOST ..."
echo ""

# ── 2. Stress test: fire all workloads repeatedly in parallel ─────────────────

run_round() {
    local round=$1
    echo "=== Round $round ==="

    # Fractals — large image
    curl -s "http://$HOST/fractals?w=4000&h=2000&iterations=1000" -o /dev/null &
    curl -s "http://$HOST/fractals?w=3000&h=3000&iterations=800"  -o /dev/null &
    curl -s "http://$HOST/fractals?w=5000&h=2500&iterations=1200" -o /dev/null &
    curl -s "http://$HOST/fractals?w=2000&h=2000&iterations=2000" -o /dev/null &

    # GrayScott — heavy simulation
    curl -s "http://$HOST/grayscott?size=512&maxIter=5000&f=0.055&k=0.062&seedMode=random" -o /dev/null &
    curl -s "http://$HOST/grayscott?size=256&maxIter=8000&f=0.035&k=0.065&seedMode=random" -o /dev/null &
    curl -s "http://$HOST/grayscott?size=400&maxIter=6000&f=0.045&k=0.060&seedMode=random" -o /dev/null &

    # DNA — genome search
    curl -s "http://$HOST/dna?g=data/genome-escherichia-coli-25k.fasta&q=data/sars-10k.fasta&gs=25000&s=30&e=5" -o /dev/null &
    curl -s "http://$HOST/dna?g=data/genome-klebsiella-pneumoniae-20k.fasta&q=data/sars-10k.fasta&gs=20000&s=30&e=5" -o /dev/null &

    wait
    echo "Round $round done."
}

for i in $(seq 1 "$ROUNDS"); do
    run_round "$i"
done

echo ""
echo "Stress test complete. Check ASG activity:"
aws autoscaling describe-scaling-activities \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --max-items 5 \
    --query "Activities[*].{Status:StatusCode,Cause:Cause,Time:StartTime}" \
    --output table

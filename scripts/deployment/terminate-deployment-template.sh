#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Step 1: delete CloudWatch alarms (no-op if already gone).
aws cloudwatch delete-alarms \
	--alarm-names CNV-HighCPU CNV-LowCPU 2>/dev/null || true

# Step 2: scale down and delete Auto Scaling group (no-op if already gone).
aws autoscaling update-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--min-size 0 \
	--max-size 0 \
	--desired-capacity 0 2>/dev/null || true

sleep 30

aws autoscaling delete-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--force-delete 2>/dev/null || true

# Step 3: delete Launch Template.
aws ec2 delete-launch-template \
	--launch-template-name CNV-LaunchTemplate 2>/dev/null || true

# Step 4: terminate Load Balancer instance.
if [ -f "$DIR/lb/lbinstance.id" ]; then
    LB_ID=$(cat "$DIR/lb/lbinstance.id")

    aws ec2 terminate-instances \
        --instance-ids "$LB_ID" >/dev/null 2>&1 || true

    echo "LB instance $LB_ID termination requested."
fi

# Step 5: delete DynamoDB metrics tables.
TABLES=(
    "CNV-Metrics-Fractals"
    "CNV-Metrics-DNA"
    "CNV-Metrics-GrayScott"
)

for T in "${TABLES[@]}"; do
    aws dynamodb delete-table --table-name "$T" 2>/dev/null && \
        echo "DynamoDB table $T deleted." || \
        echo "DynamoDB table $T not found, skipping."
done

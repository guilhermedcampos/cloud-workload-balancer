#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Step 1: delete CloudWatch alarms.
aws cloudwatch delete-alarms \
	--alarm-names CNV-HighCPU CNV-LowCPU

# Step 2: scale down and delete Auto Scaling group.
aws autoscaling update-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--min-size 0 \
	--max-size 0 \
	--desired-capacity 0

sleep 60

aws autoscaling delete-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--force-delete

# Step 3: delete Launch Template.
aws ec2 delete-launch-template \
	--launch-template-name CNV-LaunchTemplate

# Step 4: delete Load Balancer.
aws elb delete-load-balancer \
	--load-balancer-name CNV-LoadBalancer

# Step 5: delete DynamoDB metrics table.
aws dynamodb delete-table --table-name CNV-Metrics 2>/dev/null && \
    echo "DynamoDB table CNV-Metrics deleted." || \
    echo "DynamoDB table CNV-Metrics not found, skipping."


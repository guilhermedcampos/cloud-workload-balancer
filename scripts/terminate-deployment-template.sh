#!/bin/bash

source config.sh

# Step 1: update and delete Auto Scaling group.
aws autoscaling update-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--min-size 0 \
  	--max-size 0 \
  	--desired-capacity 0

# Optional: wait for instance termination before deletion
sleep 60

aws autoscaling delete-auto-scaling-group \
  	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--force-delete

# Step 2: delete Launch Template.
aws ec2 delete-launch-template \
  	--launch-template-name CNV-LaunchTemplate

# Step 3: delete Load Balancer.
aws elb delete-load-balancer \
  	--load-balancer-name CNV-LoadBalancer

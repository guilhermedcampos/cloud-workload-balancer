#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Create load balancer and configure health check.
aws elb create-load-balancer \
	--load-balancer-name CNV-LoadBalancer \
	--listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
	--availability-zones us-east-1a

aws elb configure-health-check \
	--load-balancer-name CNV-LoadBalancer \
	--health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=2,Timeout=5

# Create launch template.
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat "$DIR/../ami/image.id")\",
		\"InstanceType\": \"t3.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"Monitoring\": {\"Enabled\": true}
}"

# Create auto scaling group.
aws autoscaling create-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--launch-template LaunchTemplateName=CNV-LaunchTemplate,Version=1 \
	--load-balancer-names CNV-LoadBalancer \
	--availability-zones us-east-1a \
	--health-check-type ELB \
	--health-check-grace-period 60 \
	--min-size 1 \
	--max-size 3 \
	--desired-capacity 1

# Create scaling policies.
SCALE_OUT_ARN=$(aws autoscaling put-scaling-policy \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--policy-name CNV-ScaleOut \
	--scaling-adjustment 1 \
	--adjustment-type ChangeInCapacity \
	--query PolicyARN --output text)

SCALE_IN_ARN=$(aws autoscaling put-scaling-policy \
	--auto-scaling-group-name CNV-AutoScalingGroup \
	--policy-name CNV-ScaleIn \
	--scaling-adjustment -1 \
	--adjustment-type ChangeInCapacity \
	--query PolicyARN --output text)

# Trigger scale-out when average CPU > 10% for 2 consecutive minutes.
aws cloudwatch put-metric-alarm \
	--alarm-name CNV-HighCPU \
	--metric-name CPUUtilization \
	--namespace AWS/EC2 \
	--statistic Average \
	--period 60 \
	--threshold 10 \
	--comparison-operator GreaterThanThreshold \
	--evaluation-periods 2 \
	--dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
	--alarm-actions $SCALE_OUT_ARN

# Trigger scale-in when average CPU < 5% for 2 consecutive minutes.
aws cloudwatch put-metric-alarm \
	--alarm-name CNV-LowCPU \
	--metric-name CPUUtilization \
	--namespace AWS/EC2 \
	--statistic Average \
	--period 60 \
	--threshold 5 \
	--comparison-operator LessThanThreshold \
	--evaluation-periods 2 \
	--dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
	--alarm-actions $SCALE_IN_ARN

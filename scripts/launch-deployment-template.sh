#!/bin/bash

# TODO edit: Increase max-size, adjust desired-capacity, add scaling policies and alarms
source config.sh

# Create load balancer and configure health check.
aws elb create-load-balancer \
	--load-balancer-name CNV-LoadBalancer \
	--listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
  	--availability-zones us-east-1a

aws elb configure-health-check \
  	--load-balancer-name CNV-LoadBalancer \
  	--health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=10,Timeout=5

# Create Launch Template
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat image.id)\",
		\"InstanceType\": \"t3.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"Monitoring\": {\"Enabled\": true}
}"

# Create auto scaling group with launch template.
aws autoscaling create-auto-scaling-group \
  	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--launch-template LaunchTemplateName=CNV-LaunchTemplate,Version=1 \
  	--load-balancer-names CNV-LoadBalancer \
  	--availability-zones us-east-1a \
  	--health-check-type ELB \
  	--health-check-grace-period 60 \
  	--min-size 1 \  
  	--max-size 1 \
  	--desired-capacity 1

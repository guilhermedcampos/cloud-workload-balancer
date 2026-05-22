#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Create DynamoDB metrics table (idempotent — skips if already exists).
EXISTING=$(aws dynamodb describe-table --table-name CNV-Metrics --query "Table.TableStatus" --output text 2>/dev/null)
if [ -z "$EXISTING" ]; then
    echo "Creating DynamoDB table CNV-Metrics..."
    aws dynamodb create-table \
        --table-name CNV-Metrics \
        --attribute-definitions AttributeName=requestId,AttributeType=S \
        --key-schema AttributeName=requestId,KeyType=HASH \
        --billing-mode PAY_PER_REQUEST
    aws dynamodb wait table-exists --table-name CNV-Metrics
    echo "Table CNV-Metrics is ready."
else
    echo "DynamoDB table CNV-Metrics already exists ($EXISTING), skipping."
fi

# Create load balancer and configure health check.
aws elb create-load-balancer \
	--load-balancer-name CNV-LoadBalancer \
	--listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
	--availability-zones us-east-1a

aws elb configure-health-check \
	--load-balancer-name CNV-LoadBalancer \
	--health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=2,Timeout=5

# Encode AWS credentials as userdata so each new instance gets them on boot.
# The webserver.service reads /home/ec2-user/aws.env via EnvironmentFile.
USERDATA=$(base64 -w 0 << EOF
#!/bin/bash
cat > /home/ec2-user/aws.env << 'ENVEOF'
AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
AWS_SESSION_TOKEN=$AWS_SESSION_TOKEN
AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION
DYNAMODB_TABLE=CNV-Metrics
ENVEOF
chown ec2-user:ec2-user /home/ec2-user/aws.env
chmod 600 /home/ec2-user/aws.env
systemctl restart webserver
EOF
)

# Create launch template.
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat "$DIR/../ami/image.id")\",
		\"InstanceType\": \"t3.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"Monitoring\": {\"Enabled\": true},
		\"UserData\": \"$USERDATA\"
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

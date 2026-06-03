#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Create DynamoDB metrics table with GSI for cost lookup (idempotent).
EXISTING=$(aws dynamodb describe-table --table-name CNV-Metrics --query "Table.TableStatus" --output text 2>/dev/null)
if [ -z "$EXISTING" ]; then
    echo "Creating DynamoDB table CNV-Metrics..."
    aws dynamodb create-table \
        --table-name CNV-Metrics \
        --attribute-definitions \
            AttributeName=requestId,AttributeType=S \
            AttributeName=workloadBucketKey,AttributeType=S \
            AttributeName=tsEpochMs,AttributeType=N \
        --key-schema AttributeName=requestId,KeyType=HASH \
        --global-secondary-indexes '[{
            "IndexName": "workloadBucketKey-tsEpochMs-index",
            "KeySchema": [
                {"AttributeName": "workloadBucketKey", "KeyType": "HASH"},
                {"AttributeName": "tsEpochMs", "KeyType": "RANGE"}
            ],
            "Projection": {"ProjectionType": "ALL"}
        }]' \
        --billing-mode PAY_PER_REQUEST
    aws dynamodb wait table-exists --table-name CNV-Metrics
    echo "Table CNV-Metrics is ready."
else
    echo "DynamoDB table CNV-Metrics already exists ($EXISTING), skipping."
fi

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

# Create launch template (used by the custom AutoScaler to launch new workers).
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

echo "Launch template CNV-LaunchTemplate created."
echo "The custom Load Balancer's AutoScaler will manage worker instances."
echo "Start the LB instance separately with: scripts/lb/launch-lb-vm.sh"

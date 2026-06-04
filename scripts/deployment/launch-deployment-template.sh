#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

echo "Creating DynamoDB tables for each workload..."
TABLES=("CNV-Metrics-Fractals" "CNV-Metrics-DNA" "CNV-Metrics-GrayScott")
for T in "${TABLES[@]}"; do
    EXISTING=$(aws dynamodb describe-table --table-name "$T" --query "Table.TableStatus" --output text 2>/dev/null)
    if [ -z "$EXISTING" ]; then
        echo "Creating table $T..."
        aws dynamodb create-table \
            --table-name "$T" \
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
        aws dynamodb wait table-exists --table-name "$T"
        echo "Table $T created and ready."
    else
        echo "Table $T already exists"
    fi
done
echo "DynamoDB setup complete."

# Encode AWS credentials as userdata so each new instance gets them on boot.
# The webserver.service reads /home/ec2-user/aws.env via EnvironmentFile.
USERDATA=$(base64 -w 0 << EOF
#!/bin/bash
cat > /home/ec2-user/aws.env << 'ENVEOF'
AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
AWS_SESSION_TOKEN=$AWS_SESSION_TOKEN
AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION
DYNAMODB_TABLE_FRACTALS=CNV-Metrics-Fractals
DYNAMODB_TABLE_DNA=CNV-Metrics-DNA
DYNAMODB_TABLE_GRAYSCOTT=CNV-Metrics-GrayScott
ENVEOF
chown ec2-user:ec2-user /home/ec2-user/aws.env
chmod 600 /home/ec2-user/aws.env
systemctl restart webserver
EOF
)

aws ec2 create-launch-template \
    --launch-template-name CNV-LaunchTemplate \
    --version-description "v2-multi-table" \
    --launch-template-data "{
        \"ImageId\": \"$(cat "$DIR/../ami/image.id")\",
        \"InstanceType\": \"t3.micro\",
        \"KeyName\": \"$AWS_KEYPAIR_NAME\",
        \"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
        \"Monitoring\": {\"Enabled\": true},
        \"UserData\": \"$USERDATA\"
    }"

echo "Launch template CNV-LaunchTemplate created with 3 DynamoDB tables."
echo "AutoScaler will manage workers per workload table."
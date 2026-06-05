#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

DIR="$(dirname "$(realpath "$0")")"

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat "$DIR/lbinstance.dns") \
    "sudo yum update -y && sudo yum install java-11-amazon-corretto-devel.x86_64 -y && sudo yum install jq -y"

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    "$DIR/../../loadbalancer/target/loadbalancer-1.0-jar-with-dependencies.jar" \
    ec2-user@$(cat "$DIR/lbinstance.dns"):loadbalancer.jar

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    "$DIR/start-loadbalancer.sh" \
    ec2-user@$(cat "$DIR/lbinstance.dns"):start-loadbalancer.sh

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    "$DIR/loadbalancer.service" \
    ec2-user@$(cat "$DIR/lbinstance.dns"):loadbalancer.service

LB_ID=$(cat "$DIR/lbinstance.id")

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat "$DIR/lbinstance.dns") "
cat > /home/ec2-user/lb.env <<EOF
LB_INSTANCE_ID=$LB_ID
AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION
AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY
AWS_SESSION_TOKEN=$AWS_SESSION_TOKEN
AWS_AMI_ID=$(cat "$DIR/../ami/image.id")
AWS_KEYPAIR_NAME=$AWS_KEYPAIR_NAME
AWS_SECURITY_GROUP=$AWS_SECURITY_GROUP
DYNAMODB_TABLE_FRACTALS=CNV-Metrics-Fractals
DYNAMODB_TABLE_DNA=CNV-Metrics-DNA
DYNAMODB_TABLE_GRAYSCOTT=CNV-Metrics-GrayScott
DYNAMODB_BUCKET_INDEX=bucketKey-tsEpochMs-index
EOF
chmod 600 /home/ec2-user/lb.env
"

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat "$DIR/lbinstance.dns") "
    chmod +x /home/ec2-user/start-loadbalancer.sh &&
    sudo mv /home/ec2-user/loadbalancer.service /etc/systemd/system/loadbalancer.service &&
    sudo systemctl daemon-reload &&
    sudo systemctl enable loadbalancer.service &&
    sudo systemctl start loadbalancer.service
"

echo "LB VM setup complete"
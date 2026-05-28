#!/bin/bash

source config.sh

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat lbinstance.dns) \
    "sudo yum update -y && sudo yum install java-11-amazon-corretto-devel.x86_64 -y && sudo yum install jq -y"

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    ../loadbalancer/target/loadbalancer-1.0-jar-with-dependencies.jar \
    ec2-user@$(cat lbinstance.dns):loadbalancer.jar

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    start-loadbalancer.sh \
    ec2-user@$(cat lbinstance.dns):start-loadbalancer.sh

scp -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" \
    loadbalancer.service \
    ec2-user@$(cat lbinstance.dns):loadbalancer.service

LB_ID=$(cat lbinstance.id)

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat lbinstance.dns) "
cat > /home/ec2-user/lb.env <<EOF
LB_INSTANCE_ID=$LB_ID
AWS_DEFAULT_REGION=$AWS_DEFAULT_REGION
EOF
chmod 600 /home/ec2-user/lb.env
"

ssh -o StrictHostKeyChecking=no -i "$AWS_EC2_SSH_KEYPAR_PATH" ec2-user@$(cat lbinstance.dns) "
    chmod +x /home/ec2-user/start-loadbalancer.sh &&
    sudo mv /home/ec2-user/loadbalancer.service /etc/systemd/system/loadbalancer.service &&
    sudo systemctl daemon-reload &&
    sudo systemctl enable loadbalancer.service &&
    sudo systemctl start loadbalancer.service
"

echo "LB VM setup complete"
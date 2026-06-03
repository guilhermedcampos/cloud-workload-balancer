#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Ensure port 8080 is open for the LB (idempotent — silently ignores duplicate rules).
echo "Ensuring port 8080 is open on security group $AWS_SECURITY_GROUP..."
aws ec2 authorize-security-group-ingress \
    --group-id "$AWS_SECURITY_GROUP" \
    --protocol tcp \
    --port 8080 \
    --cidr 0.0.0.0/0 2>&1 | grep -v "InvalidPermission.Duplicate" || true

aws ec2 run-instances \
    --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --instance-type t3.micro \
    --key-name "$AWS_KEYPAIR_NAME" \
    --security-group-ids "$AWS_SECURITY_GROUP" \
    --monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > lbinstance.id

echo "New LB instance with id $(cat lbinstance.id)."

aws ec2 create-tags \
    --resources "$(cat lbinstance.id)" \
    --tags Key=type,Value=load-balancer

aws ec2 wait instance-running --instance-ids $(cat lbinstance.id)
echo "New LB instance with id $(cat lbinstance.id) is now running."

aws ec2 describe-instances \
    --instance-ids $(cat lbinstance.id) | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > lbinstance.dns

echo "New LB instance with id $(cat lbinstance.id) has address $(cat lbinstance.dns)."

while ! nc -z $(cat lbinstance.dns) 22; do
    echo "Waiting for $(cat lbinstance.dns):22 (SSH)..."
    sleep 0.5
done

echo "New LB instance is ready for SSH access."
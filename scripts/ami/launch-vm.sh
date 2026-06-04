#!/bin/bash

# Creates a temporary EC2 instance configured before creating the AMI.
source "$(dirname "$(realpath "$0")")/../config.sh"

# Run new instance.
aws ec2 run-instances \
    --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --instance-type t3.micro \
    --key-name $AWS_KEYPAIR_NAME \
    --security-group-ids $AWS_SECURITY_GROUP \
    --monitoring Enabled=true | jq -r ".Instances[0].InstanceId" > "$DIR/instance.id"
echo "New instance with id $(cat "$DIR/instance.id")."

# Wait for instance to be running.
aws ec2 wait instance-running --instance-ids $(cat "$DIR/instance.id")
echo "New instance with id $(cat "$DIR/instance.id") is now running."

# Extract DNS name.
aws ec2 describe-instances \
    --instance-ids $(cat "$DIR/instance.id") | jq -r ".Reservations[0].Instances[0].NetworkInterfaces[0].PrivateIpAddresses[0].Association.PublicDnsName" > "$DIR/instance.dns"
echo "New instance with id $(cat "$DIR/instance.id") has address $(cat "$DIR/instance.dns")."

# Wait for instance to have SSH ready.
while ! nc -z $(cat "$DIR/instance.dns") 22; do
    echo "Waiting for $(cat "$DIR/instance.dns"):22 (SSH)..."
    sleep 0.5
done
echo "New instance with id $(cat "$DIR/instance.id") is ready for SSH access."

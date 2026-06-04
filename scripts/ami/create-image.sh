#!/bin/bash

# Automates the full AMI creation workflow.
source "$(dirname "$(realpath "$0")")/../config.sh"

# Step 1: launch a vm instance.
$DIR/launch-vm.sh

# Step 2: install software in the VM instance.
$DIR/install-vm.sh

# Step 3: test VM instance.
$DIR/test-vm.sh

# Step 4: deregister existing AMI with the same name if it exists, then create a new one.
EXISTING_AMI=$(aws ec2 describe-images --owners self --filters "Name=name,Values=CNV-Image" --query "Images[0].ImageId" --output text)
if [ "$EXISTING_AMI" != "None" ] && [ -n "$EXISTING_AMI" ]; then
    echo "Deregistering existing AMI $EXISTING_AMI..."
    aws ec2 deregister-image --image-id $EXISTING_AMI
    sleep 5
fi
aws ec2 create-image --instance-id $(cat "$DIR/instance.id") --name CNV-Image | jq -r .ImageId > "$DIR/image.id"
echo "New VM image with id $(cat "$DIR/image.id")."

# Step 5: Wait for image to become available.
echo "Waiting for image to be ready... (this can take a couple of minutes)"
aws ec2 wait image-available --filters Name=name,Values=CNV-Image
echo "Waiting for image to be ready... done! \o/"

# Step 6: terminate the vm instance.
aws ec2 terminate-instances --instance-ids $(cat "$DIR/instance.id")

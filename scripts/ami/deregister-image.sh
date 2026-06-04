#!/bin/bash

# Deletes the AMI and associated snapshots.
source "$(dirname "$(realpath "$0")")/../config.sh"

aws ec2 deregister-image --delete-associated-snapshots --image-id $(cat "$DIR/image.id")

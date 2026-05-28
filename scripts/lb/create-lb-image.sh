#!/bin/bash

source config.sh

# Step 1: launch a vm instance.
$DIR/launch-lb-vm.sh

# Step 2: install software in the VM instance.
$DIR/install-lb-vm.sh

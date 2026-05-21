#!/bin/bash

# Verifies that the WebServer starts automatically after reboot.
source "$(dirname "$(realpath "$0")")/../config.sh"

# Requesting an instance reboot.
aws ec2 reboot-instances --instance-ids $(cat "$DIR/instance.id")
echo "Rebooting instance to test web server auto-start."

# Letting the instance shutdown.
sleep 1

# Wait for port 8000 to become available.
while ! nc -z $(cat "$DIR/instance.dns") 8000; do
    echo "Waiting for $(cat "$DIR/instance.dns"):8000..."
    sleep 0.5
done

# Sending a query!
echo "Sending a query!"
curl $(cat "$DIR/instance.dns"):8000/test\?testing-after-reboot

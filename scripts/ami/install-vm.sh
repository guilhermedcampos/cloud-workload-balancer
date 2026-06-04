#!/bin/bash

source "$(dirname "$(realpath "$0")")/../config.sh"

# Install java.
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat "$DIR/instance.dns") \
    "sudo yum update -y && sudo yum install java-11-amazon-corretto-devel.x86_64 -y"

# Upload jars.
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    ec2-user@$(cat "$DIR/instance.dns"):webserver.jar

scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../../javassist/target/JavassistWrapper-1.0.jar \
    ec2-user@$(cat "$DIR/instance.dns"):javassist.jar

# Upload startup script and systemd service.
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/start-webserver.sh \
    ec2-user@$(cat "$DIR/instance.dns"):start-webserver.sh

scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/webserver.service \
    ec2-user@$(cat "$DIR/instance.dns"):webserver.service

# Install and enable systemd service so the web server starts on boot.
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat "$DIR/instance.dns") "
    chmod +x /home/ec2-user/start-webserver.sh &&
    sudo mv /home/ec2-user/webserver.service /etc/systemd/system/webserver.service &&
    sudo systemctl daemon-reload &&
    sudo systemctl enable webserver.service &&
    sudo systemctl start webserver.service
"

echo "VM setup complete"

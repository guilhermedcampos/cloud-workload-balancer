#!/bin/bash

source config.sh

# Install java.
cmd="sudo yum update -y; sudo yum install java-11-amazon-corretto-devel.x86_64 -y;"
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) $cmd

# Install web server.

# WebServer jar
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../webserver/target/webserver-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    ec2-user@$(cat instance.dns):webserver.jar

# Javassist agent jar
scp -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH \
    $DIR/../javassist/target/JavassistWrapper-1.0-jar-with-dependencies.jar \
    ec2-user@$(cat instance.dns):javassist.jar

# Create rc.local service 
cmd="sudo bash -c 'cat > /etc/systemd/system/rc-local.service <<EOF
[Unit]
Description=/etc/rc.local Compatibility
ConditionPathExists=/etc/rc.local

[Service]
Type=forking
ExecStart=/etc/rc.local start
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF'

sudo systemctl enable rc-local.service'
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) "$cmd"

# Setup web server to start on instance launch.
cmd="sudo bash -c 'cat > /etc/rc.local <<EOF
#!/bin/bash
cd /home/ec2-user

java \
-cp webserver.jar \
-Xbootclasspath/a:/home/ec2-user/javassist.jar \
-javaagent:/home/ec2-user/javassist.jar=ICount:pt.ulisboa.tecnico.cnv.fractals,pt.ulisboa.tecnico.cnv.grayscott,pt.ulisboa.tecnico.cnv.dna:output \
pt.ulisboa.tecnico.cnv.webserver.WebServer \
> webserver.log 2>&1 &

exit 0
EOF'

sudo chmod +x /etc/rc.local'
ssh -o StrictHostKeyChecking=no -i $AWS_EC2_SSH_KEYPAR_PATH ec2-user@$(cat instance.dns) "$cmd"

echo "VM setup complete"
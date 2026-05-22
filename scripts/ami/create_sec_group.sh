# Get your default VPC ID
VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
    --query "Vpcs[0].VpcId" --output text)

# Create the security group
SG_ID=$(aws ec2 create-security-group \
    --group-name CNV-SecurityGroup \
    --description "CNV project security group" \
    --vpc-id $VPC_ID \
    --query GroupId --output text)

echo "Security group: $SG_ID"

# Open SSH (22), HTTP (80), and webserver (8000)
aws ec2 authorize-security-group-ingress --group-id $SG_ID \
    --protocol tcp --port 22 --cidr 0.0.0.0/0

aws ec2 authorize-security-group-ingress --group-id $SG_ID \
    --protocol tcp --port 80 --cidr 0.0.0.0/0

aws ec2 authorize-security-group-ingress --group-id $SG_ID \
    --protocol tcp --port 8000 --cidr 0.0.0.0/0
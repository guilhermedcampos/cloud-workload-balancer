## Nature@Cloud

This project contains the following sub-projects:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads
5. Javassist-based instrumentation (`ICount`) for runtime metrics collection
6. Metrics extraction and local persistence to `metrics.log`
7. DynamoDB integration for centralized metric storage (optional / enabled via environment configuration)
8. AWS scripts to launch and configure EC2 worker instances, build the worker AMI and deploy infrastructure using AWS services

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

### How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run `mvn clean package`


### Deployment approach for checkpoint
Although programmatic LB and autoscaling logic exist in the codebase, the running system for this checkpoint uses:

- AWS Elastic Load Balancer (ELB)
- AWS Auto Scaling Group (ASG)
- AWS CloudWatch alarms for scaling decisions

These are configured and managed through deployment scripts. The skeleton implementation of Java-based Load Balancer and Java-based AutoScaler are not used in production deployment.

---

## Architecture

Each EC2 worker instance runs the WebServer, which exposes:

- `/fractals`
- `/dna`
- `/grayscott`
- `/test` (health check)

### Request flow

1. Client sends request to ELB
2. ELB distributes request across EC2 worker instances
3. Worker processes request
4. Javassist instrumentation collects runtime metrics
5. Metrics are logged locally and sent to DynamoDB

---

## Metrics system

Each request is instrumented using `ICount` and produces a computed complexity score based on:

- instruction count
- method invocations
- constructor invocations
- block fragmentation metric

---

## AWS configuration

The system relies on the following environment variables defined in `config.sh`:

### Current deployment configuration

- Region: `us-east-1`
- Worker port: `8000`
- Load Balancer port: `80`
- AMI creation and deployment are fully automated via scripts

---

## AWS infrastructure setup

## AWS infrastructure setup

The deployment scripts configure:

- Elastic Load Balancer (ELB)
  - Listener on port `80`
  - Health check on `/test` at port `8000`
  - Health check settings:
    - `Interval=30`
    - `Timeout=5`
    - `HealthyThreshold=2`
    - `UnhealthyThreshold=2`

- Auto Scaling Group (ASG)
  - Desired capacity: `1`
  - Minimum: `1`
  - Maximum: `3`

- CloudWatch alarms
  - Scale out when average `CPUUtilization` is greater than `10%` for `2` consecutive 60-second periods
  - Scale in when average `CPUUtilization` is less than `5%` for `2` consecutive 60-second periods

- DynamoDB
  - Metrics table: `CNV-Metrics`
  - Used for centralized storage of computed metrics

---

## How to run
```bash
# 1. Prepare configuration
cp config.sh.template config.sh
# (AWS_SESSION_TOKEN may be empty)

# 2. Build project
mvn clean package

# 3. Create security group
cd scripts
bash ami/create_sec_group.sh
# Copy SECURITY GROUP ID into config.sh

# 4. Build worker AMI
bash ami/create-image.sh

# 5. Deploy infrastructure (ELB + ASG + DynamoDB)
bash deployment/launch-deployment-template.sh

# 6. Run stress test
tests-checkpoint/stress-test.sh

# 7. Teardown deployment
bash deployment/terminate-deployment-template.sh

# 8. Remove AMI
bash ami/deregister-image.sh
```

Notes

This is a checkpoint version of the system so the Java-based Load Balancer and AutoScaler exist as functional skeletons in the codebase, but are not used in the active deployment. The live system relies on AWS Elastic Load Balancing and Auto Scaling Group managed through scripts and AWS services.

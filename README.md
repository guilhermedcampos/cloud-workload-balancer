## Nature@Cloud

This project contains the following components:

1. `fractals` - the Julia Set fractals workload
2. `dna` - the DNA Genome matcher workload
3. `grayscott` - the Gray-Scott reaction-diffusion workload
4. `webserver` - the web server exposing the functionality of the workloads
5. Javassist-based instrumentation (`ICount`) for runtime metrics collection
6. DynamoDB integration for centralized metric storage with asynchronous buffered batch flushing
7. Java-based Load Balancer with cost-aware request scheduling
8. Java-based AutoScaler and Supervisor for EC2 worker lifecycle management
9. AWS Lambda integration for overflow handling and hybrid execution
10. AWS scripts to launch and configure EC2 worker instances, build AMIs and deploy infrastructure

Refer to the `README.md` files of the sub-projects to get more details about each specific sub-project.

---

## How to build everything

1. Make sure your `JAVA_HOME` environment variable is set to Java 11+ distribution
2. Run: `mvn clean package`

---

## Final architecture

The final system uses a custom Java-based cloud orchestration layer composed of:

- Java Load Balancer
- Java AutoScaler
- Supervisor
- EC2 Worker instances
- AWS Lambda
- DynamoDB

### Worker endpoints

Each worker exposes:

- `/fractals`
- `/dna`
- `/grayscott`
- `/test`

---

## Request flow

1. Client sends request to the Load Balancer.
2. Load Balancer extracts workload parameters.
3. A workload-specific cost estimate is computed.
4. The Metrics Cache at Load Balancer is consulted for previously observed costs within a similar parameter range (buckets).
5. The Supervisor selects the optimal EC2 worker according to current load.
6. If all workers are overloaded and the request cost is below a configurable threshold, the request is redirected to AWS Lambda.
7. Worker executes the workload.
8. Javassist instrumentation collects runtime metrics.
9. Metrics are buffered locally.
10. Metrics are asynchronously flushed to DynamoDB, keyed by parameter buckets.
11. Cache entries are periodically refreshed from DynamoDB to improve future scheduling decisions.

---

## Metrics system

Every workload execution is instrumented through `ICount` to produce a complexity score.

The following metrics are collected:

- instruction count
- method invocations
- constructor invocations
- basic block count
- fragmentation metric

Some of these metrics are used to compute workload complexity and populate DynamoDB, others to infer workload properties.

### Complexity model

The complexity score combines instruction count, structural characteristics, and fragmentation penalty to produce a multiplicative model:

```text
Cost = (instructions / 1e6) × (1 + 0.05 × constructors)× (1 + log(1 + fragmentation))
```

The resulting complexity values are stored in DynamoDB and later used to train the scheduling layer. The report further explains how each property contributes to the final score.

---

## Metrics cache

The Load Balancer maintains a cache of historical workload costs.

### Fractals

Parameters:

- `iterations`
- `resolution = width × height`

### DNA

Parameters:

- `seqLength`

Empirically the only parameter that effectively contributed to complexity.

### GrayScott

Parameters:

- `size`
- `maxIterations`

Requests are bucketed and matched against historical executions.

If a cache hit exists, the stored complexity value is used.

If a cache miss occurs, an heuristic lightweight estimator is used until real measurements become available.

---

## Heuristic Cost Estimation

The system includes workload-specific estimators that are used only when no cached metrics are available. The coefficients associated with each parameter were calibrated using the collected metrics so that the estimated cost closely approximates the complexity values produced by the ICount-based model.

Current coefficients:

### Fractals

```java
FRACTALS_COSTS = List.of(0.01, 0.01);
```

### DNA

```java
DNA_COSTS = List.of(1);
```

### GrayScott

```java
GRAYSCOTT_COSTS = List.of(10, 15);
```

Estimated costs are rounded and clamped to a minimum value of 1 before scheduling.

---

## Load balancing strategy

The Load Balancer uses:

- historical complexity values when available
- estimator-based fallback when necessary
- worker load tracking
- CPU utilization monitoring

Worker selection is performed through a Supervisor component using a cost-aware scheduling strategy and worker load at selection. More on the worker selection method in the report.

---

## Auto scaling

The AutoScaler continuously monitors worker utilization and capacity.

Capabilities include:

- launching EC2 workers
- terminating idle workers
- synchronizing active worker state
- supporting dynamic workload changes

The Supervisor coordinates worker registration, health monitoring and request accounting.

---

## AWS Lambda integration

Lambda is used as an overflow execution layer.

Requests may be redirected to Lambda when:

- all active EC2 workers are heavily loaded
- the estimated workload cost is below a configurable threshold
- no worker is immediately available

This enables burst handling without over-provisioning EC2 instances.

---

## DynamoDB

DynamoDB is used for centralized metric storage.

Tables:

- `CNV-Metrics-Fractals`
- `CNV-Metrics-DNA`
- `CNV-Metrics-GrayScott`

Stored information includes:

- workload type
- request parameters
- complexity
- instruction count
- fragmentation
- timestamps

Metrics are written asynchronously using buffered batch flushing.

---

## AWS configuration

Configuration values must be filled in `config.sh`.

---

## How to run

```bash
# 1. Prepare configuration
cp config.sh.template config.sh

# 2. Build project
mvn clean package

# 3. Create security group
bash scripts/ami/create-sec-group.sh

# 4. Build worker AMI
bash scripts/ami/create-image.sh

# 5. Deploy worker infrastructure
bash scripts/deployment/launch-deployment-template.sh

# 6. Build and deploy load balancer
bash scripts/lb/create-lb-image.sh

# 7. Run workload tests
bash tests/stress-test.sh # optionally stress-fractals.sh or stress-grayscott.sh  

# 8. Monitor Load Balancer
ssh -i $PATH_TO_KEYPAIR ec2-user@$LB_DNS 'tail -f loadbalancer.log'

# 9. Monitor Instance Worker
ssh -i $PATH_TO_KEYPAIR ec2-user@$INSTANCE_DNS 'tail -f /home/ec2-user/webserver.log'

# 9. Tear down deployment
bash scripts/deployment/terminate-deployment-template.sh

# 10. Remove generated AMIs
bash scripts/ami/deregister-image.sh
```

---
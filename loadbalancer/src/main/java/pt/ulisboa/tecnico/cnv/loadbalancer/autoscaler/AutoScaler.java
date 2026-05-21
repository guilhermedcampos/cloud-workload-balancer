package pt.ulisboa.tecnico.cnv.loadbalancer.autoscaler;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;

import pt.ulisboa.tecnico.cnv.loadbalancer.registry.WorkerRegistry;

import java.util.List;

public class AutoScaler {

    private static AutoScaler singleton;

    private final AmazonEC2 ec2;
    private final WorkerRegistry registry;

    private final String ami;
    private final String keyName;
    private final String securityGroup;

    private final Regions region;

    private AutoScaler() {
        this.region = Regions.fromName(
                System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1")
        );

        this.ec2 = AmazonEC2ClientBuilder.standard()
                .withRegion(region)
                .withCredentials(new EnvironmentVariableCredentialsProvider())
                .build();

        this.registry = WorkerRegistry.getInstance();

        this.ami = System.getenv("AWS_AMI_ID");
        this.keyName = System.getenv("AWS_KEYPAIR_NAME");
        this.securityGroup = System.getenv("AWS_SECURITY_GROUP");

        validateEnv();
    }

    public static synchronized AutoScaler getInstance() {
        if (singleton == null) {
            singleton = new AutoScaler();
        }
        return singleton;
    }

    private void validateEnv() {
        if (ami == null || keyName == null || securityGroup == null) {
            throw new RuntimeException("Missing AWS environment variables");
        }
    }

    public void syncWorkersFromCloud() {
        DescribeInstancesResult res = ec2.describeInstances();

        for (Reservation r : res.getReservations()) {
            for (Instance i : r.getInstances()) {

                if (i.getPublicIpAddress() == null) continue;
                if (i.getState().getCode() != 16) continue;

                registry.addWorker(i.getInstanceId(), i.getPublicIpAddress());
            }
        }

        System.out.println("[AutoScaler] Synced workers: " + registry.size());
    }

    public void scaleUp() {
        RunInstancesRequest req = new RunInstancesRequest()
                .withImageId(ami)
                .withInstanceType("t2.micro")
                .withMinCount(1)
                .withMaxCount(1)
                .withKeyName(keyName)
                .withSecurityGroupIds(securityGroup);

        RunInstancesResult result = ec2.runInstances(req);

        Instance inst = result.getReservation().getInstances().get(0);

        System.out.println("[AutoScaler] Launching: " + inst.getInstanceId());

        waitForRunning(inst.getInstanceId());
        syncWorkersFromCloud();
    }

    public void scaleDown(String instanceId) {
        TerminateInstancesRequest req = new TerminateInstancesRequest()
                .withInstanceIds(instanceId);

        ec2.terminateInstances(req);

        registry.removeWorker(instanceId);

        System.out.println("[AutoScaler] Terminated: " + instanceId);
    }

    private void waitForRunning(String id) {
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(1000);

                Instance inst = ec2.describeInstances(
                        new DescribeInstancesRequest().withInstanceIds(id)
                ).getReservations().get(0).getInstances().get(0);

                if (inst.getState().getCode() == 16) return;

            } catch (Exception ignored) {}
        }
    }
}
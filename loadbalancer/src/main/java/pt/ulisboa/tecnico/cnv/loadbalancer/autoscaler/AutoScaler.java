package pt.ulisboa.tecnico.cnv.loadbalancer.autoscaler;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;

import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Supervisor;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.WorkerPool;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Worker;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public class AutoScaler {

    private static AutoScaler singleton;

    private static final int RUNNING_STATE_CODE = 16;

    private final AmazonEC2 ec2;
    private final Supervisor supervisor;

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

        this.supervisor = Supervisor.getInstance();

        this.ami = System.getenv("AWS_AMI_ID");
        this.keyName = System.getenv("AWS_KEYPAIR_NAME");
        this.securityGroup = System.getenv("AWS_SECURITY_GROUP");

        validateEnv();
    }

    public void start() {
        syncWorkersFromCloud();

        new Thread(() -> {
            while (true) {
                try {
                    handleScaleUp();
                    handleScaleDown();
                    handleTerminateInstances();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }).start();
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

        for (Reservation reservation : res.getReservations()) {
            for (Instance instance : reservation.getInstances()) {
                if (instance.getPublicIpAddress() == null) continue;
                if (instance.getState().getCode() != RUNNING_STATE_CODE) continue;
                if (isLBInstance(instance)) continue;

                supervisor.registerActiveInstance(instance);
            }
        }

        System.out.println("[AutoScaler] Synced workers from cloud.");
    }

    private Instance waitUntilRunning(Instance inst) {
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(1000);

                Instance updatedInst = ec2.describeInstances(
                        new DescribeInstancesRequest().withInstanceIds(inst.getInstanceId())
                ).getReservations().get(0).getInstances().get(0);

                if (updatedInst.getState().getCode() == RUNNING_STATE_CODE) {
                    return updatedInst;
                }

            } catch (Exception ignored) {}
        }
        return null;
    }

    public void scaleUp() {
        RunInstancesRequest req = new RunInstancesRequest()
                .withImageId(ami)
                .withInstanceType("t3.micro")
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

    private void handleScaleUp() {
        RunInstancesRequest request = new RunInstancesRequest()
                .withImageId(ami)
                .withInstanceType("t3.micro")
                .withMinCount(1)
                .withMaxCount(1)
                .withKeyName(keyName)
                .withSecurityGroupIds(securityGroup);

        RunInstancesResult result = ec2.runInstances(request);
        Instance instance = result.getReservation().getInstances().get(0);

        waitForRunning(instance.getInstanceId());

        Instance runningInstance = waitUntilRunning(instance);
        if (runningInstance != null && runningInstance.getState().getCode() == RUNNING_STATE_CODE) {
            supervisor.registerActiveInstance(runningInstance);
        }
    }
    
    public void scaleDown(String instanceId) {
        TerminateInstancesRequest req = new TerminateInstancesRequest()
                .withInstanceIds(instanceId);

        ec2.terminateInstances(req);

        //TODO: adapt autoscaler to new supervisor
        // Supervisor.getInstance().removeWorker(instanceId);

        System.out.println("[AutoScaler] Terminated: " + instanceId);
    }

    private void handleScaleDown() {
        Set<Worker> excessWorkers = supervisor.getExcessWorkers();
        for (Worker worker : excessWorkers) {
            supervisor.toRemoveWorker(worker);
        }
    }

    private void terminateInstance(Instance instance) {
        TerminateInstancesRequest req = new TerminateInstancesRequest()
                .withInstanceIds(instance.getInstanceId());

        ec2.terminateInstances(req);

        System.out.println("[AutoScaler] Terminated: " + instance.getInstanceId());
    }
    
    private void handleTerminateInstances() {
        PriorityQueue<Worker> queue = supervisor.getTerminationCandidates();

        for (Worker worker : queue) {
            terminateInstance(worker.getInstance());
            supervisor.removeInactiveWorker(worker);
        }
    }
    
    private void waitForRunning(String id) {
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(1000);

                Instance inst = ec2.describeInstances(
                        new DescribeInstancesRequest().withInstanceIds(id)
                ).getReservations().get(0).getInstances().get(0);

                if (inst.getState().getCode() == RUNNING_STATE_CODE) return;

            } catch (Exception ignored) {}
        }
    }

    public boolean isAlive(String ip) {
        try {
            URL url = new URL("http://" + ip + ":8000/test");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLBInstance(Instance instance) {
        if (instance == null) {
            return false;
        }

        String lbInstanceId = System.getenv("LB_INSTANCE_ID");
        if (lbInstanceId != null && !lbInstanceId.isBlank()
                && lbInstanceId.equals(instance.getInstanceId())) {
            return true;
        }

        for (Tag tag : instance.getTags()) {
            String key = tag.getKey() == null ? "" : tag.getKey().trim().toLowerCase();
            String value = tag.getValue() == null ? "" : tag.getValue().trim().toLowerCase();

            boolean lbValue = value.equals("load-balancer")
                    || value.equals("loadbalancer")
                    || value.equals("lb");

            if ((key.equals("type") || key.equals("role") || key.equals("component")) && lbValue) {
                return true;
            }
        }

        return false;
    }

}
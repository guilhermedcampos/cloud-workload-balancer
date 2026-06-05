package pt.ulisboa.tecnico.cnv.loadbalancer.autoscaler;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.LaunchTemplateSpecification;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Supervisor;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Worker;

public class AutoScaler {

    private static AutoScaler singleton;

    private static final int    RUNNING_STATE_CODE     = 16;
    private final Set<String>   pendingInstanceIds     = ConcurrentHashMap.newKeySet();
    private static final double HIGH_CPU_THRESHOLD     = 0.8;
    private static final int    MIN_INSTANCES          = 1;
    private static final int    MAX_INSTANCES          = 6;
    private static final long   SCALING_INTERVAL       = 10_000;
    private static final long   SCALE_DOWN_COOLDOWN_MS = 60_000; // 2 min after last scale-up
    private volatile long       lastScaleUpTime        = 0;

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

        this.supervisor.setDeadWorkerHandler(worker -> {
            try {
                terminateInstance(worker.getInstance());
            } catch (Exception e) {
                System.out.println("[AutoScaler] Failed to terminate dead worker " + worker.getId() + ": " + e.getMessage());
            }
        });
    }

    public void start() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SCALING_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    syncWorkersFromCloud();
                    handleScaleUp();
                    handleScaleDown();
                    handleTerminateInstances();
                } catch (Exception e) {
                    System.out.println("[AutoScaler] Error in scaling loop: " + e.getMessage());
                    e.printStackTrace();
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
                if (isAlive(instance.getPublicIpAddress())) {
                    supervisor.registerActiveInstance(instance);
                }
            }
        }

        System.out.println("[AutoScaler] Synced workers from cloud.");
    }

    private Instance waitUntilRunning(Instance inst) {
        for (int i = 0; i < 120; i++) {
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

    private void handleScaleUp() {
        int count = supervisor.getActiveWorkerCount();
        int nonResponsive = supervisor.getNonResponsiveWorkerCount();
        // Pending + non-responsive instances count toward the cap — non-responsive workers may recover.
        if (count + nonResponsive + pendingInstanceIds.size() >= MAX_INSTANCES) return;
        double avg = supervisor.getAverageCpuUsage();
        // Scale up only when there are no workers AND no pending ones, or CPU is high.
        if (count + nonResponsive + pendingInstanceIds.size() > 0 && avg < HIGH_CPU_THRESHOLD) return;

        System.out.println(String.format("[AutoScaler] Scale-up triggered: %d active + %d non-responsive + %d pending workers, avg CPU %.2f",
                count, nonResponsive, pendingInstanceIds.size(), avg));

        RunInstancesRequest request = new RunInstancesRequest()
                .withLaunchTemplate(new LaunchTemplateSpecification()
                        .withLaunchTemplateName("CNV-LaunchTemplate")
                        .withVersion("$Latest"))
                .withMinCount(1)
                .withMaxCount(1);

        RunInstancesResult result = ec2.runInstances(request);
        Instance instance = result.getReservation().getInstances().get(0);
        String instanceId = instance.getInstanceId();

        // Reserve the slot immediately so subsequent loop ticks don't launch another.
        pendingInstanceIds.add(instanceId);
        lastScaleUpTime = System.currentTimeMillis();
        System.out.println("[AutoScaler] Launching: " + instanceId);

        new Thread(() -> {
            try {
                Instance runningInstance = waitUntilRunning(instance);
                if (runningInstance != null && runningInstance.getState().getCode() == RUNNING_STATE_CODE) {
                    supervisor.registerActiveInstance(runningInstance);
                } else {
                    System.out.println("[AutoScaler] Instance " + instanceId + " failed to become ready; terminating.");
                    try { ec2.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceId)); } catch (Exception ignored) {}
                }
            } finally {
                pendingInstanceIds.remove(instanceId);
            }
        }).start();
    }

    private void handleScaleDown() {
        if (System.currentTimeMillis() - lastScaleUpTime < SCALE_DOWN_COOLDOWN_MS) return;
        int count = supervisor.getActiveWorkerCount();
        if (count <= MIN_INSTANCES) return;
        double avg = supervisor.getAverageCpuUsage();
        // safe to remove one only if n-1 workers can still handle the load
        if (avg * count / (count - 1) >= HIGH_CPU_THRESHOLD) return;

        Set<Worker> idle = supervisor.getExcessWorkers();
        if (idle.isEmpty()) return;

        Worker toRemove = idle.iterator().next();
        System.out.println(String.format("[AutoScaler] Scale-down triggered: %d workers, avg CPU %.2f — moving %s to terminating", count, avg, toRemove.getId()));
        supervisor.toRemoveWorker(toRemove);
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
    
    public boolean isAlive(String ip) {
        try {
            URL url = new URL("http://" + ip + ":8000/health");
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
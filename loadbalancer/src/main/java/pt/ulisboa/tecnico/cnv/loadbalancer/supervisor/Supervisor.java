package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer;
import pt.ulisboa.tecnico.cnv.loadbalancer.autoscaler.AutoScaler;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.WorkerPool.WorkerPoolType;

public class Supervisor {
    private static Supervisor instance = null;
    static final int HEALTH_CHECK_INTERVAL = 5000;
    private static final int STARTUP = 30;
    private static final int SECOND_TILL_DEATH = 30;
    private static final int WORKER_PORT = LoadBalancer.WORKER_PORT;

    private final WorkerPool activeWorkersPool = new WorkerPool(WorkerPoolType.WORKING);
    private final WorkerPool terminatingPool = new WorkerPool(WorkerPoolType.TERMINATING);
    private final WorkerPool nonResponsivePool = new WorkerPool(WorkerPoolType.NON_RESPONSIVE);
    // Easier to fetch worker pool instead of using ifs/switches
    private final Map<WorkerPoolType, WorkerPool> pools = new HashMap<WorkerPoolType, WorkerPool>() {{
        put(WorkerPoolType.WORKING, activeWorkersPool);
        put(WorkerPoolType.TERMINATING, terminatingPool);
        put(WorkerPoolType.NON_RESPONSIVE, nonResponsivePool);
    }};

    private final Map<Worker, WorkerPool> workers = new HashMap<>();

    private Supervisor() {
    }

    public static Supervisor getInstance() {
        if (instance == null) {
            instance = new Supervisor();
        }
        return instance;
    }

    public void start() {
        new Thread(() -> {
            System.out.println(String.format("[Supervisor] Starting Health Checker"));
            while (true) {
                try {
                    Thread.sleep(Supervisor.HEALTH_CHECK_INTERVAL);
                    this.handleHealthCheck();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private HttpResponse<String> healthCheck(String ipAddress, Duration timeout) {
        HttpClient client = HttpClient.newHttpClient();
        String url = "http://" + ipAddress + ":" + WORKER_PORT + "/test";
        HttpRequest request = HttpRequest.newBuilder().timeout(timeout)
            .uri(URI.create(url))
            .GET()
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            return null;
        }

        return response;

    }

    private void handleHealthCheck() {
        if (this.workers.isEmpty()) {
            return;
        }
         for (Worker worker : this.workers.keySet()) {
            new Thread(() -> {
                HttpResponse<String> response = healthCheck(worker.getIp(), Duration.ofSeconds(2));
                
                if (response == null) {
                    System.out.println(String.format("[Supervisor] [%s] Worker is unreachable. May be dead or with high latency.", worker.getIp()));
                    unresponsiveWorker(worker);
                    return;
                }

                if (response.statusCode() / 100 != 2) {
                    //TODO: unhandled case: the supervisor assumes that in this case the instance is dead
                    //and removes it from every list. Possible problem: incorrect instances are kept alive
                    // doing nothing instead of being killed.
                    System.out.println(String.format("[Supervisor] [%s] Worker is not responding to health check. Removing it.", worker.getIp()));
                    unresponsiveWorker(worker);
                } else {
                    String res = response.body();
                    String[] splitRes = res.split(" ");
                    if (!res.startsWith("OK: ") || splitRes.length != 2) {
                        unresponsiveWorker(worker);
                        return;
                    }

                    double cpuUsage = Double.parseDouble(splitRes[1]);
                    worker.updateCpuUsage(cpuUsage);
                    System.out.println(String.format("[Supervisor] [%s] OK | CPU Usage: %f", worker.getIp(), cpuUsage));
                }
                
            }).start();
        }

    }

    public Worker getBestWorker(int cost) {
        WorkerPool pool = this.pools.get(WorkerPoolType.WORKING);
        Worker worker = pool.getAvailableWorker(cost);
        if (worker != null) {
            return worker;
        }

        // No workers available, check if there are any workers that should be terminating soon
        pool = this.pools.get(WorkerPoolType.TERMINATING);
        worker = pool.getAvailableWorker(cost);
        if (worker != null) {
            return worker;
        }

        // lambda functions
        return null;
    }
    

    public void registerRequestForWorker(Worker worker, long requestId, int cost) {
        WorkerPool pool = this.workers.get(worker);
        if (pool == null) {
            throw new RuntimeException("Worker not found in any pool");
        }

        worker.updateLoad(requestId, cost);
    }
    
    private void unresponsiveWorker(Worker worker) {
        // Send worker to non responsive pool
        this.activeWorkersPool.sendWorkerToPool(worker, this.nonResponsivePool);

        // move back in or terminate
        boolean isAlive = false;
        boolean removed = false;
        for (int i = 0; i < SECOND_TILL_DEATH; i++) {
            if (i > SECOND_TILL_DEATH / 10 && !removed) {
                // allow autoscaling to replace the instance
                this.workers.remove(worker);
                removed = true;
            }
            HttpResponse<String> response = healthCheck(worker.getIp(), Duration.ofSeconds(5));
            if (response != null && response.statusCode() == 200) {
                isAlive = true;
                break;
            }
        }
        if (isAlive) {
            this.nonResponsivePool.sendWorkerToPool(worker, this.activeWorkersPool);
            this.workers.put(worker, this.activeWorkersPool);

        } else {
            // AutoScaler.getInstance().terminateInstance(worker.getInstance());
            removeInactiveWorker(worker);
        }

    }

    //TODO: See if we want to try and compact the most for scaledown reducing cost (having more pools and trying to fit the load on higher usage pools)
    public Worker getLazyWorker(int cost) {
        WorkerPool pool = this.pools.get(WorkerPoolType.WORKING);
        Worker worker = pool.getAvailableWorker(cost);
        if (worker != null) {
            return worker;
        }

        // No workers available, check if there are any workers that should be terminating soon
        pool = this.pools.get(WorkerPoolType.TERMINATING);
        worker = pool.getAvailableWorker(cost);
        if (worker != null) {
            return worker;
        }

        // No worker is available in order to preserve a good load balance, use lambda functions
        // This case will probably happen when waiting for system to scale up
        return null;
    }

    public void removeInactiveWorker(Worker worker) {
        WorkerPool pool = this.workers.get(worker);
        if (pool == null) {
            throw new RuntimeException("Worker not found in any pool");
        }

        pool.removeWorker(worker);
        this.workers.remove(worker);
    }

    public void toRemoveWorker(Worker worker) {
        WorkerPool pool = this.workers.get(worker);
        if (pool == null) {
            throw new RuntimeException("Worker not found in any pool");
        }

        pool.sendWorkerToPool(worker, this.terminatingPool);
        this.workers.put(worker, this.terminatingPool);
    }

    public PriorityQueue<Worker> getFreeWorkers() {
        PriorityQueue<Worker> queue = new PriorityQueue<>();
        for (Worker worker : this.workers.keySet()) {
            if (this.workers.get(worker).getType() != WorkerPoolType.TERMINATING) {
                //TODO: Add load for each worker and assign the worker with least load, for now CPU usage is used as a proxy for load
                if (worker.getCpuUsage() == 0) {
                    queue.add(worker);
                }
            }
        }
        return queue;
    }

    public PriorityQueue<Worker> getFreeToRemoveWorkers() {
        PriorityQueue<Worker> queue = new PriorityQueue<>();

        for (Worker worker : terminatingPool.getWorkers()) {   
            //TODO: Add load for each worker and assign the worker with least load, for now CPU usage is used as a proxy for load         
            if (worker.getCpuUsage() == 0) {
                queue.add(worker);
            }
        }

        return queue;
    }

    public boolean registerActiveInstance(com.amazonaws.services.ec2.model.Instance inst) {
        if (inst == null || inst.getPublicIpAddress() == null) {
            return false;
        }

        Worker worker = new Worker(inst);

        if (this.workers.containsKey(worker)) {
            return false;
        }

        for (int i = 0; i < STARTUP; i++) {
            HttpResponse<String> response = healthCheck(
                    worker.getIp(),
                    Duration.ofSeconds(1)
            );

            if (response != null && response.statusCode() / 100 == 2) {
                System.out.println(String.format(
                        "[Supervisor] [%s] Worker is responding. Adding to worker pool.",
                        worker.getIp()
                ));
                this.activeWorkersPool.addWorker(worker);
                this.workers.put(worker, this.activeWorkersPool);
                return true;
            }

            System.out.println(String.format(
                    "[Supervisor] [%s] Worker is unreachable. Keep trying for %d seconds",
                    worker.getIp(),
                    STARTUP - i
            ));

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }
}

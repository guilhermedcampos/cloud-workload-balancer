package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import pt.ulisboa.tecnico.cnv.loadbalancer.LoadBalancer;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.WorkerPool.WorkerPoolType;

public class Supervisor {
    private static Supervisor instance = null;
    static final int HEALTH_CHECK_INTERVAL = 5000;
    private static final int STARTUP = 180;
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

    private final Map<Worker, WorkerPool> workers = new ConcurrentHashMap<>();
    private final Set<String> registrationGate = ConcurrentHashMap.newKeySet();
    private final Set<Worker> recoveringWorkers = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Consumer<Worker> deadWorkerHandler = w -> {};

    public void setDeadWorkerHandler(Consumer<Worker> handler) {
        this.deadWorkerHandler = handler;
    }

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
        String url = "http://" + ipAddress + ":" + WORKER_PORT + "/health";
        HttpRequest request = HttpRequest.newBuilder().timeout(timeout)
            .uri(URI.create(url))
            .GET()
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            return null;
        }

        return response;

    }

    private void handleHealthCheck() {
        List<Worker> snapshot = new ArrayList<>(this.workers.keySet());
        if (snapshot.isEmpty()) {
            return;
        }
        for (Worker worker : snapshot) {
            new Thread(() -> {
                HttpResponse<String> response = healthCheck(worker.getIp(), Duration.ofSeconds(2));
                
                if (response == null) {
                    System.out.println(String.format("[Supervisor] [%s] Worker is unreachable. May be dead or with high latency.", worker.getIp()));
                    unresponsiveWorker(worker);
                    return;
                }

                if (response.statusCode() / 100 != 2) {
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

    public void registerRequestForWorker(Worker worker, long requestId, int cost) {
        WorkerPool pool = this.workers.get(worker);
        if (pool == null) {
            // Worker was concurrently removed (died between selection and registration).
            // Skip load tracking and let the request proceed — it will fail at network level if the VM is gone.
            System.out.println(String.format("[Supervisor] Worker %s removed before request registration; skipping load tracking.", worker.getIp()));
            return;
        }
        worker.updateLoad(requestId, cost);
    }

    public void completeRequestForWorker(Worker worker, long requestId) {
        worker.removeLoad(requestId);
    }
    
    private void unresponsiveWorker(Worker worker) {
        if (!this.workers.containsKey(worker)) return;
        // Only one thread handles recovery per worker. gate prevents duplicate handling.
        // finally always releases so the next health-check tick can retry if this thread crashes.
        if (!recoveringWorkers.add(worker)) return;

        try {
            this.activeWorkersPool.sendWorkerToPool(worker, this.nonResponsivePool);

            boolean isAlive = false;
            for (int i = 0; i < SECOND_TILL_DEATH; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
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
                System.out.println(String.format("[Supervisor] [%s] Worker confirmed dead. Removing and scheduling termination.", worker.getIp()));
                removeInactiveWorker(worker);
                deadWorkerHandler.accept(worker);
            }
        } finally {
            recoveringWorkers.remove(worker);
        }
    }

    public Worker getOptimalWorker(int cost) {
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
        return null;
    }

    public void removeInactiveWorker(Worker worker) {
        WorkerPool pool = this.workers.remove(worker);
        if (pool != null) {
            pool.removeWorker(worker);
        }
    }

    public Set<Worker> getExcessWorkers() {
        Set<Worker> idle = new HashSet<>();
        for (Worker w : activeWorkersPool.getWorkers()) {
            if (w.getLoad() == 0) idle.add(w);
        }
        if (idle.size() == activeWorkersPool.size() && !idle.isEmpty()) {
            idle.remove(idle.iterator().next());
        }
        return idle;
    }

    public double getAverageCpuUsage() {
        Set<Worker> all = activeWorkersPool.getWorkers();
        if (all.isEmpty()) return 0.0;
        return all.stream().mapToDouble(Worker::getCpuUsage).average().orElse(0.0);
    }

    public int getActiveWorkerCount() {
        return activeWorkersPool.size();
    }

    public int getNonResponsiveWorkerCount() {
        return nonResponsivePool.size();
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
                if (worker.getLoad() == 0) {
                    queue.add(worker);
                }
            }
        }
        return queue;
    }

    public PriorityQueue<Worker> getTerminationCandidates() {
        PriorityQueue<Worker> queue = new PriorityQueue<>(
                Comparator.comparingDouble(Worker::getCpuUsage)
                        .thenComparingInt(Worker::getLoad)
        );

        WorkerPool terminatingPool = this.pools.get(WorkerPoolType.TERMINATING);

        for (Worker worker : terminatingPool.getWorkers()) {
            if (worker.getLoad() == 0) {
                queue.add(worker);
            }
        }

        return queue;
    }

    public boolean registerActiveInstance(com.amazonaws.services.ec2.model.Instance inst) {
        if (inst == null || inst.getPublicIpAddress() == null) {
            return false;
        }

        // Atomically claim this instance ID — only one thread proceeds, all others skip.
        if (!registrationGate.add(inst.getInstanceId())) {
            return false;
        }

        Worker worker = new Worker(inst);

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

        // Failed to register — release the gate so a future sync can retry.
        registrationGate.remove(inst.getInstanceId());
        return false;
    }
}

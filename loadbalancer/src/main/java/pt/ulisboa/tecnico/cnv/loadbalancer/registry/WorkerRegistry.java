package pt.ulisboa.tecnico.cnv.loadbalancer.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker registry.
 */
public class WorkerRegistry {

    private static WorkerRegistry instance;

    private static class Worker {
        String id;
        String ip;

        Worker(String id, String ip) {
            this.id = id;
            this.ip = ip;
        }
    }

    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger rrIndex = new AtomicInteger(0);

    private WorkerRegistry() {}

    public static synchronized WorkerRegistry getInstance() {
        if (instance == null) {
            instance = new WorkerRegistry();
        }
        return instance;
    }

    public synchronized void addWorker(String id, String ip) {
        for (Worker w : workers) {
            if (w.id.equals(id)) {
                return;
            }
        }
        workers.add(new Worker(id, ip));
    }

    public synchronized void removeWorker(String id) {
        workers.removeIf(w -> w.id.equals(id));
    }

    public synchronized String getNextWorkerIp() {
        if (workers.isEmpty()) {
            return null;
        }

        int idx = Math.abs(rrIndex.getAndIncrement() % workers.size());
        return workers.get(idx).ip;
    }

    public synchronized List<String> getAllIps() {
        List<String> ips = new ArrayList<>();
        for (Worker w : workers) {
            ips.add(w.ip);
        }
        return ips;
    }

    public int size() {
        return workers.size();
    }
}
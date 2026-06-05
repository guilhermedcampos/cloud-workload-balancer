package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Worker registry.
 */
public class WorkerPool {
     public enum WorkerPoolType {
        WORKING, TERMINATING, NON_RESPONSIVE
    }

    private static final int MAX_LOAD_THRESHOLD = 1_000_000;

    private final double HIGH_CPU_THRESHOLD = 0.8;
    private final double MAX_CPU_THRESHOLD = 1.0;
    private final double OPTIMAL_CPU_THRESHOLD = 0.3;


    private final WorkerPoolType type;

    private int size = 0;
    
    private final SortedSet<Worker> sortedByHighLoad = new TreeSet<>(new Worker.LoadComparator());

    private final Object lock = new Object();

    public WorkerPool(WorkerPoolType type) {
        this.type = type;
    }

    public WorkerPoolType getType() {
        return type;
    }

    public static int getMaxLoadThreshold() {
        return MAX_LOAD_THRESHOLD;
    }
    
    public void addWorker(Worker worker) {
        synchronized (lock) {
            if (sortedByHighLoad.add(worker)) {
                size++;
            }
        }
    }

    public void removeWorker(Worker worker) {
        synchronized (lock) {
            size--;
            // sortedByHighCPU.remove(worker);
            sortedByHighLoad.remove(worker);
        }
    }

    public void reinsert(Worker worker) {
        synchronized (lock) {
            if (sortedByHighLoad.remove(worker)) {
                sortedByHighLoad.add(worker);
            }
        }
    }
    
    public boolean containsWorker(Worker worker) {
        synchronized (lock) {
            return sortedByHighLoad.contains(worker);
        }
    }

    public void sendWorkerToPool(Worker worker, WorkerPool other) {
        if (this.equals(other)) {
            return;
        }

        if (this.containsWorker(worker)) {
            this.removeWorker(worker);
            other.addWorker(worker);
            System.out.println(String.format("[WorkerPool] Worker %s moved from %s to %s", worker.getIp(), this.type.name(), other.type.name()));
        }
    }

    public Worker getAvailableWorker(int cost) {
        synchronized (lock) {
            // 1) Prefer workers in [OPTIMAL_CPU_THRESHOLD, HIGH_CPU_THRESHOLD)
            Worker candidate = findHighestLoadInRange(cost, OPTIMAL_CPU_THRESHOLD, HIGH_CPU_THRESHOLD);
            if (candidate != null) return candidate;

            // 2) Then try [HIGH_CPU_THRESHOLD, MAX_CPU_THRESHOLD]
            candidate = findHighestLoadInRange(cost, HIGH_CPU_THRESHOLD, MAX_CPU_THRESHOLD + Double.MIN_VALUE);
            if (candidate != null) return candidate;

            // 3) Finally, try below OPTIMAL_CPU_THRESHOLD (including 0.0)
            candidate = findHighestLoadInRange(cost, 0.0, OPTIMAL_CPU_THRESHOLD);
            return candidate;
        }
    }

    // Caller must hold 'lock' when invoking this helper.
    private Worker findHighestLoadInRange(int cost, double minCpuInclusive, double maxCpuExclusive) {
        // Iterate sortedByHighLoad (highest load first) and pick first matching CPU range
        return sortedByHighLoad.stream()
                .filter(worker -> {
                    double cpu = worker.getCpuUsage();
                    return cpu >= minCpuInclusive && cpu < maxCpuExclusive;
                })
                .filter(worker -> worker.getLoad() + cost < MAX_LOAD_THRESHOLD)
                .findFirst()
                .orElse(null);
    }

    public SortedSet<Worker> getWorkers() {
        synchronized (lock) {
            return new TreeSet<>(sortedByHighLoad);
        }
    }

    public int size() {
        return size;
    }


}
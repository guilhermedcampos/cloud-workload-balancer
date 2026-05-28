package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker registry.
 */
public class WorkerPool {
     public enum WorkerPoolType {
        WORKING, TERMINATING, NON_RESPONSIVE
    }

    private final WorkerPoolType type;

    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger rrIndex = new AtomicInteger(0);

    int size = 0;
    private final SortedSet<Worker> decreasingCPUWorkers = new TreeSet<>(new Worker.CPUComparator());
    //  private final SortedSet<Worker> decreasingLoadWorkers = new TreeSet<>(new Worker.LoadComparator());

    private final Object lock = new Object();

    public WorkerPool(WorkerPoolType type) {
        this.type = type;
    }

    public WorkerPoolType getType() {
        return type;
    }

    public void addWorker(Worker worker) {
        size++;
        synchronized (lock) {
            decreasingCPUWorkers.add(worker);
        }
    }

    public void removeWorker(Worker worker) {
        size--;
        synchronized (lock) {
            decreasingCPUWorkers.remove(worker);
        }
    }

    public boolean containsWorker(Worker worker) {
        synchronized (lock) {
            return decreasingCPUWorkers.contains(worker);
        }
    }

    public void sendWorkerToPool(Worker worker, WorkerPool other) {
        if (this.equals(other)) {
            return;
        }

        if (this.containsWorker(worker)) {
            this.removeWorker(worker);
            other.addWorker(worker);
            System.out.println(String.format(".(WorkerPool) Worker %s moved from %s to %s", worker.getInstance().getPublicIpAddress(), this.type.name(), other.type.name()));
        }
    }

    //TODO: Add load for each worker and assign the worker with least load, for now CPU usage is used as a proxy for load
    public Worker getAvailableWorker(int cost) {
        // for (Worker worker : decreasingLoadWorkers) {
        //     if (worker.getLoad() + cost < HIGH_CONCURRENT_LOAD) {
        //         return worker;
        //     }
        // }
        for (Worker worker : decreasingCPUWorkers) {
            if (worker.getCpuUsage() + cost < 100) {
                return worker;
            }
        }
        return null;
    }

    public SortedSet<Worker> getWorkers() {
        synchronized (lock) {
            return new TreeSet<>(decreasingCPUWorkers);
        }
    }

    public int size() {
        return size;
    }


}
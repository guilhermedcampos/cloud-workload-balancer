package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.tuple.Pair;

import com.amazonaws.services.ec2.model.Instance;
import com.sun.net.httpserver.HttpExchange;

public class Worker {
    // Comparators
    // To order all workers on workpools by decreasing load
    public static class LoadComparator implements Comparator<Worker> {
        @Override
        public int compare(Worker w1, Worker w2) {
            int cmp = Double.compare(w2.getLoad(), w1.getLoad());
            if (cmp != 0) return cmp;
            return w1.getId().compareTo(w2.getId()); // tie-break so equal-load workers aren't deduplicated
        }
    }

    private final String id;
    private final String ip;

    private ReadWriteLock cpuUsageLock = new ReentrantReadWriteLock();
    private double cpuUsage = 0;

    private final Instance instance;
    private final ReadWriteLock loadRWLock = new ReentrantReadWriteLock();
    // map requestId -> (cost, exchange)
    private final Map<Long, Pair<Integer, HttpExchange>> currentLoad = new HashMap<>();

    public Worker(Instance instance) {
        this.instance = instance;
        this.id = instance.getInstanceId();
        this.ip = instance.getPublicIpAddress();
    }

    public String getId(){
        return id;
    }

    public String getIp(){
        return ip;
    }

    public double getCpuUsage() {
        cpuUsageLock.readLock().lock();
        try {
            return this.cpuUsage;
        } finally {
            cpuUsageLock.readLock().unlock();
        }
    }

    public void updateCpuUsage(double cpuUsage) {
        cpuUsageLock.writeLock().lock();
        try {
            this.cpuUsage = cpuUsage;
        } finally {
            cpuUsageLock.writeLock().unlock();
        }
    }

    public Instance getInstance() {
        return instance;
    }

    public void updateLoad(long requestId, int cost, HttpExchange exchange) {
        loadRWLock.writeLock().lock();
        try {
            currentLoad.put(requestId, Pair.of(cost, exchange));
        } finally {
            loadRWLock.writeLock().unlock();
        }
    }

    public void removeLoad(long requestId) {
        loadRWLock.writeLock().lock();
        try {
            currentLoad.remove(requestId);
        } finally {
            loadRWLock.writeLock().unlock();
        }
    }

    public int getLoad() {
        loadRWLock.readLock().lock();
        try {
            return currentLoad.values().stream().mapToInt(Pair::getLeft).sum();
        } finally {
            loadRWLock.readLock().unlock();
        }
    }

    /**
     * Return and clear all pending request exchanges and costs for this worker.
     */
    public List<Pair<HttpExchange, Integer>> drainPendingExchanges() {
        loadRWLock.writeLock().lock();
        try {
            List<Pair<HttpExchange, Integer>> list = new ArrayList<>();
            for (Pair<Integer, HttpExchange> p : currentLoad.values()) {
                list.add(Pair.of(p.getRight(), p.getLeft()));
            }
            currentLoad.clear();
            return list;
        } finally {
            loadRWLock.writeLock().unlock();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Worker other = (Worker) obj;
        return this.getId().equals(other.getId()) && this.getIp().equals(other.getIp());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ip);
    }
}

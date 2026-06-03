package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.tuple.Pair;

import com.amazonaws.services.ec2.model.Instance;

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
    private final Set<Pair<Long, Integer>> currentLoad = new HashSet<>();

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

    public void updateLoad(long requestId, int cost) {
        loadRWLock.writeLock().lock();
        try {
            currentLoad.add(Pair.of(requestId, cost));
        } finally {
            loadRWLock.writeLock().unlock();
        }
    }

    public void removeLoad(long requestId) {
        loadRWLock.writeLock().lock();
        try {
            currentLoad.removeIf(p -> p.getLeft() == requestId);
        } finally {
            loadRWLock.writeLock().unlock();
        }
    }

    public int getLoad() {
        loadRWLock.readLock().lock();
        try {
            return currentLoad.stream().mapToInt(Pair::getRight).sum();
        } finally {
            loadRWLock.readLock().unlock();
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

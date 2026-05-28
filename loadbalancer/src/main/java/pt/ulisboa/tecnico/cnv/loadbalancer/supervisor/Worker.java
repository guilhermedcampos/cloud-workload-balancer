package pt.ulisboa.tecnico.cnv.loadbalancer.supervisor;

import java.util.Comparator;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Worker {
    public static class CPUComparator implements Comparator<Worker> {
        @Override
        public int compare(Worker w1, Worker w2) {
            return Double.compare(w2.getCpuUsage(), w1.getCpuUsage());
        }
    }

    public static final int HISTORY_RANGE = 15000;
    private static final int CPU_USAGE_HISTORY_SIZE = HISTORY_RANGE / Supervisor.HEALTH_CHECK_INTERVAL;

    private final String id;
    private final String ip;

    private double[] cpuUsageHistory = new double[CPU_USAGE_HISTORY_SIZE];
    private int cpuUsagePointer = 0;
    private ReadWriteLock cpuUsageLock = new ReentrantReadWriteLock();
    private double cpuUsage = 0;

    

    public Worker(String id, String ip){
        this.id  = id;
        this.ip = ip;
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
            cpuUsageHistory[cpuUsagePointer] = cpuUsage;
            cpuUsagePointer = (cpuUsagePointer + 1) % CPU_USAGE_HISTORY_SIZE;

            double sum = 0;
            for (int i=0; i<CPU_USAGE_HISTORY_SIZE; i++) {
                sum += cpuUsageHistory[i];
            }
            this.cpuUsage = sum / CPU_USAGE_HISTORY_SIZE;
        } finally {
            cpuUsageLock.writeLock().unlock();
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
}

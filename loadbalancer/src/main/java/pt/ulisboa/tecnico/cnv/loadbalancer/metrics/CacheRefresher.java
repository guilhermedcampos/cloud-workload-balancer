package pt.ulisboa.tecnico.cnv.loadbalancer.metrics;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CacheRefresher {

    private static final long RECENT_WINDOW_MS = 5 * 60_000L;

    private final DynamoCost dynamo;
    private final MetricsCache fractalsCache;
    private final MetricsCache dnaCache;
    private final MetricsCache grayScottCache;
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    public CacheRefresher(
            DynamoCost dynamo,
            MetricsCache fractalsCache,
            MetricsCache dnaCache,
            MetricsCache grayScottCache) {

        this.dynamo = dynamo;
        this.fractalsCache = fractalsCache;
        this.dnaCache = dnaCache;
        this.grayScottCache = grayScottCache;
    }

    public void recordAccess(String workload, String bucketKey) {
        if (workload == null || bucketKey == null || bucketKey.isBlank()) {
            return;
        }
        lastSeen.put(workload + "|" + bucketKey, System.currentTimeMillis());
        System.out.println("[cacheRefresher] Recorded access for " + workload + " bucketKey=" + bucketKey);
    }

    public void refresh() {
        refreshWorkload("fractals", fractalsCache);
        refreshWorkload("dna", dnaCache);
        refreshWorkload("grayscott", grayScottCache);
    }

    private void refreshWorkload(String workload, MetricsCache cache) {
        long now = System.currentTimeMillis();

        // Iterate a stable snapshot to avoid concurrent-modification issues.
        for (Map.Entry<String, Long> e : new ArrayList<>(lastSeen.entrySet())) {
            String seenKey = e.getKey();
            if (!seenKey.startsWith(workload + "|")) continue;

            long last = e.getValue();
            if (now - last > RECENT_WINDOW_MS) {
                lastSeen.remove(seenKey, last);
                continue;
            }

            String bucketKey = seenKey.substring(workload.length() + 1);
            Integer cost = dynamo.lookupCost(workload, bucketKey);
            if (cost != null) {
                cache.cacheByBucketKey(bucketKey, cost);
                System.out.println("[cacheRefresher] Refreshed cache for " + workload + " bucketKey=" + bucketKey + " with cost=" + cost);
            }
        }
    }
}